package dev.tinycast.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.AnnotatedString

/** Matched control: original strict annotation expectation. */
internal fun editableCharactersExactly(expected: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(expected))
