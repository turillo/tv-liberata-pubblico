package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Earth screensaver scene (config.xml dream_scene "earth"): NASA EPIC's photos of the whole sunlit Earth from
 * the DSCOVR spacecraft (enhanced colours when EPIC has them for the latest day, true colours otherwise), the latest day's photos in order (about one every 1 h 50 min) so the Earth turns, one
 * every 30 seconds with a slow cross-fade, then from the first again. A small panel says when the photo was taken,
 * what is in the middle and how far DSCOVR is. Checks for a new day at most once an hour while it runs; offline it
 * shows the cached day, and with none a short line on black.
 */
final class EarthScene implements DreamScene {

    private static final long SHOW_MS = 30_000L;
    private static final long FADE_MS = 4_000L;
    private static final long TICK_MS = 15_000L;
    private static final long CORNER_MS = 4 * 60_000L;

    private final Context context;
    private final EarthView view;
    private final EarthImages images;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] regions;
    private ExecutorService network, decoder;
    private List<EarthData.Shot> shots = new ArrayList<>();
    private EarthData.Shot shown;
    private int shownIndex = -1, shownRegion = -1;
    private int generation;
    private boolean refreshing, decoding;
    private long nextCorner;
    private float[][] land;   // read on the decoder thread, once

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

    EarthScene(Context context, EarthView view) {
        this.context = context;
        this.view = view;
        this.images = new EarthImages(context);
        regions = context.getResources().getStringArray(R.array.earth_regions);
    }

    @Override
    public void start() {
        generation++;
        network = Executors.newSingleThreadExecutor();
        decoder = Executors.newSingleThreadExecutor();
        refreshing = false;
        decoding = false;
        shots = images.load();
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

    /** Decodes the next photo of the day off the main thread and cross-fades to it. */
    private void showNext() {
        if (decoding || decoder == null) return;
        if (shots.isEmpty()) {
            if (!view.hasImage()) view.showMessage(context.getString(refreshing ? R.string.earth_loading : R.string.earth_unavailable));
            return;
        }
        int index = (shownIndex + 1) % shots.size();
        EarthData.Shot shot = shots.get(index);
        int gen = generation, size = view.imageSize(shot.disk());
        decoding = true;
        decoder.execute(() -> {
            Bitmap bitmap = images.decode(shot, size);
            int region = EarthData.region(shot.lat, shot.lon, EarthData.onLand(land(), shot.lat, shot.lon));
            handler.post(() -> {
                if (gen != generation) return;
                decoding = false;
                shownIndex = index;
                if (bitmap == null) return;   // skipped; the next tick moves on
                shown = shot;
                shownRegion = region;
                view.showImage(bitmap, shot.disk(), FADE_MS, SHOW_MS);
                updatePanel();
            });
        });
    }

    private float[][] land() {
        if (land == null) {
            try (InputStream in = context.getResources().openRawResource(R.raw.world_land)) {
                land = WorldMap.read(in);
            } catch (IOException | RuntimeException e) {
                Log.w("TvHome.EarthScene", "world map unreadable", e);
                land = new float[0][];
            }
        }
        return land;
    }

    private void refreshIfDue(long now) {
        if (refreshing || network == null || !images.due(now)) return;
        refreshing = true;
        if (!view.hasImage()) showNext();   // says it is downloading
        int gen = generation;
        network.execute(() -> {
            boolean changed = images.refresh();
            List<EarthData.Shot> fresh = images.load();
            handler.post(() -> {
                if (gen != generation) return;
                refreshing = false;
                if (changed || shots.isEmpty()) {
                    shots = fresh;
                    shownIndex = -1;
                }
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
        if (shown == null) return;
        long now = System.currentTimeMillis();
        Date taken = new Date(shown.timeMillis);
        String day = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMM"), taken).toString();
        String heading = day + ", " + DateFormat.getTimeFormat(context).format(taken);
        String detail = shownRegion >= 0 && shownRegion < regions.length ? regions[shownRegion] : "";
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        if (shown.distanceKm > 0) {
            labels.add(context.getString(R.string.earth_distance));
            values.add(context.getString(R.string.earth_distance_value,
                    String.format(Locale.getDefault(), "%.2f", shown.distanceKm / 1e6)));
        }
        int position = shots.indexOf(shown);
        if (position >= 0) {
            labels.add(context.getString(R.string.earth_photo));
            values.add(context.getString(R.string.earth_photo_value, position + 1, shots.size()));
        }
        view.setPanel(context.getString(R.string.earth_title).toUpperCase(Locale.getDefault()),
                DateFormat.getTimeFormat(context).format(new Date(now)),
                context.getString(R.string.earth_source) + " · " + context.getString(
                        shown.enhanced() ? R.string.earth_enhanced : R.string.earth_natural), heading, detail,
                labels.toArray(new String[0]), values.toArray(new String[0]));
    }
}
