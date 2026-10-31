package dev.tinycast.android.extensions

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteStore
import dev.tinycast.android.PaletteTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val ink = PaletteTheme.ink
private val muted = PaletteTheme.secondary
private val line = PaletteTheme.separator
private val card = PaletteTheme.control

@Composable
fun ExtensionDialogs(store: PaletteStore, close: () -> Unit) {
    val catalog = store.extensions
    when (store.dialog) {
        "extensions" -> StoreBrowser(store, close)
        "extension-setup" -> catalog.running?.let { (e, c) -> CommandSetup(store, e, c, close) }
        "extension-run" -> catalog.running?.let { (e, c) -> CommandRunner(store, e, c, close) }
    }
}

@Composable
private fun ExtensionWindow(title: String, subtitle: String, close: () -> Unit, modifier: Modifier = Modifier,
                            compact: Boolean = false, onEscape: () -> Unit = close,
                            headerActions: (@Composable RowScope.() -> Unit)? = null,
                            content: @Composable ColumnScope.() -> Unit) {
    val ownerView = LocalView.current
    val escapePresses = remember { mutableMapOf<Int, Long>() }
    val shellFocus = remember { FocusRequester() }
    var shellHasFocus by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onEscape, properties = DialogProperties(usePlatformDefaultWidth=false, dismissOnBackPress=true, decorFitsSystemWindows=false)) {
        val dialogWindow = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(dialogWindow) {
            // Compose 1.7's floating dialog otherwise reports a full-height
            // content view displaced below the status bar on API 36.
            dialogWindow.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            dialogWindow.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
            dialogWindow.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            onDispose { }
        }
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val reportedIme = WindowInsets.ime.getBottom(LocalDensity.current)
        // API 36's floating-dialog insets omit the navigation-bar portion of
        // the IME (504 px versus the owner's 552 px in the regression).
        val ownerIme = if (reportedIme > 0) androidx.core.view.ViewCompat.getRootWindowInsets(ownerView)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0 else 0
        val effectiveIme = maxOf(reportedIme, ownerIme)
        val imeVisible = effectiveIme > 0
        LaunchedEffect(shellHasFocus, windowFocused) {
            if (windowFocused && !shellHasFocus) {
                withFrameNanos { }
                if (!shellHasFocus) shellFocus.requestFocus()
            }
            if (!windowFocused) escapePresses.clear()
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets(bottom = effectiveIme)).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().focusProperties { canFocus = false }.clearAndSetSemantics { }.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onEscape))
        Surface(color=PaletteTheme.surface,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,line),
            modifier=Modifier.widthIn(max=if(compact)680.dp else 860.dp).fillMaxWidth(.96f).fillMaxHeight(if(imeVisible)1f else if(compact).72f else .88f).then(modifier).focusRequester(shellFocus).onFocusChanged { shellHasFocus = it.hasFocus }.focusable().onPreviewKeyEvent {
                val event = it.nativeKeyEvent
                if (event.keyCode != android.view.KeyEvent.KEYCODE_ESCAPE) false
                else if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) { escapePresses[event.deviceId] = event.downTime; onEscape() }
                    true
                } else if (escapePresses[event.deviceId] == event.downTime) { escapePresses.remove(event.deviceId); true } else false
            }) {
            Column {
                Row(Modifier.fillMaxWidth().padding(20.dp,12.dp),verticalAlignment=Alignment.CenterVertically){
                    Glyph("extension",ink,21);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){
                        Text(title,fontSize=18.sp,fontWeight=FontWeight.SemiBold,lineHeight=23.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(subtitle,fontSize=11.sp,color=muted,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                    headerActions?.invoke(this)
                    TextButton(onClick=close){Text("Close",color=muted)}
                }
                HorizontalDivider(color=line)
                content()
            }
        }
        }
    }
}

