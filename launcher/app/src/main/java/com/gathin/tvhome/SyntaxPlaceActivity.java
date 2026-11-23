package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syntax TV, Sistema, "Posizione meteo": the place the weather is for. "Automatica" finds it from the internet
 * connection (Place); a city typed here and picked from Open-Meteo's search replaces it until "Automatica" again.
 * Only offered when config.xml has no place (TV Liberata).
 */
public final class SyntaxPlaceActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView current, status;
    private EditText query;
    private LinearLayout results;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.place_title), getString(R.string.place_intro));

        current = SyntaxUi.text(this, 18, R.string.font_regular, SyntaxUi.TEXT);
        current.setPadding(0, SyntaxUi.dp(this, 8), 0, SyntaxUi.dp(this, 8));
        column.addView(current);

        LinearLayout row = SyntaxUi.chipRow(this);
        TextView auto = SyntaxUi.chip(this, getString(R.string.place_auto));
        auto.setOnClickListener(v -> {
            Place.setManual(this, null);
            status.setText(R.string.place_searching_auto);
            worker.execute(() -> {
                Place.refreshAuto(this);
                handler.post(this::refresh);
            });
        });
        SyntaxUi.addChip(row, auto);
        column.addView(row);

        column.addView(SyntaxUi.label(this, getString(R.string.place_city)));
        LinearLayout search = SyntaxUi.chipRow(this);
        query = new EditText(this);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setHint(R.string.place_hint);
        query.setTextColor(SyntaxUi.TEXT);
        query.setHintTextColor(SyntaxUi.MUTED);
        query.setTextSize(18);
        query.setMinWidth(SyntaxUi.dp(this, 360));
        query.setOnEditorActionListener((v, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || enter) {
                search();
                return true;
            }
            return false;
        });
        search.addView(query);
        TextView go = SyntaxUi.chip(this, getString(R.string.place_search));
        go.setOnClickListener(v -> search());
        SyntaxUi.addChip(search, go);
        column.addView(search);

        status = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        status.setPadding(0, SyntaxUi.dp(this, 8), 0, SyntaxUi.dp(this, 4));
        column.addView(status);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        column.addView(results);

        setContentView(outer);
        refresh();
        auto.requestFocus();
    }

    private void search() {
        String q = query.getText().toString();
        if (q.trim().length() < 2) return;
        status.setText(R.string.place_searching);
        results.removeAllViews();
        worker.execute(() -> {
            List<Place.Spot> found = Place.search(q);
            handler.post(() -> {
                if (isFinishing()) return;
                status.setText(found.isEmpty() ? getString(R.string.place_none) : "");
                LinearLayout row = null;
                for (int i = 0; i < found.size(); i++) {
                    if (i % 2 == 0) {
                        row = SyntaxUi.chipRow(this);
                        row.setPadding(0, 0, 0, SyntaxUi.dp(this, 8));
                        results.addView(row);
                    }
                    Place.Spot s = found.get(i);
                    TextView chip = SyntaxUi.chip(this, s.name);
                    chip.setOnClickListener(v -> {
                        Place.setManual(this, s);
                        results.removeAllViews();
                        status.setText("");
                        refresh();
                    });
                    SyntaxUi.addChip(row, chip);
                }
                if (!found.isEmpty() && results.getChildCount() > 0) results.getChildAt(0).requestFocus();
            });
        });
    }

    private void refresh() {
        Place.Spot s = Place.spot(this);
        if (s == null) current.setText(R.string.place_unknown);
        else if (Place.manual(this)) current.setText(getString(R.string.place_chosen, s.name));
        else if (DeviceConfig.place(this) != null) current.setText(getString(R.string.place_from_device, s.name));
        else current.setText(getString(R.string.place_from_connection, s.name == null || s.name.isEmpty()
                ? Place.describe(s) : s.name));
        if (status.getText().toString().equals(getString(R.string.place_searching_auto))) status.setText("");
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
