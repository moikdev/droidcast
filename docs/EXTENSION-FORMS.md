# Readable native extension Forms

[Issue #83](https://github.com/moikdev/droidcast/issues/83) improves Android's existing Form subset: text, password, multiline text, checkbox and dropdown controls. Field labels, supplied errors and help use natural wrapping in a bounded scroll area. Help is available directly on screen. Checkbox labels toggle the entire row, whose minimum touch height is 48 dp. Closed dropdowns show the selected item's title while submitting its exact value; missing selections and empty option sets remain explicit.

The renderer delivers focus and blur for the five supported field kinds. Events resolve the current root, field and runtime session before dispatch. Native edits can precede a JavaScript publication, so the Android adapter supplies the matching callback's event-time value. Controlled fields can transform or reject an edit; an adapter acknowledgement publishes that decision even when the extension retains its previous React value. Uncontrolled fields retain their upstream state. The generated upstream runtime and AGPL/React notices are unchanged.

Native argument/preference setup uses the same readable labels, feedback, checkbox rows and option titles. Edits remain drafts until the explicit **Run command** action. Cancel discards them. Run checks required fields, saves the accepted preferences and starts the captured extension/command. Callbacks from a disposed or replacement setup cannot edit, run or close its successor. Menu callbacks also belong to one opening and retain the option the user actually saw.

Password editors now request Android's password keyboard type with autocorrection disabled, in addition to masking. A focused-editor metadata probe on the preceding runtime found ordinary text variation before any password was entered. The probe records only numeric input flags; the native preference fixture leaves its optional password empty and does not include it in destination output. The identical-instrumentation comparison passes on the repaired runtime for both editors. Android's [keyboard options](https://developer.android.com/develop/ui/compose/text/user-input#keyboard-options) describe the requested input behavior; this does not certify third-party keyboards.

Raycast documents [controlled/uncontrolled fields and blur-driven validation](https://developers.raycast.com/api-reference/user-interface/form). This slice demonstrates an extension setting an error on blur and clearing it on change. It does **not** establish Raycast's separate rule that Form errors prevent `Action.SubmitForm` submission; [#84](https://github.com/moikdev/droidcast/issues/84) tracks that follow-up with explicit negative controls and acceptance criteria. DatePicker, TagPicker, FilePicker, drafts, full imperative focus and complete Form parity remain open under #6. Native error semantics and checkbox label/role/state checks are not a full TalkBack certification.

## Verification and binary boundaries

All observations use the dedicated `Tinycast_Tablet_API_36` emulator, with ordinary app/adbd identities. The emulated profiles are tablet 1920×1200 at 240 dpi, portrait phone 1080×2340 at 420 dpi, and short landscape 2340×1080 at 420 dpi. Font scales are requested at 100%, 130%, 150% and 200%, then checked inside the running app. These are three profiles on one emulator, not physical device or actual split-window coverage.

The observed environment is Android 16 / API 36, arm64-v8a, with Google WebView `133.0.6943.137`. The environment receipt records the image fingerprint and ordinary shell identity.

The current runtime is frozen at `952fb168b482762791afb541bf2bd6b302842b9e`. Its source manifest contains 173 Android/build/runner hashes. The binaries below stay immutable throughout the final device pipeline.

| Binary | SHA-256 |
| --- | --- |
| Current debug app | `3424f3abc6ba7606e64a13f375f20b16303044ab532ee20a36fa7502ff6b5c15` |
| Current debug instrumentation | `23d8a9d29c2b03db764f61d2065c93d106712ffd2744392591971da56f97b660` |
| Common password-control instrumentation, source `922530f` | `7c20358fd5a979e825bc07e74213e40b122bf2a1d3e61eec38a0431b820d4d43` |
| Current benchmark app | `21d986b82ffcd3df32d7de2788748fdc7e7605fbdf3b1913b807bc59b4c3d417` |
| Current benchmark instrumentation | `4676e58c5b381c4234125d7258c3cab537d58c9191d063100a31b176c62aa38c` |

The exact common password-control APK is used on both the preceding `d6fba18` app and the repaired app. Both editors report input type `32769` (ordinary text variation `0`) before the repair, then `129` (password variation `128`, autocorrection off) afterward. Metadata is collected before entering any password. AndroidTest sources match the final harness; their APKs differ in `classes4.dex`, so other final runs retain their separate instrumentation identity.

Earlier runtime `cef390f` remains bound to app `23044efc…4ea67` and original instrumentation `db59aac8…c173`. Its separate `37856b9` pointer harness is `74e75952…3792`. Runtime `d6fba18` remains bound to app `ca754322…c6dec` and original instrumentation `9e72fe58…b27c5`. Those results are retained observations, not reruns of the current app. Matched controls exposed two additional defects repaired at `d6fba18`: the configuration Run button was absent from the palette chrome budget, leaving a 35 dp body at 130% short landscape; checkbox taps changed logical focus without moving native focus, preventing a fresh callback on return to the same text editor.

The current pointer helper reveals targets through both scroll owners, uses the Form's existing side padding for parent drags, observes native IME/window geometry across display frames, and checks a useful visible hit region before tapping. Ordinary editable taps follow Android's keyboard policy. Explicit show/hide scenarios separately assert actual IME visibility; hidden-IME composition waits for native window removal before constructing its controlled InputConnection. Earlier helpers that forced an extra keyboard request after each tap retain their failed and partial receipts. An activity-wide keyboard-policy experiment passed on both old and candidate apps; it earns no regression credit and was reverted.

| Check | Observed result |
| --- | --- |
| Original frozen harness, complete Form cases | 23/23 on tablet, phone and short landscape at 100% |
| Repaired d6fba18 runtime, complete Form cases | 24/24 on tablet, phone and short landscape at 100%; includes native checkbox/same-text focus return |
| Rapid edit/blur and controlled-value controls | Two matched prior-app failures, then 8/8 focused cases per portrait layout |
| Five basic baseline behaviors | Five intended old-app failures per portrait layout; current full suites cover the repaired behaviors |
| Stale text/choice callbacks | Six intended prior-app failures; current suites cover root removal, replacement field/session, changed option and reopened menu |
| Current 952fb16 runtime, complete Form/font matrix | Phone 24/24 at 100%; tablet, landscape and enlarged-font matrix in progress |
| Password input metadata | Two intended preceding-app failures, then 2/2 repaired-app passes with identical instrumentation |
| Shared menu/IME and reviewed-input regressions | Pending |
| JVM | 198 passing cases |
| Android adapter stub contracts | Seven passing Node cases, separate from actual React/device checks |
| Python | 63 normal / 63 optimized cases; unchanged runner sources |
| Builds and lint | Debug and benchmark app/test builds; zero errors / 51 warnings per variant |
| Hosted CI and independent audits | Separate exact-head PR merge gates; pending |

The five profile workflows check long labels/errors/help with actual IME open/closed transitions and full-line pointer witnesses; native required-field editing, Cancel and Run; the five supported fields with Unicode, selection, controlled composition and masked input; a frozen JSON command; and extension-driven blur validation. The composition check uses a controlled Android InputConnection across an asynchronous JavaScript round trip with Gboard hidden. It does not simulate phonetic typing gestures in a live OEM keyboard. Password assertions and public captures exclude the ephemeral plaintext password.

The guarded runner holds one nonce-bound lease through fixture deletion, independent clipboard restoration, preferences, font, geometry and final verification. It preserves pre-existing extensions/data and host state. Clipboard snapshots remain private in a separate test-APK UID and the private host backup. Public evidence contains restoration outcomes, never clipboard contents or their fingerprints.

## Raw Android captures

The [capture manifest](validation/extension-compatibility/forms/captures.json) binds 49 unmodified PNGs to their paired XML/frame receipts and exact app/test hashes. The first 41 keep their `cef390f` runtime identity: normal before/after pairs use identical original instrumentation, while the 200% sets use the separate `37856b9` pointer harness. Eight additional raw sets bind the matched `cef390f` failure / `d6fba18` native-focus and configuration-layout repair to the same final instrumentation.

| Prior native focus, tablet | Repaired same-text return, tablet | Configuration draft after selection, IME hidden |
| --- | --- | --- |
| ![Prior checkbox takes logical focus but fails the native focus assertion](screenshots/extension-compatibility/forms/native-focus-before-tablet.png) | ![Returning from checkbox to the same text field delivers the fresh native focus](screenshots/extension-compatibility/forms/native-focus-tablet-text-after-checkbox.png) | ![Native edited configuration draft and Run button at 130%, after the choice menu closes](screenshots/extension-compatibility/forms/native-layout-after-landscape-font130-configuration-edited.png) |

The old configuration failure capture has the IME open. The repaired draft capture follows menu selection with the IME hidden; the receipt separately records the full visible pre-tap target bounds and exact selected value.

| Old supplied error, phone | Repaired error, phone | Readable selected option, phone |
| --- | --- | --- |
| ![Old app omits the supplied field error](screenshots/extension-compatibility/forms/before-error-phone.png) | ![Repaired native Form displays the supplied error](screenshots/extension-compatibility/forms/after-error-phone.png) | ![Closed dropdown displays the selected item title](screenshots/extension-compatibility/forms/after-dropdown-phone.png) |

| Help without hover, tablet | Checkbox label tapped, phone | Extension error after blur, 200% phone |
| --- | --- | --- |
| ![Supplied help is visible below its field](screenshots/extension-compatibility/forms/after-help-tablet.png) | ![Tapping the actual label toggles the native checkbox](screenshots/extension-compatibility/forms/after-checkbox-phone.png) | ![Pointer scrolling reaches the owned extension's blur-validation error](screenshots/extension-compatibility/forms/font200-phone-blur-validation-error-bottom.png) |

| 200% phone, actual IME open | 200% short landscape, long option | Actual JSON Copy destination, phone |
| --- | --- | --- |
| ![Long Form title with the requested enlarged font and Gboard open](screenshots/extension-compatibility/forms/font200-phone-long-1-top.png) | ![Long option lines reached by pointer in short landscape](screenshots/extension-compatibility/forms/font200-landscape-long-option-bottom.png) | ![Frozen JSON command's exact Unicode output and explicit Copy action](screenshots/extension-compatibility/forms/phone-json-detail.png) |

## Real frozen JSON workflow

The workflow runs the frozen `json-format/index` bundle in an owned namespace. Archive SHA-256: `fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58`; command SHA-256: `bcca05348f11ffc115adc346944dcd78d209958684cb9df44d571e70a99575a0`.

The test opens the actual native preference UI, chooses **Spaces: 2**, edits `maxInitialLines` to 42 and disables Auto Paste. It enters whitespace-preserving Japanese/emoji/combining-accent JSON, selects **View Result**, verifies the exact fenced Detail value, then explicitly copies the exact formatted Unicode output. The clipboard sentinel stays unchanged until Copy. Back restores the Form input, then returns to the original launcher query. This is one named command workflow; it does not rerun the whole Store compatibility inventory.

## Retained failures and limits

The first implementation's isolated rapid-blur pass did not clear its later combined-suite failure: JavaScript's captured value could lag the native edit. The final adapter's matched negative controls exercise the failing behavior before the repair. Earlier prototype APKs, compile errors, probe failures and partial runs retain their own identities.

With the frozen `cef390f` app and original harness, the 200% tablet and phone profiles pass all five workflows. The first 200% short-landscape cohort remains **2/5**, with failed navigation/composition setup. Stricter helper revisions retain their separate **0/3**, **2/3** and **4/5** receipts. The latter's JSON Copy succeeds, but its Back assertion finds only a two-pixel visible target. The strengthened same-APK harness then passes all five workflows at 200% on all three layouts and at 130% on tablet/phone. Its 130% landscape group remains **4/5**: the stricter visible-target check exposes the actual insufficient configuration-body allocation. The remaining 150% groups and final full landscape run did not start in that pipeline. None is credited to the `d6fba18` repair.

The separate same-text return probe on the `cef390f` app fails. Two new-harness matched controls also fail the intended native-focus and configuration-target assertions against that prior app. All their restoration checks pass. The repaired app's focused return checks pass on tablet, phone and 200% short landscape. Completed broader repair outcomes remain a separate gate.

The repaired `d6fba18` app's first 200% landscape group with instrumentation `9e72…` remains **4/5**: blur-validation navigation ends with the name editor focused and the next field outside the visible region. Moving the test's parent-navigation drags into the padding gutter yields a passing focused check on the byte-identical app. The final matrix uses the separately identified `71609…` instrumentation; the earlier failure is retained.

Natural-height/non-ellipsized text and sequential full-line reachability are required; a screenshot of a clipped fragment is insufficient. Native XML is captured before each unmodified PNG, with a frame receipt and stable runtime tree. Destination/error/return captures complement assertions. Owned probes establish renderer and callback behavior, not external service recovery, arbitrary extension compatibility or broader platform readiness. Physical/OEM devices, full accessibility, actual split-window, production signing/migration and full parity remain open.
