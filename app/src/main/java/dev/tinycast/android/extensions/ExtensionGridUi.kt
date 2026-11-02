package dev.tinycast.android.extensions

import android.view.KeyEvent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteScrollHistory
import dev.tinycast.android.PaletteTheme
import dev.tinycast.android.PaletteChoice
import dev.tinycast.android.PaletteChoiceMenu
import dev.tinycast.android.paletteHeaderSurface
import dev.tinycast.android.paletteFocusBookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

private class GridVisibilityRecovery { var pending = false }
private data class GridMeasurement(val height: Int, val selected: Int, val visible: Boolean,
                                   val rows: List<Triple<Int, Int, Int>>, val scrolling: Boolean)

internal class ExtensionGridState {
    var search by mutableStateOf(TextFieldValue(""))
    var selectedKey by mutableStateOf<String?>(null)
    var lastRemoteSearch: String? = null
    var lastRemoteSelection: String? = null
    var pendingRemoteSelection by mutableStateOf<String?>(null)
    var dropdownValue by mutableStateOf<String?>(null)
    val scroll = LazyGridState()
    val history = PaletteScrollHistory()
    val selectionDelivery = ExtensionSelectionDelivery()
    fun resolve(items: List<ExtensionGridItem>, remote: String?) = gridSelection(
        items.map { GridIdentity(it.key, it.id) }, selectedKey, remote, lastRemoteSelection, pendingRemoteSelection)
    fun adopt(remote: String?, choice: GridSelectionChoice) {
        lastRemoteSelection = remote
        pendingRemoteSelection = choice.pendingId
        selectedKey = choice.key
    }
    fun selectLocally(key: String?) {
        pendingRemoteSelection = null
        selectedKey = key
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun NativeExtensionGrid(
    state: ExtensionGridState, rootProvider: () -> JSONObject, images: ExtensionGridImages,
    title: String, status: String, preferences: android.content.SharedPreferences, commandKey: String, dispatch: (String?, JSONArray) -> Unit, back: () -> Unit, configure: () -> Unit,
) {
    val root = rootProvider()
    val props = root.props()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val wide = dev.tinycast.android.LocalPaletteWide.current
    var keyboardRequested by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var gridFocused by remember { mutableStateOf(false) }
    var editingQuery by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dropdownMenu by remember { mutableStateOf(false) }
    var gridHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Two scaled title lines and a subtitle, plus cell/grid padding and
    // rounding space, must fit before assigning any height to the preview.
    val cellOverhead = with(density) { 40.dp + 16.sp.toDp() * 2 + 12.sp.toDp() }
    val claims = remember { mutableMapOf<Pair<Int, Int>, Long>() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(windowFocused) { if (!windowFocused) claims.clear() }
    val remoteQuery = props.opt("searchText") as? String
    val remoteSelection = props.opt("selectedItemId") as? String
    val dropdown = props.optJSONObject("searchBarAccessory")?.nodes("Grid.Dropdown")?.firstOrNull()
    val options = dropdown?.nodes("Grid.Dropdown.Item").orEmpty()
    val dropdownKey = "grid-dropdown-$commandKey-${dropdown?.props()?.optString("id", "accessory")}"
    val controlledDropdown = dropdown?.props()?.opt("value") as? String
    LaunchedEffect(dropdown?.optString("id"), controlledDropdown, options.map { it.props().optString("value") }) {
        if (dropdown != null) {
            val p = dropdown.props()
            val stored = if (p.optBoolean("storeValue")) preferences.getString(dropdownKey, null) else null
            val desired = controlledDropdown ?: state.dropdownValue ?: stored ?: p.optString("defaultValue")
            val chosen = options.firstOrNull { it.props().optString("value") == desired } ?: options.firstOrNull()
            val value = chosen?.props()?.optString("value")
            if (value != null && state.dropdownValue != value) {
                state.dropdownValue = value
                if (controlledDropdown == null) dispatch(dropdown.handle("onChange"), JSONArray().put(value))
            }
        }
    }
    SideEffect {
        if (remoteQuery != null && remoteQuery != state.lastRemoteSearch && remoteQuery != state.search.text) state.search = TextFieldValue(remoteQuery, androidx.compose.ui.text.TextRange(remoteQuery.length))
        state.lastRemoteSearch = remoteQuery
    }
    val sections = gridSections(root, state.search.text)
    val items = sections.flatMap { it.items }
    val selection = state.resolve(items, remoteSelection)
    val selectedIndex = items.indexOfFirst { it.key == selection.key }.coerceAtLeast(0)
    val selected = items.getOrNull(selectedIndex)
    SideEffect { state.adopt(remoteSelection, selection) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(wide, keyboardRequested, windowFocused, searchFocused, imeVisible) {
        if (wide && !keyboardRequested && windowFocused && searchFocused) { withFrameNanos { }; keyboard?.hide() }
    }
    fun current(): Pair<List<ExtensionGridSection>, ExtensionGridItem?> {
        val latest = rootProvider()
        val groups = gridSections(latest, state.search.text)
        val all = groups.flatMap { it.items }
        val remote = latest.props().opt("selectedItemId") as? String
        val choice = state.resolve(all, remote)
        state.adopt(remote, choice)
        return groups to all.firstOrNull { it.key == choice.key }
    }
    fun notifyCurrentSelection() {
        val latest = rootProvider()
        val callback = latest.handle("onSelectionChange") ?: return
        val (_, chosen) = current()
        // Retain unresolved controlled IDs until their items arrive.
        if (state.pendingRemoteSelection != null) return
        state.selectionDelivery.deliver(latest.optString("id"), chosen?.id) {
            dispatch(callback, JSONArray().put(chosen?.id ?: JSONObject.NULL))
        }
    }
    LaunchedEffect(root.optString("id"), selected?.id, selection.pendingId, root.handle("onSelectionChange")) { notifyCurrentSelection() }
    fun currentActions(): List<JSONObject> = current().second?.node?.actions().orEmpty().ifEmpty { rootProvider().actions() }
    fun action(node: JSONObject?) {
        node?.let {
            state.search = state.search.copy(composition = null)
            notifyCurrentSelection()
            dispatch(it.handle("onAction"), JSONArray())
        }
        menu = false
    }
    val actions: List<JSONObject> = selected?.node?.actions().orEmpty().ifEmpty { root.actions() }
    val history = state.history
    val recovery = remember { GridVisibilityRecovery() }

    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("extension-grid-screen")) {
        val columns = gridColumns(maxWidth.value, props.optInt("columns"), props.optString("itemSize"))
        val widthDp = maxWidth.value
        val slots = gridSlots(sections.map { it.items.size }, sections.map { it.title.isNotBlank() || it.subtitle.isNotBlank() })
        val layoutIndices = slots.mapIndexedNotNull { index, slot -> index.takeIf { slot is GridSlot.Cell } }
        val selectedLayout = layoutIndices.getOrNull(selectedIndex) ?: 0
        val currentLayoutIndex by rememberUpdatedState(selectedLayout)
        SideEffect {
            val layout = state.scroll.layoutInfo
            val cell = layout.visibleItemsInfo.firstOrNull { it.index == selectedLayout }
            val visible = cell != null && cell.offset.y >= layout.viewportStartOffset && cell.offset.y + cell.size.height <= layout.viewportEndOffset
            if (history.update(state.search.text, columns.toString(), items.map { it.key }, selectedLayout, visible)) {
                recovery.pending = true
                state.scroll.requestScrollToItem(if (selectedIndex == 0) 0 else selectedLayout)
            } else if (visible) recovery.pending = false
        }
        LaunchedEffect(state.scroll, history) {
            snapshotFlow {
                val layout = state.scroll.layoutInfo
                val cell = layout.visibleItemsInfo.firstOrNull { it.index == currentLayoutIndex }
                GridMeasurement(layout.viewportEndOffset - layout.viewportStartOffset, currentLayoutIndex,
                    cell != null && cell.offset.y >= layout.viewportStartOffset && cell.offset.y + cell.size.height <= layout.viewportEndOffset,
                    layout.visibleItemsInfo.map { Triple(it.index, it.offset.y, it.size.height) }, state.scroll.isScrollInProgress)
            }.collect { measured ->
                if (history.viewportMeasured(measured.height, measured.visible)) recovery.pending = true
                if (measured.visible || measured.scrolling) recovery.pending = false
                // Data and IME changes can invalidate a snap before its next
                // measure. Retry against measured rows, then stop once visible.
                if (recovery.pending && measured.height > 0) state.scroll.requestScrollToItem(measured.selected)
            }
        }
        fun key(event: KeyEvent): Boolean {
            val id = event.deviceId to event.keyCode
            if (claims[id] == event.downTime) {
                if (event.action == KeyEvent.ACTION_UP) claims.remove(id)
                return true
            }
            if (event.action != KeyEvent.ACTION_DOWN || menu || dropdownMenu || !(searchFocused || gridFocused) || state.search.composition != null) return false
            val plain = !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed && !event.isShiftPressed
            val ctrl = (event.isCtrlPressed xor event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed
            if (ctrl && event.keyCode == KeyEvent.KEYCODE_L) { editingQuery = true; focus.requestFocus(); claims[id] = event.downTime; return true }
            val move = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> GridMove.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> GridMove.RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> GridMove.UP; KeyEvent.KEYCODE_DPAD_DOWN -> GridMove.DOWN
                KeyEvent.KEYCODE_MOVE_HOME -> GridMove.HOME; KeyEvent.KEYCODE_MOVE_END -> GridMove.END; else -> null
            }
            if (plain && move != null && (!editingQuery || !searchFocused || move in listOf(GridMove.UP, GridMove.DOWN))) {
                val (groups, current) = current(); val all = groups.flatMap { it.items }
                if (all.isNotEmpty()) {
                    val index = all.indexOf(current).coerceAtLeast(0)
                    state.selectLocally(all[gridMove(index, move, groups.map { it.items.size }, columns)].key)
                    notifyCurrentSelection()
                }
                editingQuery = false; claims[id] = event.downTime; return true
            }
            if (plain && event.keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER)) {
                if (event.repeatCount == 0) action(currentActions().firstOrNull())
                claims[id] = event.downTime; return true
            }
            if (ctrl && event.keyCode == KeyEvent.KEYCODE_K) { menu = currentActions().isNotEmpty(); claims[id] = event.downTime; return true }
            if (ctrl && event.keyCode == KeyEvent.KEYCODE_P && options.isNotEmpty()) { dropdownMenu = true; claims[id] = event.downTime; return true }
            if (move != null && !plain) editingQuery = true
            return false
        }
        Column(Modifier.fillMaxSize().onPreInterceptKeyBeforeSoftKeyboard { key(it.nativeKeyEvent) }.onPreviewKeyEvent { key(it.nativeKeyEvent) }) {
            Row(Modifier.fillMaxWidth().heightIn(min=PaletteTheme.header).paletteHeaderSurface().padding(end=8.dp,top=4.dp,bottom=4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }.testTag("extension-back")) { Glyph("back",PaletteTheme.ink,24) }
                Spacer(Modifier.width(2.dp))
                BasicTextField(state.search, { next ->
                    val changed = next.text != state.search.text
                    state.search = next
                    if (changed) { state.selectLocally(null); dispatch(rootProvider().handle("onSearchTextChange"), JSONArray().put(next.text)) }
                }, singleLine = true, textStyle = TextStyle(color = PaletteTheme.ink, fontSize = 20.sp, lineHeight = 26.sp), cursorBrush = SolidColor(PaletteTheme.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = !wide || keyboardRequested),
                    keyboardActions = KeyboardActions(onSearch = { if (state.search.composition == null) action(currentActions().firstOrNull()) }),
                    modifier = Modifier.weight(1f).heightIn(min=48.dp).paletteFocusBookmark(focus).onFocusChanged { searchFocused = it.isFocused }.testTag("extension-search"),
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth().heightIn(min=48.dp), contentAlignment = Alignment.CenterStart) { if (state.search.text.isEmpty()) Text(props.optString("searchBarPlaceholder", "Search extension…"), color = PaletteTheme.secondary, fontSize = 20.sp, lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
                if (wide) IconButton(onClick = { keyboardRequested = true; focus.requestFocus(); keyboard?.show() }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Show on-screen keyboard" }.testTag("extension-grid-keyboard")) { Glyph("keyboard", PaletteTheme.secondary, 16) }
                if (options.isNotEmpty()) Box {
                    TextButton(onClick = { dropdownMenu = true }, modifier = Modifier.testTag("extension-grid-filter")) {
                        Text(options.firstOrNull { it.props().optString("value") == state.dropdownValue }?.props()?.optString("title") ?: "Filter", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if(wide) 140.dp else 72.dp))
                        Text(" ▾", fontSize = 10.sp)
                    }
                    PaletteChoiceMenu(dropdownMenu, { dropdownMenu = false }, options.mapIndexed { index, option ->
                        val value = option.props().optString("value")
                        PaletteChoice("extension-grid-filter-value-$value", option.props().optString("title"), checked = state.dropdownValue == value, tag = "extension-grid-filter-$index") {
                            state.dropdownValue = value; state.selectLocally(null)
                            if (dropdown?.props()?.optBoolean("storeValue") == true && dropdownKey.length <= 512 && value.length <= 4096) preferences.edit().putString(dropdownKey, value).apply()
                            dispatch(dropdown?.handle("onChange"), JSONArray().put(value)); dropdownMenu = false
                        }
                    }, title = "Filter", returnFocus = focus)
                }
                IconButton(onClick = configure, modifier = Modifier.size(48.dp).semantics { contentDescription = "Extension preferences" }) { Glyph("settings",PaletteTheme.secondary,20) }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) { if (props.optBoolean("isLoading")) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("extension-grid-loading")) }
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${items.size} ${if (items.size == 1) "result" else "results"}", fontSize = 11.sp, color = PaletteTheme.secondary, modifier = Modifier.weight(1f).testTag("extension-grid-count"))
                val pagination = props.optJSONObject("pagination")
                if (pagination?.optBoolean("hasMore") == true) TextButton(onClick = { dispatch(pagination.optJSONObject("onLoadMore")?.optString("\$fn"), JSONArray()) }, enabled = !props.optBoolean("isLoading"), modifier = Modifier.testTag("extension-grid-more")) { Text("Load more", fontSize = 11.sp) }
                if (wide) Text(if (editingQuery) "Editing query · ↓ select" else "Type to search · arrows select · Ctrl L edit", color = PaletteTheme.secondary, fontSize = 10.sp)
            }
            if (items.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().testTag("extension-grid-empty"), contentAlignment = Alignment.Center) {
                val empty = root.nodes("Grid.EmptyView").firstOrNull()?.props()
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(empty?.optString("title")?.takeIf { it.isNotBlank() } ?: if (props.optBoolean("isLoading")) "Loading…" else "No results", color = PaletteTheme.ink, fontSize = 16.sp)
                    empty?.optString("description")?.takeIf { it.isNotBlank() }?.let { Text(it, color = PaletteTheme.secondary, fontSize = 12.sp) }
                }
            } else LazyVerticalGrid(GridCells.Fixed(columns), state = state.scroll, modifier = Modifier.weight(1f).fillMaxWidth().onFocusChanged { gridFocused = it.hasFocus }.focusable().onSizeChanged { gridHeight = it.height }.testTag("extension-grid"),
                contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                slots.forEach { slot ->
                    when (slot) {
                    is GridSlot.Boundary -> {
                        val section = sections[slot.sectionIndex]
                        item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }) {
                            if (slot.heading) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(section.title, color = PaletteTheme.ink, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(section.subtitle, color = PaletteTheme.secondary, fontSize = 11.sp)
                            } else Spacer(Modifier.fillMaxWidth().height(0.dp))
                        }
                    }
                    is GridSlot.Cell -> {
                        val itemIndex = slot.itemIndex
                        val entry = items[itemIndex]
                        item(key = "cell-${entry.key}") {
                            GridCell(entry, itemIndex, selected?.key == entry.key, props, images,
                                minOf(((widthDp - 32 - (columns - 1) * 10) / columns - 16) / gridAspectRatio(props.optString("aspectRatio")),
                                    if (gridHeight > 0) (with(density) { gridHeight.toDp() } - cellOverhead).value.coerceAtLeast(0f) else Float.MAX_VALUE).dp) { state.selectLocally(entry.key); focus.requestFocus() }
                        }
                    }
                    }
                }
            }
            HorizontalDivider(color = PaletteTheme.separator)
            dev.tinycast.android.PaletteFooter(metadata={ modifier ->
                Column(modifier) {
                    Text(title,color=PaletteTheme.secondary,fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(status.ifBlank { selected?.title.orEmpty() },color=PaletteTheme.tertiary,fontSize=11.sp,lineHeight=15.sp,
                        maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("extension-grid-selected"))
                }
            }) {
                Box {
                    dev.tinycast.android.PaletteActionCapsule(actions.firstOrNull()?.props()?.optString("title", "Run action") ?: "Run action",
                        { action(currentActions().firstOrNull()) }, { menu=true }, "extension-primary", "extension-actions", compact=!wide, enabled=actions.isNotEmpty())
                    PaletteChoiceMenu(menu, { menu = false }, actions.mapIndexed { index, node ->
                        PaletteChoice("extension-action-${node.optString("id",index.toString())}", node.props().optString("title", "Action"), tag = "extension-action-$index",shortcut=if(index==0)listOf("↵")else emptyList(),icon="command") { action(node) }
                    }, title = selected?.title ?: "Actions", returnFocus = focus, searchable = true)
                }
            }
        }
    }
}

