package dev.tinycast.android;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Framework-only Java: the standalone test UID cannot load the target APK's
 * Kotlin libraries. A nonce-owned host lease gates this read-only provider.
 */
public final class OwnedFormDocumentsProvider extends DocumentsProvider {
    static File fixture(Context context, String expected) throws Exception {
        File root = new File(context.getFilesDir().getCanonicalFile(), "form-document-fixture");
        if (!root.getAbsoluteFile().equals(root.getCanonicalFile()) || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new FileNotFoundException("Owned document fixture is unavailable");
        File marker = new File(root, "owner.json");
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) || marker.length() < 1 || marker.length() > 4096)
            throw new FileNotFoundException("Owned document fixture owner is unavailable");
        JSONObject owner = new JSONObject(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
        Set<String> keys = new HashSet<>();
        Iterator<String> iterator = owner.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        String nonce = owner.getString("owner_nonce");
        if (!keys.equals(new HashSet<>(Arrays.asList("schema_version", "fixture_kind", "owner_nonce"))) ||
            !(owner.get("schema_version") instanceof Integer) || owner.getInt("schema_version") != 1 ||
            !"form-document-provider-v1".equals(owner.getString("fixture_kind")) || !nonce.matches("[0-9a-f]{32}") ||
            (expected != null && !nonce.equals(expected))) throw new FileNotFoundException("Invalid document fixture owner");
        return root;
    }
    private static final class Entry {
        final String name, path;
        final boolean directory;
        Entry(String name, String path, boolean directory) { this.name = name; this.path = path; this.directory = directory; }
    }
    private final Map<String, Entry> entries = new HashMap<>();
    public OwnedFormDocumentsProvider() {
        entries.put("root", new Entry("Droidcast owned files", "data", true));
        entries.put("text", new Entry("café 日本.txt", "data/text.bin", false));
        entries.put("binary", new Entry("owned-bytes.bin", "data/binary.bin", false));
        entries.put("oversize", new Entry("Owned too large.bin", "data/oversize.bin", false));
        entries.put("folder", new Entry("Owned folder", "data/folder", true));
        entries.put("nested", new Entry("nested.txt", "data/folder/nested.bin", false));
    }
    private static final String[] DOCUMENT_COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED };
    @Override public boolean onCreate() { return true; }
    private File root() throws FileNotFoundException {
        try { return fixture(getContext(), null); }
        catch (Exception ignored) { throw new FileNotFoundException("Owned document fixture is unavailable"); }
    }
    private Entry entry(String id) throws FileNotFoundException {
        root();
        Entry entry = entries.get(id);
        if (entry == null) throw new FileNotFoundException("Unknown owned document");
        return entry;
    }
    private File file(String id) throws FileNotFoundException {
        Entry entry = entry(id);
        File file = new File(root(), entry.path);
        try {
            if (!file.getAbsoluteFile().equals(file.getCanonicalFile()) || !(entry.directory ?
                Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) : Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)))
                throw new FileNotFoundException("Owned document is unavailable");
            return file;
        } catch (java.io.IOException ignored) { throw new FileNotFoundException("Owned document is unavailable"); }
    }
    private static void add(MatrixCursor cursor, Map<String, Object> values) {
        String[] columns = cursor.getColumnNames(); Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = values.get(columns[i]);
        cursor.addRow(row);
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES};
        MatrixCursor result = new MatrixCursor(columns);
        try { root(); } catch (Exception ignored) { return result; }
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "owned-form-root"); values.put(Root.COLUMN_DOCUMENT_ID, "root");
        values.put(Root.COLUMN_TITLE, "Droidcast owned files"); values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD);
        values.put(Root.COLUMN_MIME_TYPES, "*/*"); values.put(Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda);
        values.put(Root.COLUMN_AVAILABLE_BYTES, 64L * 1024 * 1024); add(result, values); return result;
    }
    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        Entry entry = entry(id); File file = file(id);
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id); values.put(Document.COLUMN_DISPLAY_NAME, entry.name);
        values.put(Document.COLUMN_MIME_TYPE, entry.directory ? Document.MIME_TYPE_DIR : "application/octet-stream");
        values.put(Document.COLUMN_FLAGS, 0); values.put(Document.COLUMN_LAST_MODIFIED, 1_700_000_000_000L);
        if (!entry.directory) values.put(Document.COLUMN_SIZE, "oversize".equals(id) ? 17L * 1024 * 1024 : file.length());
        add(cursor, values);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addDocument(result, id); return result;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) throws FileNotFoundException {
        file(id);
        String[] children;
        if ("root".equals(id)) children = new String[]{"folder", "text", "binary", "oversize"};
        else if ("folder".equals(id)) children = new String[]{"nested"};
        else throw new FileNotFoundException("Not a fixture directory");
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        for (String child : children) addDocument(result, child);
        return result;
    }
    @Override public boolean isChildDocument(String parent, String id) throws FileNotFoundException {
        entry(parent); entry(id);
        return ("root".equals(parent) && !"root".equals(id)) || ("folder".equals(parent) && "nested".equals(id));
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        if (!"r".equals(mode) || entry(id).directory) throw new FileNotFoundException("Fixture documents are read-only");
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
