package dev.tinycast.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Self-targeted test instrumentation loads this class without the production APK or Kotlin runtime. */
public class KeyboardObservationActivity extends Activity {
    private final JSONArray beforeIme = new JSONArray();
    private final JSONArray activityDispatch = new JSONArray();
    private final JSONArray edits = new JSONArray();
    private final Set<Integer> keys = new HashSet<>();
    private EditText editor;
    private String expected;
    private boolean overflow;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String fixture = getIntent().getStringExtra("fixture");
        if ("short".equals(fixture)) expected = "qwerty";
        else if ("long".equals(fixture)) expected = "menuKeyboardProbe271828";
        else { finish(); return; }

        for (char ch : expected.toUpperCase(Locale.ROOT).toCharArray()) {
            keys.add(KeyEvent.keyCodeFromString("KEYCODE_" + ch));
        }
        keys.add(KeyEvent.KEYCODE_SHIFT_LEFT);
        keys.add(KeyEvent.KEYCODE_SHIFT_RIGHT);
        editor = new EditText(this) {
            @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                record(beforeIme, event);
                return super.onKeyPreIme(keyCode, event);
            }
        };
        editor.setSingleLine();
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        editor.setShowSoftInputOnFocus(false);
        editor.setFocusableInTouchMode(true);
        editor.setContentDescription("Synthetic input");
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (edits.length() >= 256) { overflow = true; return; }
                try {
                    edits.put(new JSONObject().put("ms", SystemClock.uptimeMillis()).put("length", s.length()));
                } catch (JSONException e) { throw new IllegalStateException(e); }
            }
        });
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(32 + insets.getSystemWindowInsetLeft(), 32 + insets.getSystemWindowInsetTop(),
                    32 + insets.getSystemWindowInsetRight(), 32 + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView label = new TextView(this);
        label.setText("Keyboard delivery test");
        layout.addView(label);
        layout.addView(editor);
        setContentView(layout);
        editor.requestFocus();
    }

    private void record(JSONArray events, KeyEvent e) {
        if (editor != null && hasWindowFocus() && editor.hasFocus() &&
                (e.getAction() == KeyEvent.ACTION_DOWN || e.getAction() == KeyEvent.ACTION_UP)) {
            if (events.length() >= 256) overflow = true;
            else {
                try {
                    String key = keys.contains(e.getKeyCode()) ? KeyEvent.keyCodeToString(e.getKeyCode()) : "other";
                    events.put(new JSONObject().put("key", key).put("action", e.getAction()).put("repeat", e.getRepeatCount())
                            .put("down_ms", e.getDownTime()).put("event_ms", e.getEventTime()).put("received_ms", SystemClock.uptimeMillis()));
                } catch (JSONException error) { throw new IllegalStateException(error); }
            }
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        record(activityDispatch, e);
        return super.dispatchKeyEvent(e);
    }

    JSONObject sample() {
        String actual = editor.getText().toString();
        try {
            return new JSONObject().put("fixture", expected).put("exact", expected.equals(actual))
                    .put("observed", expected.startsWith(actual) || "qwerrty".equals(actual) ? actual : "outside-fixture")
                    .put("length", actual.length()).put("focused", hasWindowFocus() && editor.hasFocus())
                    .put("ms", SystemClock.uptimeMillis()).put("overflow", overflow)
                    .put("pre_ime", new JSONArray(beforeIme.toString()))
                    .put("activity_dispatch", new JSONArray(activityDispatch.toString()))
                    .put("edits", new JSONArray(edits.toString()));
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    void removeLastCharacterForControl() {
        if (!expected.contentEquals(editor.getText())) throw new IllegalStateException("Editor control requires the complete fixture");
        editor.getText().delete(expected.length() - 1, expected.length());
    }
}
