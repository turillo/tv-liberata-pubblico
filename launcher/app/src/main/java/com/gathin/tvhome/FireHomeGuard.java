package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;

/**
 * Fire OS only: makes this launcher the home screen without touching Amazon's. Fire OS keeps HOME bound to its
 * protected launcher (never disable it) and handles the HOME key before any accessibility filter sees it, so this
 * service waits for Amazon's home window and replaces it. Ported from dumbtv home's Guard (MIT), which does the same.
 *
 * A plain startActivity right after a HOME press is held back by Android's five-second app-switch delay. An
 * alarm-fired PendingIntent is sent by system_server, which is exempt, so {@link #home} launches through an alarm.
 * That alarm only fires at once with min_futurity=0 in the global alarm_manager_constants, which the Fire TV install
 * script sets (dumbtv sets the same). Amazon's home is covered with a plain overlay until ours has drawn.
 *
 * Waking from standby resumes whatever was on screen without a new window event: if that was Amazon's home screen,
 * ours is brought up at once. Amazon's other launcher screens, such as its settings grid, are left alone.
 *
 * Never enable it together with dumbtv home's Guard: both would claim the home screen.
 */
public final class FireHomeGuard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long COVER_MAX_MS = 1500;
    private static final long REPEAT_MS = 1000;
    private static final long LAUNCH_FALLBACK_MS = 150;
    private static final int COVER_COLOR = 0xFF0B1418;   // close to Home's dark background, so no flash shows

    private static volatile FireHomeGuard running;

    /** True while the service is bound: Amazon's home is replaced and TV Liberata's own updates are confirmed. */
    static boolean isRunning() {
        return running != null;
    }

    /**
     * The second way to see HOME, besides the window event: on Fire OS 7.7 the HOME key usually reaches no filter,
     * but when it does and Amazon's home is already in front (it sent no new window), TV Liberata comes back.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && event.getAction() == KeyEvent.ACTION_UP && onAmazonHome) {
            handler.postDelayed(this::launchWhenCovered, 150);
        }
        return false;   // never consumed: the remote works as before
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launch = () -> home(this, 0);
    private View cover;
    private long lastLaunch;
    private boolean onAmazonHome;

    /**
     * HOME pressed (Android says so to every app with ACTION_CLOSE_SYSTEM_DIALOGS, reason "homekey"): with Amazon's
     * home already in front no new window comes up, so TV Liberata is brought back here. Fire OS keeps this broadcast
     * behind an Amazon permission; it costs nothing where it does not arrive.
     */
    private final BroadcastReceiver homeKey = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!"homekey".equals(i.getStringExtra("reason"))) return;
            if (onAmazonHome) handler.postDelayed(FireHomeGuard.this::launchWhenCovered, 300);
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (onAmazonHome) launchWhenCovered();
        }
    };

    private static void home(Context c, long delayMs) {
        try {
            AlarmManager alarms = c.getSystemService(AlarmManager.class);
            alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, launch(c));
        } catch (RuntimeException e) {
            // No alarm: start directly, slower after a HOME press but still home.
            try {
                c.startActivity(homeIntent(c));
            } catch (RuntimeException ignored) {
                // nothing more to try; Amazon's home stays
            }
        }
    }

    /** Home is on screen: drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        if (running == null) return;
        try {
            c.getSystemService(AlarmManager.class).cancel(launch(c));
        } catch (RuntimeException ignored) {
            // nothing queued
        }
    }

    /** Called once Home's window is on screen. */
    static void shown() {
        FireHomeGuard g = running;
        if (g != null) g.handler.post(g.uncover);
    }

    private static Intent homeIntent(Context c) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private static PendingIntent launch(Context c) {
        return PendingIntent.getActivity(c, 0, homeIntent(c),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        PowerOff.connected(this);
        HomeRestore.wanted(this, true);   // TV Liberata: put back on if Fire OS drops it
        try {
            View view = new View(this);
            view.setVisibility(View.GONE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            lp.windowAnimations = 0;
            getSystemService(WindowManager.class).addView(view, lp);
            cover = view;
        } catch (RuntimeException e) {
            cover = null;   // no cover: Amazon's home may flash, the takeover still works
        }
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        listenForHome();
        // Reconnected (after an update, or our process was killed) with Amazon's home already in front: it sends
        // no new window event, so it would stay until the next app. Look once, shortly after the bind.
        handler.postDelayed(this::replaceIfAmazonInFront, 1000);
    }

    /** HOME's system broadcast; Android 13+ wants the flag (the system sends it, so "exported"). */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void listenForHome() {
        IntentFilter f = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(homeKey, f, RECEIVER_EXPORTED);
        else registerReceiver(homeKey, f);
    }

    private void replaceIfAmazonInFront() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (!w.isActive() || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                CharSequence pkg = root == null ? null : root.getPackageName();
                if (root != null) root.recycle();
                if (pkg != null && AMAZON.contentEquals(pkg)) {
                    onAmazonHome = true;
                    launchWhenCovered();
                }
                return;
            }
        } catch (RuntimeException ignored) {
            // no window list: the next window event does it
        }
    }

    /** Turned off while running (Settings, or adb): the user's choice, so HomeRestore leaves it off. */
    @Override
    public boolean onUnbind(Intent intent) {
        HomeRestore.wanted(this, false);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        PowerOff.gone(this);
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenOn);
            unregisterReceiver(homeKey);
        } catch (IllegalArgumentException ignored) {
            // never registered
        }
        if (cover != null) {
            try {
                getSystemService(WindowManager.class).removeView(cover);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            onAmazonHome = false;
            if (!getPackageName().contentEquals(pkg)) arrived(this);
            if (SelfInstall.installer(pkg) && SelfInstall.armed(this)) confirmInstall(0);
            return;
        }
        // Only Amazon's main home screen: its settings and other screens are left alone.
        onAmazonHome = cls != null && AMAZON_HOME.contentEquals(cls);
        if (!onAmazonHome) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < REPEAT_MS) return;
        lastLaunch = now;
        launchWhenCovered();
    }

    /** Shows the cover, then launches once that window is visible to the window manager (or after a short fallback). */
    private void launchWhenCovered() {
        handler.removeCallbacks(launch);
        if (cover == null) {
            home(this, 0);
            return;
        }
        boolean shown = cover.getVisibility() == View.VISIBLE;
        cover();
        if (shown) {
            home(this, 0);
            return;
        }
        cover.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                cover.getViewTreeObserver().removeOnPreDrawListener(this);
                handler.removeCallbacks(launch);
                home(FireHomeGuard.this, 0);
                return true;
            }
        });
        handler.postDelayed(launch, LAUNCH_FALLBACK_MS);
    }

    private void cover() {
        if (cover == null) return;
        cover.setBackgroundColor(COVER_COLOR);
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    // ---- TV Liberata's own update: the system installer's buttons, pressed for the user ---------------------------

    private static final int CONFIRM_TRIES = 40;
    private static final long CONFIRM_EVERY_MS = 400;
    private static final Object CONFIRM_TOKEN = new Object();

    /**
     * Android's install screen for TV Liberata's own update, started by TV Liberata a moment ago (SelfInstall.arm):
     * presses "Installa", then "Apri" or "Fine" if the installer shows them. Any other install screen, or this one
     * after the short window, is left to the user. Looks again a few times while the screen draws its buttons.
     */
    private void confirmInstall(int attempt) {
        handler.removeCallbacksAndMessages(CONFIRM_TOKEN);
        if (attempt >= CONFIRM_TRIES || !SelfInstall.armed(this)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            CharSequence pkg = root.getPackageName();
            if (pkg != null && SelfInstall.installer(pkg) && SelfInstall.ours(root, getString(R.string.app_name))) {
                String id = pkg + ":id/";
                if (SelfInstall.press(root, id + "ok_button", true)) {
                    Log.i("TvHome.Guard", "own update: Installa pressed");
                } else if (SelfInstall.press(root, id + "launch_button", false)
                        || SelfInstall.press(root, id + "done_button", false)) {
                    SelfInstall.disarm(this);
                    root.recycle();
                    return;
                }
            }
            root.recycle();
        }
        handler.postAtTime(() -> confirmInstall(attempt + 1), CONFIRM_TOKEN, SystemClock.uptimeMillis() + CONFIRM_EVERY_MS);
    }

    @Override
    public void onInterrupt() {
    }
}
