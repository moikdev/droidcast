@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.tinycast.android

import android.content.ComponentName
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

private val categories = listOf("All", "Pinned", "Apps", "Notes", "Quicklinks", "Clips", "Files", "Extensions")

@Composable
fun TinycastApp(store: PaletteStore, globalEnabled: Boolean, run: (Command) -> Unit, copy: (String) -> Unit,
                enableGlobal: () -> Unit, dismiss: () -> Unit, shortcut: (String, KeyChord) -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = PaletteTheme.ink, onPrimary = PaletteTheme.background,
        surface = PaletteTheme.surface, background = PaletteTheme.background, onSurface = PaletteTheme.ink,
        onBackground = PaletteTheme.ink, outline = PaletteTheme.border, secondary = PaletteTheme.secondary,
        surfaceContainer = PaletteTheme.surface)) {
      CompositionLocalProvider(LocalContentColor provides PaletteTheme.ink) {
        // These survive a route being replaced, including caret and scroll position.
        val launcherSearch = remember { mutableStateOf(TextFieldValue(store.query, TextRange(store.query.length))) }
        val launcherList = rememberLazyListState()
        fun closeRoute() {
            if (store.recordingTarget != null) { store.cancelShortcutRecording(); return }
            if (store.dialog?.startsWith("backup") == true) {
                if (store.backupBusy) return
                store.discardImport()
            }
            if (store.dialog == "extension-run" || store.dialog == "extension-setup") store.extensions.close()
            store.dialog = null; store.actionCommand = null; store.focusEpoch++
        }
        if (store.dialog != null && store.dialog !in setOf("emoji", "actions")) {
            val selected = store.actionCommand ?: store.results().getOrNull(store.selected)
            PaletteDialogs(store, selected, globalEnabled, enableGlobal, run, copy, ::closeRoute)
            return@CompositionLocalProvider
        }
        if (store.dialog == "emoji") {
            EmojiPicker(store.emoji, { store.dialog = null; store.focusEpoch++ }, { copy(it); store.message = null })
            return@CompositionLocalProvider
        }
        val focus = remember { FocusRequester() }
        var searchValue by launcherSearch
        // Programmatic mode changes place typing after the prefix; user edits retain their selection/composition.
        val displayedSearch = if (searchValue.text == store.query) searchValue else TextFieldValue(store.query, TextRange(store.query.length))
        SideEffect { if (searchValue != displayedSearch) searchValue = displayedSearch }
        val keyboard = LocalSoftwareKeyboardController.current
        val view = LocalView.current
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        var searchFocused by remember { mutableStateOf(false) }
        var keyboardRequested by remember { mutableStateOf(false) }
        val list = launcherList
        val results by remember(store) { derivedStateOf { store.results() } }
        val resultSelected = results.getOrNull(store.selected.coerceIn(0, (results.size - 1).coerceAtLeast(0)))
        val selected = if (store.dialog in setOf("actions", "alias", "delete")) store.actionCommand ?: resultSelected else resultSelected
        val snack = remember { SnackbarHostState() }
        val shortcutEngine = remember { ShortcutEngine() }
        var menu by remember { mutableStateOf(false) }
        fun close() = closeRoute()
        fun escape() {
            when { store.dialog != null -> close(); menu -> menu = false; store.query.isNotEmpty() -> store.search(""); store.category != "All" -> store.filter("All"); else -> dismiss() }
        }
        val handleKey: (KeyEvent) -> Boolean = { event ->
            val configured = if (event.action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) shortcutEngine.handle(event.asShortcutInput(), store.shortcutConfiguration.byChord, store.dialog != null || store.backupBusy || ShortcutRuntime.recordingOwner != null) else ShortcutEngine.Result(false)
            if (configured.target != null && !ShortcutRuntime.passesSettingsNavigation(event)) shortcut(configured.target, configured.chord!!)
            val ctrl = (event.isCtrlPressed xor event.isMetaPressed) && !event.isShiftPressed && !event.isAltPressed
            val preferencesKey = event.keyCode == KeyEvent.KEYCODE_COMMA && (event.isCtrlPressed xor event.isMetaPressed) && !event.isAltPressed && store.dialog == null
            if (event.action == KeyEvent.ACTION_DOWN && preferencesKey) {
                val target = selected?.takeIf { ShortcutRules.targetAllowed(it.id) }
                run(if (event.isShiftPressed && target != null) Command("configure-command", "Configure command", "", payload = target.id) else Command("app-settings", "Settings", "")); true
            }
            else if (configured.consumed) true
            else if (event.action != KeyEvent.ACTION_DOWN) false
            else if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) { escape(); true }
            else if (store.dialog != null) false
            else when {
                ctrl && event.keyCode == KeyEvent.KEYCODE_L -> { keyboardRequested = true; focus.requestFocus(); keyboard?.show(); true }
                ctrl && event.keyCode == KeyEvent.KEYCODE_K -> { if(selected != null) { store.actionCommand = selected; store.dialog = "actions" }; true }
                ctrl && event.keyCode == KeyEvent.KEYCODE_N -> { store.editing = null; store.dialog = "note"; true }
                ctrl && event.keyCode == KeyEvent.KEYCODE_D -> { selected?.takeUnless { it.id in listOf("result", "web") }?.let { store.togglePin(it.id) }; true }
                ctrl && event.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_8 -> { store.filter(categories[event.keyCode-KeyEvent.KEYCODE_1]); true }
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { store.selected=(store.selected+1).coerceAtMost((results.size-1).coerceAtLeast(0)); true }
                event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { store.selected=(store.selected-1).coerceAtLeast(0); true }
                event.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) -> { selected?.let(run); true }
                else -> false
            }
        }
        BackHandler(onBack = ::escape)
        // Reset before the next measure so stable item keys cannot preserve an old
        // anchor for a frame. Moving selection inside the viewport never scrolls.
        val scrollHistory = remember { PaletteScrollHistory() }
        val resultIds = remember(results) { results.map { it.id } }
        SideEffect {
            val index = store.selected.coerceIn(0, results.lastIndex.coerceAtLeast(0))
            if (store.selected != index) store.selected = index
            val layout = list.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
            val visible = item != null && item.offset >= layout.viewportStartOffset && item.offset + item.size <= layout.viewportEndOffset
            if (scrollHistory.update(store.query, store.category, resultIds, index, visible)) list.requestScrollToItem(index)
        }
        LaunchedEffect(list, scrollHistory) {
            snapshotFlow {
                val layout = list.layoutInfo
                val index = store.selected.coerceIn(0, (layout.totalItemsCount - 1).coerceAtLeast(0))
                val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                val visible = item != null && item.offset >= layout.viewportStartOffset && item.offset + item.size <= layout.viewportEndOffset
                Triple(layout.viewportEndOffset - layout.viewportStartOffset, index, visible)
            }.collect { (height, index, visible) ->
                if (scrollHistory.viewportMeasured(height, visible)) list.requestScrollToItem(index)
            }
        }
        LaunchedEffect(store.message) { store.message?.let { snack.showSnackbar(it, duration=SnackbarDuration.Short); if (store.message == it) store.message=null } }
        PaletteViewport(Modifier.onPreviewKeyEvent { handleKey(it.nativeKeyEvent) }) { wide, top, paletteHeight ->
            LaunchedEffect(store.focusEpoch) { focus.requestFocus() }
            val imeVisible = WindowInsets.isImeVisible
            LaunchedEffect(wide, imeVisible, keyboardRequested, windowFocused, searchFocused) {
                if (wide && !keyboardRequested && windowFocused && searchFocused) {
                    withFrameNanos { }
                    keyboard?.hide()
                    androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
            }
            Box(Modifier.fillMaxSize().clickable(indication=null, interactionSource=remember { MutableInteractionSource() }) { if(store.dialog==null) dismiss() }.testTag("palette-backdrop"))
            Column(Modifier.padding(top=top).padding(horizontal=12.dp).widthIn(max=PaletteTheme.width).fillMaxWidth().height(paletteHeight).align(Alignment.TopCenter)
                .shadow(24.dp,RoundedCornerShape(PaletteTheme.radius))
                .clip(RoundedCornerShape(PaletteTheme.radius))
                .background(Brush.linearGradient(listOf(Color(0xFF292929),Color(0xFF141414),Color(0xFF242424))))
                .border(1.dp,PaletteTheme.border,RoundedCornerShape(PaletteTheme.radius))
                .clickable(indication=null, interactionSource=remember { MutableInteractionSource() }) {}.testTag("palette")) {
                Row(Modifier.fillMaxWidth().height(PaletteTheme.header).padding(horizontal=16.dp).testTag("palette-header"),verticalAlignment=Alignment.CenterVertically) {
                    Glyph("search",PaletteTheme.secondary,22);Spacer(Modifier.width(12.dp))
                    BasicTextField(value=displayedSearch,onValueChange={ searchValue = it; if (it.text != store.query) store.search(it.text) },modifier=Modifier.weight(1f).height(32.dp).focusRequester(focus).onFocusChanged { searchFocused = it.isFocused }.testTag("search"),
                        textStyle=TextStyle(color=PaletteTheme.ink,fontSize=20.sp,lineHeight=26.sp),singleLine=true,cursorBrush=SolidColor(PaletteTheme.accent),
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search,showKeyboardOnFocus=!wide || keyboardRequested),keyboardActions=KeyboardActions(onSearch={selected?.let(run)}),
                        decorationBox={inner -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.CenterStart){if(store.query.isEmpty())Text("Search for apps and commands…",color=PaletteTheme.secondary,fontSize=20.sp,lineHeight=26.sp,maxLines=1,overflow=TextOverflow.Ellipsis);inner()}})
                    if(wide) IconButton(onClick={keyboardRequested=true;focus.requestFocus();keyboard?.show()},modifier=Modifier.size(36.dp).semantics{contentDescription="Show on-screen keyboard"}){Glyph("keyboard",PaletteTheme.tertiary,16)}
                }
                if (store.category == "Files") FileSearchToolbar(store, run)
                LaunchedEffect(store.folderSearch.includeInLauncher) { store.syncFileSearch() }
                if(results.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
                    Glyph("search",PaletteTheme.tertiary,28);Spacer(Modifier.height(12.dp))
                    Text("No matching commands",fontSize=14.sp);Text("Try another name or category.",fontSize=12.sp,color=PaletteTheme.secondary)
                    if(store.category=="Files") TextButton(onClick={run(Command("search-folders","Search folders",""))}){Text("Choose search folders")}
                    if(store.category=="Extensions") TextButton(onClick={store.dialog="extensions"}){Text("Browse Raycast Store")}
                } else LazyColumn(state=list,modifier=Modifier.fillMaxWidth().weight(1f).testTag("results"),contentPadding=PaddingValues(horizontal=8.dp,vertical=4.dp)) {
                    itemsIndexed(results,key={_,c->c.id}){i,c->
                        val section=sectionTitle(c,store)
                        val previous=results.getOrNull(i-1)?.let { sectionTitle(it,store) }
                        if(i==0 || section!=previous) Text(section,color=PaletteTheme.secondary,fontSize=12.sp,fontWeight=FontWeight.Medium,
                            modifier=Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top=if(i==0)4.dp else 12.dp,bottom=4.dp))
                        if(c.kind==Kind.CALC) CalculatorCard(c,i==store.selected,{store.selected=i;run(c)},{store.selected=i;store.actionCommand=c;store.dialog="actions"})
                        else CommandRow(c,i==store.selected,store.shortcutConfiguration.bindings[c.id]?.label(),{store.selected=i;run(c)},{store.selected=i;store.actionCommand=c;store.dialog="actions"})
                    }
                }
                Row(Modifier.fillMaxWidth().height(PaletteTheme.footer).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                    Box {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(PaletteTheme.control).border(1.dp,PaletteTheme.border,CircleShape)
                            .clickable{menu=!menu}.semantics{contentDescription="Droidcast menu"}.testTag("palette-menu"),contentAlignment=Alignment.Center){Glyph("menu",PaletteTheme.secondary,18)}
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false},containerColor=PaletteTheme.surface,shape=RoundedCornerShape(16.dp)) {
                            categories.forEach { c -> DropdownMenuItem(text={Text(if(c=="Pinned")"Favorites" else c,fontSize=13.sp)},
                                trailingIcon={if(store.category==c)Text("✓",color=PaletteTheme.secondary)},onClick={store.filter(c);menu=false},modifier=Modifier.testTag("filter-$c")) }
                            HorizontalDivider(color=PaletteTheme.separator)
                            DropdownMenuItem(text={Text("Preferences…",fontSize=13.sp)},onClick={menu=false;run(Command("app-settings","Settings",""))})
                            DropdownMenuItem(text={Text("Keyboard Shortcuts",fontSize=13.sp)},onClick={menu=false;run(Command("shortcuts","Shortcuts",""))})
                            DropdownMenuItem(text={Text("Dismiss Droidcast",fontSize=13.sp)},onClick=dismiss)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if(selected!=null) Box {
                        Row(Modifier.clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFF3E3E3E),Color(0xFF2C2C2C)))).border(1.dp,PaletteTheme.border,CircleShape).padding(4.dp),verticalAlignment=Alignment.CenterVertically){
                            Row(Modifier.clip(CircleShape).clickable{run(selected)}.height(30.dp).padding(horizontal=10.dp).testTag("primary-action"),verticalAlignment=Alignment.CenterVertically){
                                Text(if(wide)primaryActionTitle(selected)else primaryActionTitle(selected).substringBefore(' '),fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=1);Spacer(Modifier.width(6.dp));KeyCap("↵")
                            }
                            Row(Modifier.clip(CircleShape).clickable{store.actionCommand=selected;store.dialog="actions"}.height(30.dp).padding(horizontal=10.dp).testTag("actions-button"),verticalAlignment=Alignment.CenterVertically){
                                Text("Actions",fontSize=12.sp,color=PaletteTheme.secondary);Spacer(Modifier.width(6.dp));KeyCap("Ctrl");Spacer(Modifier.width(2.dp));KeyCap("K")
                            }
                        }
                        if(store.dialog=="actions") ActionsMenu(store,store.actionCommand ?: selected,run,copy,::close)
                    }
                }
            }
            SnackbarHost(snack,modifier=Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.imeAnimationTarget).padding(20.dp))
        }
      }
    }
}

