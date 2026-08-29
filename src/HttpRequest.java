
import java.util.*;
import java.nio.charset.StandardCharsets;

public record HttpRequest(String method, String target, String version, Map<String, String> headers, Optional<byte[]> body) {}

class HttpRequestParser {

    public static class NeedMoreData extends RuntimeException {}


    public static HttpRequest parse(String raw) {

        if (raw == null) {
            throw new HttpError(400, "Bad Request", "Empty request\n");
        }

        // separate the headers
        String[] parts = raw.split("\r\n\r\n", 2);
        String headerBlock = parts[0];
        String[] lines = headerBlock.split("\r\n");
        String bodyBlock = (parts.length == 2) ? parts[1] : "";

        if (lines.length == 0) {
            throw new HttpError(400, "Bad Request", "Missing request line\n");
        }

        // this holds GET, /index.html, HTTP/1.1
        String[] requestLine = lines[0].split(" ", -1);

        // avoid a space counting 
        if (requestLine.length != 3 ||
            requestLine[0].isEmpty() ||
            requestLine[1].isEmpty() ||
            requestLine[2].isEmpty()) {
            throw new HttpError(400, "Bad Request", "Malformed request line\n");
        }

        // if it's malformed
        String method = requestLine.length > 0 ? requestLine[0] : "";
        String target = requestLine.length > 1 ? requestLine[1] : "";
        String version = requestLine.length > 2 ? requestLine[2] : "";

        // iterate through the rest of the headers
        Map<String,String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String name = lines[i].substring(0, colon).trim().toLowerCase();
            String value = lines[i].substring(colon + 1).trim();
            headers.put(name, value);
        }

        Optional<byte[]> body = Optional.empty();

        if (method.equals("POST")) {
            int contentLength = 0;
            
            String contentLengthValue = headers.getOrDefault("content-length", "").trim();
            
            if (contentLengthValue.isEmpty()) {
                throw new HttpError(411, "Length Required", "POST requires Content-Length\n");
            }

            // actually get content length
            try {
                contentLength = Integer.parseInt(contentLengthValue);
                if (contentLength < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new HttpError(400, "Bad Request", "Invalid Content-Length\n");
            }


            // this should always be true
            if (contentLength > 0) {
                byte[] bodyBytes = bodyBlock.getBytes(StandardCharsets.ISO_8859_1);

                if (bodyBytes.length < contentLength) {
                    throw new NeedMoreData();
                }

                // removing trailing
                if (bodyBytes.length > contentLength) {
                    bodyBytes = Arrays.copyOf(bodyBytes, contentLength);
                }

                body = Optional.of(bodyBytes);
            } else {
                    // explicitly present but empty body
                    body = Optional.of(new byte[0]);
            }


            
        }

        return new HttpRequest(method, target, version, headers, body);
    }


    public static class HttpError extends RuntimeException {
        public final int code;
        public final String reason;
        public final String body;

        public HttpError(int code, String reason, String body) {
            super(code + " " + reason);
            this.code = code;
            this.reason = reason;
            this.body = body;
        }
    }

}