@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.tinycast.android

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

internal data class PaletteChoice(val id: String, val label: String, val checked: Boolean = false,
                                  val enabled: Boolean = true, val tag: String = id,
                                  val shortcut: List<String> = emptyList(), val icon: String? = null,
                                  val separator: Boolean = false, val chord: KeyChord? = null, val activate: () -> Unit)

/** One menu owned by this activity's existing palette surface, never a new window. */
internal class PaletteMenus {
    class Request(val owner: Any, val choices: State<List<PaletteChoice>>, val anchor: State<Rect?>,
                  val title: State<String?>, val dismiss: State<() -> Unit>, val returnFocus: State<FocusRequester?>, val searchable: Boolean)
    var current by mutableStateOf<Request?>(null); private set
    var surface by mutableStateOf<Rect?>(null)
    var header by mutableStateOf<Rect?>(null); private set
    var footer by mutableStateOf<Rect?>(null); private set
    var headerHeight by mutableIntStateOf(0); private set
    var footerHeight by mutableIntStateOf(0); private set
    private var headerOwner: Any? = null
    private var footerOwner: Any? = null
    fun chrome(owner: Any, isHeader: Boolean, bounds: Rect?, height: Int) {
        if (isHeader) { headerOwner=owner;header=bounds;headerHeight=height }
        else { footerOwner=owner;footer=bounds;footerHeight=height }
    }
    fun forgetChrome(owner: Any, isHeader: Boolean) {
        if (isHeader && headerOwner === owner) { header=null;headerHeight=0;headerOwner=null }
        if (!isHeader && footerOwner === owner) { footer=null;footerHeight=0;footerOwner=null }
    }
    var selectedId by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(TextFieldValue("")); private set
    fun choices(): List<PaletteChoice> {
        val words=query.text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return current?.choices?.value.orEmpty().filter { choice -> words.all { choice.label.contains(it,true) } }
    }
    fun search(value: TextFieldValue) {
        val changed=value.text!=query.text; query=value
        if(changed) selectedId=choices().firstOrNull { it.enabled }?.id
    }
    val surfaceFocus = FocusRequester()
    var activeFocus: FocusRequester? = null
    var restoreTarget: FocusRequester? = null
    private var restoreOwner: Any? = null
    fun focusChanged(requester: FocusRequester, focused: Boolean) {
        if(focused) activeFocus = requester else if(activeFocus === requester) activeFocus = null
    }
    fun forgetFocus(requester: FocusRequester) {
        if(activeFocus === requester) activeFocus = null
        if(restoreTarget === requester) restoreTarget = null
    }
    fun forgetOwner(owner: Any) {
        remove(owner)
        if(restoreOwner === owner) restoreOwner = null
    }
    private val presses = mutableMapOf<Pair<Int, Int>, Long>()
    private var prefix = ""
    private var prefixTime = 0L
    fun open(request: Request) {
        restoreTarget = request.returnFocus.value ?: activeFocus
        restoreOwner = request.owner
        current = request; prefix = ""; query=TextFieldValue("")
        selectedId = request.choices.value.firstOrNull { it.enabled && it.checked }?.id
            ?: request.choices.value.firstOrNull { it.enabled }?.id
    }
    fun remove(owner: Any) { if (current?.owner === owner) current = null }
    fun dismiss() { val old = current; current = null; old?.dismiss?.value?.invoke() }
    fun choose(id: String) {
        val choice = current?.choices?.value?.firstOrNull { it.id == id && it.enabled } ?: return
        dismiss(); choice.activate()
    }
    fun activateSelected(request: Request) {
        if (current !== request || query.composition != null) return
        val enabled = choices().filter { it.enabled }
        (enabled.firstOrNull { it.id == selectedId } ?: enabled.firstOrNull())?.let { choose(it.id) }
    }
    fun normalizeSelection() {
        val choices = choices()
        if(choices.none { it.id == selectedId && it.enabled }) selectedId = choices.firstOrNull { it.enabled }?.id
    }
    fun clearPresses() = presses.clear()
    fun index(): Int = choices().indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    fun key(event: KeyEvent): Boolean {
        val id = event.deviceId to event.keyCode
        if (presses[id] == event.downTime) {
            if (event.action == KeyEvent.ACTION_UP) presses.remove(id)
            return true
        }
        val request = current ?: return false
        val shortcut=request.choices.value.firstOrNull { it.enabled && it.chord!=null && it.chord==event.asChord() }
        if(shortcut!=null && event.action==KeyEvent.ACTION_DOWN && query.composition==null) {
            presses[id]=event.downTime
            if(event.repeatCount==0) choose(shortcut.id)
            return true
        }
        if(request.searchable) {
            val navigation=event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_TAB,
                KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_BACK)
            val toggle=(event.isCtrlPressed xor event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed && event.keyCode==KeyEvent.KEYCODE_K
            if(!navigation && !toggle) return false
            if(query.composition!=null && event.keyCode !in setOf(KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_BACK)) return false
            if(toggle && event.action==KeyEvent.ACTION_DOWN) { presses[id]=event.downTime; if(event.repeatCount==0) dismiss(); return true }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return !request.searchable
        presses[id] = event.downTime
        if (event.repeatCount > 0) return true
        val enabled = choices().filter { it.enabled }
        val index = enabled.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> dismiss()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> activateSelected(request)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> if (enabled.isNotEmpty()) {
                val delta = if (event.keyCode == KeyEvent.KEYCODE_TAB && event.isShiftPressed) -1 else 1
                selectedId = enabled[Math.floorMod(index + delta, enabled.size)].id
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (enabled.isNotEmpty()) selectedId = enabled[Math.floorMod(index - 1, enabled.size)].id
            KeyEvent.KEYCODE_MOVE_HOME -> selectedId = enabled.firstOrNull()?.id
            KeyEvent.KEYCODE_MOVE_END -> selectedId = enabled.lastOrNull()?.id
            else -> if (!event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && event.unicodeChar > 31) {
                prefix = if (event.eventTime - prefixTime > 1000) "" else prefix
                prefix += event.unicodeChar.toChar(); prefixTime = event.eventTime
                enabled.firstOrNull { it.label.startsWith(prefix, ignoreCase = true) }?.let { selectedId = it.id }
            }
        }
        return true
    }
}

internal val LocalPaletteMenus = staticCompositionLocalOf<PaletteMenus> { error("Palette menu host is missing") }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PaletteMenuHost(store: PaletteStore, openChanged: (Boolean) -> Unit = {}, contextEpoch: Any? = null, feedback: Boolean = false, content: @Composable () -> Unit) {
    val menus = remember { PaletteMenus() }
    val snackbar = remember { SnackbarHostState() }
    val message = if(feedback) store.message else null
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it, duration=SnackbarDuration.Short)
            if(store.message == it) store.message = null
        }
    }
    val changed by rememberUpdatedState(openChanged)
    val ownerView = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var restore by remember { mutableStateOf(false) }
    DisposableEffect(store, menus) {
        store.attachPaletteMenuHost(menus)
        onDispose {
            if(store.paletteMenuChanged(menus, false, ownerView.hasWindowFocus())) changed(false)
            store.detachPaletteMenuHost(menus); menus.clearPresses()
        }
    }
    SideEffect {
        val open = menus.current != null
        if(store.paletteMenuChanged(menus, open, ownerView.hasWindowFocus())) changed(open)
    }
    LaunchedEffect(contextEpoch) { menus.dismiss() }
    LaunchedEffect(windowFocused) { if (!windowFocused) { menus.clearPresses(); menus.dismiss() } }
    LaunchedEffect(menus.current?.owner, windowFocused) {
        if (menus.current != null) restore = true
        else if (restore && windowFocused) {
            withFrameNanos { }
            if(menus.activeFocus == null) {
                val target = menus.restoreTarget
                if(target != null) runCatching { target.requestFocus() }
                if(menus.activeFocus == null) runCatching { menus.surfaceFocus.requestFocus() }
            }
            menus.restoreTarget = null
            restore = false
        }
    }
    CompositionLocalProvider(LocalPaletteMenus provides menus) {
        Box(Modifier.fillMaxSize().onPreInterceptKeyBeforeSoftKeyboard { menus.key(it.nativeKeyEvent) }
            .onPreviewKeyEvent { menus.key(it.nativeKeyEvent) }) {
            content()
            BackHandler(enabled = menus.current != null) { menus.dismiss() }
            if(feedback) menus.surface?.let { bounds -> PaletteFeedback(bounds, menus, snackbar) }
            menus.current?.let { request -> PaletteMenuOverlay(menus, request) }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.paletteMenuSurface(): Modifier {
    val menus = LocalPaletteMenus.current
    return onGloballyPositioned { menus.surface = it.boundsInRoot() }
        .focusRequester(menus.surfaceFocus).focusGroup()
}

@Composable
internal fun Modifier.paletteHeaderSurface(): Modifier = paletteChromeSurface(true)

@Composable
internal fun Modifier.paletteFooterSurface(): Modifier = paletteChromeSurface(false)

@Composable
private fun Modifier.paletteChromeSurface(isHeader: Boolean): Modifier {
    val menus=LocalPaletteMenus.current
    val owner=remember { Any() }
    DisposableEffect(menus,owner,isHeader) { onDispose { menus.forgetChrome(owner,isHeader) } }
    return onGloballyPositioned { menus.chrome(owner,isHeader,it.boundsInRoot(),it.size.height) }
}

@Composable
internal fun Modifier.paletteFocusBookmark(requester: FocusRequester = remember { FocusRequester() }): Modifier {
    val menus = LocalPaletteMenus.current
    DisposableEffect(requester) { onDispose { menus.forgetFocus(requester) } }
    return focusRequester(requester).onFocusChanged { menus.focusChanged(requester, it.isFocused) }
}

@Composable
internal fun PaletteChoiceMenu(expanded: Boolean, onDismissRequest: () -> Unit, choices: List<PaletteChoice>, title: String? = null, returnFocus: FocusRequester? = null, searchable: Boolean = false) {
    val menus = LocalPaletteMenus.current
    val owner = remember { Any() }
    val occurrences = mutableMapOf<String, Int>()
    val unique = choices.map { choice ->
        val number = occurrences.getOrDefault(choice.id, 0); occurrences[choice.id] = number + 1
        if(number == 0) choice else choice.copy(id = choice.id + "#duplicate-" + number)
    }
    val options = rememberUpdatedState(unique)
    val dismiss = rememberUpdatedState(onDismissRequest)
    val heading = rememberUpdatedState(title)
    val returnTarget = rememberUpdatedState(returnFocus)
    DisposableEffect(owner) { onDispose { menus.forgetOwner(owner) } }
    val anchor = remember { mutableStateOf<Rect?>(null) }
    // Measure the actual anchor Box while leaving its layout unchanged.
    Box(Modifier.size(0.dp).onGloballyPositioned { anchor.value = it.parentLayoutCoordinates?.boundsInRoot() ?: it.boundsInRoot() })
    DisposableEffect(expanded, menus, searchable) {
        if (expanded) menus.open(PaletteMenus.Request(owner, options, anchor, heading, dismiss, returnTarget, searchable))
        onDispose { menus.remove(owner) }
    }
}

@Composable
private fun PaletteMenuOverlay(menus: PaletteMenus, request: PaletteMenus.Request) {
    val surface = menus.surface ?: return
    val choices = menus.choices()
    SideEffect { menus.normalizeSelection() }
    val focus = remember(request.owner) { FocusRequester() }
    val scroll = rememberLazyListState()
    val wide=surface.width/LocalDensity.current.density >= 600
    val showHints=wide && LocalDensity.current.fontScale <= 1.3f
    val imeVisible=WindowInsets.isImeVisible
    val keyboard=LocalSoftwareKeyboardController.current
    val view=LocalView.current
    var keyboardRequested by remember(request.owner) { mutableStateOf(!wide || imeVisible) }
    LaunchedEffect(request.owner) { focus.requestFocus() }
    LaunchedEffect(request.owner,wide,keyboardRequested,imeVisible) {
        if(request.searchable && wide && !keyboardRequested) {
            withFrameNanos { }; keyboard?.hide()
            androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }
    LaunchedEffect(request.owner, menus.selectedId, choices.map { it.id }) { scroll.requestScrollToItem(menus.index()) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menus.dismiss() })
        Layout(modifier = Modifier.fillMaxSize(), content = {
            Surface(color = PaletteTheme.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, PaletteTheme.border),
                shadowElevation = 12.dp, modifier = (if(request.searchable) Modifier else Modifier.focusRequester(focus).focusable())
                    .pointerInput(Unit) { detectTapGestures {} }
                    .semantics { testTagsAsResourceId = true }.testTag("inline-menu")) {
                Column(Modifier.fillMaxWidth().padding(6.dp)) {
                    request.title.value?.let { Text(it, color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight=16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().heightIn(min=32.dp).padding(8.dp)) }
                    LazyColumn(state = scroll, modifier = Modifier.fillMaxWidth().weight(1f,fill=false).testTag("inline-menu-choices")) {
                        if(choices.isEmpty()) item { Text("No matching actions",color=PaletteTheme.secondary,fontSize=12.sp,modifier=Modifier.padding(10.dp).testTag("inline-menu-empty")) }
                        items(choices, key = { it.id }) { choice ->
                            if(choice.separator && menus.query.text.isBlank()) HorizontalDivider(color=PaletteTheme.separator,modifier=Modifier.padding(vertical=5.dp))
                            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(if (choice.id == menus.selectedId) PaletteTheme.selection else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                                .focusProperties { canFocus = false }.clickable(enabled = choice.enabled,role=Role.Button) { menus.choose(choice.id) }
                                .semantics { selected = choice.id == menus.selectedId; contentDescription=choice.label+
                                    if(choice.shortcut.isEmpty())"" else ". Shortcut ${choice.shortcut.joinToString(" ")}"; if (!choice.enabled) disabled() }
                                .testTag(choice.tag).padding(horizontal = 10.dp,vertical=8.dp), verticalAlignment = Alignment.CenterVertically) {
                                choice.icon?.let { Glyph(it,PaletteTheme.secondary,17); Spacer(Modifier.width(10.dp)) }
                                Text(choice.label, fontSize = 13.sp, lineHeight=18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = if (choice.enabled) PaletteTheme.ink else PaletteTheme.tertiary, modifier = Modifier.weight(1f))
                                if(showHints && choice.shortcut.isNotEmpty()) { Spacer(Modifier.width(8.dp)); choice.shortcut.forEach { KeyCap(it); Spacer(Modifier.width(2.dp)) } }
                                if (choice.checked) Text("✓", fontSize = 12.sp, color = PaletteTheme.secondary)
                            }
                        }
                    }
                    if(request.searchable) {
                        HorizontalDivider(color=PaletteTheme.separator)
                        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        BasicTextField(menus.query,menus::search,singleLine=true,
                            textStyle=TextStyle(color=PaletteTheme.ink,fontSize=13.sp,lineHeight=18.sp),cursorBrush=SolidColor(PaletteTheme.accent),
                            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search,showKeyboardOnFocus=keyboardRequested),
                            keyboardActions=KeyboardActions(onSearch={ menus.activateSelected(request) }),
                            modifier=Modifier.weight(1f).heightIn(min=48.dp).focusRequester(focus).testTag("inline-menu-search"),
                            decorationBox={ inner -> Box(Modifier.fillMaxWidth().heightIn(min=48.dp),contentAlignment=Alignment.CenterStart) {
                                if(menus.query.text.isEmpty()) Text("Search for actions…",color=PaletteTheme.secondary,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis); inner()
                            } })
                        if(wide) IconButton(onClick={keyboardRequested=true;focus.requestFocus();keyboard?.show()},modifier=Modifier.size(48.dp).semantics{contentDescription="Show action search keyboard"}.testTag("inline-menu-keyboard")) { Glyph("keyboard",PaletteTheme.secondary,16) }
                        }
                    }
                }
            }
        }) { measurables, constraints ->
            val margin = 8.dp.roundToPx()
            val left = surface.left.roundToInt() + margin
            val right = (surface.right.roundToInt() - margin).coerceAtLeast(left + 1)
            val headerBottom=(menus.header?.bottom ?: (surface.top+PaletteTheme.header.toPx())).coerceIn(surface.top,surface.bottom)
            val footerTop=(menus.footer?.top ?: (surface.bottom-PaletteTheme.footer.toPx())).coerceIn(surface.top,surface.bottom)
            val titleHeight=if(request.title.value!=null)16.sp.toPx()*2+16.dp.toPx() else 0f
            val searchHeight=if(request.searchable)maxOf(48.dp.toPx(),18.sp.toPx())+1.dp.toPx() else 0f
            val choiceHeight=maxOf(48.dp.toPx(),18.sp.toPx()*3+16.dp.toPx())
            val minimumMenuHeight=12.dp.toPx()+titleHeight+searchHeight+choiceHeight
            // The anchored gap must accommodate the scaled menu chrome and
            // one complete wrapped action. Otherwise use the palette bounds.
            val contentFits=footerTop-headerBottom >= minimumMenuHeight
            val top=(if(contentFits) headerBottom else surface.top+margin).roundToInt()
            val bottom=(if(contentFits) footerTop else surface.bottom-margin).roundToInt().coerceAtLeast(top+1)
            val width = minOf(320.dp.roundToPx(), right - left)
            // Measure real wrapped content. Fixed row estimates clip large-font
            // titles; the bounded lazy body scrolls when the menu is taller.
            val child = measurables.single().measure(Constraints(minWidth=width,maxWidth=width,maxHeight=bottom-top))
            val height=child.height
            val anchor = request.anchor.value ?: surface
            val x = (anchor.right.roundToInt() - width).coerceIn(left, right - width)
            val below = anchor.center.y < surface.center.y
            val y = if(request.searchable) bottom-height else (if (below) anchor.bottom.roundToInt() else anchor.top.roundToInt() - height).coerceIn(top, bottom - height)
            layout(constraints.maxWidth, constraints.maxHeight) { child.place(x, y) }
        }
    }
}

