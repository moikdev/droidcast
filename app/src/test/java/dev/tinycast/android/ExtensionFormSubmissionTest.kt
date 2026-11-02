package dev.tinycast.android

import dev.tinycast.android.extensions.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionFormSubmissionTest {
    @Test fun aMissingOrDisposedFormCannotPrepareSubmission() {
        val preparation = FormSubmitPreparation(); val token = Any(); var calls = 0
        assertFalse(preparation.prepare())
        preparation.attach(token) { calls++ }; preparation.detach(token)
        assertFalse(preparation.prepare()); assertEquals(0, calls)
    }
    @Test fun oldDisposalCannotRemoveTheCurrentFormCallback() {
        val preparation = FormSubmitPreparation(); val old = Any(); val current = Any(); var oldCalls = 0; var currentCalls = 0
        preparation.attach(old) { oldCalls++ }; preparation.attach(current) { currentCalls++ }; preparation.detach(old)
        assertTrue(preparation.prepare()); assertEquals(0, oldCalls); assertEquals(1, currentCalls)
    }
    @Test fun disposalDuringBlurInvalidatesTheOutgoingSubmit() {
        val preparation = FormSubmitPreparation(); val token = Any()
        preparation.attach(token) { preparation.detach(token) }
        assertFalse(preparation.prepare()); assertFalse(preparation.prepare())
    }
    @Test fun aReentrantSuccessorCannotInheritTheOutgoingRequest() {
        val preparation = FormSubmitPreparation(); val old = Any(); val successor = Any(); var successorCalls = 0
        preparation.attach(old) { preparation.attach(successor) { successorCalls++ } }
        assertFalse(preparation.prepare()); assertEquals(0, successorCalls)
        assertTrue(preparation.prepare()); assertEquals(1, successorCalls)
    }
    @Test fun changingTheCallbackForTheSameOwnerAlsoInvalidatesPreparation() {
        val preparation = FormSubmitPreparation(); val token = Any(); var replacementCalls = 0
        preparation.attach(token) { preparation.attach(token) { replacementCalls++ } }
        assertFalse(preparation.prepare()); assertEquals(0, replacementCalls)
    }
    @Test fun submitBlurUsesTheLatestEditExactlyOnceAndAllowsRefocus() {
        val field = FormFieldOwner("root", "native", "name", "Form.TextField")
        val values = FormEditorValues(); values.observe(field, Any(), "initial")
        val events = mutableListOf<Pair<Boolean, Any?>>()
        val focus = FormFocusDelivery({ true }) { owner, focused -> events += focused to values.value(owner) }
        val preparation = FormSubmitPreparation(); preparation.attach(Any()) { focus.blurCurrent() }
        focus.focus(field); values.edit(field, "Owned 日本 🌱")
        assertTrue(preparation.prepare()); assertTrue(preparation.prepare()); focus.focus(field)
        assertEquals(listOf(true to "initial", false to "Owned 日本 🌱", true to "Owned 日本 🌱"), events)
    }
    @Test fun aRemovedFieldCannotReceiveSubmitBlur() {
        val field = FormFieldOwner("root", "native", "name", "Form.TextField"); var live = true
        val events = mutableListOf<Boolean>(); val focus = FormFocusDelivery({ live }) { _, focused -> events += focused }
        val preparation = FormSubmitPreparation(); preparation.attach(Any()) { focus.blurCurrent() }
        focus.focus(field); live = false
        assertTrue(preparation.prepare()); assertEquals(listOf(true), events)
    }
}
