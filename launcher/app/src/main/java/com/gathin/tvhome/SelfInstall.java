package com.gathin.tvhome;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;

/**
 * TV Liberata's own update through Android's installer, confirmed by FireHomeGuard so the user does not have to:
 * Updater arms a short window just before it hands its own, signature-checked APK to the installer, and the
 * accessibility service presses "Installa" only while that window is open and only on the screen that names
 * TV Liberata. Without the service nothing changes: the user confirms, as in 1.0.
 */
final class SelfInstall {

    private static final String PREFS = "self_install", UNTIL = "until";
    /** Long enough for a download already finished and the installer to come up; short enough to never linger. */
    static final long WINDOW_MS = 3 * 60_000L;

    private SelfInstall() {
    }

    /** Android's own installer (AOSP and Google's build). Plain Java, tested. */
    static boolean installer(CharSequence pkg) {
        if (pkg == null) return false;
        String p = pkg.toString();
        return p.equals("com.android.packageinstaller") || p.equals("com.google.android.packageinstaller");
    }

    /** True while {@code now} is inside a window that ends at {@code until}. Plain Java, tested. */
    static boolean within(long now, long until) {
        return until > 0 && now < until && until - now <= WINDOW_MS;
    }

    static void arm(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(UNTIL, System.currentTimeMillis() + WINDOW_MS).apply();
    }

    static void disarm(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(UNTIL).apply();
    }

    static boolean armed(Context c) {
        return within(System.currentTimeMillis(), c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(UNTIL, 0));
    }

    /** The install screen is about TV Liberata: its name is written on it. */
    static boolean ours(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
        boolean ours = found != null && !found.isEmpty();
        if (found != null) for (AccessibilityNodeInfo n : found) n.recycle();
        return ours;
    }

    /**
     * Clicks the button with this view id when it is enabled and on screen. Android disables "Installa" until the
     * permissions list was scrolled to its end on some versions: with {@code scroll}, the list is scrolled instead.
     */
    static boolean press(AccessibilityNodeInfo root, String viewId, boolean scroll) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(viewId);
        if (found == null || found.isEmpty()) return false;
        boolean clicked = false;
        AccessibilityNodeInfo b = found.get(0);
        if (b.isEnabled() && b.isVisibleToUser()) clicked = b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        else if (scroll) scrollDown(root);
        for (AccessibilityNodeInfo n : found) n.recycle();
        return clicked;
    }

    private static void scrollDown(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 200) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.isScrollable()) {
                n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                return;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
    }
}
