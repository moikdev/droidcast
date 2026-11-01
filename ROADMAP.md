# Recommended improvements

These are proposals from the [parity audit](PARITY.md), not features included in the preview. Priorities favor everyday Android command-palette use and explicit compatibility over adding a long menu of nonfunctional desktop commands.

## First priorities

| Priority | Improvement | Why and acceptance criteria |
| --- | --- | --- |
| P0 | Finish current readiness validation (#65 / PR #66) | Complete exact-build phone/integration checks, retain real failures, reconcile evidence and obtain both independent final-head audits before an expected-head merge. Production clearance is separate. |
| P0 | Readable controls and touch workflows (#71, parent #20) | Prioritize search, result rows, shared Actions footer and menus. Verify phone/tablet, IME, rotation/split sizes and font scales 1.0/1.3/1.5/2.0 with real before/after captures. Require readable labels, nonoverlapping 48dp touch targets, reachable Actions/Back/filter controls and preserved keyboard behavior. Remaining forms/screens stay explicitly open. |
| P1 | Reproducible command compatibility catalog (#72, parent #7) | Pin the source inventory and command identities. Keep untested, tested-workflow, partial, failed, platform-unsupported and configuration-required distinct. Inspect manifests in bounded resumable batches, then execute authorized workflows on identified Android/WebView builds. A discovered repository or static API match is not execution evidence. |
| P1 | Native extension rendering (#6 / #7) | Extend the native Grid subset, rendered Markdown/metadata, form validation and missing controls using representative fixtures and real Store examples. Unsupported APIs need actionable errors; never promote the whole extension from a single passing command. |
| P1 | Production recovery and distribution (#21) | Dedicated key preparation is complete in PR #70. Verify off-device recovery, production automation/channel and explicit installed-nightly backup/clean-install/import rehearsals before migration. Keep private signing material outside all checkouts. |
| P2 | Keyboard, storage and automation follow-ups | Build on the already merged backup, ordinary/modifier shortcuts and selected-folder slices. Test physical/OEM keyboards, broader providers and interruptions; add typed intents/deeplinks and supported Android multitasking replacements with capability/error handling. |

## Next improvements

1. **Rendered notes and explicit snippet templates.** Preview Markdown, support variables and reusable snippets, and copy the expanded result. Keep automatic typing/keyword interception out of the default workflow.
2. **A better explicit clipboard collection.** Add image sharing, pins, editing and search. Keep capture visible and user initiated; do not imply unrestricted background monitoring.
3. **More capable calculations.** Add tested date/time and color expressions, then opt-in exchange-rate providers with caching, timestamps and a clear offline/error state.
4. **Dictionary and further emoji parity.** The offline emoji/symbol picker already exists. Improve its remaining parity gaps and introduce licensed dictionary lookup with useful offline/error states.
5. **Calendar/meeting actions.** Introduce permission-scoped upcoming events and join links with a useful permission-denied state; avoid requesting calendar access on launch.
6. **Appearance and accessibility.** System/light theme, large-font layouts, TalkBack labels/focus order, localization and reliable touch targets while retaining Tinycast's palette proportions on tablets.
7. **Release engineering.** Replace preview debug signing with a maintainer-controlled release key, verify reproducible distribution, and add a physical-device matrix. Keep emulator evidence versioned and avoid shipping local machine files.

AI chat and text rewriting can be a later, optional track. Before that, shipping dependable keyboard control, saved-data recovery, file search and honest extension compatibility would improve the product more.

## Validation gates

- Local unit tests, Android lint and debug/test builds; record actual exact-head hosted workflow results separately from device checks and audits.
- Repeatable command dispatch and document-grant tests on tablet and phone sizes.
- Global keyboard checks outside UiAutomation, plus physical-keyboard checks before claiming OEM support.
- The opt-in endurance test and cold-restart document checks.
- Capability-specific tests before a parity row is promoted from partial/missing.

See [README.md](README.md) and [docs/TESTING.md](docs/TESTING.md) for the current build and evidence. None of the future items above implies an existing permission or platform entitlement.
