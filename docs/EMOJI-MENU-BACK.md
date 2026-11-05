# System Back closes one palette menu and keeps the caller query

[#115](https://github.com/moikdev/droidcast/issues/115) recorded that, with the Emoji category menu open over the query `face`, one actual system Back closed the menu and also cleared the query. This slice traces that press, repairs the double handling and verifies menu dismissal on the launcher and Emoji routes.

## Observed delivery (facts)

The tracing-only app `1dc1a1ce` (`3bf4f104…`) leaves all key and dispatcher handling unchanged. Its opt-in, debug-only `PaletteBackTrace` records primitive key metadata and handler stages; it never retains text, callbacks or views. [The stage diagnostic](validation/emoji-menu-back/registries/stage-diagnostic-v2.json) records this sequence for a single injected Back on `Tinycast_Tablet_API_36`:

1. `KEYCODE_BACK` DOWN reaches the menu host's pre-IME key handler with the menu open. `PaletteMenus.key` dismisses the menu and reports handled.
2. The same press reaches the activity Back dispatcher. The Emoji route's `BackHandler` runs with the menu already closed and a 4-unit query, so it takes its next branch and clears the query.
3. Back UP arrives canceled and is also consumed by the pre-IME handler.

A single press is therefore handled twice: once as a key and once as a dispatcher callback. The trace has zero dropped samples. `UiDevice.pressBack()` returned false in these runs, so its return value is not treated as delivery proof; the stage samples are the delivery record.

Two further facts appeared while testing the repair:

- The menu's dispatcher callback was registered when the host first composed. A route that registers its `BackHandler` later takes priority. On the guard-only app `0b535d8f`, a later-registered fixture route received Back (`route_backs=1`, `menu_dismissals=0`) and the route closed under the open menu.
- When focus moved to a menu, the Emoji text field could receive a selection-only update that collapsed a committed range such as [1,3] to [3,3]. The range witness on `0b535d8f` records this both while the menu is open and after return, independent of any Back input.

## Change

- `PaletteMenus.key` no longer handles `KEYCODE_BACK` on any API level. The Back dispatcher owns the complete press. Escape, Ctrl/⌘K, arrows, Tab and Enter keep their existing menu handling.
- The menu `BackHandler` is keyed on each menu activation, so every opening registers last and sits above route callbacks registered later.
- While a palette menu owns focus, the Emoji field keeps its committed selection when an update changes only the selection and there is no active composition. Text and composition changes still apply normally.
- The launcher, Emoji and menu dispatcher callbacks emit trace stages. Tracing stays inactive unless both `BuildConfig.DEBUG` and the `dev.droidcast.paletteBackTrace` system property are set; the tests restore the property and window observer afterward.

## Binary boundaries

| Role | Source | App SHA-256 prefix |
| --- | --- | --- |
| Tracing-only control (unchanged handling) | `1dc1a1ce` | `3bf4f104` |
| Guard-only control (no priority or range repair) | `0b535d8f` | `98dfdfcc` |
| Repaired app, executed for all current cohorts | `c0d9a068` | `f56e13db` |

All current cohorts and controls use one final instrumentation APK, `c3e3f9c7d114…`, built from `319d9c87`. After `c0d9a068`, only `TouchSearchDeviceTest.kt` changes; [the final identity](validation/emoji-menu-back/identities/final-harness.json) binds the source hashes and APK payload differences. The fixture-only rebuild changes `classes3.dex` in the test APK only. All 11 packaged upstream assets and notices remain unchanged.

## Current verification

[The summary](validation/emoji-menu-back/summary.json) credits **53 passing executions across 14 whole cohorts, zero failures and 213 successful restoration checks** on the exact `f56e13db` / `c3e3f9c7` pair:

- `Tinycast_Tablet_API_36`, API 36: tablet 1920×1200/240 dpi at 100% and 200% font; short landscape 2340×1080/420 dpi at 100% and 200%; phone 1080×2340/420 dpi at 100% and 200%.
- Android 8.0, API 26 phone emulator at 100% and 200%.
- Contracts: the original Emoji query probe; Back, Escape and backdrop dismissal preserving query, range [1,3], category and IME intent on Emoji, and query, range and filter on the launcher; a canceled repeated Back that keeps the active menu, followed by normal Back return and route exit; menu priority over a later-registered route callback; and range placement before and after IME setup.
- Regressions on the same pair: keyboard Hide/Show and owned callback guards, empty-feedback readability, touch Clear, pointer Clear and retained-callback guards, and committed/multilingual input on API 36 and API 26.

The original query probe needs the tablet or landscape keyboard-slot layout and runs on those four API 36 profiles. Phone and API 26 cohorts run the other Back contracts.

All three matched controls run on the same final instrumentation, fail only at the intended assertion and retain 45 successful restoration checks:

| Control app | Contract | Native result |
| --- | --- | --- |
| `1dc1a1ce` | Emoji query after one Back | query `''`, expected `face` |
| `0b535d8f` | Menu priority over a later route | route closed; menu not dismissed |
| `0b535d8f` | Range across a menu round trip | [1,3] collapses to [3,3] |

[The calibration record](validation/emoji-menu-back/final-control-calibration.json) and [control logs](validation/emoji-menu-back/control-logs/) preserve these receipts.

The repaired app records 74 separate passing trace-property and window-observer restorations. [Final preservation](validation/emoji-menu-back/final-preservation.json) passes 16 read-only checks: both emulators' AVD/API, unprivileged shell, idle instrumentation, geometry, font and four-field IME settings, all 573 original modern app files, both original modern APKs and absent packages on the legacy emulator. Private originals and settings values are compared by digest and remain unpublished.

## Host checks

At app source `c0d9a068`, [the host record](validation/emoji-menu-back/checks/host-c0d9a068.json) shows 343/343 JVM tests, Python 135 cases in both normal and optimized modes, two successful builds and lint with 0 errors and 56 warnings for debug and benchmark. The cached debug APK is byte-equal to the executed app. The later fixture commit changes no production, build, JVM or Python inputs. Exact-head hosted CI is reported on the PR.

## Retained failures and history

All 81 executions remain in [the summary](validation/emoji-menu-back/summary.json): 63 passes and 18 failures. Thirteen historical cohorts (10 passes / 15 failures) cover the window diagnostics on old and current apps, the tracing stage diagnostic, earlier protocol and range diagnostics, and the v2/v3 candidates. Candidate v2 failed 2 of 5. Candidate v3 failed one normal case because a repeated keyboard setup requested Show while the IME was already visible. The fixture's setup became idempotent in `319d9c87`; the app did not change. Those cohorts earn no current credit. [Registries](validation/emoji-menu-back/registries/) retain every pipeline.

## Screenshots

[50 published frames](validation/emoji-menu-back/published-screenshots.json) each equal the capture hash recorded by their cohort. API 26 renders newer emoji as missing glyphs, a pre-existing platform font limit outside this slice.

## Limits

These are emulator results with injected key events and an owned test IME. Physical devices, OEM gesture navigation, predictive-back animation, TalkBack and third-party keyboards are not certified. Broader #69/#71, #89, full Store parity and production signing/migration remain open.
