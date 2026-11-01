package dev.tinycast.android

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Window
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, instrumented debug-build measurements; never input-to-photon claims. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class ReadinessDeviceTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        var valid = false
        while (!valid && SystemClock.uptimeMillis() < until) { main { valid = condition() }; if (!valid) SystemClock.sleep(10) }
        assertTrue(message, valid)
    }
    @After fun close() { if (::scenario.isInitialized) scenario.close() }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val output get() = File(if (activity.packageName.endsWith(".benchmark")) requireNotNull(activity.getExternalFilesDir(null)) else activity.filesDir, "readiness-evidence").apply { mkdirs() }
    private val samples = JSONArray()

    @Before fun ready() {
        org.junit.Assume.assumeTrue("Explicit readiness run required", InstrumentationRegistry.getArguments().getString("runReadiness") == "true")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        await("Catalog and focus ready") { !activity.store.loading && !activity.store.emoji.loading && !activity.store.folderSearch.loading && activity.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo()) != null }
        main { activity.store.reset() }
        instrumentation.waitForIdleSync()
    }

    private fun nodes(): List<SemanticsNode> {
        fun root(view: View): ViewRootForTest? = (view as? ViewRootForTest)
            ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
        val owner = requireNotNull(root(activity.window.decorView)).semanticsOwner
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return walk(owner.rootSemanticsNode)
    }
    private fun tagged(nodes: List<SemanticsNode>, tag: String) = nodes.firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private fun text(node: SemanticsNode?) = node?.config?.getOrNull(SemanticsProperties.EditableText)?.text
    private fun label(node: SemanticsNode?) = node?.config?.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    /** Observe a draw with expected semantics, then the hardware renderer's commit.
     * Includes semantics traversal overhead. A submitted frame is not display scanout.
     * No UiAutomator/Compose idle waits run inside the measured interval. */
    private fun measure(name: String, expected: (List<SemanticsNode>) -> Boolean, action: () -> Unit) {
        val latch = CountDownLatch(1)
        val elapsed = AtomicLong(-1)
        val drawChecks = AtomicLong(0)
        var started = 0L
        var registered = false
        var callback: Runnable? = null
        val decor = activity.window.decorView
        // Decor overlay draws after descendants, including Compose's deferred layout.
        // OnDrawListener runs before them and can observe the previous semantics.
        val listener = object : Drawable() {
          override fun draw(canvas: Canvas) {
            if (!registered) {
                drawChecks.incrementAndGet()
                if (expected(nodes())) {
                    registered = true
                    callback = Runnable { elapsed.set(System.nanoTime() - started); latch.countDown() }
                    decor.viewTreeObserver.registerFrameCommitCallback(callback!!)
                }
            }
          }
          override fun setAlpha(alpha: Int) = Unit
          override fun setColorFilter(colorFilter: ColorFilter?) = Unit
          @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSPARENT
        }
        try {
            instrumentation.runOnMainSync {
                check(decor.isHardwareAccelerated)
                listener.setBounds(0, 0, decor.width, decor.height)
                decor.overlay.add(listener)
                started = System.nanoTime()
                action()
            }
            val committed = latch.await(5, TimeUnit.SECONDS)
            if (!committed) main {
                val ns = nodes()
                File(output, "measurement-failure.json").writeText(JSONObject().put("case", name).put("registered", registered)
                    .put("drawChecks", drawChecks.get()).put("searchText", text(tagged(ns, "search")))
                    .put("modelQuery", activity.store.query).put("resultsTag", tagged(ns, "results") != null).toString(2))
            }
            assertTrue("$name did not commit expected content within 5 seconds", committed)
            samples.put(JSONObject().put("case", name).put("ms", elapsed.get() / 1_000_000.0).put("drawChecks", drawChecks.get()))
        } finally {
            instrumentation.runOnMainSync {
                decor.overlay.remove(listener)
                callback?.let { decor.viewTreeObserver.unregisterFrameCommitCallback(it) }
            }
        }
        instrumentation.waitForIdleSync()
    }
    private fun replace(value: String, emoji: Boolean = false) {
        val current = if (emoji) activity.store.emoji.query else activity.store.query
        val connection = requireNotNull(activity.window.decorView.findFocus()?.onCreateInputConnection(EditorInfo()))
        assertTrue(connection.beginBatchEdit())
        assertTrue(connection.setSelection(0, current.length))
        assertTrue(connection.commitText(value, 1))
        connection.endBatchEdit()
    }
    private fun search(value: String, emoji: Boolean = false, warmup: Boolean = false) {
        val tag = if (emoji) "emoji-search" else "search"
        await("Intended input field owns focus") { tagged(nodes(), tag)?.config?.getOrNull(SemanticsProperties.Focused) == true }
        measure((if (warmup) "warmup-" else "") + if (emoji) "emoji-query" else "launcher-query", { ns ->
            text(tagged(ns, tag)) == value && when {
                emoji -> label(tagged(ns, "emoji-count")) == "${activity.store.emoji.results().size} ${if (activity.store.emoji.results().size == 1) "result" else "results"} · offline" && activity.store.emoji.query == value
                value == "sqrt(144)" -> label(tagged(ns, "result-result")).contains("12") || ns.any { label(it) == "12" }
                else -> activity.store.results().firstOrNull()?.let { tagged(ns, "result-${it.id}") != null }
                    ?: ns.any { label(it) == "No matching commands" }
            }
        }) { replace(value, emoji) }
        main { assertEquals(value, text(tagged(nodes(), tag))) }
    }
    private fun record(name: String, extra: JSONObject = JSONObject()) {
        extra.put("samples", samples).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("buildCommit", activity.packageManager.getApplicationInfo(activity.packageName, android.content.pm.PackageManager.GET_META_DATA).metaData.getString("dev.droidcast.BUILD_COMMIT"))
            .put("debuggable", activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("width", device.displayWidth).put("height", device.displayHeight)
            .put("density", activity.resources.displayMetrics.density)
            .put("method", extra.optString("pixelMethod", "main-thread IME commit/key dispatch to hardware frame commit after expected semantics draw; includes traversal; excludes physical input and scanout"))
        File(output, "$name.json").writeText(extra.toString(2))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); device.waitForIdle()
        device.dumpWindowHierarchy(File(output, "$name.xml"))
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }

    @Test fun queryAndNavigationFrameCommitLatency() {
        val frames = JSONArray()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            frames.put(JSONObject().put("totalNs", metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                .put("firstDraw", metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME)).put("droppedReports", dropped))
        }
        main { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper())) }
        try {
            val queries = listOf("s", "se", "settings", "zz-no-readiness", "sqrt(144)", "")
            repeat(5) { search(queries[it], warmup = true) }
            var origin = androidx.compose.ui.geometry.Rect.Zero
            main { origin = requireNotNull(tagged(nodes(), "search")).boundsInWindow }
            repeat(10) { queries.forEach { query ->
                search(query)
                main { assertEquals("Search origin/size changed while typing", origin, requireNotNull(tagged(nodes(), "search")).boundsInWindow) }
            } }
            search("sqrt(144)"); capture("droidcast-calculator")
            main {
                val now = SystemClock.uptimeMillis()
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_K, 0, KeyEvent.META_CTRL_ON))
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_K, 0, KeyEvent.META_CTRL_ON))
            }
            await("Attached actions visible") { tagged(nodes(), "inline-menu-search") != null }
            capture("droidcast-actions")
            device.pressKeyCode(KeyEvent.KEYCODE_ESCAPE)
            await("Actions closed") { tagged(nodes(), "inline-menu-search") == null }
            main { activity.execute(Command("emoji", "Emoji", "")) }
            await("Emoji field owns focus") { tagged(nodes(), "emoji-search")?.config?.getOrNull(SemanticsProperties.Focused) == true }
            repeat(5) { search(listOf("f", "fa", "face", "zz-no-emoji", "")[it], emoji = true, warmup = true) }
            repeat(10) { listOf("f", "fa", "face", "zz-no-emoji", "").forEach { search(it, emoji = true) } }
            search("face", emoji = true)
            repeat(40) { index ->
                val before = activity.store.emoji.selected
                val next = if (index % 2 == 0) before + 1 else before - 1
                val id = activity.store.emoji.results()[next].id
                measure("emoji-navigation", { ns -> tagged(ns, "emoji-cell-$id")?.config?.getOrNull(SemanticsProperties.Selected) == true }) {
                    val now = SystemClock.uptimeMillis()
                    val key = if (index % 2 == 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                    activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
                    activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
                }
                assertEquals(next, activity.store.emoji.selected)
            }
            capture("droidcast-emoji")
        } finally {
            var frozenFrames = JSONArray()
            main {
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                frozenFrames = JSONArray(frames.toString())
            }
            record("latency", JSONObject().put("frames", frozenFrames))
        }
    }


    private fun pixels(rect: android.graphics.Rect): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(rect.width(), rect.height(), android.graphics.Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        android.view.PixelCopy.request(activity.window, rect, bitmap, { status -> result = status; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy callback", done.await(5, TimeUnit.SECONDS))
        assertEquals("PixelCopy succeeded", android.view.PixelCopy.SUCCESS, result)
        return bitmap
    }
    private fun hash(bitmap: android.graphics.Bitmap): String {
        val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return java.security.MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }
    private fun resultRegion(emoji: Boolean): android.graphics.Rect {
        var rect = android.graphics.Rect()
        main {
            val bounds = requireNotNull(tagged(nodes(), if (emoji) "emoji-palette" else "palette")).boundsInWindow
            val density = activity.resources.displayMetrics.density
            rect = android.graphics.Rect((bounds.left + 4*density).toInt(), (bounds.top + 56*density).toInt(),
                (bounds.right - 4*density).toInt(), (bounds.bottom - 52*density).toInt())
            assertTrue("Visible content region", rect.width() > 0 && rect.height() > 0)
        }
        return rect
    }
    private fun inputReady(value: String, emoji: Boolean) {
        val tag = if (emoji) "emoji-search" else "search"
        await("Intended input owns focus") { tagged(nodes(), tag)?.config?.getOrNull(SemanticsProperties.Focused) == true }
        main { replace(value, emoji) }
        await("Input text committed") { text(tagged(nodes(), tag)) == value }
        instrumentation.waitForIdleSync(); device.waitForIdle()
    }
    /** Independent visible-pixel upper bound. No decor-overlay draw callback is used.
     * Query/result goldens omit the blinking search caret. Every measured change
     * must match the captured settled pixels, not merely a changed model value. */
    @Test fun pixelVerifiedInteractionLatency() {
        for (emoji in listOf(false, true)) {
            if (emoji) {
                main { activity.execute(Command("emoji", "Emoji", "")) }
                await("Emoji input focus") { tagged(nodes(), "emoji-search")?.config?.getOrNull(SemanticsProperties.Focused) == true }
            }
            val queries = if (emoji) listOf("face", "cat", "heart", "flag", "zz-no-emoji")
                else listOf("settings", "calculator", "sqrt(144)", "wifi", "zz-no-readiness")
            val goldens = mutableMapOf<String, String>()
            // Two identical consecutive readbacks reject unsettled reference frames.
            for ((index, query) in queries.withIndex()) {
                inputReady(query, emoji)
                val region = resultRegion(emoji)
                val first = pixels(region)
                val second = pixels(region)
                assertEquals("Stable golden pixels for $query", hash(first), hash(second))
                goldens[query] = hash(second)
                File(output, "pixel-reference-${if (emoji) "emoji" else "launcher"}-$index.png").outputStream().use {
                    assertTrue(second.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                first.recycle(); second.recycle()
            }
            assertEquals("Each fixture must change visible result pixels", queries.size, goldens.values.toSet().size)
            val region = resultRegion(emoji)
            repeat(10) {
                for (query in queries) {
                    var started = 0L
                    main { started = System.nanoTime(); replace(query, emoji) }
                    var checks = 0
                    var observed: String
                    do {
                        val bitmap = pixels(region); observed = hash(bitmap); bitmap.recycle(); checks++
                        assertTrue("Visible query response within 5 seconds", System.nanoTime() - started < 5_000_000_000L)
                    } while (observed != goldens.getValue(query))
                    samples.put(JSONObject().put("case", if (emoji) "pixel-emoji-query" else "pixel-launcher-query")
                        .put("ms", (System.nanoTime()-started)/1_000_000.0).put("readbacks", checks))
                }
            }
            if (emoji) {
                inputReady("face", true)
                val identities = mutableMapOf<Int, String>()
                for (selection in listOf(0, 1)) {
                    main {
                        if (activity.store.emoji.selected != selection) {
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                        }
                    }
                    instrumentation.waitForIdleSync(); device.waitForIdle()
                    val bitmap = pixels(region); identities[selection] = hash(bitmap); bitmap.recycle()
                }
                assertNotEquals(identities[0], identities[1])
                repeat(40) { index ->
                    val target = if (index%2==0) 0 else 1
                    val code = if (target==0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                    var started = 0L
                    main { started=System.nanoTime(); activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code)); activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code)) }
                    var observed: String; var checks=0
                    do {
                        val bitmap=pixels(region); observed=hash(bitmap); bitmap.recycle(); checks++
                        assertTrue("Visible highlight response within 5 seconds",System.nanoTime()-started<5_000_000_000L)
                    } while(observed!=identities.getValue(target))
                    samples.put(JSONObject().put("case","pixel-emoji-navigation").put("ms",(System.nanoTime()-started)/1_000_000.0).put("readbacks",checks))
                }
                repeat(20) {
                    val started=System.nanoTime();val bitmap=pixels(region);hash(bitmap);bitmap.recycle()
                    samples.put(JSONObject().put("case","pixel-readback-control").put("ms",(System.nanoTime()-started)/1_000_000.0))
                }
                capture("pixel-verified-emoji")
            }
        }
        record("pixel-latency", JSONObject().put("pixelMethod","IME commit/native key dispatch to SHA-256 matching previously captured settled result/highlight pixels; includes PixelCopy, hashing and harness overhead; no physical input/scanout timing"))
    }

    @Test fun sustainedSearchAndRouteReliability() {
        val seconds = (InstrumentationRegistry.getArguments().getString("soakSeconds")?.toLongOrNull() ?: 300).coerceIn(60, 1800)
        val started = SystemClock.elapsedRealtime()
        var rounds = 0
        val memory = JSONArray()
        fun memorySample() {
            val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
            memory.put(JSONObject().put("round", rounds).put("seconds", (SystemClock.elapsedRealtime() - started) / 1000.0)
                .put("totalPssKb", info.totalPss).put("javaHeapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()))
        }
        memorySample()
        try {
            while (SystemClock.elapsedRealtime() - started < seconds * 1000 || rounds < 20) {
                for (query in listOf("settings", "sqrt(144)", "zz-no-readiness", "")) search(query)
                main { activity.execute(Command("emoji", "Emoji", "")) }
                search("face", emoji = true)
                await("Emoji field owns focus") { tagged(nodes(), "emoji-search")?.config?.getOrNull(SemanticsProperties.Focused) == true }
                device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.waitForIdleSync(); assertEquals(1, activity.store.emoji.selected)
                // Exercise native Back, not a synthetic route mutation.
                device.pressKeyCode(KeyEvent.KEYCODE_ESCAPE)
                await("Emoji query cleared") { activity.store.emoji.query.isEmpty() }
                device.pressKeyCode(KeyEvent.KEYCODE_ESCAPE)
                await("Launcher field owns focus") { tagged(nodes(), "search")?.config?.getOrNull(SemanticsProperties.Focused) == true }
                rounds++
                if (rounds % 10 == 0) memorySample()
            }
            search("sqrt(144)"); capture("droidcast-soak-finished")
        } finally {
            memorySample()
            record("soak", JSONObject().put("rounds", rounds).put("elapsedSeconds", (SystemClock.elapsedRealtime() - started) / 1000.0).put("memory", memory))
        }
    }
}
