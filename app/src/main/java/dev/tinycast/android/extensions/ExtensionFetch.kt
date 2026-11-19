package dev.tinycast.android.extensions

import android.util.Base64
import org.json.JSONObject

/** Converts the portable runtime's Fetch envelope to and from the native HTTP transport. */
internal suspend fun ExtensionHttpClient.fetch(envelope: JSONObject): JSONObject {
    val headers = envelope.optJSONObject("headers") ?: JSONObject()
    val body = if (envelope.isNull("bodyBase64")) null else {
        val encoded = envelope.get("bodyBase64")
        require(encoded is String) { "Fetch body must be a Base64 string" }
        Base64.decode(encoded, Base64.DEFAULT)
    }
    val response = request(
        envelope.getString("url"), envelope.optString("method", "GET"),
        headers.keys().asSequence().associateWith { headers.getString(it) }, body, 8 * 1024 * 1024
    )
    val responseHeaders = JSONObject()
    response.headers.forEach { (key, values) -> responseHeaders.put(key, values.joinToString(", ")) }
    return JSONObject().put("status", response.status).put("statusText", response.message)
        .put("url", response.url).put("headers", responseHeaders)
        .put("bodyBase64", Base64.encodeToString(response.bytes, Base64.NO_WRAP))
}
