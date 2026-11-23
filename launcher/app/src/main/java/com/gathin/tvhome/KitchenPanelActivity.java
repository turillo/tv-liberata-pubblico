package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Profile Cucina: the information panel Home turns into when left alone, made to be read across the room. A large
 * clock and date, the weather now and for the next hours with rain in the next two hours, the news band along the
 * bottom (Syntax TV's sections) and a photo from the local archive, darkened. No calendar, no Home Assistant.
 * A recycling box is built but stays hidden until Syntax TV turns it on. The panel keeps the screen on unless
 * Syntax TV allows the screensaver in the kitchen. Any key returns to Home.
 */
public class KitchenPanelActivity extends Activity {
    private static final long MINUTE_MS = 60_000L;
    private static final long WEATHER_MS = 15 * MINUTE_MS;
    private static final long STALE_MS = 2 * 60 * MINUTE_MS;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private TextView clock, date, temp, condition, rain, updated;
    private LinearLayout hours;
    private WeatherIcon icon;
    private ImageView photo;
    private NewsTicker ticker;
    private boolean running;
    /** Night (Syntax TV's hours): everything but a faint clock is hidden, until a key lights the panel again. */
    private boolean dim;
    private long litUntil;
    private int nightStart, nightEnd;
    private View shadeView, rightColumn, bandView;
    private RecyclingBox recycling;
    private SyntaxSettings settings;
    private static final long LIT_MS = 2 * MINUTE_MS;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showTime();
            if (recycling != null) recycling.update(settings);
            applyNight();
            KitchenWeather saved = KitchenWeather.saved(KitchenPanelActivity.this, System.currentTimeMillis());
            if (saved != null) showWeather(saved);   // the rain countdown moves with the clock
            long now = System.currentTimeMillis();
            handler.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 50);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            work.execute(() -> {
                KitchenWeather fresh = KitchenWeather.fetch(KitchenPanelActivity.this);
                if (fresh != null) handler.post(() -> {
                    if (running) showWeather(fresh);
                });
            });
            handler.postDelayed(this, WEATHER_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = SyntaxPrefs.get(this);
        nightStart = settings.kitchenNightStart();
        nightEnd = settings.kitchenNightEnd();
        if (!settings.kitchenScreensaver()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            setContentView(build(settings));
        } catch (RuntimeException e) {
            // A panel that cannot be built must never take Home down with it.
            Log.w("TvHome.Kitchen", "kitchen panel failed: " + e.getMessage());
            finish();
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        loadPhoto();
    }

    private View build(SyntaxSettings settings) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05080C);
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(photo, match());
        // Darker toward the bottom left, where the text sits.
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TR_BL,
                new int[]{0x8C000000, 0xB3000000, 0xE0000000}));
        root.addView(shade, match());
        shadeView = shade;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        // Syntax TV's screen margin on top of the panel's own, for a TV that crops the edges.
        int margin = ScreenMargin.percent(this);
        int mx = ScreenMargin.x(this, margin), my = ScreenMargin.y(this, margin);
        page.setPadding(dp(64) + mx, dp(48) + my, dp(64) + mx, dp(56) + my);
        root.addView(page, match());

        // Left: the time, the date and (when turned on) the recycling box.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.BOTTOM);
        page.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        // With the recycling box under it the clock is a little smaller, so the three fit above the band.
        clock = SyntaxUi.text(this, settings.kitchenRecycling() ? 108 : 150, R.string.font_light, SyntaxUi.TEXT);
        clock.setIncludeFontPadding(false);
        left.addView(clock);
        date = SyntaxUi.text(this, settings.kitchenRecycling() ? 28 : 34, R.string.font_regular, 0xDDFFFFFF);
        date.setPadding(dp(6), dp(6), 0, 0);
        left.addView(date);
        if (settings.kitchenRecycling()) left.addView(recyclingBox());

        // Right: the weather.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.BOTTOM | Gravity.END);
        page.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        rightColumn = right;
        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        ImageView iconView = new ImageView(this);
        icon = new WeatherIcon(SyntaxUi.TEXT);
        iconView.setImageDrawable(icon);
        now.addView(iconView, new LinearLayout.LayoutParams(dp(84), dp(84)));
        temp = SyntaxUi.text(this, 96, R.string.font_light, SyntaxUi.TEXT);
        temp.setPadding(dp(18), 0, 0, 0);
        now.addView(temp);
        right.addView(now);
        condition = SyntaxUi.text(this, 30, R.string.font_regular, 0xDDFFFFFF);
        condition.setGravity(Gravity.END);
        right.addView(condition);
        rain = SyntaxUi.text(this, 28, R.string.font_medium, SyntaxUi.ACCENT);
        rain.setGravity(Gravity.END);
        rain.setPadding(0, dp(10), 0, dp(22));
        right.addView(rain);
        hours = new LinearLayout(this);
        hours.setGravity(Gravity.END);
        right.addView(hours);
        updated = SyntaxUi.text(this, 16, R.string.font_light, SyntaxUi.MUTED);
        updated.setGravity(Gravity.END);
        updated.setPadding(0, dp(10), 0, 0);
        right.addView(updated);

        // The news band, with the sections chosen in Syntax TV.
        NewsTickerView band = new NewsTickerView(this, NewsTicker.label(this, settings.sections()),
                settings.tickerSpeedDp(), SyntaxSettings.bandFraction(settings.tickerSize()));
        band.setInsets(mx, my);
        ticker = new NewsTicker(this, band, settings.sections(), (visible, height) ->
                page.setPadding(dp(64) + mx, dp(48) + my, dp(64) + mx, dp(56) + (visible ? height : my)));
        root.addView(band, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, band.bandHeight(),
                Gravity.BOTTOM));
        bandView = band;
        return root;
    }

    private View recyclingBox() {
        recycling = new RecyclingBox(this, false);
        recycling.update(settings);
        View box = recycling.view;
        // As wide as the column, so long lines wrap inside it instead of running under the weather.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.rightMargin = dp(24);
        box.setLayoutParams(lp);
        return box;
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        showTime();
        KitchenWeather saved = KitchenWeather.saved(this, System.currentTimeMillis());
        if (saved != null) showWeather(saved);
        applyNight();
        handler.post(tick);
        // A reading under 15 minutes old is good enough; otherwise ask now.
        long age = saved == null ? Long.MAX_VALUE : System.currentTimeMillis() - saved.fetched;
        handler.postDelayed(refresh, age < WEATHER_MS ? WEATHER_MS - age : 0);
        if (ticker != null && !dim) ticker.start();
    }

    @Override
    protected void onPause() {
        running = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(refresh);
        if (ticker != null) ticker.stop();
        super.onPause();
        finish();   // like the idle clock: never left behind in the back stack
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The key that wakes the panel should not also act on Home, so it is consumed here. At night the first key
        // only lights the panel; the next one goes back to Home.
        if (event.getAction() != KeyEvent.ACTION_UP) return true;
        if (dim) {
            litUntil = System.currentTimeMillis() + LIT_MS;
            applyNight();
        } else {
            finish();
        }
        return true;
    }

    /** Night or day, from Syntax TV's hours and the last key; switches the views only when that changes. */
    private void applyNight() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int minute = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        boolean night = SyntaxSettings.isNight(minute, nightStart, nightEnd)
                && System.currentTimeMillis() >= litUntil;
        if (night) {
            // A small step every minute, so the faint digits never sit on the same pixels all night.
            int step = minute % 8;
            clock.setTranslationX(dp(step * 6));
            clock.setTranslationY(-dp((step % 4) * 8));
        }
        if (night == dim) return;
        dim = night;
        int hidden = night ? View.INVISIBLE : View.VISIBLE;
        photo.setVisibility(hidden);
        shadeView.setVisibility(hidden);
        rightColumn.setVisibility(hidden);
        date.setVisibility(hidden);
        bandView.setVisibility(hidden);
        if (recycling != null) recycling.view.setAlpha(night ? 0f : 1f);
        clock.setAlpha(night ? 0.22f : 1f);
        if (!night) {
            clock.setTranslationX(0);
            clock.setTranslationY(0);
        }
        if (ticker != null) {
            if (night) ticker.stop();
            else if (running) ticker.start();
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void showTime() {
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(this).format(now));
        String day = DateFormat.format("EEEE d MMMM", now).toString();
        date.setText(day.isEmpty() ? day : Character.toUpperCase(day.charAt(0)) + day.substring(1));
    }

    private void showWeather(KitchenWeather w) {
        long now = System.currentTimeMillis();
        temp.setText(getString(R.string.kitchen_temp, Math.round(w.temp)));
        String[] names = getResources().getStringArray(R.array.weather_conditions);
        int index = WeatherCode.condition(w.code);
        condition.setText(index >= 0 && index < names.length ? names[index] : "");
        icon.set(WeatherCode.icon(w.code), w.day);
        // An old reading cannot say when rain comes: the countdown is left out rather than wrong.
        boolean stale = now - w.fetched > STALE_MS;
        if (stale) rain.setText("");
        else if (w.rainIn == 0) rain.setText(R.string.kitchen_rain_now);
        else if (w.rainIn > 0) rain.setText(getString(R.string.kitchen_rain_in, w.rainIn));
        else rain.setText(R.string.kitchen_rain_none);
        hours.removeAllViews();
        for (KitchenWeather.Hour h : w.hours) hours.addView(hour(h));
        updated.setText(getString(R.string.kitchen_updated, DateFormat.getTimeFormat(this).format(new Date(w.fetched))));
    }

    private View hour(KitchenWeather.Hour h) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // A fixed width, so "18:00" never gets cut and the columns line up.
        // 64 dp: five fit the right column also inside a 5 % screen margin.
        col.setLayoutParams(new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView time = SyntaxUi.text(this, 18, R.string.font_regular, SyntaxUi.MUTED);
        time.setText(DateFormat.getTimeFormat(this).format(new Date(h.time)));
        time.setSingleLine(true);
        col.addView(time);
        ImageView glyph = new ImageView(this);
        WeatherIcon small = new WeatherIcon(SyntaxUi.TEXT);
        small.set(WeatherCode.icon(h.code), isDay(h.time));
        glyph.setImageDrawable(small);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        col.addView(glyph, lp);
        TextView t = SyntaxUi.text(this, 26, R.string.font_medium, SyntaxUi.TEXT);
        t.setText(getString(R.string.kitchen_temp, Math.round(h.temp)));
        col.addView(t);
        TextView chance = SyntaxUi.text(this, 16, R.string.font_light, h.rainChance >= 40 ? SyntaxUi.ACCENT : SyntaxUi.MUTED);
        chance.setText(h.rainChance > 0 ? h.rainChance + "%" : " ");
        col.addView(chance);
        return col;
    }

    private static boolean isDay(long time) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(time);
        int hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 7 && hour < 18;
    }

    /** A random active photo of the local archive, decoded off the UI thread at screen size; none keeps the plain dark. */
    private void loadPhoto() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int w = m.widthPixels, h = m.heightPixels;
        work.execute(() -> {
            try {
                File[] all = WallpaperLibrary.photos(this);
                WallpaperState state = WallpaperLibrary.load(this);
                List<File> active = new ArrayList<>();
                if (all != null) for (File f : all) if (state.active(WallpaperLibrary.id(f))) active.add(f);
                if (active.isEmpty()) return;
                File pick = active.get(new Random().nextInt(active.size()));
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(pick.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeFile(pick.getPath(), o);
                if (b != null) handler.post(() -> {
                    if (!isFinishing()) photo.setImageBitmap(b);
                });
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w("TvHome.Kitchen", "no photo: " + e.getMessage());
            }
        });
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return SyntaxUi.dp(this, v);
    }
}
