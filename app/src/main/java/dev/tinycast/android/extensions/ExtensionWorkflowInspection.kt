package dev.tinycast.android.extensions

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

internal class RecordedTestsUnavailable : IOException("Recorded tests are unavailable")
internal class InstalledPayloadUnavailable : IOException("Installed files could not be checked")
internal data class ExtensionWorkflowInspection(
    val evidence: ExtensionWorkflowEvidence,
    val candidates: List<RecordedWorkflowSnapshot>,
    val matches: List<RecordedWorkflowSnapshot>,
)

/** Local, read-only source. Call on an IO dispatcher with a current-install check. */
internal class ExtensionWorkflowSource(private val open: () -> InputStream) {
    fun inspect(handle: String, name: String, directory: File, extensionsParent: File,
                checkActive: () -> Unit): ExtensionWorkflowInspection {
        checkActive()
        val evidence = try {
            val bytes = open().use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var emptyReads = 0
                while (true) {
                    checkActive(); val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) { if (++emptyReads > 8) throw RecordedTestsUnavailable(); continue }
                    emptyReads = 0
                    if (output.size() + count > ExtensionWorkflowEvidence.MAX_BYTES) throw RecordedTestsUnavailable()
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            ExtensionWorkflowEvidence.parse(bytes, checkActive)
        } catch (_: IOException) { throw RecordedTestsUnavailable() }
        catch (_: IllegalArgumentException) { throw RecordedTestsUnavailable() }
        catch (_: SecurityException) { throw RecordedTestsUnavailable() }
        checkActive()
        val candidates = evidence.candidates(handle, name)
        // Unknown packages do not cause scans of unrelated installed code.
        if (candidates.isEmpty()) return ExtensionWorkflowInspection(evidence, emptyList(), emptyList())
        val actual = try { ExtensionPayloadIdentity.capture(directory, extensionsParent, checkActive) }
            catch (_: IOException) { throw InstalledPayloadUnavailable() }
        checkActive()
        return ExtensionWorkflowInspection(evidence, candidates, candidates.filter { it.payload.matches(actual) })
    }
}
