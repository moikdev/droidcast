# Palette screens and actions

Issue [#56](https://github.com/moikdev/droidcast/issues/56) replaces app-owned popup windows with screens and menus in the palette's activity. Settings remains a separate fullscreen workspace.

| Flow | Presentation |
| --- | --- |
| Note, quicklink and clip editors; aliases; delete/link-query review; help/notices | Same-window palette screen with Back and a mode title; fields own input |
| Backup, selected folders and the legacy shortcut manager/recorder/reset | Same palette screen; existing embedded Settings presentation retained |
| Store, extension arguments/preferences, List/Grid/Detail/Form | Same floating palette frame |
| Store install/uninstall | Inline review, explicit confirmation, Back/Cancel |
| Launcher, Emoji and extension actions | Shared bottom-right primary/Enter/Actions/Ctrl+K pill; searchable attached action panel |
| Launcher, Emoji, file and Settings categories; extension dropdowns | Bounded selection menu inside the current surface |
| Android document/folder picker, role/permission prompts, package installer | Platform UI remains separate |

## Reference and interaction

Tinycast reference: `37e6043f3f550febb64bdb3cff3ac29a30caad5a`, `PaletteScreen.swift` and `RootPaletteView.swift`. Screens supply rows, search ownership, accessories, body and footer actions into one palette. Its menu/focus coordination informed this implementation.

The user's installed Raycast was inspected directly for its launcher and Emoji footers and action panels. Droidcast follows the grouped primary/action affordance, separate shortcut keycaps, contextual heading, action icons and bottom search field. Android Copy is offered where cross-app Paste is unavailable. This does not claim Raycast's complete action/API set or macOS material rendering.

Searchable roots use one Back/Search/accessory row aligned with launcher Search. Forms replace editable search with a title. The dark panel floats over clear surroundings; an Android-owned flow may temporarily replace the activity.

Launcher query/caret/list position live above route replacement. Back from an extension returns to its Store caller when launched there, preserving the browser query/tab/list position. Cancel from running-command preferences retains the JS session and Grid query/selection/scroll; Run deliberately starts a new launch epoch. Cancel from a collection editor discards its draft. State is scoped to the current activity/session, not process-death persistence.

One menu is open per activity. Arrow/Tab navigation, Enter/keypad Enter/D-pad activation and Escape are handled before underlying inputs. Action search accepts normal text editing; action composition is left to the IME. Category menus support typeahead and Home/End. Closing restores the source input when it still exists. Modifier sequences cannot span a focused menu transition; a background host's late cleanup cannot invalidate the current window's sequence. Configured service shortcuts yield while a focused app menu owns input.

The JS WebView remains attached in a clipped zero-height host, without keyboard focus or accessibility descendants. Runtime and image-loader ownership stays inside the activity's extension session. Package runtime assets and notices are unchanged.

## Validation status

See [Grid and navigation validation](validation/extension-grid.md). Work remains under local tablet/phone validation and is not released or audit-cleared yet. Native Grid is a subset; #6/#7 remain partial. OTA updates #55 follow this checkpoint.
