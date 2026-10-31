# Recommended improvements

These are proposals from the [parity audit](PARITY.md), not features included in the preview. Priorities favor everyday Android command-palette use and explicit compatibility over adding a long menu of nonfunctional desktop commands.

## First priorities

| Priority | Improvement | Why and acceptance criteria |
| --- | --- | --- |
| P0 | Extension compatibility and native rendering | Publish tested extension/version results; label verified, unverified and unsupported commands accurately. Extend the native Grid subset (section-specific geometry, SVG/full icons and automatic pagination); implement rendered Markdown/metadata, form validation and missing field controls. Add representative fixture tests and more real Store examples. Unsupported APIs must remain actionable errors. |
| P0 | User-controlled backup and restore | Export versioned notes, quicklinks, clips, aliases and pins through the document picker. Restore transactionally with a preview and duplicate handling. Never enable accessibility or restore capability grants automatically; selected documents need reauthorization. |
| P1 | Configurable hotkeys | Development PR #33 adds ordinary global/per-command chord recording, conflict handling and reset. Advanced modifier-tap/Fn/Hyper cases remain #26. Preserve opt-in service setup and the current no-screen-content posture. Validate real hardware keyboards and service reconnects on physical tablets. |
| P1 | Android system and automation commands | The direct settings slice adds 11 tested destinations. Next build a typed intent/deeplink command editor with argument validation. Show a destination/capability failure instead of falling back silently to an unrelated action. |
| P1 | Selected-folder file search | Add `ACTION_OPEN_DOCUMENT_TREE` folders, cancellable metadata search, persistent grants, regrant UX and a bounded index if measurement justifies it. Exercise local and cloud document providers, moved files and expired permissions. |
| P1 | Android multitasking commands | Prototype a clearly labeled system split-screen action and adjacent launch where supported. Check capability before offering it; preserve existing split layout when the palette closes. Validate on stock Android and a desktop-style tablet environment. Do not promise arbitrary quarter/window positioning without a supported implementation. |

## Next improvements

1. **Rendered notes and explicit snippet templates.** Preview Markdown, support variables and reusable snippets, and copy the expanded result. Keep automatic typing/keyword interception out of the default workflow.
2. **A better explicit clipboard collection.** Add image sharing, pins, editing and search. Keep capture visible and user initiated; do not imply unrestricted background monitoring.
3. **More capable calculations.** Add tested date/time and color expressions, then opt-in exchange-rate providers with caching, timestamps and a clear offline/error state.
4. **A local emoji/symbol picker and dictionary command.** Use licensed datasets, keyboard navigation and copy actions. No network should be needed for the emoji catalog.
5. **Calendar/meeting actions.** Introduce permission-scoped upcoming events and join links with a useful permission-denied state; avoid requesting calendar access on launch.
6. **Appearance and accessibility.** System/light theme, large-font layouts, TalkBack labels/focus order, localization and reliable touch targets while retaining Tinycast's palette proportions on tablets.
7. **Release engineering.** Replace preview debug signing with a maintainer-controlled release key, verify reproducible distribution, and add a physical-device matrix. Keep emulator evidence versioned and avoid shipping local machine files.

AI chat and text rewriting can be a later, optional track. Before that, shipping dependable keyboard control, saved-data recovery, file search and honest extension compatibility would improve the product more.

## Validation gates

- Local unit tests, Android lint and debug build; the supplied CI template repeats these on a clean runner after activation.
- Repeatable command dispatch and document-grant tests on tablet and phone sizes.
- Global keyboard checks outside UiAutomation, plus physical-keyboard checks before claiming OEM support.
- The opt-in endurance test and cold-restart document checks.
- Capability-specific tests before a parity row is promoted from partial/missing.

See [README.md](README.md) and [docs/TESTING.md](docs/TESTING.md) for the current build and evidence. None of the future items above implies an existing permission or platform entitlement.
