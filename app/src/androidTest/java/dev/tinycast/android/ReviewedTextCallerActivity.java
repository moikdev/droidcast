package dev.tinycast.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Standalone test-package Activity: framework Java only, a separate package/UID.
 * Instrumentation APKs omit libraries shared with the target; this Activity
 * must also work outside the target process, without its Kotlin/Compose code.
 */
public class ReviewedTextCallerActivity extends Activity {
    private EditText source;
    private TextView selection;

    @Override public void onCreate(Bundle state) { super.onCreate(state); showCaller(); }
    @Override public void onNewIntent(Intent value) { super.onNewIntent(value); setIntent(value); showCaller(); }
    @Override public void onResume() {
        super.onResume();
        selection.setText("Selection " + source.getSelectionStart() + " to " + source.getSelectionEnd());
    }

    private void showCaller() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(24, 28, 24, 28);
        column.setBackgroundColor(Color.rgb(240, 244, 249));
        TextView heading = new TextView(this);
        heading.setText("Owned Android caller"); heading.setTextSize(22); heading.setTextColor(Color.BLACK);
        column.addView(heading);
        TextView explanation = new TextView(this);
        explanation.setText("Select or share the owned text. Returning must leave this source unchanged.");
        explanation.setTextSize(15); column.addView(explanation);
        String text = getIntent().getStringExtra("source");
        if (text == null) text = "Owned caller source";
        source = new EditText(this);
        source.setId(android.R.id.edit); source.setTextSize(16); source.setMinLines(4); source.setText(text);
        int start = Math.max(0, Math.min(text.length(), getIntent().getIntExtra("selectionStart", 0)));
        int end = Math.max(0, Math.min(text.length(), getIntent().getIntExtra("selectionEnd", text.length())));
        source.setSelection(start, end);
        column.addView(source, new LinearLayout.LayoutParams(-1, -2));
        selection = new TextView(this); selection.setId(android.R.id.text2); selection.setTextSize(14);
        column.addView(selection);
        button(column, "Process selected text", android.R.id.button1, () -> send(Intent.ACTION_PROCESS_TEXT));
        button(column, "Share owned text", android.R.id.button2, () -> send(Intent.ACTION_SEND));
        button(column, "Edit owned source", android.R.id.button3, () -> {
            source.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(source, InputMethodManager.SHOW_IMPLICIT);
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        if (android.os.Build.VERSION.SDK_INT >= 30) scroll.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        setContentView(scroll);
    }

    private void button(LinearLayout column, String label, int id, Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setAllCaps(false); button.setId(id);
        button.setOnClickListener(view -> action.run());
        column.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private void send(String action) {
        int start = Math.max(0, Math.min(source.getSelectionStart(), source.getSelectionEnd()));
        int end = Math.max(0, Math.max(source.getSelectionStart(), source.getSelectionEnd()));
        String selected = source.getText().subSequence(start, end).toString();
        String mime = getIntent().getStringExtra("mime");
        Intent value = new Intent(action).setComponent(new ComponentName("dev.tinycast.android", "dev.tinycast.android.MainActivity"))
            .setType(mime == null ? "text/plain" : mime);
        String key = action.equals(Intent.ACTION_PROCESS_TEXT) ? Intent.EXTRA_PROCESS_TEXT : Intent.EXTRA_TEXT;
        String kind = getIntent().getStringExtra("payloadKind");
        if ("integer".equals(kind)) value.putExtra(key, 42);
        else if ("oversize".equals(kind)) value.putExtra(key, new String(new char[65537]).replace('\0', 'x'));
        else if (!"missing".equals(kind)) value.putExtra(key, (CharSequence) selected);
        if ("badflag".equals(kind)) value.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, "invalid flag");
        else value.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, getIntent().getBooleanExtra("readOnly", true));
        startActivity(value);
    }
}
