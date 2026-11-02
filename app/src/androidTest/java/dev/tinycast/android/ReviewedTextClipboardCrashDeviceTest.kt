package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Intentional process death; the host must recover without any JUnit @After. */
@RunWith(AndroidJUnit4::class)
class ReviewedTextClipboardCrashDeviceTest {
    @Test fun terminateAfterClipboardMutation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals("Tinycast_Tablet_API_36", args.getString("dedicatedAvd"))
        assertEquals("true", args.getString("reviewedTextFixture"))
        val owner = args.getString("expectedTextInputLeaseOwner")!!
        assertEquals(owner, args.getString("expectedClipboardRecoveryOwner"))
        val lease = JSONObject(File(context.filesDir, "reviewed-text-fixture-lease/owners.json").readText())
        assertEquals(owner, lease.getString("owner_nonce"))
        assertEquals("reviewed-text-device-test-v1", lease.getString("fixture_kind"))
        assertEquals(context.applicationInfo.uid, Process.myUid())
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(UiDevice.getInstance(instrumentation).wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Owned crash probe", "Owned clipboard mutation before instrumentation death"))
            assertTrue("Owned clipboard mutation must be visible without logging its previous contents",
                clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "Owned clipboard mutation before instrumentation death")
        }
        val evidence = File(context.filesDir, "reviewed-text-evidence").apply { assertTrue(mkdir()) }
        FileOutputStream(File(evidence, "clipboard-crash-probe.json")).use {
            it.write(JSONObject().put("owner_nonce", owner).put("clipboard_mutated", true)
                .put("instrumentation_pid", Process.myPid()).put("scope", "Intentional own-UID death; no JUnit cleanup")
                .toString().toByteArray())
            it.fd.sync()
        }
        Process.killProcess(Process.myPid())
        throw AssertionError("The owned instrumentation process must terminate")
    }
}
