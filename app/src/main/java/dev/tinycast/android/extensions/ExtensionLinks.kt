package dev.tinycast.android.extensions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The runtime opener and native document links share one explicit scheme policy. */
internal fun extensionLinkUri(value: String): Uri {
    require(value.length in 1..8192 && value.none { it.code < 32 || it.code == 127 }) { "This link is unavailable on Android" }
    val uri = Uri.parse(value).normalizeScheme()
    require(uri.scheme in listOf("https", "http", "mailto", "geo")) { "This link is unavailable on Android" }
    if (uri.scheme in listOf("http", "https")) {
        val host = uri.host
        require(!uri.isOpaque && !host.isNullOrBlank() && host.none { it.isWhitespace() || it.code < 32 || it.code == 127 }) {
            "This link is unavailable on Android"
        }
    } else require(!uri.schemeSpecificPart.isNullOrBlank()) { "This link is unavailable on Android" }
    return uri
}

internal fun openExtensionLink(context: Context, target: String) {
    val uri = extensionLinkUri(target)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        throw IllegalStateException("No app can open this link")
    } catch (_: SecurityException) {
        throw IllegalStateException("Android could not open this link")
    }
}
