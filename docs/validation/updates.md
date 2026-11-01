# Verified nightly updater validation

Issue [#55](https://github.com/moikdev/droidcast/issues/55). [Trust/design contract](../UPDATES.md), [binary/source/image manifest](updates-evidence.json). Independent review conclusions and merge/release state are recorded on the associated PR and issue; local checks are not GitHub CI.

## Final local results

| Check | Tablet | Phone |
| --- | --- | --- |
| Updater native cohort | 17/17 | 17/17 |
| Existing Settings/palette regressions | 14/14 | 14/14 |
| Released-r9 baseline navigation/capture | 1/1 | 1/1 |
| Real update with confirmation, Cancel and Retry | passed | passed |
| Real update with best-effort confirmation skipping | passed, no second confirmation | passed, no second confirmation |

Build succeeds; JVM XML reports 138 tests with no failures/errors/skips. Lint reports 0 errors and 50 warnings. Four independent-cleanup helper unit tests pass. An unarmed real-install method is skipped by its explicit guard, with no fixture directory created; this is a safety-control result, not another feature pass. Final execution is sequential and stops at unsuccessful suites.

One owned API36 arm64 emulator is configured as tablet 1920×1200/240dpi and phone 1080×2340/420dpi. The app runs under ordinary UID 10213; adbd remains UID2000. Passing records verify installed app bytes and restore preferences/settings/geometry. The real-install harness additionally verifies opaque files/shared_prefs inventory restoration, original app version/bytes/UID, source-approval mode and fixture-grant cleanup. Cache contents are not covered by that private-data restoration claim. Private snapshots and the test-only signing key must never be read for review or published.

## Frozen binaries and provenance

The product APK is byte-identical throughout final verification, regressions and real-install flows. Its embedded source checkpoint is df9c5b57ea57a7875b8940529721c22527c0d962; all later implementation changes are native test/runner/documentation changes. The manifest records current source hashes and unchanged packaged assets.

- Bootstrap, versionCode 6: ceadcc73222d0590dbaf9493e8a0d703d0ae6938269ef8dffc8b84a2c7e4ccc2
- Signed test-only payload, versionCode 7: 6d26d67aba3221fe82b61cf6fe02eaaeab354e7ebbc2083b736000de38ca2af4
- Final updater/regression instrumentation: 3b90247619bce2c2cbe7e7601dd5a71a6db1b8ef179b5b26dbd6ae98b2d7a8a9
- Real-install instrumentation: 026cd3452fe36a7692bd6486d98b3072cee7f0c494011de8a3f00ef45cc2d951

Only UpdateVerificationDeviceTest changed between the two final instrumentation variants. Application sources, UpdateSelfInstallDeviceTest, the shared UpdateDeviceTest capture helper and scripts/test-self-updates.py remain unchanged. Both archived APK identities are retained; the real-install variant is also archived with the preceding tablet verification cohort. This is not a claim that every suite used one instrumentation binary.

The versionCode 7 payload is named 0.5.0-update-fixture and must never be published as an eligible nightly. The release packager successfully preflights the already-tested versionCode 6 APK against public prior version codes, its embedded commit, package and the unchanged nightly signer; it writes the APK, format-1 manifest and checksums without rebuilding or signing.

## What the tests establish

Public GitHub discovery and a real HTTPS legacy-r9 download exercise production networking. Controlled transport separately supplies actual signed APKs, corruption, truncation, timeout/offline behavior and progress held at 1 MiB. These captures show real app controls and real bytes, while explicitly identifying controlled transport.

Archive cases reject corrupt/interrupted files, different package/certificate, stale versions and changed recovered/staged files. They check cleanup, controller replacement, same-release cache retention, retry and forged-result rejection. The palette test checks fixed-footer geometry, same-turn Enter/repeat/release handling, absence of a redundant Actions menu, and caller query/caret restoration. Existing regression cohorts exercise Settings navigation, inline aliases/recording/backup, palette geometry/dismissal/tile invocation and action/Emoji routes.

Four real self-updates use production recovery and PackageInstaller after the harness stages signed bytes. No instrumentation is running during the actual approval/installation UI. Confirmation-required runs verify the real Android source-approval switch, pending-user-action receipt, installer Cancel preserving version 6 plus the verified download, Retry, actual version 7/payload bytes and unchanged UID. Best-effort runs install without a second confirmation on this emulator; Android/OEM fallback remains supported and no universal silent-update claim is made.

After replacement, native assertions verify notes, quicklinks, saved clips, pins, aliases, global/per-command shortcut configuration, emoji favorites/recents, identical emoji/extension preference bytes, and palette preferences excluding the entire command-usage field (invoking Updates legitimately changes usage). Existing grant hashes remain present; the new persistent grant reads the exact document body. Retained extension code/support files are checked and the actual JS extension reads saved LocalStorage/preferences; the retained note opens with its exact body. Unsaved editor buffers and post-update physical keyboard-service delivery are not certified.

Real-install tests require an explicit external-harness flag, matching AVD/emulator properties and API36 before fixture mutation. The host validates the dedicated emulator, ordinary app/adbd identities, starting version/hash, unused fixture locations and source-approval restoration. It handles its original private bytes opaquely.

## Screenshots

All 46 durable PNGs live in docs/screenshots/updates. Each has an origin, SHA-256, dimensions, paired XML hash, app version and instrumentation identity in the manifest. Two before captures use the actual released-r9 APK (code 5); updater screens use code 6; after-replacement captures use the test-only code 7. Settings, palette, progress/error, real permission/installer, cancellation/retry and retained-data states are included. PR image URLs must pin the reviewed head. Stills establish neither latency nor continuous animation.

## Excluded attempts and unresolved limits

- Initial source transfer timed out during ADB stdin upload before native cases; restoration passed. Subsequent APK transfer uses push/copy plus hashes.
- The first 16-case tablet cohort passed 12/16, failing four native capture marker waits after preceding model/security assertions. It retained no failure frame/hierarchy, so the cause remains unknown. Explicit visible markers, native cache refresh and failure captures strengthen evidence without establishing a production timing fix.
- An earlier 16/17 cohort waited for model IDLE while cleanup was still active. A later 16/17 phone cohort observed initial model IDLE before the first content recovery effect and could not find Check before any test action. Fixtures now wait for composition and rendered/enabled controls plus nonbusy state, within the same 10-second bound. Product bytes are unchanged. Final 17/17 cohorts pass.
- Real-install setup attempts expected a Downloads drawer, an exposed launcher test resource ID or a not-yet-selected fixture row. Native captures/diagnostics established the actual storage-root/retained-breadcrumb and EditText/command-description paths. These are fixture corrections; all excluded-attempt restoration checks passed.
- The first broader tablet run passed 13/14: the existing tile package-visibility assertion timed out without a failure frame. A focused pass and the later 14/14 cohorts do not establish its cause or a production fix. The tile implementation is unchanged; evidence is appended to existing [#37](https://github.com/moikdev/droidcast/issues/37).

CI remains inactive. Physical Lenovo/OEM behavior, API26 runtime, process-kill recovery under load, full TalkBack/large-font traversal, latency/endurance and broad Tinycast/Raycast parity remain uncertified. Earlier #35/#59/#62 limitations are not resolved by this updater work. One manual bootstrap installation is necessary for existing r9 users to gain the updater; later eligible nightlies can be downloaded and installed from the app.
