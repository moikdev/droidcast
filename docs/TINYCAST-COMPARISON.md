# Tinycast source comparison

The reference is a complete, read-only local clone of [Tinycast at `37e6043f3f550febb64bdb3cff3ac29a30caad5a`](https://github.com/abue-ammar/tinycast/tree/37e6043f3f550febb64bdb3cff3ac29a30caad5a). Its current HEAD matches Droidcast's previously pinned runtime and palette references. The full checkout adds the architecture, feature coordinators, navigation policies and upstream tests that the small vendored visual reference does not contain.

This comparison reviews the files below; it is not a claim that every upstream feature has been reviewed or ported. The installed Raycast supplies a visual/interaction reference. Installing the macOS Tinycast app is optional; it was not built or launched for this comparison.

| Responsibility | Tinycast source | Droidcast now | Remaining work |
| --- | --- | --- | --- |
| Screen contract | `Palette/PaletteScreen.swift`: rows, primary-action availability, search ownership, accessories, actions and navigation hooks | Shared `PaletteFrame`, `PaletteActionCapsule` and menu host, with separate launcher/Emoji/extension handlers | Introduce a typed Kotlin screen contract; derive footer visibility and input ownership from it |
| Navigation | `Palette/PaletteState.swift` and `PaletteMode.swift`: typed modes and push/replace/pop frames holding query/selection | `PaletteStore.dialog` string routes, launcher state retained above replacement, extension-session state retained per root | Replace string dispatch and special return flags with typed routes/back-stack entries; preserve Android lifecycle boundaries |
| Actions | `PaletteScreen.swift`, `ActionMenuSearchQuery.swift`, `RootPaletteView.swift` | One attached searchable action panel with icons, keycaps, contextual title and focus restoration | Port fuzzy/diacritic matching and strongest-match selection while retaining Droidcast's order-independent word matching; let a screen hide redundant Actions for a single-submit form |
| Escape and Tab | `PaletteEscapeAction.swift` and `PaletteTabAction.swift`: explicit ordered policies | Policies are distributed across launcher, Emoji, Grid and menu handlers | Centralize precedence and add upstream-derived cases; Tinycast clears a nonempty menu query before closing the menu. Keep Android Back/IME behavior explicit |
| Menu ownership | `RootPaletteView.swift` and `MenuPanel.swift` centralize opening, highlight, query, focus and closing | `PaletteMenuHost`/`PaletteMenuOwnership` own the active overlay and modifier boundary | Preserve one policy as new screens arrive. Tinycast uses an attached macOS `NSPanel`; Android deliberately renders menus inside the existing activity instead of copying that window mechanism |
| Grid | `Extensions/Model/ExtensionGridGeometry.swift`, `ExtensionGridLayout.swift`, `UI/ExtensionCommandScreen.swift` | Native Kotlin cells, section-aware movement, bounded images and current-item actions | Vertical navigation has an independent upstream oracle below. Desktop default columns/inset fractions, complete image/icon APIs, metadata and Forms remain partial; responsive Android geometry is an explicit adaptation |
| Runtime | `Extensions/Service/ExtensionRuntime.swift` and `ExtensionHostBridge.swift`: a command-owned JS context, host boundary and rendered snapshots | Unchanged vendored generated JS plus an Android host and session-owned headless WebView | Retain the shared runtime; expand supported host APIs with explicit capability/error behavior. A new render-tree object is a normal publication, not proof that its JS session restarted |
| Feature boundaries | `docs/architecture.md`: pure models, platform services, observable state and feature coordinators | Some pure policies and separate services exist, but `PaletteStore` and UI routing still combine several features | Extract policy and feature coordinators incrementally, preserving existing behavior with tests; use Android repositories/ViewModels rather than copying the macOS singleton/window lifecycle |
| Cross-app insertion | `Emoji/UI/EmojiScreen.swift` and host/paste services | Foreground Copy on Android | Direct paste into another app remains a platform-replacement gap; a matching footer appearance does not establish that capability |

Paths in the Tinycast column are relative to `Tinycast/`, except `docs/architecture.md`.

## Executed source checks

The upstream `action-menu-search-test` was compiled with its shipped `ActionMenuSearchQuery.swift` and `SearchRelevance.swift`: **eight assertions passed**. These are upstream-model checks, not evidence that Droidcast already implements the same matcher. They identify concrete expected cases: non-contiguous `ptxt`, stronger exact matches and `resume` matching `Résumé`.

[The oracle generator](../scripts/generate-tinycast-grid-oracle.py) compiles the unchanged pinned `ExtensionGridGeometry.swift` and generates [608 valid-cell scenarios](../app/src/test/resources/tinycast-grid-navigation.tsv). [The JVM parity test](../app/src/test/java/dev/tinycast/android/TinycastGridParityTest.kt) checks **1,216 Up/Down expectations** against Droidcast's shipped `gridMove`. The matrix covers 1–8 columns, section boundaries, short final rows and top/bottom edges. The expected values come from upstream execution, not a second copy of the Kotlin algorithm. All 1,216 expectations pass within the current 132-test JVM suite. This checks vertical geometry only; it does not certify full Grid or UI parity.

Reproduce with Swift 6 and an unmodified reference checkout:

```sh
python3 scripts/generate-tinycast-grid-oracle.py --upstream /path/to/tinycast --output app/src/test/resources/tinycast-grid-navigation.tsv
./gradlew testDebugUnitTest
```

## Refactoring sequence

Keep the current inline-palette checkpoint independently reviewable, then track the screen-contract/back-stack/action-policy refactor in [enhancement #60](https://github.com/moikdev/droidcast/issues/60) with explicit acceptance criteria. Migrate launcher and Emoji first, then extension modes and editors, keeping screenshots and equivalent behavioral tests at each step. OTA updates #55 remain the next requested release feature. The parity tracker must continue distinguishing working equivalents, partial implementations and Android platform replacements.
