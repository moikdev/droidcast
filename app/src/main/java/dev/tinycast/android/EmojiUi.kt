package dev.tinycast.android

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** A palette sub-screen; no Dialog, overlay permission or clipboard monitoring. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
fun EmojiPicker(store: EmojiStore, close: () -> Unit, copy: (String) -> Unit) {
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var searchFocused by remember { mutableStateOf(false) }
    var searchValue by remember { mutableStateOf(TextFieldValue(store.query)) }
    val displayedSearch = if (searchValue.text == store.query) searchValue else TextFieldValue(store.query, TextRange(store.query.length))
    SideEffect { if (searchValue != displayedSearch) searchValue = displayedSearch }
    var gridFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    var keyboardRequested by remember { mutableStateOf(false) }
    var queryEditing by remember { mutableStateOf(false) }
    var copiedName by remember { mutableStateOf<String?>(null) }
    // Pair a claimed favorite chord with its physical key sequence, independent
    // of later modifier release or the selection changing after an unpin.
    val favoritePresses = remember { mutableMapOf<Int, Long>() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) favoritePresses.clear() }
    fun consumeFavoriteSequence(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_D || favoritePresses[event.deviceId] != event.downTime) return false
        if (event.action == KeyEvent.ACTION_UP) favoritePresses.remove(event.deviceId)
        return true
    }
    val grid = rememberLazyGridState()
    val results by remember(store) { derivedStateOf { store.results() } }
    val selected = results.getOrNull(store.selected.coerceIn(0, (results.size - 1).coerceAtLeast(0)))
    // Key events can arrive back-to-back before recomposition. Resolve logical
    // selection and composition at event time, not from the last rendered frame.
    fun currentEntry(): EmojiEntry? = store.results().let { it.getOrNull(store.selected.coerceIn(0, it.lastIndex.coerceAtLeast(0))) }
    fun isComposing() = searchValue.text == store.query && searchValue.composition != null
    fun copyEntry(entry: EmojiEntry) {
        copy(entry.glyph); store.copied(entry); copiedName = "Copied ${entry.name}"
        store.selected = store.results().indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    }
    fun pin(entry: EmojiEntry) {
        store.toggleFavorite(entry)
        store.selected = store.results().indexOfFirst { it.id == entry.id }.takeIf { it >= 0 }
            ?: store.selected.coerceAtMost((store.results().size - 1).coerceAtLeast(0))
    }
    fun handleFavoriteChord(event: KeyEvent): Boolean {
        if (consumeFavoriteSequence(event)) return true
        val modifier = (event.isCtrlPressed xor event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed
        if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_D || !modifier) return false
        favoritePresses[event.deviceId] = event.downTime
        if (event.repeatCount == 0) currentEntry()?.let(::pin)
        return true
    }
    fun back() { if(actionsMenu) actionsMenu=false else if (menu) menu = false else if (queryEditing) queryEditing = false else if (store.query.isNotEmpty()) store.search("") else close() }
    BackHandler(onBack = ::back)
    PaletteViewport { wide, top, height ->
        val columns = ((minOf(maxWidth - 24.dp, PaletteTheme.width).value - 24) / 64).toInt().coerceIn(3, 10)
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
        val imeVisible = WindowInsets.isImeVisible
        LaunchedEffect(wide, imeVisible, keyboardRequested, windowFocused, searchFocused) {
            if (wide && !keyboardRequested && windowFocused && searchFocused) {
                // Let the replacement text-input session attach before hiding an
                // IME inherited from the command search. No typing delay.
                withFrameNanos { }
                keyboard?.hide()
                androidx.core.view.ViewCompat.getWindowInsetsController(view)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
        }
        val scrollHistory = remember { PaletteScrollHistory() }
        val resultIds = remember(results) { results.map { it.id } }
        SideEffect {
            val index = store.selected.coerceIn(0, results.lastIndex.coerceAtLeast(0))
            if (store.selected != index) store.selected = index
            val layout = grid.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
            val visible = item != null && item.offset.y >= layout.viewportStartOffset && item.offset.y + item.size.height <= layout.viewportEndOffset
            if (scrollHistory.update(store.query, store.filter, resultIds, index, visible)) grid.requestScrollToItem(index)
        }
        LaunchedEffect(grid, scrollHistory) {
            snapshotFlow {
                val layout = grid.layoutInfo
                val index = store.selected.coerceIn(0, (layout.totalItemsCount - 1).coerceAtLeast(0))
                val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                val visible = item != null && item.offset.y >= layout.viewportStartOffset && item.offset.y + item.size.height <= layout.viewportEndOffset
                Triple(layout.viewportEndOffset - layout.viewportStartOffset, index, visible)
            }.collect { (height, index, visible) ->
                if (scrollHistory.viewportMeasured(height, visible)) grid.requestScrollToItem(index)
            }
        }
        fun navigate(event: KeyEvent): Boolean {
            val plain = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
            val navigation = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END)
            if (!plain || !navigation || !(searchFocused || gridFocused) || isComposing()) return false
            if (queryEditing && searchFocused && event.keyCode !in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                queryEditing = false
                if (results.isNotEmpty()) store.selected = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> (store.selected - 1).coerceAtLeast(0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> (store.selected + 1).coerceAtMost(results.lastIndex)
                    KeyEvent.KEYCODE_DPAD_UP -> (store.selected - columns).coerceAtLeast(0)
                    KeyEvent.KEYCODE_DPAD_DOWN -> (store.selected + columns).coerceAtMost(results.lastIndex)
                    KeyEvent.KEYCODE_MOVE_HOME -> 0
                    else -> results.lastIndex
                }
            }
            return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        }
        Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { close() }.testTag("emoji-backdrop"))
        PaletteColumn(Modifier.padding(top = top).padding(horizontal = 12.dp).widthIn(max = PaletteTheme.width).fillMaxWidth().height(height).align(Alignment.TopCenter)
            .shadow(24.dp, RoundedCornerShape(PaletteTheme.radius)).clip(RoundedCornerShape(PaletteTheme.radius))
            .background(PaletteTheme.surface).border(1.dp, PaletteTheme.border, RoundedCornerShape(PaletteTheme.radius))
            .paletteMenuSurface().pointerInput(Unit) { detectTapGestures {} }
            // Handle owned favorite chords before IME text conversion. Some IMEs
            // also consume keypad Enter without delivering an editor action;
            // that activation path leaves active composition alone.
            .onPreInterceptKeyBeforeSoftKeyboard { key ->
                val e = key.nativeKeyEvent
                // Modified text-navigation keys explicitly enter caret editing,
                // even when the IME handles the event before Compose receives it.
                if (e.action == KeyEvent.ACTION_DOWN && searchFocused && (e.isShiftPressed || e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) &&
                    e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END)) queryEditing = true
                // Once claimed, repeats must not become IME text when Ctrl/Meta is released first.
                if (handleFavoriteChord(e)) true
                else if (navigate(e)) true
                else if (searchFocused && !isComposing() && e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER &&
                    !e.isAltPressed && !e.isShiftPressed && !(e.isCtrlPressed && e.isMetaPressed)) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) currentEntry()?.let(::copyEntry)
                    true
                } else false
            }
            .onPreviewKeyEvent { key ->
                val e = key.nativeKeyEvent
                val ctrl = (e.isCtrlPressed xor e.isMetaPressed) && !e.isAltPressed && !e.isShiftPressed
                val plain = !e.isCtrlPressed && !e.isMetaPressed && !e.isAltPressed && !e.isShiftPressed
                if (handleFavoriteChord(e)) true
                else if (e.action != KeyEvent.ACTION_DOWN) false
                else when {
                    e.keyCode == KeyEvent.KEYCODE_ESCAPE -> { back(); true }
                    ctrl && e.keyCode == KeyEvent.KEYCODE_K -> { actionsMenu = currentEntry()!=null; true }
                    ctrl && e.keyCode == KeyEvent.KEYCODE_L -> { queryEditing = true; searchFocus.requestFocus(); true }
                    navigate(e) -> true
                    searchFocused && (e.isShiftPressed || ctrl) && e.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END) -> { queryEditing = true; false }
                    (plain || ctrl) && (searchFocused || gridFocused) && !isComposing() && e.keyCode in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER) -> { currentEntry()?.let(::copyEntry); true }
                    else -> false
                }
            }.semantics { testTagsAsResourceId = true }.testTag("emoji-palette")) {
            Row(Modifier.fillMaxWidth().heightIn(min=PaletteTheme.header).paletteHeaderSurface().padding(end=8.dp,top=4.dp,bottom=4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back to commands" }.testTag("emoji-back")) { Glyph("back",PaletteTheme.ink,24) }
                Spacer(Modifier.width(2.dp))
                BasicTextField(displayedSearch, {
                    searchValue = it
                    if (it.text != store.query) { store.search(it.text); copiedName = null }
                }, singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min=48.dp).paletteFocusBookmark(searchFocus).onFocusChanged { searchFocused = it.isFocused }.testTag("emoji-search"),
                    textStyle = TextStyle(color = PaletteTheme.ink, fontSize = 20.sp, lineHeight = 26.sp), cursorBrush = SolidColor(PaletteTheme.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = !wide || keyboardRequested),
                    keyboardActions = KeyboardActions(onSearch = { if (!isComposing()) currentEntry()?.let(::copyEntry) }),
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth().heightIn(min=48.dp), contentAlignment = Alignment.CenterStart) {
                        if (store.query.isEmpty()) Text("Search Emoji & Symbols…", color = PaletteTheme.secondary, fontSize = 20.sp, lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner()
                    } })
                if (wide) IconButton(onClick = { keyboardRequested = true; searchFocus.requestFocus(); keyboard?.show() }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Show on-screen keyboard" }) {
                    Glyph("keyboard", PaletteTheme.tertiary, 16)
                }
                Box {
                    TextButton(onClick = { menu = true }, modifier = Modifier.testTag("emoji-filter")) { Text(store.filter, maxLines = 1, fontSize = 12.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (wide) 160.dp else 90.dp)); Text(" ▾") }
                    PaletteChoiceMenu(menu, { menu = false },
                        (listOf("All", "Favorites", "Recent") + store.entries.map { it.category }.distinct()).map { category ->
                            PaletteChoice("emoji-filter-$category", category, checked = store.filter == category) {
                                store.chooseFilter(category); copiedName = null; queryEditing = false; menu = false
                            }
                        }, title = "Emoji categories", returnFocus = searchFocus)
                }
            }
            HorizontalDivider(color = PaletteTheme.separator)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${results.size} ${if (results.size == 1) "result" else "results"} · offline", color = PaletteTheme.secondary, fontSize = 11.sp, modifier = Modifier.testTag("emoji-count"))
                if (wide) Text(if (queryEditing) "Editing query · ↓ browse results" else "Type to search · arrows select · Ctrl L edit", color = PaletteTheme.secondary, fontSize = 11.sp)
            }
            if (results.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f).testTag("emoji-empty"), contentAlignment = Alignment.Center) {
                Text(when { store.loading -> "Loading catalog…"; store.error != null -> store.error!!; store.query.isNotBlank() -> "No matching emoji or symbols"; store.filter == "Favorites" -> "Favorite a symbol with the star or Ctrl D"; store.filter == "Recent" -> "Symbols you copy will appear here"; else -> "No symbols in this category" }, color = PaletteTheme.secondary, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp))
            } else LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).onFocusChanged { gridFocused = it.hasFocus }.focusable().testTag("emoji-grid"),
                contentPadding = PaddingValues(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(results, key = { _, entry -> entry.id }) { index, entry ->
                    Box(Modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (index == store.selected) PaletteTheme.selection else Color.Transparent)
                        .border(1.dp, if (index == store.selected) PaletteTheme.border else Color.Transparent, RoundedCornerShape(10.dp))
                        // One keyboard focus stop for the grid; arrows move its selection.
                        // Cells remain individually clickable and named for accessibility.
                        .focusProperties { canFocus = false }
                        .clickable { store.selected = index; copyEntry(entry) }
                        .semantics { this.selected = index == store.selected; contentDescription = "${entry.name}, ${entry.category}" + if (entry.id in store.favorites) ", favorite" else "" }
                        .testTag("emoji-cell-${entry.id}"), contentAlignment = Alignment.Center) {
                        Text(entry.glyph, fontSize = 28.sp)
                        if (entry.id in store.favorites) Text("★", color = PaletteTheme.accent, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
            }
            HorizontalDivider(color = PaletteTheme.separator)
            PaletteFooter(metadata={ modifier ->
                Column(modifier) {
                    Text(selected?.name ?: "Emoji & Symbols", fontSize=12.sp,lineHeight=16.sp,maxLines=2,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("emoji-selected-name"))
                    Text(copiedName ?: if(queryEditing) "Editing query" else if(wide) "Arrows select · Enter copy · Ctrl L edit" else "Tap a symbol to select",
                        fontSize=11.sp,lineHeight=15.sp,color=PaletteTheme.secondary,maxLines=2,overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }.testTag("emoji-copy-status"))
                }
            },leading={
                IconButton(onClick={currentEntry()?.let(::pin)},enabled=selected!=null,
                    modifier=Modifier.size(48.dp).testTag("emoji-favorite").semantics { contentDescription=if(selected?.id in store.favorites)"Remove favorite"else"Add favorite" }) {
                    Glyph(if(selected?.id in store.favorites)"star-filled"else"star",PaletteTheme.accent,22)
                }
            }) {
                Box {
                    PaletteActionCapsule("Copy to Clipboard",{currentEntry()?.let(::copyEntry)},{actionsMenu=true},"emoji-copy","emoji-actions",compact=!wide,enabled=selected!=null)
                    PaletteChoiceMenu(actionsMenu,{actionsMenu=false},listOf(
                        PaletteChoice("emoji-action-copy","Copy to Clipboard",enabled=selected!=null,shortcut=listOf("↵"),icon="clipboard") { currentEntry()?.let(::copyEntry) },
                        PaletteChoice("emoji-action-favorite",if(selected?.id in store.favorites)"Remove Favorite"else"Add Favorite",enabled=selected!=null,
                            shortcut=listOf("Ctrl","D"),icon="star",separator=true,chord=KeyChord(KeyEvent.KEYCODE_D,ShortcutRules.CTRL)) {currentEntry()?.let(::pin)},
                        PaletteChoice("emoji-action-unicode","Copy Unicode",enabled=selected!=null,icon="clipboard") {
                            currentEntry()?.let { entry -> copy(entry.glyph.codePoints().toArray().joinToString(" "){"U+"+it.toString(16).uppercase().padStart(4,'0')}); copiedName="Copied Unicode for ${entry.name}" }
                        }
                    ),title=selected?.name ?: "Emoji & Symbols",returnFocus=searchFocus,searchable=true)
                }
            }
        }
    }
}
