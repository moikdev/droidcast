# Touch and large-font palette controls

Palette controls grow, wrap and scroll with Android's selected font size. The launcher, calculator, Emoji, native extension List/Grid, note editor and their menus share the updated layout. This is a scoped improvement under [#71](https://github.com/moikdev/droidcast/issues/71), not completion of every form, preference or accessibility requirement.

Changed custom controls use a 48 dp minimum where feasible. Search fields grow to fit their text line; Back, keyboard, category, favorite, Save and Cancel controls retain useful touch space. On compact or large-font layouts, footer metadata sits above actions, and keyboard hints yield space to action labels. Very narrow large-font action capsules stack vertically. Wide primary labels can wrap to two lines; stacked primaries and menu choices allow up to three. Long result titles and secondary metadata can still ellipsize; full command/action descriptions remain in semantics.

The calculator places expression and answer vertically when needed. Native Grid reserves scaled caption space and bounds decorative symbol previews. When a route's header, footer and content cannot fit, it can scroll while keeping its inner list or form bounded. Keyboard bindings, composition guards, live selection policy and the upstream runtime assets remain unchanged. Actual keyboard/composition regressions are a separate validation gate below.

## Recorded checks

The frozen application source is **448387eaa8dd8fddbbee18db4980fac148646306**:

- App APK SHA-256: `18f5f6a12a5c1d394293e16dc2c1de7adac94434d423565eac449bd4dcd90d52`.
- Test APK SHA-256: `b72db3b298569021101ff88e82e51db52e00bac0b0bf197a754a94c6a2879af8`.
- **120/120 matrix executions**: six logical flows on five viewports at font scales **1.0, 1.3, 1.5 and 2.0**. Every run verified data/settings/geometry restoration.
- **147 JVM cases**, debug/test and benchmark/test builds, debug and benchmark lint **0 errors/51 warnings each**, and **22 Python cases** passed. Rebuilt debug APKs match the frozen pair byte for byte.

The later merge of discovery tooling #73 adds scripts/docs only; all frozen Android/build/runner source hashes still match. The [checkpoint](validation/touch-readability/matrix-checkpoint.json) binds those sources, binary identities, exact case IDs, original record/log hashes, cleanup receipts and unmodified captures.

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

## Remaining validation and scope

The broader **173-case cohort per tablet/phone layout**, including the retained keyboard/composition/live-selection cases, is an independent merge gate. Its final outcomes and the two required exact-head audits are recorded on the implementation PR; this matrix checkpoint does not label an in-progress broad run complete.

Further native extension Form and preference/error layouts, actual split-window operation, and explicit IME toggling across more routes remain in #71. Display overrides do not certify split-screen gestures. Physical/OEM behavior, full TalkBack, RTL, themes, performance and complete renderer parity remain in their parent issues. This change neither changes production signing nor clears the production migration gate.
