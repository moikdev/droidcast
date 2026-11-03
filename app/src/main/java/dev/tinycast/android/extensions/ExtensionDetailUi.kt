package dev.tinycast.android.extensions

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.Glyph
import dev.tinycast.android.PaletteTheme
import org.json.JSONArray
import org.json.JSONObject

private data class MetadataDocument(val rows: List<JSONObject>, val all: List<JSONObject>, val limited: Boolean = false)
private data class MetadataPending(val value: Any?, val inTags: Boolean, val depth: Int)
private class DetailAttachment { var active = false }
private fun metadataKind(node: JSONObject) = node.optString("type").substringAfter(".Metadata.", "")
private fun metadataDocument(value: Any?): MetadataDocument {
    val pending = ArrayDeque<MetadataPending>(); pending.addLast(MetadataPending(value, false, 0))
    val rows = mutableListOf<JSONObject>(); val all = mutableListOf<JSONObject>(); var visited = 0
    while (pending.isNotEmpty()) {
        val next = pending.removeLast()
        if (++visited > 2048 || next.depth > 64) return MetadataDocument(emptyList(), emptyList(), true)
        when (val node = next.value) {
            is JSONArray -> for (index in node.length() - 1 downTo 0) pending.addLast(MetadataPending(node.opt(index), next.inTags, next.depth + 1))
            is JSONObject -> {
                all += node
                val kind = metadataKind(node)
                if (!next.inTags && kind in listOf("Label", "Link", "Separator", "TagList")) rows += node
                pending.addLast(MetadataPending(node.opt("children"), next.inTags || kind == "TagList", next.depth + 1))
            }
        }
    }
    return MetadataDocument(rows, all)
}
private fun metadataText(value: Any?): String = when (value) {
    is String -> value
    is JSONObject -> (value.opt("value") as? String).orEmpty()
    else -> ""
}
internal fun metadataTint(value: Any?): Color {
    val name = when (value) { is JSONObject -> (value.opt("dark") as? String) ?: (value.opt("light") as? String).orEmpty(); is String -> value; else -> "" }
    return when (name) {
        "raycast-blue" -> Color(0xff3b82f6); "raycast-green" -> Color(0xff22c55e); "raycast-red" -> Color(0xffef4444)
        "raycast-yellow" -> Color(0xffeab308); "raycast-orange" -> Color(0xfff97316); "raycast-purple" -> Color(0xffa855f7)
        "raycast-magenta" -> Color(0xffd946ef); "raycast-secondary-text" -> PaletteTheme.secondary
        else -> runCatching { Color(android.graphics.Color.parseColor(name)) }.getOrDefault(PaletteTheme.ink)
    }
}

