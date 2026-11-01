# Production-readiness and parity review

Work in progress under [#65](https://github.com/moikdev/droidcast/issues/65). This report does **not** clear a production release or claim full parity. The user chose emulator-only coverage for this pass.

## References and real screenshots

Droidcast: released r10, versionCode 6, source `34b70a579ec6db366e2363deb6bd227591c7c750`, app SHA-256 `e68b7e7c4a3771c18128221f7d31b71e144db0d64947ae0ee08f91bddccf3f00`. Its production source tree matches merged main `ddeb0fd`. Android captures: API36 arm64 emulator, tablet 1920×1200/240dpi. The original measurements and comparison captures use that unchanged release. The readiness correction work now changes production code; its candidate results are recorded separately and do not retroactively certify r10.

Raycast: installed macOS app 2.10.1.0. Tinycast: actual locally built Dev 0.1.0 (1), source [`37e6043`](https://github.com/abue-ammar/tinycast/tree/37e6043f3f550febb64bdb3cff3ac29a30caad5a), Xcode27/macOS27.0.1, no Accessibility/login-item/hotkey grants or personal-data import. Upstream HEAD advanced to [`e3f813c`](https://github.com/abue-ammar/tinycast/compare/37e6043...e3f813c): native glass/shadow changes and terminal prompt handling. The images below are explicitly the older pinned revision, not screenshots of that newer revision.

Desktop captures show app windows. Android captures show the whole display with the transparent floating palette over Settings. Different bounds, display scales and glyph fonts prevent a pixel-size or visual-performance comparison. All are real captures, not mockups. The desktop capture API returned JPEG data despite the original `.png` filenames. The six desktop images are now genuine PNG encodings of those exact decoded RGB pixels, with no scaling/cropping/edits. The original JPEG bytes and decoded-pixel hashes are retained in [capture provenance](screenshots/readiness/desktop-capture-provenance.json).

| Workflow | Raycast | Tinycast | Droidcast tablet |
| --- | --- | --- | --- |
| `sqrt(144)` → `12` | ![Raycast calculator](screenshots/readiness/raycast-calculator.png) | ![Tinycast calculator](screenshots/readiness/tinycast-calculator.png) | ![Droidcast calculator](screenshots/readiness/droidcast-calculator.png) |
| Attached Actions | ![Raycast Actions](screenshots/readiness/raycast-actions.png) | ![Tinycast Actions](screenshots/readiness/tinycast-actions.png) | ![Droidcast Actions](screenshots/readiness/droidcast-actions.png) |
| Emoji search `face` | ![Raycast Emoji](screenshots/readiness/raycast-emoji.png) | ![Tinycast Emoji](screenshots/readiness/tinycast-emoji.png) | ![Droidcast Emoji](screenshots/readiness/droidcast-emoji.png) |

The common calculator expression, persistent search header, attached action panel and contextual footer have working Android equivalents. The action sets differ: Tinycast offers putting the answer into Search and copying the whole calculation; Droidcast currently only offers Copy Answer. Raycast additionally offers history, pinning and cross-app paste. Android Copy does not establish paste-back parity. Emoji glyphs, catalogs, ordering, ranking and remembered usage differ; matching the query does not imply equal result counts or catalog coverage.

## Measurement contract

The initial diagnostic instrumentation uses the real activity and real IME InputConnection or native key dispatch, without Compose's test clock. A transparent diagnostic Drawable in the decor overlay observes semantics **after** descendants have drawn, then registers a hardware frame-commit callback. It includes observer overhead and frame submission, and excludes physical input transport and display scanout. It is not input-to-photon latency. Raw samples and OS FrameMetrics are retained. Setup, screenshots, idle synchronization and warmups are excluded from per-interaction intervals; the window-wide frame sample includes those surrounding operations.

An earlier OnDrawListener experiment checked semantics before Compose's deferred drawing/layout and sometimes waited for a caret blink. Its half-second plateau is a measurement defect, not evidence of an app delay. Those results are excluded. Earlier batch-edit and fallback-result assertion failures are also fixture failures and excluded.

Startup uses Android `am start -W`: process-cold after force-stop (without evicting filesystem/OS caches), and retained-process Home→foreground. Android may report `UNKNOWN (0)` with no TotalTime for an already-existing task; only WaitTime is reported for that path. Host command time is separately labeled and includes ADB. Three warmups precede 30 measured trials per mode. Raw OS output is retained; unsupported timing fields remain absent.

The decor-overlay timing is retained as diagnostic evidence only: partial child redraws may not redraw the overlay promptly. Phone arrow samples show a suspicious caret-like cadence. A separate PixelCopy/golden-frame check is being added to verify actual result/highlight pixels; neither timing path will be presented as input-to-photon latency.

A disposable `.benchmark` package builds with `debuggable=false`, release dependency variants and the same source. It uses the local debug certificate, has no R8/minification configuration, and is **not** a production-signed release candidate. It cannot enter the updater channel. The published nightly remains installed separately. The runner refuses an existing benchmark package and independently removes test packages/restores settings and geometry afterward.

A nondebuggable API36 tablet soak completed **230 rounds in 602.565 seconds**, with **1,151** verified query responses and native Emoji/back navigation. PSS samples ranged from **54,514 to 76,548 KiB**, ending below the start. These observations cover this workload and include instrumentation; they are not leak-free certification. Final timing distributions, broader functional checks and audit conclusions remain pending. Emulator graphics delays, instrumentation overhead and shared host load preclude a physical-device latency verdict or claims that Droidcast is faster than macOS apps.

## Production gates

| Gate | Current verdict / next evidence |
| --- | --- |
| Production source and artifact identity | Released r10 frozen/hash-verified; current measurements distinguish it from the separate benchmark APK. |
| Functional regression | r10's historical 138 JVM, 32 native cases per layout and four real self-updates are recorded in [updater validation](validation/updates.md). Fresh broader checks pending; historical checks are not reruns. |
| Security and independent audits | Prior slice audits are recorded on their PRs. Two new sol/high independent audits are required for this checkpoint. |
| Clean-runner CI | The GitHub app connection activated the workflow on PR #66. After fixing the missing SDK setup, exact-head run [41276405029](https://github.com/moikdev/droidcast/actions/runs/41276405029) passed on `3d1b595`: debug and benchmark compilation, JVM tests and lint. New correction commits still require their own final-head results. Hosted compilation is separate from emulator and signing coverage. |
| Signing/release engineering | Nightly/debug certificate continuity is tested. Maintainer-controlled production signing and recovery remain #21. |
| API/OEM matrix | API36 emulator only. API26 runtime, Lenovo/OEM, physical input and hardware latency remain unverified. |
| Accessibility/localization | Scoped labels/focus tested previously. Comprehensive TalkBack, large-font, theme and translation work remains #20. |
| Reliability/performance | Ten-minute/230-round nondebuggable search/navigation soak passed. Startup and timing work is still being refined; no physical latency or leak-free certification. |
| Lifecycle/providers | Process death, cloud/large folder trees and extended installer interruption remain incomplete. |
| Parity | [Feature matrix](../PARITY.md) remains partial: dictionary, snippets, calendar, AI, advanced calculator/quicklinks, richer extension APIs and Android multitasking replacements are open. |
| Unexplained failures | #35/#37/#41/#59/#62 remain open; passing reruns do not establish their causes or fixes. |

No production-ready verdict is justified while these gates remain open. The next implementation work should close measured failures and concrete parity gaps through the existing issue/PR/audit loop.

## Primary measurement references

- [Android frame commit](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)): submission does not establish physical display visibility.
- [Android startup states and timings](https://developer.android.com/topic/performance/issues/launch-time).
- [Android FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics).

## Audit corrections in progress

The first two independent audits at `3d1b595` found existing production defects: same-turn launcher/List activation used the previously rendered selection; unsaved note/quicklink drafts were lost on Activity recreation; recent-document metadata queried providers on the UI thread; extension HTTP reads lacked cancellation and an overall deadline. The correction also fixes SDK discovery in the benchmark runner and the desktop screenshot format mismatch.

The first six controlled native cases reproduce old selection/draft behavior on released r10. Their initial editor assertions also included field labels. A later exact-`EditableText` old-build run reproduces all six original cases plus rapid discard/reopen retaining an abandoned draft. A preliminary corrected candidate passed ten focused tablet cases. The final ViewModel draft and large-content cases still require their own runs. A preliminary patched attempt used a stale instrumentation APK after a failed build and is excluded. The initial HTTP test class had a non-void JUnit method; the corrected seven-case suite passes. None of these harness corrections is evidence of a production timing fix.

The first broader unchanged-r10 battery has completed. Interaction, extension Grid and system Settings groups pass on both layouts. The unavailable-shortcut feedback passed, but clearing its dormant binding failed on both layouts: the nested list was below the outer form viewport. Failure PNG/semantics retain the clipped Clear control. The test now scrolls the outer form before reaching the row; production UI was not changed for this. The tablet backup import-preview heading and phone picker-cancel return assertions failed at different steps; focused unchanged reruns have passed and do not establish their cause or a production fix. The updater verification group was launched without its required signed test payload, causing 12 setup failures per layout; that attempt is excluded from updater functionality claims and must be repeated with the guarded fixture setup. Original logs and cleanup results remain retained.

## Retained baseline data and correction snapshots

![Released-nightly startup and separate soak measurements](screenshots/readiness/startup-and-soak.png)

The two startup panels report different Android OS metrics and remain separate. Raw [startup trials](validation/readiness/startup-samples.csv) preserve warmups, OS launch states and absent `TotalTime` fields. [Baseline metrics](validation/readiness/baseline-metrics.json) record identities and memory samples. The soak's decor-observer timing remains diagnostic and is excluded from interaction-latency statistics. Candidate PixelCopy timing with asynchronous key enqueue is still pending execution.

| Controlled state | Before / released r10 | Preliminary correction |
| --- | --- | --- |
| Unsaved new-note title after Activity recreation | ![Lost draft title](screenshots/readiness-regressions/before-note-recreation.png) | ![Preliminary retained title](screenshots/readiness-regressions/preliminary-note-recreation.png) |

These are real API36 tablet captures. The keyboard hides the body in these stills; the native assertions inspect it separately. The after image is the preliminary `5f27984…` APK, not final-candidate certification. [Per-image identities](validation/readiness/provisional-captures.json) preserve that distinction. The later correction retains drafts in the ViewModel, handles rapid discard/reopen, and adds a large-draft recreation regression without serializing text into the Activity Bundle. Explicit save, discard, large content and final tablet/phone evidence remain under validation.

The first two broad unchanged-r10 runs contain 260 native passes, 24 updater setup failures and four assertion failures. The 45-case tablet interaction host wrapper incorrectly expected 47; its failed original host record remains separate from independent verification of the 45 exact native pass IDs. Corrected fixture validation subsequently passed all 21 selected tablet cases. Phone verification passed 20/21: its backup return subtitle was outside the preserved scroll viewport, confirmed by the failure XML/PNG. The test now scrolls to that subtitle. A later export-cancel timeout and the earlier native import-preview miss remain retained and unattributed. The two initial APK transfers timed out before any updater tests; their verified cleanup records are retained. The corrected transfer uses bounded `adb push`, and the runner restores its scoped source-approval mode independently.

Sources for the coroutine/provider boundary: [Kotlin cancellation contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-cancellable-continuation/) and [Android ContentResolver](https://developer.android.com/reference/android/content/ContentResolver). Query cancellation is optional at the provider; `getType` exposes no cancellation signal, so stuck calls occupy a bounded worker rather than blocking the main thread or spawning unbounded threads.

Candidate `e89d078` has passed 145 JVM cases and debug/benchmark build/lint, and its 155-case native suites per layout are in progress. The later `944aca8` source changes only draft ownership/lifecycle and the backup return-scroll fixture; its debug build, 145 JVM cases and lint pass (0 errors/51 warnings). Its final native and benchmark evidence is pending. Those variants are not interchangeable, and neither has release or merge clearance.
