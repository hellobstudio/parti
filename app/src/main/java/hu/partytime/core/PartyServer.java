package hu.partytime.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny dependency-free HTTP/1.1 server serving the web UI and the JSON API. */
public final class PartyServer {

    public interface Assets {
        /** @return file content or null if missing */
        byte[] read(String path) throws IOException;
    }

    public interface QrRenderer {
        String svg(String text) throws Exception;
    }

    private final Game game;
    private final Assets assets;
    private final QrRenderer qr;
    private volatile String joinUrl = "";
    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private int port;

    public PartyServer(Game game, Assets assets, QrRenderer qr) {
        this.game = game;
        this.assets = assets;
        this.qr = qr;
    }

    public Game game() { return game; }
    public int port() { return port; }
    public boolean isRunning() { return running; }
    public void setJoinUrl(String url) { this.joinUrl = url == null ? "" : url; }
    public String joinUrl() { return joinUrl; }

    /** Binds to the first free port starting at {@code preferredPort}. */
    public synchronized void start(int preferredPort) throws IOException {
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 20; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p), 64);
                serverSocket = s;
                port = p;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (serverSocket == null) throw last != null ? last : new IOException("No free port");
        running = true;
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(this::acceptLoop, "PartyServer-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    // ------------------------------------------------------------ HTTP plumbing
    private void handle(Socket s) {
        try {
            s.setSoTimeout(10000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            // keep-alive loop: serve several requests on one connection
            for (int n = 0; n < 100; n++) {
                Request req = readRequest(in);
                if (req == null) break;
                boolean keepAlive = !"close".equalsIgnoreCase(req.headers.get("connection"));
                Response res;
                try {
                    res = route(req);
                } catch (Exception e) {
                    res = json(500, Json.map("error", "Szerverhiba: " + e.getMessage()));
                }
                write(out, res, keepAlive);
                if (!keepAlive) break;
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    static final class Response {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body = new byte[0];
        boolean cache;
    }

    private static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > 16384) throw new IOException("Header too large");
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = (b == '\r') ? 1 : 0;
        }
        String[] lines = head.toString("UTF-8").split("\r\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        if (first.length < 2) throw new IOException("Bad request line");
        Request r = new Request();
        r.method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];
        int q = target.indexOf('?');
        if (q >= 0) {
            r.query = target.substring(q + 1);
            target = target.substring(0, q);
        }
        r.path = URLDecoder.decode(target, "UTF-8");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) r.headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        int len = 0;
        try { len = Integer.parseInt(r.headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) {}
        if (len > 65536) throw new IOException("Body too large");
        if (len > 0) {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int k = in.read(buf, off, len - off);
                if (k < 0) throw new IOException("Unexpected EOF");
                off += k;
            }
            r.body = new String(buf, StandardCharsets.UTF_8);
        }
        return r;
    }

    private static void write(OutputStream out, Response res, boolean keepAlive) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(res.status).append(res.status == 200 ? " OK" : " Error").append("\r\n");
        h.append("Content-Type: ").append(res.type).append("\r\n");
        h.append("Content-Length: ").append(res.body.length).append("\r\n");
        h.append("Cache-Control: ").append(res.cache ? "max-age=60" : "no-store").append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.write(res.body);
        out.flush();
    }

    private static Response json(int status, Object o) {
        Response r = new Response();
        r.status = status;
        r.body = Json.write(o).getBytes(StandardCharsets.UTF_8);
        return r;
    }

    private static String param(String query, String name) {
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(name)) {
                try { return URLDecoder.decode(kv.substring(e + 1), "UTF-8"); } catch (Exception ex) { return null; }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ routing
    private Response route(Request req) throws Exception {
        String p = req.path;
        if (req.method.equals("OPTIONS")) return json(200, Json.map());
        if (p.startsWith("/api/")) {
            try {
                switch (p) {
                    case "/api/ping":
                        return json(200, Json.map("app", "PartyTime", "host", game.hostName()));
                    case "/api/join": {
                        Map<String, Object> b = Json.parseObject(req.body);
                        return json(200, game.join(Json.str(b, "name", "")));
                    }
                    case "/api/state": {
                        String secret = param(req.query, "s");
                        Map<String, Object> v = game.view(secret, joinUrl);
                        v.put("serverTime", System.currentTimeMillis());
                        return json(200, v);
                    }
                    case "/api/action": {
                        Map<String, Object> b = Json.parseObject(req.body);
                        String msg = game.action(Json.str(b, "s", null), b);
                        return json(200, Json.map("ok", true, "message", msg));
                    }
                    case "/api/qr.svg": {
                        Response r = new Response();
                        r.type = "image/svg+xml";
                        String svg = qr == null ? null : qr.svg(joinUrl);
                        if (svg == null) {
                            r.status = 404;
                            svg = "<svg xmlns='http://www.w3.org/2000/svg'/>";
                        }
                        r.body = svg.getBytes(StandardCharsets.UTF_8);
                        return r;
                    }
                    default:
                        return json(404, Json.map("error", "Nincs ilyen végpont"));
                }
            } catch (Game.GameException e) {
                if ("unknown".equals(e.getMessage())) return json(401, Json.map("error", "unknown"));
                return json(400, Json.map("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                return json(400, Json.map("error", "Hibás kérés"));
            }
        }
        // static files
        String file = p.equals("/") ? "index.html" : p.substring(1);
        if (!file.matches("[A-Za-z0-9._-]+") || file.contains("..")) return json(404, Json.map("error", "not found"));
        byte[] data = assets.read("web/" + file);
        if (data == null) return json(404, Json.map("error", "not found"));
        Response r = new Response();
        r.body = data;
        r.type = mime(file);
        return r;
    }

    private static String mime(String f) {
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
