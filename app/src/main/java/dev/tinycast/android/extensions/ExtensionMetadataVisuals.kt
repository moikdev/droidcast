package dev.tinycast.android.extensions

import org.json.JSONObject

/** Immutable values matching the currently rendered metadata visual. */
internal data class MetadataIconSignature(val visual: GridVisual, val literalCircle: String?, val tint: String)

internal fun metadataColorName(value: Any?): String = when (value) {
    is JSONObject -> (value.opt("dark") as? String) ?: (value.opt("light") as? String).orEmpty()
    is String -> value
    else -> ""
}

internal fun metadataIconSignature(value: Any?): MetadataIconSignature {
    val source = if (value is JSONObject && value.has("value")) value.opt("value") else value
    val circle = (source as? String)?.takeIf { it in listOf("circle-16", "circle-filled-16", "circle-ellipsis-16") }
    return MetadataIconSignature(gridVisual(value), circle, metadataColorName((value as? JSONObject)?.opt("tintColor")))
}
