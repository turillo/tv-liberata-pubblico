package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    private final boolean online;
    /** online_wallpaper: the general archive (getExternalFilesDir/bing-wallpapers); null without storage. */
    private final File archive;
    /** The Christmas archive (getExternalFilesDir/xmas-wallpapers), used instead while the Christmas mode is on. */
    private final File xmasArchive;
    /** The UI is 3840 wide or more: download Bing's _UHD.jpg instead of _1920x1080.jpg. */
    private final boolean uhd;
    /** The photo on screen came from the Christmas archive. */
    private boolean displayedXmas;
    /** How long a change fades across. */
    private static final int FADE_MS = 1_000;
    /** Downloads run on their own thread, so a slow network never holds up a picture change. */
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private boolean fetching;
    private boolean showingArchive;
    private final Set<String> unreadable = new HashSet<>();
    static final String PREFS = "wallpaper_rotation";
    static final String BING_API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=it-IT";
    private static final long BING_CHECK = 6 * 3_600_000L, BING_RETRY = 600_000L;
    private static final long BING_MAX_BYTES = 12_000_000L, BING_MAX_JSON = 256_000L;
    private static final int ONLINE_SHADE = 0x99000000;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    /** For the Syntax TV state (active, favourite and deleted photos) and the photos' Bing titles. */
    private final Context context;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        files = new ArrayList<>(config.wallpapers);
        online = onlineEnabled(context);
        archive = online ? WallpaperLibrary.archive(context, false) : null;
        xmasArchive = online ? WallpaperLibrary.archive(context, true) : null;
        // The Christmas photos used to share the general archive: moved to their own, once (a few renames).
        if (online) WallpaperLibrary.migrate(this.context);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        double[] home = moods.isEmpty() ? null : Place.home(context);
        latitude = home == null ? "" : String.valueOf(home[0]);
        longitude = home == null ? "" : String.valueOf(home[1]);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        uhd = Math.max(width, height) >= 3840;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && (!files.isEmpty() || archive != null)) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (archive == null || !showArchivedFirst()) showFirst();
        }
    }

    /** Home reopens on the archived photo it last showed, instead of wallpaper.jpg with the photo popping in later. */
    private boolean showArchivedFirst() {
        String name = preferences.getString("bing_image", "");
        boolean xmas = useXmas();
        File file = new File(xmas ? xmasArchive : archive, name);
        // A photo from the other archive (the Christmas mode was switched since) is not shown again.
        if (name.isEmpty() || !file.isFile()) return false;
        Bitmap bitmap = decode(file, ONLINE_SHADE);
        if (bitmap == null) return false;
        show(bitmap, false);
        root.setBackground(null);
        displayed = name;
        displayedXmas = xmas;
        showingArchive = true;
        return true;
    }

    /**
     * The Christmas mode (with its wallpapers switched on) uses only the Christmas archive's active photos, and the
     * general archive otherwise; with no Christmas photo yet, the general one stays.
     */
    private boolean useXmas() {
        if (xmasArchive == null || !SyntaxPrefs.get(context).xmas(SyntaxSettings.XMAS_WALLPAPERS)) return false;
        String[] names = xmasArchive.list((dir, name) -> name.endsWith(".jpg"));
        return names != null && names.length > 0;
    }

    /** Syntax TV's interval for the archive in use (15 minutes to 1 hour), read at each check so a change applies. */
    private long interval(boolean xmas) {
        SyntaxSettings s = SyntaxPrefs.get(context);
        return (xmas ? s.xmasWallpaperMinutes() : s.wallpaperMinutes()) * 60_000L;
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap, false);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    /**
     * Set when the TV is switched on (screen on), and at a start shortly after boot: the next check on Home takes a
     * new photo if Syntax TV's "Nuovo sfondo a ogni accensione" is on. Cleared once used.
     */
    private static volatile boolean wokeUp = android.os.SystemClock.elapsedRealtime() < 10 * 60_000L;

    /** MainActivity's screen-on receiver. */
    static void screenOn() {
        wokeUp = true;
    }

    /** True once after a power-on, if that should change the photo; then false until the next one. */
    private boolean takeWake() {
        if (!wokeUp) return false;
        wokeUp = false;
        return SyntaxPrefs.get(context).wallpaperOnWake();
    }

    void start() {
        active = true;
        // Once, after Home Assistant was dropped: its copies get the TV's own names back, and the TV's own
        // downloads start at the next minute instead of at the old schedule.
        network.execute(() -> {
            if (HaLeftovers.run(context)) preferences.edit().remove("bing_next").apply();
        });
        update();
    }

    void stop() {
        active = false;
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
        network.shutdownNow();
    }

    void update() {
        if (online) {
            updateOnline();
            return;
        }
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.dueNow(changed, System.currentTimeMillis(), interval, wokeUp, takeWake())) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap, true);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    private static boolean onlineEnabled(Context context) {
        try {
            File config = new File(context.getExternalFilesDir(null), Config.FILE_NAME);
            String value = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(value).optBoolean("online_wallpaper", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * online_wallpaper: at most every 6 hours, and only while Home is visible, adds Bing's new photos to the archive
     * (10 minutes after a failure). Every interval shows a random archived photo, offline too, picked from the local
     * archive (nothing is downloaded for a change); an empty archive keeps the theme's wallpaper.jpg. Home's minute
     * tick calls this, so the changes go on while Home stays open.
     */
    private void updateOnline() {
        if (!active || closed || archive == null) return;
        long now = System.currentTimeMillis();
        long next = preferences.getLong("bing_next", 0);
        if (!fetching && (now >= next || next - now > BING_CHECK)) {
            fetching = true;
            network.execute(() -> {
                // Bing (when ticked in Syntax TV), then the other ticked sources, into the same archive.
                SyntaxSettings syntax = SyntaxPrefs.get(context);
                // Copying another TV's wallpapers: not even Bing comes from the internet here.
                boolean bing = syntax.syncFrom() == null && syntax.sources().contains(WallpaperSources.BING);
                boolean done = !bing || fetchBing();
                if (!closed) new WallpaperFetcher(context, archive).run();
                long wait = done ? BING_CHECK : BING_RETRY;
                root.post(() -> {
                    fetching = false;
                    preferences.edit().putLong("bing_next", System.currentTimeMillis() + wait).apply();
                    update();   // the first photos show at once when the archive was empty
                });
            });
        }
        rotateArchive(now);
    }

    private void rotateArchive(long now) {
        if (loading) return;
        boolean xmas = useXmas();
        // The Christmas mode switched on or off: the other archive's photo goes at once, not after the interval.
        boolean wake = wokeUp && takeWake();
        if (showingArchive && displayedXmas == xmas
                && !WallpaperRotation.dueNow(preferences.getLong("bing_changed", 0), now, interval(xmas), wake, wake)) return;
        File next = pickArchived(xmas);
        if (next == null) return;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next, ONLINE_SHADE);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    unreadable.add(next.getName());   // skipped, never deleted
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap, true);
                root.setBackground(null);
                displayed = next.getName();
                displayedXmas = xmas;
                showingArchive = true;
                showCaption(true);
                preferences.edit().putString("bing_image", displayed)
                        .putLong("bing_changed", System.currentTimeMillis()).apply();
            });
        });
    }

    /**
     * The next archived photo, as Syntax TV set them: only the active ones (all of them when none is), favourites
     * about twice as often, never a deleted one, and not the one on screen when there is another. Null when there is
     * nothing else to show.
     */
    private File pickArchived(boolean xmas) {
        File dir = xmas ? xmasArchive : archive;
        File[] list = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (list == null) return null;
        List<String> ids = new ArrayList<>();
        for (File file : list) {
            if (!unreadable.contains(file.getName())) ids.add(WallpaperLibrary.id(file));
        }
        boolean same = showingArchive && displayedXmas == xmas;
        String current = same && displayed.endsWith(".jpg") ? displayed.substring(0, displayed.length() - 4) : null;
        String id = WallpaperLibrary.load(context, xmas).pick(ids, current, random);
        if (id == null || (id.equals(current) && ids.size() <= 1 && same)) return null;
        return new File(dir, id + ".jpg");
    }

    /** Runs on the network thread. True when the list was read and every new photo was saved. */
    private boolean fetchBing() {
        try {
            if (!archive.isDirectory() && !archive.mkdirs()) return false;
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            download(BING_API, json, BING_MAX_JSON);
            JSONArray images = new JSONObject(json.toString("UTF-8")).getJSONArray("images");
            String suffix = uhd ? "_UHD.jpg" : "_1920x1080.jpg";
            boolean done = true;
            WallpaperState state = WallpaperLibrary.load(context);
            for (int i = 0; i < images.length() && !closed; i++) {
                JSONObject entry = images.optJSONObject(i);
                if (entry == null) continue;
                String base = entry.optString("urlbase", "");
                String path = base.isEmpty() ? entry.optString("url", "").replace("_1920x1080.jpg", suffix) : base + suffix;
                String id = bingId(path);
                if (id == null || state.blacklist.contains(id)) continue;   // deleted in Syntax TV: never again
                File target = new File(archive, id + ".jpg");
                boolean have = target.isFile() || fetchPhoto("https://www.bing.com" + path, target);
                if (!have) done = false;
                // The title and description for Syntax TV's preview, also for photos archived before it kept them.
                if (have && !state.titles.containsKey(id)) {
                    WallpaperLibrary.remember(context, id, entry.optString("title", ""), entry.optString("copyright", ""));
                }
            }
            if (done) ServiceStatus.ok(context, ServiceStatus.BING);
            else if (!closed) ServiceStatus.fail(context, ServiceStatus.BING, "foto non scaricate");
            return done;
        } catch (Exception failure) {
            Log.w("TvHome.Wallpaper", "Bing archive unavailable", failure);
            ServiceStatus.fail(context, ServiceStatus.BING, failure);
            return false;
        }
    }

    /** Saves a photo only once it downloaded completely and decodes. */
    private boolean fetchPhoto(String address, File target) {
        File part = new File(target.getPath() + ".part");
        try (FileOutputStream output = new FileOutputStream(part)) {
            download(address, output, BING_MAX_BYTES);
        } catch (Exception failure) {
            Log.w("TvHome.Wallpaper", "Bing photo unavailable: " + target.getName(), failure);
            part.delete();
            return false;
        }
        boolean saved = decodes(part) && part.renameTo(target);
        if (!saved) part.delete();
        return saved;
    }

    /** OHR.Name_IT-IT1234567890 from {@code /th?id=OHR.Name_IT-IT1234567890_1920x1080.jpg&...}, or null. */
    static String bingId(String path) {
        int start = path.indexOf("id=");
        if (start < 0) return null;
        int end = path.indexOf('&', start);
        String id = path.substring(start + 3, end < 0 ? path.length() : end)
                .replaceAll("\\.jpg$", "").replaceAll("_(UHD|\\d+x\\d+)$", "");
        return id.startsWith("OHR.") && id.matches("[A-Za-z0-9._-]+") ? id : null;
    }

    private static void download(String address, OutputStream output, long limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            connection.connect();
            long length = connection.getContentLengthLong();
            if (connection.getResponseCode() != 200 || length > limit) {
                throw new IOException("Request failed or exceeded size limit: " + connection.getResponseCode());
            }
            long size = 0;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > limit) throw new IOException("Exceeded size limit");
                    output.write(buffer, 0, count);
                }
            }
            if (length > 0 && size != length) throw new IOException("Incomplete download");
        } finally {
            connection.disconnect();
        }
    }

    /** A small decode (1/8 size) proves the whole file reads without holding a full-size picture in memory. */
    private static boolean decodes(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (!WallpaperSize.accepted(options.outWidth, options.outHeight)) return false;
        options.inJustDecodeBounds = false;
        options.inSampleSize = 8;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) return false;
            bitmap.recycle();
            return true;
        } catch (OutOfMemoryError failure) {
            return false;
        }
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /**
     * Puts a picture on screen and releases the previous one once it is no longer drawn. With {@code fade} (a
     * change while Home is open) the new picture dissolves in over the old one for a second.
     */
    private void show(Bitmap bitmap, boolean fade) {
        Bitmap old = current;
        current = bitmap;
        Drawable shown = image.getDrawable();
        if (shown instanceof TransitionDrawable) shown = ((TransitionDrawable) shown).getDrawable(1);
        if (fade && old != null && shown != null) {
            TransitionDrawable t = new TransitionDrawable(new Drawable[]{shown,
                    new BitmapDrawable(context.getResources(), bitmap)});
            t.setCrossFadeEnabled(true);
            image.setImageDrawable(t);
            t.startTransition(FADE_MS);
            // Once the fade is over, only the new picture is drawn, and the old one can go.
            image.postDelayed(() -> {
                if (current == bitmap) image.setImageBitmap(bitmap);
                if (old != bitmap && old != current) old.recycle();
            }, FADE_MS + 300);
            return;
        }
        image.setImageBitmap(bitmap);
        if (old != null && old != bitmap) image.postDelayed(old::recycle, 500);
    }

    private Bitmap decode(File file) {
        return decode(file, dimColor);
    }

    private Bitmap decode(File file, int shade) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = shade != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (shade != 0) new Canvas(decoded).drawColor(shade);
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
