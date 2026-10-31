# Fixed Emoji search row

Issue [#57](https://github.com/moikdev/droidcast/issues/57), a bounded refinement within [#56](https://github.com/moikdev/droidcast/issues/56). Base: `d6fccdc4989d4d6e2203f8fa331872a9c11d7105` (the same source tree as released r7 / `5419c69`).

Emoji now uses one 56dp header row: Back, editable Search, the tablet's keyboard control and Category. Its search starts at the same horizontal column and vertical position as launcher Search, with the same 32dp field height and 20sp/26sp typography. Removing the old 52dp title row plus 48dp search row frees 44dp for the grid. Long category names truncate to preserve editable search space. Back retains a 48dp touch target entirely before the editable field.

The category selector still uses the existing menu in this focused checkpoint. Store/extension/setup/editor/action/filter popup migration remains open under #56, and native extension Grid remains an unpublished prototype under #6. Fullscreen Settings is unchanged. This is not a claim that the broader inline architecture or full parity is finished.

## Reference

The user's Raycast image establishes Back/Search/Category on a single row. Tinycast source at [`37e6043`](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/Tinycast/Palette/PaletteScreen.swift) provides a common palette search header with screen-specific accessories/body and explicit search-field ownership. The larger architecture conversion belongs to #56; this patch only aligns Emoji with the existing native launcher.

## Validation

Final local checks: successful app/test builds, **113 JVM tests**, **15/15 tablet**, **15/15 phone**, **four cleanup unit tests**, lint **0 errors/42 warnings**. Both intentional old-r7 geometric controls fail at the expected separated-row assertion. Three focused caret repetitions on the APK from the pre-final observation pass; the original cause remains unestablished. The manifest builder's initial report-aggregation indentation error was corrected and its read-only checks rerun successfully; app, test APK and native runner were unchanged by that record-generation correction.

The machine-readable [record](search-header.json) identifies exact app/test APK hashes, source hashes, reports, native runs and image provenance. Local build, JVM and lint reports are retained under ignored `artifacts/`; CI is inactive. No physical/OEM/Lenovo, TalkBack, large-font, latency or endurance certification is claimed. Screenshots establish layout states, not continuous animation or speed.

Owned API36 arm64 emulator `emulator-5590`, AVD `Droidcast_PR48_Tablet_API_36_27caf3`: tablet 1920×1200/240dpi and phone override 1080×2340/420dpi. Runs are sequential, with keyboard service disabled, app unprivileged and adbd UID2000. Each helper records installed-APK equality and restores palette/emoji preference bytes, secure settings and viewport geometry. Tests restore the original clipboard in memory; private preference snapshots are neither published nor part of the audit inputs.

The new native regression measures Search/Back/Category centers and nonoverlap, cycles queries without moving the field, checks a long category leaves usable search space, compares launcher/Emoji Search top/left/height, then reopens Emoji and copies the navigated result. Old r7 negative controls use the final test APK and fail on the actual separated Category/Search baseline, after retaining their genuine before captures. These are intentional failures, not successful product checks.

The final suite includes all 13 Emoji native cases plus two existing floating-palette/rigid-search cases per layout: caret/selection/composition, four-way navigation, same-turn Right→Enter, exact Unicode clipboard, held favorite chords, category menus, empty states, Escape/Back and tablet keyboard-show behavior. The final machine-readable record is authoritative for outcomes. Early tag-based controls and pre-final exploratory runs remain excluded.

One pre-final tablet run (`header-final-v2-tablet`, 14/15) observed `fac` immediately after injecting `e` at the caret, where the test expected `faec`. Its original log is retained. The test now waits up to five seconds for the actual editable value and records its initial/final observation instead of treating input-injection acknowledgement plus Compose idleness as an IME-commit guarantee. No production typing logic was changed for that observation. Same-production focused repetitions and final runs are recorded separately; they do not establish the original timing cause or certify latency.

## Screenshots

All images are unedited Android captures; the record maps them to raw originals and paired native XML hashes. PR embeds must pin the reviewed head. The before captures come from r7; after captures come from the final app hash in the record.

| State | Tablet | Phone |
| --- | --- | --- |
| Before: two rows, `face` | [Before](../screenshots/parity-search-header/before-tablet.png) | [Before](../screenshots/parity-search-header/before-phone.png) |
| After: one row, `face` | [After](../screenshots/parity-search-header/after-tablet.png) | [After](../screenshots/parity-search-header/after-phone.png) |
| Long category | [Category](../screenshots/parity-search-header/category-tablet.png) | [Category](../screenshots/parity-search-header/category-phone.png) |
| Empty result | [Empty](../screenshots/parity-search-header/empty-tablet.png) | [Empty](../screenshots/parity-search-header/empty-phone.png) |
| Tablet on-screen keyboard | [Keyboard](../screenshots/parity-search-header/keyboard-tablet.png) | Automatic phone IME is visible in phone captures |