@Composable
private fun StoreBrowser(store: PaletteStore, close: () -> Unit) {
    val catalog=store.extensions
    var query by remember { mutableStateOf("") }
    var installedTab by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<StoreExtension>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<StoreExtension?>(null) }
    var removing by remember { mutableStateOf<InstalledExtension?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var installTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(catalog.installed,catalog.busy){if(installTarget!=null&&catalog.busy==null&&catalog.error==null&&catalog.installed.any{it.listing.id==installTarget}){installedTab=true;installTarget=null}}
    LaunchedEffect(query,page,retry,installedTab){
        if(!installedTab){loading=true;error=null
            try { delay(250); val next=catalog.search(query,page); found=if(page==1)next else (found+next).distinctBy{it.id} }
            catch(e:CancellationException){throw e}catch(e:Throwable){error=e.message ?: "Could not reach Raycast Store"}finally{loading=false}
        }
    }
    ExtensionWindow("Raycast Store","Install real Store bundles. Android compatibility varies by command.",close){
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={installedTab=false},modifier=Modifier.testTag("store-browse")){Text("Browse",color=if(!installedTab)ink else muted)}
            TextButton(onClick={installedTab=true},modifier=Modifier.testTag("store-installed")){Text("Installed (${catalog.installed.size})",color=if(installedTab)ink else muted)}
            Spacer(Modifier.weight(1f))
            if(loading) CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp)
        }
        if(!installedTab) OutlinedTextField(query,{query=it;page=1},placeholder={Text("Search Raycast extensions…")},singleLine=true,
            modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp).testTag("store-search"),shape=RoundedCornerShape(10.dp))
        val visibleError=error ?: catalog.error
        if(visibleError!=null) Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Text(visibleError,color=MaterialTheme.colorScheme.error,fontSize=12.sp,modifier=Modifier.weight(1f));TextButton(onClick={retry++}){Text("Retry")}}
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("store-results"),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(installedTab){
                if(catalog.installed.isEmpty())item{Text("Installed extensions will appear here. Browse the Store to add your first one.",color=muted,modifier=Modifier.padding(16.dp))}
                items(catalog.installed,key={it.listing.id}){ext->
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
    pending?.let { ext -> AlertDialog(onDismissRequest={pending=null},title={Text("Install ${ext.title}?")},text={Text("This installs third-party JavaScript from Raycast Store. When you run its commands it can use HTTPS, access the foreground clipboard and save its own data. Desktop-specific commands may not work on Android.")},
        confirmButton={TextButton(onClick={pending=null;installTarget=ext.id;store.installExtension(ext)},modifier=Modifier.testTag("confirm-install")){Text("Install extension")}},dismissButton={TextButton(onClick={pending=null}){Text("Cancel")}}) }
    removing?.let{ext->AlertDialog(onDismissRequest={removing=null},title={Text("Uninstall ${ext.listing.title}?")},text={Text("Its commands, preferences and local extension data will be removed.")},confirmButton={TextButton(onClick={removing=null;store.uninstallExtension(ext)},modifier=Modifier.testTag("confirm-uninstall")){Text("Uninstall")}},dismissButton={TextButton(onClick={removing=null}){Text("Cancel")}})}
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
            args.forEach{f->val name=f.getString("name");OutlinedTextField(arguments[name].orEmpty(),{arguments=arguments+(name to it)},label={Text(name)},placeholder={Text(f.optString("placeholder"))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("argument-$name"))}
            fields.forEach{f->val name=f.getString("name");val label=f.optString("title",name);val value=preferences.opt(name)
                fun change(v:Any){preferences=JSONObject(preferences.toString()).put(name,v)}
                when(f.optString("type")){
                    "checkbox"->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(value==true,{change(it)});Text(f.optString("label",label),fontSize=13.sp)}
                    "dropdown"->{var expanded by remember{mutableStateOf(false)};Column{Text(label,color=muted,fontSize=12.sp);Box{OutlinedButton(onClick={expanded=true}){Text(value?.toString().orEmpty().ifBlank{"Choose…"})};DropdownMenu(expanded,{expanded=false}){f.optJSONArray("data").objects().forEach{option->DropdownMenuItem(text={Text(option.optString("title"))},onClick={change(option.getString("value"));expanded=false})}}}}}
                    else->OutlinedTextField(if(value==null||value==JSONObject.NULL)"" else value.toString(),{change(it)},label={Text(label)},supportingText={Text(f.optString("description"),fontSize=10.sp)},singleLine=true,visualTransformation=if(f.optString("type")=="password")PasswordVisualTransformation()else VisualTransformation.None,modifier=Modifier.fillMaxWidth().testTag("preference-$name"))
                }
            }
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
        Button(onClick={
            val missingArg=args.firstOrNull{it.optBoolean("required")&&arguments[it.getString("name")].isNullOrBlank()}
            val missingPref=fields.firstOrNull{it.optBoolean("required")&&it.optString("type")!="checkbox"&&preferences.optString(it.getString("name")).isBlank()}
            if(missingArg!=null||missingPref!=null)error="Complete the required arguments and preferences."
            else{store.extensions.savePreferences(extension,preferences);store.extensions.launch(extension,command,arguments);store.dialog="extension-run"}
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
private fun CommandRunner(store:PaletteStore,extension:InstalledExtension,command:ExtensionCommand,close:()->Unit){
    if(command.mode !in listOf("view","no-view")){
        ExtensionWindow(command.title,extension.listing.title,close){Text("This command needs a desktop menu bar. It can be installed, but cannot run on Android.",color=muted,fontSize=15.sp,modifier=Modifier.padding(24.dp))}
        return
    }
    val catalog=store.extensions
    val context=LocalContext.current
    val runtime=remember(catalog.launchEpoch){ExtensionRuntime(context,catalog,extension,command)}
    DisposableEffect(runtime){runtime.start();onDispose{runtime.close()}}
    val gridImages = remember(runtime) { ExtensionGridImages(extension.directory) }
    DisposableEffect(gridImages) { onDispose { gridImages.close() } }
    val gridStates = remember(runtime) { mutableMapOf<String, ExtensionGridState>() }
    val root=activeRoot(catalog.runtimeTree)
    var query by remember{mutableStateOf("")}
    var selected by remember{mutableIntStateOf(0)}
    var menu by remember{mutableStateOf(false)}
    var ready by remember{mutableStateOf(false)}
    val focus=remember{FocusRequester()}
    val rows=(if(root?.optString("type")=="Grid") emptyList() else root?.nodes("List.Item").orEmpty()).filter{
        root?.props()?.optBoolean("filtering",true)==false||query.isBlank()||("${it.props().optString("title")} ${it.props().optString("subtitle")} ${it.props().optJSONArray("keywords")}").contains(query,true)
    }
    val item=rows.getOrNull(selected.coerceIn(0,(rows.size-1).coerceAtLeast(0)))
    val actions=item?.actions().orEmpty().ifEmpty{root?.actions().orEmpty()}
    fun action(a:JSONObject?){a?.let{runtime.dispatch(it.handle("onAction"))};menu=false}
    LaunchedEffect(ready){if(ready)focus.requestFocus()}
    LaunchedEffect(item?.props()?.optString("id")){if (root?.optString("type") != "Grid") root?.handle("onSelectionChange")?.let{runtime.dispatch(it,JSONArray().put(item?.props()?.optString("id")))}}
    ExtensionWindow(command.title,extension.listing.title,close,Modifier.onPreviewKeyEvent{
        if(root?.optString("type")=="Grid" || it.type!=KeyEventType.KeyDown)false else when{
            it.key==Key.K&&it.isCtrlPressed->{menu=true;true}
            it.key==Key.DirectionDown&&rows.isNotEmpty()->{selected=(selected+1).coerceAtMost(rows.lastIndex);true}
            it.key==Key.DirectionUp&&rows.isNotEmpty()->{selected=(selected-1).coerceAtLeast(0);true}
            it.key==Key.Enter&&root?.optString("type")!="Form"->{action(actions.firstOrNull());true}
            else->false
        }
    },compact=true,onEscape={ if(catalog.navigationDepth>1)runtime.back() else close() },headerActions={
        if(catalog.navigationDepth>1)TextButton(onClick=runtime::back){Text("Back",fontSize=11.sp)}
        TextButton(onClick={store.dialog="extension-setup"}){Text("Preferences",fontSize=11.sp)}
    }){
        AndroidView(factory={runtime.webView},modifier=Modifier.size(1.dp))
        if(catalog.runtimeError!=null){
            Column(Modifier.weight(1f).padding(24.dp).verticalScroll(rememberScrollState())){Text("This command needs an unsupported capability",fontSize=17.sp,color=ink);Text(catalog.runtimeError!!,color=muted,fontSize=12.sp,lineHeight=19.sp,modifier=Modifier.padding(top=15.dp).testTag("extension-error"))}
        }else if(root==null){
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                if(!catalog.runtimeReady)CircularProgressIndicator(Modifier.size(30.dp))else Glyph("extension",ink,36)
                Text(catalog.runtimeStatus,fontSize=16.sp,color=ink,modifier=Modifier.padding(top=20.dp).testTag("extension-status"))
                if(catalog.runtimeReady)Text("Escape to return to the palette",color=muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))
            }
        }else when(root.optString("type")){
            "Grid" -> Box(Modifier.weight(1f).fillMaxWidth()) {
                val state = gridStates.getOrPut(root.optString("id")) { ExtensionGridState() }
                NativeExtensionGrid(state, { activeRoot(catalog.runtimeTree) ?: root }, gridImages, catalog.runtimeStatus, context.getSharedPreferences("extension-${extension.listing.id}", android.content.Context.MODE_PRIVATE), command.name, runtime::dispatch)
            }
            "List"->{
                OutlinedTextField(query,{query=it;selected=0;runtime.dispatch(root.handle("onSearchTextChange"),JSONArray().put(it))},placeholder={Text(root.props().optString("searchBarPlaceholder","Search extension…"))},singleLine=true,modifier=Modifier.fillMaxWidth().padding(16.dp,0.dp).focusRequester(focus).onGloballyPositioned{ready=true}.testTag("extension-search"))
                if(root.props().optBoolean("isLoading"))LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=5.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("extension-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    items(rows,key={it.optString("id")}){row->val i=rows.indexOf(row);Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if(i==selected)ink.copy(alpha=.12f)else card).clickable{selected=i}.padding(14.dp).testTag("extension-row-$i")){
                        Text(row.props().optString("title"),fontSize=14.sp,lineHeight=21.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        val subtitle=row.props().optString("subtitle");if(subtitle.isNotBlank())Text(subtitle,color=muted,fontSize=11.sp,lineHeight=17.sp)
                        row.props().optJSONArray("accessories").objects().forEach{Text(it.optString("text"),color=muted,fontSize=10.sp)}
                    }}
                    if(rows.isEmpty())item{Text(if(root.props().optBoolean("isLoading"))"Loading…" else "No results",color=muted,modifier=Modifier.padding(20.dp))}
                    item?.props()?.optJSONObject("detail")?.let{detail->item{Text(detail.props().optString("markdown"),color=muted,fontSize=12.sp)}}
                }
            }
            "Detail"->SelectionContainerCompat(root.props().optString("markdown"),Modifier.weight(1f).fillMaxWidth().padding(20.dp))
            "Form"->Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){root.children().forEach{FormNode(it,runtime)}}
            else->Text("Unsupported extension view",modifier=Modifier.weight(1f).padding(20.dp))
        }
        if (root?.optString("type") != "Grid") {
        HorizontalDivider(color=line)
        Row(Modifier.fillMaxWidth().padding(14.dp,8.dp),verticalAlignment=Alignment.CenterVertically){
            if(root!=null)Text(catalog.runtimeStatus,color=ink,fontSize=11.sp,modifier=Modifier.weight(1f),maxLines=2)else Spacer(Modifier.weight(1f))
            if(actions.isNotEmpty()){
                Button(onClick={action(actions.first())},modifier=Modifier.testTag("extension-primary")){Text(actions.first().props().optString("title","Run action"),fontSize=12.sp)}
                Spacer(Modifier.width(10.dp));Box{
                    TextButton(onClick={menu=true},modifier=Modifier.testTag("extension-actions")){Text("Actions ⌃K")}
                    DropdownMenu(menu,{menu=false}){actions.forEachIndexed{i,a->DropdownMenuItem(text={Text(a.props().optString("title","Action"))},onClick={action(a)},modifier=Modifier.testTag("extension-action-$i"))}}
                }
            }
        }
        }
    }
}

