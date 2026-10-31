# Native Grid and inline palette validation — in progress

This slice covers #56 and part of #6/#7, against merged header checkpoint `227d5100f8a4eec0e99cf3f3a369e02a0b89d801`. It is not yet audit-cleared. Final build identity, counts and durable screenshots are pending completion of the local pipeline. No prototype pass is final-binary certification.

The owned API36 arm64 emulator uses 1920×1200/240dpi tablet and 1080×2340/420dpi phone viewports. Application/adbd remain unprivileged for native UI tests, with the optional keyboard service off. Helpers guard AVD identity, snapshot private preferences, independently restore settings/data/geometry and verify installed APK bytes. Private snapshots are excluded from published evidence and reviewer inputs.

Coverage includes native Grid geometry/visuals, package-boundary rejection, typing/navigation, event-time activation, composition/caret, dropdowns, explicit pagination, stable reorder identity, Detail/back and preferences cancellation, actual IME shrink and the footer above the keyboard. A JS action counter provides a FIFO barrier for one physical held Enter sequence. Real Palette Colors and OKLCH exercise Store bundles; Tailwind retains the existing expanded-file-limit failure. Store UI coverage exercises review/cancel/install/run/back and uninstall/cancel/confirm.

Inline regressions cover editor/query/caret restoration, bounded menus and physical focus, current-window ownership, programmatic menu dismissal, alias conflicts/delete cancellation and feedback bounds. Searchable actions cover no matches, token filtering, exact payload, held shortcuts, deletion review, Emoji Unicode copy and focus restoration. Existing Emoji/header, Settings/backup/folder/shortcut cases are being rerun as affected by the shared shell/menu changes.

## Retained development failures

- The old Grid popup prototype is not the final presentation or certification.
- A physical-focus negative records the extension shell focused instead of its menu. The corrected shell yields while the menu is open; its focused positive records the menu owning focus.
- A 35-case tablet attempt passed 30 and failed five. Four legacy Emoji helper failures tried to address rows absent from the new virtualized menu; the helper now scrolls the actual menu list. The other capture required an extension title no longer shown after header consolidation; the title is now contextual footer content.
- An action-footer exploration showed the rendered root capsule but its decorative semantics merged into the palette's no-op click target. The root now consumes blank-area taps without merging descendants. This is distinct from visible button failure.
- An Emoji action capture requested an offscreen third row while the IME left one visible row. Action-menu scrolling/search remain required; the capture now uses a visible marker. This is not evidence of a missing Unicode action.

Existing header caret timing, physical/OEM/Lenovo, TalkBack/large-font, latency/continuous animation, endurance, full Markdown/metadata/Forms, broad Store compatibility and process-death persistence remain uncertified. CI is inactive; local/android is a local validation/audit gate.
