package com.talacore.talatv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The page-share server (v0.7.0): a small HTTP server the phone
 * runs on the home Wi-Fi so a TV browser can watch what Sandy
 * plays. Plain java.net on purpose — no Android imports — so
 * the desktop harness can run it on a loopback port and prove
 * the wire behavior (token gate, ranges, redirects) for real.
 *
 * Routes:
 *   /watch?k=<token>  the self-contained player page (TvShare)
 *   /media?k=<token>  the media itself:
 *       FILE plan     -> bytes streamed, HTTP Range honored
 *       REDIRECT plan -> 302 to the stream's own address (the
 *                        TV pulls the stream itself; live stays
 *                        live and the phone carries nothing)
 *   anything else     -> 404 in plain words
 * A wrong or missing token answers 403 in plain words — the
 * page is for the household, not the whole street.
 *
 * The media bytes come through the MediaSource seam so the
 * Android side decides what a FILE is (a real file, a content
 * stream, an assembled recording) and this class stays a dumb,
 * honest pipe.
 */
public final class ShareServer {

    public interface MediaSource {
        /** Total bytes, or -1 when unknown (then no ranges). */
        long length() throws IOException;
        String contentType();
        /** Open the body at byte {@code start} (0 = whole body). */
        InputStream open(long start) throws IOException;
        /** Non-null: answer a redirect to this address instead. */
        String redirectTo();
    }

    public interface PageSource {
        String title();
        boolean playable();
    }

    private final String token;
    private final MediaSource media;
    private final PageSource page;
    private volatile ServerSocket socket;
    private volatile boolean running = false;
    private Thread thread;

    public ShareServer(String token, MediaSource media, PageSource page) {
        this.token = token;
        this.media = media;
        this.page = page;
    }

    /** Bind an ephemeral port on all interfaces; returns it. */
    public synchronized int start() throws IOException {
        if (running) return socket.getLocalPort();
        socket = new ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"));
        running = true;
        thread = new Thread(this::acceptLoop, "sandy-share-server");
        thread.setDaemon(true);
        thread.start();
        return socket.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
    }

    public boolean isRunning() { return running; }
    public int port() { return socket == null ? -1 : socket.getLocalPort(); }
    public String token() { return token; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = socket.accept();
                Thread t = new Thread(() -> serve(s), "sandy-share-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    // one bad accept shouldn't kill the server;
                    // loop and try again
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String requestLine = in.readLine();
            if (requestLine == null) { s.close(); return; }
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim()
                        .equalsIgnoreCase("Range")) {
                    range = line.substring(colon + 1).trim();
                }
            }
            String[] parts = requestLine.split(" ");
            String target = parts.length >= 2 ? parts[1] : "/";
            OutputStream out = s.getOutputStream();
            handle(target, range, out);
            out.flush();
            s.close();
        } catch (IOException ignored) {
            try { s.close(); } catch (IOException ignored2) {}
        }
    }

    private void handle(String target, String range, OutputStream out)
            throws IOException {
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        if (!tokenOk(query)) {
            text(out, 403, "Forbidden",
                    "That link isn't the one Sandy is sharing right now.");
            return;
        }
        if (path.equals("/watch")) {
            String html = TvShare.playerPage(page.title(),
                    "/media?k=" + token, page.playable());
            byte[] body = html.getBytes("UTF-8");
            head(out, 200, "OK", "text/html; charset=utf-8", body.length, null);
            out.write(body);
            return;
        }
        if (path.equals("/media")) {
            String redirect = media.redirectTo();
            if (redirect != null) {
                String h = "HTTP/1.1 302 Found\r\nLocation: " + redirect
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(h.getBytes("ISO-8859-1"));
                return;
            }
            long len = media.length();
            long[] r = TvShare.parseRange(range, len);
            long start = r == null ? 0 : r[0];
            long end = r == null ? (len < 0 ? -1 : len - 1) : r[1];
            long count = end < 0 ? -1 : (end - start + 1);
            String extra = null;
            int code = 200;
            if (r != null) {
                code = 206;
                extra = "Content-Range: bytes " + start + "-" + end + "/" + len;
            }
            head(out, code, code == 206 ? "Partial Content" : "OK",
                    media.contentType(), count, extra);
            try (InputStream body = media.open(start)) {
                byte[] buf = new byte[64 * 1024];
                long left = count;
                int n;
                while ((n = body.read(buf, 0,
                        left < 0 ? buf.length
                                : (int) Math.min(buf.length, left))) > 0) {
                    out.write(buf, 0, n);
                    if (left > 0) {
                        left -= n;
                        if (left == 0) break;
                    }
                }
            }
            return;
        }
        text(out, 404, "Not Found",
                "Sandy isn't sharing anything at this address.");
    }

    private boolean tokenOk(String query) {
        if (query == null) return false;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals("k")) {
                return pair.substring(eq + 1).equals(token);
            }
        }
        return false;
    }

    private void head(OutputStream out, int code, String msg, String type,
                      long length, String extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        if (extra != null) sb.append(extra).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
    }

    private void text(OutputStream out, int code, String msg, String bodyText)
            throws IOException {
        byte[] body = (bodyText + "\n").getBytes("UTF-8");
        head(out, code, msg, "text/plain; charset=utf-8", body.length, null);
        out.write(body);
    }
}
