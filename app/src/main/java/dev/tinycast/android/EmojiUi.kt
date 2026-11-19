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
import androidx.compose.runtime.saveable.rememberSaveable
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
fun EmojiPicker(store: EmojiStore, searchEpoch: Int, actionCurrent: () -> Boolean, close: () -> Unit, copy: (String) -> Unit) {
    DisposableEffect(store, searchEpoch) { onDispose { store.releaseRecentCaller() } }
    val languageTags = androidx.compose.ui.platform.LocalConfiguration.current.locales.toLanguageTags()
    LaunchedEffect(store, languageTags) { store.loadLanguages(languageTags) }
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
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val paletteMenus = LocalPaletteMenus.current
    val grid = rememberLazyGridState()
    val results by remember(store) { derivedStateOf { store.results() } }
    val selected = results.getOrNull(store.selected.coerceIn(0, (results.size - 1).coerceAtLeast(0)))
    var toneMenu by remember(store, searchEpoch, store.query, store.filter, selected?.id, windowFocused) { mutableStateOf(false) }
    var keywordId by rememberSaveable(searchEpoch) { mutableStateOf<String?>(null) }
    var editOpening by rememberSaveable(searchEpoch) { mutableIntStateOf(0) }
    var zoomColumns by rememberSaveable(searchEpoch) { mutableStateOf<Int?>(null) }
    val variants = store.skinTones(selected?.id)
    val anchorQuery = store.query
    val anchorFilter = store.filter
    // Key events can arrive back-to-back before recomposition. Resolve logical
    // selection and composition at event time, not from the last rendered frame.
    fun currentEntry(): EmojiEntry? = store.results().let { it.getOrNull(store.selected.coerceIn(0, it.lastIndex.coerceAtLeast(0))) }
    fun entryActionCurrent() = selected != null && currentEntry()?.id == selected.id && store.query == anchorQuery &&
        store.filter == anchorFilter && actionCurrent() && view.hasWindowFocus()
    fun isComposing() = searchValue.text == store.query && searchValue.composition != null
    fun copyEntry(entry: EmojiEntry) {
        copy(entry.glyph); store.copied(entry); copiedName = "Copied ${entry.name}"
        store.selected = store.results().indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    }
    fun pin(entry: EmojiEntry) {
        val removedFromLeadingFavorites = store.filter == "All" && store.query.isBlank() && entry.id in store.favorites
        val previousIndex = store.selected
        store.toggleFavorite(entry)
        if (removedFromLeadingFavorites) {
            store.selected = previousIndex.coerceIn(0, store.results().lastIndex.coerceAtLeast(0))
            return
        }
        store.selected = store.results().indexOfFirst { it.id == entry.id }.takeIf { it >= 0 }
            ?: store.selected.coerceAtMost((store.results().size - 1).coerceAtLeast(0))
    }
    fun reorderCurrent() = store.filter == "Favorites" && store.query.isBlank() && !isComposing() &&
        actionCurrent() && view.hasWindowFocus()
    fun moveChoice(offset: Int): PaletteChoice {
        val earlier = offset < 0
        val neighbor = results.getOrNull(store.selected + offset)
        return PaletteChoice(
            if (earlier) "emoji-favorite-earlier" else "emoji-favorite-later",
            if (earlier) "Move favorite earlier" else "Move favorite later",
            enabled = selected != null && neighbor != null,
            shortcut = listOf("Ctrl", "Alt", if (earlier) "↑" else "↓"),
            chord = KeyChord(if (earlier) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, ShortcutRules.CTRL or ShortcutRules.ALT)
        ) {
            if (selected != null && neighbor != null && reorderCurrent() && currentEntry()?.id == selected.id)
                store.moveFavorite(selected.id, neighbor.id, offset)
        }
    }
    fun handleFavoriteChord(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.isCtrlPressed xor event.isMetaPressed) && event.isAltPressed &&
            !event.isShiftPressed && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN) &&
            reorderCurrent() && !menu && !actionsMenu && !toneMenu && paletteMenus.current == null) {
            paletteMenus.claimPress(event)
            if (event.repeatCount == 0) {
                val offset = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                val current = store.results()
                val entry = current.getOrNull(store.selected)
                val neighbor = current.getOrNull(store.selected + offset)
                if (entry != null && neighbor != null) store.moveFavorite(entry.id, neighbor.id, offset)
            }
            return true
        }
        val modifier = (event.isCtrlPressed xor event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed
        if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_D || !modifier) return false
        paletteMenus.claimPress(event)
        if (event.repeatCount == 0) currentEntry()?.let(::pin)
        return true
    }
    fun currentHistoryView() = store.filter == "Recent" && actionCurrent() && view.hasWindowFocus()
    fun back() { if (keywordId != null) keywordId = null else if (toneMenu) toneMenu = false else if(actionsMenu) actionsMenu=false else if (menu) menu = false else if (queryEditing) queryEditing = false else if (store.query.isNotEmpty()) store.search("") else close() }
    BackHandler {
        PaletteBackTrace.handler("emoji-dispatcher-before", menu || actionsMenu || toneMenu, store.query.length)
        back()
        PaletteBackTrace.handler("emoji-dispatcher-after", menu || actionsMenu || toneMenu, store.query.length)
    }
    val editingId = keywordId
    if (editingId != null) {
        val opening = editOpening
        val entry = store.entries.firstOrNull { it.id == editingId }
        fun ownsEditor() = keywordId == editingId && opening == editOpening && actionCurrent() && view.hasWindowFocus()
        if (entry != null) key(opening) {
            EmojiKeywordEditor(entry, store, ::ownsEditor) {
                if (ownsEditor()) keywordId = null
            }
        } else if (!store.loading) LaunchedEffect(editingId) { keywordId = null }
        return
    }
    PaletteViewport(emptyFeedback=results.isEmpty()) { wide, top, height ->
        val compactEmpty = results.isEmpty() && height < 180.dp
        val gridWidth = minOf(maxWidth - 24.dp, PaletteTheme.width).value - 24
        // Each cell keeps at least 48 dp plus its share of the 4 dp gaps.
        val maxColumns = ((gridWidth + 4) / 52).toInt().coerceIn(1, 10)
        val minColumns = minOf(if (gridWidth >= 600) 6 else 3, maxColumns)
        val automaticColumns = (gridWidth / 64).toInt().coerceIn(minColumns, maxColumns)
        fun currentColumns() = (zoomColumns ?: store.defaultGridColumns ?: automaticColumns).coerceIn(minColumns, maxColumns)
        val columns = currentColumns()
        val sizeRatio = automaticColumns.toFloat() / columns
        fun canChangeGrid() = actionCurrent() && view.hasWindowFocus() && !isComposing()
        fun zoom(step: Int) {
            val current = currentColumns()
            val next = (current + step).coerceIn(minColumns, maxColumns)
            if (current != next && canChangeGrid()) zoomColumns = next
        }
        fun handleZoomChord(e: KeyEvent): Boolean {
            if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0 || !(e.isCtrlPressed xor e.isMetaPressed) ||
                e.isAltPressed || !canChangeGrid() || menu || actionsMenu || toneMenu || paletteMenus.current != null) return false
            when (e.keyCode) {
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> zoom(-1)
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> if (!e.isShiftPressed) zoom(1) else return false
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> if (!e.isShiftPressed) zoomColumns = null else return false
                else -> return false
            }
            paletteMenus.claimPress(e)
            return true
        }
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
        val scrollHistory = remember(columns) { PaletteScrollHistory() }
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
                    KeyEvent.KEYCODE_DPAD_UP -> (store.selected - currentColumns()).coerceAtLeast(0)
                    KeyEvent.KEYCODE_DPAD_DOWN -> (store.selected + currentColumns()).coerceAtMost(results.lastIndex)
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
                if (handleZoomChord(e) || handleFavoriteChord(e)) true
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
                if (handleZoomChord(e) || handleFavoriteChord(e)) true
                else if (e.action != KeyEvent.ACTION_DOWN) false
                else when {
                    e.keyCode == KeyEvent.KEYCODE_ESCAPE -> { back(); true }
                    ctrl && e.keyCode == KeyEvent.KEYCODE_K -> { actionsMenu = true; true }
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
                    // A menu borrows focus. CoreTextField's blur collapse must not
                    // overwrite the caller's committed range; every other update,
                    // including deliberate selection changes, still applies.
                    val current = searchValue
                    val keepMenuRange = (menu || actionsMenu || toneMenu || paletteMenus.current != null) &&
                        it.text == current.text && it.composition == null && current.composition == null &&
                        !current.selection.collapsed && it.selection == TextRange(current.selection.max)
                    val next = if (keepMenuRange) it.copy(selection = current.selection) else it
                    searchValue = next
                    if (next.text != store.query) { store.search(next.text); copiedName = null }
                }, singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min=48.dp).paletteFocusBookmark(searchFocus).onFocusChanged { searchFocused = it.isFocused }.testTag("emoji-search"),
                    textStyle = TextStyle(color = PaletteTheme.ink, fontSize = 20.sp, lineHeight = 26.sp), cursorBrush = SolidColor(PaletteTheme.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = !wide || keyboardRequested),
                    keyboardActions = KeyboardActions(onSearch = { if (!isComposing()) currentEntry()?.let(::copyEntry) }),
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth().heightIn(min=48.dp), contentAlignment = Alignment.CenterStart) {
                        if (store.query.isEmpty()) Text("Search Emoji & Symbols…", color = PaletteTheme.secondary, fontSize = 20.sp, lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner()
                    } })
                SearchClearButton(store.query.isNotEmpty(), Triple(store, searchEpoch, windowFocused), "emoji-search-clear",
                    current = { store.query.isNotEmpty() && actionCurrent() && view.hasWindowFocus() &&
                        !menu && !actionsMenu && !toneMenu && paletteMenus.current == null }) {
                    searchValue = TextFieldValue("")
                    store.search("")
                    copiedName = null
                    queryEditing = false
                }
                if (wide) SearchKeyboardButton(imeVisible, Triple(store, searchEpoch, windowFocused), "emoji-keyboard", searchFocus,
                    current = { actionCurrent() && !menu && !actionsMenu && !toneMenu && paletteMenus.current == null },
                    requested = { keyboardRequested = it })
                Box {
                    // Retain natural scaled text and a 48 dp target without extra
                    // vertical padding shifting this editor below the launcher.
                    TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal=12.dp, vertical=4.dp),
                        modifier = Modifier.heightIn(min=48.dp).testTag("emoji-filter")) {
                        Text(store.filter, maxLines = 1, fontSize = 12.sp, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = if (wide) 160.dp else 90.dp))
                        Text(" ▾")
                    }
                    PaletteChoiceMenu(menu, { menu = false },
                        (listOf("All", "Favorites", "Recent") + store.entries.map { it.category }.distinct()).map { category ->
                            PaletteChoice("emoji-filter-$category", category, checked = store.filter == category) {
                                store.chooseFilter(category); copiedName = null; queryEditing = false; menu = false
                            }
                        }, title = "Emoji categories", returnFocus = searchFocus)
                }
            }
            HorizontalDivider(color = PaletteTheme.separator)
            if (!compactEmpty) EmojiResultCount(results.size, wide, queryEditing)
            if (results.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f).testTag("emoji-empty"), contentAlignment = if(compactEmpty)Alignment.TopCenter else Alignment.Center) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally) {
                    // Use one primary status when only a small band remains
                    // above the keyboard. Keep duplicate counts/hints in the
                    // normal viewport, not as a clipped extra compact line.
                    val emptyMessage = when {
                        store.loading -> "Loading catalog…"
                        store.error != null -> store.error!!
                        store.filter == "Recent" && !store.historyEnabled -> {
                            if (store.hasSavedHistory) "Recent history is off. Enable it to show saved copies and remember new ones."
                            else "Recent history is off. You can remember up to 32 copies on this device."
                        }
                        store.query.isNotBlank() -> "No matching emoji or symbols"
                        store.filter == "Favorites" -> "Favorite a symbol with the star or Ctrl D"
                        store.filter == "Recent" -> "Symbols you copy will appear here"
                        else -> "No symbols in this category"
                    }
                    Text(emptyMessage, color = PaletteTheme.secondary, fontSize = 13.sp,
                        lineHeight=if(compactEmpty)16.sp else TextUnit.Unspecified,
                        modifier = Modifier.padding(horizontal=20.dp,vertical=if(compactEmpty)0.dp else 20.dp))
                    if (store.filter == "Recent" && !store.historyEnabled && !store.loading && store.error == null) {
                        TextButton(
                            onClick = { if (currentHistoryView() && !store.historyEnabled) store.rememberRecent(true) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("emoji-enable-history")
                        ) { Text("Enable recent history") }
                    }
                }
            } else LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).onFocusChanged { gridFocused = it.hasFocus }.focusable().testTag("emoji-grid"),
                contentPadding = PaddingValues(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(results, key = { _, entry -> entry.id }) { index, entry ->
                    Box(Modifier.fillMaxWidth().heightIn(min=(56 * sizeRatio).coerceIn(48f, 92f).dp).clip(RoundedCornerShape(10.dp))
                        .background(if (index == store.selected) PaletteTheme.selection else Color.Transparent)
                        .border(1.dp, if (index == store.selected) PaletteTheme.border else Color.Transparent, RoundedCornerShape(10.dp))
                        // One keyboard focus stop for the grid; arrows move its selection.
                        // Cells remain individually clickable and named for accessibility.
                        .focusProperties { canFocus = false }
                        .clickable { store.selected = index; copyEntry(entry) }
                        .semantics { this.selected = index == store.selected; contentDescription = "${entry.name}, ${entry.category}" + if (entry.id in store.favorites) ", favorite" else "" }
                        .testTag("emoji-cell-${entry.id}"), contentAlignment = Alignment.Center) {
                        Text(entry.glyph, fontSize = (28 * sizeRatio).coerceIn(24f, 44f).sp)
                        if (entry.id in store.favorites) Text("★", color = PaletteTheme.accent, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
            }
            HorizontalDivider(color = PaletteTheme.separator)
            PaletteFooter(metadata={ modifier ->
                Column(modifier) {
                    Text(selected?.name ?: "Emoji & Symbols", fontSize=12.sp,lineHeight=16.sp,maxLines=2,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("emoji-selected-name"))
                    // The polite live region must already exist when a copy happens,
                    // or TalkBack treats it as a new node and stays silent. Phones
                    // hide the selection hint once a symbol is selected by leaving
                    // the region empty at 1 dp; copy/edit status still shows.
                    val status = copiedName ?: if(queryEditing) "Editing query" else if(wide) "Arrows select · Enter copy · Ctrl L edit"
                        else if(selected == null) "Tap a symbol to select" else ""
                    Text(status,fontSize=11.sp,lineHeight=15.sp,color=PaletteTheme.secondary,maxLines=2,overflow=TextOverflow.Ellipsis,
                        modifier=(if(status.isEmpty()) Modifier.height(1.dp) else Modifier).semantics { liveRegion=LiveRegionMode.Polite }.testTag("emoji-copy-status"))
                }
            },leading={
                IconButton(onClick={currentEntry()?.let(::pin)},enabled=selected!=null,
                    modifier=Modifier.size(48.dp).testTag("emoji-favorite").semantics { contentDescription=if(selected?.id in store.favorites)"Remove favorite"else"Add favorite" }) {
                    Glyph(if(selected?.id in store.favorites)"star-filled"else"star",PaletteTheme.accent,22)
                }
            }) {
                Box {
                    PaletteActionCapsule(if(wide) "Copy to Clipboard" else "Copy",{currentEntry()?.let(::copyEntry)},{actionsMenu=true},"emoji-copy","emoji-actions",
                        compact=!wide,enabled=selected!=null,actionsEnabled=true,description="Copy to Clipboard")
                    PaletteChoiceMenu(actionsMenu,{actionsMenu=false},listOf(
                        PaletteChoice("emoji-action-copy","Copy to Clipboard",enabled=selected!=null,shortcut=listOf("↵"),icon="clipboard") { currentEntry()?.let(::copyEntry) },
                        PaletteChoice("emoji-action-favorite",if(selected?.id in store.favorites)"Remove Favorite"else"Add Favorite",enabled=selected!=null,
                            shortcut=listOf("Ctrl","D"),icon="star",separator=true,chord=KeyChord(KeyEvent.KEYCODE_D,ShortcutRules.CTRL)) {currentEntry()?.let(::pin)},
                        PaletteChoice("emoji-action-unicode","Copy Unicode",enabled=selected!=null,icon="clipboard") {
                            currentEntry()?.let { entry -> copy(entry.glyph.codePoints().toArray().joinToString(" "){"U+"+it.toString(16).uppercase().padStart(4,'0')}); copiedName="Copied Unicode for ${entry.name}" }
                        },
                        PaletteChoice("emoji-action-keywords", "Edit custom keywords", enabled = selected != null) {
                            if (selected != null && entryActionCurrent() && !isComposing()) {
                                editOpening++
                                keywordId = selected.id
                            }
                        }
                    ) + (if (variants.isEmpty()) emptyList() else listOf(
                        PaletteChoice("emoji-action-tone", "Copy with skin tone…") {
                            if (entryActionCurrent() && !isComposing()) toneMenu = true
                        }
                    )) + (if (store.filter == "Favorites" && store.query.isBlank()) listOf(moveChoice(-1), moveChoice(1)) else emptyList()) + (if (store.filter == "Recent") listOf(
                        if (store.historyEnabled)
                            PaletteChoice("emoji-history-pause", "Pause recent history") { if (currentHistoryView()) store.rememberRecent(false) }
                        else
                            PaletteChoice("emoji-history-enable", "Enable recent history") { if (currentHistoryView()) store.rememberRecent(true) },
                        PaletteChoice("emoji-history-clear", "Clear saved emoji history", enabled = store.hasSavedHistory) {
                            if (currentHistoryView()) store.clearHistory()
                        }
                    ) else emptyList()) + listOf(
                        PaletteChoice("emoji-zoom-in", "Zoom in", enabled=columns > minColumns, shortcut=listOf("Ctrl", "+"), chord=KeyChord(KeyEvent.KEYCODE_EQUALS, ShortcutRules.CTRL or ShortcutRules.SHIFT), separator=true) { zoom(-1) },
                        PaletteChoice("emoji-zoom-out", "Zoom out", enabled=columns < maxColumns, shortcut=listOf("Ctrl", "−"), chord=KeyChord(KeyEvent.KEYCODE_MINUS, ShortcutRules.CTRL)) { zoom(1) },
                        PaletteChoice("emoji-actual-size", "Actual size", enabled=zoomColumns != null, shortcut=listOf("Ctrl", "0"), chord=KeyChord(KeyEvent.KEYCODE_0, ShortcutRules.CTRL)) {
                            if (canChangeGrid()) zoomColumns = null
                        },
                        PaletteChoice("emoji-save-size", "Set default to $columns columns", enabled=store.defaultGridColumns != columns) {
                            if (canChangeGrid() && currentColumns() == columns) { store.saveGridColumns(columns); zoomColumns = null }
                        },
                        PaletteChoice("emoji-auto-size", "Use automatic grid size", enabled=store.defaultGridColumns != null) {
                            if (canChangeGrid()) { store.saveGridColumns(null); zoomColumns = null }
                        }
                    ),title=selected?.name ?: "Emoji & Symbols",returnFocus=searchFocus,searchable=true)
                    if (toneMenu) PaletteChoiceMenu(true, { toneMenu = false }, variants.mapIndexed { index, variant ->
                        PaletteChoice("emoji-tone-${variant.id}", "${variant.glyph}  ${skinToneLabel(index, variants.size)}",
                            checked = selected?.id == variant.id, activateBeforeDismiss = true) {
                            if (selected != null && toneMenu && entryActionCurrent() && !isComposing()) {
                                copy(variant.glyph)
                                store.copiedVariant(variant, selected.id)
                                copiedName = "Copied ${variant.name}"
                            }
                        }
                    }, title = "Copy with skin tone", returnFocus = searchFocus, searchable = true)
                }
            }
        }
    }
}

@Composable
private fun EmojiResultCount(count: Int, wide: Boolean, queryEditing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=7.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("$count ${if(count==1)"result"else"results"} · offline",color=PaletteTheme.secondary,fontSize=11.sp,modifier=Modifier.testTag("emoji-count"))
        if(wide) Text(if(queryEditing)"Editing query · ↓ browse results"else"Type to search · arrows select · Ctrl L edit",color=PaletteTheme.secondary,fontSize=11.sp)
    }
}

private val skinToneNames = listOf("Light", "Medium-light", "Medium", "Medium-dark", "Dark")

private fun skinToneLabel(index: Int, familySize: Int): String {
    if (index == 0) return "Default"
    if (familySize == 6) return skinToneNames[index - 1]
    val first = (index - 1) / 5
    val second = (index - 1) % 5
    return if (first == second) "Both: ${skinToneNames[first]}"
        else "${skinToneNames[first]} + ${skinToneNames[second]}"
}
