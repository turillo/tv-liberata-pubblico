package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The news ticker over every screensaver scene and along the bottom of Home (Syntax TV's settings): the ticked ANSA
 * sections' headlines (Ultima ora by default), fetched every 10 minutes and only while the screensaver or Home shows
 * the band; the times are static, shared by both in the launcher's process, so they never download twice. Merged
 * into files/news.txt, so the last ones show offline. With no headline at all the band stays hidden.
 */
final class NewsTicker {

    private static final String TAG = "TvHome.NewsTicker";
    private static final long REFRESH_MS = 10 * 60_000L;
    private static final long RETRY_MS = 3 * 60_000L;
    private static final int MAX_FEED_BYTES = 256 * 1024;

    private static long lastCheck, lastAttempt;
    private static String lastSections = "";

    private final Context context;
    private final NewsTickerView view;
    private final File cache;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private List<NewsData.Headline> headlines = new ArrayList<>();
    private final List<String> sections;
    private final Listener listener;

    /** Told when the band shows or hides, so the screensaver's panels or Home's dock make room for it. */
    interface Listener {
        void bandChanged(boolean visible, int heightPx);
    }
    private int generation;
    private boolean busy;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshIfDue();
            handler.postDelayed(this, 60_000L);
        }
    };

    NewsTicker(Context context, NewsTickerView view, List<String> sections, Listener listener) {
        this.context = context;
        this.view = view;
        this.listener = listener;
        this.sections = new ArrayList<>(sections);
        this.cache = new File(context.getFilesDir(), "news.txt");
        headlines = NewsData.merge(NewsData.deserialize(read(cache)), new ArrayList<>(), this.sections);
        show();
    }

    /** The cached headlines of these sections, newest first: what the ticker would show now. */
    static List<NewsData.Headline> cached(Context context, List<String> sections) {
        return NewsData.merge(NewsData.deserialize(read(new File(context.getFilesDir(), "news.txt"))),
                new ArrayList<>(), sections);
    }

    /** Whether there is anything to show; false hides the band. */
    boolean hasHeadlines() {
        return !headlines.isEmpty();
    }

    void start() {
        generation++;
        worker = Executors.newSingleThreadExecutor();
        busy = false;
        handler.removeCallbacksAndMessages(null);
        handler.post(tick);
        view.startScrolling();
    }

    void stop() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        view.stopScrolling();
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    private void refreshIfDue() {
        long now = System.currentTimeMillis();
        String chosen = String.join(",", sections);
        if (!chosen.equals(lastSections)) {
            lastSections = chosen;
            lastCheck = 0;
            lastAttempt = 0;
        }
        if (busy || worker == null || now - lastCheck < REFRESH_MS || now - lastAttempt < RETRY_MS) return;
        busy = true;
        lastAttempt = now;
        int gen = generation;
        List<NewsData.Headline> cached = headlines;
        worker.execute(() -> {
            List<NewsData.Headline> result = fetch(context, sections, cached);
            if (result != null) lastCheck = System.currentTimeMillis();
            handler.post(() -> {
                busy = false;
                if (gen != generation || result == null) return;
                headlines = result;
                show();
            });
        });
    }

    /**
     * Downloads the sections' feeds, merges them into {@code cached} (newest first, no duplicates, at most 30) and
     * saves the cache. Null when nothing could be read. Blocking: call it off the main thread. Never throws.
     */
    static List<NewsData.Headline> fetch(Context context, List<String> sections, List<NewsData.Headline> cached) {
        List<NewsData.Headline> fresh = new ArrayList<>();
        Exception failure = null;
        boolean read = false;
        for (String key : sections) {
            NewsData.Section section = NewsData.section(key);
            if (section == null || Thread.currentThread().isInterrupted()) continue;
            boolean ansa = NewsData.ansa(key);
            try {
                fresh.addAll(NewsData.parse(download(section.url), key));
                if (ansa) read = true;
                else ServiceStatus.ok(context, ServiceStatus.ILPOST);
            } catch (Exception e) {
                Log.w(TAG, (ansa ? "ANSA " : "") + key + " not refreshed: " + e.getMessage());
                if (ansa) failure = e;
                else ServiceStatus.fail(context, ServiceStatus.ILPOST, e);
            }
        }
        if (read) ServiceStatus.ok(context, ServiceStatus.ANSA);
        else if (failure != null) ServiceStatus.fail(context, ServiceStatus.ANSA, failure);
        if (fresh.isEmpty()) return null;
        List<NewsData.Headline> merged = NewsData.merge(cached, fresh, sections);
        try {
            write(new File(context.getFilesDir(), "news.txt"), NewsData.serialize(merged));
        } catch (IOException e) {
            Log.w(TAG, "news cache not saved: " + e.getMessage());
        }
        return merged;
    }

    /** The ticker's items, {prefix, title}: "18:05", or "SPORT · 18:05" when several sections are ticked. */
    /** The band's label for these sections: ANSA's, Il Post's, or the neutral one with both. */
    static String label(Context context, List<String> sections) {
        switch (NewsData.labelKind(sections)) {
            case NewsData.LABEL_ILPOST: return context.getString(R.string.news_label_ilpost);
            case NewsData.LABEL_MIXED: return context.getString(R.string.news_label_mixed);
            default: return context.getString(R.string.news_label);
        }
    }

    static List<String[]> items(Context context, List<NewsData.Headline> headlines, List<String> sections) {
        List<String[]> items = new ArrayList<>();
        java.text.DateFormat time = DateFormat.getTimeFormat(context);
        boolean several = sections.size() > 1;
        for (NewsData.Headline h : headlines) {
            items.add(new String[]{NewsData.prefix(h, time.format(new Date(h.time)), several), h.title});
        }
        return items;
    }

    /** Hands the headlines to the band and shows or hides it; scenes keep the space free only while it shows. */
    private void show() {
        List<String[]> items = items(context, headlines, sections);
        if (!items.isEmpty()) view.setHeadlines(items);
        boolean visible = !items.isEmpty();
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (listener != null) listener.bandChanged(visible, visible ? view.bandHeight() : 0);
        if (visible) view.startScrollingIfStopped();
    }

    private static String download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "TVLiberata/" + BuildConfig.VERSION_NAME + " (Android TV launcher) RSS reader");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return readAtMost(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(File f) {
        if (!f.isFile() || f.length() > MAX_FEED_BYTES) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAtMost(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save " + f);
    }

    private static String readAtMost(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_FEED_BYTES) throw new IOException("reply too large");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
