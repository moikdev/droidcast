package dev.tinycast.android

import org.junit.Assert.*
import org.junit.Test

class ReviewedTextInputTest {
    private val json = TextInputTarget("json-format", "formatSelectionAndShow")
    private val translate = TextInputTarget("translate", "instant-translate-copy")
    private fun rejected(problem: TextInputProblem, action: () -> Unit) {
        try { action(); fail("Expected $problem") }
        catch (error: TextInputRejected) { assertEquals(problem, error.problem) }
    }
    private fun approve(state: ReviewedTextInput, text: String, owner: Any): CommandTextInput {
        val preview = state.receive(text, TextInputOrigin.SELECTION)
        state.choose(preview, json)
        return state.approve(preview, json, owner)
    }

    @Test fun receiving_copies_mutable_text_without_normalizing_unicode_or_whitespace() {
        val state = ReviewedTextInput()
        val original = " \t{\"message\":\"Hi 🌸\",\"日本語\":true}\r\n "
        val mutable = StringBuilder(original)
        val preview = state.receive(mutable, TextInputOrigin.SELECTION, false)
        mutable.clear().append("changed after receipt")
        assertEquals(original, preview.text)
        assertFalse(preview.readOnly)
        assertEquals(TextInputOrigin.SELECTION, preview.origin)
        assertNull(preview.target)
    }

    @Test fun size_limit_counts_utf16_units_and_accepts_the_exact_boundary() {
        val state = ReviewedTextInput()
        val boundary = "🌸".repeat(32_768)
        assertEquals(65_536, boundary.length)
        assertEquals(boundary, state.receive(boundary, TextInputOrigin.SELECTION).text)
        rejected(TextInputProblem.TOO_LONG) { state.receive(boundary + "x", TextInputOrigin.SELECTION) }
        assertNull(state.preview)
    }

    @Test fun whitespace_is_valid_selection_text_and_share_has_no_writable_source() {
        val state = ReviewedTextInput()
        val preview = state.receive(" \n\t", TextInputOrigin.SHARE, false)
        assertEquals(" \n\t", preview.text)
        assertTrue(preview.readOnly)
    }

    @Test fun invalid_replacements_revoke_an_already_approved_input() {
        val state = ReviewedTextInput(); val owner = Any()
        val active = approve(state, "original", owner)
        rejected(TextInputProblem.INVALID_TYPE) { state.receive(123, TextInputOrigin.SHARE) }
        rejected(TextInputProblem.UNAVAILABLE) { active.read(owner, json) }
        assertNull(state.preview)
        rejected(TextInputProblem.MISSING) { state.receive(null, TextInputOrigin.SELECTION) }
        rejected(TextInputProblem.MISSING) { state.receive("", TextInputOrigin.SELECTION) }
    }

    @Test fun wrong_types_are_rejected_without_calling_their_to_string() {
        val value = object { override fun toString(): String = throw AssertionError("Must not coerce arbitrary objects") }
        rejected(TextInputProblem.INVALID_TYPE) { ReviewedTextInput().receive(value, TextInputOrigin.SHARE) }
    }

    @Test fun a_char_sequence_cannot_bypass_the_limit_by_lying_about_its_length() {
        val value = object : CharSequence {
            override val length = 1
            override fun get(index: Int) = 'x'
            override fun subSequence(startIndex: Int, endIndex: Int) = "x"
            override fun toString() = "x".repeat(65_537)
        }
        rejected(TextInputProblem.TOO_LONG) { ReviewedTextInput().receive(value, TextInputOrigin.SELECTION) }
    }

    @Test fun approval_requires_a_chosen_command_and_consumes_the_current_review_once() {
        val state = ReviewedTextInput(); val owner = Any()
        val preview = state.receive("reviewed", TextInputOrigin.SELECTION)
        rejected(TextInputProblem.NO_COMMAND) { state.approve(preview, json, owner) }
        state.choose(preview, json)
        val input = state.approve(preview, json, owner)
        assertNull(state.preview); assertNull(preview.text)
        assertEquals("reviewed", input.read(owner, json))
        rejected(TextInputProblem.EXPIRED) { state.approve(preview, json, owner) }
    }

