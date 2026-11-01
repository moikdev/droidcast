# Android CI

The workflow is active at `.github/workflows/android.yml` on the readiness PR; `android.yml` here mirrors it. The existing GitHub app connection could publish it even though the local CLI OAuth credential still lacks workflow scope. The first run failed because the hosted runner did not expose `sdkmanager`; a pinned SDK setup action addresses that missing setup. Inspect actual run results before claiming clean-runner success.

The workflow has read-only repository permissions, SHA-pinned actions, a 20-minute timeout and no deployment or signing secrets. It builds the debug app/test APK and runs JVM tests and lint. Uploaded CI debug APKs use runner-local debug keys and **must not be published as updater artifacts**.

This workflow does not execute emulator/physical-device tests, performance benchmarks or a production-signed release. Local checks and the independent audits remain required. Production signing/recovery and broader device coverage remain in #21.

When changing the workflow, update this mirror and validate with `actionlint`. Use the existing GitHub app file tools or a workflow-authorized credential for the workflow path; do not weaken repository rules.