private fun sectionTitle(command:Command,store:PaletteStore):String = when {
    command.kind==Kind.CALC -> "Calculator"
    store.category=="Files" && command.kind==Kind.FILE -> if(command.id.startsWith("files:index:"))"Selected folders" else "Recent documents"
    store.query.isBlank() && command.id in store.pins -> "Favorites"
    store.query.isNotBlank() -> "Results"
    else -> command.kind.label
}

internal fun primaryActionTitle(command:Command):String = when(command.kind) {
    Kind.APP -> "Open Application"
    Kind.CALC -> "Copy Answer"
    Kind.CLIP -> "Copy to Clipboard"
    Kind.NOTE -> "Open Note"
    Kind.FILE -> if(command.mimeType == "vnd.android.document/directory")"Browse Folder" else "Open File"
    Kind.LINK -> "Open Link"
    Kind.EXTENSION -> "Run Command"
    Kind.COMMAND -> if(command.id=="web")"Search Web" else "Open Command"
}

@Composable
private fun CommandRow(command:Command,active:Boolean,shortcut:String?,run:()->Unit,actions:()->Unit){
    val interaction=remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().height(PaletteTheme.row).clip(RoundedCornerShape(PaletteTheme.rowRadius))
        .background(if(active)PaletteTheme.selection else if(hovered)PaletteTheme.hover else Color.Transparent)
        .hoverable(interaction).combinedClickable(onClick=run,onLongClick=actions).padding(horizontal=8.dp)
        .semantics{selected=active;contentDescription="${command.title}. ${command.subtitle}" + shortcut?.let { ". Shortcut $it" }.orEmpty()}.testTag("result-${command.id}"),verticalAlignment=Alignment.CenterVertically){
        CommandIcon(command);Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){
            Text(command.title,color=PaletteTheme.ink,fontSize=14.sp,lineHeight=20.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false))
            if(command.alias.isNotBlank())Text(command.alias,color=PaletteTheme.secondary,fontSize=10.sp,
                modifier=Modifier.padding(start=8.dp).clip(RoundedCornerShape(6.dp)).background(PaletteTheme.control).padding(horizontal=5.dp,vertical=2.dp))
        }
        Spacer(Modifier.width(8.dp))
        if (shortcut != null) { Box(Modifier.widthIn(max=120.dp)) { KeyCap(shortcut) }; Spacer(Modifier.width(8.dp)) }
        Text(when(command.kind){Kind.APP->"Application";Kind.COMMAND->"Command";Kind.EXTENSION->command.subtitle;else->command.kind.label.removeSuffix("s")},
            color=PaletteTheme.secondary,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=130.dp))
    }
}

