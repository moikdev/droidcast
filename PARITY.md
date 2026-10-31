# Tinycast parity audit

Audited 2026-10-30 against [Tinycast](https://github.com/abue-ammar/tinycast) main at [`37e6043`](https://github.com/abue-ammar/tinycast/tree/37e6043f3f550febb64bdb3cff3ac29a30caad5a). The upstream revision was refreshed and confirmed before this audit. Droidcast baseline: Android 0.3.1, with the same command implementation in the 0.4.0 preview and updated public branding.

Droidcast reproduces the central summon → search → run → dismiss workflow. It is an early Android adaptation, not a complete Tinycast port. No parity percentage is assigned: a working calculator is not equivalent in scope to an extension ecosystem or desktop window manager.

**Partial** means a working subset, **missing** means no implementation, and **replacement** means a macOS feature needs an Android-specific design. A source inspection is not an end-to-end compatibility test.

## Feature matrix

| Area | Status | Droidcast behavior and remaining gap |
| --- | --- | --- |
| Floating palette | Working Android adaptation | Native Compose palette, transparent surroundings, outside-tap/Escape dismissal, contextual actions. Uses Tinycast geometry/tokens; macOS material and fonts are not reproduced. |
| App search and launch | Partial | Fuzzy search, aliases, pins and usage ranking work. No running-app indicators, focus/hide toggles or quit-all behavior. |
| Global activation | Partial | Configurable ordinary chord (default Ctrl+Space) through an opt-in key-filter service; icon, assistant intent and Quick Settings tile. Saved chord, Android approval and live service connection are distinct, with restricted-settings help and a bounded inline delivery check. Advanced modifier taps/Fn/Hyper remain open in #26; OEM delivery is not established. |
| Per-app / per-command hotkeys | Partial | Record, clear/reset and conflict-check ordinary command/app/item chords; result keycaps and existing execution paths. Advanced upstream hotkeys remain open in #26. |
| File search | Partial | System picker, searchable recent documents, opt-in bounded filename/path index for selected folder trees, type filters, persisted read grants and external viewers. No content index/preview or certified cloud-provider behavior; remaining validation is tracked in #5. |
| Dictionary lookup | Missing | No dictionary command, dataset or definition view. |
| Clipboard | Partial | Explicitly saved text, shared/selected-text input, foreground clipboard access and copy actions. No automatic history, image collection or paste-back into another app. |
| Calculator | Partial | Arithmetic, percentages, functions and a fixed set of unit conversions. Bare numbers remain search text; x/*/× multiplication displays ×. Explicit `=` mode accepts single numbers. No live currency/crypto rates, date/time arithmetic or built-in color card. The tested color extension is a separate capability. |
| Quicklinks | Partial | HTTP/HTTPS links, aliases and one URL-encoded `{query}` value. No multi-argument editor, date/clipboard substitutions, file targets or general app deeplinks. |
| Apple Shortcuts | Replacement | Apple's automation framework does not run here. An Android intent/automation command catalog has not been built. |
| Snippet templates | Missing | Saved clips and notes are plain content, not variable-aware templates. No keyword expansion or nested snippets. |
| Shell/custom commands | Replacement | The Android host exposes no process execution. macOS tools and native helpers do not port directly; scoped Android intents are a more useful first replacement. |
| Window management | Missing | No half/quarter/third placement, resizing, display movement or snap commands. System split-screen and divider resizing were exercised separately; that is not an implemented Droidcast command. |
| System actions | Partial | Root Settings plus 11 dedicated Android destinations, including Wi-Fi, Bluetooth, display, sound, storage, accessibility and Droidcast app-info/notification settings. Missing hardware toggles and other upstream privileged actions remain open. Battery/security destination validation is tracked separately in [#22](https://github.com/moikdev/droidcast/issues/22). |
| Calendar and meeting actions | Missing | No calendar permission flow, event search, upcoming-meeting card or join command. |
| Notes | Partial | Persistent text/Markdown source, search and an editor. No rendered Markdown, file-backed notebook, floating companion editor or note import/export. |
| Emoji and symbol picker | Partial — offline slice | Searchable native grid: 1,870 pinned gemoji entries + 70 common text symbols, English names/aliases/keywords, category filters, favorites, 32 recent copies and exact Unicode clipboard delivery. Tablet/phone validation is recorded in `docs/validation/emoji-picker.md`. Automatic insertion into other apps, tones, multilingual/custom keywords, adjustable density and portable favorites remain in #44. |
| AI chat | Missing | No providers, credential setup, conversations or model calls. |
| Text transformation actions | Missing | Selected text can be received and saved; rewrite, translate and summarize actions are not implemented. |
| Raycast extension installation | Partial | Real Store search, package download, validation, install, update and uninstall. Uses unofficial endpoints. Store installation is not a compatibility guarantee. |
| Extension views | Partial | Native lists, multi-column Grid with package/HTTPS raster images, colors, emoji, headings, search, stable selection, dropdowns, explicit Load more and actions/navigation; basic forms and selectable Detail source. Full Markdown/metadata, SVGs/full icon catalog, section-specific Grid geometry, automatic pagination and advanced Forms remain incomplete. |
| Extension host APIs | Partial | HTTPS, clipboard, timers, local storage/cache, preferences/arguments, limited crypto and scoped files. OAuth, menu bars, shell/native helpers, browser/window management, WebSockets and Raycast AI are not available. |
| Backup and import | Partial | Selectable versioned Droidcast JSON export/import for notes, quicklinks, saved clips, pins/aliases, usage ranking and portable keyboard bindings, with preview, content inspection and merge/replace confirmation. Capability grants and extension data are excluded. Tinycast AppleArchive and Raycast container compatibility remain open in [#24](https://github.com/moikdev/droidcast/issues/24); Android OS backup stays disabled. |
| Settings workspace | Partial | Separate Raycast-inspired native preferences, settings search, central shortcuts/aliases, inline editing, folder scopes and backup. Appearance and advanced keyboard options remain open. |
| Settings mirror/history | Missing | No editable settings file mirror or change history; [#27](https://github.com/moikdev/droidcast/issues/27). |
| Dictation | Missing | No local speech model, microphone flow or dictation command; [#28](https://github.com/moikdev/droidcast/issues/28). |
| Camera | Missing | No camera preview/capture command; [#29](https://github.com/moikdev/droidcast/issues/29). |
| MCP | Missing | No MCP transport/tool capability flow; [#30](https://github.com/moikdev/droidcast/issues/30). |
| Frontmost app menu search | Replacement | No supported Android action-search equivalent; [#31](https://github.com/moikdev/droidcast/issues/31). |
| Custom command library | Replacement | No scoped automation catalog/argument workflow; [#32](https://github.com/moikdev/droidcast/issues/32). |
| Appearance, localization, accessibility | Partial | Dark theme and extensive keyboard handling. No light/system appearance, language translations or comprehensive TalkBack/large-font/physical-device audit. |

## Evidence and boundaries

The earlier Android 0.3.1 validation includes 18 unit tests, 20 device regression tests, a **308.1-second, 21-round endurance run**, three cold-restart document workflows and 20 real global-key activations. Settings, PNG/PDF/TXT viewing, calculator copying, Actions menus, eight orientation changes and native split-screen coexistence were exercised. No new crash-buffer entry or ANR was observed. See [the testing record](docs/TESTING.md) for version scope and reproducible commands.

**UUID Generator**, **OKLCH Color Converter** and **Palette Colors** have bounded end-to-end real Store bundle coverage. The Grid slice records versions, checks and remaining limitations in [its validation record](docs/validation/extension-grid.md). A successful download, installation or view render is not evidence that all commands or all extensions work. Isolation checks cover ZIP traversal and private-file rejection; they are not an independent security audit.

Implementation evidence:

- [`CommandEngine.kt`](app/src/main/java/dev/tinycast/android/CommandEngine.kt): command kinds, fuzzy ranking, arithmetic and units.
- [`PaletteStore.kt`](app/src/main/java/dev/tinycast/android/PaletteStore.kt): complete built-in catalog, saved collections, aliases and extension commands.
- [`MainActivity.kt`](app/src/main/java/dev/tinycast/android/MainActivity.kt): all dispatch branches, document grants, assistant/sharing entry points and global shortcut service.
- [`PaletteUi.kt`](app/src/main/java/dev/tinycast/android/PaletteUi.kt), [`PaletteDialogs.kt`](app/src/main/java/dev/tinycast/android/PaletteDialogs.kt): native palette, editors, preferences and actions.
- [`extensions/`](app/src/main/java/dev/tinycast/android/extensions): Store installer, Android host bridge and view renderer.
- [`androidTest/`](app/src/androidTest/java/dev/tinycast/android): automated device workflows and the opt-in endurance test.

## Android-specific constraints

The following affect the design of improvements; they do not excuse labeling an absent feature as implemented.

- Background clipboard access is restricted on modern Android. Keep capture explicit; a broad desktop-style history cannot be assumed. [Android privacy documentation](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data).
- Folder access can use user-selected directory grants rather than whole-device storage permission. Providers can revoke access or impose directory restrictions. [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
- Window behavior depends on device/version. The accessibility API's split-screen toggle is a device-dependent action, not a general 34-action desktop window manager. [Multi-window](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode), [split-screen action](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN).
- Dedicated settings destinations have public intents; hardware toggles and privileged actions need separate capability checks and cannot be inferred from those intents. [Settings intents](https://developer.android.com/reference/android/provider/Settings#ACTION_WIFI_SETTINGS).

The recommended implementation order and acceptance criteria are in [ROADMAP.md](ROADMAP.md). The live enhancement/PR loop is tracked in [docs/PARITY-TRACKER.md](docs/PARITY-TRACKER.md) and [issue #1](https://github.com/moikdev/droidcast/issues/1).
