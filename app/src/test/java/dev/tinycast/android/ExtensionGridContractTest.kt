package dev.tinycast.android

import dev.tinycast.android.extensions.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionGridContractTest {
    private val items = listOf(GridIdentity("a-key", "a"), GridIdentity("b-key", "b"), GridIdentity("c-key", "c"))

    @Test fun initialRemoteRequestWinsOverTheAutomaticFallback() {
        assertEquals(GridSelectionChoice("c-key", null), gridSelection(items, null, "c", null, null))
    }
    @Test fun changedRemoteRequestWinsOverAnExistingSelection() {
        assertEquals(GridSelectionChoice("b-key", null), gridSelection(items, "c-key", "b", "c", null))
    }
    @Test fun anAppliedRequestDoesNotUndoLocalNavigation() {
        assertEquals(GridSelectionChoice("a-key", null), gridSelection(items, "a-key", "c", "c", null))
    }
    @Test fun missingRequestRemainsPendingUntilItsItemArrives() {
        val empty = gridSelection(emptyList(), null, "c", null, null)
        assertEquals(GridSelectionChoice(null, "c"), empty)
        val partial = gridSelection(items.take(1), empty.key, "c", "c", empty.pendingId)
        assertEquals(GridSelectionChoice("a-key", "c"), partial)
        assertEquals(GridSelectionChoice("c-key", null), gridSelection(items, partial.key, "c", "c", partial.pendingId))
    }
    @Test fun aNewOrRemovedRequestReplacesThePendingOne() {
        assertEquals(GridSelectionChoice("b-key", null), gridSelection(items, "a-key", "b", "missing", "missing"))
        assertEquals(GridSelectionChoice("a-key", null), gridSelection(items, "a-key", null, "missing", "missing"))
    }
    @Test fun aRemovedLocalItemFallsBackWithoutResurrectingAnAppliedRequest() {
        assertEquals(GridSelectionChoice("b-key", null), gridSelection(items.drop(1), "a-key", "c", "c", null))
    }
    @Test fun untitledSectionsHaveAFullSpanBoundaryAfterTheFirstSection() {
        assertEquals(listOf(GridSlot.Cell(0), GridSlot.Boundary(1, false), GridSlot.Cell(1), GridSlot.Cell(2)),
            gridSlots(listOf(1,2), listOf(false,false)))
        assertEquals(1, gridMove(0, GridMove.DOWN, listOf(1,2), 3))
        assertEquals(0, gridMove(2, GridMove.UP, listOf(1,2), 3))
    }
    @Test fun emptySectionsDoNotAddSlotsAndTitledSectionsKeepOneHeading() {
        assertEquals(listOf(GridSlot.Boundary(1,true), GridSlot.Cell(0), GridSlot.Cell(1),
            GridSlot.Boundary(3,false), GridSlot.Cell(2)), gridSlots(listOf(0,2,0,1), listOf(true,true,false,false)))
    }
}
