package dev.tinycast.android

import dev.tinycast.android.extensions.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionFormContractTest {
    private val name = FormFieldOwner("root-one", "native-one", "name", "Form.TextField")
    private val notes = FormFieldOwner("root-one", "native-two", "notes", "Form.TextArea")
    private val choices = listOf(FormChoice("option-one", "internal-one", "Readable first"), FormChoice("option-two", "internal-two", "Readable second"))

    @Test fun ordinaryRerendersKeepTheSameFieldAuthority() {
        assertTrue(sameFormField(name.copy(), "root-one", "native-one", "name", "Form.TextField"))
    }
    @Test fun identicalLogicalIdsInAnotherRootCannotInheritAQueuedFieldEvent() {
        assertFalse(sameFormField(name, "root-two", "native-one", "name", "Form.TextField"))
    }
    @Test fun aReplacementFieldWithTheSameLogicalIdRejectsTheOldNativeCallback() {
        assertFalse(sameFormField(name, "root-one", "native-replacement", "name", "Form.TextField"))
    }
    @Test fun reusedNativeIdsCannotChangeTheirLogicalFieldOrControlType() {
        assertFalse(sameFormField(name, "root-one", "native-one", "notes", "Form.TextField"))
        assertFalse(sameFormField(name, "root-one", "native-one", "name", "Form.PasswordField"))
        assertFalse(sameFormField(name, "root-one", "native-one", null, "Form.TextField"))
    }
    @Test fun selectedTitlesNeverReplaceTheUnderlyingSubmittedValue() {
        assertEquals("Readable second", formChoiceTitle("internal-two", choices))
        assertEquals("internal-two", currentFormChoice(choices[1], choices)!!.value)
    }
    @Test fun duplicateReadableTitlesStillIdentifyDistinctValues() {
        val duplicate = choices.map { it.copy(title = "Same readable title") }
        assertEquals("internal-two", currentFormChoice(duplicate[1], duplicate)!!.value)
        assertEquals("Same readable title", formChoiceTitle("internal-two", duplicate))
    }
    @Test fun aMissingSelectionHasAnExplicitLabelWithoutInventingANewValue() {
        assertEquals("Selection unavailable", formChoiceTitle("vanished", choices))
        assertEquals("Choose…", formChoiceTitle("", choices))
        assertEquals("Pick an owned option", formChoiceTitle("", emptyList(), "Pick an owned option"))
    }
    @Test fun anExplicitEmptyValuedOptionKeepsItsOwnTitle() {
        assertEquals("Automatic", formChoiceTitle("", listOf(FormChoice("automatic", "", "Automatic"))))
    }
    @Test fun aNewOptionAtTheSamePositionCannotInheritAnOldMenuChoice() {
        assertNull(currentFormChoice(choices[0], listOf(choices[0].copy(nativeId = "replacement"), choices[1])))
    }
    @Test fun changedOptionMeaningInvalidatesItsCapturedChoice() {
        assertNull(currentFormChoice(choices[0], listOf(choices[0].copy(title = "Different meaning"))))
        assertNull(currentFormChoice(choices[0], listOf(choices[0].copy(value = "different-value"))))
        assertNull(currentFormChoice(choices[0], emptyList()))
    }
    @Test fun reorderingTheSameOptionsDoesNotChangeTheirIdentity() {
        assertEquals(choices[0], currentFormChoice(choices[0], choices.reversed()))
    }
    @Test fun rerendersDoNotDuplicateFocusAndBlur() {
        val events = mutableListOf<Pair<FormFieldOwner, Boolean>>()
        val delivery = FormFocusDelivery({ true }) { owner, focused -> events += owner to focused }
        delivery.focus(name); delivery.focus(name.copy()); delivery.blur(name); delivery.blur(name)
        assertEquals(listOf(name to true, name to false), events)
    }
    @Test fun anOutgoingBlurCannotClearTheNewerFocusedField() {
        val events = mutableListOf<Pair<FormFieldOwner, Boolean>>()
        val delivery = FormFocusDelivery({ true }) { owner, focused -> events += owner to focused }
        delivery.focus(name); delivery.focus(notes); delivery.blur(name); delivery.focus(notes)
        assertEquals(listOf(name to true, name to false, notes to true), events)
    }
    @Test fun aRemovedFieldCannotReceiveFocusOrAStaleBlur() {
        val live = mutableSetOf(name, notes)
        val events = mutableListOf<Pair<FormFieldOwner, Boolean>>()
        val delivery = FormFocusDelivery({ it in live }) { owner, focused -> events += owner to focused }
        delivery.focus(name); live.remove(name); delivery.blur(name); delivery.focus(name); delivery.focus(notes)
        assertEquals(listOf(name to true, notes to true), events)
    }
    @Test fun blurValidationCanRemoveTheNextFieldBeforeItReceivesFocus() {
        val live = mutableSetOf(name, notes)
        val events = mutableListOf<Pair<FormFieldOwner, Boolean>>()
        val delivery = FormFocusDelivery({ it in live }) { owner, focused ->
            events += owner to focused
            if (owner == name && !focused) live.remove(notes)
        }
        delivery.focus(name); delivery.focus(notes)
        assertEquals(listOf(name to true, name to false), events)
    }
    @Test fun reentrantFocusFromBlurSupersedesTheOutgoingTransition() {
        val third = notes.copy(nativeId = "native-third", logicalId = "third")
        val events = mutableListOf<Pair<FormFieldOwner, Boolean>>()
        lateinit var delivery: FormFocusDelivery
        delivery = FormFocusDelivery({ true }) { owner, focused ->
            events += owner to focused
            if (owner == name && !focused) delivery.focus(third)
        }
        delivery.focus(name); delivery.focus(notes)
        assertEquals(listOf(name to true, name to false, third to true), events)
    }
    @Test fun clearingAnOutgoingFormDropsItsPendingFocusWithoutDispatching() {
        val events = mutableListOf<Pair<FormFieldOwner, Boolean>>()
        val delivery = FormFocusDelivery({ true }) { owner, focused -> events += owner to focused }
        delivery.focus(name); delivery.clear(); delivery.blur(name)
        assertEquals(listOf(name to true), events)
    }
}