    @Test fun a_changed_command_rejects_a_retained_run_callback_without_consuming_the_review() {
        val state = ReviewedTextInput(); val owner = Any()
        val preview = state.receive("reviewed", TextInputOrigin.SELECTION)
        state.choose(preview, json); state.choose(preview, translate)
        rejected(TextInputProblem.CHANGED_COMMAND) { state.approve(preview, json, owner) }
        assertSame(preview, state.preview); assertEquals("reviewed", preview.text)
        assertEquals("reviewed", state.approve(preview, translate, owner).read(owner, translate))
    }

    @Test fun an_outgoing_review_cannot_approve_or_change_its_replacement() {
        val state = ReviewedTextInput()
        val old = state.receive("old", TextInputOrigin.SELECTION)
        val current = state.receive("current", TextInputOrigin.SHARE)
        state.choose(current, json)
        rejected(TextInputProblem.EXPIRED) { state.choose(old, translate) }
        rejected(TextInputProblem.EXPIRED) { state.approve(old, json, Any()) }
        assertNull(old.text); assertSame(current, state.preview); assertEquals(json, current.target)
    }

    @Test fun cancellation_clears_only_the_current_review() {
        val state = ReviewedTextInput()
        val old = state.receive("old", TextInputOrigin.SELECTION)
        val current = state.receive("current", TextInputOrigin.SELECTION)
        assertFalse(state.cancel(old)); assertSame(current, state.preview)
        assertTrue(state.cancel(current)); assertNull(state.preview); assertNull(current.text)
    }

    @Test fun a_queued_cancel_from_the_review_cannot_revoke_the_approved_invocation() {
        val state = ReviewedTextInput(); val owner = Any()
        val preview = state.receive("approved", TextInputOrigin.SELECTION)
        state.choose(preview, json)
        val input = state.approve(preview, json, owner)
        assertFalse(state.cancel(preview))
        assertEquals("approved", input.read(owner, json))
    }

    @Test fun authority_is_bound_to_both_the_original_owner_and_target() {
        val state = ReviewedTextInput(); val owner = Any()
        val input = approve(state, "private text", owner)
        rejected(TextInputProblem.UNAVAILABLE) { input.read(Any(), json) }
        rejected(TextInputProblem.UNAVAILABLE) { input.read(owner, translate) }
        assertEquals("private text", input.read(owner, json))
        state.invalidate()
        rejected(TextInputProblem.UNAVAILABLE) { input.read(owner, json) }
    }

    @Test fun releasing_an_outgoing_input_does_not_clear_a_new_review_or_invocation() {
        val state = ReviewedTextInput(); val firstOwner = Any(); val secondOwner = Any()
        val first = approve(state, "first", firstOwner)
        val next = state.receive("second", TextInputOrigin.SELECTION)
        state.release(first)
        assertSame(next, state.preview)
        state.choose(next, json)
        val second = state.approve(next, json, secondOwner)
        state.release(first)
        assertEquals("second", second.read(secondOwner, json))
        state.release(second)
        rejected(TextInputProblem.UNAVAILABLE) { second.read(secondOwner, json) }
    }

    @Test fun diagnostics_and_errors_do_not_echo_input_content() {
        val state = ReviewedTextInput(); val owner = Any(); val secret = "OWNED_PRIVATE_CANARY"
        val preview = state.receive(secret, TextInputOrigin.SELECTION)
        assertFalse(preview.toString().contains(secret))
        state.choose(preview, json)
        val input = state.approve(preview, json, owner)
        assertFalse(input.toString().contains(secret))
        state.invalidate()
        try { input.read(owner, json); fail("Expected revocation") }
        catch (error: TextInputRejected) { assertFalse(error.toString().contains(secret)) }
    }
}