/** The provider must resolve the current root/row/session and visible detail slot, without fallback. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeExtensionDetail(source: JSONObject, liveSource: () -> JSONObject?,
    ownerKey: Any, images: ExtensionDetailImages, dispatch: (String, JSONArray) -> Unit, status: (String) -> Unit, scroll: ScrollState,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sourceId = source.optString("id"); val sourceType = source.optString("type")
    val markdown = (source.props().opt("markdown") as? String).orEmpty()
    val documentIdentity = remember(ownerKey, sourceId, sourceType, markdown) { Any() }
    val attachment = remember(ownerKey, sourceId, sourceType) { DetailAttachment() }
    DisposableEffect(attachment) { attachment.active = true; onDispose { attachment.active = false } }
    val metadata = metadataDocument(source.props().opt("metadata"))
    fun current(): JSONObject? = if (!attachment.active) null else liveSource()?.takeIf { it.optString("id") == sourceId && it.optString("type") == sourceType }
    fun activateLink(target: String) {
        try { openExtensionLink(context, target) }
        catch (failure: IllegalArgumentException) { status(failure.message ?: "This link is unavailable on Android") }
        catch (failure: IllegalStateException) { status(failure.message ?: "Android could not open this link") }
    }
    fun liveMetadata(expected: JSONObject, fields: List<String>): JSONObject? {
        val now = current() ?: return null
        if ((now.props().opt("markdown") as? String).orEmpty() != markdown) return null
        val document = metadataDocument(now.props().opt("metadata")); if (document.limited) return null
        return document.all.filter { candidate ->
            candidate.optString("type") == expected.optString("type") &&
                (expected.optString("id").isBlank() || candidate.optString("id") == expected.optString("id")) &&
                fields.all { field ->
                    if (field == "onAction") candidate.handle(field) == expected.handle(field)
                    else candidate.props().opt(field) == expected.props().opt(field)
                }
        }.singleOrNull()
    }
    val openMarkdown: (MarkdownLink) -> Unit = { link ->
        // A callback from a preceding document may not adopt a new row's URL.
        if (current()?.props()?.opt("markdown") == markdown) activateLink(link.destination)
    }
    val body: @Composable ColumnScope.() -> Unit = {
                if (source.props().optBoolean("isLoading")) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("extension-detail-loading"))
                if (markdown.isNotEmpty()) NativeMarkdownContent(markdown, openMarkdown, image = { picture, imageModifier, inline ->
                    val link = picture.link
                    val click = if (link == null || inline) Modifier else Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) {
                        openMarkdown(link)
                    }
                    NativeDetailImage(picture.source, picture.alternative, images, imageModifier.then(click), inline)
                })
    }
    val information: @Composable ColumnScope.() -> Unit = {
                if (metadata.limited) Text("This information is too large to preview.", color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 22.sp)
                if (metadata.rows.isNotEmpty()) Column(Modifier.fillMaxWidth().testTag("extension-detail-metadata"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    metadata.rows.forEach { node ->
                        val props = node.props(); val title = (props.opt("title") as? String).orEmpty()
                        key(documentIdentity, node.optString("id"), node.optString("type"), title, metadataText(props.opt("text")), props.opt("target")) {
                        when (metadataKind(node)) {
                            "Separator" -> HorizontalDivider(color = PaletteTheme.ink.copy(alpha = .15f))
                            "Label" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (title.isNotEmpty()) Text(title, color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp)
                                val text = metadataText(props.opt("text"))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    props.opt("icon")?.takeIf { it != JSONObject.NULL }?.let { NativeDetailIcon(it, images) }
                                    if (text.isNotEmpty()) Text(text, color = metadataTint((props.opt("text") as? JSONObject)?.opt("color")), fontSize = 14.sp, lineHeight = 22.sp,
                                        modifier = Modifier.weight(1f))
                                }
                            }
                            "Link" -> TextButton(onClick = {
                                liveMetadata(node, listOf("title", "text", "target"))?.props()?.opt("target")?.let { target ->
                                    if (target is String) activateLink(target)
                                }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { contentDescription = "Open link: ${metadataText(props.opt("text"))}" },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (title.isNotEmpty()) Text(title, color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp)
                                    Text(metadataText(props.opt("text")), color = PaletteTheme.ink, textDecoration = TextDecoration.Underline,
                                        fontSize = 14.sp, lineHeight = 22.sp)
                                }
                            }
                            "TagList" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (title.isNotEmpty()) Text(title, color = PaletteTheme.secondary, fontSize = 12.sp, lineHeight = 18.sp)
                                val tags = metadataDocument(node.opt("children")).all.filter { metadataKind(it) == "TagList.Item" }
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    tags.forEach { tag ->
                                        val text = metadataText(tag.props().opt("text")); val handle = tag.handle("onAction")
                                        val tint = metadataTint(tag.props().opt("color"))
                                        key(tag.optString("id"), text, handle, tag.props().opt("icon"), tag.props().opt("color")) {
                                        val click = if (handle.isNullOrBlank()) Modifier else Modifier.clickable(role = Role.Button) {
                                            val live = liveMetadata(tag, listOf("text", "color", "icon", "onAction"))
                                            if (live?.handle("onAction") == handle) dispatch(handle, JSONArray())
                                        }
                                        Box(Modifier.widthIn(min = 48.dp).heightIn(min = if (handle.isNullOrBlank()) 32.dp else 48.dp)
                                            .clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .15f)).then(click).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                tag.props().opt("icon")?.takeIf { it != JSONObject.NULL }?.let { NativeDetailIcon(it, images) }
                                                Text(text.ifBlank { "Tag" }, color = tint, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
    }
    val metadataScroll = remember(ownerKey, sourceId, sourceType) { ScrollState(0) }
    BoxWithConstraints(modifier.testTag("extension-detail")) {
        val panes = markdown.isNotEmpty() && metadata.rows.isNotEmpty() &&
            maxWidth >= maxOf(680.dp, 600.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale)
        key(ownerKey, sourceId, sourceType) {
            SelectionContainer(Modifier.fillMaxSize()) {
                if (panes) Row(Modifier.fillMaxSize().testTag("extension-detail-panes")) {
                    Column(Modifier.weight(.62f).fillMaxHeight().verticalScroll(scroll).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp), content = body)
                    VerticalDivider(Modifier.fillMaxHeight(), color = PaletteTheme.separator)
                    Column(Modifier.weight(.38f).fillMaxHeight().verticalScroll(metadataScroll).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = information)
                } else Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    body(); information()
                    if (markdown.isEmpty() && metadata.rows.isEmpty() && !metadata.limited) Text(
                        if (source.props().optBoolean("isLoading")) "Loading details…" else "No detail content", color = PaletteTheme.secondary,
                        fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("extension-detail-empty"))
                }
            }
        }
    }
}
