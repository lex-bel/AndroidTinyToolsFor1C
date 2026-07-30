package com.alexkmbk.androidtinytools;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.Keep;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Keep
public class HttpServerClass {
    private static final Object SERVER_LOCK = new Object();
    private static SimpleHttpServer activeServer;
    private static PendingRequest pendingRequest;

    private final Activity mContext;
    private final long mV8Object;

    static native void OnHttpRequest(long pObject, String requestJson);

    public HttpServerClass(Activity context, long v8Object) {
        this.mContext = context;
        this.mV8Object = v8Object;
    }

    public boolean startHttpServer(int port, int responseTimeoutMs) {
        try {
            System.loadLibrary("AndroidTinyTools_" + Constants.version);
        } catch (UnsatisfiedLinkError e) {
            Toast.makeText(mContext.getApplicationContext(), e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }

        synchronized (SERVER_LOCK) {
            stopLocked();
            try {
                activeServer = new SimpleHttpServer(mV8Object, port, Math.max(responseTimeoutMs, 1000));
                activeServer.start();
                return true;
            } catch (IOException exception) {
                activeServer = null;
                return false;
            }
        }
    }

    public void stopHttpServer() {
        synchronized (SERVER_LOCK) {
            stopLocked();
        }
    }

    public boolean respond(String responseBody) {
        synchronized (SERVER_LOCK) {
            if (pendingRequest == null) {
                return false;
            }

            pendingRequest.setResponse(responseBody == null ? "" : responseBody);
            pendingRequest = null;
            return true;
        }
    }

    private static void stopLocked() {
        if (pendingRequest != null) {
            pendingRequest.fail("{\"error\":\"server_stopped\"}");
            pendingRequest = null;
        }

        if (activeServer != null) {
            activeServer.shutdown();
            activeServer = null;
        }
    }

    private static final class PendingRequest {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String responseBody = "";

        void setResponse(String value) {
            responseBody = value;
            latch.countDown();
        }

        void fail(String value) {
            responseBody = value;
            latch.countDown();
        }

        boolean await(int timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        String getResponseBody() {
            return responseBody;
        }
    }

    private static final class SimpleHttpServer extends Thread {
        private final long v8Object;
        private final int responseTimeoutMs;
        private final ServerSocket serverSocket;
        private volatile boolean running = true;

        SimpleHttpServer(long v8Object, int port, int responseTimeoutMs) throws IOException {
            super("AndroidTinyToolsHttpServer");
            this.v8Object = v8Object;
            this.responseTimeoutMs = responseTimeoutMs;
            this.serverSocket = new ServerSocket(port);
            this.serverSocket.setReuseAddress(true);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread worker = new Thread(() -> handleClient(socket), "AndroidTinyToolsHttpClient");
                    worker.start();
                } catch (SocketException exception) {
                    if (running) {
                        // ignore until shutdown
                    }
                } catch (IOException exception) {
                    if (running) {
                        // ignore until shutdown
                    }
                }
            }
        }

        void shutdown() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException exception) {
                // ignore
            }
        }

        private void handleClient(Socket socket) {
            try (Socket clientSocket = socket) {
                clientSocket.setSoTimeout(responseTimeoutMs);
                BufferedInputStream inputStream = new BufferedInputStream(clientSocket.getInputStream());
                OutputStream outputStream = clientSocket.getOutputStream();

                HttpRequest request = readRequest(inputStream, clientSocket);
                if (request == null) {
                    writeResponse(outputStream, 400, "Bad Request", "text/plain; charset=UTF-8", "Invalid HTTP request");
                    return;
                }

                if (!"POST".equals(request.method) && !"PUT".equals(request.method)) {
                    writeResponse(outputStream, 405, "Method Not Allowed", "text/plain; charset=UTF-8", "Only POST and PUT are supported");
                    return;
                }

                PendingRequest currentPending;
                synchronized (SERVER_LOCK) {
                    if (pendingRequest != null) {
                        writeResponse(outputStream, 503, "Service Unavailable", "application/json; charset=UTF-8", "{\"error\":\"request_already_processing\"}");
                        return;
                    }

                    currentPending = new PendingRequest();
                    pendingRequest = currentPending;
                }

                try {
                    OnHttpRequest(v8Object, request.toJson());

                    boolean completed = currentPending.await(responseTimeoutMs);
                    if (!completed) {
                        synchronized (SERVER_LOCK) {
                            if (pendingRequest == currentPending) {
                                pendingRequest = null;
                            }
                        }
                        writeResponse(outputStream, 504, "Gateway Timeout", "application/json; charset=UTF-8", "{\"error\":\"response_timeout\"}");
                        return;
                    }

                    writeResponse(outputStream, 200, "OK", "application/json; charset=UTF-8", currentPending.getResponseBody());
                } finally {
                    synchronized (SERVER_LOCK) {
                        if (pendingRequest == currentPending) {
                            pendingRequest = null;
                        }
                    }
                }
            } catch (Exception exception) {
                // ignore broken connections
            }
        }

        private HttpRequest readRequest(InputStream inputStream, Socket socket) throws Exception {
            byte[] headerBytes = readHeaders(inputStream);
            if (headerBytes == null || headerBytes.length == 0) {
                return null;
            }

            String headersText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] headerLines = headersText.split("\\r\\n");
            if (headerLines.length == 0) {
                return null;
            }

            String[] requestLineParts = headerLines[0].split(" ");
            if (requestLineParts.length < 2) {
                return null;
            }

            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < headerLines.length; i++) {
                String line = headerLines[i];
                int delimiterIndex = line.indexOf(':');
                if (delimiterIndex <= 0) {
                    continue;
                }

                String name = line.substring(0, delimiterIndex).trim();
                String value = line.substring(delimiterIndex + 1).trim();
                headers.put(name, value);
            }

            int contentLength = 0;
            String contentLengthHeader = getHeaderIgnoreCase(headers, "Content-Length");
            if (contentLengthHeader != null && !contentLengthHeader.isEmpty()) {
                contentLength = Integer.parseInt(contentLengthHeader);
            }

            byte[] bodyBytes = readBody(inputStream, contentLength);
            Charset bodyCharset = resolveCharset(getHeaderIgnoreCase(headers, "Content-Type"));
            String requestUri = requestLineParts[1];
            String path = requestUri;
            String query = "";
            int queryIndex = requestUri.indexOf('?');
            if (queryIndex >= 0) {
                path = requestUri.substring(0, queryIndex);
                query = requestUri.substring(queryIndex + 1);
            }

            return new HttpRequest(
                    requestLineParts[0].toUpperCase(Locale.ROOT),
                    requestUri,
                    path,
                    query,
                    headers,
                    new String(bodyBytes, bodyCharset),
                    socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : ""
            );
        }

        private byte[] readHeaders(InputStream inputStream) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] delimiter = new byte[]{'\r', '\n', '\r', '\n'};
            int matched = 0;
            int current;

            while ((current = inputStream.read()) != -1) {
                buffer.write(current);
                if (current == delimiter[matched]) {
                    matched++;
                    if (matched == delimiter.length) {
                        byte[] raw = buffer.toByteArray();
                        byte[] result = new byte[raw.length - delimiter.length];
                        System.arraycopy(raw, 0, result, 0, result.length);
                        return result;
                    }
                } else {
                    matched = current == delimiter[0] ? 1 : 0;
                }
            }

            return buffer.toByteArray();
        }

        private byte[] readBody(InputStream inputStream, int contentLength) throws IOException {
            if (contentLength <= 0) {
                return new byte[0];
            }

            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = inputStream.read(body, offset, contentLength - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }

            if (offset == contentLength) {
                return body;
            }

            byte[] actual = new byte[offset];
            System.arraycopy(body, 0, actual, 0, offset);
            return actual;
        }

        private Charset resolveCharset(String contentTypeHeader) {
            if (contentTypeHeader != null) {
                String[] parts = contentTypeHeader.split(";");
                for (String part : parts) {
                    String trimmed = part.trim().toLowerCase(Locale.ROOT);
                    if (trimmed.startsWith("charset=")) {
                        try {
                            return Charset.forName(trimmed.substring("charset=".length()).trim());
                        } catch (Exception exception) {
                            return StandardCharsets.UTF_8;
                        }
                    }
                }
            }

            return StandardCharsets.UTF_8;
        }

        private String getHeaderIgnoreCase(Map<String, String> headers, String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private void writeResponse(OutputStream outputStream, int statusCode, String statusText, String contentType, String responseBody) throws IOException {
            byte[] bodyBytes = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            String headers =
                    "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + bodyBytes.length + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            outputStream.write(headers.getBytes(StandardCharsets.UTF_8));
            outputStream.write(bodyBytes);
            outputStream.flush();
        }
    }

    private static final class HttpRequest {
        private final String method;
        private final String uri;
        private final String path;
        private final String query;
        private final Map<String, String> headers;
        private final String body;
        private final String remoteAddress;

        HttpRequest(String method, String uri, String path, String query, Map<String, String> headers, String body, String remoteAddress) {
            this.method = method;
            this.uri = uri;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
            this.remoteAddress = remoteAddress;
        }

        String toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("method", method);
            json.put("uri", uri);
            json.put("path", path);
            json.put("query", query);
            json.put("data", body);
            json.put("body", body);
            json.put("remote_address", remoteAddress);

            JSONObject headersJson = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                headersJson.put(sanitizeKey(entry.getKey()), entry.getValue());
            }
            json.put("headers", headersJson);
            return json.toString();
        }

        private String sanitizeKey(String value) {
            if (value == null || value.isEmpty()) {
                return "field";
            }

            String normalized = value
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+", "")
                    .replaceAll("_+$", "");

            if (normalized.isEmpty()) {
                return "field";
            }

            char firstChar = normalized.charAt(0);
            if (firstChar >= '0' && firstChar <= '9') {
                normalized = "field_" + normalized;
            }

            return normalized;
        }
    }
}
