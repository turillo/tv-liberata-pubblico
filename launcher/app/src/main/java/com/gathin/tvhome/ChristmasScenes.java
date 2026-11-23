package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Christmas mode's two screensaver scenes (Syntax TV, Natale): Santa's journey on the ISS scene's map, and the
 * Advent calendar. Each is a DreamScene on a FrameLayout; nothing
 * here throws, and a missing photo leaves a short line on a dark screen.
 */
final class ChristmasScenes {

    private static final String TAG = "TvHome.XmasScene";

    private ChristmasScenes() {
    }

    /** The scene for {@code name} on {@code root} (empty, full screen), or null for a name that is not Christmas's. */
    static DreamScene create(Context c, String name, FrameLayout root) {
        switch (name) {
            case Christmas.SANTA: return new Santa(c, root);
            case Christmas.ADVENT: return new Advent(c, root);
            case Christmas.FAVORITES: return new FavoritesScene(c, root);
            default: return null;
        }
    }

    /** Whether a scene has its own snow (the screensaver's snow switch adds none over it). */
    static boolean hasSnow(String name) {
        return Christmas.ADVENT.equals(name);
    }

    static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * An active photo from the Christmas archive (any Christmas photo when none is active, a general one when there
     * is none at all), decoded at screen size; null if none.
     */
    static Bitmap photo(Context c, Random random) {
        try {
            WallpaperLibrary.migrate(c);
            File[] photos = WallpaperLibrary.photos(c, true);
            if (photos.length == 0) photos = WallpaperLibrary.photos(c, false);
            if (photos.length == 0) return null;
            WallpaperState s = WallpaperLibrary.load(c, true);
            List<File> on = new ArrayList<>();
            for (File f : photos) if (s.active(WallpaperLibrary.id(f))) on.add(f);
            List<File> pool = on.isEmpty() ? java.util.Arrays.asList(photos) : on;
            File f = pool.get(random.nextInt(pool.size()));
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= m.widthPixels && o.outHeight / (sample * 2) >= m.heightPixels) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no photo: " + e.getMessage());
            return null;
        }
    }

    static String time(Context c) {
        return DateFormat.getTimeFormat(c).format(new Date());
    }

    // ---- 1. Santa's journey ---------------------------------------------------------------------------------

    /** The ISS scene's map with Santa: waiting at the North Pole, then westwards with midnight, fuso by fuso. */
    static final class Santa implements DreamScene {
        private final Context c;
        private final IssView view;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final NumberFormat whole = NumberFormat.getIntegerInstance();
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                update();
                handler.postDelayed(this, 3_000);
            }
        };

        Santa(Context c, FrameLayout root) {
            this.c = c;
            view = new IssView(c);
            view.setLook(c.getString(R.string.xmas_santa_title).toUpperCase(Locale.getDefault()), 0xFFFF5A4A);
            root.addView(view, full());
            double[] home = Place.home(c);   // none: no home dot
            if (home != null) view.setHome(home[0], home[1]);
        }

        @Override
        public void start() {
            long now = System.currentTimeMillis();
            double[] sun = Orbit.subsolar(now);
            view.setNight(sun[0], sun[1]);
            handler.post(tick);
        }

        private void update() {
            long now = System.currentTimeMillis();
            double[] s = Christmas.santa(now);
            LocalDate utc = LocalDate.now(ZoneId.of("UTC"));
            int year = utc.getMonthValue() == 1 ? utc.getYear() - 1 : utc.getYear();
            // Before the night the whole route is still ahead; during and after it, done up to Santa.
            int index = s[0] == Christmas.BEFORE ? 0 : Christmas.trackIndex(now, Christmas.departure(year));
            view.setTrack(Christmas.track(), index);
            view.setIss(s[1], s[2]);
            String sub = c.getString(R.string.xmas_santa_sub).toUpperCase(Locale.getDefault());
            String status;
            String[] labels, values;
            if (s[0] == Christmas.BEFORE) {
                long days = (long) Math.ceil(s[4] / (double) Christmas.DAY_MS);
                long hours = (long) Math.ceil(s[4] / (double) Christmas.HOUR);
                status = days > 1 ? c.getResources().getQuantityString(R.plurals.xmas_santa_days, (int) days, (int) days)
                        : c.getResources().getQuantityString(R.plurals.xmas_santa_hours, (int) hours, (int) hours);
                labels = new String[]{c.getString(R.string.xmas_santa_where)};
                values = new String[]{c.getString(R.string.xmas_santa_pole)};
            } else {
                status = c.getString(s[0] == Christmas.FLYING ? R.string.xmas_santa_flying : R.string.xmas_santa_done);
                labels = new String[]{c.getString(R.string.xmas_santa_where), c.getString(R.string.xmas_santa_presents)};
                values = new String[]{s[0] == Christmas.DONE ? c.getString(R.string.xmas_santa_pole)
                        : String.format(Locale.getDefault(), "%.1f° %s  %.1f° %s", Math.abs(s[1]),
                        c.getString(s[1] >= 0 ? R.string.iss_north : R.string.iss_south), Math.abs(s[2]),
                        c.getString(s[2] >= 0 ? R.string.iss_east : R.string.iss_west)), whole.format((long) s[3])};
            }
            view.setPanel(time(c), sub, labels, values, status);
        }

        @Override
        public void stop() {
            handler.removeCallbacksAndMessages(null);
            view.stopAnimations();
        }
    }

    // ---- 2. the Advent calendar -----------------------------------------------------------------------------

    static final class Advent implements DreamScene {
        private final Context c;
        private final AdventView view;
        private final SnowView snow;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler handler = new Handler(Looper.getMainLooper());

        Advent(Context c, FrameLayout root) {
            this.c = c;
            view = new AdventView(c);
            root.addView(view, full());
            snow = new SnowView(c, 50, 1f);
            root.addView(snow, full());
        }

        @Override
        public void start() {
            view.setDate(LocalDate.now());
            view.start();
            snow.start();
            worker.execute(() -> {
                Bitmap b = photo(c, new Random(LocalDate.now().toEpochDay()));
                handler.post(() -> view.setPhoto(b));
            });
        }

        @Override
        public void stop() {
            worker.shutdownNow();
            view.stop();
            snow.stop();
        }
    }

    /**
     * 24 doors in a 6x4 grid on the left; the days gone by open, today's door swinging open with its photo and a
     * Christmas tradition on the right. Out of December, the calendar closed and how long until it opens.
     */
    static final class AdventView extends SnowView {
        private final Paint door = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint open = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint();
        private final RectF r = new RectF();
        private final String[] facts;
        private final float dp;
        private int opened, today, toStart;
        private Bitmap photo;
        private long since = SystemClock.uptimeMillis();
        private List<String> lines = new ArrayList<>();

        AdventView(Context c) {
            super(c, 0, 1f);
            setSnow(false);
            dp = getResources().getDisplayMetrics().density;
            float sp = getResources().getDisplayMetrics().scaledDensity;
            facts = c.getResources().getStringArray(R.array.xmas_advent_facts);
            door.setColor(0xFF3A0E16);
            open.setColor(0xFF12301F);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.5f * dp);
            edge.setColor(0x88FFD27A);
            num.setTextAlign(Paint.Align.CENTER);
            num.setTextSize(26 * sp);
            num.setTypeface(UiFont.get(c, R.string.font_medium));
            head.setColor(0xFFFFD27A);
            head.setTextSize(30 * sp);
            head.setTypeface(UiFont.get(c, R.string.font_medium));
            body.setColor(0xE6FFFFFF);
            body.setTextSize(18 * sp);
            body.setTypeface(UiFont.get(c, R.string.font_light));
        }

        void setDate(LocalDate d) {
            int[] a = Christmas.advent(d);
            opened = a[0];
            today = a[1];
            toStart = a[2];
            since = SystemClock.uptimeMillis();
            lines.clear();
            invalidate();
        }

        void setPhoto(Bitmap b) {
            photo = b;
            invalidate();
        }

        /** Today's tradition (or, after Christmas, one a day in turn). */
        private String fact() {
            int i = today > 0 ? today - 1 : (int) (LocalDate.now().toEpochDay() % 24);
            return i >= 0 && i < facts.length ? facts[i] : "";
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            bg.setShader(new LinearGradient(0, 0, w, h, 0xFF14060A, 0xFF071410, Shader.TileMode.CLAMP));
            lines.clear();
        }

        @Override
        void drawExtra(Canvas canvas, float t) {
            int w = getWidth(), h = getHeight();
            canvas.drawRect(0, 0, w, h, bg);
            float gridL = w * 0.05f, gridT = h * 0.12f, gridW = w * 0.5f, gridH = h * 0.72f;
            float cw = gridW / 6f, ch = gridH / 4f, gap = 8 * dp;
            float anim = Math.min(1f, (SystemClock.uptimeMillis() - since) / 2500f);
            for (int i = 0; i < 24; i++) {
                int day = i + 1, col = i % 6, row = i / 6;
                r.set(gridL + col * cw + gap, gridT + row * ch + gap, gridL + (col + 1) * cw - gap, gridT + (row + 1) * ch - gap);
                boolean isToday = day == today, isOpen = day <= opened;
                canvas.drawRoundRect(r, 12 * dp, 12 * dp, isOpen || isToday ? open : door);
                if (isToday) {
                    // The door swings open: a red panel narrowing to the left edge.
                    float f = 1f - anim;
                    if (f > 0) {
                        RectF d = new RectF(r.left, r.top, r.left + r.width() * f, r.bottom);
                        canvas.drawRoundRect(d, 12 * dp, 12 * dp, door);
                    }
                    edge.setColor(0xFFFFD27A);
                    edge.setAlpha(Math.round(160 + 95 * (0.5f + 0.5f * (float) Math.sin(t * 2))));
                } else {
                    edge.setColor(0x88FFD27A);
                }
                canvas.drawRoundRect(r, 12 * dp, 12 * dp, edge);
                num.setColor(isOpen || isToday ? 0xFFFFD27A : 0xCCFFFFFF);
                canvas.drawText(String.valueOf(day), r.centerX(), r.centerY() + 9 * dp, num);
            }
            float px = w * 0.6f, pw = w * 0.35f;
            if (today == 0 && opened == 0) {
                canvas.drawText(getContext().getString(R.string.xmas_advent_title), px, h * 0.3f, head);
                String wait = getResources().getQuantityString(R.plurals.xmas_advent_wait, toStart, toStart);
                canvas.drawText(wait, px, h * 0.3f + 44 * dp, body);
                return;
            }
            float alpha = today > 0 ? Math.max(0f, Math.min(1f, (anim - 0.6f) / 0.4f)) : 1f;
            int a = Math.round(255 * alpha);
            float top = h * 0.12f;
            if (photo != null) {
                float ph = pw * 9 / 16;
                r.set(px, top, px + pw, top + ph);
                Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
                pp.setAlpha(a);
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(r, 16 * dp, 16 * dp, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                float scale = Math.max(r.width() / photo.getWidth(), r.height() / photo.getHeight());
                float dw = photo.getWidth() * scale, dh = photo.getHeight() * scale;
                canvas.drawBitmap(photo, null, new RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2,
                        r.centerY() + dh / 2), pp);
                canvas.restore();
                top += ph + 34 * dp;
            } else {
                top += 20 * dp;
            }
            head.setAlpha(a);
            body.setAlpha(Math.round(230 * alpha));
            String title = today > 0 ? getResources().getString(R.string.xmas_advent_day, today)
                    : getResources().getString(R.string.xmas_advent_all);
            canvas.drawText(title, px, top + 30 * dp, head);
            if (lines.isEmpty()) lines = wrap(fact(), body, pw);
            float y = top + 30 * dp + 40 * dp;
            for (String l : lines) {
                canvas.drawText(l, px, y, body);
                y += 28 * dp;
            }
        }

        private static List<String> wrap(String text, Paint p, float width) {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(next) > width && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            return out;
        }
    }
}
