package dev.tinycast.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    private var originalGrants=emptySet<Uri>()
    private var mediaUri:Uri?=null
    private var chosenUri:Uri?=null
    private var snapshotReady=false
    @Before fun prepare() {
        original=context.getSharedPreferences("palette",Context.MODE_PRIVATE).all.toMap()
        originalGrants=context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
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
            // This standalone case opens exactly one owned picker fixture. Roll back
            // its new permission even if metadata assertions fail before chosenUri is set.
            if(snapshotReady) context.contentResolver.persistedUriPermissions.filter { it.uri !in originalGrants }.forEach { grant ->
                context.contentResolver.releasePersistableUriPermission(grant.uri,(if(grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if(grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
            }
        } finally {
            try { mediaUri?.let { context.contentResolver.delete(it,null,null) } }
            finally { if(snapshotReady) restorePalettePreferences(context,original) }
        }
        if(snapshotReady) assertEquals(originalGrants,context.contentResolver.persistedUriPermissions.map { it.uri }.toSet())
    }
    private fun clickPicker(label:String,match:(AccessibilityNodeInfo)->Boolean) {
        val deadline=android.os.SystemClock.uptimeMillis()+7000
        var clicked=false
        while(!clicked && android.os.SystemClock.uptimeMillis()<deadline) {
            val automation=testAutomation();automation.clearCache()
            fun find(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                if(node.packageName?.toString()=="com.google.android.documentsui" && node.isVisibleToUser && match(node))return node
                for(index in 0 until node.childCount) node.getChild(index)?.let { child -> find(child)?.let { return it } }
                return null
            }
            var node=automation.windows.asSequence().mapNotNull { it.root }.mapNotNull(::find).firstOrNull()
            while(node!=null && !node.isClickable)node=node.parent
            clicked=node?.takeIf { it.packageName?.toString()=="com.google.android.documentsui" && it.isEnabled && it.isVisibleToUser }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            if(!clicked)device.waitForIdle(200)
        }
        assertTrue("Picker node: $label",clicked)
    }
    private fun capture(label:String) {
        val directory=File(context.filesDir,"readiness-evidence").apply { mkdirs() }
        testAutomation().clearCache();device.waitForIdle()
        device.dumpWindowHierarchy(File(directory,"$label.xml"));assertTrue(device.takeScreenshot(File(directory,"$label.png")))
    }
    @Test fun chosenRecentDocumentGetsItsNativeNameAndOpensTheExactContent() {
        compose.activityRule.scenario.onActivity { it.execute(Command("browse-files","Open a document","")) }
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10_000))
        clickPicker("Show roots") { it.contentDescription?.toString()=="Show roots" }
        clickPicker("Downloads") { it.viewIdResourceName=="android:id/title" && it.text?.toString()=="Downloads" }
        assertTrue("Controlled fixture appears in Downloads",device.wait(Until.hasObject(By.pkg("com.google.android.documentsui").text(name)),10_000))
        capture("recent-native-picker")
        clickPicker(name) { it.text?.toString()==name }
        compose.waitUntil(10_000) { compose.activity.store.files.any { it.title==name } && compose.activity.window.decorView.hasWindowFocus() }
        val saved=compose.activity.store.files.single { it.title==name };chosenUri=Uri.parse(saved.body)
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri==chosenUri && it.isReadPermission })
        compose.activityRule.scenario.onActivity { it.store.filter("Files");it.store.search(name) }
        compose.onNodeWithTag("search").assertIsFocused()
        assertEquals(saved.id,compose.activity.store.results().single().id)
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).text(name)),7000))
        capture("recent-native-result")
        device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
        assertTrue("Native text viewer",device.wait(Until.hasObject(By.pkg("com.android.htmlviewer")),10_000))
        assertTrue("Exact chosen title",device.wait(Until.hasObject(By.pkg("com.android.htmlviewer").text(name)),7000))
        assertTrue("Exact chosen body",device.wait(Until.hasObject(By.pkg("com.android.htmlviewer").textContains(body)),7000))
        capture("recent-native-viewer")
        File(context.filesDir,"readiness-evidence/recent-native-record.json").writeText(JSONObject()
            .put("fixture_name",name).put("body",body).put("persisted_read_grant",true).put("exact_title",true).put("exact_body",true).toString(2))
    }
}
