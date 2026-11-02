package dev.tinycast.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.tinycast.android.extensions.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real runtime/native controls. The same harness can run against the old app.
 * Uses the shared guarded input runner/lease and independent clipboard recovery.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ExtensionFormDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog get() = compose.activity.store.extensions
    private val leaseFile get() = File(context.filesDir, "reviewed-text-fixture-lease/owners.json")
    private val evidence get() = File(context.filesDir, "reviewed-text-evidence").apply { mkdirs() }
    private var prepared = false
    private var ownedId: String? = null
    private lateinit var ownedExtension: InstalledExtension
    private lateinit var expectedOwner: String
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
    private var sensitiveValue: String? = null
    private val longTitle = "Owned account name with a complete descriptive label that must wrap naturally on a narrow Android screen"
    private val longError = "Owned validation error: enter the complete account name before continuing. Every line must stay readable when the keyboard opens and the font is enlarged."
    private val longInfo = "Owned field help: this explanation is available by touch without hovering. Scroll to read every line, then continue with the choices below."
    private val longOption = "Readable first layout with a complete descriptive title that remains available when the font is enlarged and the keyboard is open"
    private val longCheckbox = "Enable the complete owned output option by tapping any part of this wrapped label, including its final line"

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fingerprint(root: File): Map<String, String> = if (!root.exists()) emptyMap() else root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else hash(it.readBytes())
    }
    private fun ownedLease(): JSONObject {
        val lease = JSONObject(leaseFile.readText())
        assertEquals(setOf("schema_version", "fixture_kind", "owner_nonce", "owned_extension_ids"), lease.keys().asSequence().toSet())
        assertEquals(1, lease.getInt("schema_version"))
        assertEquals("reviewed-text-device-test-v1", lease.getString("fixture_kind"))
        assertEquals(expectedOwner, lease.getString("owner_nonce"))
        return lease
    }
    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the guarded input/Form runner", args.getString("reviewedTextFixture") == "true")
        assertEquals("Tinycast_Tablet_API_36", args.getString("dedicatedAvd"))
        val owner = args.getString("expectedTextInputLeaseOwner")
        assertTrue(owner?.matches(Regex("[0-9a-f]{32}")) == true)
        assertEquals(owner, args.getString("expectedClipboardRecoveryOwner"))
        expectedOwner = requireNotNull(owner)
        ownedLease()
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
    private fun fixtureDirectory(): File {
        val id = "reviewed-text-" + UUID.randomUUID().toString().replace("-", "")
        val directory = File(context.cacheDir, id)
        listOf(directory, File(context.filesDir, "extensions/$id"), File(context.filesDir, "extension-data/$id"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml"),
            File(context.applicationInfo.dataDir, "shared_prefs/extension-$id.xml.bak")).forEach {
            assertFalse("Candidate fixture path must be unused", Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        val lease = ownedLease()
        val ids = lease.getJSONArray("owned_extension_ids")
        assertTrue(ids.length() < 100); assertTrue((0 until ids.length()).none { ids.getString(it) == id }); ids.put(id)
        val temporary = File(leaseFile.path + ".tmp"); assertFalse(temporary.exists())
        temporary.writeText(lease.toString()); assertTrue(temporary.renameTo(leaseFile))
        ownedId = id
        assertTrue(directory.mkdir())
        return directory
    }
    private fun launch(mode: String) {
        val directory = fixtureDirectory(); val id = directory.name
        val script = """
            const React=require('react'), api=require('@raycast/api');
            const mode=${JSONObject.quote(mode)};
            globalThis.__formProbe={changes:0,focus:{},blur:{},blurLengths:{},dispatches:0};
            const dispatch=globalThis.__tinycast.dispatch;
            globalThis.__tinycast.dispatch=function(...args){globalThis.__formProbe.dispatches++;return dispatch.apply(this,args)};
            function fieldEvents(id){return {onFocus:e=>{const p=globalThis.__formProbe.focus;p[id]=(p[id]||0)+1},
              onBlur:e=>{const p=globalThis.__formProbe;p.blur[id]=(p.blur[id]||0)+1;p.blurLengths[id]=typeof e.target.value==='string'?e.target.value.length:0}}}
            module.exports.default=function(props){
              if(mode==='configuration'){
                const prefs=api.getPreferenceValues();
                return React.createElement(api.Detail,{markdown:JSON.stringify({argument:props.arguments.input,account:prefs.account,enabled:prefs.enabled,layout:prefs.layout})});
              }
              if(mode==='validation'){
                const [name,setName]=React.useState(''),[error,setError]=React.useState(''),[notes,setNotes]=React.useState('');
                const nameEvents=fieldEvents('name');
                return React.createElement(api.Form,null,
                  React.createElement(api.Form.TextField,{key:'name',id:'name',title:'Owned account name',value:name,error:error,
                    onChange:value=>{setName(value);setError('');globalThis.__formProbe.changes++},onFocus:nameEvents.onFocus,
                    onBlur:event=>{nameEvents.onBlur(event);setError(event.target.value?'':${JSONObject.quote(fieldError)})}}),
                  React.createElement(api.Form.TextArea,{key:'notes',id:'notes',title:'Owned notes',value:notes,onChange:setNotes}));
              }
              if(mode==='controlled'){
                const [filtered,setFiltered]=React.useState('OWNED INITIAL');
                return React.createElement(api.Form,null,
                  React.createElement(api.Form.TextField,{key:'filtered',id:'filtered',title:'Owned transformed value',value:filtered,
                    onChange:value=>{setFiltered(value.toUpperCase());globalThis.__formProbe.changes++},...fieldEvents('filtered')}),
                  React.createElement(api.Form.TextField,{key:'rejected',id:'rejected',title:'Owned fixed value',value:'Owned fixed value',
                    onChange:()=>{globalThis.__formProbe.changes++},...fieldEvents('rejected')}),
                  React.createElement(api.Form.TextArea,{key:'notes',id:'notes',title:'Owned notes',...fieldEvents('notes')}));
              }
              if(mode==='all-fields'||mode==='long'){
                const [name,setName]=React.useState(''),[password,setPassword]=React.useState(''),[area,setArea]=React.useState('');
                const [choice,setChoice]=React.useState('internal-layout-two'),[checked,setChecked]=React.useState(false),[render,setRender]=React.useState(0);
                globalThis.__bumpOwnedForm=()=>setRender(n=>n+1);
                const change=setter=>value=>{globalThis.__formProbe.changes++;setter(value)};
                return React.createElement(api.Form,{actions:React.createElement(api.ActionPanel,null,React.createElement(api.Action.SubmitForm,{title:'Inspect owned values',onSubmit:()=>{}}))},
                  React.createElement(api.Form.TextField,{key:'name',id:'name',title:mode==='long'?${JSONObject.quote(longTitle)}:'Owned account name',value:name,onChange:change(setName),...fieldEvents('name'),
                    error:mode==='long'?${JSONObject.quote(longError)}:undefined,info:mode==='long'?${JSONObject.quote(longInfo)}:undefined}),
                  mode==='all-fields'?React.createElement(api.Form.PasswordField,{key:'password',id:'password',title:'Owned masked password',value:password,onChange:change(setPassword),...fieldEvents('password')}):null,
                  mode==='all-fields'?React.createElement(api.Form.TextArea,{key:'area',id:'area',title:'Owned multiline text',value:area,onChange:change(setArea),...fieldEvents('area')}):null,
                  mode==='all-fields'?React.createElement(api.Form.TextField,{key:'uncontrolled',id:'uncontrolled',title:'Owned uncontrolled text',defaultValue:'Owned initial',...fieldEvents('uncontrolled')}):null,
                  React.createElement(api.Form.Checkbox,{key:'enabled',id:'enabled',title:'Owned output option',label:mode==='long'?${JSONObject.quote(longCheckbox)}:${JSONObject.quote(checkboxLabel)},value:checked,onChange:change(setChecked),...fieldEvents('enabled')}),
                  React.createElement(api.Form.Dropdown,{key:'choice',id:'choice',title:'Owned layout choice',value:choice,onChange:change(setChoice),...fieldEvents('choice')},
                    React.createElement(api.Form.Dropdown.Item,{key:'one',value:'internal-layout-one',title:mode==='long'?${JSONObject.quote(longOption)}:'Readable first layout'}),
                    React.createElement(api.Form.Dropdown.Item,{key:'two',value:'internal-layout-two',title:${JSONObject.quote(choiceTitle)}})),
                  React.createElement(api.Form.Description,{key:'render',text:'Owned render '+render}));
              }
              if(mode==='edges'){
                const [value,setValue]=React.useState('duplicate-two');
                const [enabled,setEnabled]=React.useState(false);
                return React.createElement(api.Form,null,
                  React.createElement(api.Form.Dropdown,{key:'unknown',id:'unknown',title:'Owned unavailable selection',value:'owned-unavailable-internal-value',onChange:()=>{}},
                    React.createElement(api.Form.Dropdown.Item,{key:'known',value:'known',title:'Readable known option'})),
                  React.createElement(api.Form.Dropdown,{key:'empty',id:'empty',title:'Owned empty choices',value:'',onChange:()=>{}}),
                  React.createElement(api.Form.Dropdown,{key:'duplicates',id:'duplicates',title:'Owned duplicate titles',value:value,onChange:setValue},
                    React.createElement(api.Form.Dropdown.Item,{key:'one',value:'duplicate-one',title:'Same readable title'}),
                    React.createElement(api.Form.Dropdown.Item,{key:'two',value:'duplicate-two',title:'Same readable title'})),
                  React.createElement(api.Form.Checkbox,{key:'label-only',id:'label-only',label:'Owned label without a duplicated caption',value:enabled,onChange:setEnabled}));
              }
              const [name,setName]=React.useState(''),[notes,setNotes]=React.useState('');
              const [choice,setChoice]=React.useState('internal-layout-two'),[checked,setChecked]=React.useState(false),[changes,setChanges]=React.useState(0);
              const [events,setEvents]=React.useState({focus:0,blur:0,length:0});
              const status='Name focus='+events.focus+' blur='+events.blur+' length='+events.length+'; checkbox changes='+changes;
              const fields=mode==='choices'?[
                React.createElement(api.Form.Dropdown,{key:'choice',id:'choice',title:'Owned layout choice',value:choice,onChange:setChoice},
                  React.createElement(api.Form.Dropdown.Item,{key:'one',value:'internal-layout-one',title:'Readable first layout'}),
                  React.createElement(api.Form.Dropdown.Item,{key:'two',value:'internal-layout-two',title:${JSONObject.quote(choiceTitle)}})),
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
        val manifest = if (mode == "configuration") JSONObject("""{"commands":[{"name":"form","title":"Owned Form configuration","mode":"view","arguments":[{"name":"input","type":"text","placeholder":"Enter owned argument","required":true}],"preferences":[{"name":"account","title":"Owned account name","type":"textfield","required":true,"description":"Enter an owned value. This configuration is only saved when Run is selected."},{"name":"enabled","title":"Owned output option","type":"checkbox","label":"$checkboxLabel","default":false},{"name":"layout","title":"Owned layout choice","type":"dropdown","default":"internal-layout-two","data":[{"value":"internal-layout-one","title":"Readable first layout"},{"value":"internal-layout-two","title":"$choiceTitle"}]}]}]}""")
            else JSONObject("""{"commands":[{"name":"form","title":"Owned Form controls","mode":"view"}]}""")
        val extension = InstalledExtension(StoreExtension(id, "form-fixture", "Owned Form probe", "", "Droidcast tests", "test", "", "", "", 1), directory, manifest)
        ownedExtension = extension
        record.put("fixture_mode", mode).put("fixture_command_sha256", hash(script.toByteArray(Charsets.UTF_8))).put("owned_extension_id", id)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single()) }
        if (mode == "configuration") {
            compose.waitUntil(10000) { compose.activity.store.dialog == "extension-setup" }
            compose.waitForIdle(); return
        }
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
        assertEquals(epoch, catalog.launchEpoch); assertTrue("Runtime changed around the frame", tree == catalog.runtimeTree?.toString())
        val name = "form-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        testAutomation().clearCacheForTest()
        device.dumpWindowHierarchy(File(evidence, "$name.xml")); assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        sensitiveValue?.let { secret -> assertFalse("Native hierarchy must not expose masked input", File(evidence, "$name.xml").readText().contains(secret)) }
        assertEquals(epoch, catalog.launchEpoch); assertTrue("Runtime changed around the frame", tree == catalog.runtimeTree?.toString())
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
    @Test fun blurValidationShowsErrorAndEditingClearsIt() {
        launch("validation")
        compose.onNodeWithText(fieldError, useUnmergedTree = true).assertDoesNotExist()
        touch("extension-field-name"); touch("extension-field-notes")
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("error") == fieldError }
        compose.onNodeWithTag("extension-field-error-name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("extension-field-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, fieldError))
        capture("blur-validation")
        touch("extension-field-name")
        val name = "Owned café é 日本 🌱"
        compose.onNodeWithTag("extension-field-name").performTextReplacement(name)
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == name && field("Form.TextField", "name").props().optString("error").isBlank() }
        compose.onNodeWithText(fieldError, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("extension-field-name").assert(SemanticsMatcher("Error semantics cleared") { !it.config.contains(SemanticsProperties.Error) })
        touch("extension-field-notes")
        compose.waitUntil(5000) { probe().getJSONObject("blur").optInt("name") == 2 }
        val observed = probe(); assertEquals(2, observed.getJSONObject("focus").getInt("name"))
        assertEquals(name.length, observed.getJSONObject("blurLengths").getInt("name"))
        capture("validation-cleared")
        record.put("validation_method", "Real extension onBlur updates its error prop from the current value; real onChange clears it. Submit rejection is not exercised.")
            .put("focus_probe", observed).put("passed", true)
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
    @Test fun rapidTextThenBlurDeliversCurrentValueBeforeRedraw() {
        launch("focus")
        val engine = engine()
        val rounds = JSONArray(); record.put("rapid_rounds", rounds)
        for (index in 1..5) {
            touch("extension-field-name")
            compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.nodes("Form.Description")?.any {
                it.props().optString("text").startsWith("Name focus=$index blur=${index - 1} ")
            } == true }
            val setText = requireNotNull(compose.onNodeWithTag("extension-field-name").fetchSemanticsNode().config[SemanticsActions.SetText].action)
            val focusNotes = requireNotNull(compose.onNodeWithTag("extension-field-notes").fetchSemanticsNode().config[SemanticsActions.RequestFocus].action)
            val value = "Owned rapid $index café é 日本 🌱"
            compose.activityRule.scenario.onActivity {
                // Queue the two native events behind a bounded owned JS task.
                // No native redraw or JS acknowledgement separates the edit
                // from blur; this is a controlled ordering probe, not latency.
                engine.evaluateJavascript("(()=>{const until=performance.now()+40;while(performance.now()<until){};return true})()", null)
                assertTrue(setText(AnnotatedString(value))); assertTrue(focusNotes())
            }
            compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == value && activeRoot(catalog.runtimeTree)?.nodes("Form.Description")?.any {
                it.props().optString("text").contains("blur=$index ")
            } == true }
            val observed = requireNotNull(activeRoot(catalog.runtimeTree)).nodes("Form.Description").single().props().getString("text")
            rounds.put(JSONObject().put("round", index).put("numeric_callback_status", observed).put("expected_value_utf16_units", value.length))
            assertEquals("Name focus=$index blur=$index length=${value.length}; checkbox changes=0", observed)
        }
        record.put("interaction", "Five controlled SetText -> RequestFocus sequences in one main-thread turn, queued behind a 40ms owned JS task; no wait for change acknowledgement or native redraw")
        capture("rapid-blur"); record.put("passed", true)
    }
    @Test fun controlledTransformsAndRejectedEditsRemainAuthoritative() {
        launch("controlled"); touch("extension-field-filtered")
        val value = "owned café é 日本"; val expected = value.uppercase(java.util.Locale.ROOT)
        compose.onNodeWithTag("extension-field-filtered").performTextReplacement(value)
        compose.waitUntil(5000) { field("Form.TextField", "filtered").props().optString("value") == expected }
        compose.onNodeWithTag("extension-field-filtered").assertTextEquals(expected)
        touch("extension-field-rejected")
        compose.onNodeWithTag("extension-field-rejected").performTextReplacement("Owned edit that the extension rejects")
        compose.waitUntil(5000) { probe().getInt("changes") == 2 }
        compose.waitUntil(5000) { compose.onNodeWithTag("extension-field-rejected").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "Owned fixed value" }
        assertEquals("Owned fixed value", field("Form.TextField", "rejected").props().optString("value"))
        compose.onNodeWithTag("extension-field-rejected").assertTextEquals("Owned fixed value")
        touch("extension-field-notes")
        compose.waitUntil(5000) { probe().getJSONObject("blur").optInt("rejected") == 1 }
        val observed = probe()
        assertEquals(expected.length, observed.getJSONObject("blurLengths").getInt("filtered"))
        assertEquals("Owned fixed value".length, observed.getJSONObject("blurLengths").getInt("rejected"))
        capture("controlled-values")
        record.put("transformed_and_rejected_native_values_verified", true).put("focus_probe", observed).put("passed", true)
    }
    @Test fun replacementSessionRejectsOldCallbackAndAcceptsNewFocus() {
        launch("focus"); val originalRoot = requireNotNull(activeRoot(catalog.runtimeTree)).optString("id")
        val oldSetText = requireNotNull(compose.onNodeWithTag("extension-field-name").fetchSemanticsNode().config[SemanticsActions.SetText].action)
        val epoch = catalog.launchEpoch
        compose.activityRule.scenario.onActivity { it.store.openExtension(ownedExtension, ownedExtension.commands.single()) }
        compose.waitUntil(15000) { catalog.launchEpoch > epoch && catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        assertEquals("Fixture deliberately reuses the new runtime's native root ID", originalRoot, requireNotNull(activeRoot(catalog.runtimeTree)).optString("id"))
        val before = probe(); compose.activityRule.scenario.onActivity { assertTrue(oldSetText(AnnotatedString("Owned stale session"))) }
        assertEquals(before.toString(), probe().toString())
        touch("extension-field-name")
        val value = "Owned replacement 日本 🌱"
        compose.onNodeWithTag("extension-field-name").performTextReplacement(value)
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == value }
        touch("extension-field-notes")
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.nodes("Form.Description")?.any {
            it.props().optString("text") == "Name focus=1 blur=1 length=${value.length}; checkbox changes=0"
        } == true }
        capture("new-session-focus")
        record.put("actual_session_restart_with_reused_ids", true).put("old_callback_rejected_new_focus_works", true).put("passed", true)
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
        assertCheckboxSemantics("extension-checkbox-enabled", checkboxLabel)
        compose.waitForIdle(); capture("label-tap")
        assertTrue("Tapping the actual label must toggle its checkbox", field("Form.Checkbox", "enabled").props().optBoolean("value"))
        compose.onNodeWithText("Name focus=0 blur=0 length=0; checkbox changes=1", useUnmergedTree = true).assertIsDisplayed()
        record.put("label_pointer_tap_toggled_once", true).put("passed", true)
    }
    private fun assertCheckboxSemantics(tag: String, label: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val labels = (if (node.config.contains(SemanticsProperties.ContentDescription)) node.config[SemanticsProperties.ContentDescription] else emptyList()) +
            (if (node.config.contains(SemanticsProperties.Text)) node.config[SemanticsProperties.Text].map { it.text } else emptyList())
        assertTrue("The actionable checkbox needs its complete native label", label in labels)
        assertEquals(androidx.compose.ui.semantics.Role.Checkbox, node.config[SemanticsProperties.Role])
        compose.onNodeWithTag(tag).assertIsOn()
        record.put("native_checkbox_label_role_state_verified", true)
    }

    private fun touch(tag: String) {
        val node = compose.onNodeWithTag(tag)
        val viewport = listOf("extension-form", "extension-configuration").firstOrNull {
            compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(it))).fetchSemanticsNodes().isNotEmpty()
        }
        // isDisplayed accepts a sliver. At 200% in short landscape, the full
        // control's center can still be behind the IME or its clipped parent.
        // Reveal both scroll owners before tapping a real visible hit region.
        val minimumHitHeight = 48f * context.resources.displayMetrics.density
        if (viewport != null) {
            revealViewport(viewport, minimumHitHeight)
            node.performScrollTo()
            revealViewport(viewport, minimumHitHeight)
        } else if (!node.isDisplayed()) node.performScrollTo()
        compose.waitForIdle()
        val target = node.fetchSemanticsNode()
        val visible = target.boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val point = visible.center
        val taps = record.optJSONArray("pointer_taps") ?: JSONArray().also { record.put("pointer_taps", it) }
        taps.put(JSONObject().put("tag", tag).put("viewport", viewport)
            .put("visible_bounds", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
            .put("control_size", JSONArray(listOf(target.size.width, target.size.height)))
            .put("point", JSONArray(listOf(point.x, point.y))))
        assertTrue("A pointer target needs a useful visible hit region", visible.width > 1 &&
            visible.height + 1 >= minOf(target.size.height.toFloat(), minimumHitHeight) && root.contains(point))
        compose.onRoot().performTouchInput { click(point - root.topLeft) }
        compose.waitForIdle()
        if (target.config.contains(SemanticsProperties.EditableText)) {
            compose.waitUntil(5000) { node.fetchSemanticsNode().config[SemanticsProperties.Focused] }
            node.assertIsFocused()
        }
    }
    private fun openChoice() {
        // Both intermediate raw-value and repaired readable-label apps expose
        // this field tag. The selected item must subsequently change in JS.
        if (compose.onAllNodesWithTag("extension-choice-choice").fetchSemanticsNodes().isNotEmpty()) touch("extension-choice-choice")
        else compose.onNodeWithText("internal-layout-two", useUnmergedTree = true).performTouchInput { click(center) }
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
    }
    private fun firstChoice() = compose.onNode(hasText("Readable first layout") and hasAnyAncestor(hasTestTag("inline-menu")), useUnmergedTree = true)
    private fun engine(): WebView {
        var engine: WebView? = null
        compose.activityRule.scenario.onActivity { activity ->
            fun visit(view: android.view.View) {
                if (view is WebView) { assertNull(engine); engine = view }
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            visit(activity.window.decorView)
        }
        return requireNotNull(engine)
    }
    private fun probe(): JSONObject {
        val engine = engine()
        val result = AtomicReference<String>()
        compose.activityRule.scenario.onActivity { requireNotNull(engine).evaluateJavascript("JSON.stringify(globalThis.__formProbe)") { result.set(it) } }
        compose.waitUntil(5000) { result.get() != null }
        return JSONObject(JSONArray("[${result.get()}]").getString(0))
    }
    @Test fun choosingDropdownItemSubmitsItsExactValue() {
        launch("choices"); openChoice(); capture("choice-menu")
        firstChoice().performTouchInput { click(center) }
        compose.waitUntil(5000) { field("Form.Dropdown", "choice").props().optString("value") == "internal-layout-one" }
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        compose.onNodeWithText("Readable first layout", useUnmergedTree = true).assertIsDisplayed()
        capture("selected-choice"); record.put("selected_exact_value", "internal-layout-one").put("passed", true)
    }
    @Test fun dismissedDropdownCallbackCannotSelectAfterReopen() {
        launch("choices"); openChoice()
        val row = compose.onNode(hasClickAction() and hasText("Readable first layout") and hasAnyAncestor(hasTestTag("inline-menu")))
        val oldClick = requireNotNull(row.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = probe()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("inline-menu").assertDoesNotExist(); openChoice()
        compose.activityRule.scenario.onActivity { assertTrue(oldClick()) }
        compose.waitForIdle()
        assertEquals(before.toString(), probe().toString())
        assertEquals("internal-layout-two", field("Form.Dropdown", "choice").props().optString("value"))
        compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        capture("old-opening-rejected"); firstChoice().performTouchInput { click(center) }
        compose.waitUntil(5000) { field("Form.Dropdown", "choice").props().optString("value") == "internal-layout-one" }
        record.put("retained_opening_callback_rejected", true).put("fresh_opening_callback_works", true).put("passed", true)
    }
    private fun queuedField(transition: String) {
        launch("focus")
        val setText = requireNotNull(compose.onNodeWithTag("extension-field-name").fetchSemanticsNode().config[SemanticsActions.SetText].action)
        val before = probe()
        compose.activityRule.scenario.onActivity {
            val tree = catalog.runtimeTree; val epoch = catalog.launchEpoch
            try {
                when (transition) {
                    "root-missing" -> catalog.runtimeTree = null
                    "new-session" -> catalog.launchEpoch++
                    "replaced-field" -> {
                        val replacement = JSONObject(tree.toString())
                        requireNotNull(activeRoot(replacement)).nodes("Form.TextField").single().put("id", "replacement-native-field")
                        catalog.runtimeTree = replacement
                    }
                    else -> error("Unknown transition")
                }
                assertTrue(setText(AnnotatedString("Owned queued value")))
            } finally { catalog.runtimeTree = tree; catalog.launchEpoch = epoch }
        }
        val after = probe(); assertEquals(before.toString(), after.toString())
        assertEquals("", field("Form.TextField", "name").props().optString("value"))
        record.put("controlled_native_transition", transition).put("scope_detail", "Retained SetText before a Compose frame; native state restored in the same turn to inspect the original JS dispatch probe")
            .put("probe_before", before).put("probe_after", after)
        capture("queued-field-rejected"); record.put("passed", true)
    }
    @Test fun queuedTextCannotRunWithoutRoot() = queuedField("root-missing")
    @Test fun queuedTextCannotRunInReplacementSession() = queuedField("new-session")
    @Test fun queuedTextCannotRunAfterFieldReplacement() = queuedField("replaced-field")

    private fun queuedChoice(transition: String) {
        launch("choices"); openChoice()
        val row = compose.onNode(hasClickAction() and hasText("Readable first layout") and hasAnyAncestor(hasTestTag("inline-menu")))
        val click = requireNotNull(row.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = probe()
        compose.activityRule.scenario.onActivity {
            val tree = catalog.runtimeTree; val epoch = catalog.launchEpoch; val running = catalog.running
            val ready = catalog.runtimeReady; val status = catalog.runtimeStatus; val savedError = catalog.runtimeError
            val arguments = catalog.arguments; val depth = catalog.navigationDepth
            try {
                when (transition) {
                    "root-missing" -> catalog.runtimeTree = null
                    "changed-option" -> {
                        val replacement = JSONObject(tree.toString())
                        requireNotNull(activeRoot(replacement)).nodes("Form.Dropdown.Item").first().props().put("value", "replacement-option-value")
                        catalog.runtimeTree = replacement
                    }
                    "closed" -> catalog.close()
                    else -> error("Unknown choice transition")
                }
                assertTrue(click())
            } finally {
                catalog.runtimeTree = tree; catalog.launchEpoch = epoch; catalog.running = running
                catalog.runtimeReady = ready; catalog.runtimeStatus = status; catalog.runtimeError = savedError
                catalog.arguments = arguments; catalog.navigationDepth = depth
            }
        }
        val after = probe(); assertEquals(before.toString(), after.toString())
        assertEquals("internal-layout-two", field("Form.Dropdown", "choice").props().optString("value"))
        record.put("controlled_native_transition", transition).put("scope_detail", "Retained menu callback before a Compose frame; native state restored in the same turn to inspect the original JS dispatch probe")
            .put("probe_before", before).put("probe_after", after)
        capture("queued-choice-rejected"); record.put("passed", true)
    }
    @Test fun queuedChoiceCannotRunWithoutRoot() = queuedChoice("root-missing")
    @Test fun queuedChoiceCannotRunAfterOptionMeaningChanges() = queuedChoice("changed-option")
    @Test fun queuedChoiceCannotRunAfterClose() = queuedChoice("closed")

    @Test fun unavailableEmptyAndDuplicateChoicesKeepTheirExactValues() {
        launch("edges")
        compose.onNodeWithTag("extension-choice-unknown").performScrollTo()
        compose.onNodeWithText("Selection unavailable", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("owned-unavailable-internal-value", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("owned-unavailable-internal-value", field("Form.Dropdown", "unknown").props().getString("value"))
        compose.onNodeWithTag("extension-choice-empty").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("No choices available", useUnmergedTree = true).assertIsDisplayed()
        touch("extension-choice-duplicates")
        val dropdown = field("Form.Dropdown", "duplicates")
        val option = dropdown.nodes("Form.Dropdown.Item").single { it.props().optString("value") == "duplicate-one" }
        compose.onAllNodesWithText("Same readable title", useUnmergedTree = true).assertCountEquals(3) // closed title + two menu rows
        val tag = "form-option-${dropdown.optString("id")}-${option.optString("id")}-duplicate-one"
        compose.onNodeWithTag(tag).performTouchInput { click(center) }
        compose.waitUntil(5000) { field("Form.Dropdown", "duplicates").props().optString("value") == "duplicate-one" }
        compose.onNodeWithTag("extension-checkbox-label-only").performScrollTo()
        compose.onAllNodesWithText("Owned label without a duplicated caption", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Owned label without a duplicated caption", useUnmergedTree = true).performTouchInput { click(center) }
        compose.waitUntil(5000) { field("Form.Checkbox", "label-only").props().optBoolean("value") }
        capture("edge-choices")
        record.put("missing_selection_preserved", true).put("empty_choices_disabled", true).put("duplicate_title_exact_value", "duplicate-one").put("passed", true)
    }

    @Test fun nativeConfigurationEditsValidateCancelAndRun() {
        launch("configuration")
        val prefs = context.getSharedPreferences("extension-${ownedExtension.listing.id}", Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
        touch("run-extension-setup")
        compose.onNodeWithTag("extension-configuration-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("extension-field-error-argument-input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("extension-field-error-preference-account").performScrollTo().assertIsDisplayed()
        inspectTextLines("extension-configuration", listOf(
            compose.onNodeWithTag("argument-title-input"), compose.onNodeWithTag("preference-title-account"),
            compose.onNodeWithTag("extension-field-error-argument-input"), compose.onNodeWithTag("extension-field-error-preference-account"),
            compose.onNodeWithTag("extension-field-info-preference-account"), compose.onNodeWithTag("extension-configuration-error")
        ), "configuration-required")
        compose.onNodeWithTag("argument-input").performScrollTo().performTextReplacement("Owned canceled argument")
        compose.onNodeWithTag("preference-account").performScrollTo().performTextReplacement("Owned canceled account")
        assertTrue("Editing a draft must not save preferences", prefs.all.isEmpty())
        touch("extension-back")
        assertNull(compose.activity.store.dialog); assertTrue(prefs.all.isEmpty())
        compose.activityRule.scenario.onActivity { it.store.openExtension(ownedExtension, ownedExtension.commands.single()) }
        compose.onNodeWithTag("argument-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("preference-account").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        val argument = "Owned café é 日本 🌱"; val account = "Owned account 日本"
        compose.onNodeWithTag("argument-input").performScrollTo().performTextReplacement(argument)
        compose.onNodeWithTag("preference-account").performScrollTo().performTextReplacement(account)
        compose.onNodeWithText(checkboxLabel, useUnmergedTree = true).performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithTag("preference-checkbox-enabled").assertIsOn()
        touch("preference-choice-layout")
        firstChoice().performTouchInput { click(center) }
        assertTrue("Choices are still a draft until Run", prefs.all.isEmpty())
        capture("configuration-edited")
        touch("run-extension-setup")
        compose.waitUntil(15000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.optString("type") == "Detail" }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        val result = JSONObject(requireNotNull(activeRoot(catalog.runtimeTree)).props().getString("markdown"))
        assertEquals(argument, result.getString("argument")); assertEquals(account, result.getString("account"))
        assertTrue(result.getBoolean("enabled")); assertEquals("internal-layout-one", result.getString("layout"))
        val saved = JSONObject(prefs.getString("preferences", "{}")!!)
        assertEquals(account, saved.getString("account")); assertTrue(saved.getBoolean("enabled")); assertEquals("internal-layout-one", saved.getString("layout"))
        capture("configuration-destination")
        touch("extension-back"); assertNull(compose.activity.store.dialog)
        compose.onNodeWithTag("search").assertTextEquals(caller)
        record.put("configuration_method", "Native required errors, actual argument/preference edits, label tap and menu choice; Cancel discards, explicit Run persists and starts the exact owned values")
            .put("passed", true)
    }

    @Test fun replacedConfigurationRejectsQueuedEditsRunAndBack() {
        launch("configuration")
        val account = compose.onNodeWithTag("preference-account")
        val setText = requireNotNull(account.fetchSemanticsNode().config[SemanticsActions.SetText].action)
        val run = requireNotNull(compose.onNodeWithTag("run-extension-setup").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val back = requireNotNull(compose.onNodeWithTag("extension-back").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val prefs = context.getSharedPreferences("extension-${ownedExtension.listing.id}", Context.MODE_PRIVATE)
        compose.activityRule.scenario.onActivity {
            val epoch = catalog.launchEpoch; val running = catalog.running
            try {
                catalog.launchEpoch++
                assertTrue(setText(AnnotatedString("Owned stale account"))); assertTrue(run()); assertTrue(back())
                assertEquals("extension-setup", it.store.dialog); assertSame(running, catalog.running)
            } finally { catalog.launchEpoch = epoch }
        }
        account.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))); assertTrue(prefs.all.isEmpty())
        capture("stale-configuration-rejected")
        record.put("controlled_native_transition", "Changed launch epoch before queued native edit, Run and Back; restored before a Compose frame")
            .put("queued_edits_run_back_rejected", true).put("passed", true)
    }

    private fun evaluateOwned(script: String) {
        val engine = engine()
        val done = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { requireNotNull(engine).evaluateJavascript(script) { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
    }
    @Test fun allFieldKindsKeepFocusValuesCompositionAndMasking() {
        launch("all-fields")
        touch("extension-field-name")
        val original = "Owned café é 日本 🌱"
        compose.onNodeWithTag("extension-field-name").performTextReplacement(original)
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == original }
        compose.onNodeWithTag("extension-field-name").performTextInputSelection(TextRange(2, 5))
        evaluateOwned("globalThis.__bumpOwnedForm();true")
        compose.onNodeWithTag("extension-field-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        assertEquals(1, probe().getJSONObject("focus").getInt("name"))
        // The controlled InputConnection remains the editor input route. Hide
        // Gboard first so its separate live connection cannot finish the
        // synthetic composing region between the two asynchronous phases.
        ime(false)
        var input: android.view.inputmethod.InputConnection? = null
        compose.activityRule.scenario.onActivity {
            input = requireNotNull(it.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(input!!.setSelection(0, 0)); assertTrue(input!!.setComposingText("日本", 1))
        }
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == "日本$original" }
        // Complete composition after the real JS -> native value round trip.
        // If recomposition discarded its range, this would append instead.
        compose.activityRule.scenario.onActivity { assertTrue(input!!.commitText("日本語", 1)); assertTrue(input!!.finishComposingText()) }
        val composed = "日本語$original"
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == composed }
        compose.onNodeWithTag("extension-field-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        touch("extension-field-password")
        val password = "owned-" + UUID.randomUUID().toString(); sensitiveValue = password
        compose.onNodeWithTag("extension-field-password").performTextReplacement(password)
        compose.waitUntil(5000) { field("Form.PasswordField", "password").props().optString("value") == password }
        assertTrue("Password input must retain masking semantics", compose.onNodeWithTag("extension-field-password").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        capture("masked-password")
        touch("extension-field-area")
        val area = "Owned multiline café é\n日本 🌱"
        compose.onNodeWithTag("extension-field-area").performTextReplacement(area)
        compose.waitUntil(5000) { field("Form.TextArea", "area").props().optString("value") == area }
        touch("extension-field-uncontrolled")
        val uncontrolled = "Owned uncontrolled 日本 🌱"
        compose.onNodeWithTag("extension-field-uncontrolled").performTextReplacement(uncontrolled)
        compose.waitUntil(5000) { field("Form.TextField", "uncontrolled").props().optString("value") == uncontrolled }
        evaluateOwned("globalThis.__bumpOwnedForm();true")
        compose.onNodeWithTag("extension-field-uncontrolled").assertTextEquals(uncontrolled)
        touch("extension-checkbox-enabled")
        compose.waitUntil(5000) { field("Form.Checkbox", "enabled").props().optBoolean("value") }
        openChoice()
        compose.waitUntil(5000) { probe().getJSONObject("focus").optInt("choice") >= 1 && probe().getJSONObject("blur").optInt("enabled") == 1 }
        val observed = probe()
        listOf("name", "password", "area", "uncontrolled", "enabled").forEach { id ->
            assertEquals("One ordinary focus for $id", 1, observed.getJSONObject("focus").getInt(id))
            assertEquals("One ordinary blur for $id", 1, observed.getJSONObject("blur").getInt(id))
        }
        assertEquals(composed.length, observed.getJSONObject("blurLengths").getInt("name"))
        assertEquals(password.length, observed.getJSONObject("blurLengths").getInt("password"))
        assertEquals(area.length, observed.getJSONObject("blurLengths").getInt("area"))
        assertEquals(uncontrolled.length, observed.getJSONObject("blurLengths").getInt("uncontrolled"))
        firstChoice().performTouchInput { click(center) }
        compose.waitUntil(5000) { field("Form.Dropdown", "choice").props().optString("value") == "internal-layout-one" }
        capture("all-fields")
        record.put("focus_probe", observed).put("composition_survived_javascript_round_trip", true)
            .put("composition_method", "Controlled Android InputConnection with Gboard hidden across asynchronous JS/native phases; not phonetic typing gestures in a live OEM IME")
            .put("controlled_selection_preserved", true).put("password_masking_and_value_delivery", true).put("passed", true)
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun ime(show: Boolean) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        compose.activityRule.scenario.onActivity {
            val controller = androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
            if (show) controller.show(WindowInsetsCompat.Type.ime()) else controller.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10000) { imeVisible() == show }; compose.waitForIdle()
        if (!show) {
            // Insets become hidden before the IME's final connection callbacks
            // and window animation necessarily finish. Observe native window
            // removal before constructing a separate controlled connection.
            device.waitForIdle(2000)
            val automation = testAutomation()
            val flags = automation.serviceInfo.flags
            try {
                automation.serviceInfo = automation.serviceInfo.apply {
                    this.flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                compose.waitUntil(10000) {
                    automation.clearCacheForTest()
                    !imeVisible() && automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            } finally { automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
            record.put("hidden_ime_window_absence_confirmed", true)
        }
    }
    private fun fullBounds(interaction: SemanticsNodeInteraction): Rect {
        val node = interaction.fetchSemanticsNode()
        return compose.runOnUiThread {
            val coordinates = node.layoutInfo.coordinates; val origin = coordinates.localToRoot(Offset.Zero)
            Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        }
    }
    private fun drag(viewport: SemanticsNodeInteraction, direction: Int) {
        viewport.performTouchInput {
            down(center); moveTo(Offset(center.x, center.y + direction * height * .25f), delayMillis = 300)
            advanceEventTime(200); up()
        }
        compose.waitForIdle()
    }
    private fun revealViewport(viewport: String, lineHeight: Float) {
        val body = compose.onNodeWithTag(viewport)
        val route = compose.onNode(hasTestTag("palette-content-scroll") and hasAnyAncestor(hasTestTag("extension-palette")), useUnmergedTree = true)
        val gestures = record.optJSONArray("viewport_reveal_gestures") ?: JSONArray().also { record.put("viewport_reveal_gestures", it) }
        repeat(40) {
            val semantic = body.fetchSemanticsNode()
            val bounds = compose.runOnUiThread {
                val origin = semantic.positionInRoot
                Rect(origin.x, origin.y, origin.x + semantic.size.width, origin.y + semantic.size.height)
            }
            val visible = semantic.boundsInRoot
            val outer = route.fetchSemanticsNode().boundsInRoot
            val target = minOf(bounds.height, outer.height, lineHeight * 3 + 2)
            if (visible.height + 1 >= target) return
            val upward = bounds.top > outer.top
            // Start in the visible chrome outside the nested Form scroller.
            // Its content/padding coordinates can lie inside that scroller,
            // causing the inner view to consume a parent-reveal gesture.
            val startY = if (upward) (minOf(bounds.top, outer.bottom) - outer.top) * .75f
                else (maxOf(bounds.bottom, outer.top) - outer.top + outer.height) * .5f
            val y = startY.coerceIn(2f, outer.height - 2f)
            // A captured drag may continue into the surrounding activity.
            // Keeping it artificially inside a nearly-hidden header can make
            // the gesture shorter than Android's touch slop.
            val endY = if (upward) (y - outer.height * .5f).coerceAtLeast(1f - outer.top)
                else (y + outer.height * .5f).coerceAtMost(context.resources.displayMetrics.heightPixels - outer.top - 1f)
            gestures.put(JSONObject().put("viewport", viewport).put("body", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                .put("visible_height", visible.height).put("target_height", target).put("outer", JSONArray(listOf(outer.left, outer.top, outer.right, outer.bottom)))
                .put("start_y", y).put("end_y", endY).put("touch_slop_px", android.view.ViewConfiguration.get(context).scaledTouchSlop))
            route.performTouchInput { down(Offset(center.x, y)); moveTo(Offset(center.x, endY), delayMillis = 300); advanceEventTime(200); up() }
            compose.waitForIdle()
        }
        fail("Pointer scrolling could not reveal a text-line viewport")
    }
    private fun inspectTextLines(viewport: String, textNodes: List<SemanticsNodeInteraction>, phase: String) {
        val layouts = textNodes.map { node ->
            val results = mutableListOf<TextLayoutResult>(); node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.single().also { layout ->
                assertTrue(layout.lineCount > 0); assertTrue("Text needs its complete natural height", fullBounds(node).height + 1 >= layout.size.height)
                assertTrue("Text must not be ellipsized", (0 until layout.lineCount).none(layout::isLineEllipsized))
            }
        }
        val lineHeight = layouts.maxOf { text -> (0 until text.lineCount).maxOf { text.getLineBottom(it) - text.getLineTop(it) } }
        if (viewport != "inline-menu-choices") revealViewport(viewport, lineHeight)
        val body = compose.onNodeWithTag(viewport)
        var reset = 0
        while (reset++ < 120) {
            val before = fullBounds(textNodes.first()).top
            drag(body, 1)
            if (kotlin.math.abs(fullBounds(textNodes.first()).top - before) < 1) break
        }
        // Resetting the inner view at its leading edge can also scroll its
        // parent back to the header. Reveal the bounded body again before
        // measuring lines or starting short inner-view gestures.
        if (viewport != "inline-menu-choices") revealViewport(viewport, lineHeight)
        val witnessed = layouts.map { BooleanArray(it.lineCount) }; val steps = JSONArray()
        capture("$phase-top")
        var attempts = 0
        while (attempts++ < 200) {
            val visible = body.fetchSemanticsNode().boundsInRoot
            assertTrue("A complete text line needs a visible viewport", visible.height + 1 >= lineHeight)
            val step = JSONObject().put("viewport", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
            textNodes.forEachIndexed { index, node ->
                val bounds = fullBounds(node); val layout = layouts[index]
                step.put("text_$index", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                for (line in 0 until layout.lineCount) {
                    val left = bounds.left + layout.getLineLeft(line); val right = bounds.left + layout.getLineRight(line)
                    val top = bounds.top + layout.getLineTop(line); val bottom = bounds.top + layout.getLineBottom(line)
                    assertTrue("A text line exceeds viewport width", left >= visible.left - 1 && right <= visible.right + 1)
                    if (top >= visible.top - 1 && bottom <= visible.bottom + 1) witnessed[index][line] = true
                }
                step.put("text_${index}_lines_seen", witnessed[index].count { it })
            }
            steps.put(step)
            if (witnessed.all { it.all { seen -> seen } }) break
            val before = fullBounds(textNodes.first()).top; drag(body, -1)
            if (kotlin.math.abs(fullBounds(textNodes.first()).top - before) < 1) break
        }
        record.put("${phase}_line_probe", JSONObject().put("ime_visible", imeVisible()).put("line_counts", JSONArray(layouts.map { it.lineCount }))
            .put("all_lines_reached", witnessed.all { it.all { seen -> seen } }).put("pointer_steps", steps))
        capture("$phase-bottom")
        assertTrue("Every natural text line must become fully visible through pointer scrolling", witnessed.all { it.all { seen -> seen } })
    }
    private fun touchLastLine(viewport: String, node: SemanticsNodeInteraction) {
        val results = mutableListOf<TextLayoutResult>(); node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single(); val bounds = fullBounds(node); val last = layout.lineCount - 1
        val visible = compose.onNodeWithTag(viewport).fetchSemanticsNode().boundsInRoot
        val x = bounds.left + (layout.getLineLeft(last) + layout.getLineRight(last)) / 2
        val y = bounds.top + (layout.getLineTop(last) + layout.getLineBottom(last)) / 2
        assertTrue("The actual last label line must be visible before its pointer tap", visible.contains(Offset(x, y)))
        compose.onNodeWithTag(viewport).performTouchInput { click(Offset(x - visible.left, y - visible.top)) }
    }
    @Test fun longMessagesAndTouchChoicesStayReadableAcrossImeTransitions() {
        launch("long"); touch("extension-field-name")
        val value = "Owned large-font café é 日本 🌱"
        compose.onNodeWithTag("extension-field-name").performTextReplacement(value)
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == value }
        for ((index, shown) in listOf(false, true, false).withIndex()) {
            ime(shown)
            inspectTextLines("extension-form", listOf(
                compose.onNodeWithTag("extension-field-title-name"), compose.onNodeWithTag("extension-field-error-name"),
                compose.onNodeWithTag("extension-field-info-name"), compose.onNodeWithText(longCheckbox, useUnmergedTree = true)
            ), "long-$index")
            assertEquals(shown, imeVisible())
        }
        val before = probe().getInt("changes")
        touchLastLine("extension-form", compose.onNodeWithText(longCheckbox, useUnmergedTree = true))
        compose.waitUntil(5000) { field("Form.Checkbox", "enabled").props().optBoolean("value") }
        assertEquals(before + 1, probe().getInt("changes"))
        val checkbox = compose.onNodeWithTag("extension-checkbox-enabled").fetchSemanticsNode()
        record.put("checkbox_semantics_height_px", checkbox.size.height).put("density", context.resources.displayMetrics.density)
        assertTrue("Checkbox row must provide a 48dp touch target", checkbox.size.height + 1 >= 48 * context.resources.displayMetrics.density)
        openChoice(); compose.onNodeWithTag("inline-menu-choices").performScrollToIndex(0)
        inspectTextLines("inline-menu-choices", listOf(compose.onNodeWithText(longOption, useUnmergedTree = true)), "long-option")
        touchLastLine("inline-menu-choices", compose.onNodeWithText(longOption, useUnmergedTree = true))
        compose.waitUntil(5000) { field("Form.Dropdown", "choice").props().optString("value") == "internal-layout-one" }
        compose.onNodeWithTag("inline-menu").assertDoesNotExist()
        inspectTextLines("extension-form", listOf(compose.onNodeWithText(longOption, useUnmergedTree = true)), "selected-long-option")
        assertEquals(value, field("Form.TextField", "name").props().optString("value"))
        record.put("checkbox_last_line_pointer_tap_once", true).put("dropdown_last_line_exact_value", true).put("ime_transitions", JSONArray(listOf(false, true, false)))
            .put("passed", true)
    }

    @Test fun frozenJsonFormUsesEditedPreferencesCopiesUnicodeAndReturns() {
        val directory = fixtureDirectory(); val id = directory.name
        val archive = File(context.filesDir, "reviewed-text-fixture-lease/json-format.zip").readBytes()
        assertEquals("fc420406a59251daee0c9af32e216026724631480b3a4e2f5d65d0c507b09a58", hash(archive))
        val extracted = ExtensionArchive.extract(archive, directory)
        assertEquals("bcca05348f11ffc115adc346944dcd78d209958684cb9df44d571e70a99575a0", hash(File(extracted, "index.js").readBytes()))
        val extension = InstalledExtension(StoreExtension(id, "json-format", "Format JSON (owned frozen copy)", "", "Droidcast tests", "test", "", "", "", 6),
            extracted, JSONObject(File(extracted, "package.json").readText()))
        val command = extension.commands.single { it.name == "index" }; ownedExtension = extension
        record.put("scope", "Actual frozen json-format/index bundle in an owned namespace; native preference editing, Form input, View Result, exact Copy and launcher return; not whole-Store certification")
            .put("archive_sha256", hash(archive)).put("command_sha256", hash(File(extracted, "index.js").readBytes())).put("owned_extension_id", id)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, command) }
        compose.waitUntil(20000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        compose.onNodeWithText("Preferences", useUnmergedTree = true).performTouchInput { click(center) }
        val prefs = context.getSharedPreferences("extension-$id", Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
        compose.onNodeWithTag("preference-checkbox-autopaste").performScrollTo().assertIsOff()
        touch("preference-choice-indent")
        compose.onNodeWithText("Spaces: 2", useUnmergedTree = true).performTouchInput { click(center) }
        compose.onNodeWithTag("preference-maxInitialLines").performScrollTo().performTextReplacement("42")
        assertTrue("Native editing stays private until Run", prefs.all.isEmpty())
        capture("json-native-preferences"); touch("run-extension-setup")
        compose.waitUntil(20000) { catalog.runtimeError != null || activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        assertNull(catalog.runtimeError, catalog.runtimeError)
        val saved = JSONObject(prefs.getString("preferences", "{}")!!)
        assertEquals("2", saved.getString("indent")); assertEquals("42", saved.getString("maxInitialLines")); assertFalse(saved.getBoolean("autopaste"))
        val input = " \n\t{\"message\":\"こんにちは 🌱 café é\",\"items\":[1,2]}  \n"
        val output = "{\n  \"message\": \"こんにちは 🌱 café é\",\n  \"items\": [1, 2]\n}"
        touch("extension-field-input"); compose.onNodeWithTag("extension-field-input").performTextReplacement(input)
        compose.waitUntil(5000) { field("Form.TextArea", "input").props().optString("value") == input }
        ime(false); capture("json-form-input")
        val actions = requireNotNull(activeRoot(catalog.runtimeTree)).actions()
        val actionIndex = actions.indexOfFirst { it.props().optString("title") == "View Result" }; assertTrue(actionIndex >= 0)
        touch("extension-actions"); touch("extension-action-$actionIndex")
        compose.waitUntil(15000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Detail" }
        assertEquals("```json\n$output\n```", requireNotNull(activeRoot(catalog.runtimeTree)).props().getString("markdown"))
        assertTrue(catalog.navigationDepth > 1)
        compose.activityRule.scenario.onActivity { assertEquals("Owned Form clipboard sentinel", it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()) }
        capture("json-detail"); touch("extension-primary")
        compose.waitUntil(5000) {
            var matches = false
            compose.activityRule.scenario.onActivity { matches = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() == output }
            matches
        }
        dismissClipboardForEvidence(); capture("json-copied"); touch("extension-back")
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        assertEquals(input, field("Form.TextArea", "input").props().optString("value"))
        touch("extension-back"); assertNull(compose.activity.store.dialog)
        compose.onNodeWithTag("search").assertTextEquals(caller)
        capture("json-launcher-return")
        record.put("native_preferences_edited_not_seeded", true).put("exact_unicode_form_copy", true).put("form_state_preserved_on_back", true)
            .put("launcher_query_preserved", true).put("passed", true)
    }
}
