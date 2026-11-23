package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configurazione guidata, at the first start (also after installing with Downloader, without a PC): the device's
 * name, the TV's own adb allowed once ("Consenti sempre"), then everything else in one press (Setup): permissions,
 * Home, base clean-up, DNS filter, updates, and "Fatto". "Annulla tutto e torna come prima" is always on screen.
 * When something is missing (adb debugging off) the page says what to turn on.
 */
public final class SetupActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText name;
    private TextView status, go;
    private LinearLayout stepsBox;
    private boolean busy;

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
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
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
    }

    /** First the TV's adb (the TV asks "Consenti?"), then the steps. */
    private void start() {
        if (busy) return;
        busy = true;
        stepsBox.removeAllViews();
        status.setText(R.string.setup_adb_checking);
        String chosen = name.getText().toString();
        worker.execute(() -> {
            int adb = AdbLocal.check(this, true);
            if (adb != AdbLocal.OK) {
                handler.post(() -> {
                    busy = false;
                    status.setText(adb == AdbLocal.BLOCKED ? getString(R.string.adb_blocked) : adb == AdbLocal.NO_ADB ? getString(Device.isFireOs() ? R.string.setup_adb_off_fire
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
                    go.setText(R.string.setup_to_home);
                    go.setOnClickListener(v -> {
                        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        finish();
                    });
                }
            });
        });
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
                            if (!adb) status.setText(R.string.setup_undo_needs_adb);
                            else status.setText(left.isEmpty() ? getString(R.string.setup_undone)
                                    : getString(R.string.setup_undone_partly, left));
                        });
                    });
                });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

}
