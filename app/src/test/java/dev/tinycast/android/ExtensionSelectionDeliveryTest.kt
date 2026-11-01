package dev.tinycast.android

import dev.tinycast.android.extensions.ExtensionSelectionDelivery
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionSelectionDeliveryTest {
    @Test fun eventAndRenderDeliverEachSelectionOnlyOnceBeforeAction() {
        val delivery=ExtensionSelectionDelivery();val events=mutableListOf<String>()
        delivery.deliver("list","Alpha"){events+="Alpha"}
        delivery.deliver("list","Bravo"){events+="Bravo"}
        events+="action"
        delivery.deliver("list","Bravo"){events+="duplicate"}
        assertEquals(listOf("Alpha","Bravo","action"),events)
    }
    @Test fun initialEmptySelectionAndDifferentRootsStillNotify() {
        val delivery=ExtensionSelectionDelivery();val events=mutableListOf<String>()
        delivery.deliver("first",null){events+="first empty"}
        delivery.deliver("first",null){events+="duplicate"}
        delivery.deliver("second",null){events+="second empty"}
        assertEquals(listOf("first empty","second empty"),events)
    }
}
