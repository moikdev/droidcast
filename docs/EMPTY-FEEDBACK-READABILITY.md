# Readable empty feedback above the keyboard

Scope: [#111](https://github.com/moikdev/droidcast/issues/111), following the audited Clear controls in [PR #112](https://github.com/moikdev/droidcast/pull/112). The launcher and Emoji now place a primary empty-result message directly below the header when the palette is shorter than 180 dp. Compact empty views omit the decorative search illustration and duplicate helper/count/keyboard hints; normal viewports retain them. Existing footer controls remain reachable by pointer scrolling.

The status fonts remain **14 sp launcher / 13 sp Emoji** with the actual requested 100% / 200% system font scale. Compact leading is 18 sp / 16 sp instead of the inherited 24 sp. A very short empty palette uses a 4 dp bottom gap instead of 12 dp, gaining 21 px on the tested 420 dpi display. Its top and search origin remain unchanged. Other palette routes use the default spacing. Header, key, selection, cancellation, ownership and focus handlers are unchanged.

## Frozen sources and binaries

| Role | Source | Debug app SHA-256 | Debug test SHA-256 |
| --- | --- | --- | --- |
| Reviewed pre-repair runtime | `5e7cb6b6e0bc146a9e78085f844594afe4d5e1be` | `974b2faadb54b23d5f2d259371943379f7b372c7ad14651f898ee0a82959d750` | Each matched cohort uses the final common test below |
| First candidate | `15eb95c1c8d91611d5cd099989bf67eaaf5ecaf3` | `3c7860c837a7085a383ef7baaadcbd5195213ff34586955838c86477e72cf292` | `6555d4beb6096a8a6b59a78c778b704d1cf4e3a82fb01d7fe9f0b303488976b9` |
| Leading/clearance candidate | `2b0cbb6144065899a809ba3d9b7245cb3498a39f` | `d079a24f1618f6562ffa3b712fae6aab5601b317fde243173a0d64f2b4ddb025` | `e28ed7e9f9439b56d04a94420095e72e43ff188a47c98a446acdce6ede1426f5` |
| Final compact status | `6c5825bf0da60ad9b452ec4550aa49d60704e677` | `66a6965e04e37570e17637e5887b03857ceb26810adc5171096619f9e53c524c` | `72dc12a358c57c33b35d92439203b65e968ef4250801c6964ee008b6af4d51d2` |

[The final identity](validation/empty-feedback/identities/candidate-v3/identity.json) binds 254 Android/build/Python inputs. Later evidence/documentation commits do not change those sources. The app manifest embeds the corresponding source commit; the test APK has no independent source stamp. Instrumentation identities rest on compilation receipts, source vectors and APK hashes. Historical APK/DEX differences are retained; no stamp-only binary equivalence is inferred. The runner and all Python inputs are unchanged after `15eb95c`; their 135-per-mode receipts retain that source. All 11 original packaged assets/notices remain byte-identical, including upstream runtime assets.

## Completed current verification

[The summary](validation/empty-feedback/summary.json) credits **25 final positives / 12 whole cohorts / 186 successful restoration checks** on the final app/test pair:

- The two feedback methods run API 36 on the owned `Tinycast_Tablet_API_36`: short landscape 2340×1080 / 420 dpi, phone 1080×2340 / 420 dpi and tablet 1920×1200 / 240 dpi, each at 100% and 200%. They cover keyboard hidden/shown, an exact owned no-match query, zero results, complete primary text lines, fixed font sizes, no ellipsizing, full nonoverlapping 48 dp header controls, useful editor width, pointer reachability of the footer, unchanged query/range/focus after panning back, stable search origin across IME opening, matching query, actual pointer Clear and the restored empty query/results. Compact cases additionally require helper/count absence.
- Three normal-tablet guard/composition cases retain controlled Japanese composing-underline clearing, whitespace, live-query and stale/disposed/menu/recreation/epoch ownership contracts.
- Existing Clear pointer cases run on tablet/phone at 200%; two unchanged owned-silent-IME native-input contracts run on modern phone and API 26.
- API 26 `Droidcast_WebView_API26_27caf3`, 1080×1920 / 420 dpi, 100% runs both feedback methods as normal-viewport native smoke. This is not old-WebView extension execution parity.

At the critical 200% landscape capture, the complete launcher line uses 14 sp / 18 sp leading and Emoji 13 sp / 16 sp leading. Both lines fit the expanded 239 px palette above the actual system keyboard; the old palette was 218 px. The full line bounds and actual PNGs agree. Footer captures show a later pointer-scrolled state with the header outside the viewport, followed by a pointer return to its original scroll origin. They do not claim simultaneous visibility of all chrome.

Primary text probes use actual `TextLayoutResult` line extents and clipped palette/node geometry, not merely text-node existence or the paragraph-container overflow diagnostic. Every line must fit, retain its declared point size and remain non-ellipsized. The viewport/IME geometry must settle for 300 ms. Actual Android PNGs independently expose the clipping in controls and the final glyphs. Capture XML is written before PNG after Compose/UiAutomator idle; hierarchy bounds are diagnostic, not independent pixel/visibility proof.

The **8 final control failures / zero passes / 68 restoration checks** use the exact same final test APK as repaired runs. The original runtime fails both primary-line assertions at 100% / 200%; the first candidate fails them at 200%. The leading/clearance candidate at 100% passes primary-line visibility but fails the new compact helper/count absence assertions. These are failed positive-only runner receipts, with [separate calibration](validation/empty-feedback/final-controls-calibration.json). Control screenshots show ordinary app states and clipping, not an app error banner.

## Retained intermediate outcomes

All retained native attempts reconcile to **59 executions / 39 passes / 20 failures / 471 successful cohort restoration checks**. Historical passes receive no final-runtime credit. Initial old100/200 controls remain 0/2 each. The first candidate passed 2/2 at 100% but failed 0/2 at 200%; moving the message alone did not provide sufficient natural line height.

The second candidate has six primary cohorts of 2/2 under its earlier harness, and three old/intermediate controls of 0/2. Its actual 100% images reveal the clipped secondary helper/count. Root paused only the host orchestration process, let the in-flight phone200 runner complete 2/2 and all 17 restoration checks, verified no child/instrumentation remained and all four IME fields still matched the initial baseline, then terminated the host parent. The original registry remains incomplete and its last row was not finalized; the separate complete phone200 receipt and seven-check [boundary record](validation/empty-feedback/orchestration-stop-v2.json) remain explicit. Later planned guards/regressions were not run. No device runner was interrupted, no failed test was promoted and no app repair is credited to this host stop.

The final runtime removes the duplicate compact line. Its strengthened absence assertions and common-runtime controls remain distinct from the earlier harnesses and their broader passing criteria.

## Preservation and host checks

The owned runner preserves the original installed APKs, all existing app/evidence files, palette preferences, geometry and raw font setting before releasing its nonce lease. The explicitly owned IME regression restores four raw IME fields before stopping/uninstalling the test APK. The existing strict private archive transport remains create-only/mode 600, validates before promotion and retains partials on failure. This slice changes only the feedback-case allowlist in the runner.

All **14 separate final read-only preservation checks** pass, including both original modern APKs, all 573 original modern files/evidence, legacy package absence, font/geometry and both devices' current four-field IME baselines. The first read-only API 26 baseline preflight used an unavailable boot-name property and wrote no snapshot; the emulator-console identity check succeeded before any native mutation. Private originals, owner tokens, raw settings and their fingerprints are excluded from publication. Whole-system/cross-boot identity and the earlier original pre-probe IME history remain uncertified.

Final debug/test and benchmark/test builds succeed. Lint records 0 errors / 56 warnings per variant. Retained XML records 343 JVM cases without failure/error/skip; the final host log records that the JVM task executed. Python 135 per normal/optimized mode passed at `15eb95c`; all Python bytes remain unchanged at final source. These source-reuse boundaries are separate from actual exact-head hosted Android CI and the two required independent sol/high audits.

## Evidence and limits

[The checkpoint](validation/empty-feedback/checkpoint.json) binds 478 indexed public files plus itself, 1121 retained capture hashes and **84 selected actual PNG/XML/frame sets**, exact original/public transformations and allowlisted runner projections. Other raw captures remain hash-listed. Original hierarchy CRLF bytes are preserved; all published XML parses, including escaped host-path redactions in lint/JVM XML. Private inputs and signing material are excluded.

Editor replacement, IME visibility requests and native InputConnection setup are controlled; pointer claims cover the recorded pans/Clear actions. Matching/cleared captures establish functional state, not complete result-row/grid typography or targets. Controlled composition is a normal-profile regression, not certification of composition through every system-IME transition. API 26 composition/service behavior remains open in #69; legacy emoji glyphs and broader typography/forms/accessibility remain under #71. Files-toolbar variants, actual split-window/OEM/physical typing/TalkBack, arbitrary extension callers, whole Store/Raycast parity, #89 and production signing/migration are not cleared. No project rename/migration occurs.
