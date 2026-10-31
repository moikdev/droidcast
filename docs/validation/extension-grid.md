# Native Grid and inline palette validation

Scope: #56 and part of #6/#7, against merged header checkpoint `227d5100f8a4eec0e99cf3f3a369e02a0b89d801`. Local checks are complete; independent audit clearance is required before merge/release. Native Grid and the Tinycast screen architecture remain partial. See the [complete source comparison](../TINYCAST-COMPARISON.md) and structural follow-up #60.

## Build and executed checks

| Check | Result on the final app |
| --- | --- |
| App/test build | Success |
| JVM | 124 tests, zero failures/errors/skips; includes 1,216 upstream-derived Grid expectations |
| Lint | 0 errors / 44 warnings |
| Core Grid/inline flows | 26 tablet + 26 phone |
| Shared workspace flows | 27 tablet + 24 phone |
| Existing Emoji workflows | 13 tablet + 13 phone |
| Total final native cases | 66 tablet + 63 phone |
| Connected keyboard service | 3 Linux-input cases |
| Cleanup helper | 4 tests |
| Released r8 comparison | 2 baseline cases per layout; not final-app passes |

Final app SHA-256: `b39e157d34e09d379ecee001ee92be8ff5e9dfd54bd532d6db2d9415af4be287`
Final test APK SHA-256: `3f5256b052216a0ac2f27604d918282081c1e949e3a43d5f6cfc19e9db29f733`

[The manifest](inline-palette.json) records source, report, APK, screenshot, XML and raw-run hashes. The final core, workspace, Emoji and hardware runs use the same app/test pair. Focused development checks retain their actual earlier test-APK identities and are excluded from the total above. CI is inactive; `local/android` is a local validation/audit gate.

One owned API36 arm64 emulator is used sequentially, with tablet 1920×1200/240dpi and phone 1080×2340/420dpi viewports. These are two configurations of the same emulator, not two physical devices. Native tests run with the optional keyboard service off, app UID unprivileged and adbd UID2000. Only the guarded hardware helper temporarily roots adbd for Linux input; it never roots the application. Helpers verify installed bytes, restore private preferences/settings/geometry independently, release injected keys and unroot afterward. Private snapshots are excluded from published evidence and reviewer inputs.

## Coverage and scope

Core cases cover native Grid geometry, local/HTTPS raster images and rejected package escapes; filtering, sections, dropdown callbacks/storeValue, stable identity/reordering, explicit pagination, loading/empty states, current-item activation, composition/caret handling and held-key ownership. Real Palette Colors and OKLCH Store bundles are tested; per-package source commits and command hashes are recorded. Tailwind retains the existing expanded-file-limit rejection.

The shared Raycast-style footer and searchable attached menus are checked for context, icons, keycaps, token filtering, no-match behavior, exact clipboard payload, named shortcuts, focus restoration, menu ownership and clearance above the primary action. Same-window checks cover editors, aliases, delete/cancel, Store install/uninstall review, command return and extension preferences. Settings stays fullscreen. Backup, folder and shortcut regressions exercise the shared hosts and platform pickers.

Return checks require exact equal-viewport launcher/Grid row bounds. The preferences test verifies the actual retained WebView, unchanged launch epoch and JavaScript state 1→2; mutable render-tree identity is not a lifetime invariant. Editor geometry is sampled atomically. The dedicated real-Gboard case verifies Save is inside the current frame and above the native keyboard; it records the controlled false comparison of an old hidden-keyboard Save with a new resized frame. Neither screenshots nor those checks certify continuous animation or latency.

Controls retain the earlier 3px footer overlap, two equal-viewport scroll failures on the old candidate, and the old app's synchronous stale-feedback regression failure. Starting a valid backup import now clears obsolete export feedback before it can cover preview controls. The real-picker test waits for MainActivity window focus and its native Preview title before interacting, then scrolls the expanded content into view. Initial picker/scroll attempts and their captures remain excluded from passing evidence.

## Screenshot provenance

56 durable PNGs are stored under `docs/screenshots/parity-inline-palette/` and embedded in the PR with exact-head URLs. Each image records original capture bytes, SHA-256, dimensions, build and paired native XML when available. Two feedback-only captures and the historical footer-overlap PNG have no paired native XML; feedback bounds/text and the overlap geometry have separate test assertions and records. The other 53 captures retain paired native XML. r8 baselines and older overlap/obsolete-feedback controls are explicitly distinguished from final screenshots. Full originals are retained; chat may display a labelled zoom of a real capture. Some transparent-background captures show Android HTML Viewer reporting an unavailable earlier public test fixture behind the palette. Those images establish the foreground palette state, not successful file opening; the underlying viewer was not retouched.

Source/API, Markdown/metadata/complete Forms, icon/SVG, automatic pagination and arbitrary extension action-shortcut gaps remain under #6/#7. #59 synthetic-input truncation and the earlier caret timing observation remain unexplained. Physical/OEM/Lenovo, TalkBack/large-font, endurance and process-death certification remain unclaimed. OTA #55 follows this checkpoint.

## Retained development failures

