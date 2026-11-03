package dev.tinycast.android.extensions

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tinycast.android.PaletteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private data class ParsedMarkdown(val source: String, val document: MarkdownDocument)

/** Parsing is cancellable and off Main; a preceding document is never shown for a new source. */
@Composable
internal fun NativeMarkdownContent(source: String, onLink: (MarkdownLink) -> Unit,
    image: @Composable (MarkdownInline.Picture, Modifier, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val result by produceState<ParsedMarkdown?>(null, source) {
        value = withContext(Dispatchers.Default) {
            val work = currentCoroutineContext()
            ParsedMarkdown(source, ExtensionMarkdown.parse(source) { work.ensureActive() })
        }
    }
    val current = result?.takeIf { it.source == source }?.document
    Column(modifier.fillMaxWidth().testTag("extension-markdown"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            source.isEmpty() -> Unit
            current == null -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("extension-markdown-loading"))
            current.problem != null -> Text("This document is too large to preview.", color = PaletteTheme.secondary,
                fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("extension-markdown-unavailable"))
            else -> key(source) { MarkdownBlocks(current.blocks, onLink, image, 0) }
        }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MarkdownBlock>, onLink: (MarkdownLink) -> Unit,
    image: @Composable (MarkdownInline.Picture, Modifier, Boolean) -> Unit, depth: Int) {
    blocks.forEachIndexed { index, block -> key(index) {
        when (block) {
            is MarkdownBlock.Paragraph -> MarkdownInlineText(block.content, onLink, image)
            is MarkdownBlock.Title -> MarkdownInlineText(block.content, onLink, image,
                Modifier.semantics { heading() }, size = when (block.level) { 1 -> 26; 2 -> 22; 3 -> 19; else -> 16 },
                weight = FontWeight.SemiBold)
            is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().background(PaletteTheme.ink.copy(alpha = .06f), RoundedCornerShape(8.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (block.language.isNotBlank()) Text(block.language, color = PaletteTheme.secondary, fontSize = 11.sp, lineHeight = 16.sp)
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Text(block.value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, softWrap = false,
                        color = PaletteTheme.ink, modifier = Modifier.testTag("extension-markdown-code"))
                }
            }
            is MarkdownBlock.Quote -> {
                val content: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MarkdownBlocks(block.content, onLink, image, depth + 1)
                    }
                }
                if (depth < 2) Box(Modifier.fillMaxWidth().drawBehind {
                    drawLine(PaletteTheme.ink.copy(alpha = .25f), Offset(1.5.dp.toPx(), 0f),
                        Offset(1.5.dp.toPx(), size.height), 3.dp.toPx())
                }.padding(start = 13.dp)) { content() } else content()
            }
            is MarkdownBlock.Items -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.items.forEachIndexed { itemIndex, item ->
                    val marker = if (block.ordered) "${block.start.toLong() + itemIndex}." else "•"
                    if (depth < 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(marker, color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 23.sp,
                            modifier = Modifier.widthIn(min = 20.dp, max = 54.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { MarkdownBlocks(item, onLink, image, depth + 1) }
                    } else {
                        val first = item.firstOrNull()
                        if (first is MarkdownBlock.Paragraph) {
                            MarkdownInlineText(listOf(MarkdownInline.Words("$marker ", MarkdownMarks(), null)) + first.content, onLink, image)
                            MarkdownBlocks(item.drop(1), onLink, image, depth + 1)
                        } else {
                            Text(marker, color = PaletteTheme.secondary, fontSize = 14.sp, lineHeight = 23.sp)
                            MarkdownBlocks(item, onLink, image, depth + 1)
                        }
                    }
                }
            }
            is MarkdownBlock.Table -> MarkdownTable(block, onLink, image)
            MarkdownBlock.Divider -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = PaletteTheme.ink.copy(alpha = .15f))
        }
    } }
}

