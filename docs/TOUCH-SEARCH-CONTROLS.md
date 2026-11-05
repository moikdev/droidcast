# Touch Clear search for launcher and Emoji

Scope: [#110](https://github.com/moikdev/droidcast/issues/110). Both search headers now show an explicit X for every nonempty query, including whitespace. Tapping its 48 dp target clears text, selection and composition while keeping the route, category, already-focused editor and current keyboard visibility. The empty header keeps the same reserved slot, so clearing does not resize the editor. Clear has no launch, Copy or external-return action.

`SearchClearButton` rejects disposed owners; each caller also checks its current route, focus epoch, window, review/backup/recording context and menu ownership. The current callback reads the live query. Old callbacks are exercised after route replacement, menu coverage, activity recreation and an epoch change. These are controlled callback invocations, not queued physical gestures.

Emoji's category button retains natural scaled text and a minimum 48 dp target, with vertical content padding reduced from 8 to 4 dp. This removes the observed six-pixel search-origin shift on returning to the launcher at 200%; it does not reduce the font. Before/after control receipts use the same final instrumentation.

## Frozen identities

| Role | Source | SHA-256 |
| --- | --- | --- |
| Pre-Clear app | `fec0a4bf1155c08c141606f92ae8fb1feab4bba0` | `ae03c859cbdb5177796b6ed559a8db20b210d2919b2f5c9e4bf5d4694ac4ec71` |
| Intermediate app, old category padding | `07a2de6df0403ed15b03d3712cdd7724b4946f10` | `e143a921f4adb69551ad327ee252ffb7008b207ae1b814542f980dd8c17a7a34` |
| Final production app | `5e7cb6b6e0bc146a9e78085f844594afe4d5e1be` | `974b2faadb54b23d5f2d259371943379f7b372c7ad14651f898ee0a82959d750` |
| Final debug instrumentation | `ca17e6665ee5fd06c09a12f11abbfda8eb77a2fb` | `54a86c606faa86f45c6e467631d90df72462e7d4b72e1f28a9a531c032e366e1` |
| Runner/controller | `fed57fb02b52f660d4c35f216e5e227487235c50` | Bound by the final source manifest and each runner receipt |

[Final identity](validation/touch-search/identities/final-harness-v7/identity.json) binds 254 Android/build/Python inputs. Production/build/JVM sources remain equal to `5e7cb6b`; Python sources remain equal to `fed57fb`. Later commits publish evidence and documentation. The application manifest embeds `5e7cb6b`; the test APK has no independent source stamp. Its identity rests on the retained compilation/source vector and immutable APK hash, not an inferred stamp-only equivalence.

Here v7 names the final instrumentation build; v8/v9 name continuation registries, not new APKs.

Earlier instrumentation is distinct: `f79e5337…112a6` binds `07a2de6`, diagnostic `700348aa…c8bc` binds `d91dda7`, and the unused separately compiled `9e80a401…9d431` is retained without execution credit. It differs from F79 in both `classes3.dex` and `classes4.dex`. The final instrumentation is not relabeled as either earlier binary. All eleven original packaged assets/notices remain byte-identical across the app cohorts; upstream runtime assets are unchanged.

## Completed current verification

[Machine-readable accounting](validation/touch-search/summary.json) credits **25 positives / 11 whole cohorts / 171 successful cohort restoration checks** on the final app/test pair:

- API36, `Tinycast_Tablet_API_36`: tablet 1920×1200/240 dpi, phone 1080×2340/420 dpi, short landscape 2340×1080/420 dpi, each at 100% and 200%. The two pointer cases each exercise keyboard hidden and shown, exact query/zero results before Clear, a complete 48 dp target, an editor at least 80 dp wide, no overlap, stable editor geometry, exact cleared range, category/route/selection and keyboard preservation. Emoji also returns to the exact launcher caller and matching field origin/height.
- Three API36 guard/composition cases: witnessed controlled Japanese composing underline before pointer Clear; whitespace; live query; stale/disposed/menu/recreation/epoch ownership.
- API26, `Droidcast_WebView_API26_27caf3`, 1080×1920/420 dpi, 100%: four Clear/ownership methods; the controlled-composition method remains excluded after its unwitnessed precondition failure. This is native-app smoke, not extension execution on its old WebView.
- Two unchanged native-input contracts on modern tablet, modern phone and API26: exact committed emoji/caret navigation and active multilingual composition. These use the explicitly owned silent test IME. Clear cases use the selected system IME.

Five final common-harness controls retain **5 intended failures / zero passes / 79 restoration checks**: four pre-Clear app cases fail the missing-Clear display assertion, and the intermediate Emoji app fails the six-pixel return-origin assertion at 200% after pointer clearing succeeds. The positive-only runner records remain failed; the separate [calibration](validation/touch-search/final-controls-calibration.json) explains the expected failures. Ordinary assertion-failure screenshots contain no app error banner.

Editor replacement, category/route setup, IME visibility requests and native InputConnection composition are controlled setup. Actual recorded pointer actions activate Clear and Emoji Back; direct retained callbacks test their ownership contract. This establishes neither physical typing nor complete Tab/TalkBack navigation. The single-line editor can scroll horizontally; enlarged category labels intentionally ellipsize. The API26 after-Clear Emoji frame also retains unsupported-glyph boxes for some newer emoji in the legacy system font; no glyph fallback or font repair is claimed here (#71). This is functional Clear coverage, not a full typography certification.

## Known layout gap retained as #111

With the system keyboard open in short landscape, the palette measures 218 px high in both 100% and 200% profiles. The header and Clear target remain visible, but the empty-results body is clipped. The earlier `ready-landscape100` and `diagnostic-landscape100-v6` cohorts remain **0/2 failed** each. The diagnostic captures record an exact query and zero model results; there is no query-corruption or fullscreen-IME diagnosis.

The final v7 harness explicitly narrows the unrelated body-visibility gate: for a keyboard-visible palette below 180 dp it records whether empty feedback is visible, with a `#111` marker if absent. Outside that measured condition, the message must display. Exact query/zero results and every Clear geometry, pointer, focus, range, route, category, return and IME assertion remain mandatory; neither keyboard loop is skipped. Passing final Clear cases therefore provides **no empty-feedback repair credit**. [#111](https://github.com/moikdev/droidcast/issues/111) remains the next layout priority.

## Earlier attempts and restoration

All retained native attempts reconcile to **65 executions / 47 raw JUnit passes / 18 failures**, with **494 successful cohort checks**. Historical passes do not contribute to final v7 credit. Two raw JUnit passes came from a failed post-test protocol and are explicitly excluded. [Summary](validation/touch-search/summary.json) lists each cohort, binary, outcome and cleanup count; unrun planned rows receive no execution credit.

The original intermediate tablet100 passed 2/2; intermediate tablet200 failed 1/2 on the return-origin check. Its planned replay failed 0/1 as intended. Earlier final-app/F79 tablet and phone cohorts passed but retain their original harness; the independent API26 5/5 likewise remains historical. The two landscape empty-feedback failures are not overwritten by the scoped v7 results.

One pre-install archive-export timeout executed zero tests and retained its owner. A separate 18-check recovery verified original APKs, all 573 existing files/preferences, geometry/font and absence of a test log before final release. A later run had raw JUnit 2/2 but timed out on post-test/idle cleanup. Its positive-only receipt remains incomplete with a failed cleanup check. The same persisted dedicated AVD was stopped and restarted without wiping it. An optional whole-AVD backup attempt aborted before copying; **no whole-AVD snapshot is claimed**. Twenty-nine actual test files were recovered. A first recovery stopped during archive verification; a separate continuation completed 33 checks, including all original regular-file hashes, both APKs, geometry/font/four IME fields, unprivileged idle state and owner revalidation before release. This is permitted app/settings restoration evidence, not whole-system or cross-boot logical identity certification.

The runner now streams its fixed archive command into a create-only, mode-600 private partial file with closed input and the existing deadline. It validates exit/stderr and the strict archive structure before promoting the file. Failed/timeout partials remain private. Three host contracts exercise a large transfer with the parent's input still open, malformed archives and failed/timed-out transfers. A read-only transport probe matched all 573 originals; these observations establish no exact cause of the earlier guest/pipe stalls.

The first final-harness phone200 cohort also remains 0/2 failed. Both pre-action PNG/XML pairs visibly contain Android’s “Pixel Launcher isn’t responding” dialog over Droidcast. The later window dump confirms the exact external launcher ANR window. A separate six-check operation force-stopped only that observed launcher package on the owned AVD, checked the dialog was absent and preserved all four IME settings. A separately named continuation uses the exact same app/test/controller hashes; its successful outcomes do not turn the obstructed cohort into passes or establish an app repair. The continuations retain previously completed whole unchanged-binary cohorts rather than rerunning or relabeling them.

The final-harness API26 five-case cohort remains **4/5 failed**: the Japanese composition method lacked its required composing-underline witness before any Clear tap. No failure image or successful composition-clear receipt was produced by that method. The cause remains unestablished under #69; the earlier F79 five-case pass neither explains nor clears it. A separately named four-case whole cohort covers the established API26 Clear/ownership smoke contracts without selecting or crediting the failed composition method. Modern controlled composition and the separate owned-silent-IME regressions retain their own scope.

All current cohorts restore data/APKs/geometry/font before owner release. The separate final preservation receipt contains **14 successful read-only checks**, comparing all 573 original modern app/evidence files, both original APKs, legacy package absence and current IME/settings baselines. No private originals, raw secure settings, clipboard contents/fingerprints or owner tokens are published. The earlier pre-PR109 IME subtype-history identity remains uncertified; this slice's baseline is explicitly post-PR109.

## Host checks and public evidence

Final debug/test and benchmark/test builds succeed; lint has **0 errors / 56 warnings per variant**. Retained JVM XML records **343 passing cases, zero failures/errors/skips**; the final task is `UP-TO-DATE`, so this is reused unchanged-source evidence, not a fresh final-document-head JVM run. Fresh runner-source Python receipts show **135 passes per normal/optimized mode**. The first build failed on output/disk space, and a later build was interrupted by a daemon stop; both failed logs remain retained beside successful retries. No device tests ran during the initial build failure.

The [checkpoint](validation/touch-search/checkpoint.json) binds 538 public files plus itself, original/public byte transformations, allowlisted runner projections and all 653 retained capture hashes. **92 selected actual PNG/XML/frame sets** are published; other raw images remain hash-listed, not silently credited as newly reviewed screenshots. Raw hierarchy CRLF bytes are preserved. The two obstructed phone captures are explicitly labeled external launcher ANR failures, not Droidcast error states. Clear captures wait for Compose/UiAutomator idle and write XML before PNG; hierarchy bounds are not independent pixel/visibility proof. Native-input captures retain their own stated synchronization. Host paths and temporary names are redacted; XML uses escaped placeholders and all published XML parses.

Actual exact-head hosted Android results and the two fresh independent gpt-6.1-sol/high audits remain separate PR merge gates. Local receipts do not stand in for hosted CI. #69, #71, #89, #111, broader Raycast/Store parity, physical/OEM/accessibility, signing and migration remain open or uncertified. No project rename or migration is performed.
