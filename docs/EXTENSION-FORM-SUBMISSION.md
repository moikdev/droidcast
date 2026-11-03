# Validated native Form submission

[Issue #84](https://github.com/moikdev/droidcast/issues/84) adds error-aware submission to Android's existing five-field Form subset. A supplied nonblank error disables the actual SubmitForm action and explains the blocked state. Editing can clear the error; the same caller can then submit its exact accepted values once. Ordinary Actions remain usable. This follows the separate [readability/focus work](EXTENSION-FORMS.md) merged in PR #85.

Raycast's [Form validation contract](https://developers.raycast.com/api-reference/user-interface/form#validation) prevents the SubmitForm callback while errors remain. Its [guidance](https://developers.raycast.com/information/best-practices#use-forms-validation) uses blur for validation and change for clearing an error. The preceding generated runtime projected SubmitForm into a generic Action and retained Form values, without an error gate. Real old-app controls below establish the resulting Android behavior.

## Submission boundary

The Android adapter preserves the identity of `Action.SubmitForm`, its legacy alias and the default API export. Native submission resolves the current root/action/session, delivers the focused field's current-value blur, clears native focus and checks ownership again. The JavaScript adapter requests a fresh React publication and checks the active screen, action identity, descriptor and supported field errors after the commit. It uses the latest accepted callback rather than an edit-time captured value. Pending requests for one action coalesce; Promise results and callback failures retain the generated dispatcher's handling. Generated upstream runtime assets and AGPL/React notices remain unchanged.

The gate covers nonblank string errors on TextField, PasswordField, TextArea, Checkbox and Dropdown in the current Form. It observes extension-supplied validation; it does not invent required-field rules or wait for arbitrary network validation to finish. Native argument/preference required-field checks remain separate. Other Actions are not reinterpreted as SubmitForm.

The primary action and filtered Actions-menu option are disabled with current errors. The footer explains that highlighted fields need correction; the option retains its complete reason at enlarged fonts. Valid edits, controlled transformations/rejections, uncontrolled values and ordinary actions retain their behavior. Enter during controlled input composition does not submit.

## Frozen sources and binaries

All observations use `Tinycast_Tablet_API_36`, Android 16/API 36, arm64-v8a and Google WebView 133.0.6943.137. Profiles are tablet 1920×1200/240 dpi, portrait phone 1080×2340/420 dpi and short landscape 2340×1080/420 dpi. These are profiles on one emulator, not three devices or actual split-window coverage. App and shell remain unprivileged.

| Role | Frozen source | SHA-256 |
| --- | --- | --- |
| Preceding app | `952fb168b482762791afb541bf2bd6b302842b9e` | `3424f3abc6ba7606e64a13f375f20b16303044ab532ee20a36fa7502ff6b5c15` |
| Repaired app | `a0fca0e9118621453a40f73204f83a9a9274b446` | `61667f7ab952b8c6ef9ee309bf0c19b4f402dfc715fc01dc262cdebfbeadfcb7` |
| Original common instrumentation | `1318591b49780009a9c38ee59cc8737fec1478e7` | `7e341e7ccc3cb459ada9e523ed5ace829cd636c64832d28f00b94c6c2221dbbc` |
| Core acceptance instrumentation | `54e9038fedde8d8876794a08bad70098e773cc53` | `3add76ffbcd71144697bd8b603ace1545635d8685b04037b06f4d12ad18a3fd7` |
| Root/normal instrumentation | `ca788c5ece328a069e1b9bab2a8755eb3209a6b6` | `5d775470452b9470175dbfa29a8479d4107adbbc56444e964c822c76e5f57233` |
| Final selection instrumentation | `b54243f53e0f383c102eb42d10e4343c10daf33d` | `58cc25d51a6ad94097a8734c3c0d5a74af90a642e6d8376734a100a739d90c1f` |

The `ca788c5` supplement adds only the missing-root callback case to the core harness. Removing that method exactly reconstructs the core source. The later `b54243f` harness separates exact programmatic selection with Gboard hidden from actual long-press selection with the live IME visible; all other test, fixture and helper bodies remain unchanged. Each step changes only `classes3.dex` in its instrumentation APK. The [root supplement](validation/extension-compatibility/form-submission/checks/harness-supplement-v1.json) and [selection boundary](validation/extension-compatibility/form-submission/checks/selection-harness-boundary-v1.json) preserve the exact source/APK differences. App code is unchanged by either harness step.

Final debug builds exactly reproduce the frozen app and final selection test APK. Benchmark binaries have separate build/lint identities and no device-run credit. The 205 JVM outcomes retain unchanged production/JVM sources; later final builds may reuse those Gradle results.

## Verification

| Check | Observed result |
| --- | --- |
| Original common primary/menu controls | Four intended old-app failures across tablet/phone, then four repaired-app passes with byte-identical instrumentation |
| Additional controls | Seven intended old-app failures: blur, aliases, current JS error and three native queued-model transitions; repaired core cases pass with the same instrumentation |
| Core acceptance | 22/22 on each normal-size profile: 66 executions |
| Separate missing-root supplement | 1/1 on each profile: three executions, final harness |
| Critical 200% validation/reason text | 1/1 per profile with actual IME hidden/shown/hidden, pointer scrolling and full-line witnesses |
| Existing Form regression suite | 24/24 per profile: 72 executions, root/normal harness `ca788c5` |
| Enlarged-font Form/JSON/valid-submit regressions | Six workflows per profile at 200%: 18 executions, final selection harness `b54243f` |
| Affected menu/keyboard and reviewed-input/lifecycle regressions | 19 + 21 cases on both tablet and phone: 80 executions, final harness |
| Final selection supplement | Nine executions: five controlled range/composition cases and four actual live-IME pointer cases, final selection harness |
| Original common JSON successes | Two separate real frozen-bundle executions on tablet/phone |
| Credited repaired-app executions | 257; earlier failed/ambiguous groups are excluded |
| JVM | 205 passing cases, zero skips |
| Local adapter stub contracts | Seven focus + 13 submission Node cases; distinct from actual React/device workflows |
| Python | 63 normal and 63 optimized cases |
| Build/lint | Debug and benchmark app/test builds; zero errors and 51 warnings per variant |
| Restoration | 540 passing checks in credited groups; five separate pre-existing-evidence preservation checks |
| Hosted CI and audits | Actual exact-head hosted Android result and two independent sol/high audits remain separate PR merge gates |

The [native summary](validation/extension-compatibility/form-submission/checks/current-native-summary-v1.json) lists credited cohorts and their individual app/test identities. The [checkpoint](validation/extension-compatibility/form-submission/checkpoint.json) binds 178 current source hashes, 538 checkpoint-listed public files, 2358 retained owned capture hashes and all 33 raw PNG/XML/frame sets. Published report XML uses escaped placeholders; raw native XML remains byte-exact, including CRLF.

The queued native-model fixtures invoke the captured callback in the same UI turn before Compose can update it, then restore the original model before a frame. They record zero dispatches on the repaired app. Their screenshots show the restored UI, not a visibly replaced/removed model. Separate real navigation reaches a successor Form; a controlled old JavaScript handler request invokes neither the old nor successor callback. Duplicate requests are two requests in one JavaScript stack, not physical double-tap timing certification.

The 200% probes require natural height, no ellipsized message lines, and each full line to become visible through pointer scrolling. A short viewport need not show all lines simultaneously. Native hit probes require useful visible target bounds. Programmatic selection and composition use controlled native actions/InputConnection with Gboard hidden. Separate actual long-press checks retain the selected owned word and exact range before/after unrelated React publication with the IME visible at 100% and 200% on tablet/phone. These do not certify live OEM phonetic typing.

Guarded runners hold ownership through fixture cleanup, independent durable clipboard restoration, preferences, font, geometry and final verification. Existing extensions/data are preserved. The shared-regression stage privately archives 136 pre-existing evidence files, restores their exact bytes and releases its owner only after verification. Original clipboard snapshots, unrelated evidence and their identities remain private.

## Raw Android captures

The [capture manifest](validation/extension-compatibility/form-submission/captures.json) supplies a caption and exact binary identity for every raw set. These are actual runtime/UI observations, not mockups.

| Preceding invalid primary destination, phone | Repaired blocked primary, same common harness |
| --- | --- |
| ![Old primary submits values despite a current field error](screenshots/extension-compatibility/form-submission/before-primary-invalid-result-phone.png) | ![Current Form retains its error and disables Submit with an explanation](screenshots/extension-compatibility/form-submission/after-primary-blocked-phone.png) |

| Preceding Actions menu | Repaired Actions menu |
| --- | --- |
| ![Old menu offers Submit while a supplied error remains](screenshots/extension-compatibility/form-submission/before-menu-error-phone.png) | ![Repaired filtered option is disabled and explains why](screenshots/extension-compatibility/form-submission/after-menu-blocked-phone.png) |

| Error created by Submit blur | Corrected exact Unicode destination |
| --- | --- |
| ![Current-value blur validates the empty field without submitting](screenshots/extension-compatibility/form-submission/blur-after-error-phone.png) | ![Valid edited Form invokes its owned callback once with exact values](screenshots/extension-compatibility/form-submission/valid-exact-result-phone.png) |

| 200% phone, actual IME visible | 200% short landscape, disabled menu reason | 200% tablet, later text lines |
| --- | --- | --- |
| ![Later help lines reached by pointer scrolling](screenshots/extension-compatibility/form-submission/font200-phone-ime-bottom.png) | ![Complete disabled action reason reached by scrolling](screenshots/extension-compatibility/form-submission/font200-landscape-blocked-option.png) | ![Later natural text lines with the IME visible](screenshots/extension-compatibility/form-submission/font200-tablet-ime-bottom.png) |

| Actual long-press before unrelated publication, 200% tablet | Same selected word after publication, live IME visible |
| --- | --- |
| ![The owned word is selected by a real long-press gesture](screenshots/extension-compatibility/form-submission/pointer-selection-tablet200-before.png) | ![The exact selection survives unrelated React publication](screenshots/extension-compatibility/form-submission/pointer-selection-tablet200-after.png) |

| Frozen JSON output | Frozen JSON caller return |
| --- | --- |
| ![Actual frozen JSON result and explicit Copy action](screenshots/extension-compatibility/form-submission/json-detail-phone.png) | ![Original launcher query after returning through the Form](screenshots/extension-compatibility/form-submission/json-return-phone.png) |

The frozen JSON workflow keeps archive `fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58` and command `bcca05348f11ffc115adc346944dcd78d209958684cb9df44d571e70a99575a0`. It edits native preferences, submits exact multilingual JSON, verifies the formatted Detail, explicitly copies, restores Form input and returns to the original launcher query. It certifies this named workflow, not the Store inventory.

## Retained failures and limits

After the 72 normal Form passes, the first six-workflow 200% tablet run finished **5/6**. The synthetic selection assertion observed `19..19` instead of `2..5` while Gboard was live. Unchanged focused comparisons pass on both the current and preceding apps, so neither an introduced production defect nor the original cause is established. That failure and both diagnostic passes remain recorded without app-fix credit.

The final controlled protocol still requires the exact `2..5` range **before and after** unrelated publication, with Gboard hidden for programmatic selection and the following synthetic composition. A separate real long-press protocol checks the selected word/range while the live IME stays visible. It passes on both current and preceding tablet apps; those baseline/diagnostic observations earn no regression credit. Final enlarged-font workflows and additional normal/gesture runs use the new harness and retain its own identity. No production-code change is credited for this test-protocol correction.

The first expanded harness (`4178289`, test APK `3dc2f944…18ee`) recorded phone 18/21 and stopped. Its saved semantics callback could be updated by Compose after recomposition, so the title/root failures did not isolate the intended old caller. The long-text failure selected two Compose roots after an editor popup appeared. The corrected harness uses pre-frame native transitions and an exact main-root matcher. No app-code change or timeout increase earns credit for these harness corrections. All original logs, binary identities and restoration outcomes remain published; later unstarted groups are not counted. The earlier seven-negative run includes three ambiguous queued-native probes and is not credited as their regression control; the later corrected seven-control run provides that evidence.

An initial combined Gradle command omitted the required benchmark test-build-type property and failed before task execution. The corrected serial debug/benchmark commands succeeded; the failed invocation remains recorded. The initial/unused harness builds without device runs receive no native credit.

DatePicker/TagPicker/FilePicker, drafts, full imperative focus, arbitrary asynchronous/provider-backed validation, whole-Store compatibility, physical/OEM devices, actual split-window, full accessibility and production signing/migration remain outside this slice. The supported native semantics and owned workflows do not establish full Raycast parity.
