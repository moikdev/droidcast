package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real runtime/native controls. The same harness can run against the old app.
 * Uses the shared guarded input runner/lease and independent clipboard recovery.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionFormDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get() = compose.activity.store.extensions
    private val leaseFile get() = File(context.filesDir, "reviewed-text-fixture-lease/owners.json")
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private var prepared = false
    private var ownedId: String? = null
    private var palette = emptyMap<String, Any?>()
    private var originalIds = emptySet<String>()
    private var preserved = emptyList<File>()
    private var fingerprints = emptyList<Map<String, String>>()
    private val record = JSONObject().put("scope", "Owned React Form through the real runtime and Android controls; not Store/service certification")
    private val fieldError = "Owned name is required. Enter a name before continuing."
    private val fieldInfo = "Owned field help stays available without a desktop hover."
    private val choiceTitle = "Readable second layout"
    private val checkboxLabel = "Tap this complete option label to enable owned output"
    private val caller = "form-controls-owned-caller"

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fingerprint(root: File): Map<String, String> = if (!root.exists()) emptyMap() else root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else hash(it.readBytes())
    }
    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the guarded input/Form runner", args.getString("reviewedTextFixture") == "true")
        assertEquals("Tinycast_Tablet_API_36", args.getString("dedicatedAvd"))
        val owner = args.getString("expectedTextInputLeaseOwner")
        assertTrue(owner?.matches(Regex("[0-9a-f]{32}")) == true)
        assertEquals(owner, args.getString("expectedClipboardRecoveryOwner"))
        val lease = JSONObject(leaseFile.readText())
        assertEquals("reviewed-text-device-test-v1", lease.getString("fixture_kind"))
        assertEquals(owner, lease.getString("owner_nonce"))
        compose.waitUntil(15000) { !compose.activity.store.loading }
        palette = context.getSharedPreferences("palette", Context.MODE_PRIVATE).all.toMap()
        originalIds = catalog.installed.map { it.listing.id }.toSet()
        preserved = catalog.installed.flatMap { listOf(it.directory, File(context.filesDir, "extension-data/${it.listing.id}"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-${it.listing.id}.xml")) }
        fingerprints = preserved.map(::fingerprint)
        prepared = true
        dismissClipboardForEvidence()
        compose.activityRule.scenario.onActivity {
            // The host already durably snapshotted the original in another UID.
            it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Owned Form fixture", "Owned Form clipboard sentinel"))
            it.store.reset(); it.store.search(caller)
        }
        dismissClipboardForEvidence()
        record.put("font_scale", context.resources.configuration.fontScale)
            .put("width_dp", context.resources.configuration.screenWidthDp).put("height_dp", context.resources.configuration.screenHeightDp)
            .put("installed_build_commit", context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData.getString("dev.droidcast.BUILD_COMMIT"))
    }
    private fun launch(mode: String) {
        val id = "reviewed-text-" + UUID.randomUUID().toString().replace("-", "")
        val lease = JSONObject(leaseFile.readText())
        val ids = lease.getJSONArray("owned_extension_ids")
        assertTrue(ids.length() < 100); assertTrue((0 until ids.length()).none { ids.getString(it) == id }); ids.put(id)
        val temporary = File(leaseFile.path + ".tmp"); assertFalse(temporary.exists())
        temporary.writeText(lease.toString()); assertTrue(temporary.renameTo(leaseFile))
        ownedId = id
        val directory = File(context.cacheDir, id).apply { assertFalse(exists()); assertTrue(mkdir()) }
        val script = """
            const React=require('react'), api=require('@raycast/api');
            const mode=${JSONObject.quote(mode)};
            module.exports.default=function(){
              const [name,setName]=React.useState(''),[notes,setNotes]=React.useState('');
              const [choice,setChoice]=React.useState('internal-layout-two'),[checked,setChecked]=React.useState(false),[changes,setChanges]=React.useState(0);
              const [events,setEvents]=React.useState({focus:0,blur:0,length:0});
              const status='Name focus='+events.focus+' blur='+events.blur+' length='+events.length+'; checkbox changes='+changes;
              const fields=mode==='choices'?[
                React.createElement(api.Form.Dropdown,{key:'choice',id:'choice',title:'Owned layout choice',value:choice,onChange:setChoice},
                  React.createElement(api.Form.Dropdown.Item,{value:'internal-layout-one',title:'Readable first layout'}),
                  React.createElement(api.Form.Dropdown.Item,{value:'internal-layout-two',title:${JSONObject.quote(choiceTitle)}})),
                React.createElement(api.Form.Checkbox,{key:'check',id:'enabled',title:'Owned output option',label:${JSONObject.quote(checkboxLabel)},value:checked,
                  onChange:value=>{setChecked(value);setChanges(n=>n+1)}})
              ]:[
                React.createElement(api.Form.TextField,{key:'name',id:'name',title:'Owned account name',value:name,onChange:setName,
                  error:mode==='messages'?${JSONObject.quote(fieldError)}:undefined,info:mode==='messages'?${JSONObject.quote(fieldInfo)}:undefined,
                  onFocus:event=>setEvents(old=>({...old,focus:old.focus+1})),
                  onBlur:event=>setEvents(old=>({...old,blur:old.blur+1,length:(event.target.value||'').length}))}),
                React.createElement(api.Form.TextArea,{key:'notes',id:'notes',title:'Owned notes',value:notes,onChange:setNotes})
              ];
              return React.createElement(api.Form,{actions:React.createElement(api.ActionPanel,null,
                React.createElement(api.Action.SubmitForm,{title:'Inspect owned values',onSubmit:()=>{}}))},
                ...fields,React.createElement(api.Form.Description,{key:'status',text:status}));
            };
        """.trimIndent()
        File(directory, "form.js").writeText(script)
        val extension = InstalledExtension(StoreExtension(id, "form-fixture", "Owned Form probe", "", "Droidcast tests", "test", "", "", "", 1),
            directory, JSONObject("""{"commands":[{"name":"form","title":"Owned Form controls","mode":"view"}]}"""))
        record.put("fixture_mode", mode).put("fixture_command_sha256", hash(script.toByteArray(Charsets.UTF_8))).put("owned_extension_id", id)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single()) }
        compose.waitUntil(25000) { catalog.runtimeError != null || (catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == "Form") }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        compose.waitForIdle()
    }
    private fun field(type: String, id: String) = requireNotNull(activeRoot(catalog.runtimeTree)).nodes(type).single { it.props().optString("id") == id }
    private fun capture(phase: String) {
        compose.waitForIdle()
        val tree = catalog.runtimeTree?.toString(); val epoch = catalog.launchEpoch
        val frames = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frames.countDown() } } }
        assertTrue(frames.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
        assertEquals(epoch, catalog.launchEpoch); assertEquals(tree, catalog.runtimeTree?.toString())
        val name = "form-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.dumpWindowHierarchy(File(evidence, "$name.xml")); assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        assertEquals(epoch, catalog.launchEpoch); assertEquals(tree, catalog.runtimeTree?.toString())
        File(evidence, "$name-frame.json").writeText(JSONObject().put("scope", record.getString("scope")).put("phase", phase)
            .put("font_scale", context.resources.configuration.fontScale).put("launch_epoch", epoch)
            .put("ime_visible", ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            .put("synchronization", "Committed semantics and two display frames; hierarchy before PNG; stable runtime tree")
            .toString(2))
    }
    @After fun restore() {
        if (!prepared) return
        try {
            if (!record.optBoolean("passed") && ownedId != null) try { capture("failure") }
            catch (failure: Throwable) { record.put("failure_capture_error_type", failure.javaClass.simpleName) }
        } finally {
            compose.activityRule.scenario.onActivity { it.store.extensions.close(); it.store.dialog = null }
            restorePalettePreferences(context, palette)
            ownedId?.let { id ->
                listOf(File(context.cacheDir, id), File(context.filesDir, "extension-data/$id")).forEach { it.deleteRecursively(); assertFalse(it.exists()) }
                listOf(".xml", ".xml.bak").forEach { suffix ->
                    val file = File(context.applicationInfo.dataDir, "shared_prefs/extension-$id$suffix"); file.delete(); assertFalse(file.exists())
                }
            }
            assertEquals(originalIds, catalog.installed.map { it.listing.id }.toSet())
            assertEquals(fingerprints, preserved.map(::fingerprint))
            record.put("owned_data_removed", true).put("preexisting_installations_and_data_preserved", true)
            File(evidence, "form-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json").writeText(record.toString(2))
        }
    }
    @Test fun fieldValidationErrorIsVisible() {
        launch("messages")
        assertEquals(fieldError, field("Form.TextField", "name").props().getString("error"))
        compose.onNodeWithTag("extension-field-name").assertIsDisplayed()
        record.put("runtime_supplied_error", true); capture("feedback")
        compose.onNodeWithText(fieldError, useUnmergedTree = true).assertIsDisplayed()
        record.put("passed", true)
    }
    @Test fun fieldHelpIsVisibleWithoutHover() {
        launch("messages")
        assertEquals(fieldInfo, field("Form.TextField", "name").props().getString("info"))
        compose.onNodeWithTag("extension-field-name").assertIsDisplayed()
        record.put("runtime_supplied_help", true); capture("help")
        compose.onNodeWithText(fieldInfo, useUnmergedTree = true).assertIsDisplayed()
        record.put("passed", true)
    }
    @Test fun focusAndBlurDeliverCurrentFieldValue() {
        launch("focus")
        val node = field("Form.TextField", "name")
        assertNotNull(node.handle("onTinycastFocus")); assertNotNull(node.handle("onTinycastBlur"))
        record.put("runtime_supplied_focus_and_blur_handles", true)
        val value = "Owned café é 日本 🌱"
        compose.onNodeWithTag("extension-field-name").performTouchInput { click(center) }
        compose.onNodeWithTag("extension-field-name").assertIsFocused()
        compose.onNodeWithTag("extension-field-name").performTextReplacement(value)
        compose.waitUntil(10000) { field("Form.TextField", "name").props().optString("value") == value }
        compose.onNodeWithTag("extension-field-notes").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithTag("extension-field-notes").assertIsFocused()
        record.put("pointer_focus_moved_between_fields", true)
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.nodes("Form.Description")?.any {
            it.props().optString("text") == "Name focus=1 blur=1 length=${value.length}; checkbox changes=0"
        } == true }
        capture("focused-fields")
        record.put("focus_and_blur_once_with_current_value", true).put("payload_utf16_units", value.length).put("passed", true)
    }
    @Test fun dropdownShowsSelectedItemTitle() {
        launch("choices")
        val node = field("Form.Dropdown", "choice")
        assertEquals("internal-layout-two", node.props().getString("value"))
        assertTrue(node.nodes("Form.Dropdown.Item").any { it.props().optString("title") == choiceTitle })
        record.put("runtime_supplied_value_and_item_title", true); capture("closed-choice")
        compose.onNodeWithText(choiceTitle, useUnmergedTree = true).assertIsDisplayed()
        record.put("passed", true)
    }
    @Test fun checkboxLabelTogglesExactlyOnce() {
        launch("choices")
        assertFalse(field("Form.Checkbox", "enabled").props().optBoolean("value"))
        capture("before-label-tap")
        compose.onNodeWithText(checkboxLabel, useUnmergedTree = true).assertIsDisplayed().performTouchInput { click(center) }
        record.put("actual_label_pointer_tap_performed", true)
        compose.waitUntil(5000) { field("Form.Checkbox", "enabled").props().optBoolean("value") }
        compose.waitForIdle(); capture("label-tap")
        assertTrue("Tapping the actual label must toggle its checkbox", field("Form.Checkbox", "enabled").props().optBoolean("value"))
        compose.onNodeWithText("Name focus=0 blur=0 length=0; checkbox changes=1", useUnmergedTree = true).assertIsDisplayed()
        record.put("label_pointer_tap_toggled_once", true).put("passed", true)
    }
}
