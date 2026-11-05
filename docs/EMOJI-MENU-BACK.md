# System Back closes one palette menu and keeps the caller query

[#115](https://github.com/moikdev/droidcast/issues/115) recorded that, with the Emoji category menu open over the query `face`, one actual system Back closed the menu and also cleared the query. This slice traces that press, repairs the double handling, defines Back for searchable menus with the keyboard shown, and verifies menu dismissal on the launcher and Emoji routes.

## Observed delivery (facts)

The tracing-only app `1dc1a1ce` (`3bf4f104…`) leaves all key and dispatcher handling unchanged. Its opt-in, debug-only `PaletteBackTrace` records primitive key metadata and handler stages, plus the query *length*; it never retains text, callbacks or views. [The stage diagnostic](validation/emoji-menu-back/round1/registries/stage-diagnostic-v2.json) records this sequence for a single injected Back on `Tinycast_Tablet_API_36`:

1. `KEYCODE_BACK` DOWN reaches the menu host's pre-IME key handler with the menu open. `PaletteMenus.key` dismisses the menu and reports handled.
2. The same press reaches the activity Back dispatcher. The Emoji route's `BackHandler` runs with the menu already closed and a 4-unit query, so it takes its next branch and clears the query.
3. Back UP arrives canceled and is also consumed by the pre-IME handler.

A single press is therefore handled twice. The trace has zero dropped samples. `UiDevice.pressBack()` returned false in these runs, so its return value is not treated as delivery proof.

Two further facts appeared while testing the repair:

- The menu's dispatcher callback was registered when the host first composed. A route that registers its `BackHandler` later takes priority. On the guard-only app `0b535d8f`, a later-registered fixture route received Back (`route_backs=1`, `menu_dismissals=0`) and the route closed under the open menu.
- When focus moved to a menu, the Emoji text field received a selection-only update that collapsed a committed range [1,3] to its end, [3,3]. The range witness on `0b535d8f` records this while the menu is open and after return, independent of any Back input.

## Change

- `PaletteMenus.key` no longer handles `KEYCODE_BACK` on any API level. The Back dispatcher owns the complete press. Escape, Ctrl/⌘K, arrows, Tab and Enter keep their existing menu handling.
- The menu `BackHandler` is keyed on each menu activation, so every opening re-registers above every callback that existed at that moment. A callback registered *after* a menu opens would still take priority; no current path does that, because the menu backdrop blocks touches and the menu takes keys.
- While a palette menu owns focus, the Emoji field ignores only the blur update that collapses a non-collapsed committed range to its end, with no composition. This is the launcher's existing collapse-to-end condition, plus composition guards on both values. Every other update, including deliberate selection changes, still applies.
- Launcher, Emoji and menu dispatcher callbacks emit trace stages. Tracing stays inactive unless both `BuildConfig.DEBUG` and the `dev.droidcast.paletteBackTrace` system property are set; stage strings are only built while tracing.

## Back with the keyboard shown (defined behavior)

Searchable menus (Emoji Actions, launcher Actions, extension Actions, reviewed-text chooser) have an action-search field. Before this change, the pre-IME handler closed them on the first Back even when the keyboard was up. Now Android's normal input order applies:

- **Keyboard shown:** the first Back hides the keyboard. The menu stays open with its action search focused, and no app Back callback runs. The second Back closes only the menu and returns to the caller with its query, range, filter and selected result intact.
- **Keyboard hidden:** one Back closes only the menu, with the same caller preservation.

On a narrow palette (under 600 dp) the Actions menu requests its keyboard when it opens. A wide palette keeps it hidden unless the keyboard was already up. After return, the caller's keyboard state follows the route's existing focus behavior. The tests record that state but do not define it as a contract.

## Binary boundaries

| Role | Source | App SHA-256 prefix |
| --- | --- | --- |
| Tracing-only control (unchanged handling) | `1dc1a1ce` | `3bf4f104` |
| Guard-only control (no priority or range repair) | `0b535d8f` | `98dfdfcc` |
| Repaired app, executed for every current cohort | `867f9917` | `fb5fce28` |

Every current cohort and control uses one instrumentation APK, `8e5b6e9c…`, built from `ded3f817`. After `867f9917`, only `TouchSearchDeviceTest.kt` changes, and the fixture rebuild changes only `classes3.dex`; the app rebuilt from the same app stamp is byte-identical ([identity](validation/emoji-menu-back/v6/identity-final-pair.json)). All 11 packaged upstream assets and notices remain unchanged.

## Current verification

[The summary](validation/emoji-menu-back/v6/summary.json) credits **69 passing executions across 15 whole cohorts, zero failures and 228 successful restoration checks** on the exact `fb5fce28` / `8e5b6e9c` pair:

- `Tinycast_Tablet_API_36`, API 36 with the stock Gboard keyboard: tablet 1920×1200/240 dpi at 100% and 200% font; short landscape 2340×1080/420 dpi at 100% and 200%; phone 1080×2340/420 dpi at 100% and 200%.
- Android 8.0 (API 26) phone emulator with its stock AOSP keyboard, at 100% and 200%.
- **Back contracts:**
  - the original Emoji category-menu query probe;
  - Back, Escape and backdrop dismissal, preserving query, range [1,3], filter and selected result on both routes;
  - a canceled repeated Back that keeps the menu, followed by normal Back, query clear and route exit;
  - menu priority over a later-registered route callback;
  - Emoji and launcher Actions with the keyboard shown (two presses) and hidden (one press).
- Every menu-dismissal, canceled, keyboard-hiding and query-clear Back asserts its dispatcher owners from the trace: exactly `menu-dispatcher` for a menu dismissal, none for a canceled or keyboard-consumed press, and exactly `emoji-dispatcher` for the query clear. Route-exit presses are checked by route state and counters instead. Window-observer and trace-property restoration are asserted.
- **Other cases:**
  - Range placement before and after keyboard setup is a range diagnostic using backdrop dismissal, not a Back contract. It runs on tablet 100% only.
  - Regressions on the same pair: keyboard Hide/Show and owned callback guards, empty-feedback readability, touch Clear, pointer Clear and retained-callback guards, and committed/multilingual input on API 36 and API 26. The two input regressions use the owned test keyboard; everything else uses the stock system keyboard.

The original query probe needs the tablet or landscape layout, so it runs on those four API 36 profiles.

All five matched controls run on the same final instrumentation, fail only at the intended assertion and pass all 79 restoration checks ([logs](validation/emoji-menu-back/v6/control-logs/)):

| Control app | Contract | Native result |
| --- | --- | --- |
| `1dc1a1ce` | Emoji query after one Back | query `''`, expected `face` |
| `0b535d8f` | Menu priority over a later route | route closed; menu not dismissed |
| `0b535d8f` | Range across a menu round trip | [1,3] collapses to [3,3] |
| `1dc1a1ce` | Emoji Actions, keyboard shown, first Back | menu closed instead of staying |
| `1dc1a1ce` | Launcher Actions, keyboard shown, first Back | menu closed instead of staying |

The searchable controls fail before their after-Back capture, so only their menu-open frames exist; the failure assertion is the receipt.

## Host checks

At app source `867f9917`, [the host record](validation/emoji-menu-back/v6/checks/host-867f9917.json) shows these fresh results:

- 343/343 JVM tests;
- 135 Python cases in each of normal and optimized modes;
- two successful builds;
- lint with 0 errors and 56 warnings for debug and benchmark.

The `ded3f817` fixture rebuild relinted with the same counts. JVM tests can't usefully exercise the `KEYCODE_BACK` guard: the unit-test Android stubs return 0 for `KeyEvent.keyCode`. The device trace assertions cover it instead. Exact-head hosted CI is reported on the PR.

## Preservation and recovery

[Final preservation](validation/emoji-menu-back/v6/final-preservation.json) passes **15 of 16** read-only checks: AVD/API, unprivileged shell, idle instrumentation, geometry, font and four-field IME settings on both emulators, both original modern APKs, and absent packages on the legacy emulator. The failed check is the 573-file comparison of the original modern app data. Exactly one file differs: `files/profileInstalled`, the AndroidX ProfileInstaller marker. Its embedded timestamp is now 21:17:52 UTC, 3 s before the last round-1 modern cohort finished its restoration, which reinstalls the original APK. The round-1 preservation pass at 21:51 UTC still saw the old marker, and the first v5 cohort at 22:19 UTC already saw the new one. [The marker record](validation/emoji-menu-back/v6/profile-marker-record.json) publishes both values and times. The original app process later started (inferred, not observed) and ProfileInstaller rewrote it. A reinstall timestamp cannot be restored. The marker is derived from the build and install, not user data, so it is reported rather than overwritten.

One cohort, `v6-regression-empty-landscape200`, passed both tests, but its 60-second private-data snapshot timed out at 57.8 of about 77 MB. [The recovery record](validation/emoji-menu-back/v6/restoration-recovery.json) completes the runner's remaining steps with a longer snapshot timeout. All 9 checks pass: owner, data equality across all 573 files, unprivileged shell, both original binaries, geometry/font, and lease release last. That cohort earns no credit. The unchanged retry `-r2` passed 2/2 with all 17 restoration checks.

## Retained failures and history

[The v5/v6 registries](validation/emoji-menu-back/v6/registries/) and [round-1 records](validation/emoji-menu-back/round1/) keep all 209 executions, including 30 failures:

- **Round 1 (81 executions, 18 failures):** diagnostics, v2/v3 candidates and the first audited head `58eb26dc`, whose 14 cohorts used the broader Emoji selection rule.
- **v5 at `867f9917`:** a 7-method cap rejected one cohort before execution. Android 8 then failed both Actions cases in the keyboard-hidden phase. The frames show a test race: the menu's own keyboard appeared after the fixture had sampled visibility, so the next Back correctly hid the keyboard. `ded3f817` makes the fixture wait for the menu's opening intent; the app did not change.
- **v6:** the restoration timeout above.

None of these earns current credit.

## Screenshots

[41 published frames](validation/emoji-menu-back/v6/published-screenshots.json) each equal the capture hash recorded by their cohort. Every runner-recorded capture hash is in [capture-hashes.json](validation/emoji-menu-back/v6/capture-hashes.json). The frames include the retained v5 Android 8 race frame.

Known API 26 limits visible in these frames:

- Newer emoji render as missing glyphs, a pre-existing platform font limit.
- At 200% font with the keyboard up, the Emoji footer is cut off at "Copy to" and the Actions button is off-screen. This layout limit predates this change and belongs to #69/#71.
- After system Back hides the keyboard, the menu's keyboard button keeps showing its Hide icon and label, because Compose's keyboard-visibility state lags the system on API 26. This is not only cosmetic: by the code, the next tap requests Hide and does nothing, so showing the keyboard takes a second tap, and TalkBack announces the wrong action. Inferred from code and not executed. Before this change, Back closed the menu instead, so this PR makes the state reachable by Back. Tracked as a follow-up. The behavior assertions use the platform inset state.

## Limits

These are emulator results with injected Back key events and stock system keyboards. Gesture Back is not exercised, including stock AOSP gestures on API 36, which reach the same dispatcher without a key event. Physical devices, OEM navigation, predictive-back animation, TalkBack, hardware keyboards and third-party keyboards are not certified. Menu arrow, Tab, Enter and Ctrl/⌘K navigation is unchanged in code apart from removing Back, but it was not re-run on the final pair; only Escape was. There is no device evidence for API 27–35; API 33–35 use the same key route as API 26 for this app (no `enableOnBackInvokedCallback`). Real production routes with menus other than launcher and Emoji, such as folder filters, extension Actions, the reviewed-text chooser and Settings, are not executed; menu priority is shown on a fixture route built from the production menu host. Broader #69/#71, #89, full Store parity and production signing/migration remain open.
