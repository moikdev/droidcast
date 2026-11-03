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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

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
    private val submitValidationError = "Owned account validation error: correct the name before submitting."
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
            .put("window_soft_input_mode", compose.activity.window.attributes.softInputMode)
            .put("window_soft_input_state", compose.activity.window.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
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
            globalThis.__tinycast.dispatch=function(...args){globalThis.__ownedFormSession=args[0];globalThis.__formProbe.dispatches++;return dispatch.apply(this,args)};
            const hostRender=globalThis.__tinycastHost.render;
            globalThis.__tinycastHost.render=function(...args){
              const probe=globalThis.__formProbe,request=probe.typedGateRequest,result=hostRender.apply(this,args);
              // The Android adapter enqueues its pending-submit drain before
              // returning from render. This witness runs after that drain.
              if(request&&!request.witnessQueued){request.witnessQueued=true;Promise.resolve().then(()=>{
                request.after_host_render_and_submit_drain=true;request.settled_submits=probe.submits;
              })}
              return result;
            };
            function typedFacts(value){
              const date=value instanceof Date,array=Array.isArray(value),valid=date&&!Number.isNaN(value.getTime());
              return {is_date:date,is_null:value===null,is_array:array,array_strings:array&&value.every(item=>typeof item==='string'),
                valid_date:valid,date_iso:valid?value.toISOString():null,
                full_day_helper:typeof api.Form.DatePicker.isFullDay==='function',
                full_day:date&&typeof api.Form.DatePicker.isFullDay==='function'?api.Form.DatePicker.isFullDay(value):null};
            }
            function fieldEvents(id){return {onFocus:e=>{const p=globalThis.__formProbe;p.focus[id]=(p.focus[id]||0)+1;(p.focusFacts??={})[id]=typedFacts(e.target.value)},
              onBlur:e=>{const p=globalThis.__formProbe;p.blur[id]=(p.blur[id]||0)+1;p.blurLengths[id]=typeof e.target.value==='string'?e.target.value.length:0;
                (p.blurFacts??={})[id]=typedFacts(e.target.value);(p.blurValues??={})[id]=e.target.value}}}
            module.exports.default=function(props){
              if(mode==='configuration'){
                const prefs=api.getPreferenceValues();
                return React.createElement(api.Detail,{markdown:JSON.stringify({argument:props.arguments.input,account:prefs.account,enabled:prefs.enabled,layout:prefs.layout})});
              }
              if(mode.startsWith('typed-')){
                const initialDate=mode==='typed-date-dst-gap'?'2024-03-10T06:30:00.000Z':mode==='typed-date-dst-overlap'?'2024-11-03T06:30:12.345Z':'2024-02-29T12:34:00.000Z';
                const [date,setDate]=React.useState(mode==='typed-date-day'?new Date(2024,1,28,12,34):new Date(initialDate));
                const [tags,setTags]=React.useState(mode==='typed-tags-empty'?['missing-owned-value']:['internal-two']),[files,setFiles]=React.useState([]);
                const [revision,bump]=React.useReducer(value=>value+1,0),[dateKey,setDateKey]=React.useState('date');
                const dateRef=React.useRef(null),tagsRef=React.useRef(null);
                const [defaultDate,setDefaultDate]=React.useState('2024-02-29T12:34:00.000Z'),[defaultTags,setDefaultTags]=React.useState(['internal-two']);
                const [options,setOptions]=React.useState(mode==='typed-tags-empty'?[]:[
                  {value:'internal-one',title:mode==='typed-tags-long'?${JSONObject.quote(longTitle)}:'Readable first category'},
                  {value:'internal-two',title:'Readable second category'}]);
                const fileWorkflow=mode.startsWith('typed-file-'),{push}=api.useNavigation();
                const [fileEnabled,setFileEnabled]=React.useState(true);
                globalThis.__ownedTypedFiles={setFileEnabled};
                globalThis.__ownedTyped={setDate,setTags,setOptions,setDateKey,setDefaultDate,setDefaultTags,bump,
                  resetDate:()=>dateRef.current.reset(),resetTags:()=>tagsRef.current.reset(),focusDate:()=>dateRef.current.focus()};
                const [errorId,setErrorId]=React.useState(mode==='typed-file-error-workflow'?'files':mode.startsWith('typed-error-')?mode.slice('typed-error-'.length):null);
                globalThis.__formProbe.submits ??= 0;
                const change=(id,setter)=>value=>{
                  const probe=globalThis.__formProbe;probe.changes++;(probe.typedChanges??={})[id]=typedFacts(value);
                  if(mode!=='typed-'+id+'-reject')setter(mode==='typed-date-transform'&&id==='date'&&value?new Date(value.getTime()+30000):value);
                  if(errorId===id)setErrorId(null);
                  bump();
                };
                const error=id=>errorId===id?'Owned typed field must be corrected before submitting.':undefined;
                const dateKind=mode==='typed-date-day'?api.Form.DatePicker.Type.Date:api.Form.DatePicker.Type.DateTime;
                const bounds=mode==='typed-date-day'?{min:new Date(2024,1,29,23,59),max:new Date(2024,1,29,0,0)}:
                  mode==='typed-date-bounds'?{min:new Date('2024-02-29T12:34:00.000Z'),max:new Date('2024-02-29T12:35:00.000Z')}:
                  mode==='typed-date-inconsistent'?{min:new Date('2024-03-01T00:00:00.000Z'),max:new Date('2024-02-28T00:00:00.000Z')}:{};
                return React.createElement(api.Form,{actions:React.createElement(api.ActionPanel,null,
                  React.createElement(api.Action.SubmitForm,{title:'Inspect owned typed values',onSubmit:values=>{
                    globalThis.__formProbe.submits++;globalThis.__formProbe.submittedValues=values;
                    const types={};
                    for(const id of ['date','tags','files'])types[id]={...typedFacts(values[id]),present:Object.prototype.hasOwnProperty.call(values,id)};
                    globalThis.__formProbe.typedSubmitted=types;
                    if(fileWorkflow){
                      const fs=require('fs');
                      const reads=values.files.map(path=>{
                        const directory=fs.statSync(path).isDirectory(),leaf=directory?path+'/nested.txt':path;
                        return {name:path.split('/').pop(),directory,base64:fs.readFileSync(leaf).toString('base64')};
                      });
                      globalThis.__formProbe.fileReads=reads;
                      push(React.createElement(api.Detail,{markdown:JSON.stringify({outcome:'Owned imported documents',reads})}));
                    }
                  }}))},
                  React.createElement(api.Form.DatePicker,{key:dateKey,ref:dateRef,id:'date',title:'Owned appointment date and time',
                    type:dateKind,...bounds,...(mode==='typed-defaults'?{defaultValue:new Date(defaultDate)}:{value:date}),onChange:change('date',setDate),error:error('date'),
                    info:'Choose an owned calendar date and time by touch.',...fieldEvents('date')}),
                  React.createElement(api.Form.TagPicker,{key:'tags',ref:tagsRef,id:'tags',title:'Owned output categories',
                    ...(mode==='typed-defaults'?{defaultValue:defaultTags}:{value:tags}),onChange:change('tags',setTags),error:error('tags'),...fieldEvents('tags')},
                    ...options.map(option=>React.createElement(api.Form.TagPicker.Item,{key:option.value,...option}))),
                  React.createElement(api.Form.FilePicker,{key:'files',id:'files',title:'Owned input documents',
                    ...(fileWorkflow?{canChooseFiles:fileEnabled&&!mode.includes('folder'),canChooseDirectories:mode.includes('folder'),allowMultipleSelection:mode.includes('multiple')}:{}),
                    value:files,onChange:change('files',setFiles),error:error('files'),...fieldEvents('files')}),
                  React.createElement(api.Form.Description,{key:'scope',text:fileWorkflow?'Owned documents through Android picker, private copies and exact byte inspection. No Store or arbitrary-provider certification.':'Owned typed controls; no Store or provider workflow is certified by this fixture.'}),
                  React.createElement(api.Form.Description,{key:'typed-publication',text:'Typed publication '+revision}));
              }
              if(mode.startsWith('submit-')){
                const [name,setName]=React.useState(mode==='submit-reject'?'OWNED FIXED':'Owned invalid 日本');
                const initialError=['submit-validation','submit-alias','submit-default','submit-long'].includes(mode);
                const [error,setError]=React.useState(initialError?(mode==='submit-long'?${JSONObject.quote(longError)}:${JSONObject.quote(submitValidationError)}):undefined);
                const [actionKey,replaceAction]=React.useReducer(value=>value+1,0);
                const [actionTitle,setTitle]=React.useState('Submit owned values');
                const [errorField,setErrorField]=React.useState('name');
                const {push}=api.useNavigation();
                globalThis.__formProbe.submits ??= 0;
                globalThis.__formProbe.nonSubmitActions ??= 0;
                globalThis.__formProbe.successorSubmits ??= 0;
                globalThis.__formProbe.blurs ??= 0;
                globalThis.__ownedSubmit={setError,replaceAction,setTitle,setErrorField,pushReplacement:()=>push(
                  React.createElement(api.Form,{key:'owned-successor',actions:React.createElement(api.ActionPanel,null,
                    React.createElement(api.Action.SubmitForm,{title:'Submit successor values',onSubmit:values=>{
                      globalThis.__formProbe.successorSubmits++;globalThis.__formProbe.submittedValues=values;
                    }}))},React.createElement(api.Form.TextField,{id:'successor',title:'Owned successor name',defaultValue:'Owned successor 日本'})))};
                const errorFor=id=>mode==='submit-fields'?(errorField===id?${JSONObject.quote(submitValidationError)}:undefined):(id==='name'?error:undefined);
                const Submit=mode==='submit-alias'?api.SubmitFormAction:mode==='submit-default'?api.default.Action.SubmitForm:api.Action.SubmitForm;
                const fields=[React.createElement(api.Form.TextField,{key:'name',id:'name',title:mode==='submit-long'?${JSONObject.quote(longTitle)}:'Owned account name',
                  ...(mode==='submit-uncontrolled'?{defaultValue:'Owned default'}:{value:name}),error:errorFor('name'),
                  info:mode==='submit-long'?${JSONObject.quote(longInfo)}:undefined,
                  onChange:value=>{if(mode!=='submit-reject')setName(mode==='submit-transform'?value.toUpperCase():value);setError(undefined);if(mode==='submit-fields')setErrorField(null)},
                  onBlur:event=>{globalThis.__formProbe.blurs++;if(!event.target.value.trim()){
                    if(mode==='submit-fields')setErrorField('name');else setError(${JSONObject.quote(submitValidationError)});
                  }}}),
                  React.createElement(api.Form.TextArea,{key:'notes',id:'notes',title:'Owned notes',defaultValue:'Owned 日本 café é 🌱',error:errorFor('notes')})];
                if(mode==='submit-fields')fields.push(
                  React.createElement(api.Form.PasswordField,{key:'secret',id:'secret',title:'Owned empty password',defaultValue:'',error:errorFor('secret')}),
                  React.createElement(api.Form.Checkbox,{key:'enabled',id:'enabled',label:'Owned output enabled',defaultValue:false,error:errorFor('enabled')}),
                  React.createElement(api.Form.Dropdown,{key:'choice',id:'choice',title:'Owned layout',defaultValue:'one',error:errorFor('choice')},
                    React.createElement(api.Form.Dropdown.Item,{value:'one',title:'Owned first layout'})));
                return React.createElement(api.Form,{actions:React.createElement(api.ActionPanel,null,
                  React.createElement(Submit,{key:'owned-submit-'+actionKey,title:actionTitle,onSubmit:values=>{
                    globalThis.__formProbe.submits++;globalThis.__formProbe.submittedValues=values;
                    push(React.createElement(api.Detail,{markdown:JSON.stringify({outcome:'Owned SubmitForm callback reached',values})}));
                  }}),
                  React.createElement(api.Action,{title:'Inspect owned validation',onAction:()=>{globalThis.__formProbe.nonSubmitActions++}}))},...fields);
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
        val manifest = if (mode == "configuration") JSONObject("""{"commands":[{"name":"form","title":"Owned Form configuration","mode":"view","arguments":[{"name":"input","type":"text","placeholder":"Enter owned argument","required":true}],"preferences":[{"name":"account","title":"Owned account name","type":"textfield","required":true,"description":"Enter an owned value. This configuration is only saved when Run is selected."},{"name":"secret","title":"Owned optional password","type":"password","required":false},{"name":"enabled","title":"Owned output option","type":"checkbox","label":"$checkboxLabel","default":false},{"name":"layout","title":"Owned layout choice","type":"dropdown","default":"internal-layout-two","data":[{"value":"internal-layout-one","title":"Readable first layout"},{"value":"internal-layout-two","title":"$choiceTitle"}]}]}]}""")
            else JSONObject("""{"commands":[{"name":"form","title":"Owned Form controls","mode":"view"}]}""")
        val extension = InstalledExtension(StoreExtension(id, "form-fixture", "Owned Form probe", "", "Droidcast tests", "test", "", "", "", 1), directory, manifest)
        ownedExtension = extension
        record.put("fixture_mode", mode).put("fixture_command_sha256", hash(script.toByteArray(Charsets.UTF_8)))
            .put("fixture_manifest_sha256", hash(manifest.toString().toByteArray(Charsets.UTF_8))).put("owned_extension_id", id)
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
        val frame = JSONObject().put("scope", record.getString("scope")).put("phase", phase)
            .put("font_scale", context.resources.configuration.fontScale).put("launch_epoch", epoch)
            .put("ime_visible", ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true)
            .put("synchronization", "Committed semantics and two display frames; hierarchy before PNG; stable runtime tree")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val dialogs = JSONArray()
            compose.activityRule.scenario.onActivity {
                fun visit(view: android.view.View) {
                    if (view is androidx.compose.ui.window.DialogWindowProvider) {
                        val window = view.window
                        val insets = ViewCompat.getRootWindowInsets(window.decorView)
                        dialogs.put(JSONObject().put("dim_enabled", (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0)
                            .put("dim_amount", window.attributes.dimAmount.toDouble())
                            .put("ime_visible", insets?.isVisible(WindowInsetsCompat.Type.ime()) == true)
                            .put("width_px", window.decorView.width).put("height_px", window.decorView.height))
                    }
                    if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            }
            frame.put("process_dialog_windows", dialogs)
        }
        ownedPublication()?.let { publication ->
            val composed = compose.onNodeWithText(publication.marker, useUnmergedTree = true).fetchSemanticsNode()
            val text = composed.config[SemanticsProperties.Text].joinToString("") { it.text }
            assertEquals(publication.marker, text)
            val nameEditor = compose.onNodeWithTag("extension-field-name").fetchSemanticsNode()
            val selection = nameEditor.config[SemanticsProperties.TextSelectionRange]
            frame.put("owned_publication", publication.json().put("composed_marker", text).put("composed_semantics_id", composed.id))
                .put("name_editor_observation", JSONObject().put("selection_start", selection.start).put("selection_end", selection.end)
                    .put("text_length", nameEditor.config[SemanticsProperties.EditableText].text.length))
        }
        assertEquals(epoch, catalog.launchEpoch); assertTrue("Runtime changed around the frame receipt", tree == catalog.runtimeTree?.toString())
        File(evidence, "$name-frame.json").writeText(frame.toString(2))
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
        inspectTextLines("extension-form", listOf(compose.onNodeWithTag("extension-field-error-name")), "blur-validation-error")
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
        compose.waitForIdle()
        // Observe the screen's actual IME policy. A newly focused menu search
        // may change visibility; its previous screen's state is not a request.
        awaitImeGeometry()
        val node = compose.onNodeWithTag(tag)
        val viewport = listOf("extension-form", "extension-configuration").firstOrNull {
            compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag(it))).fetchSemanticsNodes().isNotEmpty()
        }
        // isDisplayed accepts a sliver. At 200% in short landscape, the full
        // control's center can still be behind the IME or its clipped parent.
        // Reveal both scroll owners before tapping a real visible hit region.
        val minimumHitHeight = 48f * context.resources.displayMetrics.density
        if (viewport != null) {
            revealViewport(viewport, minimumHitHeight / 3)
            node.performScrollTo()
            // ScrollTo uses the inner scroller's full layout height; a second
            // outer scroller can clip that height. Finish with real pointer
            // scrolling until the target itself has a visible hit region.
            for (attempt in 0 until 60) {
                revealViewport(viewport, minimumHitHeight / 3)
                val current = node.fetchSemanticsNode()
                if (current.boundsInRoot.height + 1 >= minOf(current.size.height.toFloat(), minimumHitHeight)) break
                val body = compose.onNodeWithTag(viewport)
                val visible = body.fetchSemanticsNode().boundsInRoot
                val origin = compose.runOnUiThread { current.positionInRoot }
                drag(body, if (origin.y < visible.top) 1 else -1)
            }
        } else if (compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty()) {
            // The palette header scrolls too. A two-pixel visible Back button
            // is not a useful pointer target even though isDisplayed is true.
            node.performScrollTo()
        }
        compose.waitForIdle()
        val target = node.fetchSemanticsNode()
        val visible = target.boundsInRoot
        // Dialog bounds use a different local origin. Inject through the root
        // that owns this exact target, rather than the launcher Activity root.
        val targetRoot = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
        val rootNode = targetRoot.fetchSemanticsNode()
        val root = rootNode.boundsInRoot
        val point = visible.center
        val taps = record.optJSONArray("pointer_taps") ?: JSONArray().also { record.put("pointer_taps", it) }
        taps.put(JSONObject().put("tag", tag).put("viewport", viewport)
            .put("injection_owner", "Compose root containing the exact target tag").put("owner_root_semantics_id", rootNode.id)
            .put("visible_bounds", JSONArray(listOf(visible.left, visible.top, visible.right, visible.bottom)))
            .put("control_size", JSONArray(listOf(target.size.width, target.size.height)))
            .put("point", JSONArray(listOf(point.x, point.y))))
        assertTrue("A pointer target needs a useful visible hit region", visible.width > 1 &&
            visible.height + 1 >= minOf(target.size.height.toFloat(), minimumHitHeight) && root.contains(point))
        targetRoot.performTouchInput { click(point - root.topLeft) }
        compose.waitForIdle()
        if (target.config.contains(SemanticsProperties.EditableText)) {
            compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()?.let {
                it.config.contains(SemanticsProperties.Focused) && it.config[SemanticsProperties.Focused]
            } == true }
            node.assertIsFocused()
            // A pointer focus follows Android's keyboard policy. Explicit
            // show/hide scenarios call ime() themselves; do not add a second
            // keyboard request to unrelated pointer-only workflows.
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(2000)
            awaitImeGeometry()
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
        touch("preference-secret")
        assertPasswordEditorMetadata("preference-secret")
        capture("configuration-password-editor")
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

    private data class OwnedPublication(val rootId: String, val counter: Int) {
        val marker get() = "Owned render $counter"
        fun json() = JSONObject().put("root_id", rootId).put("counter", counter).put("marker", marker)
    }
    private fun ownedPublication(): OwnedPublication? {
        val root = activeRoot(catalog.runtimeTree)?.takeIf { it.optString("type") == "Form" } ?: return null
        val counters = root.nodes("Form.Description").mapNotNull {
            Regex("Owned render ([0-9]+)").matchEntire(it.props().optString("text"))?.groupValues?.get(1)?.toIntOrNull()
        }
        return counters.singleOrNull()?.let { OwnedPublication(root.optString("id"), it) }
    }
    private fun advanceOwnedPublication(phase: String): OwnedPublication {
        val before = requireNotNull(ownedPublication()) { "Owned native publication marker is missing or ambiguous" }
        val expected = before.counter + 1
        evaluateOwned("globalThis.__bumpOwnedForm();true")
        // JavaScript evaluation completion does not acknowledge the async
        // AndroidHost.render delivery. Observe its actual native tree first.
        compose.waitUntil(5000) {
            ownedPublication()?.let { it.rootId == before.rootId && it.counter == expected } == true
        }
        compose.waitForIdle()
        val marker = "Owned render $expected"
        val composed = compose.onNodeWithText(marker, useUnmergedTree = true).fetchSemanticsNode()
        val composedText = composed.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertEquals(marker, composedText)
        val observed = requireNotNull(ownedPublication())
        assertEquals(before.rootId, observed.rootId); assertEquals(expected, observed.counter)
        val witnesses = record.optJSONArray("publication_witnesses") ?: JSONArray().also { record.put("publication_witnesses", it) }
        witnesses.put(JSONObject().put("phase", phase).put("before_counter", before.counter).put("expected_counter", expected)
            .put("observed_native", observed.json()).put("composed_marker", composedText).put("composed_semantics_id", composed.id)
            .put("native_delivery_and_compose_consumption_observed", true))
        return observed
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
        // Programmatic selection and the following controlled InputConnection
        // own this editor with Gboard hidden. A separate real-pointer test
        // covers selection while the live IME remains connected.
        ime(false)
        compose.onNodeWithTag("extension-field-name").performTextInputSelection(TextRange(2, 5))
        compose.onNodeWithTag("extension-field-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        val selectedBefore = compose.onNodeWithTag("extension-field-name").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        record.put("controlled_selection_before_publication", JSONArray(listOf(selectedBefore.start, selectedBefore.end)))
        advanceOwnedPublication("controlled-selection")
        val nameEditor = compose.onNodeWithTag("extension-field-name")
        nameEditor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        nameEditor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(original)))
        assertEquals(original, field("Form.TextField", "name").props().optString("value")); assertFalse(imeVisible())
        capture("controlled-selection-published")
        val selectedAfter = nameEditor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(2, 5), selectedAfter)
        record.put("controlled_selection_after_publication", JSONArray(listOf(selectedAfter.start, selectedAfter.end)))
            .put("selection_method", "Exact programmatic range before/after unrelated React publication with Gboard hidden; live-IME selection is a separate pointer test")
        assertEquals(1, probe().getJSONObject("focus").getInt("name"))
        var input: android.view.inputmethod.InputConnection? = null
        val compositionSteps = JSONArray().also { record.put("composition_steps", it) }
        fun observeComposition(phase: String) {
            compose.activityRule.scenario.onActivity {
                val extracted = input?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
                    flags = android.view.inputmethod.InputConnection.GET_TEXT_WITH_STYLES
                }, 0)
                val styled = extracted?.text as? android.text.Spanned
                val spans = if (styled != null) styled.getSpans(0, styled.length, Any::class.java).filter {
                    styled.getSpanFlags(it) and android.text.Spanned.SPAN_COMPOSING != 0
                } else emptyList()
                compositionSteps.put(JSONObject().put("phase", phase).put("editor_length", extracted?.text?.length)
                    .put("selection_start", extracted?.selectionStart).put("selection_end", extracted?.selectionEnd)
                    .put("composition_spans", JSONArray(spans.map { span -> JSONArray(listOf(styled!!.getSpanStart(span), styled.getSpanEnd(span))) }))
                    .put("runtime_value_length", field("Form.TextField", "name").props().optString("value").length))
            }
        }
        compose.activityRule.scenario.onActivity {
            input = requireNotNull(it.window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue(input!!.setSelection(0, 0)); assertTrue(input!!.setComposingText("日本", 1))
        }
        observeComposition("after-set-composing")
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == "日本$original" }
        observeComposition("after-javascript-publication")
        // Complete composition after the real JS -> native value round trip.
        // If recomposition discarded its range, this would append instead.
        compose.activityRule.scenario.onActivity { assertTrue(input!!.commitText("日本語", 1)); assertTrue(input!!.finishComposingText()) }
        observeComposition("after-commit")
        val composed = "日本語$original"
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == composed }
        compose.onNodeWithTag("extension-field-name").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        touch("extension-field-password")
        assertPasswordEditorMetadata("extension-field-password")
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
        advanceOwnedPublication("uncontrolled-value")
        compose.onNodeWithTag("extension-field-uncontrolled").assertTextEquals(uncontrolled)
        assertEquals(uncontrolled, field("Form.TextField", "uncontrolled").props().optString("value"))
        record.put("uncontrolled_value_preserved_after_witnessed_publication", true)
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

    private fun assertPasswordEditorMetadata(tag: String) {
        ime(false)
        val info = android.view.inputmethod.EditorInfo()
        compose.activityRule.scenario.onActivity {
            val editor = requireNotNull(it.window.decorView.findFocus())
            assertTrue(editor.onCheckIsTextEditor())
            assertNotNull(editor.onCreateInputConnection(info))
        }
        val probes = record.optJSONArray("password_editor_metadata") ?: JSONArray().also { record.put("password_editor_metadata", it) }
        probes.put(JSONObject().put("tag", tag).put("input_type", info.inputType).put("ime_options", info.imeOptions)
            .put("scope", "Actual focused editor InputConnection metadata before any password is entered"))
        assertEquals("Password editors must request TYPE_TEXT_VARIATION_PASSWORD", android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            info.inputType and android.text.InputType.TYPE_MASK_VARIATION)
        assertEquals("Password editors must disable autocorrection", 0, info.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
    }

    @Test fun liveImePointerSelectionSurvivesUnrelatedPublication() {
        launch("all-fields"); touch("extension-field-name"); ime(true)
        val value = "Owned caret"
        val editor = compose.onNodeWithTag("extension-field-name")
        editor.performTextReplacement(value)
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == value }
        awaitImeGeometry(true)
        val density = context.resources.displayMetrics.density
        editor.performTouchInput { longClick(Offset(32f * density, center.y)) }
        compose.waitUntil(5000) { !editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].collapsed }
        val before = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals("Owned", value.substring(before.min, before.max))
        assertTrue(imeVisible()); capture("live-ime-selection-before")
        advanceOwnedPublication("live-ime-pointer-selection")
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, before))
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
        assertTrue(imeVisible()); assertEquals(value, field("Form.TextField", "name").props().optString("value"))
        capture("live-ime-selection-after")
        val after = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(before, after); assertTrue(imeVisible())
        record.put("pointer_selection_before_publication", JSONArray(listOf(before.start, before.end)))
            .put("pointer_selection_after_publication", JSONArray(listOf(after.start, after.end)))
            .put("selection_method", "Actual long-press word selection with the live IME visible before/after an unrelated React publication")
            .put("passed", true)
    }

    @Test fun returningFromCheckboxRefocusesTheSameTextField() {
        launch("all-fields")
        touch("extension-field-name")
        val value = "Owned focus return 日本 🌱"
        compose.onNodeWithTag("extension-field-name").performTextReplacement(value)
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == value }
        touch("extension-checkbox-enabled")
        compose.waitUntil(5000) { probe().getJSONObject("blur").optInt("name") == 1 &&
            probe().getJSONObject("focus").optInt("enabled") == 1 }
        record.put("focus_after_checkbox", probe())
        compose.onNodeWithTag("extension-checkbox-enabled").assertIsFocused()
        capture("checkbox-after-text")
        touch("extension-field-name")
        try {
            compose.waitUntil(5000) { probe().getJSONObject("focus").optInt("name") == 2 &&
                probe().getJSONObject("blur").optInt("enabled") == 1 }
        } finally { record.put("focus_after_text_return", probe()) }
        compose.onNodeWithTag("extension-field-name").assertTextEquals(value)
        capture("text-after-checkbox")
        record.put("focus_probe", probe()).put("direct_text_checkbox_text_pointer_round_trip", true).put("passed", true)
    }

    private fun imeVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun awaitImeGeometry(shown: Boolean? = null, window: android.view.Window? = null) {
        val done = CountDownLatch(1)
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        var previous: List<Int>? = null
        var stableSince = 0L
        var frames = 0
        var settled = false
        lateinit var observer: Runnable
        lateinit var view: android.view.View
        compose.activityRule.scenario.onActivity {
            view = window?.decorView ?: it.window.decorView
            observer = object : Runnable {
                override fun run() {
                    val insets = ViewCompat.getRootWindowInsets(view)
                    val geometry = listOf(if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) 1 else 0,
                        insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: -1, view.width, view.height)
                    val now = android.os.SystemClock.uptimeMillis(); frames++
                    if (geometry != previous) { previous = geometry; stableSince = now }
                    settled = (shown == null || geometry[0] == (if (shown) 1 else 0)) && geometry[1] >= 0 && now - stableSince >= 100
                    if (settled || now >= deadline) done.countDown() else view.postOnAnimation(this)
                }
            }
            view.postOnAnimation(observer)
        }
        val completed = done.await(10, TimeUnit.SECONDS)
        compose.activityRule.scenario.onActivity { view.removeCallbacks(observer) }
        val samples = record.optJSONArray("settled_ime_geometry") ?: JSONArray().also { record.put("settled_ime_geometry", it) }
        samples.put(JSONObject().put("requested_visibility", shown ?: JSONObject.NULL).put("window_scope", if (window == null) "activity" else "owned picker dialog")
            .put("native_frames", frames).put("geometry", JSONArray(previous.orEmpty()))
            .put("stable_for_at_least_ms", 100).put("settled", completed && settled))
        assertTrue("Native IME visibility/insets and window geometry must settle before choosing pointer coordinates", completed && settled)
        compose.waitForIdle()
    }
    private fun ime(show: Boolean) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        // Let the actual pointer's focus/input and keyboard transitions finish
        // before issuing an explicit request to an already focused editor.
        // A second early request can race the previous native hide/start work.
        if (show) device.waitForIdle(2000)
        if (show && !imeVisible()) {
            val frames = CountDownLatch(1)
            compose.activityRule.scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frames.countDown() } } }
            assertTrue(frames.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
            compose.waitUntil(10000) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    val editor = it.window.decorView.findFocus()
                    val manager = it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ready = editor != null && editor.hasWindowFocus() && editor.onCheckIsTextEditor() &&
                        manager.isActive(editor) && manager.isAcceptingText
                }
                ready
            }
            compose.activityRule.scenario.onActivity {
                val editor = requireNotNull(it.window.decorView.findFocus())
                if (!imeVisible()) {
                    androidx.core.view.WindowCompat.getInsetsController(it.window, editor).show(WindowInsetsCompat.Type.ime())
                    record.put("native_active_editor_show_request_issued", true)
                }
            }
        }
        compose.activityRule.scenario.onActivity {
            val controller = androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
            if (!show) controller.hide(WindowInsetsCompat.Type.ime())
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
                val frames = CountDownLatch(1)
                compose.activityRule.scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frames.countDown() } } }
                assertTrue(frames.await(5, TimeUnit.SECONDS)); compose.waitForIdle()
            } finally { automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
            record.put("hidden_ime_window_absence_confirmed", true)
        }
        awaitImeGeometry(show)
    }
    private fun fullBounds(interaction: SemanticsNodeInteraction): Rect {
        val node = interaction.fetchSemanticsNode()
        return compose.runOnUiThread {
            val coordinates = node.layoutInfo.coordinates; val origin = coordinates.localToRoot(Offset.Zero)
            Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
        }
    }
    private fun drag(viewport: SemanticsNodeInteraction, direction: Int) {
        val semantic = viewport.fetchSemanticsNode()
        val tag = if (semantic.config.contains(SemanticsProperties.TestTag)) semantic.config[SemanticsProperties.TestTag] else ""
        val gutter = tag == "extension-form" || tag == "extension-configuration"
        val gutterPx = 8f * context.resources.displayMetrics.density
        val scrolls = record.optJSONArray("pointer_scrolls") ?: JSONArray().also { record.put("pointer_scrolls", it) }
        viewport.performTouchInput {
            // A focused text editor or multiline editor can consume a drag
            // that starts in its center. The Form's 20dp padding provides an
            // actual pointer gutter belonging to its parent scroll container.
            val x = if (gutter) minOf(gutterPx, width * .25f) else center.x
            val start = Offset(x, center.y); val end = Offset(x, center.y + direction * height * .25f)
            scrolls.put(JSONObject().put("viewport", tag).put("gutter", gutter)
                .put("visible_bounds", JSONArray(listOf(semantic.boundsInRoot.left, semantic.boundsInRoot.top, semantic.boundsInRoot.right, semantic.boundsInRoot.bottom)))
                .put("start", JSONArray(listOf(start.x, start.y))).put("end", JSONArray(listOf(end.x, end.y))))
            down(start); moveTo(end, delayMillis = 300)
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
        if (viewport in setOf("extension-form", "extension-configuration")) revealViewport(viewport, lineHeight)
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
        if (viewport in setOf("extension-form", "extension-configuration")) revealViewport(viewport, lineHeight)
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
        capture("json-form-input-ime")
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
    private fun typedTouchControl(type: String, id: String, tag: String) {
        launch("typed-controls")
        val current = field(type, id)
        record.put("typed_field_type", type).put("typed_field_id", id)
            .put("initial_native_value", current.props().get("value"))
            .put("matching_native_controls", compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size)
            .put("test_boundary", "Native touch-control availability only; no picker selection, provider or submitted-value workflow is certified")
        capture("typed-control-before-interaction")
        compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        record.put("native_touch_control_available", true).put("passed", true)
    }
    @Test fun datePickerHasNativeTouchControl() = typedTouchControl("Form.DatePicker", "date", "extension-date-date")
    @Test fun tagPickerHasNativeTouchControl() = typedTouchControl("Form.TagPicker", "tags", "extension-tags-tags")
    @Test fun filePickerHasNativeTouchControl() = typedTouchControl("Form.FilePicker", "files", "extension-files-files")
    private fun typedErrorDisablesSubmit(type: String, id: String) {
        launch("typed-error-$id")
        val supplied = field(type, id).props().getString("error")
        assertEquals("Owned typed field must be corrected before submitting.", supplied)
        record.put("typed_field_type", type).put("typed_field_id", id).put("runtime_supplied_error", supplied)
            .put("initial_submit_count", probe().getInt("submits"))
            .put("native_submit_enabled", !compose.onNodeWithTag("extension-primary").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
            .put("test_boundary", "Current native Submit enabled/disabled state only; this case does not attempt activation or certify JavaScript callback rejection")
        assertEquals(0, probe().getInt("submits"))
        capture("typed-error-primary-state")
        compose.onNodeWithTag("extension-primary").assertIsNotEnabled()
        record.put("native_submit_disabled_for_typed_error", true).put("passed", true)
    }
    @Test fun datePickerErrorDisablesSubmit() = typedErrorDisablesSubmit("Form.DatePicker", "date")
    @Test fun tagPickerErrorDisablesSubmit() = typedErrorDisablesSubmit("Form.TagPicker", "tags")
    @Test fun filePickerErrorDisablesSubmit() = typedErrorDisablesSubmit("Form.FilePicker", "files")

    private fun typedDateIso(): String? = field("Form.DatePicker", "date").props().optJSONObject("value")?.getString("\$date")
    private fun increaseTypedDateMinute(): String {
        val before = java.time.Instant.parse(requireNotNull(typedDateIso())).atZone(java.time.ZoneId.systemDefault())
        val minute = (before.minute + 1) % 60
        compose.onNodeWithTag("extension-date-time-plus-date-minute").performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithTag("extension-date-time-date-minute").assertTextEquals(minute.toString().padStart(2, '0'))
        return java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(before.withMinute(minute).toInstant())
    }
    private fun submittedTypedDate(expected: String?, expectedChange: String? = expected) {
        touch("extension-primary")
        compose.waitUntil(5000) { probe().getInt("submits") == 1 }
        val values = probe().getJSONObject("submittedValues")
        assertTrue("Explicit null must not be confused with an absent date key", values.has("date"))
        if (expected == null) assertTrue(values.isNull("date")) else assertEquals(expected, values.getString("date"))
        val current = probe()
        val changed = current.getJSONObject("typedChanges").getJSONObject("date")
        val submitted = current.getJSONObject("typedSubmitted").getJSONObject("date")
        assertTrue(submitted.getBoolean("present"))
        for ((facts, value) in listOf(changed to expectedChange, submitted to expected)) {
            assertEquals(value == null, facts.getBoolean("is_null"))
            assertEquals(value != null, facts.getBoolean("is_date"))
            if (value != null) { assertTrue(facts.getBoolean("valid_date")); assertEquals(value, facts.getString("date_iso")) }
        }
        record.put("date_change_runtime_type", changed).put("date_submit_runtime_type", submitted)
        assertEquals("[\"internal-two\"]", values.getJSONArray("tags").toString())
        assertEquals(0, values.getJSONArray("files").length())
        record.put("submitted_date", expected ?: JSONObject.NULL).put("submit_count", probe().getInt("submits"))
            .put("test_boundary", "Actual native date interaction and owned JS callback values; tag/file selection and a separate destination are not exercised")
    }
    @Test fun datePickerClearsAndSubmitsAnActualNull() {
        launch("typed-controls")
        assertEquals("2024-02-29T12:34:00.000Z", typedDateIso())
        touch("extension-date-clear-date")
        compose.waitUntil(5000) { field("Form.DatePicker", "date").props().isNull("value") }
        compose.waitForIdle(); capture("date-cleared")
        submittedTypedDate(null); capture("date-null-submitted")
        record.put("passed", true)
    }
    @Test fun datePickerCancelsAnEditedDraftWithoutChangingTheValue() {
        launch("typed-controls"); val before = typedDateIso()
        touch("extension-date-date")
        compose.onNodeWithTag("extension-date-dialog-date").assertIsDisplayed()
        increaseTypedDateMinute()
        capture("date-draft-before-cancel")
        compose.onNodeWithTag("extension-date-cancel-date").performTouchInput { click(center) }
        compose.waitForIdle(); compose.onNodeWithTag("extension-date-dialog-date").assertDoesNotExist()
        assertEquals(before, typedDateIso()); assertEquals(0, probe().getInt("changes"))
        capture("date-cancelled")
        record.put("date_before", before).put("date_after", typedDateIso()).put("cancel_preserved_value_and_callback_count", true).put("passed", true)
    }
    @Test fun datePickerConfirmsAnEditedTimeAndSubmitsItsExactInstant() {
        launch("typed-controls"); touch("extension-date-date")
        compose.onNodeWithTag("extension-date-dialog-date").assertIsDisplayed()
        val expected = increaseTypedDateMinute()
        capture("date-draft-before-confirm")
        compose.onNodeWithTag("extension-date-confirm-date").performTouchInput { click(center) }
        compose.waitUntil(5000) { typedDateIso() == expected }
        compose.waitForIdle(); compose.onNodeWithTag("extension-date-dialog-date").assertDoesNotExist()
        capture("date-confirmed")
        submittedTypedDate(expected); record.put("passed", true)
    }
    private fun typedTagValues() = field("Form.TagPicker", "tags").props().getJSONArray("value").toString()
    private fun typedTagRow(index: Int): SemanticsNodeInteraction {
        val tag = "extension-tags-option-tags-$index"
        compose.onNodeWithTag("extension-tags-options-tags").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun submittedTypedTags(expected: List<String>) {
        touch("extension-primary")
        compose.waitUntil(5000) { probe().getInt("submits") == 1 }
        val values = probe().getJSONObject("submittedValues")
        assertEquals(JSONArray(expected).toString(), values.getJSONArray("tags").toString())
        val changed = probe().getJSONObject("typedChanges").getJSONObject("tags")
        val submitted = probe().getJSONObject("typedSubmitted").getJSONObject("tags")
        assertTrue(submitted.getBoolean("present"))
        for (facts in listOf(changed, submitted)) { assertTrue(facts.getBoolean("is_array")); assertTrue(facts.getBoolean("array_strings")) }
        record.put("tag_change_runtime_type", changed).put("tag_submit_runtime_type", submitted)
        assertEquals("2024-02-29T12:34:00.000Z", values.getString("date"))
        assertEquals(0, values.getJSONArray("files").length())
        record.put("submitted_tags", JSONArray(expected)).put("submit_count", probe().getInt("submits"))
            .put("test_boundary", "Actual tag pointer changes and owned JS callback array values; file selection and a separate destination are not exercised")
    }
    @Test fun tagPickerAddsAndRemovesExactValuesByTouch() {
        launch("typed-controls"); touch("extension-tags-tags")
        compose.onNodeWithTag("extension-tags-dialog-tags").assertIsDisplayed()
        typedTagRow(1).assertIsOn()
        typedTagRow(0).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"internal-two\",\"internal-one\"]" }
        compose.waitForIdle(); typedTagRow(0).assertIsOn(); capture("tags-two-selected")
        typedTagRow(1).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"internal-one\"]" }
        compose.waitForIdle(); typedTagRow(1).assertIsOff()
        compose.onNodeWithTag("extension-tags-done-tags").performTouchInput { click(center) }
        compose.onNodeWithTag("extension-tags-dialog-tags").assertDoesNotExist()
        compose.onNodeWithTag("extension-tags-tags").assertTextContains("Readable first category")
        capture("tags-one-selected"); submittedTypedTags(listOf("internal-one")); record.put("passed", true)
    }
    @Test fun tagPickerClearsAndSubmitsAnEmptyArray() {
        launch("typed-controls"); touch("extension-tags-tags")
        typedTagRow(1).assertIsOn(); capture("tags-before-clear")
        compose.onNodeWithTag("extension-tags-clear-tags").performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[]" }
        compose.waitForIdle(); typedTagRow(1).assertIsOff()
        compose.onNodeWithTag("extension-tags-done-tags").performTouchInput { click(center) }
        compose.onNodeWithTag("extension-tags-dialog-tags").assertDoesNotExist()
        capture("tags-cleared"); submittedTypedTags(emptyList()); record.put("passed", true)
    }

    private fun typedPublication(): Pair<String, Int> {
        val root = requireNotNull(activeRoot(catalog.runtimeTree))
        val counter = root.nodes("Form.Description").mapNotNull {
            Regex("Typed publication ([0-9]+)").matchEntire(it.props().optString("text"))?.groupValues?.get(1)?.toIntOrNull()
        }.single()
        return root.optString("id") to counter
    }
    private fun awaitTypedPublication(before: Pair<String, Int>, phase: String) {
        val expected = before.first to before.second + 1
        compose.waitUntil(5000) { typedPublication() == expected }
        compose.waitForIdle()
        val marker = "Typed publication ${expected.second}"
        val composed = compose.onNodeWithText(marker, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(marker, composed.config[SemanticsProperties.Text].joinToString("") { it.text })
        assertEquals(expected, typedPublication())
        val witnesses = record.optJSONArray("typed_publications") ?: JSONArray().also { record.put("typed_publications", it) }
        witnesses.put(JSONObject().put("phase", phase).put("root_id", expected.first).put("before", before.second)
            .put("native_counter", expected.second).put("composed_marker", marker).put("composed_semantics_id", composed.id))
    }
    private fun mutateTyped(script: String, phase: String) {
        val before = typedPublication()
        evaluateOwned("(()=>{$script;globalThis.__ownedTyped.bump();return true})()")
        awaitTypedPublication(before, phase)
    }
    private fun dateIso(instant: java.time.Instant): String = java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant)

    private fun touchTypedCalendarDay(label: String) {
        val calendar = hasTestTag("extension-date-calendar-date")
        val day = compose.onNode(hasText(label) and hasAnyAncestor(calendar))
        val viewport = compose.onNode(hasScrollAction() and hasAnyDescendant(calendar) and
            hasAnyAncestor(hasTestTag("extension-date-dialog-date")), useUnmergedTree = true)
        val minimum = 48f * context.resources.displayMetrics.density
        var revealed = false
        for (attempt in 0 until 60) {
            val node = day.fetchSemanticsNode(); val visible = node.boundsInRoot
            val bounds = viewport.fetchSemanticsNode().boundsInRoot
            if (visible.height + 1 >= minOf(node.size.height.toFloat(), minimum) &&
                visible.width + 1 >= minOf(node.size.width.toFloat(), minimum) && bounds.contains(visible.center)) {
                revealed = true; break
            }
            assertTrue("The calendar needs a useful vertical pointer viewport", bounds.height + 1 >= minimum)
            val direction = if (fullBounds(day).top < bounds.top) 1 else -1
            drag(viewport, direction)
        }
        assertTrue("The actual calendar day must be visible before its pointer tap", revealed)
        day.assertIsEnabled()
        val node = day.fetchSemanticsNode(); val point = node.boundsInRoot.center
        val root = compose.onNode(isRoot() and hasAnyDescendant(calendar), useUnmergedTree = true)
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        record.put("calendar_day_pointer", JSONObject().put("label", label).put("point", JSONArray(listOf(point.x, point.y)))
            .put("visible_bounds", JSONArray(listOf(node.boundsInRoot.left, node.boundsInRoot.top, node.boundsInRoot.right, node.boundsInRoot.bottom))))
        root.performTouchInput { click(point - origin) }; compose.waitForIdle()
        // Material 3 exposes selection on the Day Surface itself.
        // A scroll request or successful event injection alone proves neither.
        day.assertIsSelected(); record.put("calendar_selected_after_pointer", true)
    }

    @Test fun dateOnlySelectsACalendarDayWithInclusiveDayBounds() {
        launch("typed-date-day"); touch("extension-date-date")
        touchTypedCalendarDay("Thursday, February 29, 2024")
        capture("date-calendar-day-selected")
        val before = typedPublication()
        touch("extension-date-confirm-date"); awaitTypedPublication(before, "calendar day accepted")
        val zone = java.time.ZoneId.systemDefault()
        val expected = dateIso(java.time.LocalDate.of(2024, 2, 29).atStartOfDay(zone).toInstant().plusMillis(1))
        record.put("expected_date_iso", expected).put("observed_date_iso", typedDateIso())
            .put("date_change_runtime_type", probe().getJSONObject("typedChanges").getJSONObject("date"))
        capture("date-calendar-returned")
        assertEquals(expected, typedDateIso()); submittedTypedDate(expected)
        for (key in listOf("date_change_runtime_type", "date_submit_runtime_type")) {
            assertTrue(record.getJSONObject(key).getBoolean("full_day_helper"))
            assertTrue(record.getJSONObject(key).getBoolean("full_day"))
        }
        capture("date-calendar-day-submitted")
        record.put("zone", zone.id).put("calendar_day", "2024-02-29")
            .put("bounds_contract", "Same local day accepted despite min 23:59 and max 00:00; exact time components ignored for Date kind")
            .put("passed", true)
    }
    @Test fun dateTimeBoundsRejectOutsideThenAcceptTheInclusiveMaximum() {
        launch("typed-date-bounds"); touch("extension-date-date")
        repeat(2) { touch("extension-date-time-plus-date-minute") }
        touch("extension-date-confirm-date")
        compose.onNodeWithTag("extension-date-dialog-date").assertIsDisplayed()
        compose.onNodeWithTag("extension-date-picker-error-date").performScrollTo().assertTextEquals("Choose a date and time within the allowed range.")
        assertEquals(0, probe().getInt("changes")); assertEquals("2024-02-29T12:34:00.000Z", typedDateIso())
        capture("date-outside-instant-bound")
        touch("extension-date-time-minus-date-minute")
        val before = typedPublication(); touch("extension-date-confirm-date"); awaitTypedPublication(before, "inclusive maximum")
        assertEquals("2024-02-29T12:35:00.000Z", typedDateIso())
        submittedTypedDate("2024-02-29T12:35:00.000Z"); capture("date-inclusive-maximum")
        record.put("outside_value_did_not_dispatch", true).put("inclusive_maximum_accepted", true).put("passed", true)
    }
    @Test fun dateTimeCanConfirmTheInclusiveMinimum() {
        launch("typed-date-bounds"); touch("extension-date-date")
        val before = typedPublication(); touch("extension-date-confirm-date"); awaitTypedPublication(before, "inclusive minimum")
        submittedTypedDate("2024-02-29T12:34:00.000Z"); capture("date-inclusive-minimum")
        record.put("inclusive_minimum_accepted", true).put("passed", true)
    }
    @Test fun datePickerExplainsInconsistentBoundsWithoutOpening() {
        launch("typed-date-inconsistent")
        compose.onNodeWithTag("extension-date-date").assertIsNotEnabled()
        compose.onNodeWithTag("extension-date-unavailable-date").performScrollTo().assertTextEquals("No dates are available for this field.")
        compose.onNodeWithTag("extension-date-dialog-date").assertDoesNotExist()
        assertEquals(0, probe().getInt("changes")); capture("date-inconsistent-bounds")
        record.put("invalid_bounds_have_disabled_control_and_explanation", true).put("passed", true)
    }
    private fun inOwnedNativeTimeZone(zone: String, body: () -> Unit) {
        val original = java.util.TimeZone.getDefault()
        try {
            compose.activityRule.scenario.onActivity { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone)) }
            assertEquals(zone, java.time.ZoneId.systemDefault().id)
            record.put("native_process_time_zone", zone).put("time_zone_boundary", "Owned JVM default override for native picker; Android system setting and WebView timezone unchanged")
            body()
        } finally {
            compose.activityRule.scenario.onActivity { java.util.TimeZone.setDefault(original) }
            assertEquals(original.id, java.util.TimeZone.getDefault().id)
            record.put("native_process_time_zone_restored", true)
        }
    }
    @Test fun nativeDateTimeRejectsADstGapThenAcceptsAValidWallTime() = inOwnedNativeTimeZone("America/New_York") {
        launch("typed-date-dst-gap"); touch("extension-date-date")
        touch("extension-date-time-plus-date-hour"); touch("extension-date-confirm-date")
        compose.onNodeWithTag("extension-date-picker-error-date").performScrollTo()
            .assertTextEquals("Choose a valid date and time. This local time may not exist when the clocks change.")
        assertEquals(0, probe().getInt("changes")); assertEquals("2024-03-10T06:30:00.000Z", typedDateIso())
        capture("date-dst-gap-rejected")
        touch("extension-date-time-plus-date-hour")
        val before = typedPublication(); touch("extension-date-confirm-date"); awaitTypedPublication(before, "valid DST wall time")
        submittedTypedDate("2024-03-10T07:30:00.000Z"); capture("date-dst-valid-wall-time")
        record.put("nonexistent_wall_time_did_not_shift_or_dispatch", true).put("passed", true)
    }
    @Test fun nativeDateTimeKeepsTheExistingDstOccurrenceAndMilliseconds() = inOwnedNativeTimeZone("America/New_York") {
        launch("typed-date-dst-overlap"); touch("extension-date-date")
        val before = typedPublication(); touch("extension-date-confirm-date"); awaitTypedPublication(before, "same DST occurrence")
        submittedTypedDate("2024-11-03T06:30:12.345Z"); capture("date-dst-existing-offset")
        record.put("existing_repeated_hour_and_fractional_seconds_preserved", true).put("passed", true)
    }
    @Test fun datePickerExplainsMalformedNativeBounds() {
        launch("typed-controls")
        val saved = catalog.runtimeTree
        try {
            val replacement = JSONObject(requireNotNull(saved).toString())
            requireNotNull(activeRoot(replacement)).nodes("Form.DatePicker").single().props()
                .put("min", JSONObject().put("\$date", "invalid-owned-date"))
            compose.activityRule.scenario.onActivity { catalog.runtimeTree = replacement }
            compose.waitForIdle(); compose.onNodeWithTag("extension-date-date").assertIsNotEnabled()
            compose.onNodeWithTag("extension-date-unavailable-date").performScrollTo().assertIsDisplayed()
            capture("date-malformed-native-bound")
            assertEquals(0, probe().getInt("changes"))
            record.put("test_boundary", "Controlled malformed native date envelope, not a successful JS Invalid Date serialization workflow").put("passed", true)
        } finally { compose.activityRule.scenario.onActivity { catalog.runtimeTree = saved }; compose.waitForIdle() }
    }
    @Test fun datePickerControlledRejectionWaitsForItsPublication() {
        launch("typed-date-reject"); touch("extension-date-date")
        val edited = increaseTypedDateMinute(); val before = typedPublication()
        touch("extension-date-confirm-date"); awaitTypedPublication(before, "controlled date rejection")
        assertEquals("2024-02-29T12:34:00.000Z", typedDateIso())
        compose.onNodeWithTag("extension-date-dialog-date").assertDoesNotExist()
        submittedTypedDate("2024-02-29T12:34:00.000Z", edited); capture("date-controlled-rejection")
        record.put("controlled_rejection_is_authoritative", true).put("passed", true)
    }
    @Test fun datePickerControlledTransformationWaitsForItsPublication() {
        launch("typed-date-transform"); touch("extension-date-date")
        val edited = increaseTypedDateMinute(); val before = typedPublication()
        touch("extension-date-confirm-date"); awaitTypedPublication(before, "controlled date transformation")
        val expected = dateIso(java.time.Instant.parse(edited).plusSeconds(30))
        assertEquals(expected, typedDateIso()); submittedTypedDate(expected, edited); capture("date-controlled-transformation")
        record.put("controlled_transformation_is_authoritative", true).put("passed", true)
    }
    @Test fun typedDefaultsDoNotResetEditsOnALaterPublication() {
        launch("typed-defaults"); touch("extension-date-clear-date")
        compose.waitUntil(5000) { typedDateIso() == null }
        touch("extension-tags-tags"); typedTagRow(0).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"internal-two\",\"internal-one\"]" }
        touch("extension-tags-done-tags")
        mutateTyped("globalThis.__ownedTyped.setDefaultDate('2030-01-01T00:00:00.000Z');globalThis.__ownedTyped.setDefaultTags([])", "later default props")
        assertNull(typedDateIso()); assertEquals("[\"internal-two\",\"internal-one\"]", typedTagValues())
        touch("extension-primary"); compose.waitUntil(5000) { probe().getInt("submits") == 1 }
        val observed = probe(); val submitted = observed.getJSONObject("submittedValues")
        assertTrue(submitted.has("date") && submitted.isNull("date")); assertEquals(typedTagValues(), submitted.getJSONArray("tags").toString())
        val types = observed.getJSONObject("typedSubmitted")
        assertTrue(types.getJSONObject("date").getBoolean("is_null")); assertTrue(types.getJSONObject("tags").getBoolean("array_strings"))
        capture("typed-defaults-preserved"); record.put("submitted_values", submitted).put("default_props_do_not_reset_edits", true).put("passed", true)
    }
    @Test fun typedFocusAndBlurDeliverCurrentDateNullAndArrays() {
        launch("typed-controls"); touch("extension-date-date")
        val edited = increaseTypedDateMinute(); touch("extension-date-confirm-date")
        compose.waitUntil(5000) { typedDateIso() == edited }
        touch("extension-tags-tags"); typedTagRow(0).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"internal-two\",\"internal-one\"]" }
        touch("extension-tags-done-tags"); touch("extension-files-files"); touch("extension-files-done-files")
        touch("extension-primary"); compose.waitUntil(5000) { probe().getInt("submits") == 1 }
        var observed = probe(); var blur = observed.getJSONObject("blurFacts")
        assertTrue(blur.getJSONObject("date").getBoolean("is_date")); assertEquals(edited, blur.getJSONObject("date").getString("date_iso"))
        for (id in listOf("tags", "files")) { assertTrue(blur.getJSONObject(id).getBoolean("is_array")); assertTrue(blur.getJSONObject(id).getBoolean("array_strings")) }
        assertEquals("[\"internal-two\",\"internal-one\"]", observed.getJSONObject("blurValues").getJSONArray("tags").toString())
        assertEquals(0, observed.getJSONObject("blurValues").getJSONArray("files").length())
        touch("extension-date-clear-date"); compose.waitUntil(5000) { typedDateIso() == null }
        touch("extension-tags-tags"); touch("extension-tags-done-tags")
        compose.waitUntil(5000) { probe().getJSONObject("blurFacts").getJSONObject("date").getBoolean("is_null") }
        observed = probe(); blur = observed.getJSONObject("blurFacts")
        assertTrue(blur.getJSONObject("date").getBoolean("is_null"))
        record.put("typed_focus_facts", observed.getJSONObject("focusFacts")).put("typed_blur_facts", blur)
            .put("test_boundary", "Owned Date/null and current tag/file array focus/blur; file array is empty, provider import is a separate workflow")
        capture("typed-current-focus-values"); record.put("passed", true)
    }
    private fun typedDialogWindow(): android.view.Window {
        var result: android.view.Window? = null
        compose.activityRule.scenario.onActivity {
            fun visit(view: android.view.View) {
                if (view is androidx.compose.ui.window.DialogWindowProvider) { assertNull(result); result = view.window }
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
        }
        return requireNotNull(result)
    }
    @Test fun dateTimeEditorKeepsConfirmationReachableWithItsOwnIme() {
        launch("typed-controls"); touch("extension-date-date"); touch("extension-date-time-date-minute")
        val window = typedDialogWindow()
        compose.waitUntil(10000) {
            var ready = false
            compose.activityRule.scenario.onActivity {
                val editor = window.decorView.findFocus()
                ready = editor != null && editor.hasWindowFocus() && it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(editor)
            }
            ready
        }
        compose.activityRule.scenario.onActivity { androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.ime()) }
        awaitImeGeometry(true, window)
        compose.onNodeWithTag("extension-date-time-date-minute").performTextReplacement("35")
        compose.onNodeWithTag("extension-date-time-date-minute").assertTextEquals("35")
        capture("date-time-editor-ime")
        val before = typedPublication(); touch("extension-date-confirm-date"); awaitTypedPublication(before, "date confirmed with dialog IME")
        assertEquals("2024-02-29T12:35:00.000Z", typedDateIso())
        compose.onNodeWithTag("extension-date-dialog-date").assertDoesNotExist()
        submittedTypedDate("2024-02-29T12:35:00.000Z"); capture("date-time-ime-submitted")
        record.put("ime_observation", "Insets observed on actual owned Dialog window, not Activity; pointer focus/confirm with controlled SetText entry")
            .put("passed", true)
    }
    @Test fun tagPickerCanRemoveAnUnavailableValueAndShowItsEmptyState() {
        launch("typed-tags-empty"); touch("extension-tags-tags")
        compose.onNodeWithTag("extension-tags-unavailable-tags-0").assertIsDisplayed(); capture("tags-unavailable-value")
        val before = typedPublication(); touch("extension-tags-unavailable-tags-0"); awaitTypedPublication(before, "unavailable tag removed")
        compose.onNodeWithText("No categories available", useUnmergedTree = true).assertIsDisplayed()
        capture("tags-no-choices"); touch("extension-tags-done-tags"); submittedTypedTags(emptyList())
        record.put("unavailable_value_removable_without_inventing_an_option", true).put("passed", true)
    }
    @Test fun tagPickerLongOptionKeepsEveryLineReachable() {
        launch("typed-tags-long"); touch("extension-tags-tags"); typedTagRow(0)
        inspectTextLines("extension-tags-options-tags", listOf(compose.onNodeWithText(longTitle, useUnmergedTree = true)), "long-tag-option")
        typedTagRow(0).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"internal-two\",\"internal-one\"]" }
        touch("extension-tags-done-tags"); submittedTypedTags(listOf("internal-two", "internal-one"))
        record.put("passed", true)
    }
    @Test fun tagPickerQueuedOptionCannotSelectAReplacedChoice() {
        launch("typed-controls"); touch("extension-tags-tags")
        val queued = requireNotNull(typedTagRow(0).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        mutateTyped("globalThis.__ownedTyped.setOptions([{value:'replacement-choice',title:'Readable replacement category'}])", "tag option replaced")
        compose.onNodeWithText("Readable replacement category", useUnmergedTree = true).assertIsDisplayed()
        val before = probe().toString()
        compose.activityRule.scenario.onActivity { assertTrue(queued()) }
        assertEquals(before, probe().toString()); assertEquals("[\"internal-two\"]", typedTagValues())
        capture("tags-queued-option-rejected"); touch("extension-tags-done-tags")
        record.put("queued_callback", "Captured native row callback invoked after actual replacement publication and composition; not a pointer to a still-visible old row")
            .put("passed", true)
    }
    @Test fun tagPickerControlledRejectionWaitsForItsPublication() {
        launch("typed-tags-reject"); touch("extension-tags-tags")
        val before = typedPublication(); typedTagRow(0).performTouchInput { click(center) }
        awaitTypedPublication(before, "controlled tag rejection")
        assertEquals("[\"internal-two\"]", typedTagValues()); typedTagRow(0).assertIsOff(); typedTagRow(1).assertIsOn()
        capture("tags-controlled-rejection"); touch("extension-tags-done-tags"); submittedTypedTags(listOf("internal-two"))
        record.put("controlled_rejection_is_authoritative", true).put("passed", true)
    }
    @Test fun datePickerQueuedConfirmationCannotEnterAReplacementField() {
        launch("typed-controls"); touch("extension-date-date"); increaseTypedDateMinute()
        val queued = requireNotNull(compose.onNodeWithTag("extension-date-confirm-date").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val oldId = field("Form.DatePicker", "date").optString("id")
        mutateTyped("globalThis.__ownedTyped.setDateKey('replacement-date')", "date field replaced")
        assertNotEquals(oldId, field("Form.DatePicker", "date").optString("id"))
        compose.onNodeWithTag("extension-date-dialog-date").assertDoesNotExist()
        val before = probe().toString(); compose.activityRule.scenario.onActivity { assertTrue(queued()) }
        assertEquals(before, probe().toString()); assertEquals("2024-02-29T12:34:00.000Z", typedDateIso())
        capture("date-replacement-field-rejected")
        record.put("queued_callback", "Old confirm invoked after actual replacement field publication; no successor value or dispatch change").put("passed", true)
    }
    @Test fun typedUncontrolledRefsResetToTheirInitialValues() {
        launch("typed-defaults"); touch("extension-date-clear-date")
        compose.waitUntil(5000) { typedDateIso() == null }
        touch("extension-tags-tags"); typedTagRow(0).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"internal-two\",\"internal-one\"]" }
        touch("extension-tags-done-tags"); val changes = probe().getInt("changes")
        mutateTyped("globalThis.__ownedTyped.resetDate();globalThis.__ownedTyped.resetTags()", "uncontrolled refs reset")
        assertEquals("2024-02-29T12:34:00.000Z", typedDateIso()); assertEquals("[\"internal-two\"]", typedTagValues())
        assertEquals(changes, probe().getInt("changes"))
        touch("extension-primary"); compose.waitUntil(5000) { probe().getInt("submits") == 1 }
        val observed = probe(); val values = observed.getJSONObject("submittedValues")
        assertEquals("2024-02-29T12:34:00.000Z", values.getString("date")); assertEquals("[\"internal-two\"]", values.getJSONArray("tags").toString())
        assertTrue(observed.getJSONObject("typedSubmitted").getJSONObject("date").getBoolean("is_date"))
        assertTrue(observed.getJSONObject("typedSubmitted").getJSONObject("tags").getBoolean("array_strings"))
        capture("typed-refs-reset")
        record.put("uncontrolled_reset_published", true).put("reset_did_not_invoke_on_change", true)
            .put("test_boundary", "Actual generated uncontrolled Date/tag reset refs; imperative focus and storeValue persistence are separate open contracts")
            .put("passed", true)
    }

    private fun frozenTypedBundle(name: String): InstalledExtension {
        val expectedArchive = when (name) {
            "mood" -> "46bd1d6f6598b3ecab43d6eac9778a8b4aba7e3d609859f90feceaf391b9b221"
            "checksum" -> "8528552825c393464aaea7be81b88dfe54d0e51ce87d1c5cd4530adaa674203d"
            else -> error("Unknown owned typed bundle")
        }
        val directory = fixtureDirectory(); val id = directory.name
        val archive = File(context.filesDir, "reviewed-text-fixture-lease/$name.zip").readBytes()
        assertEquals(expectedArchive, hash(archive))
        val extracted = ExtensionArchive.extract(archive, directory)
        val extension = InstalledExtension(StoreExtension(id, name, "$name (owned frozen copy)", "", "Droidcast tests", "test", "", "", "", 1),
            extracted, JSONObject(File(extracted, "package.json").readText()))
        ownedExtension = extension
        record.put("scope", "Unmodified frozen $name Store command JavaScript in an owned namespace; only owned test input/data, not whole-Store certification")
            .put("archive_sha256", hash(archive)).put("owned_extension_id", id)
        return extension
    }
    private fun runFrozenTypedCommand(extension: InstalledExtension, name: String, rootType: String) {
        val expectedHash = when (name) {
            "record-mood" -> "11e2a3e9900c47a8697004f267024865c2f4a5d21b87cb396e07ed4a5cc56478"
            "search-moods" -> "9eee3a5852244fc06109e695358c2780a3970d28d04776b7d6416a11653bdea0"
            "hashfile" -> "b42e7c5bbe881ae5397aa43660c29c23651b8d06e8165b5f11a962232a9d9a68"
            else -> error("Unknown owned frozen command")
        }
        assertEquals(expectedHash, hash(File(extension.directory, "$name.js").readBytes()))
        val commands = record.optJSONObject("frozen_command_sha256") ?: JSONObject().also { record.put("frozen_command_sha256", it) }
        commands.put(name, expectedHash)
        compose.activityRule.scenario.onActivity { it.store.openExtension(extension, extension.commands.single { command -> command.name == name }) }
        compose.waitUntil(25000) { catalog.runtimeError != null || catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == rootType }
        record.put("runtime_start_error", catalog.runtimeError ?: JSONObject.NULL)
        assertNull(catalog.runtimeError, catalog.runtimeError); compose.waitForIdle()
    }
    private fun frozenTypedCallerReturn() {
        if (compose.activity.store.dialog != null) touch("extension-back")
        compose.waitUntil(5000) { compose.activity.store.dialog == null }
        compose.onNodeWithTag("search").assertTextEquals(caller)
        record.put("caller_query_restored", true)
    }
    @Test fun frozenMoodStoresExactTypedValuesAndReloadsThemInSearch() {
        val extension = frozenTypedBundle("mood")
        runFrozenTypedCommand(extension, "record-mood", "Form")
        val entries = File(context.filesDir, "extension-data/${requireNotNull(ownedId)}/entries.json")
        assertFalse(entries.exists())
        touch("extension-date-date"); val expectedDate = increaseTypedDateMinute(); touch("extension-date-confirm-date")
        compose.waitUntil(5000) { typedDateIso() == expectedDate }
        touch("extension-tags-tags"); typedTagRow(0).performTouchInput { click(center) }; typedTagRow(3).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"work\",\"health\"]" }; touch("extension-tags-done-tags")
        val notes = "Owned mood café é 日本 🌱"
        touch("extension-field-notes"); compose.onNodeWithTag("extension-field-notes").performTextReplacement(notes)
        compose.waitUntil(5000) { field("Form.TextArea", "notes").props().optString("value") == notes }
        ime(false); capture("mood-typed-values-before-save"); touch("extension-primary")
        compose.waitUntil(10000) { runCatching { JSONArray(entries.readText()).length() == 1 }.getOrDefault(false) }
        val saved = JSONArray(entries.readText()).getJSONObject(0)
        assertEquals("neutral", saved.getString("mood")); assertEquals(notes, saved.getString("notes")); assertEquals(expectedDate, saved.getString("date"))
        assertEquals("[\"work\",\"health\"]", saved.getJSONArray("tags").toString()); UUID.fromString(saved.getString("id"))
        val bytes = entries.readBytes(); record.put("saved_owned_record", saved).put("saved_data_sha256", hash(bytes))
        capture("mood-record-persisted"); frozenTypedCallerReturn()
        runFrozenTypedCommand(extension, "search-moods", "List")
        compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.nodes("List.Item")?.singleOrNull()?.props()?.optString("subtitle") == "work, health" }
        val row = requireNotNull(activeRoot(catalog.runtimeTree)).nodes("List.Item").single()
        assertEquals("Neutral", row.props().getString("title")); assertTrue(row.props().getJSONObject("detail").props().getString("markdown").contains(notes))
        assertArrayEquals(bytes, entries.readBytes()); capture("mood-reloaded-search-destination")
        frozenTypedCallerReturn()
        record.put("record_and_search_use_same_persisted_owned_data", true)
            .put("test_boundary", "Two frozen commands: actual Date/tag/note editing, exact private record and new-runtime Search destination. AI/charts/drafts/storeValue/OEM behavior untested")
            .put("passed", true)
    }
    @Test fun frozenChecksumHashesTheActualImportedBytesCopiesAndReturns() {
        val extension = frozenTypedBundle("checksum"); runFrozenTypedCommand(extension, "hashfile", "Form")
        compose.onNodeWithText("Preferences", useUnmergedTree = true).performScrollTo().performTouchInput { click(center) }
        compose.onNodeWithTag("preference-checkbox-popRootAfterSubmit").performScrollTo().assertIsOn().performTouchInput { click(center) }
        compose.onNodeWithTag("preference-checkbox-popRootAfterSubmit").assertIsOff(); capture("checksum-preferences-edited")
        val configuredEpoch = catalog.launchEpoch; touch("run-extension-setup")
        compose.waitUntil(20000) { catalog.launchEpoch > configuredEpoch && catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        val preferences = context.getSharedPreferences("extension-${extension.listing.id}", Context.MODE_PRIVATE)
        assertFalse(JSONObject(preferences.getString("preferences", "{}")!!).getBoolean("popRootAfterSubmit"))
        val device = openOwnedDocuments(id = "file"); clickDocumentText(device, "café 日本.txt")
        val imported = awaitImportedFiles(1, id = "file").single()
        val bytes = "Owned file café 日本 🌱\n".toByteArray(); assertArrayEquals(bytes, File(imported).readBytes())
        capture("checksum-owned-file-imported"); touch("extension-files-done-file")
        val selected = field("Form.Dropdown", "dropdown").props().getString("value")
        val algorithms = linkedMapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
        assertTrue(selected in algorithms)
        val expected = algorithms.mapValues { (_, algorithm) -> MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) } }
        touch("extension-primary")
        compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.optString("type") == "List" }
        val rows = requireNotNull(activeRoot(catalog.runtimeTree)).nodes("List.Item")
        for ((algorithm, digest) in expected) assertEquals(digest, rows.single { it.props().optString("title") == algorithm.uppercase(java.util.Locale.ROOT) }.props().getString("subtitle"))
        fun clipboardEquals(expected: String): Boolean {
            var matches = false
            compose.activityRule.scenario.onActivity { matches = it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() == expected }
            return matches
        }
        compose.waitUntil(5000) { clipboardEquals(requireNotNull(expected[selected])) }
        dismissClipboardForEvidence(); capture("checksum-real-hash-destination")
        val sha256Row = rows.indexOfFirst { it.props().optString("title") == "SHA256" }; assertTrue(sha256Row >= 0)
        touch("extension-row-$sha256Row"); touch("extension-primary")
        compose.waitUntil(5000) { clipboardEquals(requireNotNull(expected["sha256"])) }
        dismissClipboardForEvidence(); capture("checksum-sha256-copied")
        touch("extension-actions"); compose.onNodeWithTag("inline-menu-search").performTextReplacement("Back to Form")
        // Match the actual second action, not the search editor containing
        // the same query text.
        touch("extension-action-1")
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        assertTrue(typedFileValues("file").isEmpty()); assertArrayEquals(bytes, File(imported).readBytes())
        capture("checksum-returned-form"); frozenTypedCallerReturn(); assertArrayEquals(bytes, File(imported).readBytes())
        record.put("all_four_hashes_match_exact_owned_bytes", JSONObject(expected)).put("selected_algorithm", selected)
            .put("actual_sha256_copy_verified", true).put("delivered_private_copy_retained_after_command_close", true)
            .put("test_boundary", "Frozen checksum/hashfile real DocumentsUI import, four actual hashes, exact Copy, actual Back to Form action and caller return; no arbitrary-provider or writeback certification")
            .put("passed", true)
    }
    private fun assertTypedErrorBlocksBothGates(id: String, type: String) {
        assertEquals("Owned typed field must be corrected before submitting.", field(type, id).props().getString("error"))
        compose.onNodeWithTag("extension-primary").assertIsNotEnabled(); touch("extension-primary")
        assertEquals(0, probe().getInt("submits"))
        // A genuine onFocus establishes this fixture's current JS session.
        // The direct dispatch below deliberately bypasses the native gate.
        when (id) {
            "date" -> { touch("extension-date-date"); touch("extension-date-cancel-date") }
            "tags" -> { touch("extension-tags-tags"); touch("extension-tags-done-tags") }
            "files" -> { touch("extension-files-files"); touch("extension-files-done-files") }
        }
        evaluateOwned("if(typeof globalThis.__ownedFormSession!=='string')throw Error('Owned dispatch session missing');true")
        val handle = requireNotNull(requireNotNull(activeRoot(catalog.runtimeTree)).actions().first().handle("onAction"))
        val before = probe().getInt("dispatches")
        val beforeTree = catalog.runtimeTree; val rootId = requireNotNull(activeRoot(beforeTree)).optString("id")
        evaluateOwned("globalThis.__formProbe.typedGateRequest={};globalThis.__tinycast.dispatch(globalThis.__ownedFormSession,${JSONObject.quote(handle)},'[]');true")
        compose.waitUntil(5000) { probe().optJSONObject("typedGateRequest")?.optBoolean("after_host_render_and_submit_drain") == true && catalog.runtimeTree !== beforeTree }
        assertEquals(rootId, activeRoot(catalog.runtimeTree)?.optString("id")); compose.waitForIdle()
        submitBarrier(); val observed = probe()
        assertEquals(before + 1, observed.getInt("dispatches")); assertEquals(0, observed.getInt("submits"))
        assertEquals(0, observed.getJSONObject("typedGateRequest").getInt("settled_submits"))
        record.put("typed_error_gates", JSONObject().put("field", id).put("type", type).put("native_disabled_attempt_verified", true)
            .put("direct_owned_js_request_dispatch_delta", observed.getInt("dispatches") - before).put("blocked_submit_count", observed.getInt("submits"))
            .put("same_native_root_published", rootId).put("js_post_drain_witness", observed.getJSONObject("typedGateRequest")))
        capture("typed-$id-both-submit-gates")
    }
    private fun awaitTypedErrorCleared(id: String, type: String) {
        compose.waitUntil(5000) { (field(type, id).props().opt("error") as? String).isNullOrBlank() }
        compose.onNodeWithTag("extension-primary").assertIsEnabled()
        compose.onNodeWithTag("extension-field-error-$id").assertDoesNotExist()
        assertEquals(0, probe().getInt("submits"))
    }
    @Test fun correctingTypedDateErrorEnablesExactlyOneNullSubmission() {
        launch("typed-error-date"); assertTypedErrorBlocksBothGates("date", "Form.DatePicker")
        touch("extension-date-clear-date"); awaitTypedErrorCleared("date", "Form.DatePicker")
        assertNull(typedDateIso()); submittedTypedDate(null); capture("typed-date-corrected-submitted")
        record.put("corrected_submit_count", probe().getInt("submits")).put("passed", true)
    }
    @Test fun correctingTypedTagErrorEnablesExactlyOneArraySubmission() {
        launch("typed-error-tags"); assertTypedErrorBlocksBothGates("tags", "Form.TagPicker")
        touch("extension-tags-tags"); typedTagRow(0).performTouchInput { click(center) }
        compose.waitUntil(5000) { typedTagValues() == "[\"internal-two\",\"internal-one\"]" }
        touch("extension-tags-done-tags"); awaitTypedErrorCleared("tags", "Form.TagPicker")
        submittedTypedTags(listOf("internal-two", "internal-one")); capture("typed-tag-corrected-submitted")
        record.put("corrected_submit_count", probe().getInt("submits")).put("passed", true)
    }
    @Test fun correctingTypedFileErrorImportsBytesAndSubmitsExactlyOnce() {
        launch("typed-file-error-workflow"); assertTypedErrorBlocksBothGates("files", "Form.FilePicker")
        val device = openOwnedDocuments(); clickDocumentText(device, "café 日本.txt")
        awaitImportedFiles(1); touch("extension-files-done-files"); awaitTypedErrorCleared("files", "Form.FilePicker")
        submitImportedFiles(mapOf("café 日本.txt" to "Owned file café 日本 🌱\n".toByteArray()))
        assertEquals(1, probe().getInt("submits")); record.put("corrected_submit_count", probe().getInt("submits"))
        returnFromFileDestination(); record.put("passed", true)
    }

    private fun typedFileValues(id: String = "files"): List<String> {
        val values = field("Form.FilePicker", id).props().getJSONArray("value")
        return (0 until values.length()).map { values.getString(it) }
    }
    private fun openOwnedDocuments(folder: Boolean = false, id: String = "files"): UiDevice {
        assertEquals("Use the host's --document-fixtures lease", expectedOwner, InstrumentationRegistry.getArguments().getString("expectedFormDocumentOwner"))
        touch("extension-files-$id")
        compose.onNodeWithTag("extension-files-dialog-$id").assertIsDisplayed()
        capture("file-import-choice")
        touch(if (folder) "extension-files-folder-$id" else "extension-files-choose-$id")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("The real Android document picker must open", device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile(".*documentsui"))), 15000))
        val pickerPackage = requireNotNull(device.currentPackageName)
        assertTrue(pickerPackage.endsWith("documentsui"))
        // Always choose the leased root, even if DocumentsUI remembers a prior
        // folder. No app-side URI injection or ActivityMonitor supplies results.
        val listSelector = UiSelector().packageName(pickerPackage).resourceId(pickerPackage + ":id/roots_list")
        val roots = device.findObject(listSelector)
        if (!roots.exists()) {
            val drawer = device.findObject(UiSelector().packageName(pickerPackage).description("Show roots"))
            assertTrue(drawer.waitForExists(5000)); assertTrue(drawer.click())
        }
        assertTrue("The actual location drawer must open", roots.waitForExists(5000))
        // Recent also has an android:id/title app shortcut with this text.
        // Resolve only descendants of the drawer's roots_list for the gesture.
        val rootRow = UiScrollable(listSelector).getChildByText(UiSelector().className("android.widget.TextView")
            .resourceId("android:id/title"), "Droidcast owned files", true)
        assertTrue(rootRow.isEnabled)
        recordPickerAcknowledgement("location drawer / Droidcast owned files", false, rootRow.click())
        assertTrue("The root drawer must finish closing", device.wait(Until.gone(By.res(pickerPackage, "roots_list")), 5000))
        record.put("system_picker_package", pickerPackage)
        if (device.hasObject(By.res(pickerPackage, "sub_menu_list"))) {
            // A large thumbnail grid can put even the first filename below
            // the viewport. Use the picker's real list-view control for touch.
            clickDocumentResource(device, "sub_menu_list")
            assertTrue(device.wait(Until.hasObject(By.res(pickerPackage, "sub_menu_grid")), 5000))
        }
        assertTrue(documentRow(device, "café 日本.txt").waitForExists(5000))
        record.put("system_picker_package", pickerPackage).put("system_picker_root", "Droidcast owned files")
            .put("system_picker_path", "Real DocumentsUI pointer selection and URI grant; no ActivityMonitor")
        captureDocumentPicker("android-picker-owned-root", device)
        return device
    }
    private fun captureDocumentPicker(phase: String, device: UiDevice) {
        val name = "form-${testName.methodName.lowercase(java.util.Locale.ROOT)}-$phase"
        testAutomation().clearCacheForTest()
        device.waitForIdle(1500)
        device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        assertTrue(device.takeScreenshot(File(evidence, "$name.png")))
        val frame = JSONObject().put("scope", "Actual Android document picker showing only leased owned documents; not the extension's Compose frame")
            .put("phase", phase).put("system_package", device.currentPackageName)
            .put("font_scale", context.resources.configuration.fontScale)
            .put("installed_build_commit", record.getString("installed_build_commit"))
            .put("synchronization", "Bounded DocumentsUI idle observation; native hierarchy before screenshot; no Compose frame claim")
        File(evidence, "$name-frame.json").writeText(frame.toString(2))
    }
    private fun clickDocumentText(device: UiDevice, text: String, long: Boolean = false, pattern: Boolean = false) {
        val selector = UiSelector().packageName(record.getString("system_picker_package")).let {
            if (pattern) it.textMatches(text) else it.text(text)
        }
        val item = if (pattern) device.findObject(selector) else documentRow(device, text)
        assertTrue("The exact system-picker item must be available", item.waitForExists(5000)); assertTrue(item.isEnabled)
        recordPickerAcknowledgement(text, long, if (long) item.longClick() else item.click())
    }
    private fun documentRow(device: UiDevice, title: String): androidx.test.uiautomator.UiObject {
        val pkg = record.getString("system_picker_package")
        val list = UiScrollable(UiSelector().packageName(pkg).resourceId(pkg + ":id/dir_list"))
        assertTrue("The actual document list must be available", list.waitForExists(5000))
        return list.getChildByText(UiSelector().className("android.widget.TextView").resourceId("android:id/title"), title, true)
    }
    private fun clickDocumentResource(device: UiDevice, id: String) {
        val pkg = record.getString("system_picker_package")
        val item = device.findObject(UiSelector().packageName(pkg).resourceId(pkg + ":id/" + id))
        assertTrue(item.waitForExists(5000)); assertTrue(item.isEnabled)
        recordPickerAcknowledgement(id, false, item.click())
    }
    private fun recordPickerAcknowledgement(label: String, long: Boolean, acknowledged: Boolean) {
        // Pinned UIAutomator 2.3.0 clickAndSync returns whether an accessibility
        // content/selection event arrived, not the resulting field value. Keep
        // its actual return and require the following route/value/byte checks.
        val actions = record.optJSONArray("system_picker_gestures") ?: JSONArray().also { record.put("system_picker_gestures", it) }
        actions.put(JSONObject().put("owned_target", label).put("long_press", long).put("accessibility_acknowledged", acknowledged)
            .put("acceptance", "Subsequent actual picker route, native field values, JS bytes and caller state; no retry on a false acknowledgement"))
    }
    private fun awaitImportedFiles(expected: Int, diagnosticDocument: String? = null, id: String = "files"): List<String> {
        compose.waitUntil(15000) { typedFileValues(id).size == expected ||
            compose.onAllNodesWithTag("extension-field-error-files-import-$id").fetchSemanticsNodes().isNotEmpty() }
        if (typedFileValues(id).size != expected) {
            if (diagnosticDocument != null) {
                // Failure-only diagnostic, after a real picker grant. It never
                // supplies a result to the UI or earns workflow pass credit.
                val uri = "content://dev.tinycast.android.test.formdocuments/document/$diagnosticDocument"
                val request = FormDocumentImportRequest(FormFileStorage(context.filesDir, requireNotNull(ownedId)), listOf(uri), true, false) {
                    AndroidFormDocuments(context.contentResolver, it)
                }
                try { runBlocking { withTimeout(5000) { request.prepare() } } }
                catch (failure: Exception) {
                    record.put("post_failure_diagnostic", "Independent bounded import of the actually selected owned document")
                        .put("diagnostic_failure_type", failure.javaClass.simpleName)
                    throw AssertionError("Owned selected-document diagnostic failed", failure)
                } finally { request.cancel() }
            }
            fail("The real picker selection was rejected by the native import")
        }
        compose.waitForIdle()
        val paths = typedFileValues(id)
        val root = File(context.filesDir, "extension-data/${requireNotNull(ownedId)}").canonicalFile
        paths.forEach { path ->
            assertFalse("A content URI must never be forwarded as a filesystem path", path.startsWith("content:"))
            val file = File(path)
            assertEquals(file.absoluteFile, file.canonicalFile)
            assertTrue(file.canonicalPath.startsWith(root.path + File.separator)); assertTrue(file.exists())
        }
        record.put("private_copy_inside_own_extension_data", true)
        return paths
    }
    private fun submitImportedFiles(expected: Map<String, ByteArray>, folder: Boolean = false) {
        val paths = typedFileValues()
        val submits = probe().getInt("submits")
        touch("extension-primary")
        compose.waitUntil(10000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Detail" }
        assertEquals(submits + 1, probe().getInt("submits"))
        val submitted = probe().getJSONObject("submittedValues").getJSONArray("files")
        assertEquals(JSONArray(paths).toString(), submitted.toString())
        val facts = probe().getJSONObject("typedSubmitted").getJSONObject("files")
        assertTrue(facts.getBoolean("present")); assertTrue(facts.getBoolean("is_array")); assertTrue(facts.getBoolean("array_strings"))
        record.put("file_submit_runtime_type", facts)
        val reads = probe().getJSONArray("fileReads")
        assertEquals(expected.size, reads.length())
        val observed = (0 until reads.length()).map { reads.getJSONObject(it) }.associateBy { it.getString("name") }
        assertEquals(expected.keys, observed.keys)
        expected.forEach { (name, bytes) ->
            val row = requireNotNull(observed[name]); assertEquals(folder, row.getBoolean("directory"))
            assertArrayEquals(bytes, android.util.Base64.decode(row.getString("base64"), android.util.Base64.DEFAULT))
        }
        record.put("submitted_exact_native_path_array", true).put("js_exact_bytes", JSONObject(expected.mapValues { hash(it.value) }))
            .put("destination", "Owned Detail with base64 bytes read by the real extension fs bridge")
        capture("file-byte-destination")
    }
    private fun returnFromFileDestination() {
        touch("extension-back")
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        compose.waitForIdle(); capture("file-returned-form")
        touch("extension-back")
        compose.waitUntil(5000) { compose.activity.store.dialog == null }
        compose.onNodeWithTag("search").assertTextEquals(caller)
        record.put("caller_query_restored", true)
    }
    @Test fun filePickerImportsExactUnicodeBytesThroughAndroidAndReturnsToCaller() {
        launch("typed-file-single-workflow")
        val device = openOwnedDocuments()
        clickDocumentText(device, "café 日本.txt")
        val paths = awaitImportedFiles(1, "text")
        assertEquals("café 日本.txt", File(paths.single()).name)
        assertArrayEquals("Owned file café 日本 🌱\n".toByteArray(), File(paths.single()).readBytes())
        capture("file-imported-unicode")
        touch("extension-files-done-files")
        submitImportedFiles(mapOf("café 日本.txt" to "Owned file café 日本 🌱\n".toByteArray()))
        returnFromFileDestination(); record.put("passed", true)
    }
    @Test fun filePickerCancellationPreservesTheValueAndDoesNotImport() {
        launch("typed-file-single-workflow")
        val device = openOwnedDocuments()
        device.pressBack()
        compose.waitForIdle(); compose.onNodeWithTag("extension-files-dialog-files").assertIsDisplayed()
        assertTrue(typedFileValues().isEmpty()); assertEquals(0, probe().getInt("changes"))
        assertTrue(File(context.filesDir, "extension-data/${requireNotNull(ownedId)}/form-input").listFiles().orEmpty().isEmpty())
        capture("file-picker-cancelled")
        touch("extension-files-done-files"); record.put("cancel_preserved_selection_and_changes", true).put("passed", true)
    }
    @Test fun filePickerRejectsAnOversizeDocumentWithoutChangingItsSelection() {
        launch("typed-file-single-workflow")
        val device = openOwnedDocuments()
        clickDocumentText(device, "Owned too large.bin")
        compose.waitUntil(15000) { compose.onAllNodesWithTag("extension-field-error-files-import-files").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(typedFileValues().isEmpty()); assertEquals(0, probe().getInt("changes"))
        compose.waitUntil(5000) { File(context.filesDir, "extension-data/${requireNotNull(ownedId)}/form-input").listFiles().orEmpty().isEmpty() }
        capture("file-import-error")
        record.put("negative_control", "Owned provider declares a 17 MiB size; actual-stream bounds are separately tested")
            .put("selection_and_callback_count_unchanged", true).put("passed", true)
    }
    @Test fun filePickerImportsARealSelectedFolderAndNestedBytes() {
        launch("typed-file-folder-workflow")
        val device = openOwnedDocuments(true)
        clickDocumentText(device, "Owned folder")
        assertTrue(documentRow(device, "nested.txt").waitForExists(5000))
        clickDocumentText(device, "(?i)use this folder", pattern = true)
        val allowSelector = By.text(java.util.regex.Pattern.compile("(?i)allow"))
        assertTrue(device.wait(Until.hasObject(allowSelector), 5000))
        captureDocumentPicker("android-folder-permission", device)
        clickDocumentText(device, "(?i)allow", pattern = true)
        val path = awaitImportedFiles(1).single(); assertTrue(File(path).isDirectory)
        assertEquals("Owned folder", File(path).name)
        assertArrayEquals("Owned nested café 日本 🌱\n".toByteArray(), File(path, "nested.txt").readBytes())
        capture("file-folder-imported"); touch("extension-files-done-files")
        submitImportedFiles(mapOf("Owned folder" to "Owned nested café 日本 🌱\n".toByteArray()), true)
        returnFromFileDestination(); record.put("passed", true)
    }
    @Test fun filePickerMultipleSelectionPreservesBinaryBytesAndCanRemoveAndClear() {
        launch("typed-file-multiple-workflow")
        val device = openOwnedDocuments()
        clickDocumentText(device, "café 日本.txt", long = true)
        clickDocumentText(device, "owned-bytes.bin")
        val openSelector = By.res(record.getString("system_picker_package"), "action_menu_select")
        assertTrue(device.wait(Until.hasObject(openSelector), 5000))
        assertTrue(device.hasObject(By.text("2 selected")))
        captureDocumentPicker("android-two-documents-selected", device)
        clickDocumentResource(device, "action_menu_select")
        val paths = awaitImportedFiles(2)
        assertEquals(setOf("café 日本.txt", "owned-bytes.bin"), paths.map { File(it).name }.toSet())
        capture("file-two-imported"); touch("extension-files-done-files")
        submitImportedFiles(mapOf("café 日本.txt" to "Owned file café 日本 🌱\n".toByteArray(), "owned-bytes.bin" to byteArrayOf(0, 1, 2, -2, -1, 10)))
        touch("extension-back"); compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        touch("extension-files-files")
        val binary = typedFileValues().indexOfFirst { File(it).name == "owned-bytes.bin" }; assertTrue(binary >= 0)
        touch("extension-files-remove-files-$binary")
        compose.waitUntil(5000) { typedFileValues().map { File(it).name } == listOf("café 日本.txt") }
        compose.waitForIdle(); capture("file-one-removed")
        touch("extension-files-clear-files")
        compose.waitUntil(5000) { typedFileValues().isEmpty() }
        compose.waitForIdle(); capture("file-selection-cleared"); touch("extension-files-done-files")
        submitImportedFiles(emptyMap()); returnFromFileDestination(); record.put("passed", true)
    }
    @Test fun filePickerResultCannotEnterAReplacementCommand() {
        launch("typed-file-single-workflow")
        val device = openOwnedDocuments(); val epoch = catalog.launchEpoch
        compose.activityRule.scenario.onActivity { it.store.openExtension(ownedExtension, ownedExtension.commands.single()) }
        assertNotEquals(epoch, catalog.launchEpoch)
        clickDocumentText(device, "café 日本.txt")
        compose.waitUntil(25000) { catalog.runtimeReady && activeRoot(catalog.runtimeTree)?.optString("type") == "Form" }
        compose.waitForIdle(); assertTrue(typedFileValues().isEmpty()); assertEquals(0, probe().getInt("changes"))
        assertTrue(File(context.filesDir, "extension-data/${requireNotNull(ownedId)}/form-input").listFiles().orEmpty().isEmpty())
        capture("file-late-selection-rejected")
        record.put("transition", "Same owned command relaunched while real DocumentsUI was open; old selection leaves successor unchanged")
            .put("successor_selection_empty", true).put("passed", true)
    }
    @Test fun disposedFilePickerRegistrationCannotConsumeASuccessorResult() {
        launch("typed-controls")
        val codes = mutableListOf<Int>()
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) { codes.add(requestCode) }
        }
        var oldCalls = 0; var successorCalls = 0
        compose.activityRule.scenario.onActivity {
            val old = FormDocumentPicker(registry)
            old.launch(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)) { oldCalls++ }
            old.close()
            val successor = FormDocumentPicker(registry)
            successor.launch(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)) { successorCalls++ }
            assertEquals(2, codes.size); assertNotEquals(codes[0], codes[1])
            assertTrue(registry.dispatchResult(codes[0], android.app.Activity.RESULT_OK, android.content.Intent()))
            assertEquals(0, oldCalls); assertEquals(0, successorCalls)
            assertTrue(registry.dispatchResult(codes[1], android.app.Activity.RESULT_OK, android.content.Intent()))
            assertEquals(0, oldCalls); assertEquals(1, successorCalls)
            successor.close()
        }
        record.put("test_boundary", "Controlled real ActivityResultRegistry dispatch: distinct unsaved keys, disposed-result tombstone, exact successor callback count; no system picker launch")
            .put("old_callbacks", oldCalls).put("successor_callbacks", successorCalls)
        capture("file-registry-isolation"); record.put("passed", true)
    }
    private fun memoryFileSource(open: () -> java.io.InputStream) = object : FormDocumentSource {
        override fun document(key: String) = FormDocument(key, "owned-memory.bin", false)
        override fun children(document: FormDocument, visit: (FormDocument) -> Unit) = Unit
        override fun open(document: FormDocument) = open()
    }
    @Test fun cancelledFileImportCannotDeliverAfterItsBlockedReadFinishes() {
        launch("typed-controls")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val offMain = AtomicBoolean(false); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val request = FormDocumentImportRequest(FormFileStorage(context.filesDir, requireNotNull(ownedId)), listOf("owned"), true, false) {
            memoryFileSource {
                object : java.io.ByteArrayInputStream(byteArrayOf(0, 1, 2, -1)) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        offMain.set(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()); entered.countDown()
                        while (release.count > 0) try { release.await(30, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { /* Controlled uncooperative provider read. */ }
                        finished.countDown(); return super.read(bytes, offset, length)
                    }
                }
            }
        }
        var deliveries = 0
        try {
            compose.activityRule.scenario.onActivity { scope.launch { request.prepare(); request.deliver { deliveries++; true } } }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(offMain.get())
            touch("extension-files-files"); compose.onNodeWithTag("extension-files-dialog-files").assertIsDisplayed()
            compose.activityRule.scenario.onActivity { request.cancel(); scope.cancel() }
            release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5000) { File(context.filesDir, "extension-data/${requireNotNull(ownedId)}/form-input").listFiles().orEmpty().isEmpty() }
            assertEquals(0, deliveries); assertTrue(typedFileValues().isEmpty())
            capture("file-cancelled-read-released")
            record.put("test_boundary", "Controlled blocked InputStream on the actual bounded copy worker; real app pointer remains responsive; no DocumentsUI/provider-service timeout claim")
                .put("read_off_main", true).put("late_deliveries", deliveries).put("undelivered_copies_removed", true).put("passed", true)
        } finally { release.countDown(); request.cancel(); scope.cancel() }
    }
    @Test fun cancellationBetweenFilePreparationAndDeliveryRemovesOnlyTheUndeliveredCopy() {
        launch("typed-controls")
        val root = File(context.filesDir, "extension-data/${requireNotNull(ownedId)}")
        val original = File(root, "owned-existing.txt").apply { writeText("Owned pre-existing extension data") }
        val request = FormDocumentImportRequest(FormFileStorage(context.filesDir, requireNotNull(ownedId)), listOf("owned"), true, false) {
            memoryFileSource { java.io.ByteArrayInputStream(byteArrayOf(0, 1, 2, -1)) }
        }
        var deliveries = 0
        try {
            runBlocking { withTimeout(5000) { request.prepare() } }
            assertEquals(1, File(root, "form-input").listFiles().orEmpty().size)
            request.cancel()
            compose.waitUntil(5000) { File(root, "form-input").listFiles().orEmpty().isEmpty() }
            compose.activityRule.scenario.onActivity { assertFalse(request.deliver { deliveries++; true }) }
            assertEquals(0, deliveries); assertEquals("Owned pre-existing extension data", original.readText())
            record.put("test_boundary", "Controlled cancellation after successful prepare/commit but before field acceptance; no external picker launch")
                .put("late_deliveries", deliveries).put("unrelated_extension_data_preserved", true)
            capture("file-prepared-result-cancelled"); record.put("passed", true)
        } finally { request.cancel() }
    }
    @Test fun intentionalFileImportDeathRequiresIndependentHostRecovery() {
        assumeTrue("Select this process-death control by its exact method name", InstrumentationRegistry.getArguments().getString("class") ==
            "dev.tinycast.android.ExtensionFormDeviceTest#intentionalFileImportDeathRequiresIndependentHostRecovery")
        launch("typed-file-single-workflow")
        val root = formFileStorageRoot(context.filesDir, requireNotNull(ownedId))
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        val request = FormDocumentImportRequest(FormFileStorage(context.filesDir, requireNotNull(ownedId)), listOf("owned"), true, false) {
            memoryFileSource {
                object : java.io.InputStream() {
                    var first = true
                    override fun read(): Int = error("Use the bounded bulk-read path")
                    override fun read(target: ByteArray, offset: Int, length: Int): Int {
                        if (first) { first = false; target[offset] = 0; target[offset + 1] = 1; return 2 }
                        blocked.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "The owned death probe did not terminate" }
                        return -1
                    }
                }
            }
        }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val copying = worker.launch { request.prepare() }
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val pending = File(root, "form-input").listFiles().orEmpty().single()
            val nonce = pending.name.removePrefix("pending-")
            assertTrue(pending.name.startsWith("pending-")); assertEquals(nonce, UUID.fromString(nonce).toString())
            assertEquals(nonce, File(pending, ".owner").readText())
            val partial = pending.walkTopDown().single { it.isFile && it.name != ".owner" }
            assertArrayEquals(byteArrayOf(0, 1), partial.readBytes()); assertTrue(copying.isActive)
            ownedLease(); assertEquals(context.applicationInfo.uid, android.os.Process.myUid())
            compose.activityRule.scenario.onActivity {
                assertEquals("Owned Form clipboard sentinel", it.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            }
            record.put("scope", "Controlled owned worker import interrupted during a blocked read; no actual provider selection or JUnit cleanup")
                .put("intentional_process_death", true).put("manual_record_before_kill", true).put("blocked_worker_active", true)
                .put("pending_owner_validated", true).put("partial_copy_bytes", partial.length()).put("owned_clipboard_mutation_verified", true)
                .put("instrumentation_pid", android.os.Process.myPid()).put("owner_nonce", expectedOwner)
            capture("file-blocked-worker-before-death")
            assertTrue(copying.isActive); assertTrue(pending.exists())
            val name = "form-${testName.methodName.lowercase(java.util.Locale.ROOT)}-record.json"
            java.io.FileOutputStream(File(evidence, name)).use { output -> output.write(record.toString(2).toByteArray()); output.fd.sync() }
            android.os.Process.killProcess(android.os.Process.myPid())
            throw AssertionError("The explicitly selected owned instrumentation process must terminate")
        } finally { release.countDown(); request.cancel(); worker.cancel() }
    }

    @Test fun formsWithErrorsBlockPrimarySubmit() = assertErroredSubmitBlocked(false)
    @Test fun formsWithErrorsBlockActionsMenuKeyboardSubmit() = assertErroredSubmitBlocked(true)

    private fun assertErroredSubmitBlocked(menuKeyboard: Boolean, mode: String = "submit-validation") {
        launch(mode)
        assertEquals(submitValidationError, field("Form.TextField", "name").props().getString("error"))
        compose.onNodeWithTag("extension-field-error-name").performScrollTo().assertIsDisplayed()
        val actions = requireNotNull(activeRoot(catalog.runtimeTree)).actions()
        assertEquals("Submit owned values", actions.first().props().getString("title"))
        record.put("submit_route", if (menuKeyboard) "Actions menu / native Enter" else "Primary pointer action")
            .put("submit_native_action_type", actions.first().optString("type"))
            .put("initial_submit_count", probe().getInt("submits"))
        assertEquals(0, probe().getInt("submits"))
        capture("submit-error-visible")
        if (menuKeyboard) {
            touch("extension-actions")
            compose.onNodeWithTag("inline-menu-search").performTextReplacement("Submit owned values")
            compose.onNodeWithTag("inline-menu-search").assertTextEquals("Submit owned values").assertIsFocused()
            compose.onNodeWithTag("extension-action-0").assertIsDisplayed()
            record.put("submit_menu_query", "Submit owned values")
            capture("submit-actions-menu")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            compose.waitForIdle()
        } else touch("extension-primary")
        // A separate WebView evaluation follows the native action's queued
        // dispatch. Its callback is an execution barrier, not a timed retry.
        evaluateOwned("globalThis.__ownedSubmitBarrier=(globalThis.__ownedSubmitBarrier||0)+1;true")
        val observed = probe()
        record.put("observed_submit_count", observed.getInt("submits"))
            .put("observed_non_submit_actions", observed.getInt("nonSubmitActions"))
            .put("observed_dispatch_count", observed.getInt("dispatches"))
            .put("result_root_type", activeRoot(catalog.runtimeTree)?.optString("type"))
        capture("submit-action-result")
        assertEquals("An attempted Submit must not activate an ordinary action", 0, observed.getInt("nonSubmitActions"))
        assertEquals("A current Form error must block the SubmitForm callback", 0, observed.getInt("submits"))
        assertEquals("Form", activeRoot(catalog.runtimeTree)?.optString("type"))
        record.put("passed", true)
    }

    private fun submitBarrier() = evaluateOwned("globalThis.__ownedSubmitBarrier=(globalThis.__ownedSubmitBarrier||0)+1;true")
    private fun seedSubmitSession() {
        touch("extension-field-name")
        compose.onNodeWithTag("extension-field-name").performTextReplacement("Owned session seed")
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value") == "Owned session seed" }
        evaluateOwned("if(typeof globalThis.__ownedFormSession!=='string')throw Error('Owned dispatch session missing');true")
    }
    private fun submittedOwnedValues(): JSONObject {
        compose.waitUntil(10000) { probe().getInt("submits") >= 1 }
        assertEquals("One explicit request must invoke one Submit callback", 1, probe().getInt("submits"))
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Detail" }
        val result = JSONObject(requireNotNull(activeRoot(catalog.runtimeTree)).props().getString("markdown"))
        assertEquals("Owned SubmitForm callback reached", result.getString("outcome"))
        assertEquals(0, probe().getInt("nonSubmitActions"))
        return result.getJSONObject("values")
    }
    @Test fun submitBlursCurrentEmptyFieldBeforeCheckingErrors() {
        launch("submit-valid"); touch("extension-field-name")
        compose.onNodeWithTag("extension-field-name").performTextReplacement("")
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value").isEmpty() &&
            (field("Form.TextField", "name").props().opt("error") as? String).isNullOrBlank() }
        compose.onNodeWithTag("extension-primary").assertIsEnabled(); capture("empty-before-submit")
        touch("extension-primary"); submitBarrier()
        val observed = probe(); record.put("observed_submit_count", observed.getInt("submits")).put("observed_blurs", observed.getInt("blurs"))
        capture("empty-after-submit")
        assertEquals("Submit must allow current blur validation before invoking the callback", 0, observed.getInt("submits"))
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("error") == submitValidationError }
        assertEquals(1, probe().getInt("blurs")); compose.onNodeWithTag("extension-primary").assertIsNotEnabled()
        record.put("current_empty_blur_validated_once", true).put("passed", true)
    }
    @Test fun correctingAnErrorSubmitsExactUnicodeOnce() {
        launch("submit-validation"); touch("extension-field-name")
        val value = "Owned café é 日本 🌱"
        compose.onNodeWithTag("extension-field-name").performTextReplacement(value)
        compose.waitUntil(5000) { (field("Form.TextField", "name").props().opt("error") as? String).isNullOrBlank() &&
            field("Form.TextField", "name").props().optString("value") == value }
        compose.onNodeWithTag("extension-primary").assertIsEnabled(); capture("corrected-before-submit")
        touch("extension-primary"); val values = submittedOwnedValues()
        assertEquals(value, values.getString("name")); assertEquals("Owned 日本 café é 🌱", values.getString("notes"))
        capture("corrected-destination"); record.put("exact_valid_unicode_once", true).put("passed", true)
    }
    @Test fun ordinaryActionsRemainUsableWhileSubmitIsBlocked() {
        launch("submit-validation"); touch("extension-actions")
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Inspect owned validation")
        touch("extension-action-1")
        compose.waitUntil(5000) { probe().getInt("nonSubmitActions") == 1 }
        assertEquals(0, probe().getInt("submits")); assertEquals("Form", activeRoot(catalog.runtimeTree)?.optString("type"))
        capture("ordinary-action-with-error"); record.put("ordinary_action_preserved", true).put("passed", true)
    }
    @Test fun rapidControlledTransformIsTheSubmittedValue() = assertRapidSubmission("submit-transform", "OWNED CAFÉ É 日本 🌱")
    @Test fun rapidControlledRejectionKeepsTheAuthoritativeValue() = assertRapidSubmission("submit-reject", "OWNED FIXED")
    @Test fun rapidUncontrolledEditRemainsTheSubmittedValue() = assertRapidSubmission("submit-uncontrolled", "Owned café é 日本 🌱")
    private fun assertRapidSubmission(mode: String, expected: String) {
        launch(mode); touch("extension-field-name"); ime(false)
        val edit = requireNotNull(compose.onNodeWithTag("extension-field-name").fetchSemanticsNode().config[SemanticsActions.SetText].action)
        val submit = requireNotNull(compose.onNodeWithTag("extension-primary").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread { assertTrue(edit(AnnotatedString("Owned café é 日本 🌱"))); assertTrue(submit()) }
        val values = submittedOwnedValues(); assertEquals(expected, values.getString("name"))
        assertEquals("Owned 日本 café é 🌱", values.getString("notes")); capture("rapid-owned-destination")
        record.put("controlled_native_sequence", "Native SetText and captured primary activation in one UI turn before a Compose frame")
            .put("accepted_values_preserved", true).put("passed", true)
    }
    @Test fun legacySubmitAliasStillBlocksCurrentErrors() = assertErroredSubmitBlocked(false, "submit-alias")
    @Test fun defaultApiSubmitStillBlocksCurrentErrors() = assertErroredSubmitBlocked(false, "submit-default")
    @Test fun queuedNativeSubmitRejectsNewError() = assertQueuedNativeSubmitBlocked("error")
    @Test fun queuedNativeSubmitRejectsReplacementAction() = assertQueuedNativeSubmitBlocked("action")
    @Test fun queuedNativeSubmitRejectsChangedTitle() = assertQueuedNativeSubmitBlocked("title")
    private fun assertQueuedNativeSubmitBlocked(change: String) {
        launch("submit-valid")
        val submit = requireNotNull(compose.onNodeWithTag("extension-primary").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val original = requireNotNull(catalog.runtimeTree)
        val replacement = JSONObject(original.toString()); val current = requireNotNull(activeRoot(replacement))
        when (change) {
            "error" -> current.nodes("Form.TextField").single { it.props().optString("id") == "name" }.props().put("error", "Owned late validation error")
            "action" -> {
                // Remove Submit while retaining the real ordinary action and
                // its registered handler. An old primary must not adopt it.
                val ordinary = current.actions()[1]
                current.props().getJSONObject("actions").put("children", JSONArray().put(ordinary))
            }
            else -> {
                val action = current.actions().first(); action.props().put("title", "Different owned submission")
                (action.props().opt("__tinycastSubmitSignature") as? String)?.let {
                    action.props().put("__tinycastSubmitSignature", JSONArray(it).put(0, "Different owned submission").toString())
                }
            }
        }
        val before = probe().getInt("dispatches")
        compose.activityRule.scenario.onActivity {
            try { catalog.runtimeTree = replacement; submit() } finally { catalog.runtimeTree = original }
        }
        submitBarrier(); val observed = probe()
        record.put("observed_submit_count", observed.getInt("submits"))
            .put("observed_non_submit_actions", observed.getInt("nonSubmitActions"))
            .put("dispatch_delta", observed.getInt("dispatches") - before)
            .put("controlled_native_transition", "Published native $change model before captured primary callback in one UI turn; original restored before a Compose frame")
        capture("queued-submit-model-restored")
        assertEquals("Queued Submit must not adopt a changed action or new error state", 0,
            observed.getInt("submits") + observed.getInt("nonSubmitActions"))
        assertEquals(before, observed.getInt("dispatches")); record.put("passed", true)
    }
    @Test fun queuedJsSubmitRechecksPublishedErrors() {
        launch("submit-valid"); seedSubmitSession()
        val handle = requireNotNull(requireNotNull(activeRoot(catalog.runtimeTree)).actions().first().handle("onAction"))
        evaluateOwned("globalThis.__ownedSubmit.setError('Owned queued validation error');true")
        compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("error") == "Owned queued validation error" }
        val before = probe().getInt("dispatches")
        evaluateOwned("globalThis.__tinycast.dispatch(globalThis.__ownedFormSession,${JSONObject.quote(handle)},'[]');true")
        submitBarrier(); val observed = probe()
        record.put("observed_submit_count", observed.getInt("submits")).put("controlled_js_dispatch", "Owned queued handler invoked after error publication, bypassing the disabled native control")
        assertEquals(before + 1, observed.getInt("dispatches")); capture("queued-js-error-result")
        assertEquals("The JavaScript Submit gate must recheck current errors", 0, observed.getInt("submits"))
        record.put("passed", true)
    }
    @Test fun queuedSubmitCannotRunWithoutItsRoot() {
        launch("submit-valid")
        val submit = requireNotNull(compose.onNodeWithTag("extension-primary").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val original = requireNotNull(catalog.runtimeTree)
        val before = probe().getInt("dispatches")
        compose.activityRule.scenario.onActivity {
            try { catalog.runtimeTree = null; submit() } finally { catalog.runtimeTree = original }
        }
        submitBarrier(); val observed = probe()
        record.put("observed_submit_count", observed.getInt("submits"))
            .put("dispatch_delta", observed.getInt("dispatches") - before)
            .put("controlled_native_transition", "Root absent before captured primary callback in one UI turn; original restored before a Compose frame")
        capture("absent-root-restored")
        assertEquals(0, observed.getInt("submits")); assertEquals(before, observed.getInt("dispatches"))
        record.put("passed", true)
    }
    @Test fun queuedSubmitCannotAdoptAReplacementRoot() {
        launch("submit-valid")
        val submit = requireNotNull(compose.onNodeWithTag("extension-primary").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val original = requireNotNull(catalog.runtimeTree); val replacement = JSONObject(original.toString())
        val root = requireNotNull(activeRoot(replacement)); root.put("id", root.optString("id") + "-owned-replacement")
        val before = probe().getInt("dispatches")
        compose.activityRule.scenario.onActivity {
            try { catalog.runtimeTree = replacement; submit() } finally { catalog.runtimeTree = original }
        }
        submitBarrier(); val observed = probe()
        record.put("observed_submit_count", observed.getInt("submits"))
            .put("observed_successor_submits", observed.getInt("successorSubmits"))
            .put("controlled_native_transition", "Root identity replaced before captured primary callback in one UI turn; original restored before a Compose frame")
        capture("replacement-root-restored")
        assertEquals(0, observed.getInt("submits")); assertEquals(0, observed.getInt("successorSubmits"))
        assertEquals(before, observed.getInt("dispatches")); record.put("passed", true)
    }
    @Test fun queuedJsSubmitCannotReplayAnInactiveFormAfterNavigation() {
        launch("submit-valid"); seedSubmitSession()
        val originalRoot = requireNotNull(activeRoot(catalog.runtimeTree))
        val handle = requireNotNull(originalRoot.actions().first().handle("onAction"))
        evaluateOwned("globalThis.__ownedSubmit.pushReplacement();true")
        compose.waitUntil(5000) { activeRoot(catalog.runtimeTree)?.optString("type") == "Form" && activeRoot(catalog.runtimeTree)?.optString("id") != originalRoot.optString("id") }
        val successorId = requireNotNull(activeRoot(catalog.runtimeTree)).optString("id")
        capture("actual-successor-form")
        evaluateOwned("globalThis.__formProbe.queuedDispatchResult=globalThis.__tinycast.dispatch(globalThis.__ownedFormSession,${JSONObject.quote(handle)},'[]');true")
        submitBarrier(); val observed = probe()
        record.put("observed_submit_count", observed.getInt("submits"))
            .put("observed_successor_submits", observed.getInt("successorSubmits"))
            .put("queued_dispatch_result", observed.getString("queuedDispatchResult"))
            .put("controlled_js_dispatch", "Old owned handler requested after actual navigation to a successor Form")
        capture("inactive-form-dispatch-result")
        assertEquals("Inactive Form Submit must not replay after navigation", 0, observed.getInt("submits"))
        assertEquals(0, observed.getInt("successorSubmits")); assertEquals(successorId, activeRoot(catalog.runtimeTree)?.optString("id"))
        record.put("passed", true)
    }
    @Test fun queuedSubmitCannotCrossANewLaunchEpoch() {
        launch("submit-valid")
        val submit = requireNotNull(compose.onNodeWithTag("extension-primary").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = probe().getInt("dispatches")
        compose.activityRule.scenario.onActivity {
            val epoch = catalog.launchEpoch
            try { catalog.launchEpoch++; submit() } finally { catalog.launchEpoch = epoch }
        }
        submitBarrier(); assertEquals(before, probe().getInt("dispatches")); assertEquals(0, probe().getInt("submits"))
        capture("replaced-session-restored"); record.put("controlled_native_transition", "Launch epoch replaced before captured Submit callback; restored before a Compose frame")
            .put("passed", true)
    }
    @Test fun reopenedSubmitMenuRejectsItsOldItemCallback() {
        launch("submit-valid"); touch("extension-actions")
        compose.onNodeWithTag("inline-menu-search").performTextReplacement("Submit owned values")
        val choose = requireNotNull(compose.onNodeWithTag("extension-action-0").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("inline-menu").fetchSemanticsNodes().isEmpty() }
        touch("extension-actions"); compose.onNodeWithTag("inline-menu-search").performTextReplacement("Submit owned values")
        compose.runOnUiThread { choose() }; submitBarrier()
        assertEquals(0, probe().getInt("submits")); compose.onNodeWithTag("inline-menu").assertIsDisplayed()
        compose.onNodeWithTag("inline-menu-search").assertTextEquals("Submit owned values")
        capture("reopened-submit-menu"); record.put("queued_menu_reopening_rejected", true).put("passed", true)
    }
    @Test fun composingFieldEnterCannotSubmitTheForm() {
        launch("submit-valid"); touch("extension-field-name"); ime(false)
        val info = android.view.inputmethod.EditorInfo(); var connection: android.view.inputmethod.InputConnection? = null
        compose.activityRule.scenario.onActivity { connection = it.window.decorView.findFocus()?.onCreateInputConnection(info) }
        val editor = requireNotNull(connection)
        try {
            compose.runOnUiThread { assertTrue(editor.setComposingText("Owned composing 日本", 1)) }
            compose.waitUntil(5000) { field("Form.TextField", "name").props().optString("value").contains("Owned composing 日本") }
            assertTrue(compose.onNodeWithTag("extension-field-name").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.contains("Owned composing 日本"))
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            submitBarrier(); assertEquals(0, probe().getInt("submits"))
            assertEquals("Form", activeRoot(catalog.runtimeTree)?.optString("type"))
            capture("composing-enter-retained"); record.put("controlled_composition_enter_not_submit", true).put("passed", true)
        } finally { compose.runOnUiThread { editor.finishComposingText(); editor.closeConnection() } }
    }
    @Test fun duplicateQueuedSubmitDispatchesInvokeOneCallback() {
        launch("submit-valid"); seedSubmitSession()
        val handle = requireNotNull(requireNotNull(activeRoot(catalog.runtimeTree)).actions().first().handle("onAction"))
        evaluateOwned("globalThis.__tinycast.dispatch(globalThis.__ownedFormSession,${JSONObject.quote(handle)},'[]');globalThis.__tinycast.dispatch(globalThis.__ownedFormSession,${JSONObject.quote(handle)},'[]');true")
        val values = submittedOwnedValues(); assertEquals("Owned session seed", values.getString("name"))
        capture("coalesced-owned-destination"); record.put("controlled_js_dispatch", "Two owned handler requests in one JavaScript stack; not a physical double-tap timing claim")
            .put("passed", true)
    }
    @Test fun everySupportedFieldErrorBlocksBothNativeAndQueuedJsSubmit() {
        launch("submit-fields"); seedSubmitSession(); ime(false)
        val kinds = listOf("name" to "Form.TextField", "notes" to "Form.TextArea", "secret" to "Form.PasswordField", "enabled" to "Form.Checkbox", "choice" to "Form.Dropdown")
        val checked = JSONArray()
        for ((id, type) in kinds) {
            evaluateOwned("globalThis.__ownedSubmit.setErrorField(${JSONObject.quote(id)});true")
            compose.waitUntil(5000) { field(type, id).props().optString("error") == submitValidationError }
            compose.onNodeWithTag("extension-field-error-$id").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("extension-primary").assertIsNotEnabled(); touch("extension-primary")
            val handle = requireNotNull(requireNotNull(activeRoot(catalog.runtimeTree)).actions().first().handle("onAction"))
            evaluateOwned("globalThis.__tinycast.dispatch(globalThis.__ownedFormSession,${JSONObject.quote(handle)},'[]');true")
            submitBarrier(); assertEquals("Error in $type must block submission", 0, probe().getInt("submits"))
            checked.put(type); capture("blocked-$id")
        }
        record.put("current_error_field_kinds_checked", checked).put("passed", true)
    }
    @Test fun blockedSubmitFeedbackStaysReachableAtLargeFontsAndImeTransitions() {
        launch("submit-long"); touch("extension-field-name")
        for ((index, shown) in listOf(false, true, false).withIndex()) {
            ime(shown)
            inspectTextLines("extension-form", listOf(compose.onNodeWithTag("extension-field-title-name"),
                compose.onNodeWithTag("extension-field-error-name"), compose.onNodeWithTag("extension-field-info-name")), "submit-long-$index")
            compose.onNodeWithTag("extension-primary").assertIsNotEnabled()
            touch("extension-primary"); submitBarrier(); assertEquals(0, probe().getInt("submits"))
        }
        touch("extension-actions"); compose.onNodeWithTag("inline-menu-search").performTextReplacement("Submit owned values")
        compose.onNodeWithTag("extension-action-0").assertIsNotEnabled()
        inspectTextLines("inline-menu-choices", listOf(compose.onNodeWithText("Submit owned values · Correct highlighted fields", useUnmergedTree = true)), "blocked-submit-option")
        assertEquals(0, probe().getInt("submits")); record.put("passed", true)
    }

}
