package dev.tinycast.android

import dev.tinycast.android.extensions.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Synthetic file/stream protocols; these do not earn Android workflow credit. */
class ExtensionWorkflowInspectionTest {
    private val temporary = Files.createTempDirectory("owned-record-inspection")
    private val parent = temporary.resolve("extensions").toFile().apply { mkdir() }
    private val directory = File(parent, "owned").apply { mkdir() }
    private fun shipped() = JSONObject(File("src/main/assets/extension-workflow-evidence-v1.json").readText())
    private fun inspect(bytes: ByteArray, handle: String = "owned", name: String = "owned", check: () -> Unit = {}) =
        ExtensionWorkflowSource { bytes.inputStream() }.inspect(handle, name, directory, parent, check)
    private fun seed(): ByteArray {
        File(directory, "package.json").writeText("{}")
        File(directory, "index.js").writeText("Owned inert unit fixture, not executed")
        val payload = ExtensionPayloadIdentity.capture(directory, parent) {}
        val root = shipped(); val original = root.getJSONArray("snapshots").getJSONObject(0)
        val snapshot = JSONObject(original.toString()).put("handle", "owned").put("name", "owned")
            .put("payload_sha256", payload.sha256).put("manifest_sha256", payload.files.single { it.path == "package.json" }.sha256)
            .put("files", JSONArray(payload.files.map { JSONObject().put("path", it.path).put("size", it.size).put("sha256", it.sha256) }))
            .put("commands", JSONArray().put(JSONObject().put("name", "index").put("title", "Owned inert unit fixture").put("mode", "view")
                .put("sha256", payload.files.single { it.path == "index.js" }.sha256)))
        val row = root.getJSONArray("observations").getJSONObject(0).put("archive_sha256", snapshot.getString("archive_sha256")).put("command", "index")
        return root.put("snapshots", JSONArray().put(snapshot)).put("observations", JSONArray().put(row)).toString().toByteArray()
    }
    @After fun removeOwnedFiles() { Files.walk(temporary).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }

    @Test fun knownExactPayloadMatchesButACommandEditStaysUnknown() {
        val bytes = seed(); assertEquals(1, inspect(bytes).matches.size)
        File(directory, "index.js").appendText(" changed")
        val different = inspect(bytes); assertEquals(1, different.candidates.size); assertTrue(different.matches.isEmpty())
    }
    @Test fun unknownPackageDoesNotReadItsMissingDirectory() {
        assertTrue(directory.delete())
        val value = inspect(shipped().toString().toByteArray())
        assertTrue(value.candidates.isEmpty()); assertTrue(value.matches.isEmpty())
    }
    @Test fun canonicalIdentityCannotBeInferredFromAnExtensionTitle() {
        val bytes = seed()
        assertTrue(inspect(bytes, "other", "owned").candidates.isEmpty())
        assertTrue(inspect(bytes, "owned", "other").candidates.isEmpty())
    }
    @Test fun knownUnreadableOrLinkedPayloadIsAnErrorNotAnEmptySuccess() {
        val bytes = seed(); File(directory, "package.json").delete()
        assertThrows(InstalledPayloadUnavailable::class.java) { inspect(bytes) }
        Files.createSymbolicLink(directory.toPath().resolve("package.json"), directory.toPath().resolve("index.js"))
        assertThrows(InstalledPayloadUnavailable::class.java) { inspect(bytes) }
    }
    @Test fun malformedUnsupportedAndUnavailableCatalogsDoNotBecomeUnknownSuccess() {
        for (bytes in listOf("{}".toByteArray(), shipped().put("schema_version", 2).toString().toByteArray(), byteArrayOf(0xc0.toByte(), 0x80.toByte()))) {
            assertThrows(RecordedTestsUnavailable::class.java) { inspect(bytes) }
        }
        assertThrows(RecordedTestsUnavailable::class.java) {
            ExtensionWorkflowSource { throw IOException("Owned unavailable stream") }.inspect("owned", "owned", directory, parent) {}
        }
    }
    @Test fun oversizeAndNonProgressingStreamsAreBoundedAndClosed() {
        var closed = false
        val large = object : ByteArrayInputStream(ByteArray(ExtensionWorkflowEvidence.MAX_BYTES + 1) { 32 }) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(RecordedTestsUnavailable::class.java) { ExtensionWorkflowSource { large }.inspect("owned", "owned", directory, parent) {} }
        assertTrue(closed)
        var reads = 0; closed = false
        val stalled = object : InputStream() {
            override fun read(): Int = error("Use bounded reads")
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return 0 }
            override fun close() { closed = true }
        }
        assertThrows(RecordedTestsUnavailable::class.java) { ExtensionWorkflowSource { stalled }.inspect("owned", "owned", directory, parent) {} }
        assertEquals(9, reads); assertTrue(closed)
    }
    @Test fun cancellationClosesTheStreamAndIsNeverReportedAsAnErrorOutcome() {
        var closed = false; var checks = 0
        val stream = object : ByteArrayInputStream(seed()) { override fun close() { closed = true; super.close() } }
        val cancelled = CancellationException("Owned cancellation")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            ExtensionWorkflowSource { stream }.inspect("owned", "owned", directory, parent) { if (++checks == 3) throw cancelled }
        })
        assertTrue(closed)
    }
    @Test fun changedOwnerAfterReadingCannotPublishAMatch() {
        val bytes = seed(); var finishedReading = false
        val stream = object : ByteArrayInputStream(bytes) { override fun close() { finishedReading = true; super.close() } }
        val cancelled = CancellationException("Owned replacement")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            ExtensionWorkflowSource { stream }.inspect("owned", "owned", directory, parent) { if (finishedReading) throw cancelled }
        })
    }
}
