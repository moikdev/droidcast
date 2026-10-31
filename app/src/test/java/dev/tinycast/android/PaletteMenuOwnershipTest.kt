package dev.tinycast.android

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class PaletteMenuOwnershipTest {
    @Test fun outgoingOwnerCannotCloseOrDetachReplacementMenu() {
        var boundaries = 0
        val state = PaletteMenuOwnership { boundaries++ }
        val old = Any(); val replacement = Any()
        state.attach(old); assertTrue(state.update(old,true,true))
        state.attach(replacement); assertFalse(state.open)
        assertTrue(state.update(replacement,true,true))
        assertFalse(state.update(old,false,true)); state.detach(old)
        assertTrue(state.open); assertEquals(2,boundaries)
        assertTrue(state.update(replacement,false,true)); assertEquals(3,boundaries)
    }
    @Test fun inactiveCleanupDoesNotCancelKeysInTheNewWindow() {
        var epoch = 0L
        val state = PaletteMenuOwnership { epoch++ }
        val owner = Any(); state.attach(owner); state.update(owner,true,true)
        val observer = ModifierShortcutObserver()
        val binding = ShortcutRules.doubleTap(ShortcutRules.CTRL)
        val config = mapOf(binding to ShortcutRules.PALETTE)
        fun key(down: Boolean, time: Long, press: Long = time) = ShortcutEngine.Input(down,KeyEvent.KEYCODE_CTRL_LEFT,
            if(down) ShortcutRules.CTRL else 0,eventTime=time,downTime=press,receivedAt=time)
        observer.handle(key(true,100),config,recordingEpoch=epoch)
        observer.handle(key(false,130,100),config,recordingEpoch=epoch)
        state.update(owner,false,false); assertFalse(state.open); assertEquals(1L,epoch)
        observer.handle(key(true,200),config,recordingEpoch=epoch)
        assertEquals(ShortcutRules.PALETTE,observer.handle(key(false,230,200),config,recordingEpoch=epoch)?.first)
    }
    @Test fun focusedMenuTransitionInvalidatesAnInProgressPairOnlyOnce() {
        var epoch = 0L
        val state = PaletteMenuOwnership { epoch++ }
        val owner = Any(); state.attach(owner)
        assertFalse(state.update(owner,false,true)); assertEquals(0L,epoch)
        assertTrue(state.update(owner,true,true)); assertEquals(1L,epoch)
        assertFalse(state.update(owner,true,true)); assertEquals(1L,epoch)
        assertTrue(state.update(owner,false,true)); assertEquals(2L,epoch)
    }
}