- The old Grid popup prototype is not the final presentation or certification.
- A physical-focus negative records the extension shell focused instead of its menu. The corrected shell yields while the menu is open; its focused positive records the menu owning focus.
- A 35-case tablet attempt passed 30 and failed five. Four legacy Emoji helper failures tried to address rows absent from the new virtualized menu; the helper now scrolls the actual menu list. The other capture required an extension title no longer shown after header consolidation; the title is now contextual footer content.
- An action-footer exploration showed the rendered root capsule but its decorative semantics merged into the palette's no-op click target. The root now consumes blank-area taps without merging descendants. This is distinct from visible button failure.
- An Emoji action capture requested an offscreen third row while the IME left one visible row. Action-menu scrolling/search remain required; the capture now uses a visible marker. This is not evidence of a missing Unicode action.

- The earlier final-v2 build passed 36 tablet cases; phone passed 35/36. Its failed resize assertion required a smaller grid even on a tall phone where the 475dp palette fits above Gboard. The first record reports the same before/after grid height. A controlled hidden-IME/grid-focus rerun also retained the same height, with the selected cell complete. The corrected test requires real IME visibility, unchanged selection, full cell height and native footer clearance on both layouts; measured shrink remains mandatory on tablet. It does not claim a production timing fix.
- The action-footer control uses the previous app with the current assertion and records a 3px menu/primary overlap. The shared 52dp footer normalization is checked by the same bounds assertion in the corrected app. These are development/control results, not final suite totals.
- The first connected-menu hardware attempt stopped on a harness assertion that expected the obscured launcher EditText in menu-only accessibility XML. Its expanded cleanup key list also included Q before the C injector whitelist did, so that release batch was rejected. The injected Ctrl sequence contained matching UPs; preferences, clipboard, secure settings and unrooting were restored, but key-release cleanup was correctly marked failed. That run is excluded. The corrected harness validates the visible menu and checks the launcher query before opening and after closing it; Q is explicitly allowed by the owned-emulator injector.

Synthetic-input observation [#59](https://github.com/moikdev/droidcast/issues/59) reproduces truncation on released r8 and intermittently on the candidate; an unchanged candidate comparison completed the exact string. Its cause remains open. Existing header caret timing, physical/OEM/Lenovo, TalkBack/large-font, latency/continuous animation, endurance, full Markdown/metadata/Forms, broad Store compatibility and process-death persistence remain uncertified. CI is inactive; local/android is a local validation/audit gate.

- Return-position controls initially failed both launcher and Grid. Grid history now lives with its retained runtime state; launcher history lives above route replacement. Detached zero-height observations are ignored. The launcher fixture also changed to close the editor IME before Back: its earlier failure included a genuine smaller viewport, which intentionally reveals the selection. Exact equal-viewport row bounds and independent keyboard-shrink recovery are tested separately; the fixture refinement is not a timing fix.
- The initial editor IME fixture touched a text cursor and created an Android framework cursor-handle popup. It now requests editor focus through native semantics, verifies real Gboard and Save clearance, and retains the no-app-popup assertion. Earlier captures labelled editor-keyboard without visible Gboard are not keyboard evidence.

- The first workspace tablet attempt passed 25/26, failing the inspection visibility assertion. A scroll-only test refinement then failed because the contents did not appear. Controlled traces show the document picker was still closing when the test tapped; after return, stale “Backup exported” feedback also covered Inspect. The test now waits for actual MainActivity window focus and the native Preview title, then scrolls the contents. Production clears obsolete feedback synchronously when a valid new import starts. The new regression fails on the earlier candidate; both it and the real-picker round trip pass on the correction. The complete final pipeline passed on the rebuilt app/test pair; earlier core passes remain historical.
- Historical pre-scroll-refinement core checks were 35/36 tablet (Tailwind download reported Connection reset), a focused unchanged Tailwind retry passed, and phone passed 36/36. These records remain separate from the final app core runs, which pass 26/26 per layout. No transport cause or fix is claimed.

- The v6 tablet core attempt passed 25/26. Its preferences test compared mutable rendered JSON objects by identity: the saved observation changes from the initial empty query/image-0 snapshot to the requested Landscape/image-1 snapshot. That does not establish an engine restart. The test now checks the actual retained WebView, unchanged launch epoch and JavaScript activation state (1→2) across Cancel; focused tablet/phone checks pass. No production change was made for this test correction. The final suites pass with the corrected assertion.

- The v7 tablet core attempt passed 25/26; a legacy editor assertion read Save bounds and frame bounds in separate UI turns during uncontrolled IME state. The failed run did not retain those bounds, so its cause remains unestablished. Both rectangles are now measured atomically on the UI thread, with no tolerance change. The controlled real-IME case records hidden Save/frame bottoms 941/950px and shown bottoms 621/630px: comparing the old Save with the new frame would fail despite both same-frame layouts being valid. Focused tablet/phone runs pass both editor cases. The obsolete keyboard-labelled capture is removed; the dedicated case still requires actual Gboard and Save clearance. This is a measurement correction, not a production timing fix.

- The v8 workspace tablet native run passed all 27 cases, then evidence collection rejected its existing `origins.tsv`. The original incomplete record and successful cleanup remain retained. The collector now permits the bounded TSV filename and records native completion before artifact transfer. A separate read-only recovery verifies the same app/test identities, native log and unchanged previously collected bytes, and collects the remaining evidence without rerunning or modifying the app. Phone/following checks use the corrected collector.
