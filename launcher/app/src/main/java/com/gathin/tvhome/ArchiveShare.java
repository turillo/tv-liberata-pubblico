package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Wallpapers shared between the TVs of the house, with no server or cloud in between.
 *
 * A TV with "Condividi i miei sfondi" on (Syntax TV, Wallpaper, Sorgenti; off by default) serves its two archives
 * read-only on the home network while the launcher runs: GET /manifest gives both archives' photo ids and states,
 * GET /p/g/ID.jpg and /p/x/ID.jpg give a photo. It announces itself with Android's network service discovery as
 * "_syntaxtv._tcp" under the TV's name. Nothing else is served, and nothing is ever written by a request.
 *
 * A TV with "Prendi da" set to one of those names downloads nothing from the internet: at each of Home's checks (and
 * "Scarica ora") it copies the other TV's archives, photos and state (on or off, favourites, deleted ones, titles),
 * so it shows the same wallpapers. Every step is caught: an unreachable source only means "try next time".
 */
final class ArchiveShare {

    static final String TYPE = "_syntaxtv._tcp.";
    static final int PORT = 8977;
    private static final String TAG = "TvHome.Share";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,160}");
    private static final long MAX_MANIFEST = 4L << 20, MAX_PHOTO = 16L << 20;

    private static ServerSocket server;
    private static NsdManager.RegistrationListener registration;

    private ArchiveShare() {
    }

    /** This TV's name for the other TVs: its own device name, or the model. */
    static String deviceName(Context c) {
        try {
            String n = Settings.Global.getString(c.getContentResolver(), "device_name");
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (RuntimeException ignored) {
            // fall back to the model
        }
        return Build.MODEL;
    }

    // ---- the TV that shares --------------------------------------------------------------------------------

    /** Starts or stops sharing to match Syntax TV's switch. Cheap to call often (Home's onResume). */
    static synchronized void apply(Context context) {
        Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        boolean on;
        try {
            on = SyntaxPrefs.get(c).shareWallpapers();
        } catch (RuntimeException e) {
            on = false;
        }
        if (on && server == null) start(c);
        else if (!on && server != null) stop(c);
    }

    private static void start(Context c) {
        try {
            ServerSocket s = new ServerSocket(PORT);
            server = s;
            Thread t = new Thread(() -> serve(c, s), "wallpaper-share");
            t.setDaemon(true);
            t.start();
            register(c);
            Log.i(TAG, "sharing the wallpapers on port " + PORT);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "sharing not started: " + e.getMessage());
            server = null;
        }
    }

    private static void stop(Context c) {
        try {
            server.close();
        } catch (IOException ignored) {
            // already closed
        }
        server = null;
        NsdManager nsd = c.getSystemService(NsdManager.class);
        if (nsd != null && registration != null) {
            try {
                nsd.unregisterService(registration);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
        registration = null;
        Log.i(TAG, "sharing stopped");
    }

    private static void register(Context c) {
        NsdManager nsd = c.getSystemService(NsdManager.class);
        if (nsd == null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(deviceName(c));
        info.setServiceType(TYPE);
        info.setPort(PORT);
        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo i, int code) {
                Log.w(TAG, "not announced on the network: " + code);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo i, int code) {
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo i) {
                Log.i(TAG, "announced as " + i.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo i) {
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException e) {
            Log.w(TAG, "not announced: " + e.getMessage());
        }
    }

    /** One request at a time: a handful of TVs, a photo now and then. */
    private static void serve(Context c, ServerSocket s) {
        while (!s.isClosed()) {
            try (Socket socket = s.accept()) {
                socket.setSoTimeout(15_000);
                handle(c, socket);
            } catch (IOException | RuntimeException e) {
                if (!s.isClosed()) Log.w(TAG, "request failed: " + e.getMessage());
            }
        }
    }

    private static void handle(Context c, Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String line = readLine(in);
        while (true) {   // the headers: not needed
            String h = readLine(in);
            if (h == null || h.isEmpty()) break;
        }
        OutputStream out = socket.getOutputStream();
        String[] parts = line == null ? new String[0] : line.split(" ");
        if (parts.length < 2 || !"GET".equals(parts[0])) {
            reply(out, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String path = parts[1];
        if ("/manifest".equals(path)) {
            reply(out, 200, "application/json", manifest(c).getBytes(StandardCharsets.UTF_8));
            return;
        }
        File photo = photoFor(c, path);
        if (photo == null) {
            reply(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + photo.length()
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        try (InputStream f = new FileInputStream(photo)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = f.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
    }

    /** /p/g/ID.jpg (general archive) or /p/x/ID.jpg (Christmas), only a bare id that is there; null otherwise. */
    static File photoFor(Context c, String path) {
        String[] p = path.split("/");
        if (p.length != 4 || !p[0].isEmpty() || !"p".equals(p[1]) || !p[3].endsWith(".jpg")) return null;
        boolean xmas;
        if ("g".equals(p[2])) xmas = false;
        else if ("x".equals(p[2])) xmas = true;
        else return null;
        String id = p[3].substring(0, p[3].length() - 4);
        if (!ID.matcher(id).matches()) return null;
        File dir = WallpaperLibrary.archive(c, xmas);
        File f = dir == null ? null : new File(dir, id + ".jpg");
        return f != null && f.isFile() ? f : null;
    }

    private static String manifest(Context c) {
        StringBuilder b = new StringBuilder("{\"name\":");
        quote(b, deviceName(c));
        for (boolean xmas : new boolean[]{false, true}) {
            b.append(xmas ? ",\"xmas\":{\"ids\":[" : ",\"general\":{\"ids\":[");
            boolean first = true;
            for (String id : WallpaperLibrary.ids(WallpaperLibrary.photos(c, xmas))) {
                if (!first) b.append(',');
                quote(b, id);
                first = false;
            }
            b.append("],\"state\":").append(WallpaperLibrary.load(c, xmas).toJson()).append('}');
        }
        return b.append('}').toString();
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(' ');
            else b.append(ch);
        }
        b.append('"');
    }

    private static void reply(OutputStream out, int code, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.0 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0 && ch != '\n') {
            if (b.length() > 2048) throw new IOException("line too long");
            if (ch != '\r') b.append((char) ch);
        }
        return ch < 0 && b.length() == 0 ? null : b.toString();
    }

    // ---- finding the TVs that share ------------------------------------------------------------------------

    /** A TV found on the network: its name and where to reach it. */
    static final class Found {
        final String name, host;
        final int port;

        Found(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * The TVs sharing their wallpapers, found within {@code waitMs} (this one left out). Blocking: call it off the
     * main thread.
     */
    static List<Found> discover(Context context, long waitMs) {
        Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        List<Found> found = new ArrayList<>();
        NsdManager nsd = c.getSystemService(NsdManager.class);
        if (nsd == null) return found;
        List<NsdServiceInfo> seen = new ArrayList<>();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String type, int code) {
                Log.w(TAG, "discovery failed: " + code);
            }

            @Override
            public void onStopDiscoveryFailed(String type, int code) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (seen) {
                    seen.add(info);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "discovery not started: " + e.getMessage());
            return found;
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
                // never started
            }
        }
        String me = deviceName(c);
        Set<String> names = new HashSet<>();
        List<NsdServiceInfo> copy;
        synchronized (seen) {
            copy = new ArrayList<>(seen);
        }
        for (NsdServiceInfo info : copy) {
            String name = info.getServiceName();
            if (name == null || name.equals(me) || !names.add(name)) continue;
            Found f = resolve(nsd, info);
            if (f != null) found.add(f);
        }
        return found;
    }

    /** One service's address; Android resolves one at a time, so this waits for it. */
    private static Found resolve(NsdManager nsd, NsdServiceInfo info) {
        CountDownLatch done = new CountDownLatch(1);
        Found[] out = new Found[1];
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo i, int code) {
                    done.countDown();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo i) {
                    InetAddress a = i.getHost();
                    if (a != null) out[0] = new Found(i.getServiceName(), a.getHostAddress(), i.getPort());
                    done.countDown();
                }
            });
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            Log.w(TAG, "not resolved: " + e.getMessage());
        }
        return out[0];
    }

    // ---- the TV that copies ----------------------------------------------------------------------------------

    /**
     * Copies the chosen TV's archives here (Syntax TV's "Prendi da"). One result, named "sync:NAME". Blocking.
     */
    static WallpaperSources.Result mirror(Context context) {
        Context c = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        String from = SyntaxPrefs.get(c).syncFrom();
        String key = "sync:" + from;
        if (from == null) return new WallpaperSources.Result(key, 0, 0, 0, true, false);
        Found source = null;
        for (Found f : discover(c, 4_000)) if (f.name.equals(from)) source = f;
        if (source == null) {
            Log.i(TAG, from + " not found on the network (off or in standby?)");
            return new WallpaperSources.Result(key, 0, 0, 0, true, false);
        }
        try {
            String base = "http://" + source.host + ":" + source.port;
            Object root = MiniJson.parse(new String(get(base + "/manifest", MAX_MANIFEST), StandardCharsets.UTF_8));
            if (!(root instanceof Map)) throw new IOException("unreadable manifest");
            int added = 0;
            added += mirror(c, base, false, ((Map<?, ?>) root).get("general"));
            added += mirror(c, base, true, ((Map<?, ?>) root).get("xmas"));
            Log.i(TAG, "copied from " + from + ": " + added + " new photos");
            return new WallpaperSources.Result(key, added, 0, 0, false, false);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "copy from " + from + " failed: " + e.getMessage());
            return new WallpaperSources.Result(key, 0, 0, 0, true, false);
        }
    }

    /** One archive: the missing photos come down, the ones the source no longer has go, the state follows. */
    private static int mirror(Context c, String base, boolean xmas, Object part) throws IOException {
        if (!(part instanceof Map)) return 0;
        Object idList = ((Map<?, ?>) part).get("ids");
        Object state = ((Map<?, ?>) part).get("state");
        if (!(idList instanceof List)) return 0;
        Set<String> theirs = new LinkedHashSet<>();
        for (Object o : (List<?>) idList) if (o instanceof String && ID.matcher((String) o).matches()) theirs.add((String) o);
        File dir = WallpaperLibrary.archive(c, xmas);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return 0;
        Set<String> mine = new HashSet<>(WallpaperLibrary.ids(WallpaperLibrary.photos(c, xmas)));
        int added = 0;
        for (String id : theirs) {
            if (mine.contains(id) || Thread.currentThread().isInterrupted()) continue;
            if (save(base + "/p/" + (xmas ? "x" : "g") + "/" + id + ".jpg", dir, id)) added++;
        }
        for (String id : mine) if (!theirs.contains(id)) WallpaperLibrary.remove(c, xmas, id);
        WallpaperState copy = state instanceof Map ? WallpaperState.fromJson(json((Map<?, ?>) state)) : null;
        if (copy != null) {
            WallpaperLibrary.update(c, xmas, s -> {
                s.disabled.clear();
                s.disabled.addAll(copy.disabled);
                s.favorites.clear();
                s.favorites.addAll(copy.favorites);
                s.blacklist.addAll(copy.blacklist);
                s.titles.clear();
                s.titles.putAll(copy.titles);
                s.credits.clear();
                s.credits.putAll(copy.credits);
            });
        }
        return added;
    }

    /** The state object back as JSON for WallpaperState.fromJson. */
    private static String json(Map<?, ?> m) {
        WallpaperState s = new WallpaperState();
        strings(m.get("disabled"), s.disabled);
        strings(m.get("favorites"), s.favorites);
        strings(m.get("blacklist"), s.blacklist);
        pairs(m.get("titles"), s.titles);
        pairs(m.get("credits"), s.credits);
        return s.toJson();
    }

    private static void strings(Object v, Set<String> into) {
        if (v instanceof List) for (Object o : (List<?>) v) if (o instanceof String) into.add((String) o);
    }

    private static void pairs(Object v, Map<String, String> into) {
        if (!(v instanceof Map)) return;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            if (e.getKey() instanceof String && e.getValue() instanceof String) into.put((String) e.getKey(), (String) e.getValue());
        }
    }

    /** A photo saved only when it came down whole and reads as a picture. */
    private static boolean save(String url, File dir, String id) {
        File part = new File(dir, id + ".part"), target = new File(dir, id + ".jpg");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(get(url, MAX_PHOTO));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, id + " not copied: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(part.getPath(), o);
        if (o.outWidth <= 0 || !part.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        }
        return true;
    }

    /**
     * A GET over a plain socket: between two TVs of the house, where Android's HTTP client would refuse cleartext.
     * {@code url} is http://HOST:PORT/PATH as built above.
     */
    private static byte[] get(String url, long limit) throws IOException {
        URL u = new URL(url);
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(u.getHost(), u.getPort()), 5_000);
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + u.getFile() + " HTTP/1.0\r\nHost: " + u.getHost() + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String status = readLine(in);
            if (status == null || !status.contains(" 200 ")) throw new IOException("answer: " + status);
            long length = -1;
            while (true) {
                String h = readLine(in);
                if (h == null || h.isEmpty()) break;
                if (h.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Long.parseLong(h.substring(15).trim());
                }
            }
            if (length > limit) throw new IOException("too large");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (body.size() + n > limit) throw new IOException("too large");
                body.write(buf, 0, n);
            }
            if (length >= 0 && body.size() != length) throw new IOException("incomplete");
            return body.toByteArray();
        }
    }
}
