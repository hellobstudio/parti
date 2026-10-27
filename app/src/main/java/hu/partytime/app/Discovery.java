package hu.partytime.app;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import hu.partytime.core.Json;

/** Listens for game master beacons on the local network. */
final class Discovery {
    private static final class Found {
        String name, url, ip;
        long seen;
    }

    private final Context ctx;
    private final Map<String, Found> found = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile DatagramSocket sock;
    private WifiManager.MulticastLock lock;

    Discovery(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    synchronized void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("partytime");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {}
        Thread t = new Thread(this::loop, "PartyTime-discovery");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        DatagramSocket s = sock;
        if (s != null) s.close();
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) {}
    }

    private void loop() {
        byte[] buf = new byte[512];
        while (running) {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(NetUtil.BEACON_PORT));
                s.setSoTimeout(1000);
                sock = s;
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!msg.startsWith(NetUtil.BEACON_PREFIX)) continue;
                    String[] parts = msg.split("\\|", 3);
                    if (parts.length < 3) continue;
                    String ip = p.getAddress().getHostAddress();
                    Found f = new Found();
                    f.ip = ip;
                    f.name = parts[2];
                    f.url = "http://" + ip + ":" + parts[1] + "/";
                    f.seen = System.currentTimeMillis();
                    found.put(f.url, f);
                }
                s.close();
            } catch (Exception e) {
                // port busy or network change – retry shortly
                try { Thread.sleep(1500); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** JSON array of games seen in the last few seconds. */
    String json() {
        long now = System.currentTimeMillis();
        List<Object> l = new ArrayList<>();
        for (Found f : found.values()) {
            if (now - f.seen < 6000) l.add(Json.map("name", f.name, "url", f.url, "ip", f.ip));
        }
        return Json.write(l);
    }
}
