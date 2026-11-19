# Backup and restore

Search **Backup and restore**, **Export Droidcast backup** or **Import Droidcast backup**. These open the Backup pane in the separate settings workspace. Preview, inspection and confirmation stay inline; Android still owns the storage chooser. Available in development builds after the original 0.4.0-preview release.

Select notes, quicklinks, saved clips, pins and aliases, usage ranking, keyboard bindings, and emoji favorites and grid size independently. Export opens Android's document picker. The output is readable JSON: notes and saved clips may contain private text, so choose its destination accordingly. Cancelling a picker does not change your collections.

Import first reads and validates the selected file. Select available categories, inspect their contents, choose a policy, review the confirmation, then apply. Previewing, cancelling and invalid input do not change stored data. Import does not open imported links or execute commands.

- **Merge** keeps existing versions when identities conflict and skips identical content. Identity matches take priority over content duplicates, preserving edited items and their references. Identical content with a different ID is mapped to its existing identity for imported pins, aliases and ranking. Existing alias overrides and ranking entries take precedence. When duplicate incoming identities map to one item, conflicting aliases block the merge and ranking uses the higher count. Pins are combined.
- **Replace selected categories** replaces only categories you select. Unselected collections and recent documents remain. The confirmation explains that current selected data will be removed.
- Effective alias collisions, including aliases on preserved recent documents, block the entire plan; nothing is partially applied. Exclude the conflicting category or change the alias first. Document aliases are checked locally but remain excluded from portable backups.
- Merging saved clips past their 100-item limit is rejected rather than silently discarding data.

Apply publishes all selected values in one preferences edit on the main thread. The palette and Settings each observe the shared preferences. They can see changes made in the other window. A background commit changes an internal checkpoint to confirm persistence before the app reports success. A second restore reports that another window is saving and can be retried after it finishes. If the commit fails, recovery restores the values it still owns and leaves later user edits in place. The checkpoint is excluded from exports.

## Format and limits

Droidcast reads versions **1** and **2** of `dev.droidcast.backup`. Exporting emoji settings uses version 2. Excluding that category produces version 1 for older readers. Root fields are exactly `format`, `version`, `categories`. Categories are `NOTES`, `LINKS`, `CLIPS`, `PREFERENCES`, `LEARNING`, `SHORTCUTS` and, in version 2, `EMOJI`. Unchecked categories are absent. Items carry `id`, `title`, `body`, `alias`. Preferences carry `pins` and `aliases`; learning is a command-ID-to-count map. Shortcuts contain `version: 1` and a `bindings` map from command ID to `keyCode`/`modifiers`. Up to 2,048 bindings are accepted using the same rules as the recorder.

The reader bounds input to **5 MiB**, validates UTF-8, rejects duplicate/unknown fields, trailing data, excessive nesting, invalid identities and unsupported values. Limits: 2,000 notes or quicklinks per category, 100 saved clips, 5,000 pins/aliases/ranking entries, 512-character titles and 131,072-character bodies. Links use the same HTTP/HTTPS host validation as the Android editor, preserving accepted internationalized URLs and raw values. Aliases follow the palette's existing syntax. Export validates against the same schema and limits.

## Coverage and exclusions

| Palette preference | Treatment |
| --- | --- |
| `notes`, `links`, `clips` | Selected content categories |
| `pins`, `aliases` | Selected command preferences; document references excluded |
| `usage` | Selected launcher learning; document references excluded |
| `shortcuts` | Selected keyboard bindings; recent and indexed-document bindings excluded and preserved locally |
| `files` | Excluded: recent/indexed-document URIs, folder scopes/indexes and grants are device-specific |
| Extension preferences, credentials, code and storage | Separate store, never read by backup |
| Accessibility consent and other Android permissions | Android-owned state, never read or written by import |

This is an Android-native format. It does **not** read Tinycast's AppleArchive/LZFSE `.tinycast` files or Raycast export containers. That compatibility remains tracked in [#24](https://github.com/moikdev/droidcast/issues/24). A JSON backup is not encrypted. It contains no filesystem archive entries to extract and cannot grant file access or enable a service.

## Keyboard binding compatibility

The optional `SHORTCUTS` category is additive within version 1. Older backups without it preserve current bindings. Pre-shortcut Droidcast readers reject the unknown category; uncheck keyboard shortcuts when exporting for those versions. Merge keeps current bindings for existing targets and remaps bindings when identical imported content maps to a local item. Replacement changes selected portable bindings while retaining local document bindings. Conflicting chords, including preserved document bindings, block the plan. The confirmation warns that imported bindings become active if the user already enabled the service. Consent itself is never imported.


## Emoji favorites and grid size

Select **Emoji favorites and grid** to carry the ordered favorites and saved column count to another installation. Merge keeps the current order and appends missing favorites in backup order. A saved local column count takes precedence. An automatic local grid adopts the imported size. Replace uses the exact imported order and size. An empty list with an automatic grid clears both portable preferences on replacement. The confirmation shows the resulting count and grid setting.

The `EMOJI` section contains exactly `favorites` and `gridColumns`. Favorites is an ordered array of unique IDs. A value of 0 for grid columns means automatic, and 1 through 10 specify a saved preference. The picker still adapts that preference to its available width. Unknown IDs retain their positions for future catalog versions. Copy history and consent stay on the device.

## Initial portable emoji verification

