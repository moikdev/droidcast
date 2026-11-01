# Updater validation in progress

Issue #55. No PR/release clearance yet. [Design and trust contract](../UPDATES.md).

- WIP build compiles; 138 JVM cases pass, including six updater policy tests. Final frozen-binary certification remains pending.
- Initial real Settings/palette runs passed 2/2 per layout after correcting an exact-label assertion. Live public GitHub discovery works; pre-updater releases supply no update manifest.
- The first signed-fixture pipeline built both versions and a distinct test signer, then failed during an ADB stdin transfer before native cases ran. All recorded cleanup steps passed; no app failure is claimed. Subsequent transfer uses bounded ADB push/copy with hashes.
- The first 16-case tablet verification run passed 12/16. Four cases failed at native screenshot marker waits after preceding state/security assertions. The failing helper retained no failure hierarchy/image; the cause is not established. Those results are excluded from final certification. Explicit visible Compose markers, fresh native cache queries and failure PNG/XML capture strengthen the helper.
- Production UI now remains busy until operation cleanup completes. This closes a source-established interval where a completed-looking phase could offer controls while its previous job was still active; no measured latency fix is claimed.
- Verification fixtures use a real signed newer APK, valid APK signed by a distinct test certificate, and a wrong-package APK. The controlled transport is explicitly distinct from the live GitHub/HTTPS cases. Test-only APKs and the private fixture key are never published as eligible nightlies.
- The native fixture runner temporarily sets per-source install approval on the owned emulator, then restores the prior mode. It records unprivileged app/adbd state and cleanup. Real Android approval and successful self-update/data-preservation validation remain pending.
- API36 arm64 tablet/phone viewports are configurations of one owned emulator. Physical Lenovo/OEM, API26 runtime, comprehensive accessibility, process-death and performance certification remain unclaimed.

The tightened 16-case verification cohort passes on tablet and phone at source checkpoint 715e866, with one bootstrap/test pair and signed fixture payloads from that checkpoint. Cleanup records restore source-approval mode and unrooted state. It covers live public GitHub/HTTPS reads and explicitly controlled transport for archive/error cases; real self-update remains pending. The subsequent fixed-footer, status-message and verified-cache refinements add a same-turn Enter/visibility assertion and another cache-recheck case, so those earlier 16-case results are historical, not final-binary certification. Real-install helpers retain opaque private app-data snapshots solely for restoration; no snapshot contents or private fixture key may be published or reviewed.

## Real-install validation checkpoints

The tightened 17-case cohort passes on both layouts at the unchanged production checkpoint df9c5b5. It adds verified-cache rechecking and fixed-footer activation. Its instrumentation APK predates subsequent real-install-fixture navigation changes; the final unified-pair 17/17 reruns now pass on both layouts.

The real-install harness uses an actual same-signer versionCode 6 → 7 APK replacement through the product PackageInstaller path. Delivery is explicitly staged by the harness; product recovery rechecks package/version/hash/signer before presenting installation. The payload is named 0.5.0-update-fixture and must never be published as an eligible nightly. Live network coverage separately exercises public GitHub discovery and a real HTTPS legacy-r9 download.

- Tablet confirmation-required: passed Android source approval, actual installer Cancel, retained verified retry, installation, same UID and payload-byte identity, then semantic collection/pin/alias/shortcut checks, emoji/extension preference-byte equality, palette-preference equality excluding legitimate command-usage changes, persistent grant plus document read, and running the retained extension/note. Cleanup verified the original bootstrap, opaque private data, approval mode and device configuration.
- Tablet best-effort: the same update/retention checks passed without a second platform confirmation on this emulator. This is not an OEM or universal silent-update claim.
- Phone confirmation-required: the same cancellation/retry/update/retention and restoration checks passed. The phone best-effort case also passed without a second platform confirmation.

Excluded real-install setup attempts are preserved. The first expected a Downloads drawer that was absent; the next capture established a primary-storage tree view with a visible Download folder and no drawer. Another expected an exposed launcher test resource ID, while a native diagnostic established a focused EditText without that ID. A subsequent picker capture established an already-selected fixture breadcrumb; navigation now handles that retained destination. These are fixture/navigation corrections, not production updater fixes. Every excluded attempt's independent restoration checks passed.

The original 16/17 verification attempt failed because setup waited for IDLE without awaiting the coordinator's still-active cleanup. The corrected fixture waits for both IDLE and nonbusy. The production busy contract was already present. No unmeasured timing or latency fix is claimed.
