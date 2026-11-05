# Touch control for search keyboard visibility

[#114](https://github.com/moikdev/droidcast/issues/114) adds Hide/Show behavior to the existing wide-screen launcher and Emoji keyboard button. The 48 dp slot, editor width and search origin remain fixed. The icon uses the existing keyboard/keyboard-hide glyphs at 20 dp, and the label describes the action for the actual IME state. Requested font scales are preserved.

Actual pointer Hide reveals the selected launcher result or Emoji cell; pointer Show returns to editing with the exact query, selection range, category/filter, selected result, route and focus intact. Selected launcher text retains 14 sp. This covers the selected target; long rows, the whole grid and every footer line are not certified. The enlarged Emoji footer can still require scrolling after Hide. Narrow phone layouts retain their existing controls.

## Sources and binary boundaries

The executed app is the exact archived `f30bebd6c414307a5b5fe690d80dfffe5fc58ccc` binary:

- App SHA-256: `98da1466e9fe6e3a5e1fbd5b27251e11ebb88e1a6c96599548c20bc4f7aa5de3`.
- Final test SHA-256: `dc22049a5a03de863a783228cff53072e01484b1ccc3eed12222e9e832ccdf3d`, fixture/controller source `69bd578d528c42296ef4a10fa24d675a8f635408`.

[The final identity](validation/keyboard-toggle/identities/final-harness/identity.json) binds all 255 current Android/build/Python inputs. After the app source, only `TouchSearchDeviceTest.kt` and its CLI allowlist change. All production/build and JVM-test inputs remain identical. The fixture-only builds explicitly retain the app's `f30bebd6` build-commit property; their app output also equals the executed APK byte-for-byte. This is a cached-build byte comparison, without clean-runner reproducibility credit. The app manifest embeds its app source; test APKs have no independent source stamp. Compilation receipts, input vectors and APK hashes bind each instrumentation version. Later documentation commits do not change those inputs.

| Cohort role | Source | App / test hash prefix |
| --- | --- | --- |
| Reviewed pre-toggle app | `6c5825bf` | `66a6965e` / matched harness per receipt |
| First corrected baseline harness | `dfd1b38c` | old app / `ba781590` |
| Explicit initial pointer-Show baseline | `723ecda1` | old app / `b08f6a53` |
| Pointer toggle with default semantics proxy | `1355f84c` | `32e2726d` / `931201bf` |
| Owned accessibility action | `411b7f73` | `1291d061` / `f4925b8c` |
| Pointer-backdrop driver, frozen executed app | `f30bebd6` | `98da1466` / `0b855d72` |
| Final state-label/API/Back fixture | `69bd578d` | exact `98da1466` / `dc22049a` |

The identities preserve full hashes, source vectors, manifest stamps and actual ZIP payload differences. Candidate app versions differ in their manifests and two DEX entries; no stamp-only reconstruction is inferred. The final test rebuild changes only `classes3.dex` relative to the `f30bebd6` test APK. All 11 original packaged assets/notices remain unchanged, including upstream runtime assets.

## Visibility and action ownership

The button validates its search owner, current epoch/route, foreground window, obstruction state and actual native IME visibility before requesting a state. A visibility-mode change or disposal invalidates the captured action, including a visible→hidden→visible cycle. Hide preserves editor focus; Show requests search focus. Neither path replaces the text value.

The first implementation's default accessibility callback remained a live proxy to the clickable node's current handler. The pinned [Foundation 1.7.6 source](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.7.6/foundation-1.7.6-sources.jar) and cached AAR bytecode both establish that lookup. A retained semantics reference therefore reached the new current Hide action after the mode cycle. This does not show invocation of the original application closure. [The source/binary inspection record](validation/keyboard-toggle/semantics-proxy-inspection.json) retains dependency hashes and the distinction.

The repaired button publishes an explicit owned accessibility action using [Compose's semantics API](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.7.6/ui-1.7.6-sources.jar), with Button role and the same guarded activation function as pointer clicks. It returns false on rejection. Current-action positive controls separately require acceptance, actual Show/Hide/Show transitions on both routes, unchanged query/range/focus and matching labels. These are controlled native semantics invocations, without TalkBack gesture certification.

## Current verification

[The summary](validation/keyboard-toggle/summary.json) credits **24 final positive executions / 11 whole cohorts / 176 successful restoration checks** on the exact final app/test pair.

- Owned `Tinycast_Tablet_API_36`, API 36: landscape 2340×1080 / 420 dpi and tablet 1920×1200 / 240 dpi, each at 100% and 200% font. Both routes exercise explicit initial pointer Show, selected-range Hide/Show, empty-query Hide/Show, complete nonoverlapping 48 dp controls and unchanged search geometry. Hide exposes a complete selected target; Settings text has complete natural, non-ellipsized 14 sp lines. No launch or clipboard-copy activation is inferred from geometry.
- Ten controlled retained callbacks reject obsolete modes, epoch changes before disposal, obstructing menus, replacement routes and recreation. Menu opening is labeled semantics setup; dismissal uses an actual pointer at a recorded backdrop point outside the menu. The source and expected query assertions remain mandatory.
- Current published accessibility actions return true and change the actual IME state in six observed transitions across launcher/Emoji, while preserving Button role, text, range and focus.
- Retained empty-feedback cases run both landscape font scales, including complete natural lines and footer reachability. Their target lookup now expects Hide while the IME is shown, retaining all line/font/48 dp assertions.
- Enlarged narrow-phone Clear cases, normal-tablet composition/live-query/stale-owner guards, and two owned-input contracts on both modern phone and `Droidcast_WebView_API26_27caf3` remain separate whole cohorts. The API 26 device retains physical 1080×1920 / 420 dpi geometry. Its silent test IME gives controlled native-input coverage, not legacy WebView extension parity.

The **6 intended controls / zero passes / 49 restoration checks** use the same final instrumentation as the repaired runs. The pre-toggle app opens the IME, then fails to hide it after tapping its existing Show-only button at both font scales. The intermediate proxy app passes its three initial pointer transitions, then changes visibility when the retained published-mode callback is invoked. [Calibration](validation/keyboard-toggle/final-controls-calibration.json) requires the exact assertions, case events and actual frame IME states. These positive-only runner receipts stay failed. Their PNGs show ordinary app states, without app error banners.

Editor replacement, range setup, initial IME requests and retained callback invocation are controlled. Pointer credit covers the recorded button/backdrop actions. Capture XML precedes PNG after Compose/UiAutomator idle and cache clearing; XML is diagnostic rather than independent pixel visibility proof. Full target assertions compare complete versus clipped bounds. Natural text checks examine each line, not merely node existence.

## Separate system-Back failure

[#115](https://github.com/moikdev/droidcast/issues/115) remains open. An earlier Emoji callback run rejected the obsolete-mode, epoch and menu-obstructed Show actions, then lost the `face` query after `UiDevice.pressBack()` closed its category menu. The complete two-case cohort stays failed 1/2. Its before-menu frame and failure semantics are retained; it has no separate post-Back image.

A final isolated probe, without header keyboard activation, reproduces query loss on **both the archived pre-toggle app and the current app**, with the same final test APK. Both runner receipts fail 0/1, with 30 successful restoration checks total. Before/menu/after PNG/XML/frame sets show the transition. This establishes the captured historical/current pattern, without a delivery cause or repair.

In both records, the raw field `injection_accepted` stores only the false return from `UiDevice.pressBack()`. That field name must not be interpreted as proof that no Back input was delivered. Actual DOWN/UP and dispatcher tracing remain future work. The passing pointer-backdrop protocol earns no system-Back cause or repair credit.

## Retained failures and accounting

All observations reconcile to **64 executions / 37 raw passes / 27 failures / 515 successful cohort restoration checks**, including the separate Back diagnostic. Historical/intermediate passes earn no final-pair credit.

- The first harness compile failed for a missing experimental-selection API opt-in; it executed no tests. The corrected baseline's 100% cohort has two intended Hide failures. Its 200% cohort retains one launcher Hide failure and an Emoji initial-Show timeout before the Hide assertion. That registry stays incomplete and earns no complete 200% control credit.
- The stronger initial-pointer-Show harness records four intended Hide failures at 100%/200%. Its successful initial Show observations establish that protocol; they explain no cause or production repair for the older timeout.
- The first candidate passes two critical pointer cases, then fails both retained-proxy cases. The owned-action candidate passes launcher but fails Emoji at the separate Back path. Their later planned work was not run.
- The `f30bebd6` driver passes four pointer profiles and both callback cases, with its intended controls. Its next feedback cohort fails because the selector still requires the old constant Show label while native IME visibility and the actual Hide label agree. Primary-line/compact-status assertions had passed. The final fixture corrects only that expected state label and adds the supported accessibility positive plus isolated Back probe.

Original registry completion flags, failed logs, distinct harnesses and exact sources remain unchanged. No rerun alone is credited as an app repair.

## Checks, preservation and limits

The app-source builds pass, and retained JVM XML records 343 cases with no failures/errors/skips; its task executed at `f30bebd6`. Final fixture debug/test and benchmark/test builds and lint pass. Fresh lint records zero errors / 56 warnings per variant. Fresh Python 135 cases per normal/optimized mode pass at `69bd578d`. JVM reuse is justified by exact unchanged production/build/JVM-test inputs; the fixture-only build does not rerun those JVM cases. Actual exact-head hosted Android CI and both independent sol/high audits remain separate merge gates.

All **14 final read-only preservation checks** pass, including both original modern APKs, all 573 original app/evidence files, legacy package absence, geometry/raw font settings and both devices' current four-field IME baselines. The collector retains its owner through restoration/verification and releases last. Private archive transport, original data/settings and fingerprints stay private. This certifies the recorded baseline equality, without whole-system/cross-boot or earlier pre-probe IME-history identity certification.

[The checkpoint](validation/keyboard-toggle/checkpoint.json) binds 536 indexed public files plus itself, 1150 retained capture hashes and **89 selected real PNG/XML/frame sets**. Exact byte transforms and allowlisted projections are recorded. Raw hierarchy CRLF is preserved; all published XML parses with escaped host-path placeholders. Private originals/owners and signing material are excluded.

Full long-row/grid/footer typography, every system-IME transition, physical typing/queued gestures, actual split-window/OEM/TalkBack, Files-toolbar variants, whole-Store/Raycast parity, #69/#71/#89, #115 and production signing/migration remain open or unverified. No project association/rename/migration occurs.