@Composable
private fun MarkdownInlineText(content: List<MarkdownInline>, onLink: (MarkdownLink) -> Unit,
    image: @Composable (MarkdownInline.Picture, Modifier, Boolean) -> Unit, modifier: Modifier = Modifier,
    size: Int = 14, weight: FontWeight = FontWeight.Normal, alignment: TextAlign = TextAlign.Start,
    allowBlockImages: Boolean = true) {
    // A standalone illustration gets the full document width. Inline illustrations
    // retain a native text placeholder and their accessibility alternative.
    if (allowBlockImages && content.isNotEmpty() && content.all { it is MarkdownInline.Picture }) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content.filterIsInstance<MarkdownInline.Picture>().forEach { image(it, Modifier.fillMaxWidth(), false) }
        }
        return
    }
    val inlineContent = mutableMapOf<String, androidx.compose.foundation.text.InlineTextContent>()
    val density = LocalDensity.current
    val text = buildAnnotatedString {
        content.forEachIndexed { index, part ->
            val link = when (part) { is MarkdownInline.Words -> part.link; is MarkdownInline.Picture -> part.link }
            val linkAnnotation = link?.let { destination ->
                LinkAnnotation.Clickable("markdown-link-$index", TextLinkStyles(SpanStyle(color = PaletteTheme.ink,
                    textDecoration = TextDecoration.Underline)), LinkInteractionListener { onLink(destination) })
            }
            if (linkAnnotation != null) pushLink(linkAnnotation)
            when (part) {
                is MarkdownInline.Words -> {
                    val marks = part.marks
                    withStyle(SpanStyle(fontWeight = if (marks.bold) FontWeight.SemiBold else null,
                        fontStyle = if (marks.italic) FontStyle.Italic else null,
                        textDecoration = if (marks.strike && link != null) TextDecoration.combine(listOf(TextDecoration.LineThrough, TextDecoration.Underline))
                            else if (marks.strike) TextDecoration.LineThrough else null,
                        fontFamily = if (marks.code) FontFamily.Monospace else null,
                        background = if (marks.code) PaletteTheme.ink.copy(alpha = .08f) else Color.Unspecified)) { append(part.value) }
                }
                is MarkdownInline.Picture -> {
                    val key = "markdown-inline-image-$index"
                    val side = with(density) { 24.dp.toSp() }
                    inlineContent[key] = androidx.compose.foundation.text.InlineTextContent(
                        Placeholder(side, side, PlaceholderVerticalAlign.TextCenter)) {
                        // The surrounding annotation owns a linked inline image's activation.
                        image(part.copy(link = null), Modifier.fillMaxSize(), true)
                    }
                    appendInlineContent(key, part.alternative.ifBlank { "Image" })
                }
            }
            if (linkAnnotation != null) pop()
        }
    }
    Text(text, modifier.fillMaxWidth(), color = PaletteTheme.ink, fontSize = size.sp,
        lineHeight = (size * 1.55f).sp, fontWeight = weight, textAlign = alignment, inlineContent = inlineContent)
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table, onLink: (MarkdownLink) -> Unit,
    image: @Composable (MarkdownInline.Picture, Modifier, Boolean) -> Unit) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(140.dp * LocalDensity.current.fontScale, maxWidth / columns)
        Column(Modifier.horizontalScroll(rememberScrollState()).testTag("extension-markdown-table")) {
            (listOf(table.header) + table.rows).forEachIndexed { rowIndex, cells ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    repeat(columns) { column ->
                        val cell = cells.getOrNull(column)
                        Box(Modifier.width(width).fillMaxHeight().border(1.dp, PaletteTheme.ink.copy(alpha = .15f)).padding(10.dp)) {
                            MarkdownInlineText(cell?.content.orEmpty(), onLink, image, weight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                                alignment = when (cell?.alignment) { MarkdownAlignment.CENTER -> TextAlign.Center; MarkdownAlignment.END -> TextAlign.End; else -> TextAlign.Start },
                                // Table rows ask for intrinsic text height. Keep images in
                                // fixed text placeholders, outside subcomposed block layout.
                                allowBlockImages = false)
                        }
                    }
                }
            }
        }
    }
}
