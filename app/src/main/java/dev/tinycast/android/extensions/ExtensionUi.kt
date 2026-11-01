@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.tinycast.android.extensions

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteStore
import dev.tinycast.android.PaletteTheme
import dev.tinycast.android.PaletteChoice
import dev.tinycast.android.PaletteChoiceMenu
import dev.tinycast.android.paletteHeaderSurface
import dev.tinycast.android.paletteFocusBookmark
import dev.tinycast.android.LocalPaletteMenus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val ink = PaletteTheme.ink
private val muted = PaletteTheme.secondary
private val line = PaletteTheme.separator
private val card = PaletteTheme.control

private class StoreBrowserState {
    val search = mutableStateOf(TextFieldValue(""))
    val installedTab = mutableStateOf(false)
    val found = mutableStateOf<List<StoreExtension>>(emptyList())
    val loading = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val pending = mutableStateOf<StoreExtension?>(null)
    val removing = mutableStateOf<InstalledExtension?>(null)
    val page = mutableIntStateOf(1)
    val retry = mutableIntStateOf(0)
    val installTarget = mutableStateOf<String?>(null)
    val scroll = androidx.compose.foundation.lazy.LazyListState()
    var lastRequest: Triple<String, Int, Int>? = null
}

private class ExtensionListState {
    var search by mutableStateOf(TextFieldValue(""))
    var selected by mutableIntStateOf(0)
    val selectionDelivery = ExtensionSelectionDelivery()
    val scroll = androidx.compose.foundation.lazy.LazyListState()
}

@Composable
fun ExtensionDialogs(store: PaletteStore, close: () -> Unit) {
    val browser = remember { StoreBrowserState() }
    if(store.dialog == "extensions") StoreBrowser(store, browser, close)
    else store.extensions.running?.let { (extension, command) ->
        key(store.extensions.launchEpoch) { ExtensionSession(store, extension, command, close) }
    }
}

@Composable
private fun ExtensionSession(store: PaletteStore, extension: InstalledExtension, command: ExtensionCommand, close: () -> Unit) {
    if(command.mode !in listOf("view","no-view")) {
        ExtensionWindow(command.title,extension.listing.title,close){Text("This command needs a desktop menu bar. It can be installed, but cannot run on Android.",color=muted,fontSize=15.sp,modifier=Modifier.padding(24.dp))}
        return
    }
    val context = LocalContext.current
    val runtime = remember { ExtensionRuntime(context, store.extensions, extension, command) }
    val images = remember { ExtensionGridImages(extension.directory) }
    val grids = remember { mutableMapOf<String, ExtensionGridState>() }
    val lists = remember { mutableMapOf<String, ExtensionListState>() }
    val details = remember { mutableMapOf<String, ScrollState>() }
    var started by remember { mutableStateOf(false) }
    DisposableEffect(runtime) { onDispose { runtime.close(); images.close() } }
    LaunchedEffect(store.dialog) {
        if(store.dialog == "extension-run" && !started) { started = true; runtime.start() }
    }
    // A session remains attached while its preferences replace the body.
    Box(Modifier.size(0.dp).clipToBounds()) { AndroidView(factory={runtime.webView},modifier=Modifier.requiredSize(1.dp)) }
    if(store.dialog == "extension-setup") CommandSetup(store, extension, command) {
        if(store.extensionSetupFromRun && started) { store.extensionSetupFromRun = false; store.dialog="extension-run" }
        else close()
    }
    else CommandRunner(store, extension, command, runtime, images, grids, lists, details, close)
}