@Composable
private fun FeedbackViewport(state: SnackbarHostState, scroll: ScrollState) {
    var overflow by remember(state.currentSnackbarData) { mutableStateOf(false) }
    val usingScroll=overflow
    Box(Modifier.testTag("palette-feedback")) {
        // A scroll modifier can intercept taps in Snackbar's transparent outer
        // padding even when gestures are disabled. Attach it only when the
        // natural message height requires scrolling; keep one SnackbarHost so
        // measuring/reflow never restarts its display lifetime.
        Layout(modifier=Modifier.testTag("palette-feedback-scroll").clipToBounds(),content={
            SnackbarHost(state,modifier=if(usingScroll)Modifier.verticalScroll(scroll) else Modifier)
        }) { children, constraints ->
            val child=children.single().measure(if(usingScroll)constraints else constraints.copy(maxHeight=Constraints.Infinity))
            val height=child.height.coerceIn(constraints.minHeight,constraints.maxHeight)
            val needsScroll=if(usingScroll)scroll.maxValue>0 else child.height>height
            if(overflow!=needsScroll)overflow=needsScroll
            layout(child.width,height) { child.place(0,0) }
        }
        if(usingScroll && scroll.maxValue>0) Canvas(Modifier.matchParentSize().padding(end=2.dp,top=4.dp,bottom=4.dp)) {
            val thumb=(size.height*size.height/(size.height+scroll.maxValue)).coerceIn(minOf(12.dp.toPx(),size.height),size.height)
            val width=3.dp.toPx()
            drawRoundRect(PaletteTheme.secondary,Offset(size.width-width,(size.height-thumb)*scroll.value/scroll.maxValue),
                Size(width,thumb),CornerRadius(width/2))
        }
    }
}

