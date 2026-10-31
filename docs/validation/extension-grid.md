# Native extension Grid validation

This is the bounded Grid slice of #6/#7, based on `d6fccdc4989d4d6e2203f8fa331872a9c11d7105`. It does not complete either issue. See [supported behavior and remaining API gaps](../EXTENSION-GRID.md).

## Environment and build identity

An owned API 36 arm64 emulator runs the tablet viewport at 1920×1200 / 240 dpi and a phone override at 1080×2340 / 420 dpi. Tests require adbd UID 2000 and the optional Droidcast keyboard service off. Instrumentation input is synthetic foreground input, not physical Lenovo/OEM keyboard certification. The final pipeline uses the isolated AVD identified in the JSON record, never the shared emulator.

App and test APK hashes, source hashes, individual test results, screenshot origins and native hierarchy hashes are recorded in `extension-grid.json`. The release-r7 before APK has SHA-256 `8ff881ce95ec90669105a3859ffeda2bdc675c265c9ea5faf2e26ed625324c0c`; its reviewed `5419c69` tree equals this slice's base tree. Before captures use the same authored React fixture through the unchanged runtime, with the old flattened List renderer. These are real device captures, not mocks.

## Checks

Final local results are recorded in the companion JSON. CI remains inactive; `local/android` is a local validation/audit gate.

The Grid tests exercise local and HTTPS image decoding, rejection of a valid PNG outside the extension package, missing/SVG image feedback, columns/sections, empty/loading states, four-way navigation, same-main-thread Right→Enter, caret/selection/composition preservation, callbacks, dropdown filtering, explicit pagination, reordering by stable ID, pushed Detail/Escape return, outside-tap dismissal, and actual docked-IME shrink with unchanged selection. The keyboard regression compares clipped and unclipped cell heights and requires the native primary action to remain above the keyboard. The window shell remains floating while its usable contents resize.

Real Store checks cover:

- **Palette Colors:** native color Grid, valid hex clipboard output, Contrast dropdown callback and remembered scheme after reopening.
- **OKLCH Color Converter:** legacy native List rendering, query input, explicit selection of the OKLCH row and exact displayed-value copy. The first row is Figma P3; selecting it does not copy OKLCH.
- **Tailwind CSS:** expected rejection by the preexisting 16 MiB expanded-file limit, visible error and no installed package. The independently downloaded Store archive inspected during discovery includes a 23,606,473-byte source map; the rejected native archive itself was not retained for byte comparison. This is an unsupported bundle, not working Grid evidence.

Basic Form editing/submission and five existing emoji/palette regressions also run on the final pair. These are scoped suites, not every instrumentation test or a new endurance certification.

## Corrections and excluded attempts

The initial Grid fixture exposed a Detail→Grid Escape failure: the screen remained in Detail after the editable child disappeared. The retained negative capture/log records that state. The corrected shell supplies focus only when no descendant owns it; the hidden JavaScript-engine WebView cannot take focus. The final test verifies preserved query/selection and root outside-tap dismissal.

The first expanded run passed eight of nine Grid tests but exposed keyboard overlap. An intermediate cell-visibility test passed while its footer was still covered; visual inspection rejected that as complete evidence. The strengthened test then rejected both a clipped cell and the footer overlap. The API 36 dialog reported an IME inset of 504 px while its activity reported 552 px. The corrected window uses a full-screen transparent input surface, safe insets and the larger live IME inset, with a floating dark panel and smaller thumbnails when needed. Final captures must show the complete selected cell and action button above the real keyboard.

The first 16-case tablet attempt passed 15, failing an incorrect legacy-test assumption that OKLCH was the first row. Pinned upstream source confirms Figma P3 comes first. The corrected test selects the row whose displayed title begins `oklch(` and compares the exact copied value. No production change was made for that assertion correction.

The subsequent tablet attempt passed 16/16, while its phone attempt passed 14/16: the pagination assertion ran before the JavaScript callback had published 33 items, and a late clipboard overlay defeated the one-shot dismissal. The final harness awaits the actual model counts and handles observed overlay appearances within a bounded deadline. Screenshot review also exposed a late IME-show race after a Form; the Grid hide effect now observes live IME visibility, compact cells use explicit text line heights, and the real Palette Colors tablet case requires the keyboard hidden before capture. Grid capture additionally checks full selection visibility and matching native count/query before recording the pair.

A further tablet run passed 15/16 but its Store capture hit UiAutomator `StaleObjectException` when the overlay disappeared between lookup and click. The bounded dismissal now catches only that stale-node condition and re-queries; it still fails if the overlay remains. This attempt is also excluded from final passing counts.

The next tablet run passed 16/16, but phone evidence exposed a real selected-cell clipping bug after reordering through a keyboard/popup transition. The retained focused negative shows image-2 selected and copied correctly while its label remains below the footer. The correction retains pending visibility recovery across measured row changes, stops after full visibility and yields to manual scrolling. Both focused phone cases then passed. The real Store test also stopped assuming Contrast count is exactly twice Mono count: Mono may deduplicate to seven colors. It now verifies a changed palette and restoration of that exact palette after reopening.

Other fixture/build/transfer and partial Store attempts remain excluded from final passing claims. Palette Colors initially rendered/copied successfully but its next capture was blocked by Android's clipboard overlay; final capture dismisses the observed SystemUI control explicitly. One emulator exit occurred between runs; the next guard stopped before mutation and the owned emulator was restarted. Earlier restoration records remain retained.

## Evidence and limits

Every committed PNG is an unedited Android screenshot. The JSON maps final tablet/phone, r7 baseline and clearly labelled prototype-failure images to retained originals, hashes, dimensions and paired native XML. Screenshot captions describe visible states. Stills do not establish latency or absence of continuous animation. The final tests also assert immediate selection/copy behavior and the existing rigid main-palette geometry.

The guarded runner snapshots/restores palette preferences and the three relevant secure settings, checks geometry and ordinary identities, deletes its fixture files, and records each independent cleanup action. The preexisting clipboard is cleared before fixtures render, and the original clip is held/restored only in memory. Screenshots may show the test fixture’s own copied text. Temporary Store installs are removed; a preexisting matching installation is rejected instead of overwritten. Installation equality and restoration are execution records; independent reviewers must distinguish them from their own read-only inspection.

Physical/OEM/Lenovo delivery, TalkBack, large fonts, latency, endurance, advanced Grid properties, broad Store compatibility, rendered Markdown/metadata and complete Forms remain unverified or incomplete. No upstream runtime or notice bytes change.
