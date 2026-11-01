package dev.tinycast.android

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** A short landscape/200% fixture, run separately from the default-font cohort. */
@RunWith(AndroidJUnit4::class)
class FeedbackReadabilityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyMap<String, Any?>()
    private var prepared = false

    @Before fun prepare() {
        assertEquals("Tinycast_Tablet_API_36", InstrumentationRegistry.getArguments().getString("dedicatedAvd"))
        compose.waitUntil(10000) { kotlin.math.abs(context.resources.configuration.fontScale - 2f) < .01f }
        assertEquals("This fixture requires the explicit landscape display profile", Configuration.ORIENTATION_LANDSCAPE,
            context.resources.configuration.orientation)
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        prepared = true
        compose.waitUntil(15000) { !compose.activity.store.loading }
        compose.activityRule.scenario.onActivity {
            it.store.filter("All")
            it.store.search("10 km to mi")
        }
        compose.onNodeWithTag("search").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("Show on-screen keyboard").performTouchInput { click(center) }
        compose.waitUntil(10000) { imeVisible() }
        var last: Pair<Rect, Boolean>? = null
        var since = 0L
        compose.waitUntil(5000) {
            val node = compose.onNodeWithTag("palette-content-scroll").fetchSemanticsNode()
            val bounds = compose.runOnUiThread { node.boundsInRoot to imeVisible() }
            val now = android.os.SystemClock.elapsedRealtime()
            if (bounds != last) { last = bounds; since = now; false } else now - since >= 300
        }
        assertTrue("Keyboard must remain visible after the viewport settles", imeVisible())
    }

    @After fun restore() {
        if (!prepared) return
        compose.activityRule.scenario.onActivity { it.store.message = null }
        restorePalettePreferences(context, original)
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun coordinates(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun fullBounds(node: androidx.compose.ui.semantics.SemanticsNode): Rect {
        val point = node.positionInRoot
        return Rect(point.x, point.y, point.x + node.size.width, point.y + node.size.height)
    }

    private fun feedback(message: String, name: String) {
        val frameNode = compose.onNodeWithTag("palette").fetchSemanticsNode()
        val footerNode = compose.onNodeWithTag("palette-footer").fetchSemanticsNode()
        val (frame, footer) = compose.runOnUiThread { frameNode.boundsInRoot to fullBounds(footerNode) }
        assertTrue("Fixture must leave the whole footer outside the palette: $footer in $frame",
            footer.top >= frame.bottom - 1 || footer.bottom <= frame.top + 1)
        val clippedFooter = compose.runOnUiThread { footerNode.boundsInRoot }
        assertTrue("Fixture must exercise a clipped, non-null footer rectangle", clippedFooter.height <= 1)

        // Deliberately injected app feedback exercises rendering only. These
        // fixture messages do not certify a clipboard copy or an operation error.
        compose.activityRule.scenario.onActivity { it.store.message = message }
        val text = compose.onNodeWithText(message, useUnmergedTree = true)
        compose.waitUntil(5000) { compose.onAllNodesWithText(message, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val hostNode = compose.onNodeWithTag("palette-feedback").fetchSemanticsNode()
        val textNode = text.fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val snapshot = compose.runOnUiThread {
            listOf(frameNode.boundsInRoot, hostNode.boundsInRoot, fullBounds(hostNode), textNode.boundsInRoot, fullBounds(textNode))
        }
        val out = File(context.filesDir, "readiness-evidence").apply { mkdirs() }
        val installed = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        val record = JSONObject().put("installed_build_commit", installed.metaData.getString("dev.droidcast.BUILD_COMMIT"))
            .put("fixture", "Injected app feedback with an offscreen footer; not an operation-result claim")
            .put("message", message).put("font_scale", context.resources.configuration.fontScale)
            .put("width_dp", context.resources.configuration.screenWidthDp).put("height_dp", context.resources.configuration.screenHeightDp)
            .put("ime_visible", imeVisible()).put("palette", coordinates(snapshot[0]))
            .put("footer_full", coordinates(footer)).put("footer_clipped", coordinates(clippedFooter))
            .put("feedback_visible", coordinates(snapshot[1])).put("feedback_full", coordinates(snapshot[2]))
            .put("text_visible", coordinates(snapshot[3])).put("text_full", coordinates(snapshot[4]))
            .put("layouts", JSONArray(layouts.map { layout -> JSONObject()
                .put("width", layout.size.width).put("height", layout.size.height)
                .put("overflow_width", layout.didOverflowWidth).put("overflow_height", layout.didOverflowHeight)
                .put("lines", JSONArray((0 until layout.lineCount).map { index -> JSONObject()
                    .put("left", layout.getLineLeft(index)).put("right", layout.getLineRight(index))
                    .put("top", layout.getLineTop(index)).put("bottom", layout.getLineBottom(index))
                    .put("ellipsized", layout.isLineEllipsized(index)) })) }))
        File(out, "feedback-$name.json").writeText(record.toString(2))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.takeScreenshot(File(out, "feedback-$name.png")))
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(out, "feedback-$name.xml"))

        val visible = snapshot[1]
        val full = snapshot[2]
        assertTrue("Feedback must have its full measured height: $visible / $full", visible.height >= full.height - 1)
        assertTrue("Feedback must fit within the actual palette", snapshot[0].contains(full.topLeft) && snapshot[0].contains(full.bottomRight))
        assertTrue("Feedback must contain a text line", visible.height >= context.resources.displayMetrics.density * 20)
        assertTrue("Feedback text must have visible height", snapshot[3].height > 1)
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertFalse("Feedback text must not overflow vertically", layout.didOverflowHeight)
        assertFalse("Feedback text must not overflow horizontally", layout.didOverflowWidth)
        for (line in 0 until layout.lineCount) {
            assertFalse("Feedback text must not ellipsize", layout.isLineEllipsized(line))
            assertTrue("Feedback line must fit its measured width", layout.getLineLeft(line) >= -1 && layout.getLineRight(line) <= layout.size.width + 1)
            assertTrue("Feedback line must fit its measured height", layout.getLineTop(line) >= -1 && layout.getLineBottom(line) <= layout.size.height + 1)
        }
        // The complete message retains its requested font size and natural
        // layout. In this tiny IME viewport, expose each line through a real
        // pointer swipe instead of requiring all lines to fit simultaneously.
        val probes = JSONArray()
        try {
            for (line in 0 until layout.lineCount) {
                var fits = false
                repeat(3) {
                    if (fits) return@repeat
                    val node = text.fetchSemanticsNode()
                    val viewport = compose.onNodeWithTag("palette-feedback-scroll").fetchSemanticsNode()
                    val (bounds, fullText) = compose.runOnUiThread { viewport.boundsInRoot to fullBounds(node) }
                    val top = fullText.top + layout.getLineTop(line)
                    val bottom = fullText.top + layout.getLineBottom(line)
                    fits = top >= bounds.top - 1 && bottom <= bounds.bottom + 1
                    probes.put(JSONObject().put("line", line).put("viewport", coordinates(bounds))
                        .put("line_top", top).put("line_bottom", bottom).put("fully_visible", fits))
                    if (!fits) compose.onNodeWithTag("palette-feedback-scroll").performTouchInput {
                        val below = bottom > bounds.bottom + 1
                        val start = if (below) height * .9f else height * .1f
                        val end = if (below) height * .1f else height * .9f
                        swipeWithVelocity(androidx.compose.ui.geometry.Offset(center.x, start),
                            androidx.compose.ui.geometry.Offset(center.x, end), endVelocity = 0f, durationMillis = 200)
                    }
                }
                assertTrue("Each feedback line must be reachable and fully visible by touch: line $line", fits)
            }
            assertTrue(device.takeScreenshot(File(out, "feedback-$name-after-pan.png")))
            testAutomation().clearCacheForTest()
            device.dumpWindowHierarchy(File(out, "feedback-$name-after-pan.xml"))
        } finally {
            record.put("line_reachability", probes)
            File(out, "feedback-$name.json").writeText(record.toString(2))
        }
    }

    @Test fun confirmationFitsWithTheFooterOffscreen() = feedback("Copied audit value", "confirmation")
    @Test fun errorFitsWithTheFooterOffscreen() = feedback(
        "Could not finish the audit action. Please try again after checking its configuration.", "error")
}
