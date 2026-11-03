package dev.tinycast.android.extensions

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class MarkdownMarks(val bold: Boolean = false, val italic: Boolean = false,
    val strike: Boolean = false, val code: Boolean = false)
internal data class MarkdownLink(val destination: String, val title: String?)
internal sealed interface MarkdownInline {
    data class Words(val value: String, val marks: MarkdownMarks, val link: MarkdownLink?) : MarkdownInline
    data class Picture(val source: String, val alternative: String, val title: String?, val link: MarkdownLink?) : MarkdownInline
}
internal enum class MarkdownAlignment { START, CENTER, END }
internal data class MarkdownCell(val content: List<MarkdownInline>, val alignment: MarkdownAlignment)
internal sealed interface MarkdownBlock {
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class Title(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Code(val value: String, val language: String) : MarkdownBlock
    data class Quote(val content: List<MarkdownBlock>) : MarkdownBlock
    data class Items(val ordered: Boolean, val start: Int, val items: List<List<MarkdownBlock>>) : MarkdownBlock
    data class Table(val header: List<MarkdownCell>, val rows: List<List<MarkdownCell>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}
internal enum class MarkdownProblem { SOURCE_LIMIT, NODE_LIMIT }
internal data class MarkdownDocument(val blocks: List<MarkdownBlock>, val nodeCount: Int,
    val problem: MarkdownProblem? = null)

/** An immutable native display model. HTML and link targets stay data, never executable markup. */
internal object ExtensionMarkdown {
    const val MAX_SOURCE_UNITS = 512 * 1024
    const val MAX_NODES = 16 * 1024
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .maxOpenBlockParsers(64).maxInlineNesting(64).build()

    fun parse(source: String, cancellationCheck: () -> Unit = {}): MarkdownDocument {
        cancellationCheck()
        if (source.length > MAX_SOURCE_UNITS) return MarkdownDocument(emptyList(), 0, MarkdownProblem.SOURCE_LIMIT)
        val root = parser.parse(source)
        cancellationCheck()
        val pending = ArrayDeque<Node>(); pending.addLast(root)
        var count = 0
        while (pending.isNotEmpty()) {
            if (++count > MAX_NODES) return MarkdownDocument(emptyList(), count, MarkdownProblem.NODE_LIMIT)
            if (count % 256 == 0) cancellationCheck()
            val node = pending.removeLast()
            var child = node.firstChild
            while (child != null) { pending.addLast(child); child = child.next }
        }
        return MarkdownDocument(blocks(root, cancellationCheck), count)
    }

    private fun children(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) { add(child); child = child.next }
    }
    private fun blocks(parent: Node, check: () -> Unit): List<MarkdownBlock> = buildList {
        for (node in children(parent)) {
            check()
            when (node) {
                is Paragraph -> add(MarkdownBlock.Paragraph(inlines(node, check)))
                is Heading -> add(MarkdownBlock.Title(node.level, inlines(node, check)))
                is FencedCodeBlock -> add(MarkdownBlock.Code(node.literal, node.info.orEmpty().trim().substringBefore(' ')))
                is IndentedCodeBlock -> add(MarkdownBlock.Code(node.literal, ""))
                // Literal HTML source is selectable native code; it is never sent to a WebView.
                is HtmlBlock -> add(MarkdownBlock.Code(node.literal, "html"))
                is BlockQuote -> add(MarkdownBlock.Quote(blocks(node, check)))
                is BulletList -> add(MarkdownBlock.Items(false, 1, children(node).map { blocks(it, check) }))
                is OrderedList -> add(MarkdownBlock.Items(true, node.markerStartNumber ?: 1, children(node).map { blocks(it, check) }))
                is ThematicBreak -> add(MarkdownBlock.Divider)
                is TableBlock -> {
                    val header = children(node).filterIsInstance<TableHead>().flatMap(::children).filterIsInstance<TableRow>().firstOrNull()
                    val rows = children(node).filterNot { it is TableHead }.flatMap(::children).filterIsInstance<TableRow>()
                    fun cells(row: TableRow): List<MarkdownCell> = children(row).filterIsInstance<TableCell>().map { cell ->
                        MarkdownCell(inlines(cell, check), when (cell.alignment) {
                            TableCell.Alignment.CENTER -> MarkdownAlignment.CENTER
                            TableCell.Alignment.RIGHT -> MarkdownAlignment.END
                            else -> MarkdownAlignment.START
                        })
                    }
                    add(MarkdownBlock.Table(header?.let(::cells).orEmpty(), rows.map(::cells)))
                }
                else -> addAll(blocks(node, check))
            }
        }
    }
    private fun inlines(parent: Node, check: () -> Unit, marks: MarkdownMarks = MarkdownMarks(), link: MarkdownLink? = null): List<MarkdownInline> = buildList {
        for (node in children(parent)) {
            check()
            when (node) {
                is Text -> add(MarkdownInline.Words(node.literal, marks, link))
                is Code -> add(MarkdownInline.Words(node.literal, marks.copy(code = true), link))
                is SoftLineBreak -> add(MarkdownInline.Words(" ", marks, link))
                is HardLineBreak -> add(MarkdownInline.Words("\n", marks, link))
                is HtmlInline -> add(MarkdownInline.Words(node.literal, marks.copy(code = true), link))
                is Emphasis -> addAll(inlines(node, check, marks.copy(italic = true), link))
                is StrongEmphasis -> addAll(inlines(node, check, marks.copy(bold = true), link))
                is Strikethrough -> addAll(inlines(node, check, marks.copy(strike = true), link))
                is Link -> addAll(inlines(node, check, marks, MarkdownLink(node.destination, node.title)))
                is Image -> {
                    val alternative = inlines(node, check).joinToString("") {
                        when (it) { is MarkdownInline.Words -> it.value; is MarkdownInline.Picture -> it.alternative }
                    }
                    add(MarkdownInline.Picture(node.destination, alternative, node.title, link))
                }
                else -> addAll(inlines(node, check, marks, link))
            }
        }
    }
}