@Composable
private fun PaletteFeedback(surface: Rect, menus: PaletteMenus, state: SnackbarHostState) {
    val scroll=key(state.currentSnackbarData) { rememberScrollState() }
    Layout(modifier = Modifier.fillMaxSize(), content = { FeedbackViewport(state,scroll) }) { children, constraints ->
        val margin = 12.dp.roundToPx()
        val surfaceWidth=surface.width.roundToInt().coerceAtLeast(1)
        val surfaceHeight=surface.height.roundToInt().coerceAtLeast(1)
        val horizontalInset=minOf(margin,(surfaceWidth-1)/2)
        val verticalInset=minOf(margin,(surfaceHeight-1)/2)
        val width=surfaceWidth-2*horizontalInset
        val top=surface.top.roundToInt()+verticalInset
        val bottom=top+surfaceHeight-2*verticalInset
        // Measure against the palette before choosing the chrome gap. A
        // scrolled-off header/footer has non-null but clipped/empty bounds;
        // using that gap as a measurement constraint can crush feedback to 1px.
        val child = children.single().measure(Constraints(maxWidth=width,maxHeight=bottom-top))
        // Footerless forms still place their controls near the lower edge.
        // Retain the legacy chrome reservation when no measured owner exists;
        // a non-null empty offscreen bound still selects the full-palette fallback.
        val headerBottom=(menus.header?.bottom ?: surface.top+PaletteTheme.header.toPx()).roundToInt().coerceIn(top,bottom)
        val footerTop=(menus.footer?.top ?: surface.bottom-PaletteTheme.footer.toPx()).roundToInt().coerceIn(top,bottom)
        val preferredBottom=footerTop-margin
        val x = (surface.center.x-child.width/2f).roundToInt()
        val y=if(preferredBottom-headerBottom>=child.height)preferredBottom-child.height else bottom-child.height
        layout(constraints.maxWidth,constraints.maxHeight) { child.place(x,y) }
    }
}
