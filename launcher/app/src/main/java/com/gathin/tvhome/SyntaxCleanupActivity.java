package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sistema, Pulizia: empty Syntax TV's regenerable cache, thin the wallpaper archive (never the favourites, always
 * after a question with "No" focused), and the installed apps by size, each opening its system page, where the
 * owner can clear that app's cache (Android lets no app clear another's).
 */
public final class SyntaxCleanupActivity extends Activity {

    /** The adb command that lets Syntax TV read other apps' sizes. */
    static final String USAGE_GRANT = "adb shell appops set com.gathin.tvhome GET_USAGE_STATS allow";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView cacheLine, archiveLine;
    private LinearLayout appList;
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);
        TextView title = SyntaxUi.text(this, 30, R.string.font_medium, SyntaxUi.TEXT);
        title.setText("Pulizia");
        column.addView(title);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setClipChildren(false);
        cols.setPadding(0, SyntaxUi.dp(this, 16), 0, 0);
        column.addView(cols, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        cols.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout cache = box(left, "Cache di TV Liberata");
        cache.addView(note("Miniature, immagini delle scene e notizie: si riscaricano da sole. "
                + "Archivi sfondi, preferite e impostazioni restano."));
        LinearLayout cacheRow = SyntaxUi.chipRow(this);
        cacheRow.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
        TextView clear = SyntaxUi.chip(this, "Svuota cache");
        clear.setOnClickListener(v -> clearCache());
        SyntaxUi.addChip(cacheRow, clear);
        cache.addView(cacheRow);
        cacheLine = note("");
        cache.addView(cacheLine);

        LinearLayout archive = box(left, "Archivio sfondi");
        archiveLine = note("…");
        archive.addView(archiveLine);
        LinearLayout a1 = SyntaxUi.chipRow(this);
        a1.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
        TextView inactive = SyntaxUi.chip(this, "Elimina non attive");
        inactive.setOnClickListener(v -> askInactive());
        SyntaxUi.addChip(a1, inactive);
        archive.addView(a1);
        LinearLayout a2 = SyntaxUi.chipRow(this);
        a2.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
        TextView keepLabel = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
        keepLabel.setText("Tieni le ultime");
        keepLabel.setPadding(0, 0, SyntaxUi.dp(this, 12), 0);
        a2.addView(keepLabel);
        for (int n : ArchiveCleanup.KEEP) {
            TextView chip = SyntaxUi.chip(this, String.valueOf(n));
            chip.setOnClickListener(v -> askKeep(n));
            SyntaxUi.addChip(a2, chip);
        }
        archive.addView(a2);

        LinearLayout apps = box(cols, "App per spazio occupato");
        ((LinearLayout.LayoutParams) apps.getLayoutParams()).setMarginStart(SyntaxUi.dp(this, 16));
        ((LinearLayout.LayoutParams) apps.getLayoutParams()).weight = 1f;
        ((LinearLayout.LayoutParams) apps.getLayoutParams()).width = 0;
        ((LinearLayout.LayoutParams) apps.getLayoutParams()).height = LinearLayout.LayoutParams.MATCH_PARENT;
        apps.addView(note("OK su un'app apre la sua pagina di sistema, dove si svuota la sua cache."));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        int sp = SyntaxUi.dp(this, 6);
        scroll.setPadding(sp, sp, sp, sp);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.setClipChildren(false);
        scroll.addView(appList);
        apps.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        appList.addView(note("Lettura in corso…"));

        setContentView(outer);
        clear.requestFocus();
        loadArchive();
        loadApps();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- cache ---------------------------------------------------------------------------------------------

    private void clearCache() {
        if (busy) return;
        busy = true;
        cacheLine.setText("Pulizia in corso…");
        worker.execute(() -> {
            long freed = SystemCleanup.clearCache(this);
            handler.post(() -> {
                busy = false;
                if (isFinishing()) return;
                cacheLine.setText("Liberati " + SystemStats.bytes(freed) + ".");
            });
        });
    }

    // ---- archive -------------------------------------------------------------------------------------------

    private void loadArchive() {
        worker.execute(() -> {
            File[] photos = WallpaperLibrary.photos(this);
            List<String> ids = WallpaperLibrary.ids(photos);
            WallpaperState s = WallpaperLibrary.load(this);
            int off = ArchiveCleanup.inactive(ids, s).size();
            int fav = 0;
            for (String id : ids) if (s.favorite(id)) fav++;
            long bytes = SystemProbe.size(WallpaperLibrary.archive(this, false));
            String text = ids.size() + " foto · " + off + " non attive · " + fav + " preferite · " + SystemStats.bytes(bytes);
            handler.post(() -> {
                if (!isFinishing()) archiveLine.setText(text);
            });
        });
    }

    private void askInactive() {
        worker.execute(() -> {
            List<String> ids = ArchiveCleanup.inactive(WallpaperLibrary.ids(WallpaperLibrary.photos(this)),
                    WallpaperLibrary.load(this));
            handler.post(() -> {
                if (isFinishing()) return;
                if (ids.isEmpty()) {
                    SyntaxDialog.message(this, "Archivio sfondi", "Non ci sono foto non attive.");
                    return;
                }
                confirm("Eliminare " + ids.size() + " foto non attive?",
                        "Non verranno riscaricate. Le preferite restano.", () -> delete(ids, true));
            });
        });
    }

    private void askKeep(int keep) {
        worker.execute(() -> {
            List<String> ids = ArchiveCleanup.beyondNewest(WallpaperLibrary.ids(WallpaperLibrary.photos(this)), keep,
                    WallpaperLibrary.load(this));
            handler.post(() -> {
                if (isFinishing()) return;
                if (ids.isEmpty()) {
                    SyntaxDialog.message(this, "Archivio sfondi", "Ci sono già al massimo " + keep + " foto (preferite a parte).");
                    return;
                }
                confirm("Eliminare le " + ids.size() + " foto più vecchie?",
                        "Restano le " + keep + " più recenti e tutte le preferite.", () -> delete(ids, false));
            });
        });
    }

    /** "No" is the first choice and the focused one. */
    private void confirm(String title, String message, Runnable yes) {
        SyntaxDialog.show(this, title, message, new String[]{"No", "Sì, elimina"}, 0, i -> {
            if (i == 1) yes.run();
        });
    }

    private void delete(List<String> ids, boolean forever) {
        archiveLine.setText("Eliminazione in corso…");
        worker.execute(() -> {
            long freed = SystemCleanup.deletePhotos(this, ids, forever);
            handler.post(() -> {
                if (isFinishing()) return;
                SyntaxDialog.message(this, "Archivio sfondi", "Liberati " + SystemStats.bytes(freed) + ".");
                loadArchive();
            });
        });
    }

    // ---- apps ----------------------------------------------------------------------------------------------

    private void loadApps() {
        worker.execute(() -> {
            List<SystemCleanup.App> apps = null;
            boolean refused = false;
            try {
                apps = SystemCleanup.apps(this);
            } catch (SystemCleanup.NoAccess e) {
                refused = true;
            } catch (RuntimeException e) {
                apps = null;
            }
            List<SystemCleanup.App> found = apps;
            boolean noAccess = refused;
            handler.post(() -> {
                if (isFinishing()) return;
                appList.removeAllViews();
                if (noAccess) {
                    appList.addView(note("Android non permette di leggere lo spazio delle altre app. "
                            + "Per abilitarlo, una volta, dal PC:\n" + USAGE_GRANT));
                    return;
                }
                if (found == null || found.isEmpty()) {
                    appList.addView(note("Nessuna app da mostrare."));
                    return;
                }
                for (SystemCleanup.App app : found) appList.addView(appRow(app));
            });
        });
    }

    private TextView appRow(SystemCleanup.App app) {
        TextView t = SyntaxUi.text(this, 14, R.string.font_regular, SyntaxUi.TEXT);
        String cache = app.cache > 0 ? " · cache " + SystemStats.bytes(app.cache) : "";
        t.setText(app.label + "   " + SystemStats.bytes(app.total) + cache);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER_VERTICAL);
        int p = SyntaxUi.dp(this, 12);
        t.setPadding(p, SyntaxUi.dp(this, 8), p, SyntaxUi.dp(this, 8));
        t.setBackground(SyntaxUi.cardBackground(this, 12, SyntaxUi.CARD));
        SyntaxUi.focusable(t, 1.02f);
        t.setOnClickListener(v -> openApp(app.pkg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = SyntaxUi.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private void openApp(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            SyntaxDialog.message(this, "App", "Questo dispositivo non apre la pagina di sistema delle app.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from an app's system page: its size may have changed.
        if (appList != null && appList.getChildCount() > 0 && appList.getChildAt(0).isFocusable()) loadApps();
    }

    // ---- layout --------------------------------------------------------------------------------------------

    private LinearLayout box(LinearLayout parent, String title) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setClipChildren(false);
        b.setBackground(SyntaxUi.rounded(this, 16, SyntaxUi.CARD, 1, SyntaxUi.CARD_EDGE));
        int pad = SyntaxUi.dp(this, 16);
        b.setPadding(pad, SyntaxUi.dp(this, 2), pad, pad);
        TextView t = SyntaxUi.label(this, title);
        t.setPadding(0, SyntaxUi.dp(this, 12), 0, SyntaxUi.dp(this, 6));
        b.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0 && parent.getOrientation() == LinearLayout.VERTICAL) {
            lp.topMargin = SyntaxUi.dp(this, 14);
        }
        parent.addView(b, lp);
        return b;
    }

    private TextView note(String text) {
        TextView t = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
        t.setText(text);
        t.setPadding(0, SyntaxUi.dp(this, 6), 0, 0);
        return t;
    }
}
