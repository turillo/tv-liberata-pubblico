package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The live Earth screensaver scene (config.xml dream_scene "live"): Meteosat-12 over Europe and Africa from
 * EUMETSAT's EUMETView, GeoColour (true colour by day, infrared clouds and city lights by night). Every 60 seconds it
 * cross-fades between the whole disk and Europe with Italy in the middle; every fourth time it plays the last six
 * hours as a time-lapse. A small dot marks config.xml latitude and longitude, with no name. The panel names the
 * image time, the satellite, what Rome sees in it (true colour or infrared) and the credit "© EUMETSAT".
 */
final class LiveScene implements DreamScene {

    private static final long SHOW_MS = 60_000L;
    private static final long FADE_MS = 4_000L;
    private static final long TICK_MS = 15_000L;
    private static final long CORNER_MS = 4 * 60_000L;
    private static final int LAPSE_EVERY = 4;
    private static final long LAPSE_FRAME_MS = 280L;
    private static final int LAPSE_LOOPS = 3;

    private final Context context;
    private final LiveView view;
    private final LiveImages images;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final double lat, lon;
    /** A small TV: the time-lapse keeps every other frame. */
    private final boolean lite;
    private ExecutorService network, decoder;
    private int generation;
    private boolean refreshing, decoding;
    private int viewShown = LiveData.EUROPE;
    private int cycles;
    private long nextCorner;
    private LiveImages.Frame shown;
    private Bitmap shownBitmap;
    private boolean lapse;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            refreshIfDue(now);
            if (now >= nextCorner) {
                nextCorner = now + CORNER_MS;
                if (view.hasImage()) view.moveCorner();
            }
            if (!lapse) updatePanel(shown, false);
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

    LiveScene(Context context, LiveView view) {
        this.context = context;
        lite = Device.lite(context);
        this.view = view;
        this.images = new LiveImages(context);
        // No home: no dot, and the panel goes by the disk's centre.
        double[] home = Place.home(context);
        lat = home == null ? Double.NaN : home[0];
        lon = home == null ? Double.NaN : home[1];
    }

    @Override
    public void start() {
        generation++;
        network = Executors.newSingleThreadExecutor();
        decoder = Executors.newSingleThreadExecutor();
        refreshing = false;
        decoding = false;
        lapse = false;
        cycles = 0;
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
        lapse = false;
    }

    /** Cross-fades to the other view's newest frame (or the same view when only it is cached). */
    private void showNext() {
        if (decoding || lapse || decoder == null) return;
        int want = 1 - viewShown;
        List<LiveImages.Frame> frames = images.frames(want);
        if (frames.isEmpty()) {
            want = viewShown;
            frames = images.frames(want);
        }
        if (frames.isEmpty()) {
            if (!view.hasImage()) view.showMessage(context.getString(refreshing ? R.string.live_loading : R.string.live_unavailable));
            return;
        }
        LiveImages.Frame frame = frames.get(frames.size() - 1);
        int target = want;
        int size = target == LiveData.DISK ? view.diskSize() : LiveData.EUROPE_W;
        int gen = generation;
        boolean playLapse = ++cycles % LAPSE_EVERY == 0;
        decoding = true;
        decoder.execute(() -> {
            Bitmap bitmap = images.decode(frame, size, lat, lon);
            handler.post(() -> {
                if (gen != generation) return;
                decoding = false;
                if (bitmap == null) return;
                viewShown = target;
                shown = frame;
                shownBitmap = bitmap;
                view.showImage(bitmap, target == LiveData.DISK, FADE_MS, SHOW_MS);
                updatePanel(frame, false);
                if (playLapse) handler.postDelayed(this::startLapse, FADE_MS + 2_000L);
            });
        });
    }

    /** {@code lite} (a small TV): every other frame, the newest kept, so half the bitmaps are in memory. */
    static List<LiveImages.Frame> everyOther(List<LiveImages.Frame> frames, boolean lite) {
        if (!lite) return frames;
        List<LiveImages.Frame> half = new ArrayList<>();
        for (int i = frames.size() - 1; i >= 0; i -= 2) half.add(0, frames.get(i));
        return half;
    }

