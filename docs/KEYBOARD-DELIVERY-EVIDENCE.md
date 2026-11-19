# Synthetic keyboard delivery on Android 16

In the second run on the API 36 tablet, the Droidcast palette visibly changed from `menuKeyboardProbe27182` **before** hierarchy attachment to `menuKeyboardProbe271828` **afterward**. The final `8` is missing in the earlier screenshot. The first hierarchy, collected between the two screenshots, already contains the complete query. Visual inspection and an English OCR reading of the same editor rectangle agree on all three palette screenshots. [Before](screenshots/keyboard-delivery/palette-preframe-before.png) · [Hierarchy](validation/keyboard-delivery/palette-preframe-typing-01.xml) · [After](screenshots/keyboard-delivery/palette-preframe-after.png) · [Crop coordinates, readings and frame hashes](validation/keyboard-delivery/palette-pixel-readings.json)

Both runs used the dedicated Android 16 arm64 tablet at 1920 × 1200 and 240 dpi. The first run requested the hierarchy immediately after ADB input returned. Its first hierarchy and later screenshot both contain the complete query. In the second run the host completed the pre-observer capture and its focus checks at 1.730 seconds, and recorded the first hierarchy at 3.888 seconds, measured from the return of ADB input. These are host observation times. Each run passed 17 cleanup checks. [First run](validation/keyboard-delivery/palette-immediate.json) · [Second run](validation/keyboard-delivery/palette-preframe.json)

Issue #59 records an earlier truncated palette query and a separate Settings frame showing `qwerrty` for six requested letters. The new incomplete frame establishes a transient visible prefix in this palette run. It cannot distinguish pending input delivery, a later editor change or delayed rendering. The production app supplied no per-key or pre-attachment model trace, and the interval also included observer attachment. The native editor comparison below measures those signals in the test APK. Production code and the 11 assets packaged with the tested app are unchanged. [Historical observations](https://github.com/moikdev/droidcast/issues/59) · [Earlier Settings capture](screenshots/viewer-body/settings-typing-failure.png)

## Receiver comparison

All 16 combinations passed. Each combination uses one service state (off or on), one input transport (ADB text or Linux keyboard events), one payload (six letters or the 23-character mixed-case query), and one hierarchy observer (standard suppression or preservation of accessibility services). The Linux path uses the emulator's qwerty2 device. The test records receipt times, event times, repeat counts and key-down/up order, including shifts for the two uppercase letters. The editor records the lengths and times of text changes. The production app has no new input listener. [Complete results](validation/keyboard-delivery/matrix.json) · [Example event trace](validation/keyboard-delivery/cases/on-preserve-linux-long-none/case.json)

Before entry the runner checks window focus and the service's binding. After entry it captures the focused fixture, then signals the observer to attach. While the observer remains attached it samples service binding and captures another frame. The enabled service stayed bound in preserve mode and was absent at the sample in suppress mode. The exact pre-IME sequence was already complete at the pre-attachment sample in every final trial. The host's focus checks and first screenshot consume time before attachment. Continuous service state and the interval immediately after injection are outside those observations.

The editor saw 12 pre-IME events for six ordinary letters, and 50 for the mixed-case payload including shifts. At activity dispatch the corresponding traces held only six and 27 events, even when the text was complete. Counting at activity dispatch had incorrectly rejected the first complete diagnostic matrix. [Short trace](validation/keyboard-delivery/cases/off-preserve-adb-short-none/case.json) · [Long trace](validation/keyboard-delivery/cases/off-preserve-adb-long-none/case.json)

## Controls on the same app and test APKs

| Controlled change | At the editor, before IME handling | Text and pixels |
| --- | --- | --- |
| Omit the final input | 10 events for five letters | Both model and hierarchy read `qwert`. |
| Send an extra R tap | 14 events for seven letters | Both read `qwerrty`. The trace contains two R presses, each with repeat count zero. |
| Delete the last letter inside the test editor after the first capture | All 12 events arrived | The model changed from `qwerty` to `qwert`. The post-attachment hierarchy read `qwert`. |
| Conceal text paint until the first capture finishes | All 12 events arrived | The model held `qwerty` throughout. In the measured text bounds, the first screenshot had zero dark pixels and the second had 405. |

The sampled rectangle is the first line's advance width and font ascent/descent around the baseline. The pixel check counts grayscale values below 128. Text equality comes separately from the editor and hierarchy. These four deliberately altered trials passed their individual expectations. Together with the 16 ordinary trials they share one hardware session and seven successful cleanup checks. [Omitted input](validation/keyboard-delivery/cases/on-preserve-linux-short-drop/case.json) · [Extra input](validation/keyboard-delivery/cases/on-preserve-linux-short-extra-r/case.json) · [Editor change](validation/keyboard-delivery/cases/on-preserve-linux-short-editor-loss/case.json) · [Paint control](validation/keyboard-delivery/cases/on-preserve-linux-short-paint/case.json)

## Reproduce the comparison

Install the matching debug production APK and debug test APK on the dedicated tablet. Start with unprivileged adbd and keyboard-service consent off. The host needs Python with Pillow and Android platform-tools. Supply the same APK files that are installed. The runner checks their digests, verifies the owned tablet and test instrumentation, and creates a fresh output directory. The app runs under its application UID.

```sh
python3 scripts/test-keyboard-delivery.py --serial emulator-5580 --allow-adbd-root \
  --app-apk path/to/app-debug.apk --test-apk path/to/app-debug-androidTest.apk \
  --output artifacts/keyboard-delivery-new
```

For the separate Droidcast palette probe, use the existing guarded `scripts/test-modifier-taps-hardware.py --typing-only` protocol. Use a new output directory when adding `--pre-observer-frame` for the additional screenshot. Its receipt lists observed editor values. Check the `typing_probe.exact` field to establish the exact match. The two real-palette observations have zero ordinary hardware-case entries. Neither supplies per-key delivery data for the production palette.

## Build and evidence identities

The device ran production app SHA-256 `d37d4adc89f3a7c0be4d960dfd9b9d5673c48ff0bfe38055ce16316569acbea8` from the PR #122 build at `b4f3fe7c`, with test APK `e3d1c508bcdfb72d560fad9a07f831b6c09d3cc965be60bdaf9f52368def5849` built at `3ea2b8bf`. The final receiver matrix used the runner from `b5bacfcb`. The two palette probes used `16c4f6f9`. Debug and benchmark app/test builds at `845900d0` succeeded. The checkpoint lists those later APKs separately. All 343 JVM cases passed with zero skips. Both lint variants reported 47 warnings and zero errors. Python passed 143 cases in each of normal and optimized mode at `100310df`. [APK, source and publication hashes](validation/keyboard-delivery/checkpoint.json)

Earlier development runs are separate. The first fixture process lacked the Kotlin runtime. The second ended before the observer received a focused hierarchy. Neither completed a case. The activity-dispatch-only collector rejected all 19 rows of the next matrix. A pre-IME collector subsequently passed 19, and the first paint-control runner passed 20 before the final ordered-event checks. Each run recorded seven successful cleanup checks. The first four exploratory runs used an evolving host script. Its exact earlier bytes were not frozen. [Development accounting](validation/keyboard-delivery/development-runs.json)

The captures cover a synthetic native editor and two palette observations on one emulator and keyboard configuration. The test-window paint control deliberately changes text color. The historical Settings editor's key stream, physical typing, OEM keyboards and input latency remain unverified.
