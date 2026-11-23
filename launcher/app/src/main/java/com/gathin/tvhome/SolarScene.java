package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Sun screensaver scene (config.xml dream_scene "sun"): NASA SDO's latest picture of the Sun, one channel
 * after another every 35 seconds with a slow cross-fade, plus a small panel with the channel, when SDO published
 * the picture and NOAA's last solar flare. Downloads at most every 15 minutes while it runs; offline it shows
 * the cached pictures, and with none a short line on black.
 */
final class SolarScene implements DreamScene {

    private static final long SHOW_MS = 35_000L;
    private static final long FADE_MS = 4_000L;
    private static final long TICK_MS = 15_000L;
    private static final long CORNER_MS = 4 * 60_000L;

    private final Context context;
    private final SolarView view;
    private final SolarImages images;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] names, details;
    private ExecutorService network, decoder;
    private int current = -1;
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

    SolarScene(Context context, SolarView view) {
        this.context = context;
        this.view = view;
        this.images = new SolarImages(context);
        names = context.getResources().getStringArray(R.array.sun_channel_names);
        details = context.getResources().getStringArray(R.array.sun_channel_details);
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
        handler.post(rotate);
        handler.post(tick);
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

    /** Decodes the next cached channel off the main thread and cross-fades to it. */
    private void showNext() {
        if (decoding || decoder == null) return;
        int next = -1;
        for (int k = 1; k <= SolarData.CODES.length; k++) {
            int i = (current + k + SolarData.CODES.length) % SolarData.CODES.length;
            if (images.has(i)) {
                next = i;
                break;
            }
        }
        if (next < 0) {
            if (!view.hasImage()) view.showMessage(context.getString(refreshing ? R.string.sun_loading : R.string.sun_unavailable));
            return;
        }
        int channel = next, gen = generation, size = view.imageSize(next);
        decoding = true;
        decoder.execute(() -> {
            Bitmap bitmap = images.decode(channel, size);
            handler.post(() -> {
                if (gen != generation) return;
                decoding = false;
                if (bitmap == null) {
                    current = channel;   // skip a broken file next time
                    return;
                }
                current = channel;
                view.showImage(bitmap, channel, FADE_MS, SHOW_MS);
                updatePanel();
            });
        });
    }

    private void refreshIfDue(long now) {
        if (refreshing || network == null || !images.due(now)) return;
        refreshing = true;
        if (!view.hasImage()) showNext();   // says it is downloading
        int gen = generation;
        network.execute(() -> {
            images.refresh();
            handler.post(() -> {
                if (gen != generation) return;
                refreshing = false;
                if (!view.hasImage()) {
                    showNext();
                    handler.removeCallbacks(rotate);
                    handler.postDelayed(rotate, SHOW_MS);
                }
                updatePanel();
            });
        });
    }

    private void updatePanel() {
        if (current < 0) return;
        long now = System.currentTimeMillis();
        String[] labels, values;
        String published = when(images.published(current), now);
        SolarData.Flare flare = images.flare();
        if (flare != null) {
            labels = new String[]{context.getString(R.string.sun_published), context.getString(R.string.sun_flare)};
            values = new String[]{published,
                    context.getString(R.string.sun_flare_value, flare.flareClass, when(flare.peakMillis, now))};
        } else {
            labels = new String[]{context.getString(R.string.sun_published)};
            values = new String[]{published};
        }
        view.setPanel(context.getString(R.string.sun_title).toUpperCase(Locale.getDefault()),
                DateFormat.getTimeFormat(context).format(new Date(now)),
                context.getString(R.string.sun_source),
                names[current], details[current], labels, values);
    }

    /** A time in the TV's time zone, with the date in front when it is not today. */
    private String when(long millis, long now) {
        if (millis <= 0) return "–";
        String time = DateFormat.getTimeFormat(context).format(new Date(millis));
        if (DateUtils.isToday(millis) || Math.abs(now - millis) < 60_000L) return time;
        String day = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), millis).toString();
        return day + " " + time;
    }
}
