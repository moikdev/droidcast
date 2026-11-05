package dev.tinycast.android

import dev.tinycast.android.extensions.ExtensionWorkflowEvidence
import dev.tinycast.android.extensions.RecordedWorkflowOutcome
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionWorkflowEvidenceTest {
    @Test fun weatherHistoricalSourceAndBothApksMatchTheCohortIdentity() {
        val identity = JSONObject(java.io.File("../docs/validation/extension-compatibility/fetch/identities/build-harness-v3.json").readText())
        val catalog = ExtensionWorkflowEvidence.parse(java.io.File("src/main/assets/extension-workflow-evidence-v1.json").readBytes())
        val observed = catalog.observations.single { it.id == "weather-tokyo-observation" }.environment
        val apks = identity.getJSONObject("apk_hashes")
        assertEquals(apks.getString("app-debug-androidTest.apk"), observed.testSha256)
        assertEquals(identity.getString("test_source_commit"), observed.testSource)
        assertEquals(apks.getString("app-debug.apk"), observed.appSha256)
        assertEquals(identity.getString("runtime_source_commit"), observed.appSource)
    }
    @Test fun shippedCatalogParsesAndKeepsUntestedCommandsSeparateFromHistoricalOutcomes() {
        val bytes = java.io.File("src/main/assets/extension-workflow-evidence-v1.json").readBytes()
        val catalog = ExtensionWorkflowEvidence.parse(bytes)
        assertEquals(9, catalog.snapshots.size)
        assertEquals(300, catalog.snapshots.sumOf { it.payload.files.size })
        assertEquals(34, catalog.snapshots.sumOf { it.commands.size })
        assertEquals(16, catalog.observations.size)
        assertEquals(14, catalog.observations.map { it.archiveSha256 to it.command }.distinct().size)
        assertEquals(11, catalog.observations.count { it.outcome == RecordedWorkflowOutcome.WORKED })
        assertEquals(1, catalog.observations.count { it.entryProbe })
        val weather = catalog.candidates("tonka3000", "weather").single()
        assertEquals(setOf(RecordedWorkflowOutcome.PARTIAL, RecordedWorkflowOutcome.FAILED), catalog.records(weather, "index").map { it.outcome }.toSet())
        assertTrue(catalog.records(weather, "owned-unknown-command").isEmpty())
        assertTrue(catalog.observations.all { it.environment.appSource != "local" })
    }
    private fun fixture(): JSONObject {
        val original = requireNotNull(javaClass.getResourceAsStream("/extension-payload-identities-v1.json")).use {
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText()).getJSONArray("snapshots").getJSONObject(0)
        }
        val sourceCommands = original.getJSONArray("commands")
        val commands = JSONArray(List(sourceCommands.length()) { at -> sourceCommands.getJSONObject(at).let {
            JSONObject().put("name", it.getString("name")).put("title", "Owned " + it.getString("name"))
                .put("mode", it.getString("mode")).put("sha256", it.getString("sha256"))
        } })
        val snapshot = JSONObject().put("archive_sha256", original.getString("archive_sha256"))
            .put("handle", original.getString("handle")).put("name", original.getString("name"))
            .put("package_name", original.getString("name")).put("manifest_sha256", original.getString("manifest_sha256"))
            .put("payload_sha256", original.getString("payload_sha256")).put("files", original.getJSONArray("files")).put("commands", commands)
        val phases = JSONObject()
        for (key in listOf("setup", "startup", "interaction", "destination", "persistence", "lifecycle")) {
            phases.put(key, JSONObject().put("result", if (key in setOf("persistence", "lifecycle")) "not_exercised" else "passed")
                .put("detail", "Owned unit fixture phase $key"))
        }
        val environment = JSONObject().put("app_source", "a".repeat(40)).put("app_sha256", "b".repeat(64))
            .put("test_source", "c".repeat(40)).put("test_sha256", "d".repeat(64)).put("android_api", 36)
            .put("device", "Owned unit fixture").put("webview_package", JSONObject.NULL).put("webview_version", JSONObject.NULL)
            .put("profiles", JSONArray().put("Owned tablet 100%"))
        val evidence = JSONObject().put("commit", "1".repeat(40)).put("case_path", "docs/owned-case.json").put("report_path", "docs/OWNED-REPORT.md")
        val record = JSONObject().put("id", "owned-record").put("archive_sha256", original.getString("archive_sha256"))
            .put("command", commands.getJSONObject(0).getString("name")).put("outcome", "worked_for_workflow").put("kind", "execution")
            .put("workflow", "Owned parsing fixture, not an Android observation").put("configuration", "Owned unit values")
            .put("limits", JSONArray().put("No execution evidence is supplied by this unit fixture"))
            .put("phases", phases).put("environment", environment).put("evidence", evidence)
        return JSONObject().put("schema_version", 1).put("snapshots", JSONArray().put(snapshot)).put("observations", JSONArray().put(record))
    }
    private fun snapshot(root: JSONObject) = root.getJSONArray("snapshots").getJSONObject(0)
    private fun record(root: JSONObject) = root.getJSONArray("observations").getJSONObject(0)
    private fun parse(root: JSONObject) = ExtensionWorkflowEvidence.parse(root.toString().toByteArray())
    private fun reject(change: (JSONObject) -> Unit) = assertThrows(IllegalArgumentException::class.java) { parse(fixture().also(change)) }

    @Test fun observationsStayHistoricalAndDoNotPromoteOtherCommandsOrPackages() {
        val value = parse(fixture()); val source = value.snapshots.single(); val observed = value.observations.single()
        assertEquals(RecordedWorkflowOutcome.WORKED, observed.outcome)
        assertEquals("Worked in recorded test", observed.outcome.label)
        assertEquals(1, value.records(source, observed.command).size)
        assertTrue(value.records(source, source.commands.first { it.name != observed.command }.name).isEmpty())
        assertTrue(value.candidates("unknown-owner", source.name).isEmpty()); assertEquals(1, value.candidates(source.handle, source.name).size)
        assertNull(observed.environment.webViewVersion)
    }
    @Test fun changedAssetsAndCommandsCannotReuseTheExpectedPayload() {
        reject { snapshot(it).getJSONArray("files").getJSONObject(0).put("sha256", "f".repeat(64)) }
        reject { snapshot(it).getJSONArray("commands").getJSONObject(0).put("sha256", "f".repeat(64)) }
        reject { record(it).put("archive_sha256", "f".repeat(64)) }
    }
    @Test fun duplicateIdsAndDeclarationsCannotSilentlyReplaceHistory() {
        reject { it.getJSONArray("observations").put(JSONObject(record(it).toString())) }
        reject { it.getJSONArray("snapshots").put(JSONObject(snapshot(it).toString())) }
        reject { snapshot(it).getJSONArray("commands").put(JSONObject(snapshot(it).getJSONArray("commands").getJSONObject(0).toString())) }
    }
    @Test fun jsonDuplicateKeysAndPlatformLeniencyAreRejectedBeforeDecoding() {
        val raw = fixture().toString()
        for (invalid in listOf(raw.replaceFirst("{", "{\"schema_version\":1,"),
            raw.replaceFirst("{", "{\"schema_\\u0076ersion\":1,"),
            raw.replaceFirst("{", "{/* owned comment */"), raw + " {}", "{'schema_version':1}")) {
            assertThrows(IllegalArgumentException::class.java) { ExtensionWorkflowEvidence.parse(invalid.toByteArray()) }
        }
    }
    @Test fun missingAndUnknownSchemaDataCannotBecomeAnEmptySuccessfulCatalog() {
        reject { it.put("schema_version", 2) }
        val decimalVersion = fixture().toString().replace("\"schema_version\":1", "\"schema_version\":1.0")
        assertThrows(IllegalArgumentException::class.java) { ExtensionWorkflowEvidence.parse(decimalVersion.toByteArray()) }
        reject { it.put("current_build_verified", true) }; reject { snapshot(it).put("verification", "working") }
        reject { record(it).put("outcome", "compatible") }; reject { record(it).remove("limits") }
        reject { it.put("observations", JSONArray()) }; reject { it.put("snapshots", JSONArray()) }
    }
    @Test fun aWorkedOutcomeRequiresStartupInteractionAndDestinationWithoutBlockedPhases() {
        for (key in listOf("startup", "interaction", "destination")) {
            reject { record(it).getJSONObject("phases").getJSONObject(key).put("result", "not_exercised") }
        }
        reject { record(it).getJSONObject("phases").getJSONObject("setup").put("result", "blocked") }
        reject { record(it).getJSONObject("phases").getJSONObject("lifecycle").put("result", "failed") }
    }
    @Test fun failureAndConfigurationLabelsRequireTheirObservedPhase() {
        reject { record(it).put("outcome", "observed_failure") }
        reject { record(it).put("outcome", "configuration_required") }
        val failed = fixture(); record(failed).put("outcome", "observed_failure")
        record(failed).getJSONObject("phases").getJSONObject("interaction").put("result", "failed")
        assertEquals(RecordedWorkflowOutcome.FAILED, parse(failed).observations.single().outcome)
    }
    @Test fun entryProbesDoNotCertifyAnExecutedAction() {
        val root = fixture(); val observation = record(root); observation.put("outcome", "entry_unavailable").put("kind", "entry_probe")
        observation.getJSONObject("phases").getJSONObject("startup").put("result", "blocked")
        observation.getJSONObject("phases").getJSONObject("interaction").put("result", "not_exercised")
        assertTrue(parse(root).observations.single().entryProbe)
        observation.getJSONObject("phases").getJSONObject("interaction").put("result", "passed")
        assertThrows(IllegalArgumentException::class.java) { parse(root) }
    }
    @Test fun evidenceLinksOnlyUseThePinnedRepositoryDocsPath() {
        val value = parse(fixture()).observations.single()
        assertEquals("https://github.com/moikdev/droidcast/blob/" + "1".repeat(40) + "/docs/OWNED-REPORT.md", value.reportUrl)
        for (path in listOf("../private.json", "docs/../private.json", "docs/%2e%2e/private.json", "https://example.invalid/", "docs/file.md?token=owned", "docs\\file.json")) {
            reject { record(it).getJSONObject("evidence").put("report_path", path) }
        }
        reject { record(it).getJSONObject("evidence").put("commit", "main") }
    }
    @Test fun invalidUtf8EscapesDepthAndSizeAreBounded() {
        assertThrows(IllegalArgumentException::class.java) { ExtensionWorkflowEvidence.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionWorkflowEvidence.parse(ByteArray(ExtensionWorkflowEvidence.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionWorkflowEvidence.parse(("[".repeat(12) + "]".repeat(12)).toByteArray()) }
        reject { record(it).put("workflow", "x".repeat(4097)) }
        val raw = fixture().toString().replace("Owned unit values", "\\uD800")
        assertThrows(IllegalArgumentException::class.java) { ExtensionWorkflowEvidence.parse(raw.toByteArray()) }
    }
    @Test fun incompleteEnvironmentAndMissingPhaseDetailsRemainInvalid() {
        reject { record(it).getJSONObject("environment").put("webview_package", "owned.webview") }
        reject { record(it).getJSONObject("environment").put("android_api", "36") }
        reject { record(it).getJSONObject("phases").remove("persistence") }
        reject { record(it).getJSONObject("phases").getJSONObject("startup").remove("detail") }
    }
    @Test fun cancelledParsingCannotReturnRecords() {
        val bytes = fixture().toString().toByteArray(); val cancelled = CancellationException("owned cancellation"); var checks = 0
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            ExtensionWorkflowEvidence.parse(bytes) { if (++checks == 12) throw cancelled }
        })
    }
}
