# Production-readiness and parity review

Work in progress under [#65](https://github.com/moikdev/droidcast/issues/65). This report does **not** clear a production release or claim full parity. The user chose emulator-only coverage for this pass.

## References and real screenshots

Droidcast: released r10, versionCode 6, source `34b70a579ec6db366e2363deb6bd227591c7c750`, app SHA-256 `e68b7e7c4a3771c18128221f7d31b71e144db0d64947ae0ee08f91bddccf3f00`. Its production source tree matches merged main `ddeb0fd`. Android captures: API36 arm64 emulator, tablet 1920×1200/240dpi. The current test/benchmark additions do not change the production palette.

Raycast: installed macOS app 2.10.1.0. Tinycast: actual locally built Dev 0.1.0 (1), source [`37e6043`](https://github.com/abue-ammar/tinycast/tree/37e6043f3f550febb64bdb3cff3ac29a30caad5a), Xcode27/macOS27.0.1, no Accessibility/login-item/hotkey grants or personal-data import. Upstream HEAD advanced to [`e3f813c`](https://github.com/abue-ammar/tinycast/compare/37e6043...e3f813c): native glass/shadow changes and terminal prompt handling. The images below are explicitly the older pinned revision, not screenshots of that newer revision.

Desktop captures show app windows. Android captures show the whole display with the transparent floating palette over Settings. Different bounds, display scales and glyph fonts prevent a pixel-size or visual-performance comparison. All are real captures, not mockups.

| Workflow | Raycast | Tinycast | Droidcast tablet |
| --- | --- | --- | --- |
| `sqrt(144)` → `12` | ![Raycast calculator](screenshots/readiness/raycast-calculator.png) | ![Tinycast calculator](screenshots/readiness/tinycast-calculator.png) | ![Droidcast calculator](screenshots/readiness/droidcast-calculator.png) |
| Attached Actions | ![Raycast Actions](screenshots/readiness/raycast-actions.png) | ![Tinycast Actions](screenshots/readiness/tinycast-actions.png) | ![Droidcast Actions](screenshots/readiness/droidcast-actions.png) |
| Emoji search `face` | ![Raycast Emoji](screenshots/readiness/raycast-emoji.png) | ![Tinycast Emoji](screenshots/readiness/tinycast-emoji.png) | ![Droidcast Emoji](screenshots/readiness/droidcast-emoji.png) |

The common calculator expression, persistent search header, attached action panel and contextual footer have working Android equivalents. The action sets differ: Tinycast offers putting the answer into Search and copying the whole calculation; Droidcast currently only offers Copy Answer. Raycast additionally offers history, pinning and cross-app paste. Android Copy does not establish paste-back parity. Emoji glyphs, catalogs, ordering, ranking and remembered usage differ; matching the query does not imply equal result counts or catalog coverage.

## Measurement contract

The new instrumentation uses the real activity and real IME InputConnection or native key dispatch, without Compose's test clock. A transparent diagnostic Drawable in the decor overlay observes semantics **after** descendants have drawn, then registers a hardware frame-commit callback. It includes observer overhead and frame submission, and excludes physical input transport and display scanout. It is not input-to-photon latency. Raw samples and OS FrameMetrics are retained. Setup, screenshots, idle synchronization and warmups are excluded from per-interaction intervals; the window-wide frame sample includes those surrounding operations.

An earlier OnDrawListener experiment checked semantics before Compose's deferred drawing/layout and sometimes waited for a caret blink. Its half-second plateau is a measurement defect, not evidence of an app delay. Those results are excluded. Earlier batch-edit and fallback-result assertion failures are also fixture failures and excluded.

Startup uses Android `am start -W`: process-cold after force-stop (without evicting filesystem/OS caches), and retained-process Home→foreground. Android may report `UNKNOWN (0)` with no TotalTime for an already-existing task; only WaitTime is reported for that path. Host command time is separately labeled and includes ADB. Three warmups precede 30 measured trials per mode. Raw OS output is retained; unsupported timing fields remain absent.

A disposable `.benchmark` package builds with `debuggable=false`, release dependency variants and the same source. It uses the local debug certificate, has no R8/minification configuration, and is **not** a production-signed release candidate. It cannot enter the updater channel. The published nightly remains installed separately. The runner refuses an existing benchmark package and independently removes test packages/restores settings and geometry afterward.

Final distributions, soak results, broader functional checks and audit conclusions will be added after the sequential fail-fast run completes. Emulator graphics delays, instrumentation overhead and shared host load preclude a physical-device latency verdict or claims that Droidcast is faster than macOS apps.

## Production gates

| Gate | Current verdict / next evidence |
| --- | --- |
| Production source and artifact identity | Released r10 frozen/hash-verified; current measurements distinguish it from the separate benchmark APK. |
| Functional regression | r10's historical 138 JVM, 32 native cases per layout and four real self-updates are recorded in [updater validation](validation/updates.md). Fresh broader checks pending; historical checks are not reruns. |
| Security and independent audits | Prior slice audits are recorded on their PRs. Two new sol/high independent audits are required for this checkpoint. |
| Clean-runner CI | CLI OAuth still lacks workflow scope. Existing template is inactive; activation through the GitHub app connection is being investigated. No CI success claimed. |
| Signing/release engineering | Nightly/debug certificate continuity is tested. Maintainer-controlled production signing and recovery remain #21. |
| API/OEM matrix | API36 emulator only. API26 runtime, Lenovo/OEM, physical input and hardware latency remain unverified. |
| Accessibility/localization | Scoped labels/focus tested previously. Comprehensive TalkBack, large-font, theme and translation work remains #20. |
| Reliability/performance | Fresh startup, frame/interaction measurements and sustained search/navigation run pending. No latency or leak-free certification yet. |
| Lifecycle/providers | Process death, cloud/large folder trees and extended installer interruption remain incomplete. |
| Parity | [Feature matrix](../PARITY.md) remains partial: dictionary, snippets, calendar, AI, advanced calculator/quicklinks, richer extension APIs and Android multitasking replacements are open. |
| Unexplained failures | #35/#37/#41/#59/#62 remain open; passing reruns do not establish their causes or fixes. |

No production-ready verdict is justified while these gates remain open. The next implementation work should close measured failures and concrete parity gaps through the existing issue/PR/audit loop.

## Primary measurement references

- [Android frame commit](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)): submission does not establish physical display visibility.
- [Android startup states and timings](https://developer.android.com/topic/performance/issues/launch-time).
- [Android FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics).
