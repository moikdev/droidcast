# Native Detail audit repairs

This checkpoint addresses the three findings on [PR #90](https://github.com/moikdev/droidcast/pull/90), under [#89](https://github.com/moikdev/droidcast/issues/89). The [initial milestone](EXTENSION-MARKDOWN-DETAIL.md), its 890-file publication and 43 raw capture sets remain unchanged and bound to the older `4bb10aa` runtime. That runtime includes the three audited defects. Its 161-execution accounting, original failures and line-370 metadata correction are historical evidence.

The [repair checkpoint](validation/extension-compatibility/native-detail-audit-repair/checkpoint.json) records 218 source hashes for repository `a2cab95`. The Android/native-test binaries remain the frozen `8e75546` build; the Python runner adds its timeout at `a2cab95`. Final CI, two fresh independent audits and expected-head verification remain separate PR gates. The image-readiness failure below remains unexplained; successful subsequent checks establish no repair of it.

| Binary / cohort | SHA-256 |
| --- | --- |
| Current debug app | `e442c0857b8a59e3cb4c3ad144d0df517f1ad1598d581869f431d6bcb961ace0` |
| Current full-matrix instrumentation | `0b804a9bca3d857ae44fc0aab3d9b98847ec366a724929b9724343af9e201a31` |
| Common old/repaired-control instrumentation, source `49c5d5a` | `3aca85e4dc53a5523c4dd090754703c6d747b04c39a676fd573c5cccce140a74` |
| Repaired-control app, source `37eb0ed` | `ef1ab92be63c57971cade97492f752f49e1bdff8a47104567d3d4a5d3b17c0bd` |
| Benchmark app, build only | `b2bc606af9f4a5df0ed3b689a127463e13261d42609583e937942a3de55898bf` |
| Benchmark instrumentation, build only | `f9e1b76cdbed4ca408f53359f9d3e0f2cf00c2265bb9516cb01c5ce9df3ab712` |

## Three repairs and matched controls

**Parser limits.** A 1,001-column header/separator and 1,000 short rows take only 8,008 source units but expand to 1,002,001 cells. The old parser throws before the post-parse node check. A focused old-source JVM execution records the exception with zero passes. Three old-runtime Android controls record the preview-effect exception; instrumentation can intercept an uncaught Compose exception, so these are not proof of process crashes.

The repair sets **4,096 cells per table** and translates parser `IllegalArgumentException` into an unavailable document with no partial model. A cancellation check preserves owner cancellation before that result returns. JVM cases cover the accepted 4,096-cell boundary, rejection of the next padded row and cancellation after rejection. Matched repaired Android cases show readable fallback and subsequent normal document/tag action. The 512 Ki UTF-16 source bound, post-parse AST-walk limit of 16,384 nodes and per-table limit are specific bounds, not a hard global-memory or CPU-deadline guarantee. Near-limit composite documents remain unverified.

**Object-valued metadata.** Tags now key and compare immutable rendered icon/source/mask/tint and selected adaptive-color values. Equivalent fresh JSON objects retain the current control and callback. Document, node, root, session, action handle and visibility guards remain. Actual visual changes still reject captured old callbacks.

One protocol deliberately replaces the native tree with equivalent JSON and invokes the captured semantic callback in the same UI turn before recomposition. The actual JavaScript action count establishes dispatch; this is controlled native publication, with no pointer or real-React-publication credit. The separate real React loading/title/sibling protocol waits for native and composed publication markers, verifies unchanged bindings, and checks actual focus/control identity and Enter delivery with the clipboard unchanged. Each old protocol fails on all three profiles, and all six matched repaired executions pass.

**Multiline link glyphs.** Metadata links now use an 8 dp corner radius. Natural text size, wrapping, padding and minimum 48 dp targets remain. The old rectangular bounds checks missed glyphs clipped by the pill shape. The stronger gate paints the actual resolved paragraph without ancestor clipping into a reference alpha mask, then compares leading/trailing core ink against an actual root screenshot. A one-pixel alignment neighborhood and recorded RGB tolerance handle raster placement/compositing. Unclipped title text calibrates the gate. Complete natural-line viewport witnesses through pointer swipes remain required. All three old runs pass calibration and fail link ink; all three matched repaired runs pass every recorded probe. Reference masks are not implementation screenshots.

| Common-instrumentation controls | Old `4bb10aa` runtime | Repaired `37eb0ed` runtime | Cleanup checks per side |
| --- | --- | --- | ---: |
| Compact table fallback and recovery | 3 intended failures, zero passes | 3 passes | 58 |
| Equivalent callback / actual React focus | 6 intended failures, zero passes | 6 passes | 58 |
| Multiline link edge pixels | 3 intended failures, zero passes | 3 passes | 58 |
| Total | **12 intended failures** | **12 passes** | **174** |

These controls use the identical test APK. The current runtime retains their three production repairs; its only production-code change after `37eb0ed` adds the DEBUG observed/composed readiness booleans. Its current test harness additionally changes two files for the cached-image case and its fixture. Consequently the common-control and current-matrix APKs and AndroidTest sources are distinct. Relative to the `37eb0ed` full-matrix test APK, the current test APK changes the fixture asset, `classes3.dex` and `classes4.dex`. No exact stamp-only equivalence is claimed, and the common controls are not added to current execution totals.

## Current runtime and retained image failure

Dedicated `Tinycast_Tablet_API_36`, API36 arm64, ordinary application and adbd UIDs. Profiles are tablet 1920×1200/240 dpi, portrait phone 1080×2340/420 dpi and short landscape 2340×1080/420 dpi. Detail and Markdown run at 100% and 200% text. These viewport profiles do not certify physical devices or actual split-window operation.

| Current app / current instrumentation group | Executions | Successful cohort cleanup checks | Complete cohorts |
| --- | ---: | ---: | ---: |
| Critical long-text, rendered glyph and actual IME cases | 6 | 58 | 3 |
| Native Detail, metadata, images and lifecycle; includes the new six-cycle image case | 138 | 116 | 6 |
| Owned Markdown and hidden-detail controls | 24 | 116 | 6 |
| Frozen reviewed JSON formatter | 3 | 58 | 3 |
| Frozen Mood record → Search scenario | 3 | 58 | 3 |
| Focused shared-key cases at 100%; landscape uses the explicit three-case subset | 11 | 61 | 3 |
| Total | **185** | **467** | **24** |

The 185 credited executions comprise 164 completed v2 executions retained with their original runner receipts and 21 v3 executions with the bounded runner. These use the identical app/test APKs. All 24 credited cohorts complete without failed or skipped cases; the separate transport-failed cohort is excluded. Five separate preservation checks verify temporary preservation and byte-identical restoration of 136 older evidence files. Independent clipboard recovery and guarded runner receipts cover existing installed data, preferences, secure settings, font, geometry and lease release.

The earlier `37eb0ed` pipeline is **incomplete**, with seven passing whole cohorts and a failed landscape-100% Detail cohort at **21/22**. The failed image-title case captured a blank block-image placeholder while the debug producer phase reported ready. All 20 host cleanup checks and all five outer preservation checks succeeded. This failed pipeline is not included in the 185 current executions.

Adding two read-only DEBUG booleans did not deliberately change image behavior. A separate diagnostic landscape cohort passed 22/22. A new test then observed six actual offscreen bitmap releases, retained geometry and green-pixel returns per case on landscape 100%, phone 200% and tablet 100%. Those three cases passed, with 58 cleanup checks. Neither successful protocol isolates the original failure or demonstrates a production repair. The current six-profile Detail cohorts include that new six-cycle test, covering 36 release/return transitions. Its semantic scroll positioning is setup, not pointer-navigation credit. The earlier blank-image observation remains an open rendering limitation.

The phone-200% category-containment and landscape-100% “No matching actions” empty-state failures also remain separately bound to historical old/current controls under [#91](https://github.com/moikdev/droidcast/issues/91). Their causes remain unisolated. Current shared-key coverage contains eleven cases at 100%; landscape excludes the failing whole action-search flow. This checkpoint fixes neither legacy protocol.

Frozen workflows refresh **12 executions over four named commands in three bundles**: JSON formatter three, the Mood record/Search scenario three, and HTML Colors Blue six inside Detail. JSON requires explicit reviewed Run, exact native monospace output, fresh exact Copy and unchanged owned source return. Mood checks exact date/tags/notes persistence, a fresh Search session, false/true Detail toggles and unchanged stored bytes. Blue checks actual SVG/HEX/RGB metadata, toggle, fresh exact HEX Copy and return. Archive and command identities remain separate in receipts. Other commands, configurations, providers, services and arbitrary callers remain partial or untested. Static inventory remains **3,491 directories / 11,153 untested declarations**. Store metadata does not certify source correspondence.

## Transport interruption and bounded runner

The initial `8e75546` continuation remains incomplete after its phone-200% Markdown cohort. Its four JUnit cases passed, but a read-only ADB private-path query had no timeout and stopped returning. New shell and console reads also failed. A reconnect scoped to the dedicated emulator left it offline. All **20 host cleanup checks** failed, and both recovery owners remained. No application cause is established by that transport incident, and this cohort receives no completed-cohort credit.

A verified private disk checkpoint preceded a cold boot of the same AVD with its existing userdata. The [separate nine-check recovery](validation/extension-compatibility/native-detail-audit-repair/transport-recovery/public-recovery.json) verifies the installed APK identities, recovers the four cases' raw evidence byte-for-byte, removes only registered fixture paths, restores clipboard/preferences/secure settings/font/geometry, and restores all 136 older evidence files. Owners release after verification, with the application lease last. Original failed checks remain failed. Logical installed-data fingerprints were not persisted for a cross-boot comparison; the private pre-restart disk checkpoint remains available, without a broader post-boot data-identity claim.

The Python runner now bounds that private-path query at **60 seconds**. Two isolated copies of the actual old/current function use the same delayed host client. The old probe stays blocked beyond 66 seconds and is terminated; the new probe raises `TimeoutExpired` at approximately 60 seconds without calling the absence classifier. These are host subprocess probes, not Android/JUnit executions and not a repair of the emulator or the earlier blank-image observation.

The same-APK continuation verifies the 164 earlier completed executions, then reruns the affected four cases and executes the remaining 17 using the bounded runner. Its 21 new executions complete with their own receipts and five new preservation checks. The combined table counts exactly 185 positive executions across 24 unique cohorts and 467 successful cohort cleanup checks. The failed 20 checks and separate nine-check recovery are not added as successful cohort cleanup. Original pipeline states, runner hashes and recovered evidence remain separately bound.

## Host checks, failures and publication

Retained Android checks executed **264 JVM cases with zero failures, errors or skips** at `8e75546`. Android, build and JVM-test sources match those frozen results; this runner-only change does not relabel them as fresh runs. The 652 core HTML and 580 native-model conformance comparisons remain comparisons within their JUnit methods; 72 literal-HTML cases remain excluded from semantic equivalence. The frozen debug/test and benchmark/test builds succeeded at `8e75546`. Lint records **0 errors / 55 warnings** per variant. No benchmark native execution is claimed.

The initial host repair run executed 264 cases with two failures on Android's mockable JSON methods. It remains failed. A strict parsed-value assertion now prevents equal unsupported/default values earning identity credit, and the [pinned host-only JSON provider](validation/extension-compatibility/native-detail-audit-repair/research/json-java-20240303-v1.json) supplies actual host values. Android uses platform `org.json`; class-definition inspection confirms the provider defines no application/instrumentation classes. The additional lint warning is NewerVersionAvailable for the pinned test dependency. The first glyph-harness compile failure and compiled-but-unexecuted intermediate harnesses retain no native credit.

All 89 Python cases per mode are newly executed normally and optimized at the bounded-runner commit `a2cab95`. The 25 Node contracts retain their actual earlier receipts and unchanged relevant source hashes. The isolated query-deadline probes are separate host protocols, with no UI or JUnit credit. Generated upstream runtime assets and existing AGPL/React notices remain unchanged; added licenses remain packaged.

The supplement preserves 35 actual PNG/XML/frame sets, including the failed image observation and separate diagnostics. Raw bytes and XML CRLF remain intact. Projection keys, original bindings and XML-safe host-prefix transformations are explicit. Original clipboard contents/fingerprints, private recovery snapshots/backups, signing material and signed Store metadata are excluded.

| Capture | Scope |
| --- | --- |
| [table-before-trigger-phone200](screenshots/extension-compatibility/native-detail-audit-repair/table-before-trigger-phone200.png) | Old runtime before the compact table triggers its preview-effect exception; not a screenshot of a process crash. |
| [table-old-failure-phone200](screenshots/extension-compatibility/native-detail-audit-repair/table-old-failure-phone200.png) | Old runtime after the instrumented parser exception; retained failure UI, with zero JUnit passes. |
| [table-unavailable-phone200](screenshots/extension-compatibility/native-detail-audit-repair/table-unavailable-phone200.png) | Repaired runtime rejects padded-cell expansion with a readable unavailable preview. |
| [table-recovered-phone200](screenshots/extension-compatibility/native-detail-audit-repair/table-recovered-phone200.png) | A normal document and its tag callback work after table rejection. |
| [table-unavailable-landscape200](screenshots/extension-compatibility/native-detail-audit-repair/table-unavailable-landscape200.png) | Readable table-limit outcome in the short landscape 200% profile. |
| [object-callback-before-phone200](screenshots/extension-compatibility/native-detail-audit-repair/object-callback-before-phone200.png) | Old captured callback is rejected after an equivalent controlled native JSON publication; actual JS count remains zero. |
| [object-callback-after-phone200](screenshots/extension-compatibility/native-detail-audit-repair/object-callback-after-phone200.png) | The same captured callback dispatches its actual JavaScript action after equivalent native JSON replacement; this is not pointer-action credit. |
| [object-changed-visual-phone200](screenshots/extension-compatibility/native-detail-audit-repair/object-changed-visual-phone200.png) | Old action rejects an actual visual change; a fresh pointer activation reaches the new visual owner. |
| [object-focus-before-phone200](screenshots/extension-compatibility/native-detail-audit-repair/object-focus-before-phone200.png) | Old control loses identity/focus after a witnessed unrelated real React publication. |
| [object-focus-after-phone200](screenshots/extension-compatibility/native-detail-audit-repair/object-focus-after-phone200.png) | Actual keyboard focus and control identity survive the real unrelated React publication. |
| [object-enter-after-phone200](screenshots/extension-compatibility/native-detail-audit-repair/object-enter-after-phone200.png) | Enter invokes the focused current tag; the clipboard sentinel is unchanged. |
| [object-focus-after-tablet100](screenshots/extension-compatibility/native-detail-audit-repair/object-focus-after-tablet100.png) | Real React publication retains actual focused control; initial semantic positioning is separate from keyboard activation. |
| [object-focus-after-landscape200](screenshots/extension-compatibility/native-detail-audit-repair/object-focus-after-landscape200.png) | Real React publication retains actual focused control; initial semantic positioning is separate from keyboard activation. |
| [link-glyph-before-phone200](screenshots/extension-compatibility/native-detail-audit-repair/link-glyph-before-phone200.png) | Old pill clips link-edge glyphs; the independent paragraph alpha-mask probe fails after unclipped-title calibration passes. |
| [link-glyph-after-phone200](screenshots/extension-compatibility/native-detail-audit-repair/link-glyph-after-phone200.png) | Same instrumentation verifies complete edge glyph pixels after bounded-corner repair; normal viewport scrolling remains visible. |
| [link-glyph-before-landscape200](screenshots/extension-compatibility/native-detail-audit-repair/link-glyph-before-landscape200.png) | Old pill clips link-edge glyphs; the independent paragraph alpha-mask probe fails after unclipped-title calibration passes. |
| [link-glyph-after-landscape200](screenshots/extension-compatibility/native-detail-audit-repair/link-glyph-after-landscape200.png) | Same instrumentation verifies complete edge glyph pixels after bounded-corner repair; normal viewport scrolling remains visible. |
| [current-link-glyph-tablet100](screenshots/extension-compatibility/native-detail-audit-repair/current-link-glyph-tablet100.png) | Retained v2 same-APK cohort: all natural link lines and their leading/trailing glyph pixels are witnessed through pointer scrolling. |
| [current-link-glyph-tablet200](screenshots/extension-compatibility/native-detail-audit-repair/current-link-glyph-tablet200.png) | Retained v2 same-APK cohort: all natural link lines and their leading/trailing glyph pixels are witnessed through pointer scrolling. |
| [current-link-glyph-phone100](screenshots/extension-compatibility/native-detail-audit-repair/current-link-glyph-phone100.png) | Retained v2 same-APK cohort: all natural link lines and their leading/trailing glyph pixels are witnessed through pointer scrolling. |
| [current-link-glyph-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-link-glyph-phone200.png) | Retained v2 same-APK cohort: all natural link lines and their leading/trailing glyph pixels are witnessed through pointer scrolling. |
| [current-link-glyph-landscape100](screenshots/extension-compatibility/native-detail-audit-repair/current-link-glyph-landscape100.png) | Retained v2 same-APK cohort: all natural link lines and their leading/trailing glyph pixels are witnessed through pointer scrolling. |
| [current-link-glyph-landscape200](screenshots/extension-compatibility/native-detail-audit-repair/current-link-glyph-landscape200.png) | Retained v2 same-APK cohort: all natural link lines and their leading/trailing glyph pixels are witnessed through pointer scrolling. |
| [current-table-unavailable-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-table-unavailable-phone200.png) | Retained v2 same-APK cohort: table allocation failure remains readable. |
| [current-object-focus-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-object-focus-phone200.png) | Retained v2 same-APK cohort: the same focused tag survives real React publication. |
| [current-image-error-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-image-error-phone200.png) | Retained v2 same-APK cohort: fresh current image failure has a readable alternative. |
| [current-html-colors-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-html-colors-phone200.png) | Retained v2 same-APK cohort: frozen unmodified Blue command: current native metadata; Copy/return outcomes are bound separately. |
| [current-json-code-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-json-code-phone200.png) | Frozen formatter after reviewed explicit Run: exact native monospace output. This also serves as a real app smoke capture after the host-query timeout change; it establishes no repair of the transport or prior image failure. |
| [current-json-source-return-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-json-source-return-phone200.png) | Fresh exact Copy returns to unchanged owned external source text/range. |
| [current-mood-native-detail-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-mood-native-detail-phone200.png) | Frozen Mood persisted owned notes render in current native Detail after actual Show Details. |
| [image-readiness-failure-landscape100](screenshots/extension-compatibility/native-detail-audit-repair/image-readiness-failure-landscape100.png) | Retained earlier21/22 failed cohort: actual blank block image while the debug loader phase reports ready. Cause and repair are not established. |
| [diagnostic-image-information-landscape100](screenshots/extension-compatibility/native-detail-audit-repair/diagnostic-image-information-landscape100.png) | Separate unchanged-behavior diagnostic22/22 run shows the image/caption; this successful rerun establishes no cause or repair. |
| [current-cached-image-tablet100](screenshots/extension-compatibility/native-detail-audit-repair/current-cached-image-tablet100.png) | Retained v2 same-APK cohort: current test observes six offscreen releases, stable geometry and returned green pixels. Semantic scroll positioning is setup; this does not explain the earlier failure. |
| [current-cached-image-phone200](screenshots/extension-compatibility/native-detail-audit-repair/current-cached-image-phone200.png) | Retained v2 same-APK cohort: current test observes six offscreen releases, stable geometry and returned green pixels. Semantic scroll positioning is setup; this does not explain the earlier failure. |
| [current-cached-image-landscape200](screenshots/extension-compatibility/native-detail-audit-repair/current-cached-image-landscape200.png) | Retained v2 same-APK cohort: current test observes six offscreen releases, stable geometry and returned green pixels. Semantic scroll positioning is setup; this does not explain the earlier failure. |

Math, hover-link titles, unsupported image/SVG/icon surfaces, every near-cap document shape, complete Forms/Store parity, arbitrary providers/callers, physical/OEM/API/WebView and full accessibility remain open. Note preview/portability is #10. Production signing/migration and project association stay separate. This is scoped repair evidence, not full parity or production-readiness clearance.
