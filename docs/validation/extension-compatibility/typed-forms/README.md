# Typed Form evidence

This directory records issue #87. The same frozen production app (`55e641a`) completes 183 executions across 24 cohorts, with 479 successful cohort-level cleanup checks. The five retained groups use `71493bc` instrumentation (123 executions). Existing-Form regression and critical fonts use `5edba75` (60 executions). Only the checkbox test method differs; no retained run is relabeled as a run on the newer test APK.

- `checkpoint.json` binds source/runner hashes, APK identities and all published files except itself.
- `cohorts/` holds the completed current-runtime outcomes, allowlisted host records, logs and case receipts.
- `historical/` retains old controls, failed/partial development runs and separately completed focused probes. These are excluded from the current total.
- `capture-manifest.json` binds 33 byte-exact PNG/XML/frame sets. PNGs are in `docs/screenshots/extension-compatibility/typed-forms/`; paired XML/frame files use the same capture name under `captures/`. Full captions preserve original build/profile and fixture limits.
- `retained-capture-hashes.json` records 4,859 original local capture hashes; old interrupted outcomes are not upgraded.
- `pipelines/` preserves frozen source identities and reference orchestration. Redacted host paths make these archival references, not portable runner commands. Executable guarded runners live under `scripts/`.
- `checks/` contains the full frozen build, executed JVM/Node/Python receipts and lint. `checks-final/` contains final verification, with the JVM task explicitly UP-TO-DATE. Node/Python checks are retained against unchanged sources, not rerun.
- `publication/` declares publication transforms. Host records use an explicit field allowlist. No private full host record or original clipboard/data recovery material is copied or hashed. Raw input-method system dumps stay private; `legacy-ime-monitor.json` contains only active-case flags and their observation limits.

All 85 XML files parse. The 33 raw XMLs preserve CRLF bytes. Text artifacts replace fixed host prefixes, with XML-escaped placeholders in XML. The failed private export stopped on a reference script's own path matcher before publication; the following transform validation records that mechanical correction.

The monitored legacy pass does not establish the cause of earlier unmonitored IME setup timeouts, nor an application fix. Real DocumentsUI cases, controlled worker/registry probes, actual recreation and the zero-JUnit deliberate-death control remain distinct. Physical/OEM, TalkBack, arbitrary providers, storeValue, imperative focus and complete Store parity remain open. Actual exact-head hosted CI and two independent audits are PR gates, separate from these receipts.
