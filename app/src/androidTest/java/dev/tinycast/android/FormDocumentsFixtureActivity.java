package dev.tinycast.android;

import android.app.Activity;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Refresh the system root cache after the host established its private lease.
 * This standalone helper uses framework classes only and never the clipboard.
 */
public final class FormDocumentsFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String owner = getIntent().getStringExtra("owner_nonce");
            if (owner == null) throw new IllegalArgumentException("Missing fixture owner");
            OwnedFormDocumentsProvider.fixture(this, owner);
            getContentResolver().notifyChange(DocumentsContract.buildRootsUri(getPackageName() + ".formdocuments"), null);
        } catch (Exception ignored) { /* No matching lease means no root refresh. */ }
        finish();
    }
}
