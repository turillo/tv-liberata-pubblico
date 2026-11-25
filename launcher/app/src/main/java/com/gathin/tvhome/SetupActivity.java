package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configurazione guidata, at the first start (also after installing with Downloader, without a PC): the device's
 * name, the TV's own adb allowed once ("Consenti sempre"), then everything else in one press (Setup): permissions,
 * Home, base clean-up, DNS filter, updates, and "Fatto". "Annulla tutto e torna come prima" is always on screen.
 * When something is missing (adb debugging off) the page says what to turn on.
 *
 * Fire OS 7.7 refuses adb from the TV itself: there the page becomes "Collega il PC", with the TV's address in large
 * type and the link to the PC program, and it watches every few seconds for what the PC grants (secure settings,
 * the Home service, the VPN consent, Home) until it can say "Fatto!".
 */
public final class SetupActivity extends Activity {

    static final String PC_LINK = "tinyurl.com/Tv-liberata-pc";
    private static final long PC_POLL_MS = 3000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pcPoll = this::pcCheck;
    private EditText name;
    private TextView status, go, powerOn;
    private LinearLayout column, stepsBox, pcBox;
    private boolean busy, pcMode;

    /** First start: the set-up is offered once, unless the PC configured this device. */
    static boolean offer(Context c) {
        if (Setup.done(c) || Setup.prefs(c).getBoolean("offered", false)) return false;
        Setup.prefs(c).edit().putBoolean("offered", true).apply();
        c.startActivity(new Intent(c, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        column = SyntaxUi.scrollPage(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.setup_title), getString(R.string.setup_intro));

        column.addView(SyntaxUi.label(this, getString(R.string.setup_name)));
        name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(DeviceConfig.name(this));
        name.setTextColor(SyntaxUi.TEXT);
        name.setTextSize(20);
        name.setMinWidth(SyntaxUi.dp(this, 420));
        LinearLayout nameRow = SyntaxUi.chipRow(this);
        nameRow.addView(name);
        column.addView(nameRow);

        LinearLayout buttons = SyntaxUi.chipRow(this);
        buttons.setPadding(0, SyntaxUi.dp(this, 14), 0, 0);
        go = SyntaxUi.chip(this, getString(R.string.setup_go));
        go.setOnClickListener(v -> start());
        SyntaxUi.addChip(buttons, go);
        TextView undo = SyntaxUi.chip(this, getString(R.string.setup_undo));
        undo.setOnClickListener(v -> askUndo());
        SyntaxUi.addChip(buttons, undo);
        TextView later = SyntaxUi.chip(this, getString(R.string.setup_later));
        later.setOnClickListener(v -> finish());
        SyntaxUi.addChip(buttons, later);
        powerOn = SyntaxUi.chip(this, "");
        powerOn.setOnClickListener(v -> {
            PowerOnOpen.set(this, !PowerOnOpen.enabled(this));
            showPowerOn();
        });
        SyntaxUi.addChip(buttons, powerOn);
        SyntaxUi.toggle(powerOn);
        showPowerOn();
        column.addView(buttons);

        status = SyntaxUi.text(this, 16, R.string.font_regular, SyntaxUi.TEXT);
        status.setPadding(0, SyntaxUi.dp(this, 14), 0, SyntaxUi.dp(this, 6));
        column.addView(status);
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        column.addView(stepsBox);
        setContentView(outer);
        go.requestFocus();
        if (Setup.done(this)) status.setText(R.string.setup_already);
        // Fire OS 7.7: no adb from the TV itself; say at once that the PC is needed.
        worker.execute(() -> {
            if (AdbLocal.check(this, false) == AdbLocal.BLOCKED) handler.post(this::showPcMode);
        });
    }

    private void showPowerOn() {
        powerOn.setText(getString(PowerOnOpen.enabled(this) ? R.string.power_on_open_yes : R.string.power_on_open_no));
        powerOn.setSelected(PowerOnOpen.enabled(this));
    }

    /** First the TV's adb (the TV asks "Consenti?"), then the steps. */
    private void start() {
        if (busy) return;
        if (pcMode) {
            pcCheck();
            return;
        }
        busy = true;
        stepsBox.removeAllViews();
        status.setText(R.string.setup_adb_checking);
        String chosen = name.getText().toString();
        worker.execute(() -> {
            int adb = AdbLocal.check(this, true);
            if (adb == AdbLocal.BLOCKED) {
                handler.post(() -> {
                    busy = false;
                    showPcMode();
                });
                return;
            }
            if (adb != AdbLocal.OK) {
                handler.post(() -> {
                    busy = false;
                    status.setText(adb == AdbLocal.NO_ADB ? getString(Device.isFireOs() ? R.string.setup_adb_off_fire
                            : R.string.setup_adb_off_tv) : getString(R.string.setup_adb_consent));
                });
                return;
            }
            List<Setup.Step> steps = Setup.run(this, chosen, s -> handler.post(() -> show(s)));
            boolean ok = !steps.isEmpty() && steps.get(steps.size() - 1).ok && Setup.done(this);
            handler.post(() -> {
                busy = false;
                show(steps);
                status.setText(ok ? R.string.setup_done : R.string.setup_failed);
                if (ok) {
                    toHomeButton();
                    // 1.1.0: the cartolina's question, once, at the end of the set-up (focus on "Si").
                    if (!Cartolina.answered(this)) Cartolina.ask(this, Cartolina.INSTALLED, () -> go.requestFocus());
                }
            });
        });
    }

    private void toHomeButton() {
        go.setText(R.string.setup_to_home);
        go.setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
    }

    // ---- "Collega il PC" (Fire OS 7.7) ------------------------------------------------------------------------

    private void showPcMode() {
        if (pcMode || isFinishing()) return;
        pcMode = true;
        go.setText(R.string.setup_pc_check);
        status.setText(R.string.setup_pc_intro);
        stepsBox.removeAllViews();
        pcBox = new LinearLayout(this);
        pcBox.setOrientation(LinearLayout.VERTICAL);
        pcBox.addView(SyntaxUi.label(this, getString(R.string.setup_pc_ip)));
        TextView ip = SyntaxUi.text(this, 54, R.string.font_medium, SyntaxUi.TEXT);
        String address = address();
        ip.setText(address == null ? getString(R.string.setup_pc_no_ip) : address);
        pcBox.addView(ip);
        TextView how = SyntaxUi.text(this, 18, R.string.font_regular, SyntaxUi.TEXT);
        how.setText(getString(R.string.setup_pc_steps, PC_LINK));
        how.setPadding(0, SyntaxUi.dp(this, 10), 0, SyntaxUi.dp(this, 10));
        pcBox.addView(how);
        column.addView(pcBox, column.indexOfChild(stepsBox));
        pcCheck();
    }

    /** What the PC has granted so far, every few seconds until all is there. */
    private void pcCheck() {
        handler.removeCallbacks(pcPoll);
        if (isFinishing()) return;
        boolean secure = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        boolean service = !Device.isFireOs() || PowerOnOpen.guardEnabled(this);
        boolean vpn;
        try {
            vpn = VpnService.prepare(this) == null;
        } catch (RuntimeException e) {
            vpn = false;
        }
        boolean home = Device.isFireOs() ? FireHomeGuard.isRunning() : weAreHome();
        stepsBox.removeAllViews();
        row(secure, R.string.setup_pc_secure);
        if (Device.isFireOs()) row(service, R.string.setup_pc_service);
        row(vpn, R.string.setup_pc_vpn);
        row(home, R.string.setup_pc_home);
        if (secure && service && vpn && home) {
            Setup.markDone(this);
            status.setText(R.string.setup_done);
            if (pcBox != null) pcBox.setVisibility(View.GONE);
            showPowerOn();
            toHomeButton();
            go.requestFocus();
            // The PC program asks it too: only when its answer did not come with the configuration.
            if (!Cartolina.answered(this)) Cartolina.ask(this, Cartolina.INSTALLED, () -> go.requestFocus());
            return;
        }
        handler.postDelayed(pcPoll, PC_POLL_MS);
    }

    private void row(boolean ok, int text) {
        TextView t = SyntaxUi.text(this, 16, R.string.font_regular, ok ? SyntaxUi.TEXT : SyntaxUi.MUTED);
        t.setText((ok ? "✓  " : "…  ") + getString(text));
        t.setPadding(0, SyntaxUi.dp(this, 4), 0, SyntaxUi.dp(this, 4));
        stepsBox.addView(t);
    }

    private boolean weAreHome() {
        ResolveInfo ri = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    /** The TV's address on the home network (Wi-Fi or cable), not the filter's tunnel. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // no address
        }
        return null;
    }

    private void show(List<Setup.Step> steps) {
        if (isFinishing()) return;
        stepsBox.removeAllViews();
        for (Setup.Step s : steps) {
            TextView t = SyntaxUi.text(this, 15, R.string.font_regular, s.ok ? SyntaxUi.TEXT : SyntaxUi.MUTED);
            t.setText((s.ok ? "✓  " : "…  ") + s.name + (s.detail == null ? "" : " · " + s.detail));
            t.setPadding(0, SyntaxUi.dp(this, 4), 0, SyntaxUi.dp(this, 4));
            stepsBox.addView(t);
        }
    }

    private void askUndo() {
        SyntaxDialog.show(this, getString(R.string.setup_undo), getString(R.string.setup_undo_question),
                new String[]{getString(R.string.syntax_yes), getString(R.string.syntax_no)}, 1, which -> {
                    if (which != 0 || busy) return;
                    busy = true;
                    status.setText(R.string.sysapps_working);
                    worker.execute(() -> {
                        boolean adb = AdbLocal.authorized(this);
                        String left = adb ? Setup.undo(this) : null;
                        handler.post(() -> {
                            busy = false;
                            if (!adb) status.setText(pcMode ? getString(R.string.setup_undo_needs_pc, PC_LINK)
                                    : getString(R.string.setup_undo_needs_adb));
                            else status.setText(left.isEmpty() ? getString(R.string.setup_undone)
                                    : getString(R.string.setup_undone_partly, left));
                        });
                    });
                });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

}
