package dev.tinycast.android

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class CalculatorActionsTest {
    private fun selection(query: String): CalculatorSelection {
        val calculation = Calculator.evaluateSearch(query)!!
        return CalculatorSelection(query, "All", 0, 4, 8, "actions",
            Command("result", calculation.result, calculation.expression, Kind.CALC, "calculator", payload=calculation.result))
    }
    private fun snapshot(query: String) = CalculatorActionSnapshot.capture(selection(query))!!

    @Test fun fullCalculationUsesTheDisplayedMultiplicationAndStripsOnlyTheModePrefix() {
        for (query in listOf("12*3", "12x3", "12×3", "12X3", "= 12*3", "  = 12x3  ")) {
            assertEquals(query, "12 × 3 = 36", snapshot(query).calculation)
            assertEquals("36", snapshot(query).answer)
        }
    }
    @Test fun functionsAndUnitsRetainTheirExpressionAndAnswer() {
        assertEquals("sqrt(81)+log(1000) = 12", snapshot("= sqrt(81)+log(1000)").calculation)
        assertEquals("1 m to in = 39.3700787402 in", snapshot("1 m to in").calculation)
        assertEquals("100 c to f = 212 f", snapshot("= 100 c to f").calculation)
    }
    @Test fun scalarChainingKeepsExplicitIntentAndAllowsTheNextOperation() {
        val chain = snapshot("12x3").chainQuery!!
        assertEquals("= 36", chain)
        assertEquals("36", Calculator.evaluateSearch(chain)?.result)
        assertEquals("144", Calculator.evaluateSearch(chain + "*4")?.result)
        assertNull(Calculator.evaluateSearch("36"))
    }
    @Test fun quantitiesChainWithoutDiscardingTheirUnit() {
        for (query in listOf("1 m to in", "100 c to f", "1024 mib to gib", "1.5 h to min", "1 kg to lb")) {
            val owner = snapshot(query)
            assertEquals(query, "= " + owner.answer, owner.chainQuery)
            assertEquals(query, owner.answer, Calculator.evaluateSearch(owner.chainQuery!!)?.result)
            assertNull(Calculator.evaluateSearch(owner.answer))
        }
        // Chaining deliberately uses the rounded displayed answer, rather than
        // silently retrieving higher precision from the preceding expression.
        assertEquals("100.0000000001 cm", Calculator.evaluateSearch(snapshot("1 m to in").chainQuery!! + " to cm")?.result)
        assertEquals("100 c", Calculator.evaluateSearch(snapshot("100 c to f").chainQuery!! + " to c")?.result)
    }
    @Test fun explicitQuantityLiteralsAcceptOnlyKnownUnitsAndFiniteValues() {
        for (query in listOf("= 1 cm", "= 1\tcm", "= 1  cm", "= -3 c")) assertNotNull(query, Calculator.evaluateSearch(query))
        for (query in listOf("1 cm", "1\tcm", "= 1 px", "= 1 unknown", "= 1 cm extra", "= 1cm", "= 1 m + 2 m")) {
            assertNull(query, Calculator.evaluateSearch(query))
        }
    }
    @Test fun answersBeyondTheParserLimitRemainCopyableWithoutAnUnusableChain() {
        val owner = snapshot("10^308")
        assertEquals(309, owner.answer.length)
        assertNull(owner.chainQuery)
        assertEquals("10^308 = " + owner.answer, owner.calculation)
        assertNotNull(snapshot("10^255").chainQuery)
    }
    @Test fun copyingUsesTheSelectedPayloadWithoutRoundingOrReevaluatingIt() {
        val original = selection("12x3")
        val owner = CalculatorActionSnapshot.capture(original.copy(command=original.command!!.copy(payload="36.0000000000")))!!
        assertEquals("36.0000000000", owner.answer)
        assertEquals("12 × 3 = 36.0000000000", owner.calculation)
        assertNull(owner.chainQuery)
    }
    @Test fun sharedResultIdsCannotAuthorizeChangedQueryValueSelectionCategoryOrRoute() {
        val original = selection("12x3")
        val owner = CalculatorActionSnapshot.capture(original)!!
        assertTrue(owner.isCurrent(original))
        for (changed in listOf(original.copy(query="6x6"), original.copy(category="Pinned"), original.copy(index=1),
            original.copy(revision=5), original.copy(focusEpoch=9), original.copy(route=null),
            original.copy(command=original.command!!.copy(payload="42")), original.copy(command=null))) {
            assertFalse(changed.toString(), owner.isCurrent(changed))
        }
    }
    @Test fun ordinaryCommandsAndWebResultsDoNotOfferCalculatorActions() {
        val original = selection("12x3")
        for (command in listOf(Command("web", "Search the web", "36"), Command("calculator", "Calculator", ""),
            original.command!!.copy(payload=""))) assertNull(CalculatorActionSnapshot.capture(original.copy(command=command)))
    }
    @Test fun acceleratorsConsumeHeldRepeatsAndReleaseAfterTheResultChanges() {
        for (key in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            val engine = ShortcutEngine()
            val down = ShortcutEngine.Input(true, key, ShortcutRules.CTRL)
            assertEquals(CalculatorAction.PUT_ANSWER.name, engine.handle(down, CalculatorAccelerators.bindings).target)
            repeat(12) { assertEquals(ShortcutEngine.Result(true), engine.handle(down.copy(repeat=it+1), emptyMap(), paused=true)) }
            assertEquals(ShortcutEngine.Result(true), engine.handle(down.copy(down=false, modifiers=0), emptyMap(), paused=true))
            assertFalse(engine.handle(down.copy(repeat=1), CalculatorAccelerators.bindings).consumed)
            assertEquals(CalculatorAction.COPY_CALCULATION.name,
                engine.handle(down.copy(modifiers=ShortcutRules.CTRL or ShortcutRules.SHIFT), CalculatorAccelerators.bindings).target)
        }
    }
    @Test fun modifiedEnterDefaultsDoNotReserveAnExistingConfigurableBinding() {
        assertNull(ShortcutRules.error(KeyChord(KeyEvent.KEYCODE_ENTER, ShortcutRules.CTRL or ShortcutRules.SHIFT)))
        val engine = ShortcutEngine()
        val chord = KeyChord(KeyEvent.KEYCODE_ENTER, ShortcutRules.CTRL or ShortcutRules.SHIFT)
        assertEquals("calculator", engine.handle(ShortcutEngine.Input(true, chord.keyCode, chord.modifiers), mapOf(chord to "calculator")).target)
    }
}
