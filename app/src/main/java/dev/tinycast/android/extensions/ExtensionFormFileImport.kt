package dev.tinycast.android.extensions

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class FormDocument(val key: String, val name: String, val directory: Boolean, val size: Long? = null)

/** The Android provider adapter supplies only documents selected by the user.
 * Callback enumeration lets it close its cursor even if a limit aborts a tree.
 */
internal interface FormDocumentSource {
    fun document(key: String): FormDocument
    fun children(document: FormDocument, visit: (FormDocument) -> Unit)
    fun open(document: FormDocument): InputStream
}

internal data class FormImportLimits(val fileBytes: Long = 16L * 1024 * 1024, val totalBytes: Long = 64L * 1024 * 1024,
                                     val entries: Int = 512, val depth: Int = 32)

private fun checkedDirectory(path: Path) {
    require(path.toFile().canonicalFile.toPath() == path.toAbsolutePath().normalize()) { "Extension file storage changed" }
    try { Files.createDirectory(path) } catch (_: FileAlreadyExistsException) { /* Validate an existing directory below. */ }
    require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Extension file storage is unavailable" }
}

private object ActiveFormImports {
    val lock = Any()
    val paths: MutableSet<Path> = ConcurrentHashMap.newKeySet()
}

/** A new process has no live requests. Recover only nonce-owned pending
 * directories, leaving delivered imports and unknown data untouched.
 */
internal fun recoverFormFileImports(root: File, active: Set<Path>? = null) = synchronized(ActiveFormImports.lock) {
    val parent = root.toPath().resolve("form-input")
    if (!Files.exists(parent, NOFOLLOW_LINKS)) return@synchronized
    checkedDirectory(parent)
    Files.newDirectoryStream(parent).use { paths ->
        paths.forEach { path ->
            val name = path.fileName.toString()
            val nonce = name.removePrefix("pending-")
            if (!name.startsWith("pending-") || runCatching { UUID.fromString(nonce).toString() }.getOrNull() != nonce || path in (active ?: ActiveFormImports.paths)) return@forEach
            val marker = path.resolve(".owner")
            if (Files.isDirectory(path, NOFOLLOW_LINKS) && Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
                Files.size(marker) == nonce.length.toLong() && Files.readAllBytes(marker).contentEquals(nonce.toByteArray(Charsets.UTF_8))) {
                PreparedFormFiles(parent, path, nonce, emptyList()).discard()
            }
        }
    }
}

internal fun formFileStorageRoot(files: File, extensionId: String): File {
    require(extensionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) && extensionId != "." && extensionId != "..") { "This extension cannot import files" }
    val parent = files.canonicalFile.toPath()
    require(Files.isDirectory(parent, NOFOLLOW_LINKS))
    val data = parent.resolve("extension-data"); checkedDirectory(data)
    val root = data.resolve(extensionId); checkedDirectory(root)
    return root.toFile()
}

/** A prepared copy is discarded unless its current field accepts delivery.
 * Committing uses a new private name; it never replaces an existing import.
 */
internal class PreparedFormFiles internal constructor(private val parent: Path, private var directory: Path,
    private val nonce: String, private val relativePaths: List<Path>) {
    private var retained = false
    private var committed = false
    private var discarded = false
    init { ActiveFormImports.paths.add(directory) }
    private fun parentAtExpectedPath() = parent.toFile().canonicalFile.toPath() == parent.toAbsolutePath().normalize()
    private fun ownsDirectory(): Boolean = parentAtExpectedPath() && Files.isDirectory(parent, NOFOLLOW_LINKS) &&
        Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory) &&
        Files.isRegularFile(directory.resolve(".owner"), NOFOLLOW_LINKS) && Files.size(directory.resolve(".owner")) == nonce.length.toLong() &&
        Files.readAllBytes(directory.resolve(".owner")).contentEquals(nonce.toByteArray(Charsets.UTF_8))

    @Synchronized fun commit(): List<String> {
        check(!discarded && !retained && !committed && ownsDirectory()) { "File import is no longer available" }
        val target = parent.resolve("import-$nonce")
        check(!Files.exists(target, NOFOLLOW_LINKS)) { "File import destination already exists" }
        Files.move(directory, target)
        ActiveFormImports.paths.remove(directory)
        directory = target; committed = true
        return relativePaths.map { directory.resolve(it).toString() }
    }
    @Synchronized fun retain() {
        check(committed && !discarded) { "File import was not committed" }
        retained = true
    }
    @Synchronized fun discard() {
        if (retained || discarded) return
        check(parentAtExpectedPath()) { "File import cleanup lost its storage" }
        if (!Files.exists(directory, NOFOLLOW_LINKS)) { discarded = true; ActiveFormImports.paths.remove(directory); return }
        check(ownsDirectory()) { "File import cleanup lost its owner" }
        // Default walk options do not follow links planted in the owned tree.
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
        discarded = true
        ActiveFormImports.paths.remove(directory)
    }
}

