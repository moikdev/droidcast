# Battery and security settings

Search the DroidCast palette for **Battery settings** to open Android’s battery overview. The tested page shows battery usage, charge level and Battery Saver. **Security settings** opens the system’s Safety Center on Android 13 and later. On the tested Android 16 image its Security & privacy dashboard showed App security and Device unlock. Android owns both pages and the user chooses whether to change a setting.

DroidCast uses the public battery overview and Safety Center intents. On Android 8–12 it selects Android’s older security settings intent. If Android rejects the launch or has no handler, the palette keeps the search and names the unavailable page. These commands use standard activity launches. [Android battery overview](https://developer.android.com/reference/android/content/Intent#ACTION_POWER_USAGE_SUMMARY) · [Android Safety Center](https://developer.android.com/reference/android/content/Intent#ACTION_SAFETY_CENTER) · [Older security settings action](https://developer.android.com/reference/android/provider/Settings#ACTION_SECURITY_SETTINGS)

## Comparison on Android 16

Both layouts used the dedicated Tinycast_Tablet_API_36 arm64 emulator at 100% font. The tablet used 1920 × 1200 at 240 dpi and the phone used 1080 × 2340 at 420 dpi. These are two viewports on one system image.

| Search | Previous build | Updated palette | Android destination |
| --- | --- | --- | --- |
| Battery settings | [Tablet web search](screenshots/battery-security/before-battery.png) | [Tablet battery command](screenshots/battery-security/after-battery.png) | [Tablet overview](screenshots/battery-security/battery-tablet.png) · [Phone overview](screenshots/battery-security/battery-phone.png) |
| Security settings | [Tablet web search](screenshots/battery-security/before-security.png) | [Phone security command](screenshots/battery-security/after-security.png) | [Tablet dashboard](screenshots/battery-security/security-tablet.png) · [Phone dashboard](screenshots/battery-security/security-phone.png) |

The test replaced the search text, activated the result, recorded the outgoing intent, then checked the foreground package and two destination-specific controls. The previous build returned a web search first for both queries. A monitor denied a real security-command invocation to exercise its error path without changing the Android security configuration. A separate test used an action with no installed handler. [Denied launch with the query retained](screenshots/battery-security/security-denied.png)

Earlier system-image runs in #22 observed Settings crashes on the direct Battery Saver and Security routes. The new Android 16 checks open the battery overview and Safety Center. The Battery Saver subpage itself, policy changes, and the original crash cause remain unverified. The API 32/33 choice has host regression coverage. Actual pre-33 security navigation, other system images and physical OEM devices remain open in #22.

## Validation results

The final app and instrumentation were built from `b12cd99b`. The final tablet passed six cases and the phone passed five. Both checked Battery, Security, the denied security launch, a genuinely missing activity and the package arguments for existing commands. The tablet also checked Wi-Fi. The final instrumentation on the earlier app produced both expected search-ranking failures. The older production APK was built from `f5ef2d8a` in the tile investigation and its production sources match this PR’s base. The wrapper restored the original app and test APKs, preferences, previous evidence directory, display geometry, font, and observed IME/accessibility settings; shell and app remained unprivileged. [Per-run APK identities, captures and checks](validation/battery-security/runs.json)

The earlier test build at `674bc926` recorded six tablet passes and two old-app search failures. Including those separate runs, 21 device cases executed with 17 passes and four intended old-app failures. Each of the five batches recorded eight successful restoration checks. The first debug instrumentation compile at `fd1d0caa` failed on an unavailable package-manager accessor. The final debug and benchmark builds, including instrumentation, passed. Final JVM results were 347 passed, and both variants had zero lint errors and 47 warnings. Python ran 146 tests per mode before the final Android-only changes. All eleven packaged assets matched the comparison app. [Host evidence](validation/battery-security/host.json) · [Published capture and log bindings](validation/battery-security/bindings.json)

### Collector guard correction

The five retained device batches used normal Python. Their collector used an assertion to check that Python’s assertions were enabled. Optimized Python removed both that check and other assertions containing APK installation and restoration checks. The corrected collector explicitly rejects optimized Python before creating output or contacting a device. The runner code from argument parsing onward is byte-identical.

A host probe intercepted the first directory creation or subprocess and blocked file writes. In normal mode both revisions reached directory creation. With `-O`, `-OO`, `PYTHONOPTIMIZE=1` and `PYTHONOPTIMIZE=2`, the older revision reached directory creation and the corrected revision rejected execution first. These ten host observations performed zero device operations. The device receipts keep their original runner identities. [Host observations and source identities](validation/battery-security/collector-guard.json)
