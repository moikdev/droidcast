# Order favorite emoji

Open **Search Emoji & Symbols** and choose **Favorites** from the filter. With the search empty, select an emoji and choose **Move favorite earlier** or **Move favorite later** in Actions. Ctrl+Alt+Up and Ctrl+Alt+Down move the selected favorite one place. Its selection follows the move. At either end, the action that would move past the end is disabled.

New favorites appear at the end. Removing and adding a favorite again also puts it last. A picker that already has favorites adopts their catalog order on its first edit. The unfiltered All view shows the ordered favorites first. Keyword matches still rank by relevance and Recent keeps its copy chronology. Moving favorites leaves recent-history consent alone.

Membership and order are written together in the Emoji preferences. A second open store observes the change. If the catalog cannot display an ID saved by another version, moving its visible neighbors preserves the ID's stored position. See [Backup and restore](BACKUP.md#emoji-favorites-and-grid-size) to transfer the ordered IDs and saved grid size.

## Android verification

On Tinycast_Tablet_API_36, three legacy favorites started in catalog order. The same test APK selected the second entry and injected Ctrl+Alt+Up on each app. The earlier build from `6afcb53c` kept the initial order and failed the assertion. The build from `7b11c9dd` moved the selected entry to the first position. The test then moved it later using Actions and the keyboard, verified the leading order in All, and recreated the activity. A separate case exercised edits from two open stores, including an unavailable catalog ID between two visible neighbors and rejection of an obsolete move.

[Earlier tablet before the move](screenshots/emoji-order/before-tablet.png) · [Tablet after the move](screenshots/emoji-order/after-tablet.png) · [Phone before](screenshots/emoji-order/before-phone.png) · [Phone after](screenshots/emoji-order/after-phone.png) · [Enlarged phone after](screenshots/emoji-order/phone200-moved.png)

The tablet used 1920×1200 at 240 dpi. The phone used 1080×2340 at 420 dpi. Both ran at 100% font and passed five methods each. The phone also passed the ordering case at 200%. Setup and menu assertions used Compose. Keyboard events were injected through Android. The raw hierarchies expose cell bounds and mark the selected symbol with `checked="true"`. All eight captures containing a grid agree with the visible selected name. Compose also asserts selection in the instrumentation workflow. Regenerate the cell summary with `python3 scripts/emoji_capture_geometry.py --cells docs/validation/emoji-order/hierarchies`. The enlarged profile covers the exercised ordering workflow.

The separate [tap workflow](screenshots/emoji-order/move-actions.png) started with a legacy favorites set. It used taps on observed bounds to choose a move, verified the new order in the grid and preferences, force-stopped the process, confirmed its exit, and [reopened the app](screenshots/emoji-order/restarted.png). Another tap moved the new first item later. The runner also checked that the fixture had no recent history. Query text for filtering Actions was entered using Android shell input.

Eleven current instrumentation cases passed. One comparison case failed at the intended order assertion on the earlier app. A preliminary matrix had ten passes and one earlier-app failure, using different APKs. Across both matrices there were 23 JUnit invocations, 21 passes and two expected failures. The pointer workflow ran separately. All eight batches passed ten restoration checks each.

Debug/benchmark app and AndroidTest builds succeeded. The debug JVM suite ran 348 cases with zero failures, errors or skips. Python passed 146 tests per mode on the unchanged scripts. Both lint variants reported zero errors and 57 warnings. All eleven bundled assets match the earlier apps. Physical keyboards, other IMEs, OEM devices, TalkBack and portable backup remain outside these checks.

[Run results and APK identities](validation/emoji-order/runs.json) · [Capture geometry](validation/emoji-order/captured-order.json) · [File bindings](validation/emoji-order/bindings.json) · [Build inputs](validation/emoji-order/sources.json) · [Host checks](validation/emoji-order/host.json)
