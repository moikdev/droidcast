package dev.tinycast.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import org.xmlpull.v1.XmlSerializer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

/** Attaches the requested observer only after the host signals completion of the pre-observer capture. */
public class KeyboardObservationInstrumentation extends Instrumentation {
    private Bundle options;
    private KeyboardObservationActivity receiver;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        options = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            runTrial();
            code = Activity.RESULT_OK;
        } catch (Exception e) {
            result.putString("error", e.toString());
        } finally {
            if (receiver != null) runOnMainSync(receiver::finish);
            finish(code, result);
        }
    }

    private void runTrial() throws Exception {
        String session = options.getString("session", "");
        require(session.matches("[0-9a-f]{32}"), "Invalid trial ID");
        File folder = new File(getTargetContext().getCacheDir(), "keyboard-observation-" + session);
        String[] existing = folder.list();
        require(existing != null && existing.length == 0, "Expected an owned empty trial directory");
        String fixture = options.getString("fixture");
        require("short".equals(fixture) || "long".equals(fixture), "Unknown text fixture");
        String observer = options.getString("observer");
        require("suppress".equals(observer) || "preserve".equals(observer), "Unknown observer");
        int flags = "preserve".equals(observer) ? UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES : 0;

        Intent intent = new Intent(getTargetContext(), KeyboardObservationActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture", fixture);
        receiver = (KeyboardObservationActivity) startActivitySync(intent);
        await(() -> sample().optBoolean("focused"));
        require(sample().getInt("length") == 0, "Editor must start empty");
        require(new File(folder, "ready").createNewFile(), "Duplicate ready marker");
        await(() -> new File(folder, "attach").isFile());
        JSONObject report = new JSONObject().put("before_observer", sample());
        if ("editor-loss".equals(options.getString("control"))) runOnMainSync(receiver::removeLastCharacterForControl);

        AccessibilityNodeInfo root = getUiAutomation(flags).getRootInActiveWindow();
        require(root != null && sample().getBoolean("focused"), "Missing focused fixture window");
        try (OutputStream out = Files.newOutputStream(new File(folder, "hierarchy.xml").toPath())) {
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(out, "UTF-8");
            xml.startDocument("UTF-8", true);
            writeNode(xml, root, 0, new int[]{0});
            xml.endDocument();
        }
        report.put("after_observer", sample());
        require(new File(folder, "attached").createNewFile(), "Duplicate attached marker");
        await(() -> new File(folder, "finish").isFile());
        report.put("final", sample()).put("observer_flags", flags).put("session", session);
        Files.write(new File(folder, "record.json").toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeNode(XmlSerializer xml, AccessibilityNodeInfo node, int depth, int[] count) throws IOException {
        require(++count[0] <= 64 && depth <= 20 && getTargetContext().getPackageName().equals(node.getPackageName()),
                "Unexpected window or fixture tree");
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String text = node.getText() == null ? "" : node.getText().toString();
        boolean known = "qwerty".startsWith(text) || "menuKeyboardProbe271828".startsWith(text)
                || "qwerrty".equals(text) || "Keyboard delivery test".equals(text);
        xml.startTag(null, "node");
        xml.attribute(null, "class", String.valueOf(node.getClassName()));
        xml.attribute(null, "bounds", bounds.toShortString());
        xml.attribute(null, "editable", String.valueOf(node.isEditable()));
        xml.attribute(null, "text", known ? text : "outside-fixture");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) writeNode(xml, child, depth + 1, count);
        }
        xml.endTag(null, "node");
    }

    private JSONObject sample() {
        JSONObject[] data = new JSONObject[1];
        runOnMainSync(() -> data[0] = receiver.sample());
        return data[0];
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 60_000;
        while (!condition.getAsBoolean()) {
            require(SystemClock.uptimeMillis() < deadline, "Fixture protocol timed out");
            SystemClock.sleep(40);
        }
    }
}
