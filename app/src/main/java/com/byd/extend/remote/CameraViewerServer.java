package com.byd.extend.remote;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Loopback-only HTTP endpoint behind the authenticated native tunnel. */
public final class CameraViewerServer implements AutoCloseable {
    public interface Stream {
        void connected();
        void disconnected();
        String status();
        int unavailableChargeMask();
        FragmentedVideoWindow video();
        byte[] resource(String name) throws IOException;
    }
    private final ViewerAccess access;
    private final Stream stream;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(2, 4, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> new Thread(r, "camera-view-http"));
    private final Thread listener;
    private volatile boolean closed;
    private long lastAuthenticationNs = System.nanoTime() - 1_000_000_000L;

    public CameraViewerServer(String password, String privateProxyProof, Stream stream) throws IOException {
        this.access = new ViewerAccess(password, privateProxyProof);
        this.stream = stream;
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(1000);
        listener = new Thread(this::listen, "camera-view-listen");
        listener.start();
    }

    public int port() { return server.getLocalPort(); }
    private static long now() { return System.nanoTime() / 1_000_000; }

    private void listen() {
        try {
            while (!closed) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    try { requests.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException busy) { sockets.remove(socket); socket.close(); }
                } catch (SocketTimeoutException ignored) { /* Bounded abandoned-viewer cleanup. */ }
                synchronized (access) {
                    if (access.expire(now())) stream.disconnected();
                }
            }
        } catch (IOException ignored) {
            if (!closed) stream.disconnected();
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5_000);
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4 && headers.size() < 16_384) {
                int value = input.read();
                if (value < 0) return;
                headers.write(value);
                matched = value == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : 0;
            }
            if (matched != 4) { respond(socket, 431, "text/plain", new byte[0], null); return; }
            String[] lines = headers.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length != 3 || !(first[0].equals("GET") || first[0].equals("POST"))) {
                respond(socket, 405, "text/plain", new byte[0], null); return;
            }
            Map<String, String> fields = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) throw new IOException("Malformed HTTP field");
                String key = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
                if (fields.put(key, lines[i].substring(colon + 1).trim()) != null)
                    throw new IOException("Duplicate HTTP field");
            }
            if (fields.containsKey("transfer-encoding")) throw new IOException("Chunked requests unsupported");
            int length = Integer.parseInt(fields.getOrDefault("content-length", "0"));
            if (length < 0 || length > 4096) throw new IOException("Request body too large");
            byte[] body = new byte[length];
            new DataInputStream(input).readFully(body);
            String path = first[1];
            if (!path.startsWith("/") || path.contains("?") || path.contains("%") || path.contains(".."))
                throw new IOException("Invalid viewer path");
            String session = cookie(fields.get("cookie"));
            String origin = fields.get("origin");
            if (first[0].equals("POST") && !Objects.equals(origin, "https://" + fields.get("host"))) {
                respond(socket, 403, "text/plain", new byte[0], null); return;
            }
            if (path.equals("/session") && first[0].equals("POST")) {
                boolean admitted;
                synchronized (this) {
                    long nano = System.nanoTime();
                    admitted = nano - lastAuthenticationNs >= 1_000_000_000L;
                    if (admitted) lastAuthenticationNs = nano;
                }
                if (!admitted) { json(socket, 429, "retry", null); return; }
                String password = new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("password", "");
                if (!access.authenticate(password, fields.get("x-byd-extend-proxy-token"))) {
                    json(socket, 401, "unauthorized", null); return;
                }
                String key;
                synchronized (access) {
                    boolean wasOccupied = access.occupied();
                    key = access.admit(session, now());
                    if (key != null && !wasOccupied) stream.connected();
                }
                if (key == null) { json(socket, 409, "busy", null); return; }
                json(socket, 200, "preparing", "viewer=" + key + "; Path=/; Secure; HttpOnly; SameSite=Strict");
                return;
            }
            if (path.equals("/") && first[0].equals("GET")) {
                // This resource contains only the login shell. Video/assets/control require its session.
                respond(socket, 200, "text/html; charset=utf-8", stream.resource("index.html"), null);
                return;
            }
            if (!access.accepts(session, now())) { json(socket, 401, "unauthorized", null); return; }
            if (path.equals("/stop") && first[0].equals("POST")) {
                synchronized (access) {
                    if (access.release(session)) stream.disconnected();
                }
                json(socket, 200, "ended", "viewer=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0");
            } else if (path.equals("/status")) {
                byte[] status = new JSONObject().put("status", stream.status())
                        .put("missingCharge", stream.unavailableChargeMask())
                        .toString().getBytes(StandardCharsets.UTF_8);
                respond(socket, 200, "application/json", status, null);
            } else if (path.equals("/hls.js")) {
                respond(socket, 200, "application/javascript", stream.resource("hls.js"), null);
            } else {
                FragmentedVideoWindow window = stream.video();
                byte[] data = null;
                String mime = "video/mp4";
                if (window != null) {
                    if (path.equals("/live.m3u8")) {
                        String playlist = window.playlist();
                        data = playlist == null ? null : playlist.getBytes(StandardCharsets.UTF_8);
                        mime = "application/vnd.apple.mpegurl";
                    } else if (path.equals("/init.mp4")) data = window.initialization();
                    else if (path.startsWith("/segment/") && path.endsWith(".m4s"))
                        data = window.segment(Long.parseLong(path.substring(9, path.length() - 4)));
                }
                respond(socket, data == null ? 404 : 200, mime, data == null ? new byte[0] : data, null);
            }
        } catch (Exception ignored) {
            // Malformed peers affect only their request. Never log passwords, cookies or provider URLs.
        } finally { sockets.remove(socket); }
    }

    private static String cookie(String header) {
        if (header == null) return null;
        for (String item : header.split(";")) {
            String value = item.trim();
            if (value.startsWith("viewer=")) return value.substring(7);
        }
        return null;
    }

    private static void json(Socket socket, int status, String phase, String cookie) throws IOException {
        byte[] bytes;
        try { bytes = new JSONObject().put("status", phase).toString().getBytes(StandardCharsets.UTF_8); }
        catch (org.json.JSONException invalid) { throw new IOException(invalid); }
        respond(socket, status, "application/json", bytes, cookie);
    }

    private static void respond(Socket socket, int status, String mime, byte[] body, String cookie) throws IOException {
        OutputStream out = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + " Response\r\nContent-Type: " + mime
                + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"
                + "\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'self'; "
                + "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                + "media-src 'self' blob:; worker-src 'self' blob:; frame-ancestors 'none'"
                + (cookie == null ? "" : "\r\nSet-Cookie: " + cookie) + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }

    @Override public void close() {
        closed = true; access.revoke();
        try { server.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        requests.shutdownNow();
        stream.disconnected();
    }
}
