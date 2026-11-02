package dev.tinycast.android

import dev.tinycast.android.extensions.FormEditorValues
import dev.tinycast.android.extensions.FormFieldOwner
import org.junit.Assert.*
import org.junit.Test

class ExtensionFormValuesTest {
    private val field = FormFieldOwner("form", "native-field", "name", "Form.TextField")
    @Test fun samePublicationDoesNotOverwriteAnEditBeforeBlur() {
        val values = FormEditorValues(); val render = Any()
        values.observe(field, render, ""); values.edit(field, "Owned café é 日本 🌱")
        values.observe(field, render, "")
        assertEquals("Owned café é 日本 🌱", values.value(field))
    }
    @Test fun aNewPublicationCanTransformOrRejectTheNativeEdit() {
        val values = FormEditorValues(); values.observe(field, Any(), "initial")
        values.edit(field, "native"); values.observe(field, Any(), "NATIVE")
        assertEquals("NATIVE", values.value(field))
        values.edit(field, "rejected"); values.observe(field, Any(), "NATIVE")
        assertEquals("NATIVE", values.value(field))
    }
    @Test fun booleanFalseIsAnEventValueRatherThanMissingData() {
        val values = FormEditorValues(); values.observe(field, Any(), true); values.edit(field, false)
        assertEquals(false, values.value(field))
    }
    @Test fun removedAndUnregisteredOwnersCannotAdoptAnotherFieldValue() {
        val values = FormEditorValues(); values.observe(field, Any(), "owned"); values.remove(field)
        values.edit(field, "stale"); assertNull(values.value(field))
        val replacement = field.copy(nativeId = "replacement")
        values.observe(replacement, Any(), "new"); assertNull(values.value(field)); assertEquals("new", values.value(replacement))
    }
    @Test fun disposalDropsAllEditorValues() {
        val values = FormEditorValues(); values.observe(field, Any(), "owned"); values.clear()
        assertNull(values.value(field))
    }
}
