package dev.tinycast.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** Framework-only recovery in the separate test APK UID, never instrumentation.
 * The private snapshot survives target/instrumentation death. No clipboard data
 * is rendered, logged, backed up by Android, or included in public receipts.
 */
public final class ClipboardRecoveryActivity extends Activity {
    private static final int LIMIT = 1024 * 1024;
    private boolean handled;
    private TextView status;
    private File root() { return new File(getNoBackupFilesDir(), "reviewed-text-clipboard-recovery"); }

    @Override public void onCreate(Bundle state) { super.onCreate(state); showRequest(); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); showRequest(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) perform();
    }
    private void showRequest() {
        handled = false;
        status = new TextView(this);
        status.setText("Owned clipboard recovery"); status.setTextSize(20); status.setPadding(24, 48, 24, 24);
        setContentView(status);
        status.post(() -> { if (hasWindowFocus()) perform(); });
    }
    private static void require(boolean value) { if (!value) throw new IllegalStateException("Invalid clipboard recovery state"); }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private static byte[] read(File file) throws Exception {
        require(file.isFile() && file.length() > 0 && file.length() <= LIMIT);
        return Files.readAllBytes(file.toPath());
    }
    private static void write(File file, byte[] bytes) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = atomic.startWrite();
        try { stream.write(bytes); atomic.finishWrite(stream); }
        catch (Exception failure) { atomic.failWrite(stream); throw failure; }
    }
    private JSONObject owner(String nonce) throws Exception {
        JSONObject value = new JSONObject(new String(read(new File(root(), "owner.json")), java.nio.charset.StandardCharsets.UTF_8));
        require(value.getInt("schema_version") == 1 && nonce.equals(value.getString("owner_nonce")));
        return value;
    }
    private void receipt(String nonce, String request, String phase, String failure) throws Exception {
        JSONObject value = owner(nonce);
        value.put("request_id", request).put("phase", phase);
        if (failure != null) value.put("error_type", failure);
        write(new File(root(), "receipt.json"), value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void supported(ClipData clip) {
        if (clip == null) return;
        require(clip.getItemCount() > 0 && clip.getItemCount() <= 100);
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            // URI/Intent grants can expire when the clipboard is replaced.
            // Refuse before mutation instead of promising unrecoverable grants.
            require(item.getUri() == null && item.getIntent() == null && item.getText() != null);
        }
    }
    private static byte[] content(ClipData clip) {
        Parcel out = Parcel.obtain();
        try {
            out.writeInt(clip == null ? 0 : 1);
            if (clip != null) {
                supported(clip);
                ClipDescription description = clip.getDescription();
                TextUtils.writeToParcel(description.getLabel(), out, 0);
                out.writeInt(description.getMimeTypeCount());
                for (int i = 0; i < description.getMimeTypeCount(); i++) out.writeString(description.getMimeType(i));
                out.writePersistableBundle(description.getExtras());
                out.writeInt(clip.getItemCount());
                for (int i = 0; i < clip.getItemCount(); i++) {
                    TextUtils.writeToParcel(clip.getItemAt(i).getText(), out, 0);
                    out.writeString(clip.getItemAt(i).getHtmlText());
                }
            }
            // Clipboard timestamps/classification are system-generated metadata.
            return out.marshall();
        } finally { out.recycle(); }
    }
    private static byte[] encode(String nonce, ClipData clip) {
        Parcel out = Parcel.obtain();
        try {
            out.writeInt(1); out.writeString(nonce); out.writeInt(clip == null ? 0 : 1);
            if (clip != null) clip.writeToParcel(out, 0);
            return out.marshall();
        } finally { out.recycle(); }
    }
    private static ClipData decode(String nonce, byte[] bytes) {
        Parcel in = Parcel.obtain();
        try {
            in.unmarshall(bytes, 0, bytes.length); in.setDataPosition(0);
            require(in.readInt() == 1 && nonce.equals(in.readString()));
            int present = in.readInt(); require(present == 0 || present == 1);
            ClipData result = present == 0 ? null : ClipData.CREATOR.createFromParcel(in);
            require(in.dataAvail() == 0); supported(result); return result;
        } finally { in.recycle(); }
    }
    private void perform() {
        if (handled || !hasWindowFocus()) return;
        handled = true;
        String nonce = getIntent().getStringExtra("owner_nonce"), request = getIntent().getStringExtra("request_id");
        String action = getIntent().getStringExtra("recovery_action");
        try {
            require((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
            require(nonce != null && nonce.matches("[0-9a-f]{32}") && request != null && request.matches("[0-9a-f]{32}"));
            require("snapshot".equals(action) || "restore".equals(action));
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if ("snapshot".equals(action)) {
                require(root().mkdir()); // An existing recovery owner is never overwritten.
                JSONObject metadata = new JSONObject().put("schema_version", 1).put("owner_nonce", nonce);
                write(new File(root(), "owner.json"), metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                ClipData clip = clipboard.getPrimaryClip();
                require(clipboard.hasPrimaryClip() == (clip != null)); supported(clip);
                byte[] bytes = encode(nonce, clip); require(bytes.length <= LIMIT);
                require(Arrays.equals(content(clip), content(decode(nonce, bytes))));
                write(new File(root(), "snapshot.bin"), bytes);
                require(Arrays.equals(bytes, read(new File(root(), "snapshot.bin"))));
                require(Arrays.equals(content(clip), content(clipboard.getPrimaryClip())));
                metadata.put("snapshot_sha256", hash(bytes)).put("content_sha256", hash(content(clip)));
                write(new File(root(), "owner.json"), metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                receipt(nonce, request, "snapshot-ready", null);
                status.setText("Owned clipboard snapshot saved");
            } else {
                JSONObject metadata = owner(nonce);
                byte[] bytes = read(new File(root(), "snapshot.bin"));
                require(metadata.getString("snapshot_sha256").equals(hash(bytes)));
                ClipData clip = decode(nonce, bytes);
                require(metadata.getString("content_sha256").equals(hash(content(clip))));
                if (clip == null) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(clip);
                require(Arrays.equals(content(clip), content(clipboard.getPrimaryClip())));
                require(clipboard.hasPrimaryClip() == (clip != null));
                receipt(nonce, request, "restored", null);
                status.setText("Owned clipboard restored and verified");
            }
        } catch (Exception failure) {
            // No exception message/stack: Parcel or clipboard errors may contain data.
            try { receipt(nonce, request, "failed", failure.getClass().getSimpleName()); } catch (Exception ignored) { }
            status.setText("Owned clipboard recovery failed; retain its recovery lease");
        }
    }
}
