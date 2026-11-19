# Action-search keyboard behavior after system Back

On a phone, open Emoji Actions with the keyboard visible and press Back. The keyboard should hide, the action sheet should stay open, and its keyboard button should offer Show. One tap should reopen the keyboard with action search focused. With the keyboard hidden, Back should close the sheet and return to the caller.

## What failed and what changed

On Android 16, the observed result depended on how focus reached action search. When Android delivered Back to the activity with the keyboard visible, the previous menu handler dismissed its menu unconditionally. Two five-case phone runs on app `b12e1fa6` each failed this interaction. The second run captured a visible keyboard immediately before Back. Its trace then shows the menu dispatcher entering with the menu open and returning with it closed. A later isolated run on the same app passed. With the final instrumentation and the same six methods, the earlier app passed five and failed the first-tap dispatcher assertion. The repaired app passed all six. Both runs passed all 17 restoration checks.

The dispatcher now checks root-window keyboard visibility for an active searchable menu. If the keyboard is visible, it requests keyboard dismissal. If hidden, it dismisses the menu. The final Android 16 phone run observed the handler taking the keyboard-only branch. A separate test that opens Actions from a hidden caller keyboard observed Android consuming the press before it reached the activity. After each path, the test checked the label, tapped once, and observed the actual keyboard and focused action search.

On Android 8, an earlier 200% frame shows the Hide icon with the keyboard already hidden. The menu now observes root-window visibility during layout and reads it again when tapped on API 26–29. The November runs exercised API 26. Both the current app and the earlier builds reopened the keyboard on the first tap. A controlled replay of old insets also passed on both sides and was removed from the test suite. The original stale-icon frame remains evidence for #118. Whether a tap failed in that historical state remains undetermined. [Historical Android 8 frame](https://github.com/moikdev/droidcast/blob/746a45401831527c28665dab5fafae223f0d1270/docs/screenshots/emoji-menu-back/v6-legacy200/emojiactionsbackhideskeyboardbeforeclosingmenu-actions-shown-first-back.png)

## Builds and tests

The debug app was built from `487c29e6` and the final instrumentation from `6eb05bf2`. The later commit changed the test and its runner allowlist. The identity record contains the APK hashes, source inventory and byte comparisons for the eleven assets from the PR base. [Build identities](validation/emoji-menu-back/keyboard-first/identity.json)

The final tests used the stock keyboards on the Android 8 phone and Android 16 tablet emulator. The modern phone profile was 1080×2340 at 420 dpi. The Android 8 phone retained its native 1080×1920 at 420 dpi. The readability tests additionally exercised 840×1600 at 420 dpi. Phone and narrow tests used 100% and 200% text. The tablet Back and keyboard regressions used native 1920×1200 at 240 dpi and 100% text.

The final APK pair passed **33 touch and input tests across six runs** with all 84 restoration checks successful. The same pair passed **24 readability tests across four runs** with all 64 cleanup checks successful.

The focused suites cover pointer opening, single-tap keyboard recovery, Back, Escape, selection range, caller focus, sheet geometry, scrolling and the Emoji footer's persistent status region. The separate readability suite covers launcher and extension menu flows. Editor query and selection setup is programmatic. System Back is an injected key event. The results cover the tested emulators and keyboards. [Cohorts](validation/emoji-menu-back/keyboard-first/cohorts.json) · [Device observations](validation/emoji-menu-back/keyboard-first/observations.json) · [Readability results](validation/emoji-menu-back/keyboard-first/readability.json)

The first Android 16 run also exposed a restoration error. The original debug app had a lower version code, and Android rejected a normal reinstall. Recovery verified both original binaries and the app files and preferences before releasing the retained lease. The guarded runner now permits downgrade only when restoring its captured original APKs. A separate disposable emulator instance ran the readability tests in read-only AVD mode. [Recovery record](validation/emoji-menu-back/keyboard-first/separate-recovery.json)

Debug and benchmark app/test builds succeeded. All 343 JVM tests passed with zero skips. The Python runner suite passed 135 tests in each of normal and optimized modes. Both lint reports recorded 47 warnings and zero errors. [Host checks](validation/emoji-menu-back/keyboard-first/host.json)

The debugging history includes a benchmark lint run that failed inside Kotlin FIR analysis. Subsequent benchmark builds and lint completed. Device records preserve whole failed runs and separately identify controls that passed. Earlier v8 test results remain attached to their original binaries. [Earlier report](EMOJI-MENU-BACK.md)

## Captured states

| Device and action | Screenshot |
| --- | --- |
| Android 8 at 200%, immediately after Back hides the keyboard | [Action sheet and Show button](screenshots/emoji-menu-back/keyboard-first/api26-200-hidden.png) |
| Same Android 8 interaction, after one tap on Show | [Focused action search and visible keyboard](screenshots/emoji-menu-back/keyboard-first/api26-200-first-tap.png) |
| Android 16 at 100%, after activity-handled Back | [Action sheet retained](screenshots/emoji-menu-back/keyboard-first/api36-100-after-back.png) |
| Android 16 at 200%, Emoji Actions with the keyboard visible | [Three Emoji actions](screenshots/emoji-menu-back/keyboard-first/phone-200-sheet.png) |
