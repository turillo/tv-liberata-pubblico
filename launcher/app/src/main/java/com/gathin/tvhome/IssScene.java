package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The ISS screensaver scene (config.xml dream_scene "iss"): where the International Space Station is now,
 * computed on the TV with SGP4 from a TLE that IssTle downloads at most once a day. The position and panel
 * update every few seconds; the ground track (45 minutes back and ahead) and the night side once a minute.
 *
 * Phase 2, the next pass visible from home, has its pieces in Orbit: look() for the ISS's elevation in the
 * home sky, sunlit() for whether it reflects sunlight, and sunElevation() for whether the sky is dark enough.
 */
final class IssScene implements DreamScene {

    private static final long TICK_MS = 3_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final long CORNER_MS = 4 * MINUTE_MS;
    private static final int TRACK_MINUTES = 45;
    private static final long STALE_MS = 3 * 86_400_000L;

    private final Context context;
    private final IssView view;
    private final IssTle store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean hasHome;
    private final double homeLat, homeLon;
    private final NumberFormat whole = NumberFormat.getIntegerInstance();
    private ExecutorService worker;
    private Sgp4 tle;
    private boolean downloading;
    private long nextMinute, nextCorner;
    private int nudgeStep;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, TICK_MS);
        }
    };

    IssScene(Context context, IssView view) {
        this.context = context;
        this.view = view;
        this.store = new IssTle(context);
        double[] home = home(context);
        hasHome = home != null;
        homeLat = hasHome ? home[0] : 0;
        homeLon = hasHome ? home[1] : 0;
        if (hasHome) view.setHome(homeLat, homeLon);
    }

    /** {latitude, longitude} of this TV (Place), or null when unknown. */
    private static double[] home(Context context) {
        return Place.home(context);
    }

    @Override
    public void start() {
        if (tle == null) tle = store.load();
        long now = System.currentTimeMillis();
        nextMinute = 0;
        nextCorner = now + CORNER_MS;
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(1500).start();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    public void stop() {
        handler.removeCallbacksAndMessages(null);
        view.animate().cancel();
        view.stopAnimations();
        if (worker != null) worker.shutdownNow();
        worker = null;
        downloading = false;
    }

    private void update() {
        long now = System.currentTimeMillis();
        refreshTle(now);
        if (now >= nextMinute) {
            nextMinute = now + MINUTE_MS;
            double[] sun = Orbit.subsolar(now);
            view.setNight(sun[0], sun[1]);
            if (tle != null) view.setTrack(track(now), TRACK_MINUTES);
            // A slow loop of small offsets: the map moves up to 6 dp, the panel up to 14 dp.
            nudgeStep = (nudgeStep + 1) % 8;
            double a = nudgeStep * Math.PI / 4;
            view.nudge((float) (6 * Math.cos(a)), (float) (4 * Math.sin(a)),
                    (float) (14 * Math.sin(a)), (float) (10 * Math.cos(a)));
        }
        if (now >= nextCorner) {
            nextCorner = now + CORNER_MS;
            view.moveCorner();
        }
        showPosition(now);
    }

    private void showPosition(long now) {
        String time = DateFormat.getTimeFormat(context).format(new Date(now));
        String subtitle = context.getString(R.string.iss_subtitle).toUpperCase(Locale.getDefault());
        if (tle == null) {
            view.setPanel(time, subtitle, new String[0], new String[0], context.getString(R.string.iss_waiting));
            return;
        }
        Sgp4.State state;
        try {
            state = tle.at(now);
        } catch (IllegalStateException e) {
            view.setPanel(time, subtitle, new String[0], new String[0], context.getString(R.string.iss_waiting));
            return;
        }
        Orbit.Geo geo = Orbit.geodetic(state, now);
        view.setIss(geo.lat, geo.lon);

        String position = degrees(geo.lat, R.string.iss_north, R.string.iss_south) + "  "
                + degrees(geo.lon, R.string.iss_east, R.string.iss_west);
        String altitude = context.getString(R.string.iss_km, whole.format(Math.round(geo.altKm)));
        String speed = context.getString(R.string.iss_kmh, whole.format(Math.round(state.speedKmS() * 3600)));
        String[] labels, values;
        if (hasHome) {
            double km = Orbit.groundDistanceKm(homeLat, homeLon, geo.lat, geo.lon);
            labels = new String[]{context.getString(R.string.iss_position), context.getString(R.string.iss_altitude),
                    context.getString(R.string.iss_speed), context.getString(R.string.iss_distance)};
            values = new String[]{position, altitude, speed,
                    context.getString(R.string.iss_km, whole.format(Math.round(km)))};
        } else {
            labels = new String[]{context.getString(R.string.iss_position), context.getString(R.string.iss_altitude),
                    context.getString(R.string.iss_speed)};
            values = new String[]{position, altitude, speed};
        }
        String status = context.getString(Orbit.sunlit(state, now) ? R.string.iss_sunlit : R.string.iss_eclipsed);
        long age = now - tle.epochMillis();
        if (age > STALE_MS) status = context.getString(R.string.iss_stale, (int) (age / 86_400_000L));
        view.setPanel(time, subtitle, labels, values, status);
    }

    private String degrees(double value, int positive, int negative) {
        return String.format(Locale.getDefault(), "%.2f° %s", Math.abs(value),
                context.getString(value >= 0 ? positive : negative));
    }

    /** Ground track one point a minute, TRACK_MINUTES back to TRACK_MINUTES ahead, as lon, lat pairs. */
    private float[] track(long now) {
        int n = 2 * TRACK_MINUTES + 1;
        float[] points = new float[2 * n];
        for (int i = 0; i < n; i++) {
            long t = now + (i - TRACK_MINUTES) * MINUTE_MS;
            Orbit.Geo g;
            try {
                g = Orbit.geodetic(tle.at(t), t);
            } catch (IllegalStateException e) {
                g = i > 0 ? new Orbit.Geo(points[2 * i - 1], points[2 * i - 2], 0) : new Orbit.Geo(0, 0, 0);
            }
            points[2 * i] = (float) g.lon;
            points[2 * i + 1] = (float) g.lat;
        }
        return points;
    }

    /** Downloads a new TLE in the background when the saved one is a day old; only while the scene runs. */
    private void refreshTle(long now) {
        if (downloading || !store.due(now)) return;
        downloading = true;
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        Sgp4 current = tle;
        worker.execute(() -> {
            Sgp4 fresh = store.download(current);
            handler.post(() -> {
                downloading = false;
                if (fresh != null) {
                    tle = fresh;
                    nextMinute = 0;   // redraw the track with the new orbit
                }
            });
        });
    }
}