@Composable private fun SelectionContainerCompat(text:String,modifier:Modifier){androidx.compose.foundation.text.selection.SelectionContainer(modifier.verticalScroll(rememberScrollState())){Text(text,fontSize=14.sp,lineHeight=23.sp)}}

@Composable
private fun FormNode(node:JSONObject,runtime:ExtensionRuntime){
    val p=node.props();val title=p.optString("title",p.optString("label"));val type=node.optString("type")
    when(type){
        "Form.TextField","Form.PasswordField","Form.TextArea"->{var text by remember(node.optString("id"),p.optString("value")){mutableStateOf(p.optString("value"))};OutlinedTextField(text,{text=it;runtime.dispatch(node.handle("onTinycastChange"),JSONArray().put(it))},label={Text(title)},placeholder={Text(p.optString("placeholder"))},singleLine=type!="Form.TextArea",visualTransformation=if(type=="Form.PasswordField")PasswordVisualTransformation()else VisualTransformation.None,modifier=Modifier.fillMaxWidth().testTag("extension-field-${p.optString("id")}"))}
        "Form.Checkbox"->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.optBoolean("value"),{runtime.dispatch(node.handle("onTinycastChange"),JSONArray().put(it))});Text(p.optString("label",title),fontSize=13.sp)}
        "Form.Dropdown"->{var expanded by remember{mutableStateOf(false)};Text(title,fontSize=12.sp,color=muted);Box{OutlinedButton(onClick={expanded=true}){Text(p.optString("value").ifBlank{"Choose…"})};DropdownMenu(expanded,{expanded=false}){node.nodes("Form.Dropdown.Item").forEach{option->DropdownMenuItem(text={Text(option.props().optString("title"))},onClick={runtime.dispatch(node.handle("onTinycastChange"),JSONArray().put(option.props().optString("value")));expanded=false})}}}}
        "Form.Description"->Text(p.optString("text"),color=muted,fontSize=13.sp)
        "Form.Separator"->HorizontalDivider(color=line)
        else->if(node.children().isNotEmpty())node.children().forEach{FormNode(it,runtime)}else Text("$type is not supported yet",color=muted,fontSize=12.sp)
    }
}