@Composable
private fun ExtensionWindow(title: String, subtitle: String, close: () -> Unit, modifier: Modifier = Modifier,
                            compact: Boolean = false, onEscape: () -> Unit = close, headerless: Boolean = false,
                            headerActions: (@Composable RowScope.() -> Unit)? = null,
                            content: @Composable ColumnScope.() -> Unit) {
    val escapePresses = remember { mutableMapOf<Int, Long>() }
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val menus = LocalPaletteMenus.current
    val menuOpen = menus.current != null
    LaunchedEffect(windowFocused, hasFocus, menuOpen) {
        if (windowFocused && !hasFocus && !menuOpen) { withFrameNanos { }; if (!hasFocus && menus.current == null) focus.requestFocus() }
        if (!windowFocused) escapePresses.clear()
    }
    dev.tinycast.android.PaletteFrame(onEscape, modifier.focusRequester(focus).onFocusChanged { hasFocus = it.hasFocus }
        .focusable().onPreviewKeyEvent {
            val event = it.nativeKeyEvent
            if (event.keyCode != android.view.KeyEvent.KEYCODE_ESCAPE) false
            else if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) { escapePresses[event.deviceId] = event.downTime; onEscape() }; true
            } else if (escapePresses[event.deviceId] == event.downTime) { escapePresses.remove(event.deviceId); true } else false
        }, tag = "extension-palette") {
        if (!headerless) {
            Row(Modifier.fillMaxWidth().heightIn(min=PaletteTheme.header).padding(end=8.dp,top=4.dp,bottom=4.dp).paletteHeaderSurface(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEscape, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }.testTag("extension-back")) { Glyph("back",PaletteTheme.ink,24) }
                Spacer(Modifier.width(2.dp))
                Text(title, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                headerActions?.invoke(this)
            }
            HorizontalDivider(color = line)
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionSearchHeader(value: TextFieldValue, change: (TextFieldValue) -> Unit, placeholder: String, tag: String,
                                  back: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    val focus = remember { FocusRequester() }
    val wide = dev.tinycast.android.LocalPaletteWide.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var requested by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ime = WindowInsets.isImeVisible
    LaunchedEffect(wide, ime, requested, focused, windowFocused) {
        if(wide && !requested && focused && windowFocused) {
            withFrameNanos { }
            keyboard?.hide()
            androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min=PaletteTheme.header).padding(end=8.dp,top=4.dp,bottom=4.dp).paletteHeaderSurface(),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick=back,modifier=Modifier.size(48.dp).semantics{contentDescription="Back"}.testTag("extension-back")){Glyph("back",PaletteTheme.ink,24)}
        Spacer(Modifier.width(2.dp))
        BasicTextField(value,change,singleLine=true,textStyle=TextStyle(color=ink,fontSize=20.sp,lineHeight=26.sp),cursorBrush=SolidColor(PaletteTheme.accent),
            keyboardOptions=KeyboardOptions(showKeyboardOnFocus=!wide || requested),
            modifier=Modifier.weight(1f).heightIn(min=48.dp).paletteFocusBookmark(focus).onFocusChanged{focused=it.isFocused}.testTag(tag),
            decorationBox={inner->Box(Modifier.fillMaxWidth().heightIn(min=48.dp),contentAlignment=Alignment.CenterStart){if(value.text.isEmpty())Text(placeholder,color=muted,fontSize=20.sp,lineHeight=26.sp,maxLines=1,overflow=TextOverflow.Ellipsis);inner()}})
        if(wide)IconButton(onClick={requested=true;focus.requestFocus();keyboard?.show()},modifier=Modifier.size(48.dp).semantics{contentDescription="Show on-screen keyboard"}){Glyph("keyboard",muted,16)}
        trailing()
    }
    HorizontalDivider(color=line)
}

@Composable
private fun StoreBrowser(store: PaletteStore, state: StoreBrowserState, close: () -> Unit) {
    val catalog=store.extensions
    var storeSearch by state.search
    val query = storeSearch.text
    val storeScroll = state.scroll
    var installedTab by state.installedTab
    var found by state.found
    var loading by state.loading
    var error by state.error
    var pending by state.pending
    var removing by state.removing
    var page by state.page
    var retry by state.retry
    var installTarget by state.installTarget
    LaunchedEffect(catalog.installed,catalog.busy){if(installTarget!=null&&catalog.busy==null&&catalog.error==null&&catalog.installed.any{it.listing.id==installTarget}){installedTab=true;installTarget=null}}
    LaunchedEffect(query,page,retry,installedTab){
        if(!installedTab){
            val request = Triple(query,page,retry)
            if(state.lastRequest == request) return@LaunchedEffect
            loading=true;error=null
            try { delay(250); val next=catalog.search(query,page); found=if(page==1)next else (found+next).distinctBy{it.id}; state.lastRequest=request }
            catch(e:CancellationException){throw e}catch(e:Throwable){error=e.message ?: "Could not reach Raycast Store"}finally{loading=false}
        }
    }
    if (pending != null || removing != null) {
        val installing = pending
        val uninstalling = removing
        val title = if (installing != null) "Install ${installing.title}?" else "Uninstall ${uninstalling!!.listing.title}?"
        fun cancelReview() { pending = null; removing = null }
        ExtensionWindow(title, "Review extension access", ::cancelReview) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp).testTag("extension-review")) {
                Text(if (installing != null) "This installs third-party JavaScript from Raycast Store. When you run its commands it can use HTTPS, access the foreground clipboard and save its own data. Desktop-specific commands may not work on Android."
                    else "Its commands, preferences and local extension data will be removed.", color = muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::cancelReview) { Text("Cancel") }
                Button(onClick = {
                    if (installing != null) { pending = null; installTarget = installing.id; store.installExtension(installing) }
                    else uninstalling?.let { removing = null; store.uninstallExtension(it) }
                }, enabled = catalog.busy == null, modifier = Modifier.testTag(if(installing!=null) "confirm-install" else "confirm-uninstall")) { Text(if(installing!=null) "Install extension" else "Uninstall") }
            }
        }
        return
    }
    ExtensionWindow("Raycast Store","Install real Store bundles. Android compatibility varies by command.",close,headerless=true){
        ExtensionSearchHeader(storeSearch, { next -> if(next.text != query) page=1; storeSearch=next }, if(installedTab) "Search installed extensions…" else "Search Raycast Store…", "store-search", close) {
            if(loading) CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
            TextButton(onClick={installedTab=false},modifier=Modifier.testTag("store-browse")){Text("Store",fontSize=11.sp,color=if(!installedTab)ink else muted)}
            TextButton(onClick={installedTab=true},modifier=Modifier.testTag("store-installed")){Text("Installed",fontSize=11.sp,color=if(installedTab)ink else muted)}
        }
        val visibleError=error ?: catalog.error
        if(visibleError!=null) Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Text(visibleError,color=MaterialTheme.colorScheme.error,fontSize=12.sp,modifier=Modifier.weight(1f));TextButton(onClick={retry++}){Text("Retry")}}
        LazyColumn(state=storeScroll,modifier=Modifier.weight(1f).fillMaxWidth().testTag("store-results"),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(installedTab){
                if(catalog.installed.isEmpty())item{Text("Installed extensions will appear here. Browse the Store to add your first one.",color=muted,modifier=Modifier.padding(16.dp))}
                items(catalog.installed.filter { query.isBlank() || (it.listing.title+" "+it.listing.author+" "+it.commands.joinToString { c->c.title }).contains(query,true) },key={it.listing.id}){ext->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(card).padding(16.dp)){
                        Text(ext.listing.title,fontSize=17.sp,fontWeight=FontWeight.Medium)
                        Text("${ext.listing.author} · ${ext.commands.size} commands",color=muted,fontSize=11.sp)
                        Row{ TextButton(onClick={pending=ext.listing},enabled=catalog.busy==null){Text("Update from Store")}
                            TextButton(onClick={removing=ext},enabled=catalog.busy==null,modifier=Modifier.testTag("uninstall-${ext.listing.name}")){Text("Uninstall",color=muted)} }
                        ext.commands.forEach{command->
                            Row(Modifier.fillMaxWidth().clickable{store.openExtension(ext,command)}.padding(vertical=9.dp).testTag("extension-command-${command.name}"),verticalAlignment=Alignment.CenterVertically){
                                Glyph("terminal",ink,17);Spacer(Modifier.width(10.dp));Text(command.title,fontSize=13.sp,modifier=Modifier.weight(1f))
                                Text(if(command.mode=="menu-bar")"Desktop only" else "Run ↵",fontSize=10.sp,color=muted)
                            }
                        }
                    }
                }
            }else{
                items(found,key={it.id}){ext->
                    val installed=catalog.installed.any{it.listing.id==ext.id}
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(card).padding(16.dp).testTag("store-item-${ext.name}")){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Box(Modifier.size(38.dp).background(ink.copy(alpha=.1f),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center){Glyph("extension",ink,22)}
                            Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){
                                Text(ext.title,fontSize=16.sp,fontWeight=FontWeight.Medium)
                                Text("${ext.author} · ${ext.commandCount} commands",color=muted,fontSize=11.sp,lineHeight=17.sp)
                            }
                            Button(onClick={pending=ext},enabled=catalog.busy==null,modifier=Modifier.testTag("install-${ext.name}")){
                                Text(if(catalog.busy==ext.id)"Installing…" else if(installed)"Update" else "Install",fontSize=12.sp)
                            }
                        }
                        Text(ext.description,color=muted,fontSize=12.sp,lineHeight=18.sp,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=10.dp))
                    }
                }
                if(found.isNotEmpty())item{TextButton(onClick={page++},enabled=!loading){Text("Load more")}}
                if(!loading&&found.isEmpty()&&error==null)item{Text("No extensions found. Try another search.",color=muted)}
            }
        }
        HorizontalDivider(color=line)
        Text("Native lists/image grids, basic forms, clipboard, local storage & HTTPS are supported. Mac apps, shell commands, OAuth and menu-bar integrations need desktop APIs.",color=muted,fontSize=10.sp,lineHeight=15.sp,modifier=Modifier.padding(18.dp,12.dp))
    }

}

