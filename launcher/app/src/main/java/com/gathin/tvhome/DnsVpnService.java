package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Liberata's DNS filter: a local VPN that carries only DNS. The VPN gives Android one DNS server, an address
 * that exists only inside the tunnel; every lookup arrives here, blocked names get "no such domain", the others go
 * to the network's own DNS servers (through a socket outside the tunnel) and their answers come back. No other
 * traffic enters the tunnel, so streaming is untouched. Lists: the bundled base and OTA lists (dumbtv's), HaGeZi's
 * Amazon native tracker list refreshed weekly, and the exceptions set in Impostazioni TV Liberata, Sistema, Filtro.
 * Started by FilterSettings (the switch), at boot (HomeRestore's boot broadcast) and by Android's always-on VPN.
 */
public final class DnsVpnService extends VpnService {

    private static final String TAG = "TvHome.DnsFilter";
    static final String ACTION_STOP = "com.gathin.tvhome.DNS_STOP";
    private static final String TUN_IP = "10.111.222.1", DNS_IP = "10.111.222.2";
    static final String HAGEZI_URL = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt";
    static final long LIST_EVERY = 7 * 24 * 3_600_000L;
    /** Always resolving, whatever a list says: the TV needs them (dumbtv's notes) and Prime Video, Alexa, the Appstore. */
    static final List<String> KEEP = Collections.unmodifiableList(Arrays.asList(
            "atv-ext.amazon.com", "ktpx.amazon.com", "a4k.amazon.com", "m.media-amazon.com", "ecx.images-amazon.com",
            "appstore-tv-prod-na.amazon.com", "mas-ext.amazon.com", "mas-sdk.amazon.com", "aiv-delivery.net",
            "aiv-cdn.net", "device-messaging-na.amazon.com", "amazonalexa.com", "api.amazon.com", "www.amazon.com",
            "googlevideo.com", "youtube.com", "ytimg.com", "github.com", "objects.githubusercontent.com",
            "api.github.com", "release-assets.githubusercontent.com"));

    private static volatile DnsVpnService running;
    private ParcelFileDescriptor tun;
    private Thread reader;
    private final ExecutorService forward = Executors.newFixedThreadPool(4);
    private volatile DnsFilter filter = new DnsFilter();
    private volatile InetAddress[] upstream = new InetAddress[0];
    private final Object statsLock = new Object();
    private long blockedCount, totalCount, lastFlush;
    private final LinkedHashMap<String, Integer> top = new LinkedHashMap<>();
    private final List<String> recent = new ArrayList<>();

    static boolean isRunning() {
        return running != null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PowerOnOpen.listen(this);   // "Apri TV Liberata all'accensione" needs a living process: this one
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (tun == null) start();
        return START_STICKY;
    }

    private void start() {
        upstream = upstreamServers(this);
        filter = load(this);
        try {
            Builder b = new Builder().setSession(getString(R.string.filter_title)).setMtu(1500)
                    .addAddress(TUN_IP, 32).addDnsServer(DNS_IP).addRoute(DNS_IP, 32).setBlocking(true);
            try {
                b.addDisallowedApplication(getPackageName());   // our own lookups (Updater, lists) go direct
            } catch (Exception ignored) {
                // still fine: the filter forwards them
            }
            tun = b.establish();
        } catch (RuntimeException e) {
            Log.w(TAG, "VPN not started: " + e.getMessage());
        }
        if (tun == null) {
            Log.w(TAG, "VPN not allowed yet: consent missing");
            stopSelf();
            return;
        }
        running = this;
        reader = new Thread(this::loop, "dns-filter");
        reader.start();
        Log.i(TAG, "filter on: " + filter.size() + " domains, upstream " + Arrays.toString(upstream));
        forward.execute(this::refreshLists);
    }

    private void loop() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tun.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tun.getFileDescriptor())) {
            while (!Thread.currentThread().isInterrupted()) {
                int n = in.read(buf);
                if (n <= 0) continue;
                DnsFilter.Query q = DnsFilter.parse(buf, n);
                if (q == null) continue;
                boolean block = filter.blocks(q.name);
                count(q.name, block);
                if (block) {
                    byte[] nx = DnsFilter.nxdomain(q.dns);
                    if (nx != null) write(out, DnsFilter.reply(q, nx, nx.length));
                } else {
                    forward.execute(() -> resolve(out, q));
                }
            }
        } catch (IOException e) {
            if (running == this) Log.w(TAG, "tunnel closed: " + e.getMessage());
        }
    }

    private void resolve(FileOutputStream out, DnsFilter.Query q) {
        InetAddress[] servers = upstream;
        if (servers.length == 0) servers = fallback();
        for (InetAddress server : servers) {
            try (DatagramSocket s = new DatagramSocket()) {
                protect(s);   // outside the tunnel
                s.setSoTimeout(4000);
                s.send(new DatagramPacket(q.dns, q.dns.length, server, 53));
                byte[] r = new byte[4096];
                DatagramPacket p = new DatagramPacket(r, r.length);
                s.receive(p);
                write(out, DnsFilter.reply(q, r, p.getLength()));
                return;
            } catch (IOException e) {
                // next server
            }
        }
    }

    private static synchronized void write(FileOutputStream out, byte[] packet) {
        try {
            out.write(packet);
        } catch (IOException ignored) {
            // tunnel gone
        }
    }

    private void stop() {
        running = null;
        if (reader != null) reader.interrupt();
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {
            // closed
        }
        tun = null;
        flush(true);
    }

    @Override
    public void onRevoke() {
        // Another VPN took over, or the user withdrew the consent: the switch goes off.
        FilterSettings.prefs(this).edit().putBoolean(FilterSettings.KEY_ON, false).apply();
        stop();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        PowerOnOpen.stop(this);
        stop();
        forward.shutdownNow();
        super.onDestroy();
    }

    // ---- lists ------------------------------------------------------------------------------------------------

    /** The filter as the settings say: base list, OTA list when blocked, HaGeZi's, minus KEEP and the exceptions. */
    static DnsFilter load(Context c) {
        DnsFilter f = new DnsFilter();
        f.addList(raw(c, R.raw.dns_base));
        if (FilterSettings.ota(c)) f.addList(raw(c, R.raw.dns_ota));
        f.addList(read(new File(c.getFilesDir(), "dns_hagezi.txt")));
        Set<String> allow = new HashSet<>(KEEP);
        allow.addAll(FilterSettings.exceptions(c));
        f.setAllowed(allow);
        return f;
    }

    /** Settings changed (Filtro screen): the running filter takes the new lists at once. */
    static void reload(Context c) {
        DnsVpnService s = running;
        if (s != null) s.filter = load(c);
    }

    /** Sistema, Servizi, "Riprova": the lists again now (the running filter takes them; without it, next start). */
    static void refreshNow(Context c) {
        FilterSettings.prefs(c).edit().putLong("lists_updated", 0).apply();
        DnsVpnService s = running;
        if (s != null) s.refreshLists();
        else ServiceStatus.fail(c, ServiceStatus.FILTER_LISTS, "filtro spento");
    }

    private void refreshLists() {
        SharedPreferences p = FilterSettings.prefs(this);
        if (System.currentTimeMillis() - p.getLong("lists_updated", 0) < LIST_EVERY) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(HAGEZI_URL).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "TVLiberata/" + BuildConfig.VERSION_NAME);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            String text;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() > 4_000_000) throw new IOException("lista troppo grande");
                    out.write(buf, 0, n);
                }
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                c.disconnect();
            }
            DnsFilter check = new DnsFilter();
            check.addList(text);
            if (check.size() < 10) throw new IOException("lista vuota");
            File f = new File(getFilesDir(), "dns_hagezi.txt"), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) throw new IOException("lista non salvata");
            p.edit().putLong("lists_updated", System.currentTimeMillis()).apply();
            filter = load(this);
            Log.i(TAG, "lists updated: " + filter.size() + " domains");
            ServiceStatus.ok(this, ServiceStatus.FILTER_LISTS);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "lists not updated: " + e.getMessage());
            ServiceStatus.fail(this, ServiceStatus.FILTER_LISTS, e.getMessage() == null ? "errore" : e.getMessage());
        }
    }

    static String raw(Context c, int id) {
        try (InputStream in = c.getResources().openRawResource(id)) {
            return readAll(in);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String read(File f) {
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- upstream ---------------------------------------------------------------------------------------------

    /** The network's own DNS servers (IPv4), read before the VPN is up; none if Android will not say. */
    private static InetAddress[] upstreamServers(Context c) {
        List<InetAddress> out = new ArrayList<>();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
            if (lp != null) for (InetAddress a : lp.getDnsServers()) {
                if (a instanceof Inet4Address && !a.getHostAddress().equals(DNS_IP)) out.add(a);
            }
        } catch (RuntimeException ignored) {
            // fallback below
        }
        return out.toArray(new InetAddress[0]);
    }

    private static InetAddress[] fallback() {
        try {
            return new InetAddress[]{InetAddress.getByName("1.1.1.1"), InetAddress.getByName("9.9.9.9")};
        } catch (IOException e) {
            return new InetAddress[0];
        }
    }

    // ---- statistics -------------------------------------------------------------------------------------------

    private void count(String name, boolean blocked) {
        synchronized (statsLock) {
            totalCount++;
            if (blocked) {
                blockedCount++;
                String n = name.toLowerCase(java.util.Locale.ROOT);
                Integer v = top.get(n);
                top.put(n, v == null ? 1 : v + 1);
                recent.remove(n);
                recent.add(0, n);
                if (recent.size() > 30) recent.remove(recent.size() - 1);
                if (top.size() > 200) top.remove(top.keySet().iterator().next());
            }
        }
        flush(false);
    }

    private void flush(boolean now) {
        long t = System.currentTimeMillis();
        if (!now && t - lastFlush < 30_000) return;
        lastFlush = t;
        Map<String, Integer> snapshot;
        List<String> rec;
        long b, all;
        synchronized (statsLock) {
            snapshot = new LinkedHashMap<>(top);
            rec = new ArrayList<>(recent);
            b = blockedCount;
            all = totalCount;
            blockedCount = 0;
            totalCount = 0;
        }
        FilterSettings.addStats(this, b, all, snapshot, rec);
        synchronized (statsLock) {
            top.clear();
        }
    }
}
