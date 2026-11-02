# Parity implementation loop

Readiness work under [#65](https://github.com/moikdev/droidcast/issues/65) / [PR #66](https://github.com/moikdev/droidcast/pull/66) now has a complete `9986679` functional checkpoint: **167/167 native cases per viewport**, **8/8 assistant per viewport**, **12 guarded hardware cases**, and **four actual self-updates**, with all recorded restoration checks passing. Six Ctrl/Alt/Shift probes work; both Meta probes remain unsupported. Build/lint (0 errors/51 warnings), 147 JVM cases, four Python cleanup checks and the exact-9986679 hosted build pass. The [final evidence manifest](validation/readiness/final-checkpoint-9986679.json) binds binaries, source/runner hashes, case IDs, receipts and eight real screenshots. Both fresh independent sol/high audits cleared final head `2302c12`; PR #66 merged as `9583956` with the identical tree and an expected-head guard. The [report](PRODUCTION-READINESS.md) retains prior failures and labels historical performance measurements honestly. Next: readable touch/large-font controls [#71](https://github.com/moikdev/droidcast/issues/71), then command compatibility [#72](https://github.com/moikdev/droidcast/issues/72). API26 remains 26/33 at its earlier checkpoint (#68/#69); production recovery/migration, physical/OEM coverage and full parity remain open.

The follow-up `9ba105e` test source adds the actual successful MIME-timeout retry check: **4/4 provider cases per viewport**, using the same frozen runtime APK and a separately hashed strengthened test APK. It verifies zero stale launches, retention/reselection and one exact URI/MIME/read-grant launch intent through a controlled monitor. [Follow-up evidence](validation/readiness/provider-retry-followup.json) keeps these rechecks separate from the prior broad/integration identities. Product sources are unchanged; both fresh final-head audits cleared the strengthened test in `2302c12` before the verified merge.

The first readable, touch-friendly palette milestone ([#71](https://github.com/moikdev/droidcast/issues/71), parent #20) merged in [PR #74](https://github.com/moikdev/droidcast/pull/74) as `8d60d5c`. Its tree matches reviewed head `a1f2a47`. The final frozen `9558e01` app/test pair passes **173/173 native cases on each tablet/phone layout**, with all 18/20 cleanup checks, plus **12/12 critical-font executions** with all 32 cleanup checks. Both independent sol/high audits and the actual current-head hosted Android build passed. The [final native receipt](validation/touch-readability/native-final-checkpoint.json) preserves identities and all 173 case IDs.

The original `448387e` adaptive-controls pair retains its separate **120/120 matrix executions** (six flows × five viewports × four font scales). The later feedback repair preserves natural text and caller hit areas: six matched negative executions fail as intended, eight repaired executions pass, and all 90 cleanup checks pass. Earlier recorder interruption, HTTPS timeout and emulator-crash attempts remain uncredited; the fresh phone pass follows verified recovery and focused reproduction. [Real captures and scope](TOUCH-READABILITY.md) keep those checkpoints separate. Broader forms/preferences/errors, systematic IME transitions, actual split-window, physical/OEM and full accessibility coverage remain open in #71.

The first execution batch for [#72](https://github.com/moikdev/droidcast/issues/72) now covers sixteen named commands from nine frozen Store bundles declaring 34 commands. Eleven have completed listed workflows on tablet and phone, three remain partial and two have observed workflow failures. Tablet History uses a focused same-APK pass after the broad run's SystemUI clipboard-helper interruption; the original untested row is retained. All 41 cleanup checks pass. The [workflow report](EXTENSION-WORKFLOWS.md) and [checkpoint](validation/extension-compatibility/batch-001-checkpoint.json) bind the frozen `eef1b8d` app/test pair, sources, command hashes and 70 raw native capture sets. The static snapshot remains untested; these Store outcomes are not results for the larger source inventory. Next: diagnose [HTTP filesystem startup #75](https://github.com/moikdev/droidcast/issues/75), expose native empty/error states under #6, and pursue explicit selected-text session input under #19. Translation output and further batches remain open.

Command discovery tooling merged in [PR #73](https://github.com/moikdev/droidcast/pull/73); [#72](https://github.com/moikdev/droidcast/issues/72), parent #7, still tracks runtime workflow batches and a compatibility view. Static discovery is always labeled untested. Calculator Actions [#67](https://github.com/moikdev/droidcast/issues/67) remains a separate gap.
The live backlog is [GitHub issue #1](https://github.com/moikdev/droidcast/issues/1). Every implementation PR includes real Android screenshots and independent `gpt-6.1-sol` / `high` correctness/security and UX/test audits before merge. See [AGENTS.md](../AGENTS.md).

## Enhancements

- [#2: Open dedicated Android settings destinations from palette commands](https://github.com/moikdev/droidcast/issues/2)
- [#3: Add versioned user-controlled backup and restore](https://github.com/moikdev/droidcast/issues/3)
- [#4: Add configurable global and per-command keyboard shortcuts](https://github.com/moikdev/droidcast/issues/4)
- [#5: Search user-selected folders with persistent document grants](https://github.com/moikdev/droidcast/issues/5)
- [#6: Render native extension Grid, Markdown, metadata and complete Forms](https://github.com/moikdev/droidcast/issues/6)
- [#7: Expand verified Raycast host compatibility and capability reporting](https://github.com/moikdev/droidcast/issues/7)
- [#8: Support multi-argument quicklinks, variables and Android deeplinks](https://github.com/moikdev/droidcast/issues/8)
- [#9: Add reusable snippets with variables and nested expansion](https://github.com/moikdev/droidcast/issues/9)
- [#10: Add rendered Markdown and portable note workflows](https://github.com/moikdev/droidcast/issues/10)
- [#11: Add a searchable offline emoji and symbol picker](https://github.com/moikdev/droidcast/issues/11)
- [#12: Add dictionary lookup and definition views](https://github.com/moikdev/droidcast/issues/12)
- [#13: Extend calculator parity with dates, colors and exchange rates](https://github.com/moikdev/droidcast/issues/13)
- [#14: Expand explicit clipboard collections with images and useful actions](https://github.com/moikdev/droidcast/issues/14)
- [#15: Implement tested Android multitasking equivalents for Tinycast window actions](https://github.com/moikdev/droidcast/issues/15)
- [#16: Improve application focus, running-state and launch behavior](https://github.com/moikdev/droidcast/issues/16)
- [#17: Add calendar search, upcoming meetings and join actions](https://github.com/moikdev/droidcast/issues/17)
- [#18: Add optional provider-backed chat with saved conversations](https://github.com/moikdev/droidcast/issues/18)
- [#19: Add selected-text rewrite, translation and summary actions](https://github.com/moikdev/droidcast/issues/19)
- [#20: Complete theme, localization and accessibility parity](https://github.com/moikdev/droidcast/issues/20)
- [#21: Enable clean-runner CI and production release engineering](https://github.com/moikdev/droidcast/issues/21)

- [#22: Validate battery and security destinations across Android builds](https://github.com/moikdev/droidcast/issues/22)

- [#24: Import supported Tinycast and Raycast export categories on Android](https://github.com/moikdev/droidcast/issues/24)
- [#26: Add modifier-tap hotkeys and verified Android Hyper/Fn equivalents](https://github.com/moikdev/droidcast/issues/26)

- [#36: Compact Raycast-style settings workspace](https://github.com/moikdev/droidcast/issues/36)
- [#37: Diagnose intermittent Quick Settings tile activation timeout](https://github.com/moikdev/droidcast/issues/37)
- [#59: Investigate truncated synthetic input around keyboard-service hierarchy capture](https://github.com/moikdev/droidcast/issues/59)
- [#62: Trace intermittent Grid return during clipboard-overlay screenshot capture](https://github.com/moikdev/droidcast/issues/62)
- [#60: Adopt Tinycast screen contracts, typed navigation and shared action policies](https://github.com/moikdev/droidcast/issues/60)

## Active slice

The first readable touch/large-font milestone [#71](https://github.com/moikdev/droidcast/issues/71) is merged; broader Forms, IME and accessibility work remains. Production/platform gaps stay in [#65](https://github.com/moikdev/droidcast/issues/65) and [the report](PRODUCTION-READINESS.md). Discovery preparation for [#72](https://github.com/moikdev/droidcast/issues/72) is merged; the first bounded Store workflow results are recorded separately above, with unresolved outcomes and the source inventory kept honest. Calculator Actions #67 remains in the backlog.

Inline palette migration merged in [PR #61](https://github.com/moikdev/droidcast/pull/61), closing #56 after both independent sol/high audits cleared `af12a71`; merge `b2aa1d1` has the identical tree. [Nightly r9](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-11-01-r9) publishes the exact tested APK with verified public bytes and unchanged nightly signer. Final evidence:132 JVM,74 tablet,71 phone,3 hardware,4 cleanup;89 PNGs/87 paired XMLs;lint0errors/44warnings. Native Grid and #6/#7 remain partial. #59/#62 and the historical picker miss remain unexplained. The complete source comparison and structural follow-up #60 remain applicable.

## Completed slices

Pinned command discovery merged in [PR #73](https://github.com/moikdev/droidcast/pull/73) after both independent gpt-6.1-sol/high audits cleared `86218d1`; merge `66d5d94` has the identical tree. Local checks: 18 collector cases normally and optimized, 22 total Python checks; hosted Android build passed separately. All 3,491 pinned source directories were attempted: 3,489 accepted manifests, 11,153 untested declarations, two invalid manifests, **zero runtime-tested commands**. Queue cancellation and truncated-read regressions have matched old-code failures and corrected passes. [Catalog instructions and evidence](EXTENSION-CATALOG.md) distinguish static discovery, retained unchanged-runtime smoke and outstanding #72 execution work.

Verified nightly updates merged in [PR #64](https://github.com/moikdev/droidcast/pull/64), closing [#55](https://github.com/moikdev/droidcast/issues/55), after both independent audits cleared `209c5d5`; merge `ddeb0fd` has the identical tree. [Nightly r10](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-11-01-r10) publishes the versionCode 6 updater bootstrap. Recorded slice evidence: 138 JVM cases, 18 updater + 14 existing cases per layout, four real version 6→7 self-updates, and 52 screenshot/XML pairs. Public nightly discovery, bounded HTTPS, checksum/package/version/signer verification and Android installer approval/recovery are implemented. Production signing, process-death/platform coverage and automatic notifications remain partial. See [update design](UPDATES.md) and [scoped validation](validation/updates.md).

Fixed Emoji header merged in [PR #58](https://github.com/moikdev/droidcast/pull/58), closing #57 after both independent sol/high audits cleared `3e446db`; merge `227d510` has the identical tree. Back/Search/Category share one row aligned with launcher Search; Back retains its full touch target. Local checks: 113 JVM, 15 tablet, 15 phone, four cleanup tests, lint 0 errors/42 warnings, nine original screenshots. The historical caret observation remains unexplained; eventual-state checks certify no latency fix. [Nightly r8](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-10-31-r8) publishes the reviewed APK. Broader #56/#6 migration and #55 updater remain open.

Double-modifier shortcuts merged in [PR #54](https://github.com/moikdev/droidcast/pull/54) after both independent sol/high audits and documentation-only follow-ups cleared `5419c69`; merge `d6fccdc` has the identical tree. Local evidence: 113 JVM, 41 tablet, 41 phone, 12 hardware core cases, eight capability probes and four cleanup tests, lint 0 errors/42 warnings. Ctrl/Alt/Shift probes passed; Meta did not. The controlled stale-window notification race is corrected; the historical untraced Home miss remains unattributed. Six rebind/Home repetitions do not certify activation after process kill. [Nightly r7](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-10-31-r7) publishes the tested APK. Single taps, Fn/Globe, Hyper, physical/OEM and broader #26 parity remain gaps.

Touch assistant setup merged in [PR #53](https://github.com/moikdev/droidcast/pull/53), closing #47 after both independent sol/high audits cleared `876761b`; merge `8a6476a` has the identical tree. Final local evidence: 89 JVM, 26 tablet, 26 phone, lint 0 errors/42 warnings and 28 real screenshots. Actual corner swipes and Home holds pass with keyboard service off; roles/navigation/preferences are restored. Seven post-gesture XMLs omit the pictured palette and remain explicitly limited evidence. Production assistant-session code is unchanged. [Nightly r6](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-10-31-r6) publishes the tested APK with verified public bytes/checksum. Physical Lenovo/OEM, TalkBack, latency/endurance and full parity remain unclaimed.

Deliberate calculator activation merged in [PR #52](https://github.com/moikdev/droidcast/pull/52), closing #51 after both sol/high audits cleared `7f8dea5`; merge `48e33d0`. Bare numbers remain searchable; x/X/*/× multiplication uses × display with exact numeric clipboard values, including constant operands. Final local evidence: 89 JVM, 14 tablet, 14 phone, lint 0 errors/42 warnings and 29 real screenshot/XML pairs. The constant-boundary audit finding has old-head negative controls and corrected focused/full checks. [Nightly r5](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-10-31-r5) publishes that tested APK with a verified public download/checksum. Calculator #13 and full parity remain partial.

Keyboard-readiness onboarding merged in [PR #50](https://github.com/moikdev/droidcast/pull/50), closing #39 after both sol/high audits cleared `cece515`; merge `7bec152`. Final local evidence: 80 JVM, 37 tablet, 37 phone, 11 Linux hardware cases, lint 0 errors/42 warnings and 35 real screenshots. Search-hidden probes, reconnect epochs and explicitly armed navigation chords have negative controls and corrected regressions. Saved/approved/connected/received states remain distinct; six Ctrl/Meta chord variants are in-process coverage, with actual hardware Ctrl+F delivery. CI inactive; Lenovo/OEM certification remains unclaimed.

The direct-settings slice merged in [PR #23](https://github.com/moikdev/droidcast/pull/23), closing #2 after both independent audits cleared head `39fc5f8` and local checks passed. Its live review/merge state and [issue #1](https://github.com/moikdev/droidcast/issues/1) are authoritative. Record subsequent slices here after their verified merges; the baseline preview is not full parity.

Backup/restore merged in [PR #25](https://github.com/moikdev/droidcast/pull/25), closing #3 after both independent audits cleared `d144ae0`: 31 JVM, 39 tablet and 10 phone tests, build/lint, and 18 inspected screenshots. CI remains inactive.

Configurable ordinary keyboard shortcuts merged in [PR #33](https://github.com/moikdev/droidcast/pull/33), closing #4 and #34 after both sol/high audits cleared `8ccd87d`: 46 JVM, 52 tablet, 23 phone and 18 Linux hardware cases, plus build/lint and 21 screenshots. The unexplained phone backup timeout remains tracked in #35.

Raycast-style settings, scoped folders, assistant activation and immediate search merged in [PR #38](https://github.com/moikdev/droidcast/pull/38), closing #36/#40 after both audits cleared `1508ac6`. Final scope: 66 JVM, 27 targeted tests per layout, 16+5 hardware cases and 39 screenshots. #5 remains partial; the 71-test full suites belong to an earlier binary.

Fullscreen tablet Settings merged in [PR #43](https://github.com/moikdev/droidcast/pull/43), closing #42 after both audits cleared `0ac6689`: 66 JVM tests, 16 targeted tests per layout and eight screenshots. The palette stays floating. [Nightly r2](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-10-31-r2) is the released checkpoint for that source.

Offline Emoji & Symbols merged in [PR #45](https://github.com/moikdev/droidcast/pull/45), closing #11 after both audits cleared `679c69e`; merge `b974ff0`. Final evidence: 71 JVM, 11 native tests per layout and 25 screenshots, build/lint, exact Unicode copying and persistence. [Nightly r3](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-10-31-r3) is the published checkpoint. Advanced emoji parity remains in [#44](https://github.com/moikdev/droidcast/issues/44).


Rigid typing and immediate emoji navigation merged in [PR #48](https://github.com/moikdev/droidcast/pull/48), closing #46/#49 after both sol/high audits cleared `a101671`; merge `703ddb0`. Final local scope: 71 JVM, 27 tablet and 24 phone cases, lint 0 errors/41 warnings and 42 real screenshots. Viewport and retained-index audit findings were resolved with old-head negative controls. [Nightly r4](https://github.com/moikdev/droidcast/releases/tag/nightly-2026-10-31-r4) publishes that tested APK. Physical/OEM/latency/TalkBack and full parity remain unclaimed.

## Additional upstream gaps

- [#27: Editable settings mirror and history](https://github.com/moikdev/droidcast/issues/27)
- [#28: Local dictation](https://github.com/moikdev/droidcast/issues/28)
- [#29: Camera preview and capture](https://github.com/moikdev/droidcast/issues/29)
- [#30: MCP](https://github.com/moikdev/droidcast/issues/30)
- [#31: Frontmost app menu/action search](https://github.com/moikdev/droidcast/issues/31)
- [#32: Custom command library and scoped automation](https://github.com/moikdev/droidcast/issues/32)

- [#34: Predictable root Settings routing from warm search](https://github.com/moikdev/droidcast/issues/34), exposed and addressed within PR #33.

Global-shortcut onboarding follow-up: [#39](https://github.com/moikdev/droidcast/issues/39). A Lenovo user reports Alt+Q working after approving restricted settings; broader OEM certification remains open.

Assistant activation and immediate search: [#40](https://github.com/moikdev/droidcast/issues/40), merged in PR #38. The custom global hotkey remains separate from the system assistant mapping.

Validation limitation [#41](https://github.com/moikdev/droidcast/issues/41): HTML Viewer rendered the correct TXT body but omitted it from accessibility XML in one hardware run; an unchanged rerun passed. Cause and fix remain unestablished.

Tablet Settings fullscreen polish: [#42](https://github.com/moikdev/droidcast/issues/42), merged in PR #43; the palette remains floating.

Production-key preparation for [#21](https://github.com/moikdev/droidcast/issues/21): a dedicated production identity was generated outside the repository at the user's request. Its [public certificate, local signing proof and installed-nightly migration plan](PRODUCTION-KEY-PREPARATION.md) are recorded. Independent recovery backup, migration rehearsals, production automation/channel and readiness clearance remain open; no production APK was published or installed.
