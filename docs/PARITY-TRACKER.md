# Parity implementation loop

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

Inline palette migration [#56](https://github.com/moikdev/droidcast/issues/56), including native extension Grid [#6](https://github.com/moikdev/droidcast/issues/6). Editors, Store/extension screens, reviews and selection/action menus now use the same window. The shared Raycast-style footer/action search has final local evidence: 132 JVM, 74 tablet, 71 phone, three connected-keyboard cases and 89 screenshots. Independent audits and expected-head merge remain pending. See [migration notes](PALETTE-NAVIGATION.md). The earlier popup Grid prototype remains unpublished. Verified in-app nightly updates [#55](https://github.com/moikdev/droidcast/issues/55) follow this UI checkpoint. A [complete Tinycast source comparison](TINYCAST-COMPARISON.md) now records the typed-screen/coordinator refactor and generates independent Grid navigation expectations from the pinned Swift model. No full-parity claim.

## Completed slices

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
