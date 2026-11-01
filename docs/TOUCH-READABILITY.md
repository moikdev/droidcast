# Touch and large-font palette controls

Palette controls grow, wrap and scroll with Android's selected font size. The launcher, calculator, Emoji, native extension List/Grid, note editor and their menus share the updated layout. This is a scoped improvement under [#71](https://github.com/moikdev/droidcast/issues/71), not completion of every form, preference or accessibility requirement.

Changed custom controls use a 48 dp minimum where feasible. Search fields grow to fit their text line; Back, keyboard, category, favorite, Save and Cancel controls retain useful touch space. On compact or large-font layouts, footer metadata sits above actions, and keyboard hints yield space to action labels. Very narrow large-font action capsules stack vertically. Wide primary labels can wrap to two lines; stacked primaries and menu choices allow up to three. Long result titles and secondary metadata can still ellipsize; full command/action descriptions remain in semantics.

The calculator places expression and answer vertically when needed. Native Grid reserves scaled caption space and bounds decorative symbol previews. When a route's header, footer and content cannot fit, it can scroll while keeping its inner list or form bounded. Keyboard bindings, composition guards, live selection policy and the upstream runtime assets remain unchanged. Actual keyboard/composition regressions are a separate validation gate below.

Feedback measures against the visible palette, then uses the gap between header and footer when the message fits. When scrolled chrome leaves that gap too small, feedback stays inside the palette. Longer messages retain their scaled text and can scroll, with a visible scroll indicator.

## Recorded checks

The original adaptive-controls checkpoint retains application source **448387eaa8dd8fddbbee18db4980fac148646306**. These completed results keep that identity after the feedback follow-up below:

- App APK SHA-256: `18f5f6a12a5c1d394293e16dc2c1de7adac94434d423565eac449bd4dcd90d52`.
- Test APK SHA-256: `b72db3b298569021101ff88e82e51db52e00bac0b0bf197a754a94c6a2879af8`.
- **120/120 matrix executions**: six logical flows on five viewports at font scales **1.0, 1.3, 1.5 and 2.0**. Every run verified data/settings/geometry restoration.
- **147 JVM cases**, debug/test and benchmark/test builds, debug and benchmark lint **0 errors/51 warnings each**, and **22 Python cases** passed. Rebuilt debug APKs match the frozen pair byte for byte.

The merge of discovery tooling #73 added scripts/docs only. The [checkpoint](validation/touch-readability/matrix-checkpoint.json) binds the original sources, binary identities, exact case IDs, original record/log hashes, cleanup receipts and unmodified captures. At review head9e9244d, that same app/test pair also completed 173/173 native cases per tablet/phone layout with all 18 tablet/20 phone cleanup checks passing; [the PR receipt](https://github.com/moikdev/droidcast/pull/74#issuecomment-6564392495) retains exact case IDs and hashes. These results are not relabeled as the later feedback runtime.

All captures use the dedicated **Tinycast_Tablet_API_36/API36 arm64 emulator**, with an ordinary app UID and unprivileged adbd. Profiles run sequentially on that emulator:

| Profile | Display pixels | Density | Matrix executions |
| --- | --- | --- | --- |
| Tablet landscape | 1920 × 1200 | 240 dpi | 24 |
| Tablet portrait | 1200 × 1920 | 240 dpi | 24 |
| Phone portrait | 1080 × 2340 | 420 dpi | 24 |
| Narrow portrait | 840 × 1600 | 420 dpi | 24 |
| Phone landscape | 2340 × 1080 | 420 dpi | 24 |

The six flows cover launcher actions and owned-note editing/saving; categories and empty results; calculator answer copying; Emoji copying/filtering/disabled empty states; and native List/Grid action copying and return. List/Grid use actual JavaScript fixtures rendered by the app, not Store compatibility certifications. Input uses Compose pointer clicks/swipes plus text and lazy-list semantics, without hardware-key navigation. Screenshots show real Android windows; they are neither mockups nor edited composites.

The editor explicitly opens the IME. Geometry checks wait for a stable viewport, then read clipped/unclipped bounds together on the UI thread. Captured drags start inside a scroll surface and may continue beyond the small palette within its app root. They first expose enough of an inner viewport to contain the target, then scroll its content. Target-size, non-overlap, text-line, exact clipboard and saved-note assertions remain in place. The matrix exercises keyboard-visible and keyboard-hidden states as encountered in these flows; it is not an explicit IME toggle test for every route.

Clipboard preparation has [240 phase receipts](validation/touch-readability/clipboard-phase-receipts.json), all settled with entry flags restored. Of 161 exact SystemUI node-action attempts, 160 were accepted; the rejected attempt is not credited as an accepted action. This is fixture preparation and flag-restoration evidence, not a claim that every phase required a click. Visible IME clipboard suggestions contain known synthetic fixture values.

## Before and after

This section retains the original adaptive-controls captures, whose updated frames use 448387e and the b72db3b test APK. The later feedback repair has its own captures and identities below.

The original 7398e25 phone/200% baseline uses the earlier test APK `b233652b…effcdb3` and failed all six initial checks. Its capture visibly loses the note title and primary action. It remains historical baseline evidence, separate from the matched controls below.

| Original phone/200% baseline | Updated phone/200% |
| --- | --- |
| ![Original phone baseline](screenshots/touch-readability/baseline-phone-font2.png) | ![Updated phone controls](screenshots/touch-readability/phone-font2-launcher.png) |