@Composable
private fun CalculatorCard(command:Command,active:Boolean,run:()->Unit,actions:()->Unit){
    val conversion=Regex("^(.+?)\\s+(?:to|in)\\s+([a-zA-Z°]+)$").matchEntire(command.subtitle)
    Row(Modifier.fillMaxWidth().heightIn(min=118.dp).clip(RoundedCornerShape(PaletteTheme.rowRadius))
        .background(if(active)PaletteTheme.selection else PaletteTheme.hover).border(1.dp,PaletteTheme.separator,RoundedCornerShape(PaletteTheme.rowRadius))
        .combinedClickable(onClick=run,onLongClick=actions).padding(horizontal=12.dp,vertical=28.dp)
        .semantics{selected=active}.testTag("result-${command.id}"),verticalAlignment=Alignment.CenterVertically){
        CalculatorColumn(conversion?.groupValues?.get(1) ?: command.subtitle,conversion?.groupValues?.get(1)?.substringAfterLast(' ')?.let(::unitBadge) ?: "Expression",false,Modifier.weight(1f))
        Glyph("arrow",PaletteTheme.tertiary,20)
        CalculatorColumn(command.title,conversion?.groupValues?.get(2)?.let(::unitBadge) ?: "Result",true,Modifier.weight(1f))
    }
}