    /** Decodes the last six hours of the shown view at half size, then plays them a few times over. */
    private void startLapse() {
        if (shown == null || decoder == null || lapse) return;
        List<LiveImages.Frame> all = everyOther(LiveImages.lastSix(images.frames(viewShown), shown.time), lite);
        if (all.size() < 3) return;
        int size = (viewShown == LiveData.DISK ? view.diskSize() : LiveData.EUROPE_W) / 2;
        int gen = generation;
        lapse = true;
        decoder.execute(() -> {
            List<Bitmap> bitmaps = new ArrayList<>();
            List<LiveImages.Frame> frames = new ArrayList<>();
            for (LiveImages.Frame f : all) {
                if (Thread.currentThread().isInterrupted()) break;
                Bitmap b = images.decode(f, size, lat, lon);
                if (b != null) {
                    bitmaps.add(b);
                    frames.add(f);
                }
            }
            handler.post(() -> {
                if (gen != generation) return;
                if (bitmaps.size() < 3) {
                    lapse = false;
                    return;
                }
                playLapse(bitmaps, frames, 0, gen);
            });
        });
    }

    private void playLapse(List<Bitmap> bitmaps, List<LiveImages.Frame> frames, int step, int gen) {
        if (gen != generation) return;
        int total = bitmaps.size() * LAPSE_LOOPS;
        if (step >= total) {
            lapse = false;
            view.showFrame(shownBitmap);
            updatePanel(shown, false);
            return;
        }
        int i = step % bitmaps.size();
        view.showFrame(bitmaps.get(i));
        updatePanel(frames.get(i), true);
        // Hold the newest frame a little longer at the end of each loop.
        long delay = i == bitmaps.size() - 1 ? LAPSE_FRAME_MS * 4 : LAPSE_FRAME_MS;
        handler.postDelayed(() -> playLapse(bitmaps, frames, step + 1, gen), delay);
    }

    private void refreshIfDue(long now) {
        if (refreshing || network == null || !images.due(now)) return;
        refreshing = true;
        if (!view.hasImage()) showNext();   // says it is downloading
        int gen = generation;
        network.execute(() -> {
            images.refresh(v -> handler.post(() -> {
                if (gen != generation) return;
                if (!view.hasImage() && !decoding) {
                    handler.removeCallbacks(rotate);
                    handler.post(rotate);
                }
            }));
            handler.post(() -> {
                if (gen != generation) return;
                refreshing = false;
                if (!view.hasImage()) showNext();   // says it is unavailable
            });
        });
    }

    private void updatePanel(LiveImages.Frame frame, boolean inLapse) {
        if (frame == null) return;
        long now = System.currentTimeMillis();
        boolean day = Double.isNaN(lat) ? LiveData.daylight(0, 0, frame.time) : LiveData.daylight(lat, lon, frame.time);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(frame.time);
        String subtitle = context.getString(R.string.live_source, c.get(Calendar.YEAR));
        String heading = context.getString(R.string.live_image, when(frame.time));
        String detail = context.getString(day ? R.string.live_natural : R.string.live_infrared);
        String[] labels = {context.getString(R.string.live_view)};
        String[] values = {context.getString(inLapse ? R.string.live_lapse
                : frame.view == LiveData.DISK ? R.string.live_disk : R.string.live_europe)};
        view.setPanel(context.getString(R.string.live_title).toUpperCase(Locale.getDefault()),
                DateFormat.getTimeFormat(context).format(new Date(now)), subtitle, heading, detail, labels, values);
    }

    private String when(long millis) {
        String time = DateFormat.getTimeFormat(context).format(new Date(millis));
        if (DateUtils.isToday(millis)) return time;
        return DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"), millis) + ", " + time;
    }
}
