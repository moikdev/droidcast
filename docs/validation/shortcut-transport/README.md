# Shortcut test daemon recovery

During the API 36 tablet re-audit, the hardware runner’s root request exited 1 while the emulator was restarting adbd. The first screenshot attempt and the secure-settings cleanup command both failed. The subsequent unroot request also exited 1. After the runner exited, an independent shell identity read returned UID 0. The run stopped before the first shortcut injection.

The host retained ownership. A separate recovery restarted adbd as an ordinary shell and verified UID 2000 before restoring the original application APK. The host verified the instrumentation APK, palette and emoji preferences, accessibility settings and previous hierarchy dump against the saved baseline, checked the app UID and released the lease. The original attempt still counts as a cleanup failure. [Issue #161](https://github.com/moikdev/droidcast/issues/161)

The repaired runner checks the emulator name and shell UID when the transport reconnects. A disconnected root command can succeed if the shell later confirms UID 0 on the intended emulator. An unverified transition fails after a bounded wait. State restoration runs only once the suite has entered its mutation phase. Cleanup retries unroot if the same emulator still reports UID 0 and verifies UID 2000 before reporting success. Suite, capture and cleanup failures are recorded separately.

## What ran

| Run on the dedicated Android 16 tablet | Outcome |
| --- | --- |
| Current app, original runner | An input-dispatch ANR occurred before the first case completed. All four inner restoration checks passed. |
| Earlier app, original runner | The transport failure described above stopped setup. Separate recovery passed. |
| Earlier and current apps, original runner, one retry each | Each completed all 16 cases, including the TXT viewer, external Settings typing and service reconnection. Both runs passed the four inner restoration checks and host comparisons. |
| Current app, repaired runner, measured Settings workflow | All four cases passed, including Ctrl+F and Ctrl+Comma. Both daemon commands returned 0. All four inner restoration checks passed. The host verified the original packages, preferences, consent and dump after the run. |

The smoke run used runner sources from `34535a7e`, byte-identical to the corresponding files in the rebased `255c01cc`. Its application APK was the frozen Quicklink build from `0c3ef167`. The earlier APK was built from `7da6c3e3`. The ordinary hardware suite injects Linux input events through the dedicated emulator helper. The captured Settings General page shows the configured shortcut following the Ctrl+Comma transition. [Device records](device.json) · [Android smoke screenshot](../../screenshots/shortcut-transport/settings-workspace.png)

The ANR on the current app occurred while entering “Keyboard shortcuts.” Android reported a key-up waiting 5,024 ms on MainActivity. The first screenshot showed Settings Shortcuts and the later hierarchy showed Android’s ANR dialog. The main-thread sample was in the framework’s service-unbind path. This event preceded Ctrl+Comma. Its cause and its relationship to the earlier no-focused-window incident remain unknown. [Issue #121](https://github.com/moikdev/droidcast/issues/121#issuecomment-7007438248)

## Controlled checks

Five host tests exercise a disconnected root acknowledgement followed by verified success, an unroot retry, an unavailable transport, a different emulator identity, and the emulator-console name fallback. An independent subprocess stub also ran the original and repaired entry points with root denied and the shell staying unprivileged. Both runs failed setup. The original runner attempted secure-setting mutations and force-stop during cleanup. The repaired runner skipped both, recorded the failed setup and verified UID 2000. The probe ran entirely on the host. The Python suite passed 157 tests in each interpreter mode. [Host records](host.json)

The repaired hardware smoke exercised successful daemon transitions. Its injected transport failure coverage comes from the controlled host checks. ANR causation, timing bounds and physical-device behavior remain unverified.

Subsequent debug and benchmark app and instrumentation builds used source `255c01cc`. The debug JVM suite passed all 358 cases. Lint reported 48 warnings and zero errors on each variant. The tablet smoke used the separately identified frozen application.