private fun unitBadge(unit:String):String = when(unit.lowercase()) {
    "mm"->"Millimeters";"cm"->"Centimeters";"m"->"Meters";"km"->"Kilometers";"in"->"Inches";"ft"->"Feet";"mi"->"Miles"
    "g"->"Grams";"kg"->"Kilograms";"lb"->"Pounds";"oz"->"Ounces";"ms"->"Milliseconds";"s"->"Seconds";"min"->"Minutes";"h"->"Hours";"day"->"Days"
    "c","°c"->"Celsius";"f","°f"->"Fahrenheit";"k"->"Kelvin";else->unit.uppercase()
}

@Composable
private fun CalculatorColumn(value:String,badge:String,strong:Boolean,modifier:Modifier){
    BoxWithConstraints(modifier){
      val valueSize=if(maxWidth<220.dp)18.sp else 24.sp
      Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
        Text(value,fontSize=valueSize,fontWeight=if(strong)FontWeight.SemiBold else FontWeight.Normal,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=30.sp)
        Text(badge,fontSize=10.sp,color=PaletteTheme.secondary,modifier=Modifier.padding(top=8.dp).clip(RoundedCornerShape(6.dp)).background(PaletteTheme.control).padding(horizontal=6.dp,vertical=2.dp))
      }
    }
}

