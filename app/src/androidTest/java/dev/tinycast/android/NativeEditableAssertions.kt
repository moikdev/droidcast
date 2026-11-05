package dev.tinycast.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher

/** Exact UTF-16 content; IME composition may add decoration to EditableText. */
internal fun editableCharactersExactly(expected: String) = SemanticsMatcher(
    "EditableText has exactly ${expected.length} UTF-16 units and matching characters"
) { node -> node.config.getOrNull(SemanticsProperties.EditableText)?.text == expected }
