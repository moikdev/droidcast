# DroidCast parity loop

The user explicitly authorized a sustained Tinycast/Raycast parity → enhancement issue → implementation PR → subagent audit → merge loop. Track progress in issue #1 and `docs/PARITY-TRACKER.md`.

- Work through enhancement issues with explicit acceptance criteria. Keep partial and Android platform-replacement gaps honest; do not claim 100% parity without working equivalents and evidence.
- Every PR must embed real Android screenshots showing the change. Capture before/after when the behavior changes, identify the emulator/device and build, and include destination/error states where relevant. For nonvisual changes include a clearly labeled real app smoke-test screenshot. Mocks are not implementation evidence.
- Put durable PNGs under `docs/screenshots/` and use commit-pinned GitHub image URLs in PR descriptions. Do not publish local machine paths, credentials, signing keys or unrelated user data.
- Each PR requires two independent subagent audits configured as **gpt-6.1-sol, high reasoning effort**: correctness/security and UX/test coverage. Reviewers identify the exact head SHA and must not mutate the shared checkout or emulator. Do not substitute a different model/effort silently.
- Fix findings and obtain fresh audits when the reviewed code changes. Record review conclusions and relevant local checks on the PR. Merge only the reviewed, verified head; use an expected-head check.
- The CLI OAuth credential lacks workflow scope. PR #66 activated `.github/workflows/android.yml` through the existing GitHub app connection; `ci/android.yml` mirrors it. Report actual exact-head workflow results, separately from local/emulator checks. Local checks and independent audits remain required; never report unavailable or failed checks as passing.
- Android SDK is discovered from `ANDROID_HOME`/`local.properties`. The dedicated `Tinycast_Tablet_API_36` emulator is for device tests. The application must run unprivileged; adbd root is used only by the guarded hardware-key test helper and must be restored afterward.
- Preserve upstream runtime assets unchanged and retain AGPL/React notices. Android-specific code, tests and docs are the implementation surface.
