package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Parcel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Private clipboard snapshot for the guarded hardware helper; never a production entry. */
@RunWith(AndroidJUnit4::class)
class ModifierHardwareClipboardTest {
    @Test fun preserveClipboardForOwnedHardwareRun() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("clipboardMode")
        Assume.assumeTrue("Only the explicit hardware helper invokes this fixture", mode in setOf("save", "restore"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        assertEquals(args.getString("dedicatedAvd"), device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
        assertTrue(android.os.Process.myUid() >= 10000)
        val file = File(context.cacheDir, "modifier-hardware-clipboard.private")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            var focused = false
            while (!focused && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                if (!focused) android.os.SystemClock.sleep(30)
            }
            assertTrue("Clipboard access requires the actual focused app", focused)
            scenario.onActivity {
                val clipboard = it.getSystemService(ClipboardManager::class.java)
                if (mode == "save") {
                    assertFalse("Never overwrite a pending private clipboard snapshot", file.exists())
                    val clip = clipboard.primaryClip
                    if (clip == null) file.writeBytes(byteArrayOf(0)) else {
                        val parcel = Parcel.obtain()
                        try { clip.writeToParcel(parcel, 0); file.writeBytes(byteArrayOf(1) + parcel.marshall()) }
                        finally { parcel.recycle() }
                    }
                } else {
                    val data = file.readBytes()
                    if (data.first() == 0.toByte()) {
                        if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.resetClipboardForTest()
                        else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    } else {
                        val parcel = Parcel.obtain()
                        try {
                            parcel.unmarshall(data, 1, data.size - 1); parcel.setDataPosition(0)
                            clipboard.setPrimaryClip(ClipData.CREATOR.createFromParcel(parcel))
                        } finally { parcel.recycle() }
                    }
                    assertTrue(file.delete())
                }
            }
        }
    }
}
