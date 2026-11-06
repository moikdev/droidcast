# System Back closes one palette menu and keeps the caller query

[#115](https://github.com/moikdev/droidcast/issues/115) recorded that, with the Emoji category menu open over the query `face`, one actual system Back closed the menu and also cleared the query. This slice traces that press, repairs the double handling, defines Back for searchable menus with the keyboard shown, and verifies menu dismissal on the launcher and Emoji routes. It also presents phone action menus as a keyboard-docked sheet ([#119](https://github.com/moikdev/droidcast/issues/119)).

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

## Phone action sheet (#119)

On the 1080×2340 phone at 200%, the Emoji Actions popover sat between header and footer. It showed about 2½ rows, cut "Copy Unicode" mid-glyph, overlapped the grid, and repeated both the selected name and Copy to Clipboard. The maintainer approved a draft. The change implements it for every searchable menu below a 600 dp palette width:

- **Docked sheet:** the menu docks across the palette's full width on its bottom edge, which sits directly above the keyboard, over a dimmed backdrop.
  - Its header has the target name, an "Actions" subtitle and a 48 dp **Close actions** button.
  - Rows are at least 52 dp with 16 sp labels; shortcut keycaps stay hidden on phones, as before.
  - The action search stays on the bottom edge.
- **Large font or short palette:** above 130% font, or when the palette is short, the decorative handle and subtitle are dropped. The sheet grows up to the palette's full height, and its list scrolls instead of clipping.
- **Emoji footer on phones:** the primary button reads **Copy**, keeping "Copy to Clipboard" as its accessible name. The "Tap a symbol to select" line shows only when nothing is selected, or with a copy/edit status. The **Actions** label is kept rather than the draft's ⋮ icon: with the short primary label both fit on one row, and the word is easier to find.
- **Unchanged:** wide, tablet and landscape layouts keep the anchored popover. Ownership, keyboard-first Back, Escape, backdrop dismissal and caller preservation are unchanged.

## Binary boundaries

| Role | Source | App SHA-256 prefix |
| --- | --- | --- |
| Tracing-only control (unchanged handling) | `1dc1a1ce` | `3bf4f104` |
| Guard-only control (no priority or range repair) | `0b535d8f` | `98dfdfcc` |
| Back repair before the sheet (previous PR head app) | `867f9917` | `fb5fce28` |
| Final app with the phone sheet, executed for every current cohort | `6b7d5fa9` | `e80ac97b` |

Every current cohort and control uses one instrumentation APK, `0134ca0c…`, built from `6b7d5fa9` after `./gradlew clean`. The pipeline checked that the built APK contains the sheet code before installing it ([identity](validation/emoji-menu-back/v8/identity.json)). An earlier exploratory build was incremental and stale; see *Retained failures*. All 11 packaged upstream assets and notices remain unchanged.

## Current verification

[The summary](validation/emoji-menu-back/v8/summary.json) credits **77 passing executions across 19 whole cohorts, zero failures and 282 successful restoration checks** on the exact `e80ac97b` / `0134ca0c` pair:

- `Tinycast_Tablet_API_36`, API 36 with the stock Gboard keyboard: tablet 1920×1200/240 dpi at 100% and 200% font; short landscape 2340×1080/420 dpi at 100% and 200%; phone 1080×2340/420 dpi at 100% and 200%.
- Android 8.0 (API 26) phone emulator with its stock AOSP keyboard, at 100% and 200%.
- **Phone sheet contracts (phone and API 26, 100% and 200%):** with the keyboard shown, Emoji and launcher Actions dock to the palette bottom at full width, inside the palette. The header and a 48 dp close button are present, and every Emoji action row is at least 52 dp and complete inside the visible list (the first launcher row likewise). Closing returns the caller's query with focus. The phone **Copy** button keeps "Copy to Clipboard" as its accessible name.
- **Back contracts:**
  - the original Emoji category-menu query probe;
  - Back, Escape and backdrop dismissal, preserving query, range [1,3], filter and selected result on both routes;
  - a canceled repeated Back that keeps the menu, followed by normal Back, query clear and route exit;
  - menu priority over a later-registered route callback;
  - Emoji and launcher Actions with the keyboard shown (two presses) and hidden (one press). These now exercise the sheet on phones and the popover on wide layouts.
- In the Back contracts, every menu-dismissal, canceled, keyboard-hiding and query-clear Back asserts its dispatcher owners from the trace: exactly `menu-dispatcher` for a menu dismissal, none for a canceled or keyboard-consumed press, and exactly `emoji-dispatcher` for the query clear. Route-exit presses are checked by route state and counters instead. Window-observer and trace-property restoration are asserted.
- **Other cases:**
  - Range placement before and after keyboard setup is a range diagnostic using backdrop dismissal, not a Back contract. It runs on tablet 100% only.
  - Regressions on the same pair: keyboard Hide/Show and owned callback guards, empty-feedback readability, touch Clear, pointer Clear and retained-callback guards, and committed/multilingual input on API 36 and API 26. The two input regressions use the owned test keyboard; everything else uses the stock system keyboard.
- **Touch readability** ([records](validation/emoji-menu-back/v8/readability/)): the existing `TouchReadabilityDeviceTest` runner passes 24/24 on phone and narrow (840×1600/420 dpi, 320 dp) at 100% and 200%. That covers launcher, categories, calculator, Emoji, and extension List and Grid actions. A matched run on the previous PR head app passes narrow 200% 6/6. This confirms the sheet's v7 regression at that profile (below) is fixed, not pre-existing.

The original query probe needs the tablet or landscape layout, so it runs on those four API 36 profiles.

All seven matched controls run on the same final instrumentation, fail only at their intended assertions (9 failures) and pass all 113 restoration checks ([logs](validation/emoji-menu-back/v8/control-logs/)):

| Control app | Contract | Native result |
| --- | --- | --- |
| `1dc1a1ce` | Emoji query after one Back | query `''`, expected `face` |
| `0b535d8f` | Menu priority over a later route | route closed; menu not dismissed |
| `0b535d8f` | Range across a menu round trip | [1,3] collapses to [3,3] |
| `1dc1a1ce` | Emoji Actions, keyboard shown, first Back | menu closed instead of staying |
| `1dc1a1ce` | Launcher Actions, keyboard shown, first Back | menu closed instead of staying |
| `867f9917` (previous PR head) | Emoji and launcher Actions sheet, phone 100% | popover, no close button |
| `867f9917` (previous PR head) | Emoji and launcher Actions sheet, phone 200% | popover, no close button |

The searchable Back controls fail before their after-Back capture, so only their menu-open frames exist. The sheet controls capture the old popover before failing.

## Host checks

At `6b7d5fa9`, after `./gradlew clean`, [the host record](validation/emoji-menu-back/v8/checks/host-6b7d5fa9.json) shows these fresh results:

- 343/343 JVM tests;
- 135 Python cases in each of normal and optimized modes;
- two successful builds;
- lint with 0 errors and 56 warnings for debug and benchmark.

JVM tests can't usefully exercise the `KEYCODE_BACK` guard: the unit-test Android stubs return 0 for `KeyEvent.keyCode`. The device trace assertions cover it instead. Exact-head hosted CI is reported on the PR.

## Preservation and recovery

[Final preservation](validation/emoji-menu-back/v8/final-preservation.json) passes **16 of 16** read-only checks: AVD/API, unprivileged shell, idle instrumentation, geometry, font and four-field IME settings on both emulators, both original modern APKs, all 573 original modern app files (byte-equal), and absent packages on the legacy emulator.

That result follows a recorded recovery. The touch-readability runner restores its own settings and fixtures, but it leaves its candidate APKs installed and replaces `files/readiness-evidence/`. Its own check doesn't cover either. The first preservation pass after v8 therefore [failed 3 of 16](validation/emoji-menu-back/v8/final-preservation-before-recovery.json): the original APKs and the file set. Touch-search cohorts that ran after a readability run captured and restored the readability candidate APK, not the original. Each of those cohorts still installs and tests its own pair, so their results are unaffected.

[The recovery record](validation/emoji-menu-back/v8/readability-recovery.json) reinstalled both private original APKs, keeping data. It removed the 45 foreign evidence files and restored 23 original files byte-for-byte from the pre-slice snapshot, including the ProfileInstaller marker. All 9 checks passed. The earlier 15/16 ProfileInstaller explanation ([v6](validation/emoji-menu-back/v6/profile-marker-record.json)) is superseded by this byte restore. Private originals and settings values are compared by digest and remain unpublished.

The v6 restoration timeout and its [recovery](validation/emoji-menu-back/v6/restoration-recovery.json) remain recorded.

## Retained failures and history

[The v8 registries](validation/emoji-menu-back/v8/registries/), [v5/v6 registries](validation/emoji-menu-back/v6/registries/) and [round-1 records](validation/emoji-menu-back/round1/) keep all 453 executions, including 56 failures:

- **Round 1 (81 executions, 18 failures):** diagnostics, v2/v3 candidates and the first audited head `58eb26dc`, whose 14 cohorts used the broader Emoji selection rule.
- **v5 at `867f9917`:** a 7-method cap rejected one cohort before execution. Android 8 then failed both Actions cases in the keyboard-hidden phase. The frames show a test race: the menu's own keyboard appeared after the fixture had sampled visibility, so the next Back correctly hid the keyboard. `ded3f817` makes the fixture wait for the menu's opening intent; the app did not change.
- **v6:** a restoration timeout, recovered separately (no credit).
- **x7 (stale build):** an incremental Gradle build produced an APK with the new footer but the old menu code; there was no "Close actions" string in its dex. Its 4 sheet failures show the old popover. The round-1, v5 and v6 APKs were checked the same way and match their sources. Every later build ran `clean` and asserted the sheet code.
- **x8:** exploratory clean phone run, 8/8 passing, no credit.
- **v7 at `13c27299`:** the full matrix passed 77/77, but the readability subset failed narrow 200% 4/6. A long extension action (348 px) could not fit the sheet list (338 px), because the sheet header and 16 sp rows took that height. `6b7d5fa9` drops the handle and subtitle at large font or on a short palette. v7 earns no credit.

None of these earns current credit.

## Screenshots

[37 published frames](validation/emoji-menu-back/v8/published-screenshots.json) come from the final pair, its controls and the readability runs. Touch-search frames equal their runner-recorded capture hashes, all of which are in [capture-hashes.json](validation/emoji-menu-back/v8/capture-hashes.json). The two readability frames are hashed in the manifest. Earlier rounds' frames remain at their commit-pinned paths in the PR history.

Known API 26 limits visible in these frames:

- Newer emoji render as missing glyphs, a pre-existing platform font limit.
- In the v6 frames at 200% with the keyboard up, the Emoji footer was cut off at "Copy to". The phone footer is now one short row, and the sheet covers it while Actions is open. Footer reachability with the keyboard up is still not certified (#69/#71).
- After system Back hides the keyboard, the menu's keyboard button keeps showing its Hide icon and label, because Compose's keyboard-visibility state lags the system on API 26. This is not only cosmetic: by the code, the next tap requests Hide and does nothing, so showing the keyboard takes a second tap, and TalkBack announces the wrong action. Inferred from code and not executed. Before this change, Back closed the menu instead, so this PR makes the state reachable by Back. Tracked in [#118](https://github.com/moikdev/droidcast/issues/118). In the v8 Android 8 frame after keyboard-first Back, the button shows its Show icon. So the stale state is intermittent, not fixed, and #118 stays open. The behavior assertions use the platform inset state.

## Limits

These are emulator results with injected Back key events and stock system keyboards. Gesture Back is not exercised, including stock AOSP gestures on API 36, which reach the same dispatcher without a key event. Physical devices, OEM navigation, predictive-back animation, TalkBack, hardware keyboards and third-party keyboards are not certified. Menu arrow, Tab, Enter and Ctrl/⌘K navigation is unchanged in code apart from removing Back, but it was not re-run on the final pair; only Escape was. There is no device evidence for API 27–35; API 33–35 use the same key route as API 26 for this app (no `enableOnBackInvokedCallback`). Extension List/Grid Actions are covered only by the readability runner at phone/narrow. Other production menu routes (folder filters, the reviewed-text chooser, Settings) are not executed, and the sheet on those routes is unverified. Readability ran on phone and narrow only, because wide profiles keep the popover; menu priority is shown on a fixture route built from the production menu host. Broader #69/#71, #89, full Store parity and production signing/migration remain open.