@Composable
private fun GridCell(entry: ExtensionGridItem, index: Int, selected: Boolean, props: JSONObject, images: ExtensionGridImages, visualHeight: Dp, select: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(if (selected) PaletteTheme.accent.copy(alpha = .14f) else PaletteTheme.control)
        .border(if (selected) 2.dp else 1.dp, if (selected) PaletteTheme.accent else PaletteTheme.separator, shape)
        .focusProperties { canFocus = false }.clickable(onClick = select).semantics(mergeDescendants = true) { this.selected = selected; contentDescription = entry.title.ifBlank { "Grid item ${index + 1}" } }
        .padding(8.dp).testTag("extension-grid-cell-$index")) {
        val visual = gridVisual(entry.node.props().opt("content"))
        val inset = (when (props.optString("inset")) { "zero" -> 0; "sm" -> 4; "lg" -> 20; else -> 10 }).coerceAtMost((visualHeight.value / 10).toInt())
        BoxWithConstraints(Modifier.fillMaxWidth().height(visualHeight).padding(inset.dp), contentAlignment = Alignment.Center) {
            if (visualHeight >= 16.dp) when (visual) {
                is GridVisual.Symbol -> {
                    // The symbol is a thumbnail: fit its ink to the preview,
                    // while the caption retains the user's scaled text size.
                    val font = with(LocalDensity.current) { minOf(38.sp.toPx(),minOf(maxWidth,maxHeight).toPx()*.8f).toSp() }
                    Text(visual.value, fontSize = font, lineHeight = font, color = PaletteTheme.ink)
                }
                is GridVisual.Swatch -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(gridColor(visual.value)).semantics { contentDescription = "Color ${visual.value}" })
                is GridVisual.Image -> {
                    val loaded by key(visual.source) { produceState<Result<android.graphics.Bitmap>?>(null, images, visual.source) {
                        value = try { Result.success(images.load(visual.source)) } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { Result.failure(error) }
                    } }
                    val bitmap = loaded?.getOrNull()
                    if (bitmap != null) BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val bounds = if (visual.mask == "circle") Modifier.size(minOf(maxWidth, maxHeight)) else Modifier.fillMaxSize()
                        Image(bitmap.asImageBitmap(), null,
                            modifier = bounds.clip(if (visual.mask == "circle") CircleShape else RoundedCornerShape(if (visual.mask == "roundedRectangle") 10.dp else 0.dp)).testTag("extension-grid-image-$index"),
                            contentScale = if (props.optString("fit") == "fill") ContentScale.Crop else ContentScale.Fit)
                    } else if (loaded == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Image unavailable", color = PaletteTheme.secondary, fontSize = 10.sp, modifier = Modifier.testTag("extension-grid-image-error-$index"))
                }
                GridVisual.Unsupported -> Text("No preview", color = PaletteTheme.secondary, fontSize = 10.sp)
            }
        }
        if (entry.title.isNotBlank()) Text(entry.title, color = PaletteTheme.ink, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (entry.subtitle.isNotBlank()) Text(entry.subtitle, color = PaletteTheme.secondary, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun gridColor(value: String): Color = when (value) {
    "raycast-blue" -> Color(0xff3b82f6); "raycast-green" -> Color(0xff22c55e); "raycast-red" -> Color(0xffef4444)
    "raycast-yellow" -> Color(0xffeab308); "raycast-orange" -> Color(0xfff97316); "raycast-purple" -> Color(0xffa855f7)
    "raycast-magenta" -> Color(0xffd946ef); "raycast-primary-text" -> PaletteTheme.ink; "raycast-secondary-text" -> PaletteTheme.secondary
    else -> runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(PaletteTheme.control)
}
