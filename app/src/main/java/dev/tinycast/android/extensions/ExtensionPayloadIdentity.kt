package dev.tinycast.android.extensions

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections

internal data class ExtensionPayloadFile(val path: String, val size: Long, val sha256: String)

/**
 * Identity of payload bytes only; this establishes no execution compatibility.
 * SHA-256 framing: ASCII domain plus NUL, then UTF-8-byte-sorted paths, each as
 * big-endian uint32 path length, path bytes, uint64 size and 32 raw digest bytes.
 */
internal class ExtensionPayloadIdentity private constructor(entries: List<ExtensionPayloadFile>) {
    val files: List<ExtensionPayloadFile> = Collections.unmodifiableList(entries.toList())
    val sha256: String = MessageDigest.getInstance("SHA-256").run {
        update("droidcast-extension-payload-v1\u0000".toByteArray(Charsets.US_ASCII))
        for (file in files) {
            val path = file.path.toByteArray(Charsets.UTF_8)
            update(ByteBuffer.allocate(4).putInt(path.size).array()); update(path)
            update(ByteBuffer.allocate(8).putLong(file.size).array())
            update(ByteArray(32) { index -> file.sha256.substring(index * 2, index * 2 + 2).toInt(16).toByte() })
        }
        digest().hex()
    }

    fun matches(other: ExtensionPayloadIdentity): Boolean = sha256 == other.sha256 && files == other.files

    companion object {
        internal const val STORE_METADATA = ".tinycast-store.json"
        internal const val MAX_FILES = 4000
        internal const val MAX_FILE_BYTES = 16L * 1024 * 1024
        internal const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
        internal const val MAX_DEPTH = 32
        private const val MAX_VISITED = 8192
        private val digestPattern = Regex("[0-9a-f]{64}")

        fun fromFiles(entries: List<ExtensionPayloadFile>): ExtensionPayloadIdentity {
            require(entries.size in 1..MAX_FILES) { "Invalid extension payload file count" }
            var total = 0L
            val paths = HashSet<String>()
            for (file in entries) {
                require(validPath(file.path) && file.path != STORE_METADATA && paths.add(file.path)) { "Invalid extension payload path" }
                require(file.size in 0..MAX_FILE_BYTES && digestPattern.matches(file.sha256)) { "Invalid extension payload file" }
                total += file.size
                require(total <= MAX_TOTAL_BYTES) { "Extension payload exceeds the size limit" }
            }
            require("package.json" in paths) { "Extension payload has no manifest" }
            for (path in paths) {
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(parent !in paths) { "Extension payload has conflicting paths" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            return ExtensionPayloadIdentity(entries.sortedWith { left, right ->
                compareUtf8(left.path.toByteArray(Charsets.UTF_8), right.path.toByteArray(Charsets.UTF_8))
            })
        }

        /** Caller supplies its install-generation/coroutine check and must recheck before publishing. */
        fun capture(directory: File, extensionsParent: File, checkActive: () -> Unit): ExtensionPayloadIdentity {
            try {
                checkActive()
                val root = directory.toPath()
                if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root) ||
                    directory.canonicalFile.parentFile != extensionsParent.canonicalFile) unavailable()
                val canonicalRoot = directory.canonicalFile
                val initial = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                fun currentRoot() {
                    checkActive()
                    if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root) || directory.canonicalFile != canonicalRoot) unavailable()
                    val now = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (now.fileKey() != initial.fileKey() || now.lastModifiedTime() != initial.lastModifiedTime()) unavailable()
                }
                val entries = ArrayList<ExtensionPayloadFile>()
                var total = 0L
                var visited = 0
                fun relative(path: Path): String {
                    currentRoot()
                    if (++visited > MAX_VISITED) unavailable()
                    val name = root.relativize(path).joinToString("/")
                    if (!validPath(name) || !path.toFile().canonicalFile.toPath().startsWith(canonicalRoot.toPath())) unavailable()
                    return name
                }
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                        currentRoot()
                        if (path != root && relative(path) == STORE_METADATA) unavailable()
                        if (Files.isSymbolicLink(path) || root.relativize(path).nameCount > MAX_DEPTH) unavailable()
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                        val name = relative(path)
                        if (!attributes.isRegularFile || attributes.isSymbolicLink || Files.isSymbolicLink(path)) unavailable()
                        // This file is written by the installer and is not part of the Store payload.
                        if (name == STORE_METADATA) return FileVisitResult.CONTINUE
                        if (entries.size >= MAX_FILES || attributes.size() > MAX_FILE_BYTES) unavailable()
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        val options = setOf<OpenOption>(StandardOpenOption.READ, NOFOLLOW_LINKS)
                        Files.newByteChannel(path, options).use { channel ->
                            val buffer = ByteBuffer.allocate(8192)
                            var emptyReads = 0
                            while (true) {
                                checkActive()
                                val size = channel.read(buffer)
                                if (size < 0) break
                                if (size == 0) { if (++emptyReads > 8) unavailable(); continue }
                                emptyReads = 0; count += size; total += size
                                if (count > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) unavailable()
                                hash.update(buffer.array(), 0, size); buffer.clear()
                            }
                        }
                        currentRoot()
                        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (!after.isRegularFile || after.isSymbolicLink || count != attributes.size() ||
                            after.size() != count || after.fileKey() != attributes.fileKey() ||
                            after.lastModifiedTime() != attributes.lastModifiedTime()) unavailable()
                        entries += ExtensionPayloadFile(name, count, hash.digest().hex())
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(path: Path, failure: IOException?): FileVisitResult {
                        if (failure != null) throw failure
                        currentRoot(); return FileVisitResult.CONTINUE
                    }
                })
                currentRoot()
                return fromFiles(entries)
            } catch (_: IOException) {
                unavailable()
            } catch (_: SecurityException) {
                unavailable()
            } catch (_: IllegalArgumentException) {
                unavailable()
            } catch (_: UnsupportedOperationException) {
                unavailable()
            }
        }

        private fun validPath(path: String): Boolean {
            if (path.isEmpty() || path.length > 512 || path.startsWith('/') || '\\' in path || path.any { it < ' ' || it == '\u007f' }) return false
            val segments = path.split('/')
            if (segments.size > MAX_DEPTH || segments.any { it.isEmpty() || it == "." || it == ".." }) return false
            var index = 0
            while (index < path.length) {
                val value = path[index++]
                if (Character.isHighSurrogate(value)) {
                    if (index >= path.length || !Character.isLowSurrogate(path[index++])) return false
                } else if (Character.isLowSurrogate(value)) return false
            }
            return path.toByteArray(Charsets.UTF_8).size <= 1024
        }
        private fun compareUtf8(left: ByteArray, right: ByteArray): Int {
            for (index in 0 until minOf(left.size, right.size)) {
                val comparison = (left[index].toInt() and 255).compareTo(right[index].toInt() and 255)
                if (comparison != 0) return comparison
            }
            return left.size.compareTo(right.size)
        }
        private fun unavailable(): Nothing = throw IOException("Installed extension files could not be identified")
        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
