package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Moon screensaver scene (config.xml dream_scene "moon"): NASA SVS Dial-a-Moon's picture of the Moon for this
 * hour, north up as seen from the northern hemisphere, large over faint stars with a slow zoom (EarthView, which
 * draws any round body over stars). The panel shows the phase, how much is lit, the age and distance (from
 * Dial-a-Moon, or computed on the TV when the cached reply is old), the next new and full Moon, and moonrise and
 * moonset at config.xml latitude and longitude, all computed on the TV.
 */
final class MoonScene implements DreamScene {

    private static final long SHOW_MS = 60_000L;
    private static final long FADE_MS = 4_000L;
    private static final long TICK_MS = 15_000L;
    private static final long CORNER_MS = 4 * 60_000L;
    /** A cached reply older than this gives way to the TV's own numbers. */
    private static final long FRESH_MS = 2 * 3_600_000L;

    private final Context context;
    private final EarthView view;
    private final MoonImages images;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] phases;
    private final double lat, lon;
    private final boolean hasHome;
    private ExecutorService worker;
    private Bitmap bitmap;
    private MoonData.Reading reading;
    private long readingTime;
    private int generation;
    private boolean busy;
    private long nextCorner, nextShow;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            refreshIfDue(now);
            if (bitmap != null && now >= nextShow) {
                nextShow = now + SHOW_MS;
                view.showImage(bitmap, MoonData.DISK, FADE_MS, SHOW_MS);   // the same picture, a new slow drift
            }
            if (now >= nextCorner) {
                nextCorner = now + CORNER_MS;
                if (view.hasImage()) view.moveCorner();
            }
            updatePanel();
            handler.postDelayed(this, TICK_MS);
        }
    };

    MoonScene(Context context, EarthView view) {
        this.context = context;
        this.view = view;
        this.images = new MoonImages(context);
        phases = context.getResources().getStringArray(R.array.moon_phases);
        // No home: no moonrise and moonset.
        double[] home = Place.home(context);
        hasHome = home != null;
        lat = home == null ? Double.NaN : home[0];
        lon = home == null ? Double.NaN : home[1];
    }

    @Override
    public void start() {
        generation++;
        worker = Executors.newSingleThreadExecutor();
        busy = false;
        nextCorner = System.currentTimeMillis() + CORNER_MS;
        nextShow = 0;
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(1500).start();
        handler.removeCallbacksAndMessages(null);
        load(false);
        handler.post(tick);
    }

    @Override
    public void stop() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        view.animate().cancel();
        view.stopAnimations();
        if (worker != null) worker.shutdownNow();
        worker = null;
        busy = false;
    }

    /** Reads (and with {@code download} first fetches) the picture off the main thread, then shows it. */
    private void load(boolean download) {
        if (busy || worker == null) return;
        busy = true;
        int gen = generation, size = view.imageSize(MoonData.DISK);
        if (!view.hasImage()) view.showMessage(context.getString(R.string.moon_loading));
        worker.execute(() -> {
            boolean fresh = download && images.refresh(System.currentTimeMillis());
            MoonData.Reading r = images.load();
            long time = images.fetched();
            Bitmap b = (fresh || bitmap == null) && r != null ? images.decode(size) : null;
            handler.post(() -> {
                if (gen != generation) return;
                busy = false;
                if (r != null) {
                    reading = r;
                    readingTime = time;
                }
                if (b != null) {
                    bitmap = b;
                    nextShow = 0;
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                } else if (bitmap == null) {
                    view.showMessage(context.getString(download ? R.string.moon_unavailable : R.string.moon_loading));
                }
            });
        });
    }

    private void refreshIfDue(long now) {
        if (!busy && images.due(now)) load(true);
    }

    private void updatePanel() {
        if (bitmap == null) return;
        long now = System.currentTimeMillis();
        boolean fresh = reading != null && now - readingTime < FRESH_MS;
        double lit = fresh ? reading.phase : MoonData.illumination(now);
        double age = fresh ? reading.ageDays : MoonData.ageDays(now);
        double km = fresh ? reading.distanceKm : MoonData.position(now)[2];
        String phase = phases[MoonData.phaseName(lit, MoonData.waxing(age))];
        String detail = context.getString(R.string.moon_detail, Math.round(lit),
                String.format(Locale.getDefault(), "%.1f", age));

        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        labels.add(context.getString(R.string.moon_distance));
        values.add(context.getString(R.string.moon_km, NumberFormat.getIntegerInstance().format(Math.round(km / 10) * 10)));
        long newMoon = MoonData.next(now, false), fullMoon = MoonData.next(now, true);
        boolean newFirst = newMoon < fullMoon;
        labels.add(context.getString(newFirst ? R.string.moon_next_new : R.string.moon_next_full));
        values.add(when(newFirst ? newMoon : fullMoon, now));
        labels.add(context.getString(newFirst ? R.string.moon_next_full : R.string.moon_next_new));
        values.add(when(newFirst ? fullMoon : newMoon, now));
        if (hasHome) {
            long[] riseSet = MoonData.nextRiseSet(now, lat, lon);
            if (riseSet[0] > 0) {
                labels.add(context.getString(R.string.moon_rise));
                values.add(when(riseSet[0], now));
            }
            if (riseSet[1] > 0) {
                labels.add(context.getString(R.string.moon_set));
                values.add(when(riseSet[1], now));
            }
        }
        view.setPanel(context.getString(R.string.moon_title).toUpperCase(Locale.getDefault()),
                DateFormat.getTimeFormat(context).format(new Date(now)),
                context.getString(R.string.moon_source), phase, detail,
                labels.toArray(new String[0]), values.toArray(new String[0]));
    }

    /** A time in the TV's time zone, with the day in front when it is not today. */
    private String when(long millis, long now) {
        if (millis <= 0) return "–";
        String time = DateFormat.getTimeFormat(context).format(new Date(millis));
        if (DateUtils.isToday(millis)) return time;
        String day = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), millis).toString();
        return day + ", " + time;
    }
}
