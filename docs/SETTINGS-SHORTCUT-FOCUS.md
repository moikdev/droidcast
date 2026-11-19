# Opening Settings with Ctrl+Comma

Droidcast opened Settings in all four measured transitions on the Android 16 tablet. On both APKs, the input dispatcher sometimes reported a focused application with no focused window while switching activities. Each transition subsequently reached the General page. The intermittent no-focused-window ANR remains unexplained. [Issue #121](https://github.com/moikdev/droidcast/issues/121)

The historical failure stopped after three successful shortcut checks. The screenshot showed the palette. The subsequent hierarchy contained the system ANR dialog, Android reported an input-dispatch timeout, and the captured main-thread stack was in the message queue. The earlier broad run on that same APK had already passed the Settings step before encountering the separate typing discrepancy in #59. [Historical screenshot, hierarchy and stack](HTML-VIEWER-EVIDENCE.md#regression-checks)

## Matched tablet runs

On the dedicated API 36 arm64 tablet at 1920×1200 and 240 dpi, the guarded hardware runner recorded and saved Ctrl+Alt+Space, then sent Ctrl+F to focus the Settings search. With the shortcut service bound, the global chord opened the palette. Ctrl+Comma then opened General. The final hierarchy contained the page description and Global shortcut, and the focused window was Settings immediately before and after the screenshot. The hierarchy and screenshot were captured sequentially. [Saved binding](screenshots/settings-focus/current-binding.png) · [Current destination hierarchy](validation/settings-focus/final/2-current/destination.xml)

| Run, in order | Destination | Input samples | Samples with an app selected and no focused window | Host one-minute load | Guest one-minute load |
| --- | --- | ---: | ---: | ---: | ---: |
| Earlier 1 | [General](screenshots/settings-focus/1-earlier-destination.png) | 22 | 2 | 15.06–19.75 | 1.16–2.01 |
| Current 1 | [General](screenshots/settings-focus/2-current-destination.png) | 23 | 2 | 24.89–26.75 | 2.99–3.17 |
| Current 2 | [General](screenshots/settings-focus/3-current-destination.png) | 22 | 1 | 36.78–42.96 | 2.22–3.67 |
| Earlier 2 | [General](screenshots/settings-focus/4-earlier-destination.png) | 21 | 2 | 24.01–25.75 | 3.41–3.70 |

Each run passed all four UI checks and all twelve trace checks. The event log recorded entry of input focus and activity resume callbacks in palette-then-Settings order. The samples found a ready, drawn window for each activity and enabled, unfrozen input dispatch with the input filter active. No Droidcast ANR event appeared in these four trace intervals. The guest event log uses guest epoch time. Host phase markers and sample boundaries use monotonic time. Input, activity, window and load queries run sequentially. The gaps between samples leave the continuous duration of a focus change unknown, and the load readings do not establish what triggered the historical timeout. [Per-run traces](validation/settings-focus/final)

The measured APKs were the earlier `f29d95f5` and current `b4f3fe7c` binaries already used for the HTML Viewer investigation. Installed bytes were checked in each run. The host script at `8ab600c4` records only selected activity names, state flags, timing and load from the system reports. The four earlier exploratory passes used `9829feba`, before the final trace assertions and screenshot focus guards. Their receipts are retained separately. [Binary and script identities](validation/settings-focus/identities.json) · [Exploratory traces](validation/settings-focus/initial)

## Omitted-shortcut control

On the current APK, a separate runner omitted the Ctrl+Comma injection and labeled the omission in its trace. The first three cases passed. The destination assertion timed out while the palette was visible. The trace also lacked a return to Settings in both the input-focus and resume events. This control exercises detection of a missed transition. The historical ANR has a different failure record. [Control screenshot](screenshots/settings-focus/omitted-comma-palette.png) · [Trace and source difference](validation/settings-focus/omit-comma)

## Full shortcut suite and local checks

The unmodified one-round suite passed all 16 cases on the current APK, including the Settings destination, warm document shortcut, exact external typing and service reconnect. This run used the normal runner without the focus sampler. Each of the four measured runs, the omitted-key control and the full-suite run passed four restoration checks. The outer harness also verified five conditions for each run, covering the installed app hash, preference bytes, original accessibility settings, unprivileged adbd and the app UID. These comparisons cover the same emulator boot. [Full suite](validation/settings-focus/broad/cases.json) · [Full-suite General page](screenshots/settings-focus/broad-destination.png)

After integration with the keyboard-observation change, the debug and benchmark app and instrumentation builds succeeded at `e1b64df8`. The JVM task executed 343 cases with zero failures, errors or skips. Python passed 146 tests in each of normal and optimized mode. Both lint variants reported 47 warnings and zero errors. The device runs used the two frozen APKs listed above. The identity file records the later build products separately. [Host results](validation/settings-focus/identities.json) · [Original capture bindings](validation/settings-focus/bindings.json)

## Installed extension loading at activity startup

Settings and the palette both create a `PaletteStore`. Previously, its constructor opened the installed-extension directory and read and parsed each extension's Store metadata and command manifest on the UI thread. The initial directory scan now runs on the I/O dispatcher. Shortcuts and the Settings shortcut inspector wait for the installed inventory and app list before resolving a command. Store installation and removal await the initial scan. Installed and reviewed-text views can show loading while the inventory is pending.

On the dedicated API 36 emulator, a controlled application context supplied an isolated directory with one installed command to the production store. The instrumentation counted calls to obtain the catalog's files directory. It held the worker at that access and checked whether the UI could report loading while the command was still unavailable. After release, it checked the exact command title. These observations measure thread ownership and command readiness. Activity launch duration, real-world collections and the cause of the earlier ANR in #121 remain unmeasured.

| App and layout | Files directory accesses on UI / worker | UI responded with inventory pending | Exact command resolved |
| --- | --- | --- | --- |
| Earlier tablet, `56687e37` | 1 / 0 | No worker to hold | Yes |
| Repaired tablet, `5ed3508f` | 0 / 1 | Yes | Yes |
| Repaired phone layout, same emulator and APK | 0 / 1 | Yes | Yes |

All three invocations used the same instrumentation built at `38cbccef`. The earlier run failed the zero-UI-access assertion. Its legacy runner retained ownership after the failure, and the next attempted run stopped at the ownership check. The separate recovery verified fixture removal, the clipboard restoration receipt, preferences, device settings and original APKs before releasing ownership. The two repaired runs passed, with 19 and 21 successful cleanup checks respectively and verified package restoration. The screenshots show the actual palette displaying 42 as an app smoke check. Thread and command observations are in the probe records.

Both APK variants and instrumentation built. The debug JVM suite passed 358 tests, Python passed 150 tests per interpreter mode, and lint reported zero errors and 57 warnings per variant. The 23 packaged assets and notices match between the compared apps. See the [device runs](validation/extension-catalog-startup/runs.json), [host results](validation/extension-catalog-startup/host.json), [source inventories](validation/extension-catalog-startup/sources.json) and [capture identities](validation/extension-catalog-startup/bindings.json).
