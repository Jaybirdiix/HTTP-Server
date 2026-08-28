import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ThreadWorker implements Runnable {

    private final SocketChannel client;
    private final HttpConfig config;
    private final ServerState serverState;

    private static final int REQUEST_TIMEOUT_MILLIS = 3000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final ByteArrayOutputStream recvBuffer = new ByteArrayOutputStream();

    public ThreadWorker(SocketChannel client, HttpConfig config, ServerState serverState) {
        this.client = client;
        this.config = config;
        this.serverState = serverState;
    }

    @Override
    public void run() {
        try {
            client.configureBlocking(true);
            try { client.socket().setSoTimeout(REQUEST_TIMEOUT_MILLIS); } catch (Exception ignored) {}

            var socket = client.socket();
            socket.setSoTimeout(REQUEST_TIMEOUT_MILLIS);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            boolean keepAlive = true;

            while (keepAlive) {
                HttpRequest request = readOneRequest(in);
                if (request == null) break;

                boolean requestKeepAlive = shouldKeepAliveFromRequest(request);

                byte[] responseBytes;
                try {
                    responseBytes = RouterAndStatic.buildResponseBytes(
                            request, config, serverState, serverState.accepting.get()
                    );
                } catch (HttpRequestParser.HttpError e) {
                    responseBytes = RouterAndStatic.simpleText(e.code, e.reason, e.body, "close");
                    requestKeepAlive = false;
                } catch (Exception e) {
                    responseBytes = RouterAndStatic.simpleText(
                            500, "Internal Server Error", "Server error\n", "close"
                    );
                    requestKeepAlive = false;
                }

                out.write(responseBytes);
                out.flush();

                boolean responseCloses = responseHasConnectionClose(responseBytes);
                keepAlive = requestKeepAlive && !responseCloses;
            }

        } catch (java.net.SocketTimeoutException e) {
            try {
                OutputStream out = Channels.newOutputStream(client);
                byte[] resp = RouterAndStatic.simpleText(
                        408, "Request Timeout", "Request timed out\n", "close"
                );
                out.write(resp);
                out.flush();
            } catch (Exception ignored) {}
        } catch (HttpRequestParser.HttpError e) {
            try {
                OutputStream out = Channels.newOutputStream(client);
                byte[] resp = RouterAndStatic.simpleText(e.code, e.reason, e.body, "close");
                out.write(resp);
                out.flush();
            } catch (Exception ignored) {}
        } catch (Exception e) {
            try {
                OutputStream out = Channels.newOutputStream(client);
                byte[] resp = RouterAndStatic.simpleText(
                        500, "Internal Server Error", "Server error\n", "close"
                );
                out.write(resp);
                out.flush();
            } catch (Exception ignored) {}
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            serverState.activeConnections.decrementAndGet();
        }
    }

    private HttpRequest readOneRequest(InputStream in) throws IOException {
        discardLeadingNewlines();

        HeaderBoundary hb = readUntilHeadersComplete(in);
        if (hb == null) return null;

        byte[] buf = recvBuffer.toByteArray();
        int headerEnd = hb.headerEndIndex;
        int headerLen = hb.delimiterLength;

        int headerBytesCount = headerEnd + headerLen;
        byte[] headerBytesRaw = slice(buf, 0, headerBytesCount);

        String headerText = new String(headerBytesRaw, StandardCharsets.ISO_8859_1);
        if (hb.isLfOnly) headerText = headerText.replace("\n", "\r\n");

        String[] headerLines = headerText.split("\r\n");
        if (headerLines.length == 0 || headerLines[0].isEmpty()) {
            throw new HttpRequestParser.HttpError(400, "Bad Request", "Missing request line\n");
        }

        String[] requestLineParts = headerLines[0].split(" ");
        if (requestLineParts.length < 3) {
            throw new HttpRequestParser.HttpError(400, "Bad Request", "Malformed request line\n");
        }

        String method = requestLineParts[0];

        int contentLength = 0;
        if (method.equals("POST")) {
            String cl = findHeaderValue(headerLines, "Content-Length");
            if (cl == null || cl.isEmpty()) {
                throw new HttpRequestParser.HttpError(411, "Length Required", "POST requires Content-Length\n");
            }
            try {
                contentLength = Integer.parseInt(cl.trim());
                if (contentLength < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new HttpRequestParser.HttpError(400, "Bad Request", "Invalid Content-Length\n");
            }
        }

        int totalRequestBytes = headerBytesCount + contentLength;
        ensureBytes(in, totalRequestBytes);

        buf = recvBuffer.toByteArray();
        byte[] oneRequestBytes = slice(buf, 0, totalRequestBytes);
        byte[] remainder = slice(buf, totalRequestBytes, buf.length - totalRequestBytes);

        recvBuffer.reset();
        recvBuffer.writeBytes(remainder);

        String raw = new String(oneRequestBytes, StandardCharsets.ISO_8859_1);
        if (hb.isLfOnly) raw = raw.replace("\n", "\r\n");

        return HttpRequestParser.parse(raw);
    }

    private void discardLeadingNewlines() {
        byte[] buf = recvBuffer.toByteArray();
        int i = 0;
        while (i < buf.length && (buf[i] == '\r' || buf[i] == '\n')) i++;
        if (i > 0) {
            recvBuffer.reset();
            recvBuffer.writeBytes(slice(buf, i, buf.length - i));
        }
    }

    private HeaderBoundary readUntilHeadersComplete(InputStream in) throws IOException {
        while (true) {
            byte[] buf = recvBuffer.toByteArray();

            int crlf = indexOf(buf, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            if (crlf >= 0) return new HeaderBoundary(crlf, 4, false);

            int lflf = indexOf(buf, "\n\n".getBytes(StandardCharsets.US_ASCII));
            if (lflf >= 0) return new HeaderBoundary(lflf, 2, true);

            if (buf.length > MAX_HEADER_BYTES) {
                throw new HttpRequestParser.HttpError(431, "Request Header Fields Too Large", "Headers too large\n");
            }

            int n = readSome(in);
            if (n == -1) {
                if (recvBuffer.size() == 0) return null;
                throw new HttpRequestParser.HttpError(400, "Bad Request", "Unexpected EOF\n");
            }
        }
    }

    private void ensureBytes(InputStream in, int nBytes) throws IOException {
        while (recvBuffer.size() < nBytes) {
            int n = readSome(in);
            if (n == -1) throw new HttpRequestParser.HttpError(400, "Bad Request", "Unexpected EOF\n");
        }
    }

    private int readSome(InputStream in) throws IOException {
        byte[] tmp = new byte[4096];
        int n = in.read(tmp);
        if (n > 0) recvBuffer.write(tmp, 0, n);
        return n;
    }

    private static String findHeaderValue(String[] headerLines, String headerName) {
        for (int i = 1; i < headerLines.length; i++) {
            String line = headerLines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            if (name.equalsIgnoreCase(headerName)) return line.substring(colon + 1).trim();
        }
        return null;
    }

    private static boolean shouldKeepAliveFromRequest(HttpRequest request) {
        String version = request.version() == null ? "" : request.version().trim();
        String connectionHeader = request.headers().getOrDefault("connection", "").trim();

        if (version.equals("HTTP/1.1")) {
            return !connectionHeader.equalsIgnoreCase("close");
        } else {
            return connectionHeader.equalsIgnoreCase("keep-alive");
        }
    }

    private static boolean responseHasConnectionClose(byte[] responseBytes) {
        int headerEnd = indexOf(responseBytes, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        int headerLen = 4;
        if (headerEnd < 0) {
            headerEnd = indexOf(responseBytes, "\n\n".getBytes(StandardCharsets.US_ASCII));
            headerLen = 2;
        }
        if (headerEnd < 0) return true;

        String headerText = new String(slice(responseBytes, 0, headerEnd + headerLen), StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);

        return headerText.contains("\r\nconnection: close") || headerText.contains("\nconnection: close");
    }

    private record HeaderBoundary(int headerEndIndex, int delimiterLength, boolean isLfOnly) {}

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return 0;
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] slice(byte[] data, int off, int len) {
        if (len <= 0) return new byte[0];
        byte[] out = new byte[len];
        System.arraycopy(data, off, out, 0, len);
        return out;
    }
}
