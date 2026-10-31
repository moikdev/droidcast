# Droidcast

An unofficial, native Android adaptation of [Tinycast](https://github.com/abue-ammar/tinycast), built with Kotlin and Jetpack Compose. Summon a compact floating search window over the app you are using, type, run a command, and dismiss it. The underlying app stays visible. The native overlay entry point was informed by [Launchpad Search](https://github.com/jaxparrow07/launchpad-search). The palette now ports the visual grammar of [Tinycast](https://github.com/abue-ammar/tinycast): its search header, section labels, neutral selection, compact rows, outlined keycaps, floating action capsule and two-column calculator card.


**Android preview** · Android 8.0+ · AGPL-3.0-or-later

This README follows the development branch. The downloadable tagged preview may predate newly merged parity work; its exact scope is recorded in the release notes and linked PRs.

[Nightly checkpoints](https://github.com/moikdev/droidcast/releases) · [Baseline preview APK](https://github.com/moikdev/droidcast/releases/tag/v0.4.0-preview) · [Parity audit](PARITY.md) · [Recommended improvements](ROADMAP.md) · [Testing record](docs/TESTING.md)

Nightly checkpoints are published manually while CI is inactive. Each release identifies its source commit, APK checksum, tested scope and known findings. Review its notes before enabling experimental features.

This preview covers the core palette workflow and a subset of Raycast extension APIs. **Window-snapping commands, automatic clipboard history and snippets remain unimplemented. Development builds add Android-native backup/restore, centralized settings and partial selected-folder metadata search; Tinycast/Raycast export-container import remains open.** See the parity matrix for the full scope. Droidcast is not affiliated with Tinycast's maintainers or Raycast.

![Droidcast floating command palette over Android Settings](docs/screenshots/palette.png)

## Build and install

Use JDK 17 or 21, Android SDK platform 36 and an Android 8.0+ device. Verification uses JDK 21 and an Android 16 tablet emulator.

Set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=/path/to/Android/sdk`.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper and distribution checksum are included. JDK 21 is the verified toolchain. The installed package remains `dev.tinycast.android` so earlier previews can be upgraded. The app label and public project name are Droidcast.

The downloadable APK is a debug-signed preview, not a production-signed release. CI debug APKs can use a different signing certificate. Uninstalling removes local collections. Development builds provide versioned backup/restore through the document picker; the tagged 0.4.0-preview APK predates this feature.

## The palette

- Translucent activity with Tinycast’s 750×475 tablet proportions and 26 dp corners, adapted to smaller screens. A single search header, Favorites and category sections, compact 44 dp rows, native app icons and a contextual action capsule. No draw-over-other-apps permission.
- Neutral dark surfaces follow Tinycast’s white-alpha color tokens. Android uses its own fonts and a neutral gradient surface; macOS Liquid Glass is not reproduced.
- The circular bottom-left menu opens category filters, Preferences, shortcuts and dismissal. Actions appear in an anchored menu above the bottom-right capsule; Ctrl+K and long-press open the same menu. Calculator answers use source → result columns with unit badges.
- The area outside the palette is fully transparent, with no dark scrim or screen dimming.
- Tap outside or close to return to the previous app. App and URL commands dismiss the palette when launching their destination.
- Summon using the app icon, a bottom-corner swipe or Home-button hold after choosing Droidcast as Android's assistant, the optional Droidcast search Quick Settings tile, or the configurable global shortcut (default Ctrl + Space) after explicitly enabling the keyboard service. Android controls touch gesture availability and mappings; inline setup links to navigation settings. Assign command shortcuts through Actions; see [Keyboard shortcuts](docs/SHORTCUTS.md). The assistant route does not require the keyboard service; [Opening Droidcast](docs/ACTIVATION.md) explains the independent options.
- Fuzzy app/command search, aliases, pins and usage ranking.
- A separate Raycast-inspired settings workspace with searchable command shortcuts and aliases, inline recording/conflicts, folder controls and backup. Open with Ctrl/Meta+,; Ctrl/Meta+Shift+, configures the selected command. See [Settings](docs/SETTINGS.md).
- Dedicated Android settings commands: Wi-Fi, Bluetooth, display, sound, app notifications/info, installed apps, storage, accessibility, date/time and language. These open Android controls; they do not silently toggle hardware.
- Prefixes: `@` apps, `#` notes, `:` quicklinks, `>` built-in commands, `=` calculator.
- Plain numbers such as `123` and `12.5` stay in normal search. Local arithmetic accepts `12x3`, `12*3` and `12×3`, displaying multiplication as `×`. Operators (including unary `+`/`-`, powers and percentages), functions and unit conversions trigger calculation; select Calculator or prefix `=` to calculate a single number deliberately.
- Local calculator and conversions: `200 * 15%`, `sqrt(81)`, `10 km to mi`, `32 f to c`, `1.5 h to min`, `1 gib to mib`. Constants `pi` and `e` remain available. Trigonometric inputs use radians. No currency rates.
- Offline **Search Emoji & Symbols** palette screen with 1,870 emoji, 70 common text symbols, English name/keyword search, category filters, local favorites and recent copies. Keyboard navigation and Copy preserve complete Unicode strings; paste into the destination app yourself. See [Emoji & Symbols](docs/EMOJI.md) for scope and shortcuts.
- Persistent plain-text/Markdown notes, custom HTTP/HTTPS quicklinks with `{query}` arguments, saved clips and shared/selected-text input.
- Android document picker, searchable recent files and opt-in filename/path search inside selected folders using persisted read grants. Metadata index limits: 5,000 items, 8 folders, 16 levels; refresh explicitly. No whole-device storage permission or file-content index. See [Folder search](docs/FOLDER-SEARCH.md).
- Backup and restore: select notes, quicklinks, saved clips, pins/aliases, usage ranking and keyboard bindings; export a Droidcast JSON file, preview its contents, then merge or replace selected categories. Imports leave document grants, accessibility consent and extension data untouched. This does not read Tinycast `.tinycast` or Raycast containers. See [Backup and restore](docs/BACKUP.md).

## Raycast Store extensions

Run **Raycast Store** (alias `store`) to browse, search and install the Store's real prebuilt bundles. The Installed tab lists commands, updates from Store, and uninstallation. Installed commands also appear in palette search and the Extensions filter. Installation alone does not run a command.

The installer refreshes the exact Store entry before downloading because download URLs expire. It validates ZIP paths, size limits, package identity and built command files; updates replace an existing installation only after validation. The Store endpoints are the same public, unofficial endpoints used by upstream Tinycast and may change.

The upstream Tinycast JavaScript runtime is bundled unchanged, including its React reconciler and Raycast API shims. Android supplies a host bridge and native Compose views. A restricted, non-navigating WebView runs JavaScript; it does not render the extension UI or load remote pages. Each foreground command owns a runtime, which is destroyed when you leave it.

Supported Android host capabilities:

- Native `List` and multi-column `Grid`: searchable image/color/emoji cells, section headings, stable selection, four-way keyboard navigation, dropdowns and actions. Grid supports package raster assets and bounded HTTPS thumbnails; unsupported images show a placeholder.
- `Detail` as selectable text/Markdown source.
- Basic `Form` text/password/text-area, checkbox and dropdown fields, descriptions, separators and submit actions.
- Manifest arguments and preferences, including defaults and required fields.
- Foreground clipboard copy/read, isolated LocalStorage and cache, timers, HTTPS fetch and ordinary web URLs.
- Secure random bytes/UUIDs and common hashes. Read-only package assets and limited filesystem access within the extension's own data folder.

**Compatibility is partial.** Installing successfully does not imply every command works. Shell/process execution, AppleScript, Mac apps, desktop helpers, OAuth, menu bars, WebSockets, browser/window-management APIs and Raycast AI services are unsupported. Unsupported host calls report an error. Clipboard paste into other apps is unavailable; use Copy. Advanced form controls, full Markdown rendering and the complete Grid API remain incomplete. Grid section-specific geometry, SVGs, the full icon catalog and automatic pagination are not implemented; pagination uses an explicit Load more control.

Real Store packages tested include UUID Generator (generation, clipboard, native history), OKLCH Color Converter (Store installation, reactive native results and copying), and Palette Colors (native Grid, copying and remembered color scheme). See [Extension Grid](docs/EXTENSION-GRID.md) for its subset and validation. This is not an ecosystem-wide compatibility claim.

Store searches/downloads contact Raycast. Running third-party code can make HTTPS requests and access the foreground clipboard; an install confirmation explains this. Extension storage and preferences are scoped per extension and excluded from OS backup. Uninstall removes the extension and its own data.

## Keyboard

| Shortcut | Action |
| --- | --- |
| Ctrl + Space (configurable) | Show/hide globally, after optional service setup |
| Ctrl + L | Focus search/show keyboard |
| Up / Down, Enter | Select and run |
| Ctrl + K | Actions |
| Ctrl + D | Pin/unpin selected command |
| Ctrl + N | New note |
| Ctrl + 1…8 | All, Pinned, Apps, Notes, Quicklinks, Clips, Files, Extensions |
| Ctrl + S / Ctrl + Enter | Save editor |
| Escape | Close dialog, clear search/back, then dismiss palette |

Configure global and per-command chords or double-modifier taps in **Keyboard shortcuts**. For a double tap, release the same Ctrl, Alt, Shift or Meta key between presses, then choose Either, Left or Right in the inline recorder. Android/OEM delivery varies; the shortcut guide records the tested scope. Enable global use through **Open accessibility settings → Droidcast keyboard shortcuts**. The service does not retrieve screen content or record keystrokes. In-app shortcuts do not require it. Settings → General separates the saved chord, Android approval and live service connection, with restricted-settings help and an optional inline delivery check. Receipt in Settings is distinct from testing activation in another app. Global key delivery can vary with other accessibility services and OEM shortcuts.

## Verify

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push test-fixtures/document.png /sdcard/Download/tinycast-test-image.png
adb shell am instrument -w -r dev.tinycast.android.test/androidx.test.runner.AndroidJUnitRunner
```

The Store tests require internet access and intentionally install/run public Store packages. The tests cover real bundles rather than locally substituted commands. The file test requires the PNG fixture. Named note/clip fixtures are cleaned up. The color extension is left installed for inspection.

Global shortcut testing runs outside UiAutomation because UI instrumentation can suppress accessibility services:

```sh
python3 scripts/test-configurable-shortcuts.py --serial emulator-5580 --rounds 3
```

The helper restores preferences, accessibility consent and unprivileged adbd in a finally block. It requires the saved TXT fixture from the extended workflow below. It is restricted to the dedicated `Tinycast_Tablet_API_36` emulator. Root adbd is used only to inject hardware-style Linux key events; the app stays unprivileged and does not require root.

## Extended workflow test

`EnduranceDeviceTest` is opt-in; ordinary test runs skip it. It repeatedly opens Settings through both the built-in command and installed-app search, copies calculated answers, uses the Actions menu, opens image/PDF documents and rotates the display. It also opens a text file through Android’s viewer chooser and verifies its saved read grant and contents. Use at least five minutes; the test also requires at least 20 rounds.

```sh
adb push test-fixtures/document.png /sdcard/Download/tinycast-test-image.png
adb push test-fixtures/document.pdf /sdcard/Download/tinycast-soak-document.pdf
adb push test-fixtures/document.txt /sdcard/Download/tinycast-soak-document.txt
adb shell am instrument -w -r -e class dev.tinycast.android.EnduranceDeviceTest -e runSoak true -e durationSeconds 300 dev.tinycast.android.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p artifacts
adb exec-out run-as dev.tinycast.android tar -C files -cf - extended-test > artifacts/endurance-device-evidence.tar
```

After a successful endurance run, `python3 scripts/test-restart-files.py --serial emulator-5580` force-stops both Tinycast and each viewer, then reopens all three recent documents.

The image test expects Google Photos, the PDF test expects Google's PDF viewer, and the text test selects the built-in HTML Viewer through Android’s chooser, matching the dedicated AVD. Evidence is written to the app's `files/extended-test/` folder. Do not operate the emulator during automation. Native Android split-screen and divider resizing can be used around the palette; Tinycast does not yet provide commands that snap other apps into windows.

## CI template

A pinned, lint-checked workflow is provided in [`ci/android.yml`](ci/android.yml). It is not active: the publishing credential can create repositories and push source, but lacks GitHub’s `workflow` scope. See [`ci/README.md`](ci/README.md) to enable it with an authorized credential. Local build, unit, lint and device checks are documented in [docs/TESTING.md](docs/TESTING.md).

## Parity loop

Enhancements and audited PRs are tracked in [issue #1](https://github.com/moikdev/droidcast/issues/1) and [docs/PARITY-TRACKER.md](docs/PARITY-TRACKER.md). Every implementation PR includes real emulator/device screenshots and two independent `gpt-6.1-sol` high-effort audits before merge.

## What to improve next

The highest-value next steps are verified extension compatibility and richer native views, cross-platform backup-container import, advanced hotkeys, further Android intent/automation commands, broader document-provider validation and supported multitasking actions. These are proposals, not advertised preview features. [ROADMAP.md](ROADMAP.md) gives priorities and acceptance criteria; [PARITY.md](PARITY.md) compares the current implementation with upstream.

## Source and attribution

- `PaletteTheme.kt`, `PaletteUi.kt`: adapted Tinycast design tokens, floating palette, grouped rows, calculator card and keyboard controls.
- `third_party/tinycast-palette/`: unchanged Swift reference sources and exact commit provenance for the visual port, licensed AGPL-3.0.
- `CommandEngine.kt`, `PaletteStore.kt`: search, calculation, persistence and command dispatch.
- `MainActivity.kt`, `PaletteTileService.kt`: Android entry points, intents and file grants.
- `Shortcuts.kt`, `AndroidShortcuts.kt`, `ShortcutUi.kt`: chord rules, key filtering, authenticated dispatch and shortcut recording.
- `extensions/ExtensionCatalog.kt`: Store networking, archive validation, install/update/uninstall and manifest state.
- `extensions/ExtensionRuntime.kt`, `assets/android-runtime.js`: Android host and JavaScript engine adapter.
- `extensions/ExtensionUi.kt`: native Store, preferences and extension rendering.
- `third_party/tinycast-runtime/`: upstream source, build files, license and exact commit provenance. The generated runtime asset is copied unchanged. Store extensions are downloaded at runtime under their respective licenses, not bundled into this APK.

Project license: AGPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for upstream attribution and modification notices. No analytics or account is required. AI commands and background clipboard monitoring are not included.
