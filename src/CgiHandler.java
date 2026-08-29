import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.nio.file.attribute.FileTime;
import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;



final class CgiHandler {

    private CgiHandler() {}


    static byte[] encodeChunk(byte[] rawBodyBytes, int maxChunkSizeInBytes) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < rawBodyBytes.length) {
            int currentChunkSizeBytes = Math.min(maxChunkSizeInBytes, rawBodyBytes.length - offset);

            // <hex>\r\n
            String chunkSizeLine = Integer.toHexString(currentChunkSizeBytes) + "\r\n";
            outputStream.writeBytes(chunkSizeLine.getBytes(StandardCharsets.US_ASCII));
            // <data>\r\n
            outputStream.write(rawBodyBytes, offset, currentChunkSizeBytes);
            outputStream.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            
            
            offset += currentChunkSizeBytes;

        }

        outputStream.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return outputStream.toByteArray();
    }

    static byte[] buildChunkedResponseBytes(int statusCode, String statusMessage, String contentType, String lastModified, byte[] rawBodyBytes, boolean keepAlive) {

        byte[] chunkedBodyBytes = encodeChunk(rawBodyBytes, 4096);

        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));

        // NO Content-Length here
        String headers =
            "HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n" +
            "Date: " + date + "\r\n" +
            "Server: mini-java\r\n" +
            "Content-Type: " + contentType + "\r\n" +
            "Last-Modified: " + lastModified + "\r\n" +
            "Transfer-Encoding: chunked\r\n" + 
            "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n" +
            "\r\n";
        
        byte[] headerBytes = headers.getBytes(StandardCharsets.US_ASCII);

        byte[] responseBytes = new byte[headerBytes.length + chunkedBodyBytes.length];
        System.arraycopy(headerBytes, 0, responseBytes, 0, headerBytes.length);
        System.arraycopy(chunkedBodyBytes, 0, responseBytes, headerBytes.length, chunkedBodyBytes.length);
        return responseBytes;

    }


    static boolean isCgiPath(String urlPath) {
        return urlPath.toLowerCase(Locale.ROOT).endsWith(".cgi");
    }

    // run cgi for get and post
    static byte[] handle(
        HttpRequest request,
        String docRoot,
        String urlPath,
        String queryString,
        String host,
        boolean keepAlive
    ) {

        try {

            String method = request.method().toUpperCase();

            if (!(method.equals("GET") || method.equals("POST"))) {
                // System.out.println("method here is illegal");
                return RouterAndStatic.simpleText(
                    405, "Method Not Allowed",
                    "Only GET and POST supported for CGI\n",
                    keepAlive ? "keep-alive" : "close"
                );

            }

            String relative = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
            Path targetFilePath = Paths.get(docRoot).resolve(relative).normalize().toAbsolutePath();

            if (!Files.exists(targetFilePath) || !Files.isRegularFile(targetFilePath)) {
                return RouterAndStatic.simpleText(404, "Not Found", "Not Found\n", keepAlive ? "keep-alive" : "close");
            }

            // last modified --------------------------
            FileTime modifiedTime = Files.getLastModifiedTime(targetFilePath);
            String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                .withLocale(Locale.US)
                .format(ZonedDateTime.ofInstant(modifiedTime.toInstant(), ZoneOffset.UTC));

            // if modified by -------------------------------
            String ifModifiedByDate = request.headers().getOrDefault("if-modified-since", "");
            if (!ifModifiedByDate.isEmpty()) {
                Instant ifModifiedInstant = ZonedDateTime
                    .parse(ifModifiedByDate.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US))
                    .toInstant();

                Instant targetFileInstant = modifiedTime.toInstant();
                if (!targetFileInstant.isAfter(ifModifiedInstant)) {
                    return R304(lastModified, keepAlive);
                }
            }
            // cont -------------------------------


            ProcessBuilder pb = new ProcessBuilder("/usr/bin/perl", targetFilePath.toString());

            pb.directory(targetFilePath.getParent().toFile());

            // new env
            Map<String, String> env = pb.environment();
            env.clear();

            env.put("REQUEST_METHOD", request.method().toUpperCase(Locale.ROOT));
            env.put("QUERY_STRING", queryString == null ? "" : queryString);
            env.put("SERVER_NAME", host);
            env.put("SCRIPT_NAME", urlPath);


            byte[] body = new byte[0];
            if (method.equals("POST")) {
                body = request.body().orElse(new byte[0]);

                String contentType = request.headers().getOrDefault("content-type", "");
                String contentLength = request.headers().getOrDefault("content-length", "");

                env.put("CONTENT_TYPE", contentType);
                env.put("CONTENT_LENGTH", contentLength.isEmpty() ? Integer.toString(body.length) : contentLength);
            }

            Process p = pb.start();

            if (method.equals("POST")) {
                p.getOutputStream().write(body);
            }
            p.getOutputStream().close();

            // cgi writes out to this, server reads it
            byte[] cgiOut = p.getInputStream().readAllBytes();
            byte[] cgiErr = p.getErrorStream().readAllBytes();

            int exit = p.waitFor(); // blocks until process fully exits
           
            // if we don't exit cleanly
            if (exit != 0) {
                System.out.println("CGI exit code: " + exit);
                System.out.println("CGI stderr:\n" + new String(cgiErr, StandardCharsets.UTF_8));
                return RouterAndStatic.simpleText(500, "Internal Server Error", "CGI failed\n", keepAlive ? "keep-alive" : "close");
            }

            return translateCgiOutputToHttpResponse(cgiOut, lastModified, request, keepAlive);



        } catch (Exception e) {
            e.printStackTrace();
            return RouterAndStatic.simpleText(500, "Internal Server Error", "CGI error\n", keepAlive ? "keep-alive" : "close");
        }
    }

    private static byte[] translateCgiOutputToHttpResponse(byte[] cgiOut, String lastModified, HttpRequest request, boolean keepAlive) {
        // turn array of bytes into a string
        String cgiText = new String(cgiOut, StandardCharsets.ISO_8859_1);
        int endOfHeaders = cgiText.indexOf("\r\n\r\n");
        // if it doesn't exist, problem !
        if (endOfHeaders < 0) {
            return RouterAndStatic.simpleText(500, "Internal Server Error", "Bad CGI output\n", keepAlive ? "keep-alive" : "close");
        }


        String cgiHeaderBlock = cgiText.substring(0, endOfHeaders);
        // copy the body, - the returns and new values
        byte[] cgiBodyBytes = Arrays.copyOfRange(cgiOut, endOfHeaders + 4, cgiOut.length);

        // check what kind of content CGI is returning
        String contentType = "text/plain";
        for (String line : cgiHeaderBlock.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            // name value for headers
            // same as in main file basically
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-type")) {
                contentType = value;
            }
        }

        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));

        return buildChunkedResponseBytes(200, "OK", contentType, lastModified, cgiBodyBytes, keepAlive);


    }

    private static byte[] R304(String lastModified, boolean keepAlive) {
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        String headers =
            "HTTP/1.1 304 Not Modified\r\n" +
            "Date: " + date + "\r\n" +
            "Server: mini-java\r\n" +
            "Last-Modified: " + lastModified + "\r\n" +
            "Connection: " + (keepAlive ? "keep-alive" : "close") + "\r\n" +
            "\r\n";
        return headers.getBytes(StandardCharsets.US_ASCII);
    }
}
