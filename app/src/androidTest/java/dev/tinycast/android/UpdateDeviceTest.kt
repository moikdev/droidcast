package dev.tinycast.android

import android.content.Context
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.tinycast.android.updates.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

internal fun updateEvidence(name: String, marker: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val directory = File(instrumentation.targetContext.filesDir, "updates-evidence").apply { mkdirs() }
    assertTrue("Expected native updater state: $marker", device.wait(Until.hasObject(By.pkg(instrumentation.targetContext.packageName).textContains(marker)), 10_000))
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val rendered = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = WindowInspector.getGlobalWindowViews().single { it.hasWindowFocus() }
            view.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
            view.invalidate()
        }
        assertTrue("Updater frame committed", rendered.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }
    instrumentation.uiAutomation.clearCache()
    device.dumpWindowHierarchy(File(directory, "$name.xml"))
    assertTrue(device.takeScreenshot(File(directory, "$name.png")))
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateSettingsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<SettingsActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyMap<String, Any?>()
    @Before fun setup() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("cmd statusbar collapse")
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15_000) { !compose.activity.store.loading }
    }
    @After fun restore() { restorePalettePreferences(context, original) }
    @Test fun updatesPageFillsSettingsAndDoesNotCheckAutomatically() {
        compose.activityRule.scenario.onActivity { it.selectPage("updates") }
        compose.waitUntil(10_000) { !compose.activity.store.updates.state.busy }
        compose.onNodeWithTag("updates-check").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("updates-installed").assertTextEquals("Droidcast ${BuildConfig.VERSION_NAME}")
        assertEquals(UpdatePhase.IDLE, compose.activity.store.updates.state.phase)
        assertEquals(compose.onNodeWithTag("settings-viewport").fetchSemanticsNode().boundsInRoot,
            compose.onNodeWithTag("settings-frame").fetchSemanticsNode().boundsInRoot)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        updateEvidence("settings-idle", "Nightly channel")
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdatePaletteDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var original = emptyMap<String, Any?>()
    @Before fun setup() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("cmd statusbar collapse")
        original = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        compose.waitUntil(15_000) { !compose.activity.store.loading }
    }
    @After fun restore() { restorePalettePreferences(context, original) }
    @Test fun livePublicGithubCheckStaysInlineAndBackRestoresCaller() {
        compose.activityRule.scenario.onActivity { it.store.search("Public updater caller") }
        compose.onNodeWithTag("search").performTextInputSelection(TextRange(2, 8))
        compose.activityRule.scenario.onActivity { it.execute(requireNotNull(it.store.command("check-updates"))) }
        compose.waitUntil(90_000) { !compose.activity.store.updates.state.busy }
        val state = compose.activity.store.updates.state
        assertTrue("Public GitHub result: ${state.message}", state.phase in setOf(UpdatePhase.CURRENT, UpdatePhase.AVAILABLE))
        compose.onNodeWithTag("updates-installed").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        updateEvidence("palette-live-check", if (state.phase == UpdatePhase.CURRENT) "You're up to date" else "A new nightly is available")
        compose.onNodeWithTag("inline-back").performClick()
        compose.onNodeWithTag("search").assertIsFocused().assertTextEquals("Public updater caller")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 8)))
    }
}