@Composable
private fun CommandSetup(store:PaletteStore,extension:InstalledExtension,command:ExtensionCommand,close:()->Unit){
    val fields=extension.fields(command)
    val args=command.definition.optJSONArray("arguments").objects()
    var preferences by remember{mutableStateOf(store.extensions.preferences(extension,command))}
    var arguments by remember{mutableStateOf(store.extensions.arguments)}
    var error by remember{mutableStateOf<String?>(null)}
    ExtensionWindow(command.title,"${extension.listing.title} · arguments & preferences",close){
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            args.forEach{f->val name=f.getString("name");OutlinedTextField(arguments[name].orEmpty(),{arguments=arguments+(name to it)},label={Text(name)},placeholder={Text(f.optString("placeholder"))},singleLine=true,modifier=Modifier.fillMaxWidth().paletteFocusBookmark().testTag("argument-$name"))}
            fields.forEach{f->val name=f.getString("name");val label=f.optString("title",name);val value=preferences.opt(name)
                fun change(v:Any){preferences=JSONObject(preferences.toString()).put(name,v)}
                when(f.optString("type")){
                    "checkbox"->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(value==true,{change(it)});Text(f.optString("label",label),fontSize=13.sp)}
                    "dropdown"->{var expanded by remember{mutableStateOf(false)};Column{Text(label,color=muted,fontSize=12.sp);Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.paletteFocusBookmark()){Text(value?.toString().orEmpty().ifBlank{"Choose…"})};PaletteChoiceMenu(expanded,{expanded=false},f.optJSONArray("data").objects().mapIndexed{index,option->PaletteChoice("preference-option-$name-$index-${option.getString("value")}",option.optString("title"),checked=value?.toString()==option.getString("value")){change(option.getString("value"));expanded=false}},title=label)}}}
                    else->OutlinedTextField(if(value==null||value==JSONObject.NULL)"" else value.toString(),{change(it)},label={Text(label)},supportingText={Text(f.optString("description"),fontSize=10.sp)},singleLine=true,visualTransformation=if(f.optString("type")=="password")PasswordVisualTransformation()else VisualTransformation.None,modifier=Modifier.fillMaxWidth().paletteFocusBookmark().testTag("preference-$name"))
                }
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
        Button(onClick={
            val missingArg=args.firstOrNull{it.optBoolean("required")&&arguments[it.getString("name")].isNullOrBlank()}
            val missingPref=fields.firstOrNull{it.optBoolean("required")&&it.optString("type")!="checkbox"&&preferences.optString(it.getString("name")).isBlank()}
            if(missingArg!=null||missingPref!=null)error="Complete the required arguments and preferences."
            else{store.extensions.savePreferences(extension,preferences);store.extensionSetupFromRun=false;store.extensions.launch(extension,command,arguments);store.dialog="extension-run"}
        },modifier=Modifier.fillMaxWidth().padding(20.dp).testTag("run-extension-setup")){Text("Run command")}
    }
}

