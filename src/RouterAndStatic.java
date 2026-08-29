
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

// all for formatting last modified
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.util.ArrayList;

import java.util.Base64;



import java.io.ByteArrayOutputStream;



class RouterAndStatic {

    public static byte[] buildResponseBytes(HttpRequest request, HttpConfig config, ServerState serverState, boolean keepAlive_bool) {

        String keepAlive = keepAlive_bool ? "keep-alive" : "close";

        
        try {

            String path = request.target();
            if (path.isEmpty()) path = "/";
            String urlPath = path.split("\\?", 2)[0];

            String hostHeader = request.headers().getOrDefault("host", "");
            String host = hostHeader.split(":", 2)[0].trim().toLowerCase();

            VirtualHost chosenVirtualHost = chooseVhost(host, config.virtualHosts());
            String docRoot = chosenVirtualHost.documentRoot();

            String queryString = "";
            String[] pathPieces = path.split("\\?", 2);
            if (pathPieces.length == 2) {
                queryString = pathPieces[1];
            }

            // ---------------- LOAD ----------------

            String method= request.method();

            if (method.equals("GET") && urlPath.equals("/load")) {
                if (serverState.accepting.get()) {
                    return simpleText(200, "OK", "OK\n", keepAlive);
                } else {
                    return simpleText(503, "Service Unavailable", "OVERLOADED\n", keepAlive);
                }
            }

            // ---------------- CGI ----------------

            // set env vars
            // REQUEST_METHOD, QUERY_STRING, ETC
            // read stdout from CGI process
            // turn CGI output into a response

            if (CgiHandler.isCgiPath(urlPath)) {

                String relative = urlPath.startsWith("/") ? urlPath.substring(1) : urlPath;
                Path cgiPath = Paths.get(docRoot).resolve(relative).normalize();

                // prevent traversal / escaping docroot
                if (!cgiPath.startsWith(Paths.get(docRoot).normalize())) {
                    return simpleText(403, "Forbidden", "Path traversal blocked\n", keepAlive);
                }

                // find .htaccess if exists
                Path authFile = cgiPath.getParent().resolve(".htaccess");
                if (Files.isRegularFile(authFile)) {
                    AuthConfigParser.AuthConfig authConfig = AuthConfigParser.parse(authFile);

                    String authorizationHeader = request.headers().getOrDefault("authorization", "");
                    
                    if (authorizationHeader.isEmpty()) {
                        return unauthorizedBasic(authConfig.authName, keepAlive);
                    }

                    String[] parts = authorizationHeader.split("\\s+", 2);
                    if (parts.length != 2 || !parts[0].equalsIgnoreCase("Basic") || !authConfig.authType.equalsIgnoreCase("Basic")) {
                        return simpleText(403, "Forbidden", "", keepAlive);
                    }

                    String decoded = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    String[] up = decoded.split(":", 2);
                    if (up.length != 2) {
                        return simpleText(403, "Forbidden", "", keepAlive);
                    }

                    String username = up[0];
                    String password = up[1];

                    if (!(username.equals(authConfig.username) && password.equals(authConfig.password))) {
                        return simpleText(403, "Forbidden", "", keepAlive);
                    }
                }

                // System.out.println("CGI file requested\n");
                return CgiHandler.handle(request, docRoot, urlPath, queryString, host, keepAlive_bool);

            }

            // method check for non cgi -----------------------------------------------
            // System.out.println("method: " + request.method());

            if (!(method.equals("GET"))) {
                return simpleText(405, "Method Not Allowed", "Only GET supported for now !!\n", keepAlive);
            }

        
            // ---------------- FILE EXISTS, USER AGENT ----------------

            String userAgent = request.headers().getOrDefault("user-agent", "");
            boolean wantsMobile = userAgent.toLowerCase(Locale.ROOT).contains("iphone");

            if (urlPath.equals("/") || urlPath.endsWith("/")) {
                // ensure dirPath ends with "/"
                String dirPath = urlPath.equals("/") ? "/" : urlPath;

                String chosenIndex = "index.html";

                if (wantsMobile) {
                    Path mobileCandidate = Path.of(docRoot + dirPath + "index_m.html").normalize();
                    if (Files.exists(mobileCandidate) && Files.isRegularFile(mobileCandidate)) {
                        chosenIndex = "index_m.html";
                    }
                }

                urlPath = dirPath + chosenIndex;
            }

            if (urlPath.contains("..")) {
                return simpleText(403, "Forbidden", "Path traversal blocked\n", keepAlive);
            }

            Path filePath = Path.of(docRoot + urlPath).normalize();
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return simpleText(404, "Not Found", "Not Found\n", keepAlive);
            }

            // ---------------- LAST MODIFIED ----------------

            FileTime modifiedTime = getFileLastModified(filePath);
            String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                .withLocale(Locale.US)
                .format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(modifiedTime.toMillis()), ZoneOffset.UTC));

            

            // ---------------- IF MODIFIED ----------------

            String ifModifiedByDate = request.headers().getOrDefault("if-modified-since", "");

            if (!ifModifiedByDate.isEmpty()) {

                Instant ifModifiedInstant = ZonedDateTime
                    .parse(ifModifiedByDate.trim(),
                        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US))
                    .toInstant();

                Instant fileInstant = modifiedTime.toInstant();

                if (!fileInstant.isAfter(ifModifiedInstant)) {
                    // do NOT add a body here because num bytes in body will be greater than 0
                    // also body should just be empty
                    return simpleText(304, "Not Modified", "", keepAlive);
                }

            }

            // ---------------- SECURITY / AUTH ----------------

            
            Path authFile = filePath.getParent().resolve(".htaccess");
            
            if (Files.isRegularFile(authFile)) {

                // System.out.println("making config");

                AuthConfigParser.AuthConfig authConfig = AuthConfigParser.parse(authFile);

                // System.out.println("Created parser");

                // do security stuff
                String authorizationHeader = request.headers().getOrDefault("authorization", "");
                
                if (authorizationHeader.isEmpty()) {
                    return unauthorizedBasic(authConfig.authName, keepAlive);
                }

                if ((authorizationHeader.split("\\s")[0].equalsIgnoreCase("Basic")) && authConfig.authType.equalsIgnoreCase("Basic")) {

                    String encodedUsernamePassword = authorizationHeader.split("\\s")[1];

                    String decodedUsernamePassword = new String(
                        Base64.getDecoder().decode(encodedUsernamePassword),
                        StandardCharsets.UTF_8
                    );

                    String username = decodedUsernamePassword.split(":")[0];
                    String password = decodedUsernamePassword.split(":")[1];

                    if (!((username.equals(authConfig.username)) && (password.equals(authConfig.password)))) {
                        return simpleText(403, "Forbidden", "", keepAlive);
                    }
                    
                }
                

            }
            


            // ---------------- READ FILE ----------------

            byte[] body = Files.readAllBytes(filePath);
            String contentType = guessContentType(urlPath);

            // ---------------- ACCEPT ----------------

            // Accept usually comes after we know what we want to serve
            String acceptHeader = request.headers().getOrDefault("accept", "");
            Set<String> acceptedTypes = new HashSet<>();

            if (!acceptHeader.isEmpty()) {
                // fill acceptedTypes
                for (String token : acceptHeader.split(",")) {
                    String type = token.split(";", 2)[0].trim();
                    acceptedTypes.add(type);
                }

                if (acceptedTypes.contains("image/jpg") || acceptedTypes.contains("image/jpeg")) {
                    acceptedTypes.add("image/jpg");
                    acceptedTypes.add("image/jpeg");
                }

                // check if what we're about to return is valid
                if (!(acceptedTypes.contains(contentType) || acceptedTypes.contains("*/*"))) {
                    return simpleText(406, "Not Acceptable", "Requested content is not in an acceptable format.\n", keepAlive);
                } else {
                    // System.out.println("Accept content type matches response type.\n");
                }
            }


            String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));

            String headers = 
                "HTTP/1.1 200 OK\r\n" +
                "Date: " + date + "\r\n" +
                "Server: mini-java\r\n" +
                "Last-Modified: " + lastModified + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: " + keepAlive + "\r\n" +
                "\r\n";

            byte[] head = headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

            byte[] combined = new byte[head.length + body.length];
            System.arraycopy(head, 0, combined, 0, head.length);
            System.arraycopy(body, 0, combined, head.length, body.length);
            return combined;
        } catch (Exception e) {
            // e.printStackTrace();
            return simpleText(500, "Internal Server Error", "Server error OOPS my bad :(\n", keepAlive);
            
        }
    }

    private static VirtualHost chooseVhost(String host, List<VirtualHost> vhosts) {
        for (VirtualHost vh : vhosts) {
            if (vh.serverName().equalsIgnoreCase(host)) {
                return vh;
            }
        }
        return vhosts.get(0); // default is first one!
    }

    private static FileTime getFileLastModified(Path path) throws IOException {
        FileTime fileTime;
        fileTime = Files.getLastModifiedTime(path);
        return fileTime;
    }

    private static String guessContentType(String urlPath) {
        String path = urlPath.toLowerCase();
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".txt")) return "text/plain";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }


    static byte[] simpleText(int code, String msg, String body, String keepAlive) {
        byte[] body_bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String headers =
            "HTTP/1.1 " + code + " " + msg + "\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: " + body_bytes.length + "\r\n" +
            "Connection: " + keepAlive + "\r\n" +
            "\r\n";

        byte[] header_bytes = headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] combined = new byte[header_bytes.length + body_bytes.length];
        System.arraycopy(header_bytes, 0, combined, 0, header_bytes.length);
        System.arraycopy(body_bytes, 0, combined, header_bytes.length, body_bytes.length);
        return combined;
    }
    static byte[] unauthorizedBasic(String realm, String keepAlive) {
        if (realm == null || realm.isBlank()) realm = "Restricted";
        realm = realm.replace("\r","").replace("\n","").replace("\"","");

        String headers =
            "HTTP/1.1 401 Unauthorized\r\n" +
            "WWW-Authenticate: Basic realm=\"" + realm + "\"\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: " + keepAlive + "\r\n" +
            "\r\n";

        return headers.getBytes(StandardCharsets.US_ASCII);
    }

}