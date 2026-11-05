package dev.tinycast.android

import dev.tinycast.android.extensions.ExtensionPayloadFile
import dev.tinycast.android.extensions.ExtensionPayloadIdentity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ExtensionPayloadIdentityTest {
    private val temporary = Files.createTempDirectory("owned-payload-test")
    private val parent = temporary.resolve("extensions").toFile().apply { mkdir() }
    private val directory = File(parent, "owned-extension").apply { mkdir() }
    private fun file(path: String, text: String): ExtensionPayloadFile {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ExtensionPayloadFile(path, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
    private fun write(path: String, text: String) = File(directory, path).also { requireNotNull(it.parentFile).mkdirs(); it.writeText(text) }
    private fun snapshot() = ExtensionPayloadIdentity.capture(directory, parent) {}
    private fun seed() = listOf(file("package.json", "{}"), file("command.js", "owned café é 日本 🌱"), file("assets/icon.svg", "<svg/>"))
        .also { write("package.json", "{}"); write("command.js", "owned café é 日本 🌱"); write("assets/icon.svg", "<svg/>") }
    @After fun removeOwnedFiles() {
        Files.walk(temporary).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test fun exactPayloadIncludesManifestCommandAndAssetsRegardlessOfDeclarationOrder() {
        val entries = seed(); val expected = ExtensionPayloadIdentity.fromFiles(entries.reversed())
        assertTrue(snapshot().matches(expected)); assertEquals(listOf("assets/icon.svg", "command.js", "package.json"), expected.files.map { it.path })
    }
    @Test fun canonicalBytesMatchTheIndependentBigEndianUtf8Vector() {
        assertEquals("6dd2e9f42b9ef82f80e3dba9f1a53a2365088862b9d06b4b8ce2ea836fe89761",
            ExtensionPayloadIdentity.fromFiles(seed()).sha256)
    }
    @Test fun allNineFrozenSourceArchivesMatchTheIndependentPayloadVectors() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/extension-payload-identities-v1.json"))
        val root = resource.use { JSONObject(it.bufferedReader(Charsets.UTF_8).readText()) }
        val snapshots = root.getJSONArray("snapshots"); assertEquals(9, snapshots.length())
        var files = 0; var commands = 0
        for (index in 0 until snapshots.length()) {
            val snapshot = snapshots.getJSONObject(index); val rows = snapshot.getJSONArray("files")
            val entries = List(rows.length()) { at -> rows.getJSONObject(at).let {
                ExtensionPayloadFile(it.getString("path"), it.getLong("size"), it.getString("sha256"))
            } }
            assertEquals(snapshot.getString("payload_sha256"), ExtensionPayloadIdentity.fromFiles(entries.reversed()).sha256)
            files += rows.length(); commands += snapshot.getJSONArray("commands").length()
        }
        assertEquals(300, files); assertEquals(34, commands)
    }
    @Test fun sameLengthCommandEditsStillInvalidateIdentityWhenTheTimestampIsRestored() {
        val original = ExtensionPayloadIdentity.fromFiles(seed()); val command = File(directory, "command.js")
        val time = Files.getLastModifiedTime(command.toPath()); val size = command.length()
        command.writeText(command.readText().replace("owned", "OWNED")); Files.setLastModifiedTime(command.toPath(), time)
        assertEquals(size, command.length()); assertFalse(snapshot().matches(original))
    }
    @Test fun changingOnlyAnAssetCannotKeepTheRecordedIdentity() {
        val original = ExtensionPayloadIdentity.fromFiles(seed()); write("assets/icon.svg", "<svg>changed</svg>")
        assertFalse(snapshot().matches(original))
    }
    @Test fun addedMissingAndChangedManifestFilesStayDifferent() {
        val original = ExtensionPayloadIdentity.fromFiles(seed())
        write("extra.js", "owned extra"); assertFalse(snapshot().matches(original)); File(directory, "extra.js").delete()
        File(directory, "command.js").delete(); assertFalse(snapshot().matches(original))
        write("command.js", "owned café é 日本 🌱"); write("package.json", "{\"name\":\"changed\"}"); assertFalse(snapshot().matches(original))
    }
    @Test fun installerMetadataIsExcludedButOtherHiddenFilesArePayload() {
        val original = ExtensionPayloadIdentity.fromFiles(seed()); write(".tinycast-store.json", "owned listing")
        assertTrue(snapshot().matches(original)); write(".unknown", "owned extra"); assertFalse(snapshot().matches(original))
    }
    @Test fun symlinkedFilesAndDirectoriesAreRejectedWithoutChangingTheirTargets() {
        seed(); val outside = temporary.resolve("owned-outside").toFile().apply { mkdir() }; val value = File(outside, "value").apply { writeText("owned unrelated") }
        Files.createSymbolicLink(directory.toPath().resolve("linked"), value.toPath())
        assertThrows(IOException::class.java) { snapshot() }; Files.delete(directory.toPath().resolve("linked"))
        Files.createSymbolicLink(directory.toPath().resolve("linked"), outside.toPath())
        assertThrows(IOException::class.java) { snapshot() }; Files.delete(directory.toPath().resolve("linked"))
        Files.createSymbolicLink(directory.toPath().resolve(".tinycast-store.json"), value.toPath())
        assertThrows(IOException::class.java) { snapshot() }; assertEquals("owned unrelated", value.readText())
    }
    @Test fun aLinkedRootOrSiblingOutsideTheAllowedParentCannotBeScanned() {
        seed(); val link = parent.toPath().resolve("linked"); Files.createSymbolicLink(link, directory.toPath())
        assertThrows(IOException::class.java) { ExtensionPayloadIdentity.capture(link.toFile(), parent) {} }
        val sibling = temporary.resolve("owned-sibling").toFile().apply { mkdir() }; File(sibling, "package.json").writeText("{}")
        assertThrows(IOException::class.java) { ExtensionPayloadIdentity.capture(sibling, parent) {} }
    }
    @Test fun rootReplacementDuringInspectionIsRejected() {
        seed(); var checks = 0
        assertThrows(IOException::class.java) {
            ExtensionPayloadIdentity.capture(directory, parent) {
                if (++checks == 3) {
                    Files.move(directory.toPath(), parent.toPath().resolve("replaced")); directory.mkdir(); write("package.json", "{}")
                }
            }
        }
    }
    @Test fun cancellationPropagatesWithoutBecomingAnIdentityOrIoResult() {
        seed(); var checks = 0; val cancelled = CancellationException("owned cancellation")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            ExtensionPayloadIdentity.capture(directory, parent) { if (++checks == 3) throw cancelled }
        })
    }
    @Test fun missingOrIncompletePayloadsCannotProduceAnIdentity() {
        assertThrows(IOException::class.java) { snapshot() }
        write("command.js", "owned command without a manifest")
        assertThrows(IOException::class.java) { snapshot() }
    }
    @Test fun malformedAndConflictingManifestPathsCannotBecomeExpectedIdentities() {
        for (path in listOf("../outside", "/absolute", "a/../b", "a//b", "a\\b", "a/./b", ".tinycast-store.json", "bad\u0000name", "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { ExtensionPayloadIdentity.fromFiles(listOf(file("package.json", "{}"), file(path, "owned"))) }
        }
        assertThrows(IllegalArgumentException::class.java) { ExtensionPayloadIdentity.fromFiles(listOf(file("package.json", "{}"),file("package.json", "{}"))) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionPayloadIdentity.fromFiles(listOf(file("package.json", "{}"),file("a", "owned"),file("a/b", "owned"))) }
    }
    @Test fun oversizedFilesAndTreesDoNotGetPartialIdentities() {
        seed(); val large = File(directory, "large.bin")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(ExtensionPayloadIdentity.MAX_FILE_BYTES + 1) }
        assertThrows(IOException::class.java) { snapshot() }
        assertThrows(IllegalArgumentException::class.java) { ExtensionPayloadIdentity.fromFiles(listOf(file("package.json", "{}"), ExtensionPayloadFile("large", ExtensionPayloadIdentity.MAX_FILE_BYTES + 1, "a".repeat(64)))) }
        assertThrows(IllegalArgumentException::class.java) { ExtensionPayloadIdentity.fromFiles(List(4001) { file("file-$it", "owned") }) }
    }
    @Test fun unicodePathsAndEmptyFilesKeepTheirExactBytes() {
        val original = seed(); val extra = listOf(file("assets/🌱", ""), file("assets/\uE000", "é"), file("assets/é", "combining"))
        write("assets/🌱", ""); write("assets/\uE000", "é"); write("assets/é", "combining")
        val actual = snapshot(); assertTrue(actual.matches(ExtensionPayloadIdentity.fromFiles(original + extra)))
        assertTrue(actual.files.indexOfFirst { it.path == "assets/\uE000" } < actual.files.indexOfFirst { it.path == "assets/🌱" })
    }
    @Test fun callerCollectionChangesCannotMutateAnExpectedIdentity() {
        val input = seed().toMutableList(); val identity = ExtensionPayloadIdentity.fromFiles(input); val digest = identity.sha256
        input.clear(); assertEquals(3, identity.files.size); assertEquals(digest, identity.sha256)
        assertThrows(UnsupportedOperationException::class.java) { (identity.files as MutableList<ExtensionPayloadFile>).clear() }
    }
}