internal fun JSONObject.props()=optJSONObject("props")?:JSONObject()
internal fun JSONObject.children()=optJSONArray("children").objects()
internal fun JSONObject.handle(name:String)=props().optJSONObject(name)?.optString("\$fn")
internal fun JSONObject.nodes(type:String):List<JSONObject> = (if(optString("type")==type)listOf(this)else emptyList()) + children().flatMap{it.nodes(type)}
internal fun JSONObject.actions():List<JSONObject> = props().optJSONObject("actions")?.nodes("Action").orEmpty()
internal fun activeRoot(tree:JSONObject?):JSONObject? {
    if(tree==null)return null
    val screens=tree.nodes("__screen");val active=screens.lastOrNull{it.props().optBoolean("active")}
    val start=active ?: tree
    fun find(node:JSONObject):JSONObject? { if(node.optString("type") in listOf("List","Grid","Detail","Form"))return node;return node.children().firstNotNullOfOrNull{find(it)} }
    return find(start)
}

@Composable
private fun CommandRunner(store:PaletteStore,extension:InstalledExtension,command:ExtensionCommand,
                          runtime:ExtensionRuntime,gridImages:ExtensionGridImages,gridStates:MutableMap<String,ExtensionGridState>,
                          listStates:MutableMap<String,ExtensionListState>,detailStates:MutableMap<String,ScrollState>,close:()->Unit){
    if(command.mode !in listOf("view","no-view")){
        ExtensionWindow(command.title,extension.listing.title,close){Text("This command needs a desktop menu bar. It can be installed, but cannot run on Android.",color=muted,fontSize=15.sp,modifier=Modifier.padding(24.dp))}
        return
    }
    val catalog=store.extensions
    val context=LocalContext.current
    val root=activeRoot(catalog.runtimeTree)
    val failed=catalog.runtimeError!=null
    val listState = listStates.getOrPut(root?.optString("id").orEmpty()) { ExtensionListState() }
    var listSearch by listState::search
    val query = listSearch.text
    var selected by listState::selected
    var menu by remember{mutableStateOf(false)}
    val rows=(if(root?.optString("type")=="Grid") emptyList() else root?.nodes("List.Item").orEmpty()).filter{
        root?.props()?.optBoolean("filtering",true)==false||query.isBlank()||("${it.props().optString("title")} ${it.props().optString("subtitle")} ${it.props().optJSONArray("keywords")}").contains(query,true)
    }
    val item=rows.getOrNull(selected.coerceIn(0,(rows.size-1).coerceAtLeast(0)))
    val actions=item?.actions().orEmpty().ifEmpty{root?.actions().orEmpty()}
    fun liveRows(): List<JSONObject> {
        val liveRoot = activeRoot(catalog.runtimeTree) ?: return emptyList()
        if (liveRoot.optString("type") != "List" || liveRoot.optString("id") != root?.optString("id")) return emptyList()
        val liveQuery = listState.search.text
        return liveRoot.nodes("List.Item").filter {
            !liveRoot.props().optBoolean("filtering", true) || liveQuery.isBlank() ||
                ("${it.props().optString("title")} ${it.props().optString("subtitle")} ${it.props().optJSONArray("keywords")}").contains(liveQuery, true)
        }
    }
    fun liveActions(): List<JSONObject> {
        val liveRoot = activeRoot(catalog.runtimeTree) ?: return emptyList()
        if (liveRoot.optString("id") != root?.optString("id") || liveRoot.optString("type") != root?.optString("type")) return emptyList()
        if (liveRoot.optString("type") != "List") return liveRoot.actions()
        val liveRows = liveRows()
        return liveRows.getOrNull(listState.selected.coerceIn(0, liveRows.lastIndex.coerceAtLeast(0)))?.actions().orEmpty()
            .ifEmpty { liveRoot.actions() }
    }
    fun notifyCurrentSelection() {
        val currentRoot = activeRoot(catalog.runtimeTree) ?: return
        if (catalog.runtimeError != null || currentRoot.optString("type") != "List" || currentRoot.optString("id") != root?.optString("id")) return
        val callback = currentRoot.handle("onSelectionChange") ?: return
        val currentRows = liveRows()
        val id = currentRows.getOrNull(listState.selected.coerceIn(0, currentRows.lastIndex.coerceAtLeast(0)))?.props()?.opt("id") as? String
        listState.selectionDelivery.deliver(currentRoot.optString("id"), id) {
            runtime.dispatch(callback, JSONArray().put(id ?: JSONObject.NULL))
        }
    }
    fun action(a: JSONObject?) {
        if (catalog.runtimeError == null && a != null) {
            listState.search = listState.search.copy(composition = null)
            // Queue selection before activation, including back-to-back keys
            // before Compose's selection effect can run. JS refs see this first.
            notifyCurrentSelection()
            runtime.dispatch(a.handle("onAction"))
        }
        menu = false
    }
    fun activateCurrent() {
        if (root?.optString("type") != "List" || listState.search.composition == null) action(liveActions().firstOrNull())
    }
    LaunchedEffect(root?.optString("id"), item?.props()?.optString("id"), root?.handle("onSelectionChange"), failed) {
        if (!failed) notifyCurrentSelection()
    }
    ExtensionWindow(command.title,extension.listing.title,close,Modifier.onPreviewKeyEvent{
        if(catalog.runtimeError!=null || root?.optString("type")=="Grid" || it.type!=KeyEventType.KeyDown)false else {
        val eventRows=if(it.key in listOf(Key.DirectionDown,Key.DirectionUp)) liveRows() else emptyList()
        when{
            it.key==Key.K&&it.isCtrlPressed->{menu=true;true}
            it.key==Key.DirectionDown&&eventRows.isNotEmpty()->{selected=(selected+1).coerceAtMost(eventRows.lastIndex);notifyCurrentSelection();true}
            it.key==Key.DirectionUp&&eventRows.isNotEmpty()->{selected=(selected-1).coerceAtLeast(0);notifyCurrentSelection();true}
            it.key in listOf(Key.Enter, Key.NumPadEnter)&&root?.optString("type")!="Form"->{activateCurrent();true}
            else->false
        }
        }
    },compact=true,onEscape={ if(catalog.runtimeError!=null)close() else if(catalog.navigationDepth>1)runtime.back() else close() },headerless=!failed && root?.optString("type") in listOf("Grid","List"),headerActions={
        if(failed)TextButton(onClick=close,modifier=Modifier.testTag("extension-error-close")){Text("Close",fontSize=11.sp)}
        else {
            if(catalog.navigationDepth>1)TextButton(onClick=runtime::back){Text("Back",fontSize=11.sp)}
            TextButton(onClick={store.extensionSetupFromRun=true;store.dialog="extension-setup"}){Text("Preferences",fontSize=11.sp)}
        }
    }){
        if(catalog.runtimeError!=null){
            Column(Modifier.weight(1f).padding(24.dp).verticalScroll(rememberScrollState())){Text("This command stopped",fontSize=17.sp,color=ink);Text(catalog.runtimeError!!,color=muted,fontSize=12.sp,lineHeight=19.sp,modifier=Modifier.padding(top=15.dp).testTag("extension-error"))}
        }else if(root==null){
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                if(!catalog.runtimeReady)CircularProgressIndicator(Modifier.size(30.dp))else Glyph("extension",ink,36)
                Text(catalog.runtimeStatus,fontSize=16.sp,color=ink,modifier=Modifier.padding(top=20.dp).testTag("extension-status"))
                if(catalog.runtimeReady)Text("Escape to return to the palette",color=muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))
            }
        }else when(root.optString("type")){
            "Grid" -> Box(Modifier.weight(1f).fillMaxWidth()) {
                val state = gridStates.getOrPut(root.optString("id")) { ExtensionGridState() }
                NativeExtensionGrid(state, { activeRoot(catalog.runtimeTree) ?: root }, gridImages, extension.listing.title, catalog.runtimeStatus, context.getSharedPreferences("extension-${extension.listing.id}", android.content.Context.MODE_PRIVATE), command.name, runtime::dispatch, back={ if(catalog.navigationDepth>1)runtime.back() else close() }, configure={store.extensionSetupFromRun=true;store.dialog="extension-setup"})
            }
            "List"->{
                ExtensionSearchHeader(listSearch,{next->
                    if(next.text != query){selected=0;runtime.dispatch(root.handle("onSearchTextChange"),JSONArray().put(next.text))}
                    listSearch=next
                },root.props().optString("searchBarPlaceholder","Search extension…"),"extension-search",{if(catalog.navigationDepth>1)runtime.back() else close()}) {
                    IconButton(onClick={store.extensionSetupFromRun=true;store.dialog="extension-setup"},modifier=Modifier.size(48.dp).semantics{contentDescription="Extension preferences"}){Glyph("settings",muted,16)}
                }
                if(root.props().optBoolean("isLoading"))LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=5.dp))
                LazyColumn(state=listState.scroll,modifier=Modifier.weight(1f).fillMaxWidth().testTag("extension-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    items(rows,key={it.optString("id")}){row->val i=rows.indexOf(row);val rowSelected=i==selected;Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if(rowSelected)ink.copy(alpha=.12f)else card).semantics{this.selected=rowSelected}.clickable{selected=i;notifyCurrentSelection()}.padding(14.dp).testTag("extension-row-$i")){
                        Text(row.props().optString("title"),fontSize=14.sp,lineHeight=21.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        val subtitle=row.props().optString("subtitle");if(subtitle.isNotBlank())Text(subtitle,color=muted,fontSize=11.sp,lineHeight=17.sp)
                        row.props().optJSONArray("accessories").objects().forEach{Text(it.optString("text"),color=muted,fontSize=10.sp)}
                    }}
                    if(rows.isEmpty())item{Text(if(root.props().optBoolean("isLoading"))"Loading…" else "No results",color=muted,modifier=Modifier.padding(20.dp))}
                    item?.props()?.optJSONObject("detail")?.let{detail->item{Text(detail.props().optString("markdown"),color=muted,fontSize=12.sp)}}
                }
            }
            "Detail"->SelectionContainerCompat(root.props().optString("markdown"),Modifier.weight(1f).fillMaxWidth().padding(20.dp),detailStates.getOrPut(root.optString("id")){ScrollState(0)})
            "Form"->Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){root.children().forEach{FormNode(it,runtime)}}
            else->Text("Unsupported extension view",modifier=Modifier.weight(1f).padding(20.dp))
        }
        if (!failed && root?.optString("type") != "Grid") {
        HorizontalDivider(color=line)
        dev.tinycast.android.PaletteFooter(metadata={ modifier ->
            Column(modifier) {
                Text(extension.listing.title,color=muted,fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(catalog.runtimeStatus,color=muted,fontSize=11.sp,lineHeight=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
        },showActions=actions.isNotEmpty()) {
            if(actions.isNotEmpty()){
                Box {
                    dev.tinycast.android.PaletteActionCapsule(actions.first().props().optString("title","Run action"),{action(liveActions().firstOrNull())},{menu=true},
                        "extension-primary","extension-actions",compact=!dev.tinycast.android.LocalPaletteWide.current)
                    PaletteChoiceMenu(menu,{menu=false},actions.mapIndexed{i,a->PaletteChoice("extension-action-${a.optString("id",i.toString())}",a.props().optString("title","Action"),tag="extension-action-$i",shortcut=if(i==0)listOf("↵")else emptyList(),icon="command"){action(a)}},title=item?.props()?.optString("title") ?: command.title,searchable=true)

                }
            }
        }
        }
    }
}

@Composable private fun SelectionContainerCompat(text:String,modifier:Modifier,scroll:ScrollState){androidx.compose.foundation.text.selection.SelectionContainer(modifier.verticalScroll(scroll)){Text(text,fontSize=14.sp,lineHeight=23.sp)}}

@Composable
private fun FormNode(node:JSONObject,runtime:ExtensionRuntime){
    val p=node.props();val title=p.optString("title",p.optString("label"));val type=node.optString("type")
    when(type){
        "Form.TextField","Form.PasswordField","Form.TextArea"->{var text by remember(node.optString("id"),p.optString("value")){mutableStateOf(p.optString("value"))};OutlinedTextField(text,{text=it;runtime.dispatch(node.handle("onTinycastChange"),JSONArray().put(it))},label={Text(title)},placeholder={Text(p.optString("placeholder"))},singleLine=type!="Form.TextArea",visualTransformation=if(type=="Form.PasswordField")PasswordVisualTransformation()else VisualTransformation.None,modifier=Modifier.fillMaxWidth().paletteFocusBookmark().testTag("extension-field-${p.optString("id")}"))}
        "Form.Checkbox"->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.optBoolean("value"),{runtime.dispatch(node.handle("onTinycastChange"),JSONArray().put(it))});Text(p.optString("label",title),fontSize=13.sp)}
        "Form.Dropdown"->{var expanded by remember{mutableStateOf(false)};Text(title,fontSize=12.sp,color=muted);Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.paletteFocusBookmark()){Text(p.optString("value").ifBlank{"Choose…"})};PaletteChoiceMenu(expanded,{expanded=false},node.nodes("Form.Dropdown.Item").mapIndexed{index,option->val value=option.props().optString("value");PaletteChoice("form-option-${node.optString("id")}-${index}-$value",option.props().optString("title"),checked=p.optString("value")==value){runtime.dispatch(node.handle("onTinycastChange"),JSONArray().put(value));expanded=false}},title=title)}}
        "Form.Description"->Text(p.optString("text"),color=muted,fontSize=13.sp)
        "Form.Separator"->HorizontalDivider(color=line)
        else->if(node.children().isNotEmpty())node.children().forEach{FormNode(it,runtime)}else Text("$type is not supported yet",color=muted,fontSize=12.sp)
    }
}
