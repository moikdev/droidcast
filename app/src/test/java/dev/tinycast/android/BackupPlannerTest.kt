package dev.tinycast.android

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class BackupPlannerTest {
    private val note = SavedItem("notes:one", "Travel", "Tablet workflow")
    private val categories = BackupCategory.entries.toSet()
    private fun plan(old: BackupData, incoming: BackupData, policy: RestorePolicy = RestorePolicy.MERGE, selected: Set<BackupCategory> = categories, defaults: Map<String, String> = emptyMap()) = BackupPlanner.plan(old, BackupDocument(incoming, categories), selected, policy, defaults)

    @Test fun rankingFeedbackDescribesTheResultForMergeAndEmptyReplacement() {
        val selected = setOf(BackupCategory.LEARNING)
        val old = BackupData(usage = mapOf("calculator" to 3))
        val incoming = BackupData(usage = mapOf("calculator" to 2, "emoji" to 1))
        val merge = plan(old, incoming, selected = selected)
        assertEquals("After restore · 2 usage ranking entries", merge.reviewSummary())
        assertEquals("Backup restored · Usage ranking", merge.completionMessage())
        val cleared = plan(old, BackupData(), RestorePolicy.REPLACE, selected)
        assertEquals("After restore · 0 usage ranking entries", cleared.reviewSummary())
    }

    @Test fun mixedFeedbackSeparatesCollectionChangesAndSelectedSettings() {
        val incoming = BackupData(notes = listOf(note), pins = setOf(note.id))
        val mixed = plan(BackupData(), incoming, selected = setOf(BackupCategory.NOTES, BackupCategory.PREFERENCES))
        assertEquals("Collection items · 1 to apply · 0 duplicates kept · 0 to replace\nAfter restore · 1 pin, 0 alias overrides", mixed.reviewSummary())
        assertEquals("Backup restored · 2 categories", mixed.completionMessage())
    }

    @Test fun mergeIsIdempotentAndKeepsConflictingIdentity() {
        val incoming = BackupData(notes = listOf(note, SavedItem("notes:two", "Other", "New")))
        val first = plan(BackupData(notes = listOf(note.copy(body = "Keep edited text"))), incoming)
        assertEquals("Keep edited text", first.data.notes[0].body)
        assertEquals(1, first.added); assertEquals(1, first.skipped)
        assertEquals(first.data, plan(first.data, incoming).data)
    }
    @Test fun contentDuplicatesRemapPinsAliasesAndLearning() {
        val old = BackupData(notes = listOf(note))
        val incoming = BackupData(notes = listOf(note.copy(id = "notes:copy")), pins = setOf("notes:copy"), aliases = mapOf("notes:copy" to "travel"), usage = mapOf("notes:copy" to 7))
        val result = plan(old, incoming).data
        assertEquals(listOf(note), result.notes)
        assertEquals(setOf(note.id), result.pins)
        assertEquals(mapOf(note.id to "travel"), result.aliases)
        assertEquals(mapOf(note.id to 7), result.usage)
    }
    @Test fun replacementTouchesOnlySelectedCategories() {
        val old = BackupData(notes = listOf(note), clips = listOf(SavedItem("clips:one", "Keep", "clip")), pins = setOf("calculator"))
        val result = plan(old, BackupData(), RestorePolicy.REPLACE, setOf(BackupCategory.NOTES))
        assertTrue(result.data.notes.isEmpty()); assertEquals(old.clips, result.data.clips); assertEquals(old.pins, result.data.pins)
        assertEquals(1, result.removed)
    }
    @Test fun preferencesMergeKeepsExistingOverridesAndRank() {
        val result = plan(BackupData(aliases = mapOf("calculator" to "math"), usage = mapOf("calculator" to 8)), BackupData(aliases = mapOf("calculator" to "count"), usage = mapOf("calculator" to 2)))
        assertEquals("math", result.data.aliases["calculator"]); assertEquals(8, result.data.usage["calculator"])
    }
    @Test fun conflictingEffectiveAliasesRejectTheWholePlan() {
        val incoming = BackupData(links = listOf(SavedItem("links:one", "Docs", "https://example.com", "calc")))
        assertThrows(IllegalArgumentException::class.java) { plan(BackupData(), incoming, defaults = mapOf("calculator" to "calc")) }
    }
    @Test fun clipMergeCannotSilentlyTrimExistingData() {
        val clips = (1..100).map { SavedItem("clips:$it", "$it", "$it") }
        assertThrows(IllegalArgumentException::class.java) { plan(BackupData(clips = clips), BackupData(clips = listOf(SavedItem("clips:new", "New", "text")))) }
    }
    @Test fun noOrUnavailableCategoryCannotBeApplied() {
        assertThrows(IllegalArgumentException::class.java) { plan(BackupData(), BackupData(), selected = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { BackupPlanner.plan(BackupData(), BackupDocument(BackupData(), setOf(BackupCategory.NOTES)), categories, RestorePolicy.MERGE, emptyMap()) }
    }
    @Test fun remappedAliasesCannotSilentlyDiscardConflictingValues() {
        val incoming = BackupData(notes = listOf(note, note.copy(id = "notes:copy")), aliases = mapOf(note.id to "travel", "notes:copy" to "trip"))
        assertThrows(IllegalArgumentException::class.java) { plan(BackupData(), incoming) }
        assertEquals(listOf(note), plan(BackupData(), incoming, selected = setOf(BackupCategory.NOTES)).data.notes)
    }
    @Test fun remappedRankingUsesMaximumRatherThanInputOrder() {
        val incoming = BackupData(notes = listOf(note, note.copy(id = "notes:copy")), usage = linkedMapOf(note.id to 8, "notes:copy" to 2))
        assertEquals(mapOf(note.id to 8), plan(BackupData(), incoming).data.usage)
    }
    @Test fun identityWinsWhenAnEarlierItemAlsoMatchesIncomingContent() {
        val duplicate = note.copy(id = "notes:duplicate")
        val editedIdentity = note.copy(body = "Edited on this device")
        val current = BackupData(notes = listOf(duplicate, editedIdentity))
        val incoming = BackupData(notes = listOf(note), pins = setOf(note.id), aliases = mapOf(note.id to "trip"), usage = mapOf(note.id to 5))
        val result = plan(current, incoming)
        assertEquals(current.notes, result.data.notes)
        assertEquals(setOf(note.id), result.data.pins)
        assertEquals(mapOf(note.id to "trip"), result.data.aliases)
        assertEquals(mapOf(note.id to 5), result.data.usage)
    }
    @Test fun oldBackupOrUnselectedShortcutCategoryKeepsCurrentBindings() {
        val current = BackupData(shortcuts = ShortcutConfiguration(mapOf(ShortcutRules.PALETTE to KeyChord(KeyEvent.KEYCODE_SPACE, ShortcutRules.CTRL or ShortcutRules.ALT))))
        val document = BackupDocument(BackupData(notes = listOf(note)), setOf(BackupCategory.NOTES))
        val result = BackupPlanner.plan(current, document, document.categories, RestorePolicy.REPLACE, emptyMap())
        assertEquals(current.shortcuts, result.data.shortcuts)
    }
    @Test fun deduplicatedContentRemapsItsKeyboardBinding() {
        val chord = KeyChord(KeyEvent.KEYCODE_T, ShortcutRules.CTRL or ShortcutRules.ALT)
        val incoming = BackupData(notes = listOf(note.copy(id = "notes:copy")), shortcuts = ShortcutConfiguration(mapOf("notes:copy" to chord)))
        val result = plan(BackupData(notes = listOf(note)), incoming)
        assertEquals(chord, result.data.shortcuts.bindings[note.id])
        assertFalse("notes:copy" in result.data.shortcuts.bindings)
    }
    @Test fun preservedDocumentBindingsBlockCollisionsForMergeAndReplace() {
        val chord = KeyChord(KeyEvent.KEYCODE_F, ShortcutRules.CTRL or ShortcutRules.ALT)
        val incoming = BackupDocument(BackupData(shortcuts = ShortcutConfiguration(mapOf("browse-files" to chord))), setOf(BackupCategory.SHORTCUTS))
        RestorePolicy.entries.forEach { policy ->
            assertThrows(IllegalArgumentException::class.java) { BackupPlanner.plan(BackupData(), incoming, incoming.categories, policy, emptyMap(), mapOf("files:local" to chord)) }
        }
    }
    @Test fun remappingCannotDiscardDistinctKeyboardBindings() {
        val incoming = BackupData(notes = listOf(note, note.copy(id = "notes:copy")), shortcuts = ShortcutConfiguration(mapOf(note.id to KeyChord(KeyEvent.KEYCODE_T, ShortcutRules.CTRL or ShortcutRules.ALT), "notes:copy" to KeyChord(KeyEvent.KEYCODE_Y, ShortcutRules.CTRL or ShortcutRules.ALT))))
        assertThrows(IllegalArgumentException::class.java) { plan(BackupData(), incoming) }
    }
    @Test fun preservedDocumentModifierSidesBlockOverlapsButAllowDistinctSides() {
        val left = ShortcutRules.doubleTap(ShortcutRules.CTRL, ModifierSide.LEFT)
        val right = left.copy(side = ModifierSide.RIGHT)
        val either = left.copy(side = ModifierSide.ANY)
        for (policy in RestorePolicy.entries) {
            for (incoming in listOf(left, either)) {
                val document = BackupDocument(BackupData(shortcuts = ShortcutConfiguration(mapOf("calculator" to incoming))), setOf(BackupCategory.SHORTCUTS))
                assertThrows(IllegalArgumentException::class.java) {
                    BackupPlanner.plan(BackupData(), document, document.categories, policy, emptyMap(), mapOf("files:local" to left))
                }
            }
            val document = BackupDocument(BackupData(shortcuts = ShortcutConfiguration(mapOf("calculator" to right))), setOf(BackupCategory.SHORTCUTS))
            assertEquals(right, BackupPlanner.plan(BackupData(), document, document.categories, policy, emptyMap(), mapOf("files:local" to left)).data.shortcuts.bindings["calculator"])
        }
    }

}
