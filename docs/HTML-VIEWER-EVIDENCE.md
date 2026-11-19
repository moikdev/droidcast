# Opening and verifying a recent TXT document

During the API 36 tablet run recorded in #41, the viewer displayed the fixture filename and sentence. Its accessibility hierarchy included the filename and two nested WebView nodes, both without body text. The hardware test timed out looking for the sentence. A second retained hierarchy from the same evidence directory exposes the sentence as a TextView. The original hierarchy and screenshot establish the discrepancy. Its cause inside the stock viewer remains undetermined. [Issue #41](https://github.com/moikdev/droidcast/issues/41)

The hardware runner checks the focused viewer package around capture and requires the fixture title outside the WebView. It reads the body only inside the viewer's WebView. If Android exposes text there, the sole sentence must match, including case and punctuation. If Android exposes no body, Tesseract checks the visible pixels in the WebView rectangle. Both paths save the hierarchy and screenshot used for that decision. The result records `accessibility_text` or `screenshot_ocr`. The OCR path needs Pillow and the Tesseract English language model on the host.

| Evidence | Result |
| --- | --- |
| Original screenshot and hierarchy with the body omitted | OCR reads the expected sentence. The XML supplies the title and bounds. [Screenshot](screenshots/viewer-body/historical-visible.png) · [Hierarchy](validation/viewer-body/historical-missing-body.xml) |
| Retained hierarchy exposing the document text | The exact accessibility text passes. A changed filename or a missing final period fails. [Hierarchy](validation/viewer-body/historical-exposed-body.xml) |
| New same-title control on the API 36 tablet | The viewer opens a different TXT with the identical filename. Its XML again omits the body. OCR reads “Tinycast wrong document control.” and rejects the expected fixture sentence. [Screenshot](screenshots/viewer-body/wrong-body.png) · [Hierarchy](validation/viewer-body/same-title-wrong-body.xml) |

The new control used an unprivileged shell and a generated MediaStore document on a disposable session of the Tinycast_Tablet_API_36 emulator at 1920×1200 and 240 dpi. The test verified its bytes before opening the stock HTML Viewer and confirmed deletion afterward. That launch exercises the verifier directly. The historical screenshot came from the original shortcut route. The original APK hash was unavailable in that run's receipt.

These checks apply to a one-line English fixture. OCR verifies its visible rendering. It gives no assurance about accessibility exposure, offscreen content, other document types or other viewer apps.

## File search and hardware shortcuts

On the API 36 tablet, the app displayed the persisted file in search. Tapping **Open File** opened HTML Viewer and the exact body was accessible. The verifier also rejected the other sentence against this capture. [Droidcast search](screenshots/viewer-body/app-file-search.png) · [Viewer destination](screenshots/viewer-body/app-file-viewer.png)

An earlier isolated hardware check on the original app used Droidcast’s Actions menu to assign Ctrl + Alt + P, switched to Android Settings, and delivered that chord through the emulator keyboard device. The exact title and body passed through the accessibility path. All four checks for original preferences, accessibility consent, adbd identity and the app process passed. [Shortcut destination](screenshots/viewer-body/shortcut-destination.png) · [Hierarchy](validation/viewer-body/shortcut-destination.xml)

The dedicated hardware runner requires the existing test TXT fixture. Its focused command is `python3 scripts/test-configurable-shortcuts.py --serial emulator-5580 --document-only`. The test now filters the Actions menu before tapping **Set keyboard shortcut**, which can otherwise be outside the visible list when the IME is open.

Two earlier broad attempts stopped before the TXT case. The first timed out at keyboard service binding and the second passed seven cases before it could not reach the Wi-Fi shortcut recorder. A focused run on the merged app also stopped during setup. Its captured menu showed **Open File** above the keyboard, with the shortcut action outside the exposed rows. All three attempts passed the four restoration checks. The latter capture demonstrates a menu-setup problem in that focused run. The two earlier failures have no established common cause.

## Warm palette failure and repair

During the full hardware run, a separate failure occurred after ten successful cases. From Android Settings, Ctrl + Alt + P brought an existing palette to the foreground and left it there. A focused warm-start run reproduced it on the earlier APK. [Earlier palette](screenshots/viewer-body/warm-before.png) · [Issue #120](https://github.com/moikdev/droidcast/issues/120)

A diagnostic build logged a fresh invocation, the current binding and a resolved file command. Its MIME lookup finished while the activity was resumed and the window still lacked focus. The route, query, category and focus epoch all matched. The window gained focus 26 ms later. By then, the document launch had already been discarded. The trace comes from a separate APK containing only the diagnostic source additions relative to the earlier app. [Trace](validation/viewer-body/diagnostic-timing.log) · [Diagnostic source changes](validation/viewer-body/diagnostic-only.patch)

The palette now waits for window focus before dispatching a file shortcut. It rechecks the binding and expiry before execution. Leaving the activity, running another command, or accepting a newer shortcut cancels a pending request. The provider lookup still checks focus and navigation before opening a viewer. The final APK passed the same warm hardware route, reaching the stock viewer with the requested file and exact sentence. [Repaired destination](screenshots/viewer-body/warm-after.png) · [Hierarchy](validation/viewer-body/warm-after.xml)

The warm activity failure occurred before HTML Viewer opened. The historical accessibility omission occurred inside an already visible HTML Viewer. Each has a separate control and outcome. The hardware observations use an API 36 arm64 tablet emulator at 1920×1200, 240 dpi.

## Regression checks

The same instrumentation APK exercised a file shortcut while a test dialog held window focus. On the earlier app, the request was already gone when the assertion checked for it. On the repair, the request remained pending. The test removed the binding, dismissed the dialog, observed the rejection feedback and verified that the file's usage count did not increase. This passed on the tablet and phone profiles. The tablet cohort also covered authenticated invocation, replay and rebinding, an unresponsive provider, retry, and supersession by a newer command.

The first broad run on the final app passed fourteen cases, including the warm document route. The next case expected `qwerty` in Android Settings and got `qwerrty`. Its screenshot and hierarchy agree. This is a separate external-input failure tracked under #59. All four restoration checks passed. [Settings failure](screenshots/viewer-body/settings-typing-failure.png) · [Issue #59](https://github.com/moikdev/droidcast/issues/59)

The full-suite repeat on the same APK passed three cases and stopped before the document shortcut. The palette screenshot preceded an accessibility dump showing an Android ANR dialog. Android recorded an input dispatch timeout because the application lacked a focused window. The captured main-thread stack was in the message queue. These observations do not identify the cause. The four restoration checks passed. [Palette screenshot](screenshots/viewer-body/before-settings-anr.png) · [Subsequent hierarchy](validation/viewer-body/settings-anr.xml) · [Main thread](validation/viewer-body/anr-main-frames.txt) · [Issue #121](https://github.com/moikdev/droidcast/issues/121)

Local debug, debug instrumentation and benchmark builds succeeded at `b4f3fe7c`. All 343 JVM cases and 141 Python cases in each of normal and optimized mode passed. Lint reported 47 warnings and zero errors for debug and benchmark. The full shortcut suite remains failed on this build. [Binary identities and individual outcomes](validation/viewer-body/results.json)
