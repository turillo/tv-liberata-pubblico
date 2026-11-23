package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Webb and Hubble screensaver scene (config.xml dream_scene "space"): ESA/Webb and ESA/Hubble images from the
 * local archive, a random one every 60 seconds (not one of the last few) with a slow cross-fade and zoom, and a
 * panel with the title, the telescope and the credit line the CC BY 4.0 licence asks for. Looks for new images at
 * most once a day while it runs; offline it shows the archive, and with none a short line on black.
 */
final class SpaceScene implements DreamScene {

    private static final long SHOW_MS = 60_000L;
    private static final long FADE_MS = 4_000L;
    private static final long TICK_MS = 15_000L;
    private static final long CORNER_MS = 4 * 60_000L;

    private final Context context;
    private final SpaceView view;
    private final SpaceImages images;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Deque<Integer> recent = new ArrayDeque<>();
    private ExecutorService network, decoder;
    private List<SpaceImages.Entry> archive = new ArrayList<>();
    private SpaceImages.Entry shown;
    private int last = -1;
    private int generation;
    private boolean refreshing, decoding;
    private long nextCorner;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            refreshIfDue(now);
            if (now >= nextCorner) {
                nextCorner = now + CORNER_MS;
                if (view.hasImage()) view.moveCorner();
            }
            updatePanel();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            showNext();
            handler.postDelayed(this, SHOW_MS);
        }
    };

    SpaceScene(Context context, SpaceView view) {
        this.context = context;
        this.view = view;
        this.images = new SpaceImages(context);
    }

    @Override
    public void start() {
        generation++;
        network = Executors.newSingleThreadExecutor();
        decoder = Executors.newSingleThreadExecutor();
        refreshing = false;
        decoding = false;
        nextCorner = System.currentTimeMillis() + CORNER_MS;
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(1500).start();
        handler.removeCallbacksAndMessages(null);
        int gen = generation;
        decoder.execute(() -> {
            List<SpaceImages.Entry> loaded = images.load();
            handler.post(() -> {
                if (gen != generation) return;
                archive = loaded;
                handler.post(rotate);
                handler.post(tick);
            });
        });
    }

    @Override
    public void stop() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        view.animate().cancel();
        view.stopAnimations();
        if (network != null) network.shutdownNow();
        if (decoder != null) decoder.shutdownNow();
        network = null;
        decoder = null;
    }

    private void showNext() {
        if (decoding || decoder == null) return;
        if (archive.isEmpty()) {
            if (!view.hasImage()) view.showMessage(context.getString(refreshing ? R.string.space_loading : R.string.space_unavailable));
            return;
        }
        int index = SpaceData.pick(archive.size(), recent, last, random);
        SpaceImages.Entry entry = archive.get(index);
        DisplayMetrics m = context.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        int gen = generation;
        decoding = true;
        decoder.execute(() -> {
            Bitmap bitmap = images.decode(entry, w, h);
            handler.post(() -> {
                if (gen != generation) return;
                decoding = false;
                remember(index);
                if (bitmap == null) return;
                shown = entry;
                view.showImage(bitmap, FADE_MS, SHOW_MS);
                updatePanel();
            });
        });
    }

    private void remember(int index) {
        last = index;
        recent.addLast(index);
        while (recent.size() > SpaceData.memory(archive.size())) recent.removeFirst();
    }

    private void refreshIfDue(long now) {
        if (refreshing || network == null || !images.due(now)) return;
        refreshing = true;
        if (!view.hasImage()) showNext();   // says it is downloading
        int gen = generation;
        network.execute(() -> {
            images.refresh(() -> {
                // Each new image joins the rotation at once; the first one is shown straight away.
                List<SpaceImages.Entry> loaded = images.load();
                handler.post(() -> {
                    if (gen != generation) return;
                    archive = loaded;
                    recent.clear();
                    last = -1;
                    if (!view.hasImage()) {
                        handler.removeCallbacks(rotate);
                        handler.post(rotate);
                    }
                });
            });
            handler.post(() -> {
                if (gen != generation) return;
                refreshing = false;
                if (archive.isEmpty()) showNext();   // says it is unavailable
            });
        });
    }

    private void updatePanel() {
        if (shown == null) return;
        String time = DateFormat.getTimeFormat(context).format(new Date(System.currentTimeMillis()));
        view.setPanel(SpaceData.NAMES[shown.telescope].toUpperCase(Locale.ROOT), time, shown.title,
                context.getString(R.string.space_credit, shown.credit));
    }
}
