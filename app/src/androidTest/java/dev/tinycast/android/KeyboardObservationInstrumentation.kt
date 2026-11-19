package dev.tinycast.android

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File

/** Self-targeted instrumentation. The shell owns a fresh directory per trial and signals observer attachment. */
class KeyboardObservationInstrumentation : Instrumentation() {
    private lateinit var options: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = requireNotNull(arguments)
        start()
    }

    override fun onStart() {
        var activity: KeyboardObservationActivity? = null
        val result = Bundle()
        val code = try {
            val session = requireNotNull(options.getString("session")).also {
                require(it.matches(Regex("[0-9a-f]{32}")))
            }
            val folder = File(targetContext.cacheDir, "keyboard-observation-$session")
            check(folder.isDirectory && folder.list()?.isEmpty() == true)
            val fixture = options.getString("fixture").also { require(it in setOf("short", "long")) }
            val flags = when (options.getString("observer")) {
                "suppress" -> 0
                "preserve" -> UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
                else -> error("Unknown observer")
            }
            val intent = Intent(targetContext, KeyboardObservationActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture", fixture)
            val receiver = startActivitySync(intent) as KeyboardObservationActivity
            activity = receiver
            fun sample(): JSONObject {
                lateinit var data: JSONObject
                runOnMainSync { data = receiver.sample() }
                return data
            }
            await { sample().getBoolean("focused") }
            check(sample().getInt("length") == 0)
            signal(folder, "ready")
            await { File(folder, "attach").isFile }
            val report = JSONObject().put("before_observer", sample())
            if (options.getString("control") == "editor-loss") runOnMainSync { receiver.removeLastCharacterForControl() }
            val automation = getUiAutomation(flags)
            val root = requireNotNull(automation.rootInActiveWindow)
            check(root.packageName == targetContext.packageName && sample().getBoolean("focused"))
            dumpFixtureTree(root, File(folder, "hierarchy.xml"))
            report.put("after_observer", sample())
            signal(folder, "attached")
            await { File(folder, "finish").isFile }
            report.put("final", sample()).put("observer_flags", flags).put("session", session)
            File(folder, "record.json").writeText(report.toString())
            Activity.RESULT_OK
        } catch (failure: Exception) {
            result.putString("error", failure.toString())
            Activity.RESULT_CANCELED
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
        }
        finish(code, result)
    }

    private fun await(ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (!ready()) {
            check(SystemClock.uptimeMillis() < deadline) { "Fixture protocol timed out" }
            SystemClock.sleep(40)
        }
    }

    private fun signal(folder: File, name: String) {
        check(File(folder, name).createNewFile())
    }

    private fun dumpFixtureTree(root: AccessibilityNodeInfo, file: File) {
        file.outputStream().use { out ->
            val xml = Xml.newSerializer().apply { setOutput(out, "UTF-8"); startDocument("UTF-8", true) }
            var nodes = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                check(++nodes <= 64 && depth <= 20 && node.packageName == targetContext.packageName)
                val bounds = Rect().also(node::getBoundsInScreen)
                xml.startTag(null, "node")
                xml.attribute(null, "class", node.className.toString())
                xml.attribute(null, "bounds", bounds.toShortString())
                xml.attribute(null, "editable", node.isEditable.toString())
                // The test APK owns the entire captured window. Only publish recognized fixture text.
                val text = node.text?.toString().orEmpty()
                val known = "qwerty".startsWith(text) || "menuKeyboardProbe271828".startsWith(text) ||
                    text == "qwerrty" || text == "Keyboard delivery test"
                xml.attribute(null, "text", if (known) text else "outside-fixture")
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
                xml.endTag(null, "node")
            }
            visit(root, 0)
            xml.endDocument()
        }
    }
}
