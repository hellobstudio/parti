package hu.partytime.app;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import hu.partytime.core.Game;
import hu.partytime.core.PartyServer;

/** Owns the game master's server, the LAN beacon and the autosave. Process-wide singleton. */
final class HostManager {
    private HostManager() {}

    private static PartyServer server;
    private static File saveFile;
    private static volatile boolean dirty;
    private static volatile boolean running;
    private static Thread worker;

    static synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    static synchronized boolean hasSave(Context ctx) {
        return new File(ctx.getFilesDir(), "game.json").exists();
    }

    static synchronized String localUrl() {
        if (!isRunning()) return null;
        return "http://127.0.0.1:" + server.port() + "/?host=" + server.game().hostSecret();
    }

    static synchronized String joinUrl() {
        return isRunning() ? server.joinUrl() : null;
    }

    /** Starts (or returns the already running) server. Returns the local URL for the host's WebView. */
    static synchronized String start(Context ctx, String name, boolean resume) throws IOException {
        if (isRunning()) return localUrl();
        saveFile = new File(ctx.getFilesDir(), "game.json");
        Game game = null;
        if (resume && saveFile.exists()) {
            try {
                game = Game.fromJson(new String(readAll(new FileInputStream(saveFile)), StandardCharsets.UTF_8));
            } catch (Exception e) {
                game = null;
            }
        }
        if (game == null) game = new Game(name);
        final AssetManager am = ctx.getApplicationContext().getAssets();
        PartyServer s = new PartyServer(game, path -> {
            try {
                return readAll(am.open(path));
            } catch (IOException e) {
                return null;
            }
        }, new ZxingQr());
        s.start(NetUtil.HTTP_PORT);
        s.setJoinUrl("http://" + NetUtil.localIp() + ":" + s.port() + "/");
        server = s;
        game.setOnChange(() -> dirty = true);
        dirty = true;
        running = true;
        worker = new Thread(HostManager::workerLoop, "PartyTime-beacon");
        worker.setDaemon(true);
        worker.start();
        return localUrl();
    }

    static synchronized void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        if (server != null) {
            save(server);
            server.stop();
        }
        server = null;
    }

    private static void save(PartyServer s) {
        if (s == null || saveFile == null) return;
        try {
            String json = s.game().toJson();
            File tmp = new File(saveFile.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(saveFile)) {
                saveFile.delete();
                tmp.renameTo(saveFile);
            }
        } catch (Exception ignored) {}
    }

    /** Broadcasts the LAN beacon, refreshes the join URL and autosaves. */
    private static void workerLoop() {
        DatagramSocket sock = null;
        int n = 0;
        while (running) {
            PartyServer s;
            synchronized (HostManager.class) { s = server; }
            if (s == null) break;
            try {
                if (n % 4 == 0) s.setJoinUrl("http://" + NetUtil.localIp() + ":" + s.port() + "/");
                if (sock == null) {
                    sock = new DatagramSocket();
                    sock.setBroadcast(true);
                }
                byte[] msg = (NetUtil.BEACON_PREFIX + s.port() + "|" + s.game().hostName()).getBytes(StandardCharsets.UTF_8);
                for (InetAddress b : NetUtil.broadcastAddresses()) {
                    try {
                        sock.send(new DatagramPacket(msg, msg.length, b, NetUtil.BEACON_PORT));
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                if (sock != null) { sock.close(); sock = null; }
            }
            if (dirty) {
                dirty = false;
                save(s);
            }
            n++;
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (sock != null) sock.close();
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
