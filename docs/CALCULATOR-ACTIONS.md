# Calculator Actions

This slice of [#67](https://github.com/moikdev/droidcast/issues/67) adds two actions to the calculator's existing attached Actions panel. The floating palette, Copy Answer, menu search and native IME submission remain in use.

| Action | Result | Default accelerator |
| --- | --- | --- |
| Copy Answer | Copies the selected formatted answer, such as `36`. | Enter |
| Put Answer in Search Bar | Replaces the launcher query with `= 36`, focuses Search and places the caret after the answer. | Ctrl+Enter |
| Copy Calculation | Copies the displayed expression and selected answer, such as `12 × 3 = 36`. | Ctrl+Shift+Enter |

Both Enter and numeric-keypad Enter support the new accelerators. Existing configured shortcuts retain precedence at the launcher; a saved Ctrl+Shift+Enter binding is still valid. Within Actions, the named action owns its accelerator even if menu search highlights another row. A disabled chaining accelerator cannot fall through to Copy Answer. No cross-app paste-back is implemented.

## Formatting and chaining

The pinned Tinycast [calculator coordinator](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/Tinycast/Features/Calculator/UI/CalculatorCoordinator.swift) copies `expression = answer` and rewrites Search with the answer for chaining. Its [result model](https://github.com/abue-ammar/tinycast/blob/37e6043f3f550febb64bdb3cff3ac29a30caad5a/Tinycast/Features/Calculator/Model/CalcEngine.swift) excludes answers that do not retain their meaning when entered again. Android keeps its deliberate calculator prefix when rewriting Search, so ordinary number searches remain available.

Copy Calculation uses the selected command's existing displayed expression and payload. It removes the leading explicit `=` mode marker and surrounding whitespace, then joins the expression and answer with ` = `. Multiplication retains the displayed `×`: `= sqrt(81)+12x3` copies `sqrt(81)+12 × 3 = 45`. Conversion expressions retain their units; `1 m to in` copies `1 m to in = 39.3700787402 in`. This action does not silently reevaluate or add precision to the selected value.

Chaining also retains units. A supported explicit quantity such as `= 100 cm` evaluates to `100 cm`; appending ` to m` converts it to `1 m`. Bare `100 cm` remains a search. This narrow quantity-literal support does not implement general unit arithmetic. The existing on-device Double arithmetic, US decimal formatting, ten fractional digits and 256-character expression limit remain. Reusing a rounded answer can accumulate rounding: the displayed inch answer above converts back to `100.0000000001 cm`.

Put Answer is enabled only when the formatted answer can be read back unchanged by the supported parser. For example, the 309-digit answer to `10^308` remains copyable, while chaining is disabled because it exceeds the input limit. Calculator history/pinning, currencies, broader date/time calculation, locale preferences and Raycast's cross-app paste workflows remain separate parity gaps.

## Input ownership

Every calculation shares the ID `result`. Calculator Actions therefore bind to the opening's exact query, category, selection, command value, route and focus epoch. Query/category/selection revisions also reject away-and-back transitions before a Compose frame. A stale choice is rejected before it can dismiss another route. Window focus, recording and busy-state guards remain active.

Root accelerators block actions during IME composition. The Actions search retains its separate composition guard and native IME action. Held repeats and releases remain consumed after a successful accelerator; an unmatched modified Enter repeat cannot become a plain Enter copy. Pointer activation is an explicit choice of the displayed action.

Chaining schedules keyboard activation after the Actions input session has been disposed. The request rechecks the exact calculation, route, menu ownership, focus, recording and busy state before focusing Search, waits for a frame, and rechecks before showing the keyboard. This addresses the observed short-landscape case where the query and caret changed correctly but the keyboard stayed hidden after the menu keyboard toggle. The unchanged common instrumentation is retained on both the failed earlier runtime and repaired runtime; its raw frame stamp is the compile target, not the installed app's identity.

Local validation is complete. Exact-head hosted CI, both independent audits and merge status are recorded on the implementation PR as separate gates. The original failed probe and all historical APK/test identities are retained separately; they do not earn final-runtime pass credit. Physical/OEM keyboards, TalkBack, whole-Store parity and production signing/migration remain unverified.

## Reproducing the owned calculator cohort

Build debug application and instrumentation APKs, then use the existing initialized, idle `Tinycast_Tablet_API_36` emulator. With `ANDROID_HOME` set, the guarded runner can execute Calculator Actions without an extension archive:

```sh
python3 scripts/test-reviewed-text-input.py \
  --serial emulator-5580 --avd Tinycast_Tablet_API_36 --layout phone \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --classes dev.tinycast.android.CalculatorActionsDeviceTest --expected 14 \
  --output artifacts/calculator-actions-owned
```

Use a fresh output directory and retain any recovery ownership after failure. The runner restores and independently verifies the original clipboard and preferences; private recovery snapshots stay outside published evidence. Extension and shared regression cohorts still require their frozen formatter archive. Short viewports use existing palette scrolling and, when needed, the action-search keyboard toggle; these paths are verified separately from the production calculator changes.

The clipboard evidence helper uses a bounded walk of SystemUI's non-application/non-IME windows. API 33+ [node prefetching](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#FLAG_PREFETCH_DESCENDANTS_HYBRID) reduces repeated remote ID searches. The walk checks the root and descendants, limits traversal to 256 nodes and 16 roots, and clicks only an exact SystemUI dismiss/minimized ID that is visible, enabled and clickable. Its five-second polling budget, service-mode/flag restoration and confirming absence checks remain. Synchronous accessibility calls can exceed that outer budget; their durations are recorded. Known clipboard-producing calculator fixtures additionally require a successful node-action receipt. A separate cold case and minimized expand/dismiss fixture test those boundaries without crediting disappearance alone.

## Evidence identity and limits

The production runtime is frozen at `b99a65e`; the final Calculator/shared-regression instrumentation sources are `54ab2e6`. The helper's separate `43e46ac` instrumentation differs from that final APK only in `classes3.dex`, following a Calculator-only diagnostic guard change. Helper, Activation and production sources are byte-identical between those two harness revisions. The common keyboard control uses instrumentation from `5b68984`; its sources and binary remain distinct from the final harness. Full hashes, source manifests and cohort bindings belong to the validation checkpoint.

The owned runner restores the original clipboard independently in the test-APK UID, verifies preferences and display/accessibility settings, and releases its nonce lease after verification. Regression wrappers additionally protect pre-existing evidence and verify durable data without publishing private contents or fingerprints. Calculator failures capture UI only after the owned clipboard and controlled query are established. The earlier pre-query SystemUI failure has no published app capture.

The first host run had an incorrect expectation that a rounded unit answer would convert back to exactly `100 cm`. Its actual result was `100.0000000001 cm`; correcting the test expectation earns no arithmetic-precision repair claim. Earlier pointer/IME fixture failures, the redundant final clipboard cleanup failure, and the old helper's remote-query deadline failure remain failed. Later passing runs do not relabel those cohorts. Supplementary filtered-menu screenshots use editor test actions to reveal each complete label; ordinary pointer reachability is checked separately by the main and font cohorts.

## Completed local validation

All native observations use the unprivileged app on the dedicated API 36 `Tinycast_Tablet_API_36` emulator. Tablet uses its physical 1920×1200 / 240 dpi profile. Phone and short landscape are 1080×2340 and 2340×1080 overrides at 420 dpi. These are viewport profiles on one emulator, not physical phone or OEM coverage.

| Cohort | Result | Identity/scope |
| --- | --- | --- |
| Main Calculator suite, 100% text | 13/13 on tablet, phone and landscape | Final `54ab2e6` instrumentation; 39 executions |
| Critical touch/copy/unit/disabled cases | 4/4 on tablet/phone/landscape at 200%, plus phone at 130% and 150% | Final instrumentation; 20 executions |
| Complete-label rendering supplement | 1/1 on each layout at 200% | Three filtered-menu rendering executions; distinct from ordinary pointer navigation |
| Common keyboard instrumentation | 13/13 on repaired runtime | Same `d14d8a…dd2` test APK as the older 12/13 keyboard-failure cohort; separate from the final harness |
| Matched missing-action controls | Two intended failures on each old-app layout, zero passes | Old `9b69c2…da8f` app; same final instrumentation as the repaired positives |
| Matched held-repeat control | One intended failure, zero passes | Intermediate `6a3b38…eabf` app; unchanged final test APK |
| Shared palette regressions | 16/16 on all three layouts | 48 executions covering menus, composition, focus, editors, aliases, return and rapid live selection |
| Clipboard helper/launcher checks | Cold 1/1 and full 7/7 on both tablet and phone | 16 executions on separate `43e46ac` instrumentation; actual actions, entry-flag restoration and minimized expand→dismiss |

The completed receipts total **139 positive executions**: 75 Calculator, 48 shared regression and 16 helper/launcher. Five intended negative executions remain separate. All **431 cohort cleanup checks** pass across 22 whole cohorts. The wrappers verify the same-boot durable data and all 528 pre-existing evidence files; private originals and their fingerprints are excluded from publication.

Host validation records **278 fresh JVM passes**, successful debug/test and benchmark/test builds, and fresh lint with **0 errors / 55 warnings per variant**. The final harness reuses the JVM results from `b99a65e`; production and JVM sources are byte-identical. Python checks record **97 normal and 97 optimized passes** at `f096d55`, with all 35 Python sources unchanged at the final head. Benchmark device execution and new Node execution are unclaimed. All ten pre-existing packaged assets remain byte-identical to the baseline APK.

The [checkpoint](validation/calculator-actions/checkpoint.json) indexes 639 public files, with 52 raw screenshot/XML/frame sets, 86 parseable XML files and 917 retained owned Calculator capture hashes. All current 204 source/runner hashes, frozen APK hashes, declared redactions, allowlisted receipt projections and raw XML CRLF bytes are independently checked before PR creation. The [identity](validation/calculator-actions/identity.json), [cohorts](validation/calculator-actions/cohorts.json), [supplements](validation/calculator-actions/supplement.json), [history](validation/calculator-actions/historical.json) and [frame index](validation/calculator-actions/frames.json) retain the separate binary/source bindings.

The old helper's later eligible node does not prove a click was rejected; its failed cohort records no attempted action. The replacement walk has successful cold and mode/minimized receipts, without claiming the visibility flag alone caused the earlier failure. The excluded pre-query failure has no public app image. The 309-digit result remains visually abbreviated by existing result presentation; complete-label claims concern the action rows. Very short landscape with the keyboard open can require existing caller scrolling and explicit keyboard hide to reveal content.

## Screenshot guide

All images are native Android captures; the complete 52-frame inventory is linked above. Baseline menu images use app `9b69c2…da8f` (build stamp `bdbfc56`); current images use `31ad1c…0f4b` (build stamp `b99a65e`).

| Evidence | Capture |
| --- | --- |
| Phone before / after, 100% | [One action](screenshots/calculator-actions/final-old-phone-v12/touch-chain-actions.png) / [three actions](screenshots/calculator-actions/final-phone100-v12/touch-chain-actions.png) |
| Tablet before / after, 100% | [One action](screenshots/calculator-actions/final-old-tablet-v12/touch-chain-actions.png) / [three actions](screenshots/calculator-actions/final-tablet100-v12/touch-chain-actions.png) |
| Chained Search and next result | [Focused `= 36`](screenshots/calculator-actions/final-phone100-v12/touch-chain-chained-search.png) / [next result copied](screenshots/calculator-actions/final-phone100-v12/touch-chain-next-calculation.png) |
| Full expression and units | [Function/× copy](screenshots/calculator-actions/final-phone100-v12/full-copy-full-copy-destination.png) / [unit conversion copy](screenshots/calculator-actions/final-phone100-v12/unit-chain-unit-converted.png) |
| 200% complete rows | [Phone Put Answer](screenshots/calculator-actions/visual-phone200-v12/revealed-labels-revealed-put-answer.png) / [landscape Copy Calculation](screenshots/calculator-actions/visual-landscape200-v12/revealed-labels-revealed-copy-calculation.png); filtered editor setup, IME hidden |
| Disabled chaining | [Phone 200%](screenshots/calculator-actions/final-phone200-v12/disabled-chain-disabled-chain.png); input-limit refusal with no Copy Answer fallback |
| Matched keyboard transition | [Old failure](screenshots/calculator-actions/historical-keyboard-failure/touch-chain-failure.png), app `652d93…6109` / [repaired return](screenshots/calculator-actions/ime-common-landscape100-v9/touch-chain-chained-search.png), app `31ad1c…0f4b`; identical test APK compiled against `4eb32eb` |
