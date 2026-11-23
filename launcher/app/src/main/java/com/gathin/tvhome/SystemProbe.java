package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What the "Sistema" page reads from the TV. Every method is blocking (call it off the main thread) and never
 * throws: what the device does not expose comes back as -1, NaN, null or an empty list, never as a guess.
 */
final class SystemProbe {

    private SystemProbe() {
    }

    // ---- memory and storage ------------------------------------------------------------------------------

    /** {total, available} RAM in bytes; {-1, -1} when Android will not say. */
    static long[] ram(Context c) {
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            return new long[]{info.totalMem, info.availMem};
        } catch (RuntimeException e) {
            return new long[]{-1, -1};
        }
    }

    /** {total, free} bytes of the data partition, where apps and their files live. */
    static long[] disk() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            return new long[]{fs.getTotalBytes(), fs.getAvailableBytes()};
        } catch (RuntimeException e) {
            return new long[]{-1, -1};
        }
    }

    /** Syntax TV's own data, by kind: the parts the page lists and Pulizia can clear. */
    static final class Usage {
        long archive, xmasArchive, scenes, news, thumbs;

        long total() {
            return archive + xmasArchive + scenes + news + thumbs;
        }
    }

    /** The scene caches in files/: regenerable, downloaded again by the screensaver. */
    static final String[] SCENE_DIRS = {"earth", "sun", "moon", "live", "live_sim", "space"};

    static Usage usage(Context c) {
        Usage u = new Usage();
        u.archive = size(WallpaperLibrary.archive(c, false));
        u.xmasArchive = size(WallpaperLibrary.archive(c, true));
        for (String d : SCENE_DIRS) u.scenes += size(new File(c.getFilesDir(), d));
        u.scenes += size(new File(c.getFilesDir(), "iss_tle.txt"));
        u.news = size(new File(c.getFilesDir(), "news.txt"));
        u.thumbs = size(c.getCacheDir());
        return u;
    }

    /** Bytes in a file or folder, recursively; 0 for what is missing or unreadable. */
    static long size(File f) {
        return size(f, 0);
    }

    private static long size(File f, int depth) {
        if (f == null || depth > 8) return 0;
        try {
            if (f.isFile()) return f.length();
            File[] list = f.listFiles();
            if (list == null) return 0;
            long n = 0;
            for (File x : list) n += size(x, depth + 1);
            return n;
        } catch (SecurityException e) {
            return 0;
        }
    }

    // ---- CPU -----------------------------------------------------------------------------------------------

    /** The first line of /proc/stat, or null: Android 8 and later hide it from apps on most TVs. */
    static long[] cpuSample() {
        return SystemStats.parseCpuLine(firstLine(new File("/proc/stat")));
    }

    /** Each core's current frequency in MHz (-1 for a core that does not say); empty when none does. */
    static int[] coreFreqs() {
        int n = Math.max(1, Runtime.getRuntime().availableProcessors());
        int[] f = new int[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            f[i] = SystemStats.parseFreqMhz(firstLine(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq")));
            if (f[i] > 0) any = true;
        }
        return any ? f : new int[0];
    }

    /** The top frequency of core 0 in MHz, or -1. */
    static int maxFreq() {
        return SystemStats.parseFreqMhz(firstLine(new File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")));
    }

    /** A thermal zone the device lets apps read. */
    static final class Temp {
        final String name;
        final float celsius;

        Temp(String name, float celsius) {
            this.name = name;
            this.celsius = celsius;
        }
    }

    static List<Temp> temps() {
        List<Temp> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            File zone = new File("/sys/class/thermal/thermal_zone" + i);
            if (!zone.exists()) {
                if (i > 2) break;
                continue;
            }
            float t = SystemStats.parseTemp(firstLine(new File(zone, "temp")));
            if (Float.isNaN(t)) continue;
            String type = firstLine(new File(zone, "type"));
            out.add(new Temp(type == null || type.trim().isEmpty() ? "zona " + i : type.trim(), t));
        }
        return out;
    }

    // ---- network -------------------------------------------------------------------------------------------

    static final class Net {
        /** "Wi-Fi", "Cavo", other, or null when offline. */
        String type;
        String ip, gateway, ssid;
        /** dBm, or 0 when not on Wi-Fi or unknown. */
        int rssi;
        boolean vpn;
    }

    /**
     * The network the TV uses: the one with internet that is not a VPN (a firewall such as NetGuard is the active
     * network on some TVs, with the Wi-Fi under it).
     */
    static Net net(Context c) {
        Net n = new Net();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network chosen = null;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    n.vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (chosen == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) chosen = net;
            }
            if (chosen == null) return n;
            NetworkCapabilities caps = cm.getNetworkCapabilities(chosen);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) n.type = "Cavo";
            else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) n.type = "Wi-Fi";
            else n.type = "Altra rete";
            LinkProperties lp = cm.getLinkProperties(chosen);
            if (lp != null) {
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) n.ip = a.getAddress().getHostAddress();
                }
                for (RouteInfo r : lp.getRoutes()) {
                    InetAddress g = r.getGateway();
                    if (r.isDefaultRoute() && g instanceof Inet4Address && !g.isAnyLocalAddress()) n.gateway = g.getHostAddress();
                }
            }
            if ("Wi-Fi".equals(n.type)) {
                WifiManager wm = c.getApplicationContext().getSystemService(WifiManager.class);
                WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                if (info != null) {
                    int rssi = info.getRssi();
                    n.rssi = SystemStats.signalLabel(rssi) == null ? 0 : rssi;
                    String ssid = info.getSSID();
                    if (ssid != null && !ssid.contains("unknown ssid") && !ssid.equals(WifiManager.UNKNOWN_SSID)) {
                        n.ssid = ssid.replace("\"", "");
                    }
                }
            }
        } catch (RuntimeException e) {
            // offline as far as this page can tell
        }
        return n;
    }

    /**
     * Round trip to {@code host} in ms: one ping when the system's ping runs for apps, otherwise the time to open a
     * TCP connection to {@code port}. -1 when neither answers in 2 s.
     */
    static float latency(String host, int port) {
        if (host == null) return -1;
        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-c", "1", "-W", "2", host).redirectErrorStream(true).start();
            if (p.waitFor(3, TimeUnit.SECONDS)) {
                float ms = SystemStats.parsePingMs(read(p.getInputStream(), 4096));
                if (ms >= 0) return ms;
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            // no ping for apps here: the TCP connect below
        } finally {
            if (p != null) p.destroy();
        }
        try (Socket s = new Socket()) {
            long t0 = SystemClock.elapsedRealtimeNanos();
            s.connect(new InetSocketAddress(host, port), 2000);
            return (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000f;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Cloudflare's public speed test: the same download its page at speed.cloudflare.com makes. */
    static final String SPEED_URL = "https://speed.cloudflare.com/__down?bytes=";
    static final int SPEED_BYTES = 10_000_000;

    /** Downloads up to 10 MB (at most 20 s) and returns Mbit/s, or -1. Blocking; only on the button. */
    static double speedTest() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(SPEED_URL + SPEED_BYTES).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            long t0 = SystemClock.elapsedRealtime();
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return -1;
            // A changed service (a page instead of the file, or a tiny reply) is "not available", not a speed.
            String type = c.getContentType();
            if (type != null && type.startsWith("text/")) return -1;
            long got = 0;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    got += n;
                    if (SystemClock.elapsedRealtime() - t0 > 20_000 || Thread.currentThread().isInterrupted()) break;
                }
            }
            if (got < 1_000_000) return -1;
            return SystemStats.mbps(got, SystemClock.elapsedRealtime() - t0);
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- device --------------------------------------------------------------------------------------------

    /** Lines for the "Info" box: model, system, patch, Syntax TV, screen, uptime. */
    static List<String[]> info(Context c) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Modello", (Build.MANUFACTURER + " " + Build.MODEL).trim()});
        String os = "Amazon".equalsIgnoreCase(Build.MANUFACTURER) ? "Fire OS · Android " + Build.VERSION.RELEASE : "Android " + Build.VERSION.RELEASE;
        rows.add(new String[]{"Sistema", os + " · API " + Build.VERSION.SDK_INT});
        String version = "?";
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
            // shown as "?"
        }
        // The rows that matter come first: the card shows about four on a 1 GB Fire TV's screen.
        String channel = DeviceConfig.TEST.equals(DeviceConfig.channel(c)) ? "test" : "produzione";
        rows.add(new String[]{"TV Liberata", version + " · " + channel + " · " + BuildConfig.GIT_COMMIT});
        rows.add(new String[]{"Dispositivo", DeviceConfig.name(c)});
        try {
            DisplayMetrics dm = new DisplayMetrics();
            c.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
            rows.add(new String[]{"Schermo", dm.widthPixels + "×" + dm.heightPixels + " · " + dm.densityDpi + " dpi"});
        } catch (RuntimeException ignored) {
            // no row
        }
        rows.add(new String[]{"Acceso da", SystemStats.duration(SystemClock.elapsedRealtime())});
        String patch = Build.VERSION.SECURITY_PATCH;
        rows.add(new String[]{"Patch", patch == null || patch.isEmpty() ? "non indicata" : patch});
        return rows;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    static String firstLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String read(InputStream in, int max) throws IOException {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && b.length() < max) b.append(line).append('\n');
        }
        return b.toString();
    }
}