The two matched controls use the same current `b72db3b…2879af8` test APK against the previous `d949f43` app, SHA-256 `489e0702e871b059c3d4f8cc2b9e54d998268e58699dd81c1e3e3d646f6a437e`. They reach the intended product assertions: the result type “Note” is ellipsized at narrow/200%, and the long primary action is ellipsized at landscape/130%. Both corrected focus cases pass. Owned fixture UUIDs differ between runs.

| Narrow result type before | Narrow result type after |
| --- | --- |
| ![Result type squeezed out](screenshots/touch-readability/narrow-kind-before.png) | ![Result type visible](screenshots/touch-readability/narrow-kind-after.png) |

| Wide primary before | Wide primary after |
| --- | --- |
| ![Primary action truncated](screenshots/touch-readability/wide-primary-before.png) | ![Primary action wrapped](screenshots/touch-readability/wide-primary-after.png) |

The earlier menu failure below uses the same d949f43 app but the previous `3ee5ffd0…341de29` test APK. It is labeled separately rather than counted as a matched current-harness control. The updated frame shows both the menu and primary label wrapped.

| Earlier landscape/130% menu failure | Updated menu |
| --- | --- |
| ![Earlier truncated menu label](screenshots/touch-readability/wide-menu-before.png) | ![Wrapped menu label](screenshots/touch-readability/wide-menu-after.png) |

Additional real frames include [calculator](screenshots/touch-readability/phone-font2-calculator.png), [Emoji](screenshots/touch-readability/phone-font2-emoji.png), [disabled Emoji empty state](screenshots/touch-readability/phone-font2-emoji-empty.png), [launcher empty state](screenshots/touch-readability/phone-font2-empty.png), [tablet Grid](screenshots/touch-readability/tablet-font2-grid.png), [landscape editor with IME](screenshots/touch-readability/landscape-font1-editor.png), and [tablet at default font size](screenshots/touch-readability/tablet-font1-launcher.png). Each has its own build, viewport, JSON and paired native hierarchy identity in the checkpoint.

## Feedback with scrolled chrome

The first correctness audit found that a fully offscreen footer has a non-null empty bound. Using it to constrain feedback reduced confirmations and errors to one pixel. The repair measures the message within the whole palette before choosing its position. A bounded scroll surface exposes long messages when the IME leaves very little height.

The frozen feedback runtime is **a48d13cc5d9b1b75785b56249ad7bdabe0f30c8b**, app SHA-256 `179fca2a5e24f0debd9acf020de128248c71d848424ad8c49ef2c83c4a57c312`. The final test harness is **0c6cf742f88028857cedca8c7c20b8913569c0f6**, test APK `6b3d21c0ae2c2f5c6c2e9eb89011f6f59e01802af45069411313b8059e360b40`. Only FeedbackReadabilityDeviceTest changes between those commits; production/build sources and both runners match the runtime checkpoint.

Two matched old-app cases use the original 448387e runtime and that same final test APK. Both reach the intended one-pixel feedback assertion; both pass on the repaired runtime. All 16 cleanup checks per run pass. These are real app rendering fixtures with injected confirmation/error text, separate from actual clipboard or error-producing workflows. They use the visible keyboard button, verify a settled IME viewport, and require the entire footer to be offscreen. The 2340×1080/420 dpi landscape profile at 200% font leaves approximately 83 dp of visible palette height.

| Confirmation before | Confirmation after |
| --- | --- |
| ![Before: feedback has one pixel](screenshots/touch-readability/feedback-confirmation-before.png) | ![After: confirmation text is visible](screenshots/touch-readability/feedback-confirmation-after.png) |

The longer error is readable sequentially. The test keeps its natural text layout, checks each line's bounds and absence of ellipsis, then uses a pointer swipe to expose the second line. The first line can move out of view while the second is shown.

| Error before | Error after, top | Error after, following swipe |
| --- | --- | --- |
| ![Before: error is one pixel](screenshots/touch-readability/feedback-error-before.png) | ![After: first error line](screenshots/touch-readability/feedback-error-top.png) | ![After swipe: remaining error line](screenshots/touch-readability/feedback-error-bottom.png) |

The [feedback checkpoint](validation/touch-readability/feedback-checkpoint.json) binds 118 source hashes, both runners, binaries, original records/logs, paired raw JSON/XML and all five unchanged PNGs. The harness waits for native hierarchy idle before screenshots and requires the message to remain active. Its width assertion checks visible node width and actual line extents: Compose 1.7.6's diagnostic didOverflowWidth compares the paragraph container with the tighter text node, including unused space. The recorded confirmation has a 1554 px paragraph container, a 576 px text node and a 574.29 px drawn line; it fits visibly.

The feedback runtime passed 147 JVM and 22 Python cases, debug/test and benchmark/test builds, and debug/benchmark lint with 0 errors/51 warnings each. Those builds retain a48d13c; the final debug test harness was compiled anew after its assertion/capture-only changes. Current-runtime critical font and broad regression results, the actual hosted workflow and two fresh exact-head audits remain independent merge gates recorded on [PR #74](https://github.com/moikdev/droidcast/pull/74).

## Remaining validation and scope

The current feedback runtime requires the critical font regressions and broader **173-case cohort per tablet/phone layout**, including retained keyboard/composition/live-selection cases. Final outcomes and the two required exact-head audits are recorded on PR #74. The original matrix and the focused feedback checkpoint retain their separate identities and scopes.

Further native extension Form and preference/error layouts, actual split-window operation, and explicit IME toggling across more routes remain in #71. Display overrides do not certify split-screen gestures. Physical/OEM behavior, full TalkBack, RTL, themes, performance and complete renderer parity remain in their parent issues. This change neither changes production signing nor clears the production migration gate.
