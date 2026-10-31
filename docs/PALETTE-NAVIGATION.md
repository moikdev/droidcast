# Palette navigation migration — work in progress

Issue #56. The source audit found these app-owned window/menu sites:

| Owner | Existing presentation | Target |
| --- | --- | --- |
| Note/quicklink/clip editors, alias/delete/link query, help/notices | Shell Dialog | Same-window palette route; inline form/review |
| Backup and selected folders | Shell Dialog; embedded Settings content already exists | Palette route when invoked there; keep fullscreen Settings workspace |
| Shortcut settings/recorder/reset | Shell Dialog; embedded Settings content already exists | Inline palette route; preserve recorder ownership and cancellation |
| Store / extension setup / command runner | Separate ExtensionWindow Dialog | Same palette frame and fixed search row for searchable roots |
| Store install / uninstall | AlertDialog | In-surface confirmation route with explicit Back/Cancel |
| Launcher actions | Popup | One bounded menu owned by the palette surface |
| Launcher, Emoji, file filters, Settings categories | DropdownMenu | In-surface selection menu, one focus owner |
| Extension Grid/List actions and dropdowns; setup/Form options | DropdownMenu | Same in-surface menu host |
| Document/folder picker, permissions, assistant selection, installer | Android system UI | Remain platform flows |

Tinycast reference: `37e6043f3f550febb64bdb3cff3ac29a30caad5a`, `PaletteScreen.swift` and `RootPaletteView.swift`. Screens supply rows, search ownership/accessories/body and actions into one palette; one menu/focus coordinator handles attached menus. Forms may own focus and hide editable search. This is an architecture reference, not a claim of feature parity.

The fixed Emoji header is independently tracked in #57. Carry its verified change forward rather than publishing the discarded popup Grid prototype. Route state must retain launcher query/caret/scroll and unsaved form drafts; Back must prefer menus and extension navigation before returning to the caller. Verify one activity window/focus tree, query restoration, keyboard sequences, IME shrink, outside dismissal and confirmation cancellation on both layouts before PR publication.

The initial shared-frame/editor and extension-window migrations compile but are not yet device-tested. Native Grid now owns its single Back/Search/filter row in the shared frame. Store/List headers, in-surface install/uninstall reviews, actions and selection-menu conversion are still pending. This file does not certify the migration as complete.

## Remaining implementation constraints

- Host selection/action menus inside the palette's measured root bounds, with one open menu and one focus owner. A naive child Box that draws outside a header row does not provide reliable pointer hit testing; position the menu in the actual surface host. Preserve named rows, keyboard selection/activation, Escape key pairing and focus return.
- Keep forms/reviews in the same frame; search is replaced by a mode title when the form owns keyboard focus. Preserve drafts through nested confirmation/back steps.
- Hoist the running extension session and Grid state above setup/run route replacement, within the activity's composition lifetime. Opening preferences should not destroy a command solely because its body leaves composition. Starting with changed preferences/arguments may deliberately create a new launch epoch. Do not retain an activity-backed WebView in a longer-lived ViewModel.
- The zero-height clipped host keeps the 1dp JS WebView attached without moving Grid's shared header origin. Verify actual JS delivery and lifecycle on device; Kotlin compilation alone does not prove it.
- Keep the new fixed Emoji header from #57, including its full Back touch target and exact launcher alignment. The older unpublished prototype's Emoji edit must be superseded when rebasing.
