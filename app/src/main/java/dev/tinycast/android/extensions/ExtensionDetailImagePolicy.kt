package dev.tinycast.android.extensions

import java.io.File
import java.net.URI
import java.net.URLDecoder

internal data class DetailImageRequest(val source: String, val width: Int?, val height: Int?, val tint: String?)

/** Rendering hints are removed without rewriting an HTTPS service's other query bytes. */
internal fun detailImageRequest(source: String): DetailImageRequest {
    require(source.isNotBlank() && source.length <= 3 * 1024 * 1024 && source.none { it.code < 32 || it.code == 127 })
    val data = source.startsWith("data:", true)
    val queryStart = source.indexOf('?').takeIf { !data || source.substring(it + 1).startsWith("raycast-") }
    val uri = if (data) null else URI(source.replace(" ", "%20"))
    val query = if (data) queryStart?.let { source.substring(it + 1) } else uri?.rawQuery
    val remaining = mutableListOf<String>(); val hints = mutableMapOf<String, String>()
    query?.split('&')?.forEach { component ->
        val key = URLDecoder.decode(component.substringBefore('='), "UTF-8")
        val name = when (key) {
            "raycast-width" -> "width"; "raycast-height" -> "height"
            "raycast-tint-color", "raycast-tintColor" -> "tint"
            else -> null
        }
        if (name == null) remaining += component else {
            require(name !in hints) { "Ambiguous image rendering hint" }
            hints[name] = URLDecoder.decode(component.substringAfter('=', ""), "UTF-8")
        }
    }
    fun dimension(name: String): Int? = hints[name]?.let {
        if (it == "auto") null else {
            require(it.matches(Regex("[0-9]{1,4}")))
            it.toInt().also { value -> require(value in 1..2048) }
        }
    }
    val clean = if (data) {
        require(remaining.isEmpty()); queryStart?.let { source.substring(0, it) } ?: source
    } else {
        requireNotNull(uri); require(source.length <= 8192)
        val scheme = uri.scheme?.lowercase()
        require(scheme == null || scheme in listOf("file", "https")) { "Unsupported image source" }
        if (scheme == "https") require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in listOf(-1, 443))
        else require(uri.rawAuthority.isNullOrBlank() && remaining.isEmpty())
        buildString {
            if (scheme != null) append(scheme).append(':')
            if (uri.rawAuthority != null) append("//").append(uri.rawAuthority)
            append(uri.rawPath.orEmpty())
            if (remaining.isNotEmpty()) append('?').append(remaining.joinToString("&"))
        }
    }
    val tint = hints["tint"]?.also { require(it.length in 1..128 && it.none(Char::isISOControl)) }
    return DetailImageRequest(clean, dimension("width"), dimension("height"), tint)
}

/** URI decoding precedes canonical containment, including siblings and symbolic links. */
internal fun detailImageAsset(directory: File, source: String): File {
    val uri = URI(source.replace(" ", "%20"))
    require((uri.scheme == null || uri.scheme == "file") && uri.rawAuthority.isNullOrBlank() && uri.query == null && uri.fragment == null)
    val path = requireNotNull(uri.path); require(path.isNotBlank() && '\u0000' !in path)
    val root = directory.canonicalFile
    val file = (if (File(path).isAbsolute) File(path) else File(File(root, "assets"), path)).canonicalFile
    require(file != root && file.toPath().startsWith(root.toPath())) { "Image is outside its extension package" }
    return file
}
