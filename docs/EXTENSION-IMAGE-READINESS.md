# Image readiness diagnostics

This checkpoint advances [#89](https://github.com/moikdev/droidcast/issues/89) with opt-in image diagnostics and stronger native pixel checks. The original `37eb0ed` blank-image/Loading observation remains unexplained and open. The earlier [Detail repair report](EXTENSION-MARKDOWN-DETAIL-REPAIR.md#current-runtime-and-retained-image-failure) retains it. Passing repeats establish no cause or production repair.

The normal image path exposes the existing basic DEBUG probe. Detailed history is enabled before owned fixture entry through a process-local test property and restored exactly in `@After`, including failed tests. It records producer completion, actual state writes, applied composition and draw commands. No URI, bitmap contents, original clipboard data or private fingerprints are emitted. A zero value token denotes a producer-only event with no render value attached. Draw-command records are separate from the actual captured pixel check and certify no physical scanout timing.

Recent history is capped at 64 events, with five fixed latest-stage summaries so a stable image's publication witness survives timeline rollover. At most 128 weak render-value aliases are retained; token eviction is reported and prevents a value-agreement claim. Disposed probes release their sampling closure. Ordinary-mode smoke cases paint the owned green image with no extended snapshot key.

Tokens follow the [pinned Compose 1.7.6 produceState equality behavior](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.7.6/runtime-android-1.7.6-sources.jar): equal source/bitmap/error values may reuse a composition even when their wrappers differ. The first diagnostic lookup failed to register a live equal replacement. Retiring its older weak wrapper could change the diagnostic token while the pixels stayed correct. `WeakValueTokens` now registers each live alias under its existing label.

## Results and retained failures

Current binaries are frozen at `a324c12b4fb903eb35524fff8fc5425b18e6e53a`; all 220 build/test/runner source hashes are in [identity.json](validation/extension-compatibility/image-readiness/identity.json). Documentation publication does not rebuild them. Debug app SHA256: `8145f28dbf826b33174996c28f06cd5df3711d5ee8f20253f94b9c852583988f`; test APK: `635e7f2eae03c547f66eefd133a539ac47a694a7bbd136ce1204e11120e809e3`.

| Current cohort | Executions | Successful host cleanup checks |
| --- | ---: | ---: |
| Focused image paths, landscape at 100% | 4 | 20 |
| Original set of 22 methods in the original order, landscape at 100% | 22 | 20 |
| Focused image paths, phone at 200% | 4 | 20 |
| Total | 30 | 60 |

These are 30 executions of 24 unique methods. Every case verifies process-property restoration and preservation/removal of the appropriate installed extension data and owned fixtures. Read-only before/after snapshots separately verify all 136 pre-existing evidence files. Two cached-image cases observe eight releases and green-pixel returns each, stable geometry, actual recent-history rollover and zero token evictions. Semantic scroll positioning is setup; this adds no pointer-navigation credit. Pixel checks inspect the sampled native image pixels, not every pixel in an arbitrary image.

The actual environment is dedicated Tinycast_Tablet_API_36, API 36 arm64-v8a, ordinary app UID 10213 and shell/adbd UID 2000. Current profiles are 2340×1080 landscape at 420 dpi/100% text and 1080×2340 phone at 420 dpi/200% text. Selected smoke frames have the IME hidden. This is no new full font/IME/platform matrix.

All 267 JVM cases pass with zero errors/skips. Debug/test and benchmark/test builds pass; lint records zero errors and 55 warnings per variant. Benchmark device execution is unclaimed. Python and Node sources are unchanged; no new execution of those suites is claimed here.

The common host regression at `af505dd` fails its single case with zero passes when the older weak reference is explicitly cleared. The exact same JUnit file is included among the 267 current passes; the test adds no GC-timing assumption. Its older `f0f9742` prototype remains a separate expected-negative record. These are host bookkeeping controls, not reproductions of the original Android blank image.

The earlier `4db0b51` native focus cohort remains failed with three passes and one failure because its diagnostic draw token changed 3→5, although its green pixel assertions passed. All 20 cleanup checks, process-property restoration and the 136 evidence comparisons succeeded. Later planned cohorts were not run. Its app/test binaries and harness differ from the current cohort; only the host regression is a common unchanged contract. A prior benchmark-task invocation also remains failed before compilation; corrected separate variant commands pass. The earlier unchanged 8e one-case replay and first 2e93 diagnostic run with 24 passes remain separately identified, without original-failure repair credit.

## Real Android smoke frames

Five current captures and one earlier diagnostic-failure capture are raw Android PNGs. Their paired native XML/frame receipts preserve original bytes and CRLF, with binary identities and scopes in the checkpoint. This nonvisual diagnostic change introduces no new UI design claim.

- [ordinary-landscape100](screenshots/extension-compatibility/image-readiness/ordinary-landscape100.png): Current normal-mode real app smoke; extended history disabled; landscape100.
- [ordinary-phone200](screenshots/extension-compatibility/image-readiness/ordinary-phone200.png): Current normal-mode real app smoke; extended history disabled; phone200.
- [touch-information-phone200](screenshots/extension-compatibility/image-readiness/touch-information-phone200.png): Current opted-in image information after a real pointer long press; phone200.
- [cached-landscape100](screenshots/extension-compatibility/image-readiness/cached-landscape100.png): Current opted-in eighth cache return; green pixels and current stage summaries agree; landscape100.
- [cached-phone200](screenshots/extension-compatibility/image-readiness/cached-phone200.png): Current opted-in eighth cache return; green pixels and current stage summaries agree; phone200.
- [retained-diagnostic-failure](screenshots/extension-compatibility/image-readiness/retained-diagnostic-failure.png): Earlier 4db0b51 failure in diagnostic counters despite correct green pixels; the original blank-image observation is separate.

The [checkpoint](validation/extension-compatibility/image-readiness/checkpoint.json) binds the public projections, raw frames and retained capture hashes. Host/case projections use explicit allowlists; private clipboard originals/fingerprints, preference backups, evidence tar backups, signing material and VM boot/disk data are excluded. JVM/lint/log host prefixes use declared replacements; XML placeholders are escaped. Generated upstream assets and AGPL/React notices are unchanged.

The original blank-image investigation stays under #89. Legacy menu failures remain under #91. Whole Store/API coverage, physical/OEM devices, accessibility, near-cap aggregate resource stress and production migration remain open. Actual final-head hosted CI, local status, two fresh independent gpt-6.1-sol/high audits and expected-head verification remain separate merge gates.

## JPEG edges at a power-of-two sampling boundary

On API 36, a 2,049 × 256 JPEG decoded at a sample factor of two produced a 1,025 × 128 bitmap. Detail exceeded its 1,024-pixel edge limit and showed “Image unavailable.” Swapping width and height produced the same failure. A 2,048 × 256 JPEG rendered on the same earlier app. The Grid decoder also returned a 257 × 32 thumbnail for a 513 × 64 JPEG despite its 256-pixel target.

Detail and Grid now choose the smallest power-of-two sample for which the source edge fits within the target edge multiplied by the sample. For the two Detail failures this chooses four. The native Detail tests check green pixels after the actual Markdown image appears. The Grid test invokes the production thumbnail loader and measures the returned bitmap and its center pixel. The repaired thumbnail measured 129 × 16. The Grid test’s host screen is Detail.

| Landscape run on the dedicated API 36 emulator | Passed | Failed |
| --- | ---: | ---: |
| Initial investigation, app built at `bc103d88`, test source `96a90012` | 2 | 2 |
| Comparison app built at `adde55c9`, final test APK | 2 | 3 |
| Repaired app built at `2449c383`, same final test APK | 5 | 0 |

The landscape tests ran at 2340 × 1080, 420 dpi, and 100% font scale. The comparison app’s production sources match main at `0148f79f`. Both landscape apps rendered the exact boundary and passed the existing SVG image-information workflow. The historical blank SVG frame on `37eb0ed` remains unexplained under #89. Each completed landscape batch recorded 20 cleanup checks, verification of both original installed packages, and release of the host lease.

The first portrait attempt stopped after the first JUnit start event when the host process and read-only emulator instance disappeared. Its log contains no method completion, and its wrapper produced no restoration receipt. The later boot had different installed package hashes and no prior run lease. Cross-boot restoration of the interrupted instance is unverified. The fresh portrait run used the same repaired app and test APKs at 1080 × 2340, 420 dpi and 100% font. All four JPEG cases passed. Its 20 inner cleanup checks passed, and the outer wrapper verified both package bytes against the separate fresh-boot baseline before releasing its lease. [Native logs and selected measurements](validation/extension-compatibility/raster-sampling/batches.json) · [Captured frames and hierarchies](validation/extension-compatibility/raster-sampling/bindings.json).

Host verification ran 355 debug JVM tests and 147 Python tests in each of normal and optimized modes. The debug and benchmark app and instrumentation APKs built. Lint recorded zero errors and 53 warnings per variant. The first benchmark invocation stopped during lint analysis. The separate continuation completed successfully. [Host results](validation/extension-compatibility/raster-sampling/host.json).
