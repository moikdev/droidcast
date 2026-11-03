# Native Markdown, metadata and adaptive Detail

[#89](https://github.com/moikdev/droidcast/issues/89), under #1/#6 and the shared rendering foundation for #10, replaces literal Markdown with native readable content and renders structured metadata. List detail now follows `isShowingDetail`: false or omitted stays hidden; true uses the selected row, with useful wide panes or a reversible full-width touch destination. Query, selection, live action scope, exact Copy and caller return remain covered.

The local checkpoint is bound to implementation `4bb10aa67571f456c3943d6357374c55ab06e4c1` and instrumentation source `d022f3188b65eda4350a0b72f2feba41970d4ee6`. Documentation commits do not relabel binaries. The [checkpoint](validation/extension-compatibility/native-detail/checkpoint.json) binds current sources, binaries, individual cohorts, retained failures and raw evidence. Actual exact-head hosted CI, independent audits and expected-head merge receipts are recorded on the implementation PR.

| Binary | SHA-256 |
| --- | --- |
| Debug app | `7e99368a80208eb065eed0daf55f174d5fffbe0f72331b918901675de6f07640` |
| Debug instrumentation | `e14e449bdf09711a1b25f3276aa3f5ced078b9abf49f2e4e01e67d01d428f2aa` |
| Benchmark app, build-only | `0ba8b4044d02c50eed9a7d37c70a0947c638911d05c3a4d19c724f2b2d530510` |
| Benchmark instrumentation, build-only | `9ef77e72483c01f6930dabbc1b9f3ac306726104fc7ce9ef1536bacf62fa7468` |

## Native behavior

- Headings, emphasis, nested bold/italic, strike, lists/ordered starts, quotations, breaks, escaping, Unicode/combining text, exact inline/fenced code and GFM tables render natively. Code and wide tables scroll horizontally. Literal HTML is selectable source; it is never executed as browser markup.
- Metadata supports labels, optional supported icons/colored values, links, separators and wrapping tag actions. Empty values show Not provided, Link unavailable or No tags. Current callbacks revalidate document, selected row, visibility, root and runtime ownership; closure, recreation, replacement and captured old callbacks are exercised.
- Package, data and HTTPS image paths use bounded static rendering with alternatives. Package resolution rejects traversal, siblings and symlink escapes. Supported size/tint hints include both documented tint spellings. The loader limits input/decode/cache/queue work and uses a 15-second whole-job deadline in addition to per-connection/read limits. Owner cancellation remains cancellation; deadline cancellation becomes an image failure.
- Block captions and persistent, scrollable image information give hover titles a touch equivalent. Explicit Close and document replacement have separate ownership. Opening information releases document selection handles. Offscreen views clear their decoded view reference while preserving layout geometry; the bounded global cache may retain the bitmap. Returning rechecks visibility and restores output pixels.
- Native focused controls keep Enter. Frame/search navigation retains live List selection and composition rules. An explicit Hide keyboard action makes constrained extension search routes easier to use; the existing outer scroll fallback remains. No claim is made that every footer is immediately visible above an arbitrarily tall IME.

Image visibility is sampled after source/lifecycle and position changes, coalescing a pending placement callback rather than continuously polling. A DEBUG-only read-only semantic probe records producer phase, boolean gates and geometry, with no URL/content and no self-triggered recomposition. It supplies diagnostic evidence, not a user-facing error detail.

## Grammar and local checks

CommonMark 0.32.0 and AndroidSVG 1.4 artifact identities are pinned in the [research records](validation/extension-compatibility/native-detail/research). Generated upstream runtime assets and existing AGPL/React notices are unchanged. Added license/notice assets are packaged with the app.

The primary CommonMark 0.31.2 corpus has **652 exact core HTML comparisons** and **580 independent native-model semantic comparisons**. The remaining **72 literal-HTML cases** have a deliberately different native policy and are excluded from semantic equivalence. These are host grammar/model comparisons, not 652 Android visual tests. The committed TSV and notice retain source attribution and a pinned digest.

The implementation build executed **258 JVM cases**, with zero failures/errors/skips, and built debug/test successfully. The final instrumentation compiles against unchanged production/JVM sources. Current debug and benchmark lint both have **0 errors/54 warnings**; benchmark app/test builds succeed. The **89 Python cases normally and optimized** and **25 Node contracts** retain their original execution identities; their corresponding scripts, test sources and Android adapter asset match current bytes and were not needlessly rerun for Kotlin-only repairs. Local checks and hosted CI remain separate.

## Completed Android scope

Dedicated `Tinycast_Tablet_API_36`, API36 arm64, ordinary app/adbd UIDs. Tablet 1920×1200/240 dpi, portrait phone 1080×2340/420 dpi and short landscape 2340×1080/420 dpi are tested at both 100% and 200% text. These are viewport profiles, not physical devices or actual Android split-window certification. Actual IME open/closed is exercised where relevant; natural-line witnesses, pointer scrolling and semantic setup are labeled separately.

| Current immutable app/instrumentation group | Executions | Cleanup checks | Whole cohorts |
| --- | ---: | ---: | ---: |
| Critical long-text / actual IME touch checks | 6 | 58 | 3 |
| Native Detail, metadata, images, lifecycle; includes frozen HTML Colors | 114 | 116 | 6 |
| Owned core Markdown / hidden-detail controls | 24 | 116 | 6 |
| Frozen reviewed JSON formatter | 3 | 58 | 3 |
| Frozen Mood record → Search scenario | 3 | 58 | 3 |
| Focused shared keyboard / menu regressions (100%; landscape three-case subset) | 11 | 61 | 3 |
| Total | **161** | **467** | **24** |

No skips/failures earn current credit. Host restoration independently verifies clipboard recovery, preferences, secure settings, font, geometry, fixture removal and ownership before lease release. Existing installed extensions/data are preserved. Private backups, original clipboard contents/fingerprints, recovery snapshots and signed Store metadata are excluded from publication.

The final accounting retains **150 completed executions from v7**, **four tablet shared-key executions from v8**, **four phone shared-key executions from v9**, and **three landscape shared-key executions from v10**, with the same immutable app/test APKs. All shared-key cohorts are at **100% text**; the Detail/Markdown groups retain all six 100%/200% profiles. V7 itself remains incomplete: its keyboard cohort stopped at a no-mutation preflight because two older evidence directories were present. Those **136 pre-existing files** were privately copied, temporarily preserved and restored byte-for-byte; **five separate preservation checks** accompany the resumed cohort. No test or app repair is claimed for that preflight failure, and all three incomplete controllers are retained separately.

The additional phone-200% legacy keyboard cohort fails **3/4** on the category-menu frame-containment assertion. The frozen pre-change app fails the **same assertion, 0/1**, using identical instrumentation; `PaletteUi.kt` and `PaletteMenus.kt` have byte-identical source across those runtimes. Neither failed cohort earns current credit. The [boundary receipt](validation/extension-compatibility/native-detail/pipelines/category-menu-boundary-v1.json) retains these identities. The assertion's cause remains unisolated under [#71](https://github.com/moikdev/droidcast/issues/71); no menu-layout or harness fix is claimed. Each runner intentionally retains its lease after an incomplete legacy cohort; separate manual receipts verify fixture teardown, preferences/settings, independently restored clipboard and final release. This does not turn either cohort's failed cleanup checks into passes.

A separate landscape-100% action-search assertion also fails on both runtimes: current **3/4**, old **0/1**, at `InlinePaletteDeviceTest.kt:370` (`inline-menu-empty`, the “No matching actions” empty state, not displayed). The [second boundary receipt](validation/extension-compatibility/native-detail/pipelines/action-search-boundary-v1.json) retains the identical instrumentation and unchanged root sources. Those whole failed cohorts also earn no current credit; independently verified restoration is separate. The final landscape keyboard cohort runs the three other cases explicitly. This leaves **eleven** current shared-key executions across three completed cohorts, with no claim that the two legacy flows are repaired. Both investigations stay under #71.

Version 1 mislabeled line 370 as the later Copy content row. This metadata correction identifies the actual empty-state assertion; original test logs, failed outcomes, recovery receipts, source identities and raw captures remain unchanged. The first publication remains available at commit `fd2a780`.

Long paragraphs, unbroken URLs, metadata, code, tables, image descriptions and the error/destination surfaces retain natural size and complete line reachability where asserted. Paragraph/long-text witnesses use actual pointer sweeps; code/table final-column reveal and offscreen-image positioning also use explicitly recorded semantic setup. The offscreen image test checks the view-result state, stable height and returned green pixels; it does not claim eviction of the shared bounded cache.

## Frozen real workflows

These are **12 workflow executions over four named commands in three frozen bundles**: JSON 3, a two-command Mood scenario 3, and HTML Colors 6 inside the Detail group. They are not twelve blanket extension compatibility passes. Original batches and the intermediate 981 runtime observations keep their own identities.

| Frozen command | Current listed workflow | Remaining scope |
| --- | --- | --- |
| `json-format/formatSelectionAndShow` | Review owned external text → choose → explicit Run → exact native monospace JSON without fences → fresh exact Copy → unchanged source text/range and return. | Arbitrary callers, source replacement, all formatter commands/configurations and provider workflows are not certified. |
| `mood/record-mood` + `mood/search-moods` | Actual typed date/tag/notes edit, one exact persisted owned entry, fresh Search, false → true → false → true detail toggles, native notes, unchanged bytes and touch return. | Other commands/actions, AI, charts, drafts and storeValue remain untested. |
| `html-colors/html-color` | Default Blue search/select, real blue SVG, HEX/RGB metadata, Show/Hide Details, fresh exact `#0000FF` Copy and query/caller return. | Dropdown/accessory modes, other colors/actions and full command parity remain partial. No service request is exercised. |

Archives: JSON `fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58`; Mood `46bd1d6f6598b3ecab43d6eac9778a8b4aba7e3d609859f90feceaf391b9b221`; HTML Colors `8a346fd2bd4ed01ceeacdeb71462b458014faa539a1f2b563638983843bdf0fb`. Command hashes are retained in the per-case receipts and workflow records. Store-reported metadata is not certification of correspondence to the pinned source inventory. **3,491 directories / 11,153 declarations remain statically untested.**

## Matched controls and retained failures

Identical test APKs are used within each pair, with distinct runtime identities. The [pipeline receipts](validation/extension-compatibility/native-detail/pipelines) preserve exact source/APK/fixture bindings; historical cohorts are not added to the current total.

| Contract | Prior runtime | Repaired runtime at that checkpoint |
| --- | --- | --- |
| Core rendering/metadata/false-or-omitted detail | 12 expected negatives, zero JUnit passes | 12 passes, shared `bea5e14a…` test APK |
| Link glyph safe area | 3 expected negatives | 3 passes, shared `ea5af23d…` |
| Numeric entity LF paragraph flow | 3 expected negatives | 3 entity passes plus six separately identified image/link passes, shared `539e1662…` |
| Frozen JSON native code | 3 expected negatives | 3 JSON passes; Mood/HTML observations stay separate, shared `7805538e…` |
| Empty metadata / image title information | 6 expected negatives | 6 passes, shared `03a9f6aa…` |
| Focused metadata Enter | 3 expected negatives | 3 passes, shared `4b7ccf88…` |
| Explicit keyboard hide / actual selection handles | 6 expected negatives | 6 passes, shared `f8e9d5dc…` |
| Offscreen block layout footprint / returned pixels | 3 expected negatives | 3 passes, shared current `e14e449bdf09…` |

Earlier failed/ambiguous observations remain failed: a model test's terminal-newline expectation; a missing-import compile; first-landscape code visibility (11/12) through a horizontal-only ScrollTo owner; XML attribute normalization in the conformance projector; a short-link center tap on blank space; and a cross-window selection registrar crash in the first information popup. Those harness corrections or product repairs are individually identified and are not silently credited to older APKs.

The initial requirement that a footer be immediately visible above the shortest 200% IME was an invalid test assumption about an existing scroll fallback. A later selection-focus probe also included legitimate palette-frame focus; it was replaced by an actual selection-handle UI witness. A diagnostic asset-path check looked outside the assets directory and earns no asset-integrity credit. The first geometry-positive phone run raced image-node publication before parsing completed; the final helper adds a presence barrier without changing readiness/pixel assertions.

Intermittent placed-image loading failures at earlier binaries remain retained, including the 17/18 tablet cohorts. A separate same-D7 18/18 diagnostic positive at 22e7 was an intermediate observation, not final clearance; later failures prompted preserved placeholder geometry and per-position resampling. The 22e7 final pipeline was deliberately stopped only after its in-flight 18/18 host cohort and all cleanup completed, to add the whole-job deadline. No device runner was signaled. The [boundary receipt](validation/extension-compatibility/native-detail/pipelines/deadline-boundary-v1.json) records that distinction. Prepared/compiled but unexecuted harnesses earn no native credit.

## Evidence and remaining boundaries

The following are raw Android PNGs with paired XML/frame receipts. Raw bytes, including XML CRLF where present, are preserved. The publication manifest binds originals, explicit host-path redactions, projected case/host fields and every public evidence file. Lint XML remains parseable after XML-safe redaction. The PR embeds commit-pinned image URLs; screenshots here use stable repository-relative links.

| Real capture | Meaning |
| --- | --- |
| [raw-markdown-before-phone200](screenshots/extension-compatibility/native-detail/raw-markdown-before-phone200.png) | Old app: literal heading/emphasis/fences, owned fixture. |
| [native-markdown-after-phone200](screenshots/extension-compatibility/native-detail/native-markdown-after-phone200.png) | Current app: native heading, emphasis, exact selectable code and Unicode. |
| [missing-metadata-before-phone200](screenshots/extension-compatibility/native-detail/missing-metadata-before-phone200.png) | Old app does not render the supplied owned metadata. |
| [metadata-action-after-phone200](screenshots/extension-compatibility/native-detail/metadata-action-after-phone200.png) | Current app: native metadata and actual pointer tag activation. |
| [hidden-detail-before-phone200](screenshots/extension-compatibility/native-detail/hidden-detail-before-phone200.png) | Parent isShowingDetail false; old violation. |
| [hidden-detail-after-phone200](screenshots/extension-compatibility/native-detail/hidden-detail-after-phone200.png) | Parent isShowingDetail false; current hidden-detail behavior. |
| [selected-detail-tablet100](screenshots/extension-compatibility/native-detail/selected-detail-tablet100.png) | Query-filtered beta selection with the current wide/narrow touch layout. |
| [selected-detail-phone200](screenshots/extension-compatibility/native-detail/selected-detail-phone200.png) | Query-filtered beta selection with the current wide/narrow touch layout. |
| [keyboard-hide-before-landscape200](screenshots/extension-compatibility/native-detail/keyboard-hide-before-landscape200.png) | Prior app: actual short-landscape IME; no explicit Hide keyboard button. Existing outer route scroll fallback remains available. |
| [keyboard-hide-after-landscape200](screenshots/extension-compatibility/native-detail/keyboard-hide-after-landscape200.png) | Current app after actual Hide keyboard pointer tap; primary action reachable. |
| [list-ime-open-tablet100](screenshots/extension-compatibility/native-detail/list-ime-open-tablet100.png) | Real tablet IME open; selected Detail and action above it. |
| [long-information-tablet100](screenshots/extension-compatibility/native-detail/long-information-tablet100.png) | Current profile: persistent long image description; every natural line and Close reached by pointer scrolling. |
| [long-information-tablet200](screenshots/extension-compatibility/native-detail/long-information-tablet200.png) | Current profile: persistent long image description; every natural line and Close reached by pointer scrolling. |
| [long-information-phone100](screenshots/extension-compatibility/native-detail/long-information-phone100.png) | Current profile: persistent long image description; every natural line and Close reached by pointer scrolling. |
| [long-information-phone200](screenshots/extension-compatibility/native-detail/long-information-phone200.png) | Current profile: persistent long image description; every natural line and Close reached by pointer scrolling. |
| [long-information-landscape100](screenshots/extension-compatibility/native-detail/long-information-landscape100.png) | Current profile: persistent long image description; every natural line and Close reached by pointer scrolling. |
| [long-information-landscape200](screenshots/extension-compatibility/native-detail/long-information-landscape200.png) | Current profile: persistent long image description; every natural line and Close reached by pointer scrolling. |
| [long-url-phone200](screenshots/extension-compatibility/native-detail/long-url-phone200.png) | Long unbroken URL rendered without shrinking; complete natural-line pointer witnesses in the receipt. |
| [long-url-landscape200](screenshots/extension-compatibility/native-detail/long-url-landscape200.png) | Long unbroken URL rendered without shrinking; complete natural-line pointer witnesses in the receipt. |
| [empty-metadata-after-phone200](screenshots/extension-compatibility/native-detail/empty-metadata-after-phone200.png) | Not provided, disabled Link unavailable and No tags. |
| [image-title-before-phone200](screenshots/extension-compatibility/native-detail/image-title-before-phone200.png) | Intermediate981 renderer: image title has no touch-readable equivalent. |
| [image-title-after-phone200](screenshots/extension-compatibility/native-detail/image-title-after-phone200.png) | Current native block image caption. |
| [inline-information-after-phone200](screenshots/extension-compatibility/native-detail/inline-information-after-phone200.png) | Actual inline-image long press; readable persistent information and explicit Close. |
| [selection-handles-before-tablet100](screenshots/extension-compatibility/native-detail/selection-handles-before-tablet100.png) | Prior5e95 app: actual selection-handle UI remains while image information is open. |
| [selection-handles-after-tablet100](screenshots/extension-compatibility/native-detail/selection-handles-after-tablet100.png) | Current image information with no document selection handles; palette-frame focus is allowed. |
| [detail-loading-phone200](screenshots/extension-compatibility/native-detail/detail-loading-phone200.png) | Current owned loading publication. |
| [detail-empty-phone200](screenshots/extension-compatibility/native-detail/detail-empty-phone200.png) | Current owned empty publication. |
| [image-error-phone200](screenshots/extension-compatibility/native-detail/image-error-phone200.png) | Rejected image path with readable alternative and Image unavailable. |
| [unsupported-link-phone200](screenshots/extension-compatibility/native-detail/unsupported-link-phone200.png) | Explicit unsupported-scheme activation reports an outcome; no script execution. |
| [table-image-tablet200](screenshots/extension-compatibility/native-detail/table-image-tablet200.png) | Native image-only table cell and neighboring text. |
| [code-pointer-landscape200](screenshots/extension-compatibility/native-detail/code-pointer-landscape200.png) | Actual horizontal pointer scrolling of exact native code. |
| [table-final-column-landscape200](screenshots/extension-compatibility/native-detail/table-final-column-landscape200.png) | Final GFM table column; pointer overflow probe and explicit semantic final-column reveal are separately recorded. |
| [keyboard-metadata-after-phone200](screenshots/extension-compatibility/native-detail/keyboard-metadata-after-phone200.png) | Actual Tab focus/Enter activates the metadata action, preserving the clipboard sentinel. |
| [html-colors-svg-tablet100](screenshots/extension-compatibility/native-detail/html-colors-svg-tablet100.png) | Frozen HTML Colors default Blue SVG; owned namespace, unmodified bundle. |
| [html-colors-metadata-phone200](screenshots/extension-compatibility/native-detail/html-colors-metadata-phone200.png) | Frozen Blue RGB metadata; exact HEX Copy/return recorded separately. |
| [json-native-code-phone200](screenshots/extension-compatibility/native-detail/json-native-code-phone200.png) | Frozen formatter after reviewed explicit Run: exact native code, no raw fences. |
| [json-copy-source-return-phone200](screenshots/extension-compatibility/native-detail/json-copy-source-return-phone200.png) | Fresh exact Copy and return to unchanged owned external source selection. |
| [mood-hidden-phone200](screenshots/extension-compatibility/native-detail/mood-hidden-phone200.png) | Fresh frozen Mood Search session starts with detail false and no note body. |
| [mood-native-detail-phone200](screenshots/extension-compatibility/native-detail/mood-native-detail-phone200.png) | Actual Show Details action renders the exact persisted owned notes; bytes remain unchanged. |
| [mood-hidden-again-phone200](screenshots/extension-compatibility/native-detail/mood-hidden-again-phone200.png) | Actual Hide Details action removes the detail; later reopen/return are asserted. |
| [json-raw-before-phone200](screenshots/extension-compatibility/native-detail/json-raw-before-phone200.png) | Old app: frozen formatter output still contains literal Markdown fences; exact owned input. |
| [block-geometry-before-landscape200](screenshots/extension-compatibility/native-detail/block-geometry-before-landscape200.png) | Old offscreen view intentionally absent: native receipt measures the shrunken placeholder. Semantic scroll setup, not a pointer-only claim. |
| [block-return-after-landscape200](screenshots/extension-compatibility/native-detail/block-return-after-landscape200.png) | Repaired block view returns with its original layout footprint and exact green output pixels; bounded global image cache may retain bytes. |

Math rendering, link-hover titles and the remaining image/icon/format surface stay under #6. The SVG allowlist excludes SVG text/fonts, external resources, animation and several complex features; raster animation is not certified. Remote HTTPS/slow-stream/OEM behavior and near-cap individual text/code/table stress are not device-certified. Full TalkBack, physical/OEM/API/WebView coverage, complete Forms/Store parity, provider transformations, note preview/import/export under #10 and production signing/migration remain open. No full parity or production-readiness claim follows from this checkpoint.
