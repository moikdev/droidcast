package dev.tinycast.android.extensions

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteTheme
import dev.tinycast.android.paletteHeaderSurface
import org.json.JSONArray
import org.json.JSONObject

/** Detail mode uses two useful panes or an explicit, reversible touch destination. */
@Composable
internal fun NativeExtensionList(root: JSONObject, rows: List<JSONObject>, state: ExtensionListState, ownerKey: Any,
    liveRoot: () -> JSONObject?, liveRows: () -> List<JSONObject>, select: (JSONObject) -> Boolean,
    dispatch: (String, JSONArray) -> Unit, status: (String) -> Unit,
    empty: @Composable (Modifier) -> Unit, searchHeader: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val selected = rows.getOrNull(state.selected.coerceIn(0, rows.lastIndex.coerceAtLeast(0)))
    val enabled = root.props().opt("isShowingDetail") == true
    LaunchedEffect(enabled) { if (!enabled) state.detailRowId = null }
    DisposableEffect(state) { onDispose { state.detailOnly = false } }
    BoxWithConstraints(modifier) {
        // At larger text sizes prefer a full-width destination to cramped columns.
        val panes = enabled && maxWidth >= maxOf(640.dp, 560.dp * LocalDensity.current.fontScale)
        val detailOnly = enabled && !panes && selected != null && state.detailRowId == selected.optString("id")
        SideEffect { state.detailOnly = detailOnly }
        val source = selected?.props()?.optJSONObject("detail")
        val currentDetail: () -> JSONObject? = current@{
            liveRoot()?.takeIf { it.optString("type") == "List" && it.optString("id") == root.optString("id") &&
                it.props().opt("isShowingDetail") == true } ?: return@current null
            val currentRows = liveRows()
            val row = currentRows.getOrNull(state.selected.coerceIn(0, currentRows.lastIndex.coerceAtLeast(0))) ?: return@current null
            if (row.optString("id") != selected?.optString("id") || (!panes && state.detailRowId != row.optString("id"))) return@current null
            row.props().optJSONObject("detail")
        }
        val detail: @Composable (Modifier) -> Unit = { detailModifier ->
            if (source == null) Box(detailModifier.padding(20.dp), contentAlignment = Alignment.Center) {
                Text("No detail content", color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.testTag("extension-detail-empty"))
            } else key(selected?.optString("id")) {
                NativeExtensionDetail(source, currentDetail, ownerKey, dispatch, status,
                    state.details.getOrPut(selected?.optString("id").orEmpty()) { ScrollState(0) }, detailModifier)
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (detailOnly) {
                Row(Modifier.fillMaxWidth().paletteHeaderSurface().padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { state.detailRowId = null; state.detailOnly = false },
                        modifier = Modifier.size(48.dp).testTag("extension-detail-back").semantics { contentDescription = "Back to results" }) {
                        Glyph("back", PaletteTheme.ink, 24)
                    }
                    Text(selected?.props()?.optString("title").orEmpty(), fontSize = 18.sp, lineHeight = 26.sp,
                        modifier = Modifier.weight(1f).semantics { heading() })
                }
                HorizontalDivider(color = PaletteTheme.separator)
            } else searchHeader()
            if (root.props().optBoolean("isLoading")) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (detailOnly) detail(Modifier.weight(1f).fillMaxWidth()) else Row(Modifier.weight(1f).fillMaxWidth()) {
                val listModifier = Modifier.weight(if (panes) .42f else 1f).fillMaxHeight().testTag("extension-list")
                if (rows.isEmpty()) empty(listModifier) else LazyColumn(state = state.scroll, modifier = listModifier,
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.optString("id") }) { index, row ->
                        val rowSelected = row === selected
                        val hasDetail = enabled && row.props().optJSONObject("detail") != null
                        key(ownerKey, root.optString("id"), row.props().opt("title")) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
                                .background(if (rowSelected) PaletteTheme.ink.copy(alpha = .12f) else PaletteTheme.control)
                                .semantics { this.selected = rowSelected }
                                .clickable {
                                    if (select(row) && hasDetail && !panes) {
                                        state.detailRowId = row.optString("id")
                                        focus.clearFocus(); keyboard?.hide()
                                    }
                                }.padding(14.dp).testTag("extension-row-$index")) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.props().optString("title"), fontSize = 14.sp, lineHeight = 21.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (hasDetail && !panes) Glyph("arrow", PaletteTheme.secondary, 16)
                                }
                                val subtitle = row.props().optString("subtitle")
                                if (subtitle.isNotBlank()) Text(subtitle, color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp)
                                row.props().optJSONArray("accessories").objects().forEach { accessory ->
                                    val text = accessory.optString("text")
                                    if (text.isNotEmpty()) Text(text, color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                }
                if (panes) {
                    VerticalDivider(Modifier.fillMaxHeight(), color = PaletteTheme.separator)
                    detail(Modifier.weight(.58f).fillMaxHeight().testTag("extension-list-detail-pane"))
                }
            }
        }
    }
}
