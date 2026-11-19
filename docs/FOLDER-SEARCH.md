# Selected-folder search

Available in development builds after the original 0.4.0-preview tag. Search **Search folders** or open **Settings → Folders → Add folder**. Choose a folder in Android’s system picker and approve read access. The first scan enables folder search and reads document names, MIME types, modification times and relative paths. It does not read file contents.

Open **Search files** or the **Files** filter to search the bounded index alongside individually selected recent documents. Use the type selector for documents, images, audio, video, archives or folders. Narrow type filters show indexed entries with known MIME metadata; older recent-document entries appear under All types. A directory result opens Android’s document picker at that directory. A file result launches a compatible external viewer with a temporary read grant. Pin, alias and shortcut actions use stable provider/document identities.

**Include in main search** is off initially. Turn it on to include indexed matches outside Files. Explicit file favorites and aliases remain available while folder search is enabled. **Refresh** checks for changes; the app does not monitor every filesystem event. Cancel scan retains the previous results. A refresh can continue while switching settings pages or temporarily backgrounding the workspace; closing the workspace cancels its active work.

Limits are 8 selected folders, 5,000 indexed entries, 16 directory levels, 512 directory queries, an 8 MiB cache and 60 results per query. Provider queries have bounded worker/queue and time limits; incomplete listings display a status or per-folder error. Storage roots, Download root and Android/data or Android/obb can be restricted by Android; choose an allowed subfolder. See the [Storage Access Framework documentation](https://developer.android.com/training/data-storage/shared/documents-files).

Turning search off deletes the private metadata cache and retains selected scopes for reenabling. Removing a scope deletes its indexed metadata and releases a read grant acquired by this feature; preexisting grants are kept. Individually selected recent documents and their grants remain separate. Expired scope grants hide indexed entries and offer reauthorization. Opening an indexed result checks its current document ID and MIME type with the provider. If the provider can no longer find the item, the palette keeps the search open and directs the user to Refresh in Search folders.

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

## Opening an indexed result after a move or restart

DroidCast asks the selected folder’s Android document provider for the tapped item’s current ID and MIME type before opening it. This reads two metadata fields. A file opens in a viewer, and a directory opens in Android’s picker. If the provider no longer recognizes the indexed item, the palette keeps the query and shows “Item unavailable. Refresh the index in Search folders.” A delayed reply can act only while the original search and window still own the request.

On the dedicated API 36 emulator, the test selects a real folder through DocumentsUI, indexes its contents, renames an owned file or directory, then taps the cached result. An Android instrumentation monitor records outgoing intents. The earlier app requested a viewer for the moved file and a picker for the moved directory. The repaired app kept both searches open and displayed the recovery message. A separate case checks “1 indexed item” in both Settings and Files.

| Run | Earlier app | Repaired app |
| --- | --- | --- |
| Initial fixture | All three cases failed. Both move cases stopped at fixture setup. | The singular count passed. Both move cases stopped at fixture setup. |
| Corrected rename | The directory and count checks detected their defects. The file test lost its Compose host after launch. | All three tablet cases passed. |
| Final fixture on the #142 base | Both attempted launches and the incorrect count were observed. | All three cases passed on each of the tablet and phone layouts. |
| Integrated with #143 | All three checks detected their defects using the final instrumentation. | All three cases passed on each layout. The three batches recorded 41 successful cleanup checks. |

The integrated build uses source revision `b8eb54eb`. The comparison APK is the previously verified #143 binary, SHA-256 beginning `17b359ce`, with manifest stamp `local` and production sources matching `3ab115df`. The row screenshots taken before the tap show the cached result. The attempted Android intents are recorded separately. The recovery screenshots show the state after tapping. Moved-result PNGs are native screenshots cropped to the palette bounds in the paired Android hierarchy. Published phone hierarchies include only the app’s nodes. The bindings record original and published hashes. [Device cases and builds](validation/folder-restart/runs.json) · [Capture bindings](validation/folder-restart/bindings.json) · [Host checks](validation/folder-restart/host.json).

A separate tablet workflow selected a folder containing one text file, verified the persisted tree read grant, force-stopped DroidCast and checked that its process was absent. After relaunch, the process ID changed, the saved index bytes matched, and the grant was still present. Tapping the matching row opened Android’s HTML Viewer. Its accessibility hierarchy exposed the fixture title and the complete original sentence. The workflow removed the folder through Settings, verified the grant release and restored the original app and preferences. It passed on the pre-integration and integrated builds.

Three earlier host attempts stopped during setup. The first waited for a Compose test tag absent from the native palette hierarchy. The second expected a navigation drawer in the wide picker. The third reached the indexed count but queried a system dump that omitted the permission records. The successful runs read the fixture’s persisted flags from Android’s activity permission records. [Restart receipts](validation/folder-restart/restart.json).

These runs cover the emulator’s local external-storage provider. Offline and cloud providers, providers that ignore cancellation, and physical devices remain open under #5. Debug and benchmark builds passed, all 356 debug JVM tests passed, and lint reported 57 warnings and zero errors per variant. The 148 Python tests passed in each interpreter mode on the pre-integration revision. The Python sources are identical in the integrated build.

### Changing the file type while an item is opening

An indexed lookup now remembers which search initiated it. Changing the query, activating or leaving folder search, or changing the file type advances a revision. A reply from an older revision can neither open Android’s picker nor replace the current search feedback. Returning to the original file type still counts as navigation. A new tap starts its own lookup.

On API 36, two controlled name reads held the two document-provider workers while the test invoked the palette’s normal directory command. The directory came from a real, user-selected Android tree. The test changed the file type through the Compose menu, released the workers, and waited for the original open to finish. The success case switched to Documents, which hid the directory. The error case first moved the fixture directory and switched from All types to Documents and back before the real metadata query ran.

The comparison build at `b8eb54eb` attempted to launch the picker for the filtered-out directory. It also displayed the late missing-item message on returning to All types. Both comparison assertions used the same instrumentation as the repaired runs. The tests intercept launch attempts. The screenshot records the palette, and the case JSON records the attempted action. Each repaired case also tapped the row again and checked that the new request could open the picker or report the missing item.

The initial harness reproduced the stale launch and confirmed its repair on tablet. On both builds, the error case stopped before lookup because its directory row had yet to appear. The final fixture waits for the row to be displayed before testing navigation. [Case outcomes and APKs](validation/folder-restart/filter/runs.json) · [Screenshots and case bindings](validation/folder-restart/filter/bindings.json).

The repaired build at `e8c4730e` passed all five cases on both tablet and phone. The three final instrumentation batches recorded 41 successful cleanup checks. The separate cold-start workflow also reopened the indexed file and verified the full fixture sentence through the viewer hierarchy. Both app and instrumentation variants built successfully. All 356 JVM tests and 148 Python cases in each interpreter mode passed. Lint reported zero errors and 57 warnings per variant. [Cold-start result](validation/folder-restart/filter/restart.json) · [Host results](validation/folder-restart/filter/host.json).

### Refresh while a provider is loading or offline

A partial directory listing can look like a successful refresh. Android providers can set `DocumentsContract.EXTRA_LOADING` while fetching more children, or `EXTRA_ERROR` when they cannot return a listing. DroidCast now discards the unfinished scan for that folder, reports a retry message beside its name, and keeps its previously indexed names while its tree permission is valid. Healthy folders refresh independently. A completed empty listing removes old entries. Retained entries share the normal index size limits with fresh entries.

On the dedicated API 36 emulator, Android’s picker granted access to a local folder containing one file and a test provider containing five entries. The fixture renamed the local file during refresh. With the earlier app, a loading response containing one row reduced the total to two. An error response containing zero rows reduced it to one. Both updates appeared successful in Settings. With the repair, both responses kept six entries, showed the folder-specific warning, and updated the local filename. A separately constructed store read the same entries back from disk. A completed empty retry reduced the total to one, and a subsequent healthy listing restored all six.

| Device run | Result |
| --- | --- |
| First picker fixture on earlier and candidate apps | Both cases on both builds stopped before selecting the test provider. |
| Earlier app, final fixture, tablet | Both cases failed at the row-preservation assertion. |
| Repaired app, final fixture, tablet and phone | Both cases passed on both layouts. |
| Repaired app, separate tablet revocation regression | Opening a fresh Settings store pruned the revoked folder and preserved the readable folder. |

The final four batches completed 57 inner cleanup checks. The earlier two setup batches recorded 26 separately. The final control uses the same instrumentation APK as the repaired runs. The Settings images are native screenshots cropped to the app workspace, with matching hierarchy subtrees. Full query results and persistence are checked in instrumentation. The fixture controls Android cursor flags and row counts. A commercial cloud service, actual network outage and process death during refresh remain untested. [Case outcomes and APKs](validation/folder-provider/runs.json) · [Screenshot bindings](validation/folder-provider/bindings.json).

Debug and benchmark app and instrumentation builds passed, as did 356 debug JVM tests and 149 Python cases in each interpreter mode. Both lint variants reported zero errors and 57 warnings. An initial Python run failed three cleanup-test setups because the hermetic finalizer lacked the new optional fixture variable. The first benchmark command selected the debug instrumentation configuration and failed task selection. The final host checks include the corrected cleanup harness and benchmark configuration. [Host results](validation/folder-provider/host.json).
