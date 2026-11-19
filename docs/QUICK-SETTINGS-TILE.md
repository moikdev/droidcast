# Quick Settings tile activation

The tile test now waits for Android’s Quick Settings panel to appear before sending the activation command. It checks that the DroidCast search editor is visible and that the palette owns window focus. The editor has the same ten-second deadline as the original launch assertion. The test temporarily adds an absent tile and restores its previous membership on success or failure.

## What the timeout looked like

On the dedicated API 36 emulator, I reproduced the timeout on the tablet (1920 × 1200, 240 dpi) and phone (1080 × 2340, 420 dpi) layouts. Both used the same production APK and test APK. These runs collected their first screenshot, hierarchy and system state only after the editor deadline expired. In both cases Android reported the palette as resumed. Quick Settings still held window focus, its panel was expanded, and the accessibility hierarchy contained the tile but no palette editor. The tile service had a process attached. This distinguishes a covered palette from a missing activity launch in these two runs. The service sample does not give the individual callback times.

| Observed state | Tablet | Phone |
| --- | --- | --- |
| Timed-out command before panel synchronization | [Quick Settings still visible](screenshots/quick-settings-tile/before-shade.png) | [Failure observations](validation/quick-settings-tile/runs/matrix-phone-fast/phases.json) |
| Pointer target on the final harness | [Tablet tile](screenshots/quick-settings-tile/tablet-tile.png) | [Phone tile](screenshots/quick-settings-tile/phone-tile.png) |
| Focused editor after tapping | [Tablet palette](screenshots/quick-settings-tile/tablet-palette.png) | [Phone palette](screenshots/quick-settings-tile/phone-palette.png) |
| Destination | [Back at Android Settings after tapping outside](screenshots/quick-settings-tile/tablet-return.png) | [Following DroidCast Settings navigation test](screenshots/quick-settings-tile/phone-settings.png) |

In the tablet control that deliberately omitted the command, the shade also remained visible after ten seconds. Android Settings was still the resumed activity. The control’s screenshot is an ordinary Android screen and the failure appears in JUnit. [Control screenshot](screenshots/quick-settings-tile/omitted-command.png)

The earlier membership test also left behind a tile that was absent at entry. It did so both in a failed exploratory tablet run and in a passing comparison on the production APK used for the final matrix. The guarded outer runner restored the original ordered tile list after recording each outcome. The revised test checks the configured component and only removes a tile it added. Cases starting with the tile already installed check its original position too.

## How to interpret the runs

The input modes exercise different paths. The normal test sends the SystemUI activation command after the panel appears. The optional pointer case taps the enabled SystemUI tile. The optional failure-only collector takes its first full observations at failure. In full capture mode each screenshot and hierarchy takes time before the next action. Individual focus, service, layout and load samples are sequential observations. A single load average cannot establish an emulator scheduling cause. The opt-in success cases also tap outside the palette and verify return to Android Settings.

The earlier 68/70 tablet suite and the later updater timeout lacked these observations at the failing assertion. Their causes remain open under #37. This investigation covers the current API 36 emulator and the standard Quick Settings panel on the two stated viewports. The released r9 versus updater comparison, physical devices and other SystemUI implementations remain unverified.

## Validation

All ten final positive invocations passed. Each layout covered the default command case, command with capture only on failure, command with full captures and return, physical tile activation and return, and the subsequent DroidCast Settings navigation case. Settings ran in a separate instrumentation invocation after restoration. The ten positives had 112 successful host restoration checks. The final omission control failed the editor assertion as expected and had eleven successful restoration checks.

Across the exploratory, comparison and final stages there were 25 JUnit invocations, 20 passes, five failures and 276 successful host restoration checks. The five failures comprise the original exploratory launch timeout, two pre-guard command timeouts, and two intentional omission controls. One further legacy comparison passed JUnit but failed to restore tile membership within the test. The host measured the difference before restoring the original layout. Historical suite totals are separate. [Per-run results](validation/quick-settings-tile/runs.json) · [APK, host and count summary](validation/quick-settings-tile/summary.json)

The pre-guard and final comparison used identical production APK bytes (SHA-256 `d35fbbf0b119d0543a74b786aab81cc425784e1620ed91074505d234412602cd`). The app has the `f5ef2d8a` build identifier. Instrumentation built from `f5ef2d8a` and `dce49984` differs only in the `classes3.dex` ZIP payload. The initial exploratory runs used separate binaries recorded per case. All eleven packaged assets match the preceding backup build.

The debug and benchmark builds and both instrumentation builds passed. The first combined Gradle invocation used the wrong test build type and failed task selection. The two variant-specific invocations succeeded. The JVM run at the pre-guard source passed 346 cases and the final debug invocation reused it. Python passed 146 tests per mode. Final lint produced zero errors and 47 warnings per variant. [Build and test log identities](validation/quick-settings-tile/summary.json)

Published PNGs and XML are byte copies of the selected emulator captures. The binding list also covers the recorded phase observations and JUnit logs. Private preference and ordered layout snapshots remain local. The shell ran unprivileged and the test used the dedicated API 36 AVD. The wrapper checked both installed APKs and restored the layout and preferences. Its durable result records booleans for these checks. The exploratory host-script hash is recorded per run. Its first revision was subsequently edited in place. [Artifact bindings](validation/quick-settings-tile/bindings.json)
