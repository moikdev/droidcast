package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Verify the real legacy reset API and exact Unicode delivery on the owned test VM. */
@RunWith(AndroidJUnit4::class)
class ClipboardApiDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun publicClipboardResetAndExactUnicodeCopyWorkOnTheRuntime() {
        compose.activityRule.scenario.onActivity { activity ->
            val manager=activity.getSystemService(ClipboardManager::class.java)
            val original=manager.primaryClip
            try {
                manager.resetClipboardForTest()
                if(android.os.Build.VERSION.SDK_INT>=28) {
                    assertNull("Modern clearing preserves absence",manager.primaryClip)
                    assertFalse(manager.hasPrimaryClip())
                } else assertEquals("Legacy public API uses empty text", "", manager.primaryClip?.getItemAt(0)?.text?.toString())
                val value="Droidcast API clipboard 👨‍👩‍👧‍👦 271828"
                activity.copyText(value)
                assertEquals(value,manager.primaryClip?.getItemAt(0)?.text?.toString())
                manager.resetClipboardForTest()
                if(android.os.Build.VERSION.SDK_INT>=28) assertNull(manager.primaryClip)
                else assertEquals("",manager.primaryClip?.getItemAt(0)?.text?.toString())
            } finally { original?.let(manager::setPrimaryClip) ?: manager.resetClipboardForTest() }
        }
    }
}