The earlier app built from `7b11c9dd` [rejected the version 2 fixture](screenshots/emoji-portable/earlier-error.png). With the same test APK, the build from `0a0a4215` [previewed a merge](screenshots/emoji-portable/tablet-merge.png) that retained the local four-column grid. The [inspection view](screenshots/emoji-portable/tablet-inspect.png) showed the incoming order and names. The [replacement confirmation](screenshots/emoji-portable/tablet-replace.png) instead showed the incoming three favorites and eight-column setting. The test verified the saved values and a new export, including retention of an unavailable ID. These instrumentation cases use a controlled file URI and Compose interactions.

A mixed-category case applied notes alone, then emoji alone, then both. A separate writer changed the stored note between review and apply. The intermediate build at `4bb82501` overwrote that unselected edit. On `0a0a4215` the same test APK preserved it. All selected categories use one palette preference transaction. Legacy emoji favorites and grid preferences are read until their corresponding portable keys are written. History and consent continue to use the local emoji preferences.

The API 36 emulator used a 1920×1200 tablet at 240 dpi and a 1080×2340 phone at 420 dpi. The tablet passed five backup cases on `0a0a4215`, the phone passed three, the enlarged phone passed the portable workflow at 200% font, and five picker regressions passed on tablet. The two comparison builds failed their intended assertions. The earlier development matrix had eleven passes and one expected version rejection with different instrumentation. [Batch records](validation/emoji-portable/runs.json) identify the APKs and individual outcomes.

Debug/benchmark app and AndroidTest builds passed. The debug JVM task executed 349 tests with zero failures, errors or skips. Python passed 146 tests per mode. Both lint variants recorded zero errors and 57 warnings. The eleven packaged assets match the comparison app. [Host checks](validation/emoji-portable/host.json) · [Build inputs](validation/emoji-portable/sources.json) · [Capture and log bindings](validation/emoji-portable/bindings.json)

### Import through the system picker

On tablet, a tap on **Import Droidcast backup** opened Android DocumentsUI. The test selected a synthetic backup served by the test APK’s read-only documents provider. The actual Settings workspace [previewed a merge](screenshots/emoji-portable/settings-preview.png). Tapping Replace and Review showed [two favorites and eight grid columns](screenshots/emoji-portable/settings-confirm.png). After Apply, the test verified the stored order and column count, force-stopped Droidcast, confirmed process exit, and reopened Favorites. The [restarted picker](screenshots/emoji-portable/restarted-favorites.png) shows the two imported symbols in order. Android App info was opened behind the picker to provide a controlled background. These captures were taken after the document picker returned.

The first Downloads-based attempt did not find the test document. A subsequent provider attempt did not reach the file row. Both ended before import. The next two provider runs located the selected row within the document list and completed the workflow. Public screenshots come from the last run. The pointer workflows are separate from the 28 instrumentation invocations. All fourteen batches completed their recorded restoration checks, 143 in total. The app and instrumentation were exercised on the API 36 emulator. Physical/OEM devices, other document providers, a forced storage-commit failure and comprehensive enlarged-text visibility were outside this matrix.

## Edits from another open window

On the API 36 emulator, the palette opened a Merge confirmation for a backup containing one note. Droidcast’s Settings activity then saved a different note. On build `0a0a4215`, applying the backup removed the Settings note from storage. On `41404809`, both notes appeared in search, both stores saw the imported note, and another save from Settings preserved it. The test uses the real activity stores, controlled note entry, a fixture file URI and a Compose click on Apply. [Earlier tablet](screenshots/peer-backup/earlier-note.png) · [Repaired tablet](screenshots/peer-backup/tablet-notes.png) · [Repaired phone](screenshots/peer-backup/phone-notes.png)

Two device cases inject a failed disk commit after publishing the preference change in memory. In one, the peer saves a seven-column grid when recovery takes its snapshot. In the other, it explicitly saves nine columns and then eight, which equals the imported value. The earlier app overwrote both edits with the original four-column setting. The repaired app preserved seven and eight respectively and rolled back the untouched favorite list. Both comparisons use the same instrumentation APK. This exercises the failure protocol with a controlled preferences implementation. Physical disk failure and process death during recovery remain unverified.

An intermediate build at `57ba473d` synchronized the two activity stores. Its tablet run passed six of seven cases. The replacement check observed the previous emoji settings when the restore reported completion. That run remains failed. The final implementation publishes the selected values on the main thread before the background persistence check. The original and intermediate test APKs have separate identities in the [device records](validation/peer-backup/runs.json).

On build `41404809`, 10 tablet cases, five phone cases, one 200%-font phone case and five tablet emoji regressions passed. The same final test APK produced five expected failures on comparison apps, including version 2 rejection and an overwrite of an unselected note. One enlarged-profile invocation stopped at argument validation before any device contact. The corrected invocation passed. A separate [system-picker import](screenshots/peer-backup/settings-preview.png) reached [Settings confirmation](screenshots/peer-backup/settings-confirm.png) and verified the [reopened Favorites grid](screenshots/peer-backup/restarted-favorites.png) after confirmed process exit. The eight final and comparison batches recorded 81 successful restoration checks. The intermediate pair of batches recorded a separate 20 checks.

Both debug and benchmark app and instrumentation builds succeeded. The debug JVM task ran 349 tests. Python passed 146 tests per mode and lint reported zero errors and 52 warnings per variant. The eleven packaged assets and notices matched the earlier app. [Host results](validation/peer-backup/host.json) · [Build inputs](validation/peer-backup/sources.json) · [Published file bindings](validation/peer-backup/bindings.json)
