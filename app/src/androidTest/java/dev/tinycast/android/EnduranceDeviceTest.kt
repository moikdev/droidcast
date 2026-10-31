package dev.tinycast.android

import android.app.UiAutomation
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EnduranceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val app = "dev.tinycast.android"
    private val events = JSONArray()
    private val output get() = File(context.filesDir, "extended-test").apply { mkdirs() }
    private val started = SystemClock.elapsedRealtime()
    private var rounds = 0

    private fun event(name: String, detail: String = "") {
        val value = JSONObject().put("seconds", (SystemClock.elapsedRealtime() - started) / 1000.0)
            .put("round", rounds).put("event", name).put("detail", detail)
        events.put(value)
        android.util.Log.i("TinycastSoak", value.toString())
        File(output, "progress.json").writeText(JSONObject().put("events", events).toString(2))
    }

    private fun field(): UiObject2 = device.wait(Until.findObject(By.pkg(app).clazz("android.widget.EditText")), 7000)
        ?: error("Palette search field did not appear")

    private fun summon() {
        device.executeShellCommand("am start -W -n $app/.MainActivity")
        assertEquals("A new summon must start with an empty query", "", field().text.orEmpty())
    }

    private fun search(query: String, expected: String) {
        field().text = query
        assertTrue("Missing result: $expected", device.wait(Until.hasObject(By.pkg(app).text(expected)), 5000))
    }

    private fun key(code: Int, ctrl: Boolean = false) {
        device.pressKeyCode(code, if (ctrl) KeyEvent.META_CTRL_ON else 0)
    }

    private fun outsideApp(expected: String) {
        assertTrue("Destination did not open: $expected", device.wait(Until.hasObject(By.pkg(expected)), 7000))
        assertTrue("Palette did not dismiss after launching destination", device.wait(Until.gone(By.pkg(app).clazz("android.widget.EditText")), 5000))
    }

    private fun snapshot(name: String) {
        device.takeScreenshot(File(output, "$name.png"))
        device.dumpWindowHierarchy(File(output, "$name.xml"))
    }

    private fun selectFile(filename: String) {
        summon()
        search("Open a document", "Open a document")
        key(KeyEvent.KEYCODE_ENTER)
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 7000))
        device.waitForIdle()
        if (!device.hasObject(By.text("Files in Downloads"))) {
            device.wait(Until.findObject(By.desc("Show roots")), 5000)!!.click()
            device.waitForIdle()
            device.wait(Until.findObject(By.res("android:id/title").text("Downloads")), 5000)!!.click()
            assertTrue(device.wait(Until.gone(By.text("Open from")), 5000))
            device.waitForIdle()
        }
        val file = device.wait(Until.findObject(By.text(filename)), 7000)
            ?: error("Push the fixture to Download/$filename")
        file.click()
        field()
        assertTrue("Document grant was not persisted", context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.lastPathSegment.orEmpty().endsWith(filename) })
        event("file-selected-through-picker", filename)
    }

    @Test fun extendedSettingsFilesKeyboardAndRotation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with -e runSoak true", arguments.getString("runSoak") == "true")
        val duration = (arguments.getString("durationSeconds")?.toLongOrNull() ?: 300L).coerceIn(60, 1800) * 1000
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        event("start", "durationMs=$duration")
        var success = false
        try {
            device.executeShellCommand("am start -W -a android.settings.SETTINGS")
            selectFile("tinycast-test-image.png")
            selectFile("tinycast-soak-document.pdf")
            selectFile("tinycast-soak-document.txt")
            search("tinycast-soak-document.txt", "tinycast-soak-document.txt")
            key(KeyEvent.KEYCODE_ENTER)
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Open with")), 5000))
            device.findObject(By.text("HTML Viewer"))?.click()
            device.waitForIdle()
            device.wait(Until.findObject(By.text("Just once")), 5000)!!.click()
            outsideApp("com.android.htmlviewer")
            assertTrue(device.wait(Until.hasObject(By.text("tinycast-soak-document.txt")), 7000))
            val textGrant = context.contentResolver.persistedUriPermissions.first { it.uri.lastPathSegment.orEmpty().endsWith("tinycast-soak-document.txt") }
            assertEquals("Tinycast extended text file test.\n", context.contentResolver.openInputStream(textGrant.uri)!!.bufferedReader().use { it.readText() })
            snapshot("text-file-viewer")
            event("plain-text-opened-through-system-chooser")

            while (SystemClock.elapsedRealtime() - started < duration || rounds < 20) {
                rounds++
                summon()
                val answer = ((rounds + 7) * 8).toString()
                search("(${rounds + 7}) * 8", answer)
                key(KeyEvent.KEYCODE_ENTER)
                instrumentation.runOnMainSync {
                    assertEquals(answer, context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString())
                }
                key(KeyEvent.KEYCODE_K, true)
                assertTrue(device.wait(Until.hasObject(By.res("inline-menu-search")), 5000))
                key(KeyEvent.KEYCODE_ESCAPE)
                assertTrue(device.wait(Until.gone(By.res("inline-menu-search")), 5000))
                event("calculator-copy-and-actions", answer)

                if (rounds % 5 == 0) {
                    val query = field().text
                    device.setOrientationLeft()
                    assertEquals("Query lost on rotation", query, field().text)
                    snapshot("portrait-round-$rounds")
                    device.setOrientationNatural()
                    assertEquals("Query lost returning to landscape", query, field().text)
                    event("rotation-preserved-query")
                }

                val appSearch = rounds % 2 == 0
                search(if (appSearch) "@settings" else "Android settings", if (appSearch) "Settings" else "Android settings")
                key(KeyEvent.KEYCODE_ENTER)
                outsideApp("com.android.settings")
                if (rounds == 1) snapshot("settings-opened-from-palette")
                event("settings-opened-from-palette", if (appSearch) "installed app search" else "built-in command")

                summon()
                val pdf = rounds % 3 == 0
                val filename = if (pdf) "tinycast-soak-document.pdf" else "tinycast-test-image.png"
                search(filename, filename)
                key(KeyEvent.KEYCODE_ENTER)
                outsideApp(if (pdf) "com.google.android.apps.docs" else "com.google.android.apps.photos")
                if (rounds in listOf(1, 3, 10)) snapshot("file-viewer-round-$rounds")
                event("recent-file-opened", filename)

                if (rounds % 5 == 0) {
                    val memory = device.executeShellCommand("dumpsys meminfo $app")
                    File(output, "memory-round-$rounds.txt").writeText(memory)
                    val tasks = device.executeShellCommand("dumpsys activity activities")
                    File(output, "tasks-round-$rounds.txt").writeText(tasks)
                    event("resource-sample")
                }
            }
            success = true
        } catch (failure: Throwable) {
            event("failure", failure.toString())
            snapshot("failure")
            throw failure
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            File(output, "summary.json").writeText(JSONObject().put("passed", success).put("rounds", rounds)
                .put("elapsedSeconds", (SystemClock.elapsedRealtime() - started) / 1000.0).put("events", events).toString(2))
            event("finish", "passed=$success")
        }
    }
}