@Composable
private fun CommandIcon(command:Command){
    val context=LocalContext.current
    val appIcon=remember(command.id){if(command.kind==Kind.APP) runCatching {
        val icon=context.packageManager.getActivityIcon(ComponentName.unflattenFromString(command.payload)!!)
        val bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        icon.setBounds(0,0,80,80);icon.draw(android.graphics.Canvas(bitmap));bitmap.asImageBitmap()
    }.getOrNull() else null}
    Box(Modifier.size(PaletteTheme.icon).clip(RoundedCornerShape(6.dp)).background(if(appIcon==null)PaletteTheme.control else Color.Transparent),contentAlignment=Alignment.Center){
        if(appIcon!=null)Image(appIcon,contentDescription=null,modifier=Modifier.fillMaxSize()) else Glyph(command.icon,PaletteTheme.secondary,17)
    }
}

@Composable
internal fun KeyCap(text:String){Text(text,color=PaletteTheme.secondary,fontSize=10.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
    modifier=Modifier.heightIn(min=18.dp).clip(RoundedCornerShape(6.dp)).border(1.dp,PaletteTheme.border,RoundedCornerShape(6.dp)).padding(horizontal=4.dp,vertical=1.dp))}

@Composable
fun Glyph(name: String, tint: Color = PaletteTheme.secondary, size: Int = 20) {
    Canvas(Modifier.size(size.dp)) {
        val scale = this.size.width / 24f
        fun line(x: Float, y: Float, x2: Float, y2: Float) { drawLine(tint, androidx.compose.ui.geometry.Offset(x * scale, y * scale), androidx.compose.ui.geometry.Offset(x2 * scale, y2 * scale), 1.65f * scale, StrokeCap.Round) }
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) { drawRoundRect(tint, androidx.compose.ui.geometry.Offset(x * scale, y * scale), androidx.compose.ui.geometry.Size(w * scale, h * scale), androidx.compose.ui.geometry.CornerRadius(r * scale), style = Stroke(1.6f * scale)) }
        when (name) {
            "menu" -> { line(5f,9f,19f,9f); line(5f,15f,14f,15f) }
            "arrow" -> { line(4f,12f,20f,12f); line(14f,6f,20f,12f); line(14f,18f,20f,12f) }
            "search" -> { drawCircle(tint, 6.5f * scale, androidx.compose.ui.geometry.Offset(10.5f * scale, 10.5f * scale), style = Stroke(1.7f * scale)); line(15.5f, 15.5f, 21f, 21f) }
            "terminal", "command" -> { line(5f, 5f, 12f, 11f); line(12f, 11f, 5f, 17f); line(13f, 19f, 21f, 19f) }
            "note" -> { rect(5f, 3f, 14f, 18f); line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f) }
            "calculator" -> { rect(5f, 2f, 14f, 20f); rect(8f, 5f, 8f, 4f, .5f); listOf(12f, 16f, 19f).forEach { y -> line(8f, y, 10f, y); line(14f, y, 16f, y) } }
            "folder" -> { val p = Path().apply { moveTo(3f*scale,7f*scale); lineTo(3f*scale,5f*scale); lineTo(10f*scale,5f*scale); lineTo(12f*scale,8f*scale); lineTo(21f*scale,8f*scale); lineTo(21f*scale,20f*scale); lineTo(3f*scale,20f*scale); close() }; drawPath(p,tint,style=Stroke(1.6f*scale)) }
            "clipboard" -> { rect(5f, 5f, 14f, 17f); rect(9f, 2f, 6f, 5f, 1f); line(9f,12f,15f,12f); line(9f,16f,14f,16f) }
            "keyboard" -> { rect(2f, 5f, 20f, 14f); for (y in listOf(9f,12f)) for (x in listOf(6f,10f,14f,18f)) line(x,y,x+.4f,y); line(7f,16f,17f,16f) }
            "app" -> { for (y in listOf(3f,14f)) for (x in listOf(3f,14f)) rect(x,y,7f,7f,1f) }
            "pin" -> { line(8f,3f,16f,3f); line(9f,3f,9f,10f); line(15f,3f,15f,10f); line(9f,10f,6f,14f); line(15f,10f,18f,14f); line(6f,14f,18f,14f); line(12f,14f,12f,22f) }
            "link" -> { line(8f,16f,16f,8f); val p=Path().apply { moveTo(7f*scale,12f*scale); cubicTo(-1f*scale,16f*scale,8f*scale,25f*scale,13f*scale,17f*scale); moveTo(11f*scale,7f*scale); cubicTo(16f*scale,-1f*scale,25f*scale,8f*scale,17f*scale,12f*scale) }; drawPath(p,tint,style=Stroke(1.7f*scale,cap=StrokeCap.Round)) }
            "settings" -> { drawCircle(tint,7.5f*scale,style=Stroke(1.6f*scale)); drawCircle(tint,2.5f*scale,style=Stroke(1.6f*scale)); for(i in 0..7) { val a=i*Math.PI/4; line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat()) } }
            else -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
        }
    }
}
