package hu.partytime.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class NetUtil {
    private NetUtil() {}

    static final int HTTP_PORT = 8765;
    static final int BEACON_PORT = 47777;
    static final String BEACON_PREFIX = "PARTYTIME|";

    private static boolean preferred(String name) {
        name = name == null ? "" : name.toLowerCase();
        return name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
                || name.startsWith("softap") || name.startsWith("eth") || name.startsWith("rndis");
    }

    /** Best guess of this phone's LAN IPv4 address (wifi or hotspot). */
    static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (preferred(ni.getName()) && a.isSiteLocalAddress()) return a.getHostAddress();
                    if (a.isSiteLocalAddress() && fallback == null) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return fallback != null ? fallback : "127.0.0.1";
    }

    /** All IPv4 broadcast addresses (plus the global one). */
    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> l = new ArrayList<>();
        try {
            l.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !l.contains(b)) l.add(b);
                }
            }
        } catch (Exception ignored) {}
        return l;
    }
}