internal class FormFileImport(private val root: File, private val limits: FormImportLimits = FormImportLimits()) {
    init {
        require(limits.fileBytes in 1..(16L * 1024 * 1024) && limits.totalBytes in limits.fileBytes..(256L * 1024 * 1024))
        require(limits.entries in 1..4096 && limits.depth in 1..64)
        require(root.absoluteFile == root.canonicalFile && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) { "Extension file storage is unavailable" }
    }
    fun prepare(keys: List<String>, source: FormDocumentSource, files: Boolean, directories: Boolean,
                cancelled: () -> Boolean): PreparedFormFiles {
        require(keys.isNotEmpty() && keys.size <= limits.entries && keys.distinct().size == keys.size) { "Choose a supported number of documents" }
        val parent = root.toPath().resolve("form-input"); checkedDirectory(parent)
        val relative = mutableListOf<Path>()
        val (workspace, prepared) = synchronized(ActiveFormImports.lock) {
            val nonce = UUID.randomUUID().toString()
            val workspace = parent.resolve("pending-$nonce"); Files.createDirectory(workspace)
            val directoryKey = Files.readAttributes(workspace, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            var markerKey: Any? = null
            try {
                Files.newOutputStream(workspace.resolve(".owner"), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                    markerKey = Files.readAttributes(workspace.resolve(".owner"), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                    output.write(nonce.toByteArray(Charsets.UTF_8))
                }
                workspace to PreparedFormFiles(parent, workspace, nonce, relative)
            } catch (failure: Throwable) {
                runCatching {
                    if (directoryKey != null && Files.readAttributes(workspace, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == directoryKey) {
                        val marker = workspace.resolve(".owner")
                        if (markerKey != null && Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == markerKey) Files.delete(marker)
                        Files.delete(workspace) // Fails safely if an unexpected file appeared.
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        var bytes = 0L; var entries = 0
        fun active() { if (cancelled() || Thread.currentThread().isInterrupted) throw IOException("File import was cancelled") }
        fun name(value: String): String {
            require(value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it == '\u0000' } && value.toByteArray(Charsets.UTF_8).size <= 255) { "This document name cannot be imported" }
            return value
        }
        fun parents(destination: Path) {
            var path = workspace
            require(path.toFile().canonicalFile.toPath() == path.toAbsolutePath().normalize() && Files.isDirectory(path, NOFOLLOW_LINKS)) { "File import storage changed" }
            workspace.relativize(destination.parent).forEach { component ->
                path = path.resolve(component)
                require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "File import storage changed" }
            }
        }
        fun copy(document: FormDocument, destination: Path, level: Int, ancestors: Set<String>) {
            active(); entries++
            require(entries <= limits.entries && level <= limits.depth) { "This selection contains too many documents" }
            require(!Files.exists(destination, NOFOLLOW_LINKS) && destination.normalize().startsWith(workspace)) { "Document names conflict" }
            parents(destination)
            if (document.directory) {
                require(document.key !in ancestors) { "This folder contains a document cycle" }
                Files.createDirectory(destination)
                source.children(document) { child -> copy(child, destination.resolve(name(child.name)), level + 1, ancestors + document.key) }
            } else {
                require(document.size == null || document.size <= limits.fileBytes) { "This file exceeds the import limit" }
                var fileBytes = 0L
                source.open(document).use { input ->
                    Files.newOutputStream(destination, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            active(); val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw IOException("The document provider did not return data")
                            require(count.toLong() <= limits.fileBytes - fileBytes && count.toLong() <= limits.totalBytes - bytes) { "This selection exceeds the import limit" }
                            output.write(buffer, 0, count); fileBytes += count; bytes += count
                        }
                    }
                }
            }
        }
        try {
            keys.forEachIndexed { index, key ->
                active(); val document = source.document(key)
                require(if (document.directory) directories else files) { "This document type is not allowed for this field" }
                require(workspace.toFile().canonicalFile.toPath() == workspace.toAbsolutePath().normalize() && Files.isDirectory(workspace, NOFOLLOW_LINKS)) { "File import storage changed" }
                val container = workspace.resolve(index.toString()); Files.createDirectory(container)
                val destination = container.resolve(name(document.name))
                copy(document, destination, 1, emptySet()); relative.add(workspace.relativize(destination))
            }
            active(); return prepared
        } catch (failure: Throwable) {
            try { prepared.discard() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
