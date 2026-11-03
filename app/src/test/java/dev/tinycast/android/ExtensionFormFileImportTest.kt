package dev.tinycast.android

import dev.tinycast.android.extensions.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionFormFileImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Source(val documents: Map<String, FormDocument>, val bytes: Map<String, ByteArray>,
                         val descendants: Map<String, List<String>> = emptyMap()) : FormDocumentSource {
        override fun document(key: String) = requireNotNull(documents[key])
        override fun children(document: FormDocument, visit: (FormDocument) -> Unit) {
            descendants[document.key].orEmpty().forEach { visit(this.document(it)) }
        }
        override fun open(document: FormDocument) = ByteArrayInputStream(requireNotNull(bytes[document.key]))
    }
    private fun source(name: String = "owned.json", bytes: ByteArray = byteArrayOf(0, 1, 2, -1)) =
        Source(mapOf("owned" to FormDocument("owned", name, false)), mapOf("owned" to bytes))
    private fun root(): File = formFileStorageRoot(temporary.newFolder(), "owned-extension")
    private fun emptyImports(root: File) = assertTrue(File(root, "form-input").listFiles().orEmpty().isEmpty())

    @Test fun selectedBytesAndUnicodeNamesAreExactAndRetainedCopiesStayReadable() {
        val root = root(); val first = byteArrayOf(0, 1, 2, -1); val second = "café é 日本 🌱".toByteArray()
        val source = Source(mapOf("a" to FormDocument("a", "café 日本.json", false), "b" to FormDocument("b", "café 日本.json", false)), mapOf("a" to first, "b" to second))
        val prepared = FormFileImport(root).prepare(listOf("a", "b"), source, true, false) { false }
        val paths = prepared.commit(); assertEquals(2, paths.size); assertNotEquals(paths[0], paths[1])
        assertEquals("café 日本.json", File(paths[0]).name); assertArrayEquals(first, File(paths[0]).readBytes()); assertArrayEquals(second, File(paths[1]).readBytes())
        assertTrue(paths.all { File(it).canonicalPath.startsWith(root.canonicalPath + File.separator) })
        prepared.retain(); prepared.discard(); assertTrue(paths.all { File(it).isFile })
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), first)
    }
    @Test fun anUndeliveredCommitCanBeDiscardedWithoutChangingUnrelatedData() {
        val root = root(); val unrelated = File(root, "existing.txt").apply { writeText("Owned pre-existing data") }
        val prepared = FormFileImport(root).prepare(listOf("owned"), source(), true, false) { false }
        val paths = prepared.commit(); assertTrue(File(paths.single()).exists()); prepared.discard(); emptyImports(root)
        assertEquals("Owned pre-existing data", unrelated.readText())
        assertThrows(IllegalStateException::class.java) { prepared.commit() }
    }
    @Test fun traversalNamesNeverBecomeDestinationPaths() {
        listOf("../outside", ".", "..", "folder/file", "folder\\file", "\u0000name").forEach { name ->
            val root = root()
            assertThrows(IllegalArgumentException::class.java) { FormFileImport(root).prepare(listOf("owned"), source(name), true, false) { false } }
            emptyImports(root)
        }
    }
    @Test fun actualStreamBytesAreBoundedEvenWhenSizeMetadataIsMissing() {
        val root = root()
        assertThrows(IllegalArgumentException::class.java) {
            FormFileImport(root, FormImportLimits(fileBytes = 8, totalBytes = 8)).prepare(listOf("owned"), source(bytes = ByteArray(9)), true, false) { false }
        }
        emptyImports(root)
    }
    @Test fun aggregateLimitsRollBackTheWholeSelection() {
        val root = root()
        val source = Source(mapOf("a" to FormDocument("a", "one.txt", false), "b" to FormDocument("b", "two.txt", false)), mapOf("a" to ByteArray(6), "b" to ByteArray(6)))
        assertThrows(IllegalArgumentException::class.java) { FormFileImport(root, FormImportLimits(fileBytes = 8, totalBytes = 10)).prepare(listOf("a", "b"), source, true, false) { false } }
        emptyImports(root)
    }
    @Test fun directoriesRespectChoicePolicyAndRejectProviderCycles() {
        val root = root(); val source = Source(mapOf("folder" to FormDocument("folder", "owned", true)), emptyMap(), mapOf("folder" to listOf("folder")))
        assertThrows(IllegalArgumentException::class.java) { FormFileImport(root).prepare(listOf("folder"), source, true, false) { false } }
        emptyImports(root)
        assertThrows(IllegalArgumentException::class.java) { FormFileImport(root).prepare(listOf("folder"), source, false, true) { false } }
        emptyImports(root)
    }
    @Test fun aSelectedDirectoryRetainsItsHierarchyAndExactLeafBytes() {
        val root = root(); val bytes = "Owned nested café 日本 🌱".toByteArray()
        val source = Source(mapOf("folder" to FormDocument("folder", "Owned set", true), "nested" to FormDocument("nested", "nested", true),
            "leaf" to FormDocument("leaf", "owned.txt", false)), mapOf("leaf" to bytes), mapOf("folder" to listOf("nested"), "nested" to listOf("leaf")))
        val prepared = FormFileImport(root).prepare(listOf("folder"), source, false, true) { false }
        val directory = File(prepared.commit().single())
        assertTrue(directory.isDirectory); assertArrayEquals(bytes, File(directory, "nested/owned.txt").readBytes())
        prepared.retain(); prepared.discard(); assertTrue(directory.isDirectory)
    }
    @Test fun cancellationAfterAReadDiscardsTheUndeliveredCopy() {
        val root = root(); val cancelled = AtomicBoolean(false)
        val source = object : FormDocumentSource {
            override fun document(key: String) = FormDocument(key, "owned.bin", false)
            override fun children(document: FormDocument, visit: (FormDocument) -> Unit) = Unit
            override fun open(document: FormDocument) = object : ByteArrayInputStream(ByteArray(16)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { cancelled.set(true) }
            }
        }
        assertThrows(IOException::class.java) { FormFileImport(root).prepare(listOf("owned"), source, true, false) { cancelled.get() } }
        emptyImports(root)
    }
    @Test fun aProviderThatMakesNoReadProgressDoesNotSpinOrLeaveACopy() {
        val root = root()
        val source = object : FormDocumentSource {
            override fun document(key: String) = FormDocument(key, "owned.bin", false)
            override fun children(document: FormDocument, visit: (FormDocument) -> Unit) = Unit
            override fun open(document: FormDocument) = object : java.io.InputStream() {
                override fun read() = 0
                override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
            }
        }
        assertThrows(IOException::class.java) { FormFileImport(root).prepare(listOf("owned"), source, true, false) { false } }
        emptyImports(root)
    }
    @Test fun cleanupDoesNotFollowLinksToOtherData() {
        val root = root(); val outside = temporary.newFolder(); val sentinel = File(outside, "keep.txt").apply { writeText("Owned outside sentinel") }
        val prepared = FormFileImport(root).prepare(listOf("owned"), source(), true, false) { false }
        val pending = File(root, "form-input").listFiles()!!.single()
        Files.createSymbolicLink(File(pending, "link").toPath(), outside.toPath())
        prepared.discard(); emptyImports(root); assertEquals("Owned outside sentinel", sentinel.readText())
    }
    @Test fun losingTheCleanupOwnerRetainsDataUntilOwnershipIsRecovered() {
        val root = root(); val prepared = FormFileImport(root).prepare(listOf("owned"), source(), true, false) { false }
        val pending = File(root, "form-input").listFiles()!!.single(); val marker = File(pending, ".owner"); val original = marker.readBytes()
        marker.writeText("replacement owner")
        assertThrows(IllegalStateException::class.java) { prepared.discard() }; assertTrue(pending.exists())
        marker.writeBytes(original); prepared.discard(); emptyImports(root)
    }
    @Test fun storageRootRejectsTraversalAndLinkedImportDirectories() {
        val files = temporary.newFolder(); assertThrows(IllegalArgumentException::class.java) { formFileStorageRoot(files, "../other") }
        val root = formFileStorageRoot(files, "owned-extension"); val outside = temporary.newFolder()
        Files.createSymbolicLink(File(root, "form-input").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { FormFileImport(root).prepare(listOf("owned"), source(), true, false) { false } }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }
}
