package dev.tinycast.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual DocumentsUI → activity result → background metadata → native viewer. */
@SdkSuppress(minSdkVersion=29)
@RunWith(AndroidJUnit4::class)
class RecentDocumentDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val name="Droidcast-recent-${UUID.randomUUID()}.txt"
    private val body="Droidcast recent document fixture body 271828"
    private var original=emptyMap<String,Any?>()
    private var originalGrants=emptyMap<Uri, Pair<Boolean, Boolean>>()
    private var mediaUri:Uri?=null
    private var chosenUri:Uri?=null
    private var snapshotReady=false
    @Before fun prepare() {
        original=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        originalGrants=context.contentResolver.persistedUriPermissions.associate { it.uri to (it.isReadPermission to it.isWritePermission) }
        snapshotReady=true
        compose.waitUntil(15_000) { !compose.activity.store.loading }
        compose.activityRule.scenario.onActivity { it.store.resetShortcuts();it.store.reset() }
        mediaUri=requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
        }))
        context.contentResolver.openOutputStream(mediaUri!!)?.use { it.write(body.toByteArray()) } ?: error("Fixture output unavailable")
    }
    @After fun restore() {
        if(device.currentPackageName=="com.android.htmlviewer") device.pressBack()
        try {
            // Only release permissions attributable to this test's unique fixture.
            if(snapshotReady) context.contentResolver.persistedUriPermissions.filter { grant ->
                grant.uri !in originalGrants && (grant.uri == chosenUri || runCatching {
                    val columns = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
                    context.contentResolver.query(grant.uri, columns, null, null, null)?.use {
                        it.moveToFirst() && it.getString(0) == name
                    } == true
                }.getOrDefault(false))
            }.forEach { grant ->
                context.contentResolver.releasePersistableUriPermission(grant.uri,(if(grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if(grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
            }
        } finally {
            try { mediaUri?.let { context.contentResolver.delete(it,null,null) } }
            finally { if(snapshotReady) restorePalettePreferences(context,original) }
        }
        if(snapshotReady) assertEquals(originalGrants,context.contentResolver.persistedUriPermissions.associate { it.uri to (it.isReadPermission to it.isWritePermission) })
    }
    private fun clickNative(label:String,expectedPackage:String="com.google.android.documentsui",match:(AccessibilityNodeInfo)->Boolean) {
        val deadline=android.os.SystemClock.uptimeMillis()+7000
        var clicked=false
        while(!clicked && android.os.SystemClock.uptimeMillis()<deadline) {
            val automation=testAutomation();automation.clearCacheForTest()
            fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                if(node.packageName?.toString()==expectedPackage && node.isVisibleToUser && match(node))return node
                for(index in 0 until node.childCount) node.getChild(index)?.let { child -> find(child)?.let { return it } }
                return null
            }
            var node=automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(::find).firstOrNull()
            while(node!=null && !node.isClickable)node=node.parent
            clicked=node?.takeIf { it.packageName?.toString()==expectedPackage && it.isEnabled && it.isVisibleToUser }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            if(!clicked)device.waitForIdle(200)
        }
        assertTrue("Picker node: $label",clicked)
    }
    private fun capture(label:String) {
        val directory=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
        testAutomation().clearCacheForTest();device.waitForIdle()
        device.dumpWindowHierarchy(File(directory,"$label.xml"));assertTrue(device.takeScreenshot(File(directory,"$label.png")))
    }
    private fun selectFixtureInDocumentsUi(capturePicker: Boolean = false) {
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10_000))
        clickNative("Show roots") { it.contentDescription?.toString()=="Show roots" }
        clickNative("Downloads") { it.viewIdResourceName=="android:id/title" && it.text?.toString()=="Downloads" }
        assertTrue("Controlled fixture appears in Downloads",device.wait(Until.hasObject(By.pkg("com.google.android.documentsui").text(name)),10_000))
        if (capturePicker) capture("recent-native-picker")
        clickNative(name) { it.text?.toString()==name }
    }
    private fun pickFixture(capturePicker: Boolean = false): SavedItem {
        compose.activityRule.scenario.onActivity { it.execute(Command("browse-files","Open a document","")) }
        selectFixtureInDocumentsUi(capturePicker)
        compose.waitUntil(10_000) { compose.activity.store.files.any { it.title==name } && compose.activity.window.decorView.hasWindowFocus() }
        val saved=compose.activity.store.files.single { it.title==name };chosenUri=Uri.parse(saved.body)
        compose.waitUntil(5_000) { context.contentResolver.persistedUriPermissions.any { it.uri==chosenUri && it.isReadPermission } }
        return saved
    }

    private fun deleteRecent(selected: SavedItem, withCapture: Boolean = false) {
        compose.activityRule.scenario.onActivity {
            it.store.filter("Files")
            it.store.search(selected.title)
        }
        compose.onNodeWithTag("search").assertIsFocused()
        assertEquals(selected.id, compose.activity.store.results().single().id)
        device.pressKeyCode(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON)
        compose.onNodeWithTag("inline-menu-choices").performScrollToNode(hasTestTag("action-Delete from Droidcast"))
        compose.onNodeWithTag("action-Delete from Droidcast").performClick()
        compose.onNodeWithTag("confirm-delete").assertIsDisplayed()
        if (withCapture) capture("recent-grant-confirm-delete")
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.waitUntil(5_000) { compose.activity.store.files.none { it.id == selected.id } }
        compose.onNodeWithTag("confirm-delete").assertDoesNotExist()
        if (withCapture) {
            compose.onNodeWithText("No matching commands").assertIsDisplayed()
            compose.waitUntil(5_000) {
                testAutomation().clearCacheForTest()
                device.hasObject(By.pkg(context.packageName).text("No matching commands"))
            }
            capture("recent-grant-after-delete")
        }
    }

    // Acquire access for a separate consumer through a second, actual picker result.
    private fun takeIndependentGrant(flags: Int): Uri {
        lateinit var launcher: ActivityResultLauncher<Array<String>>
        var returned: Uri? = null
        compose.activityRule.scenario.onActivity { activity ->
            launcher = activity.activityResultRegistry.register("recent-grant-fixture", ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    chosenUri = uri
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                    returned = uri
                }
            }
            launcher.launch(arrayOf("text/plain"))
        }
        try {
            selectFixtureInDocumentsUi()
            compose.waitUntil(10_000) { returned != null }
            return requireNotNull(returned)
        } finally {
            compose.activityRule.scenario.onActivity { launcher.unregister() }
        }
    }

    @Test fun keepsBorrowedReadWhenTheLastRecentIsRemoved() {
        val uri = takeIndependentGrant(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var legacyId: String? = null
        compose.activityRule.scenario.onActivity {
            // Pre-ownership records contain the URI but no acquisition journal entry.
            legacyId = it.store.save("files", name, uri.toString())
        }
        val saved = pickFixture()
        assertEquals(legacyId, saved.id)
        assertEquals(uri.toString(), saved.body)
        deleteRecent(saved)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { !compose.activity.store.loading }
        assertTrue(compose.activity.store.files.none { it.id == saved.id })
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
    }

    @Test fun lastSharedReferenceReleasesOnlyTheOwnedRead() {
        val uri = takeIndependentGrant(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val initial = context.contentResolver.persistedUriPermissions.single { it.uri == uri }
        assertTrue(initial.isWritePermission)
        assertFalse(initial.isReadPermission)
        val saved = pickFixture()
        assertEquals(uri.toString(), saved.body)
        lateinit var second: SavedItem
        compose.activityRule.scenario.onActivity { activity ->
            val id = activity.store.save("files", "Second reference", saved.body)
            second = activity.store.files.single { it.id == id }
        }
        deleteRecent(saved)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { !compose.activity.store.loading }
        assertTrue(compose.activity.store.files.any { it == second })
        assertTrue(context.contentResolver.persistedUriPermissions.single { it.uri == uri }.isReadPermission)
        deleteRecent(second)
        compose.waitUntil(5_000) {
            context.contentResolver.persistedUriPermissions.singleOrNull { it.uri == uri }?.let {
                it.isWritePermission && !it.isReadPermission
            } == true
        }
    }

    @Test fun failedRemovalCheckpointAndReleaseCanRetryFromANewStore() {
        val saved = pickFixture()
        val uri = Uri.parse(saved.body)
        val prefs = context.getSharedPreferences("palette", Context.MODE_PRIVATE)
        val resolver = context.contentResolver
        fun hasRead() = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

        // Stage just this reference's removal. The production cleanup is then invoked
        // with controlled failure points before a second Activity opens the same store.
        val rows = org.json.JSONArray(prefs.getString("files", "[]"))
        val otherRows = org.json.JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.getString("id") != saved.id) otherRows.put(row)
        }
        assertEquals(rows.length() - 1, otherRows.length())
        assertTrue(prefs.edit().putString("files", otherRows.toString()).commit())
        var releaseCalls = 0
        runBlocking {
            withContext(Dispatchers.Main) {
                val failedSave = RecentDocumentGrants(prefs, resolver, folderReferences = { emptySet() }, persist = { false }, releaseRead = { releaseCalls++ })
                assertTrue(runCatching { failedSave.releaseUnused() }.exceptionOrNull() is IllegalStateException)
                assertEquals(0, releaseCalls)
                assertTrue(hasRead())
                val failedRelease = RecentDocumentGrants(prefs, resolver, folderReferences = { emptySet() }, releaseRead = {
                    assertEquals(uri, it)
                    releaseCalls++
                    throw SecurityException("Controlled release failure")
                })
                assertTrue(runCatching { failedRelease.releaseUnused() }.exceptionOrNull() is SecurityException)
                assertEquals(1, releaseCalls)
                assertTrue(hasRead())
            }
        }
        ActivityScenario.launch(SettingsActivity::class.java).use {
            compose.waitUntil(10_000) { !hasRead() }
        }
    }

    @Test fun deletingLastPickedDocumentReleasesReadAccessAndKeepsSource() {
        val selected = pickFixture()
        val uri = Uri.parse(selected.body)
        assertFalse("Fixture already had a permission before selection", uri in originalGrants)
        deleteRecent(selected, withCapture = true)
        compose.waitUntil(5_000) {
            context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }
        }
        val originalBody = context.contentResolver.openInputStream(requireNotNull(mediaUri))?.bufferedReader()?.use { it.readText() }
        assertEquals("Removing a recent must keep the provider's document", body, originalBody)
    }

    @Test fun chosenRecentDocumentGetsItsNativeNameAndOpensTheExactContent() {
        try {
        val saved = pickFixture(capturePicker = true)
        compose.activityRule.scenario.onActivity { it.store.filter("Files");it.store.search(name) }
        compose.onNodeWithTag("search").assertIsFocused()
        assertEquals(saved.id,compose.activity.store.results().single().id)
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text(name)),7000))
        capture("recent-native-result")
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        var viewerOpened=device.wait(Until.hasObject(By.pkg("com.android.htmlviewer").text(name)),3000)
        if(!viewerOpened && device.wait(Until.hasObject(By.pkg("android").text("Just once")),3000)) {
            capture("recent-native-chooser")
            if(!device.hasObject(By.pkg("android").text("Open with HTML Viewer")))
                clickNative("HTML Viewer","android") { it.text?.toString()=="HTML Viewer" }
            clickNative("Just once","android") { it.text?.toString()=="Just once" && it.isEnabled }
            viewerOpened=device.wait(Until.hasObject(By.pkg("com.android.htmlviewer").text(name)),7000)
        }
        if(!viewerOpened) capture("recent-native-viewer-failure")
        assertTrue("Native text viewer",viewerOpened)
        assertTrue("Exact chosen title",device.wait(Until.hasObject(By.pkg("com.android.htmlviewer").text(name)),7000))
        assertTrue("Exact chosen body",device.wait(Until.hasObject(By.pkg("com.android.htmlviewer").textContains(body)),7000))
        capture("recent-native-viewer")
        File(context.filesDir,"readiness-evidence/recent-native-record.json").writeText(JSONObject()
            .put("fixture_name",name).put("body",body).put("persisted_read_grant",true).put("exact_title",true).put("exact_body",true).toString(2))
        } catch(failure:Throwable) {
            runCatching { capture("recent-native-failure") }
            throw failure
        }
    }
}
