# Selected-folder search

Available in development builds after the original 0.4.0-preview tag. Search **Search folders** or open **Settings → Folders → Add folder**. Choose a folder in Android’s system picker and approve read access. The first scan enables folder search and reads document names, MIME types, modification times and relative paths. It does not read file contents.

Open **Search files** or the **Files** filter to search the bounded index alongside individually selected recent documents. Use the type selector for documents, images, audio, video, archives or folders. Narrow type filters show indexed entries with known MIME metadata; older recent-document entries appear under All types. A directory result opens Android’s document picker at that directory. A file result launches a compatible external viewer with a temporary read grant. Pin, alias and shortcut actions use stable provider/document identities.

**Include in main search** is off initially. Turn it on to include indexed matches outside Files. Explicit file favorites and aliases remain available while folder search is enabled. **Refresh** checks for changes; the app does not monitor every filesystem event. Cancel scan retains the previous results. A refresh can continue while switching settings pages or temporarily backgrounding the workspace; closing the workspace cancels its active work.

Limits are 8 selected folders, 5,000 indexed entries, 16 directory levels, 512 directory queries, an 8 MiB cache and 60 results per query. Provider queries have bounded worker/queue and time limits; incomplete listings display a status or per-folder error. Storage roots, Download root and Android/data or Android/obb can be restricted by Android; choose an allowed subfolder. See the [Storage Access Framework documentation](https://developer.android.com/training/data-storage/shared/documents-files).

Turning search off deletes the private metadata cache and retains selected scopes for reenabling. Removing a scope deletes its indexed metadata and releases a read grant acquired by this feature; preexisting grants are kept. Individually selected recent documents and their grants remain separate. Expired scope grants hide indexed entries and offer reauthorization. Stale/deleted file results can fail to open until Refresh updates the index.

Index/configuration files remain in private app storage and are excluded from portable backups, as are indexed-document pins, aliases and key bindings. Restore does not grant folder access. Search is partial parity: cloud/offline providers, providers that ignore cancellation, very large real trees and process-death edge cases require broader validation in [#5](https://github.com/moikdev/droidcast/issues/5). No whole-device storage permission is requested.

## Index retention when permissions change

Picking one file gives Android a separate document permission. Folder search now compares read permissions for its configured trees when Settings or the palette resumes. Taking or releasing an individual document permission leaves the folder index intact. When access to a configured tree expires, the app hides its rows and removes their metadata from the private cache. Other readable folders keep their indexed entries. Pruning reads the current file under the shared storage lock, so a controller with an older in-memory view preserves rows saved by a peer.

Three comparisons on the dedicated API 36 emulator exercised these changes with real persisted permissions from DocumentsUI. The earlier app was built at `b6ff0495`; its production sources match main at `f4c8bfd0`. The integration app was built at `34f50efd`. The final build at `af929a09` changes the test capture helper and keeps the integrated production source.

| Change | Earlier app | Repaired behavior under test |
| --- | --- | --- |
| Pick an individual document, then reopen Settings | A fresh Settings store loaded zero of four indexed entries | All four entries survive, and the cache remains byte-identical after taking and releasing the document permission |
| Revoke one configured tree and revalidate before reopening | The existing store kept the surviving file in memory, but a fresh store lost it | The surviving Research file remains available after reopening |
| Revoke a tree and load a fresh store before explicit revalidation | The UI hid revoked entries, but three of their rows remained on disk | The private cache contains only the readable tree’s row |

The fixture specifies an initial folder for Android’s picker. The tests approve the real tree or pick the actual fixture document, then finish and relaunch Settings and assert that the new Activity owns a distinct store. Reopening is in the same process. The captures show indexed counts and the expired-access message. Assertions on cache bytes and stored rows provide the persistence evidence. The two layouts use one emulator image. Process termination, cloud providers and storage-write failure remain outside this comparison.

Both cases in the initial comparison batch stopped during picker setup. A later six-case matrix exercised activity recreation and included a passing earlier-app revocation case on a retained ViewModel. With fresh stores the earlier revocation check failed at the lost-row assertion, but its companion stopped at a picker precondition. These attempts retain their individual outcomes. The final comparison explicitly selects the fixture location and exercises all three cache assertions. [Android cases, APK identities and cleanup](validation/folder-grants/runs.json) · [Capture and log bindings](validation/folder-grants/bindings.json) · [Host checks](validation/folder-grants/host.json).

On the integrated production build, each layout passed all three cache cases. An additional tablet run passed the malformed-cache case and stopped in the cancellation and revocation workflow. Its screenshot shows the expired-access warning while the paired native hierarchy still reports the preceding disabled state. The final instrumentation refreshes the accessibility cache and waits for the app’s expected text before taking the XML and screenshot. [Earlier screenshot](screenshots/folder-grants/verified-affected-expired.png) · [Earlier hierarchy](validation/folder-grants/hierarchies/verified-affected-expired.xml).

With the final instrumentation the earlier app failed all three assertions, and the repaired app passed all three on tablet and phone. The two additional tablet workflows passed, including revocation, reauthorization, removal, cancellation and rebuilding a malformed index. All 54 inner cleanup checks passed across these four batches. Each batch also verified its installed app and instrumentation bytes and restored the earlier packages and evidence directory. Final debug and benchmark builds and lint passed with 53 warnings per variant. All 356 debug JVM cases passed. The 148 Python tests per mode ran at `34f50efd`; the Python sources are identical at the final build revision.

## Removing a recent document

In **Files**, select a recent document, open **Actions**, choose **Delete from Droidcast**, and confirm **Remove**. The provider keeps the original file. When another recent uses the same document, that entry retains access. After the last entry has been saved as removed, Droidcast releases the persistent read permission it acquired for that document.

The ownership record stays in the app's private preferences and is excluded from portable backups. If a read grant already exists, picking the document again leaves ownership with its original consumer. Legacy records alone never authorize release. Droidcast requests only the read flag when acquiring or releasing permission. A separate write grant remains available to its owner. Folder authorization still uses the configured tree URI.

If Droidcast cannot verify that removal was saved, it retains the grant. A failed release leaves the ownership record available for a retry when a new palette or Settings store opens. The user can reselect a document whose provider no longer grants persistent access.

### Android verification for #141

The final comparison used one instrumentation APK on the dedicated Tinycast Tablet API 36 emulator. The earlier app came from build `af929a09`. The repaired debug app and tests were built from `3ab115df`; the local app manifest uses the default `local` stamp. APK hashes and source inventories identify the tested artifacts. Tablet captures are 1920 × 1200 at 240 dpi. The phone layout on the same emulator used 1080 × 2340 at 420 dpi.

| After removing the last recent entry | Earlier tablet | Repaired tablet | Repaired phone |
| --- | --- | --- | --- |
| Recent row present | No | No | No |
| Persisted read access present | Yes | No | No |
| Original document contents match | Yes | Yes | Yes |

The test created a uniquely named Downloads document, selected it in Android DocumentsUI and confirmed removal in the app. Native picker activation used accessibility actions. Search setup used the model, and confirmation used a Compose click. The screenshots show the dialog and return destination. The test read the permission flags from Android and reopened the original fixture through its MediaStore URI to compare the text. [Earlier observation](validation/recent-grants/earlier-result.json) · [Repaired tablet](validation/recent-grants/tablet-result.json) · [Repaired phone](validation/recent-grants/phone-result.json)

All four recent-document cases passed on each layout. They cover owned access, a borrowed read on a legacy reference, two records sharing a URI with an independently held write grant, and retry after injected persistence and release failures. The failure test stages removal of the selected row, forces a failed persistence barrier and a failed release separately, and opens a new Settings store in the same process to exercise recovery. The shared-reference fixture creates the second record through the store. The earlier app failed both matched owned-read assertions. Four additional tablet cases passed for slow provider names, timeout, MIME retry and cancellation.

The first development pair recorded one earlier failure and one repaired pass. Its app/test source snapshot was still uncommitted. Intermediate build `366bbc15` recorded four repaired passes and two comparison failures. Build `a326a116` recorded eight repaired passes and two comparison failures. Earlier captures taken immediately after the model changed could still show the confirmation dialog. The final fixture waits for the visible search destination before capturing. Only the final screenshot sets illustrate removal here.

The final four batches recorded 54 successful inner cleanup checks. All four outer wrappers verified the original installed packages and restoration of the evidence directory. Host checks recorded 356 passing debug JVM tests and 148 Python tests in each interpreter mode on unchanged scripts. Debug and benchmark app and instrumentation APKs built. Lint found zero errors and 58 warnings per variant, including five Kotlin extension suggestions in the grant coordinator. Physical storage failure, process death between writes, OEM providers and cross-process recovery remain untested. [Run records](validation/recent-grants/runs.json) · [Build checks](validation/recent-grants/host.json) · [APK identities](validation/recent-grants/apks.json)
