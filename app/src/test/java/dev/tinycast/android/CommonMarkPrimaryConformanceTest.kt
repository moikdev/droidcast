package dev.tinycast.android.extensions

import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node as HtmlNode
import java.io.StringReader
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Primary grammar data, independently projected into the native display model.
 * Raw HTML is deliberately literal on Android; GFM extensions are separately tested.
 * Neither these JVM comparisons nor parser conformance certify Android display.
 */
class CommonMarkPrimaryConformanceTest {
    private data class Example(val id: Int, val section: String, val markdown: String, val html: String)
    private val examples: List<Example> by lazy {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/commonmark-0.31.2.tsv")).use { it.readBytes() }
        assertEquals("dc27be954caac0ed8961299ca581a62aa4c68312091e68e52ab1e2bfe6aa5f5f", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        bytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).map { line ->
            val parts = line.split('\t'); require(parts.size == 4)
            Example(parts[0].toInt(), decode(parts[1]), decode(parts[2]), decode(parts[3]))
        }.toList().also { assertEquals((1..652).toList(), it.map(Example::id)) }
    }
    private val parser = Parser.builder().maxOpenBlockParsers(64).maxInlineNesting(64).build()
    @Test fun coreGrammarMatchesAll652PrimaryExamples() {
        val renderer = HtmlRenderer.builder().percentEncodeUrls(true).build()
        examples.forEach { assertEquals("CommonMark ${it.id}: ${it.section}", it.html, renderer.render(parser.parse(it.markdown))) }
        println("Primary CommonMark0.31.2 HTML comparisons:652; no Android/display certification")
    }
    @Test fun nativeModelMatchesPrimaryTextStylesLinksAndBlocksOutsideLiteralHtmlAndGfm() {
        val literalHtml = mutableListOf<Int>(); val extended = mutableListOf<Int>(); var compared = 0
        val mismatches = mutableListOf<String>()
        examples.forEach { example ->
            val document = ExtensionMarkdown.parse(example.markdown); assertNull("Native limit at ${example.id}", document.problem)
            val pending = ArrayDeque<org.commonmark.node.Node>(); pending.addLast(parser.parse(example.markdown)); var html = false
            while (pending.isNotEmpty()) {
                val node = pending.removeLast(); if (node is HtmlBlock || node is HtmlInline) html = true
                var child = node.firstChild; while (child != null) { pending.addLast(child); child = child.next }
            }
            if (html) literalHtml += example.id
            else if (isExtended(document.blocks)) extended += example.id
            else {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                val root = factory.newDocumentBuilder().parse(InputSource(StringReader("<root>${htmlAttributesForXml(example.html)}</root>"))).documentElement
                val expected = canonical(blocks(children(root))); val actual = canonical(document.blocks)
                if (expected != actual) mismatches += "Example ${example.id}: ${example.section}\nExpected:$expected\nActual:$actual"
                compared++
            }
        }
        assertTrue("Primary native projection must cover the core grammar broadly", compared >= 500)
        assertEquals(652, compared + literalHtml.size + extended.size)
        println("Native primary semantic comparisons:$compared; mismatches=${mismatches.size}; literal-HTML cases=${literalHtml.joinToString(",")}; GFM-different cases=${extended.joinToString(",")}")
        assertTrue(mismatches.joinToString("\n\n"), mismatches.isEmpty())
    }
    private fun children(node: HtmlNode) = (0 until node.childNodes.length).map { node.childNodes.item(it) }
    private fun htmlAttributesForXml(html: String): String = buildString {
        // XML attribute normalization would otherwise erase the primary HTML's
        // actual multiline link/image titles. Character references preserve them.
        var tag = false; var quote: Char? = null
        for (character in html) {
            if (quote != null) {
                append(when (character) { '\n' -> "&#10;"; '\r' -> "&#13;"; '\t' -> "&#9;"; else -> character.toString() })
                if (character == quote) quote = null
            } else {
                append(character)
                if (tag && character in listOf('\'', '"')) quote = character
                else if (character == '<') tag = true
                else if (character == '>') tag = false
            }
        }
    }
    private fun blocks(nodes: List<HtmlNode>): List<MarkdownBlock> = buildList {
        val run = mutableListOf<HtmlNode>()
        fun flush() {
            val text = inlines(run, trimEdges = true)
            if (text.isNotEmpty()) add(MarkdownBlock.Paragraph(text))
            run.clear()
        }
        for (node in nodes) {
            val element = node as? Element
            val name = element?.tagName
            if (name !in setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "ul", "ol", "hr")) {
                if (element != null || node.textContent.isNotBlank() || run.isNotEmpty()) run += node
                continue
            }
            flush(); requireNotNull(element)
            when (name) {
                "p" -> add(MarkdownBlock.Paragraph(inlines(children(element))))
                "h1", "h2", "h3", "h4", "h5", "h6" -> add(MarkdownBlock.Title(name.last().digitToInt(), inlines(children(element))))
                "pre" -> {
                    val code = children(element).filterIsInstance<Element>().single()
                    add(MarkdownBlock.Code(code.textContent, code.getAttribute("class").removePrefix("language-")))
                }
                "blockquote" -> add(MarkdownBlock.Quote(blocks(children(element))))
                "ul", "ol" -> add(MarkdownBlock.Items(name == "ol", element.getAttribute("start").toIntOrNull() ?: 1,
                    children(element).filterIsInstance<Element>().map { blocks(children(it)) }))
                "hr" -> add(MarkdownBlock.Divider)
            }
        }
        flush()
    }
    private fun inlines(nodes: List<HtmlNode>, trimEdges: Boolean = false): List<MarkdownInline> = buildList {
        var afterBreak = false
        fun visit(node: HtmlNode, marks: MarkdownMarks, link: MarkdownLink?, overrideText: String? = null) {
            val element = node as? Element
            if (element == null) {
                var text = overrideText ?: node.nodeValue.orEmpty()
                if (afterBreak) { text = text.removePrefix("\n"); afterBreak = false }
                if (text.isNotEmpty()) add(MarkdownInline.Words(text.replace('\n', ' '), marks, link))
                return
            }
            when (element.tagName) {
                "br" -> { add(MarkdownInline.Words("\n", marks, link)); afterBreak = true }
                "img" -> add(MarkdownInline.Picture(element.getAttribute("src"), element.getAttribute("alt"), element.getAttribute("title"), link))
                else -> {
                    val nextMarks = when (element.tagName) { "em" -> marks.copy(italic = true); "strong" -> marks.copy(bold = true); "code" -> marks.copy(code = true); else -> marks }
                    val nextLink = if (element.tagName == "a") MarkdownLink(element.getAttribute("href"), element.getAttribute("title")) else link
                    require(element.tagName in setOf("em", "strong", "code", "a"))
                    children(element).forEach { visit(it, nextMarks, nextLink) }
                }
            }
        }
        nodes.forEachIndexed { index, node ->
            var text: String? = null
            if (trimEdges && node !is Element) {
                text = node.nodeValue.orEmpty()
                if (index == 0) text = text.trimStart('\n')
                if (index == nodes.lastIndex) text = text.trimEnd('\n')
            }
            visit(node, MarkdownMarks(), null, text)
        }
    }
    private fun url(value: String): String = URLDecoder.decode(value.replace("+", "%2B").replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"), "UTF-8")
    private fun link(value: MarkdownLink?) = value?.copy(destination = url(value.destination), title = value.title.orEmpty())
    private fun canonicalInline(values: List<MarkdownInline>): List<MarkdownInline> = buildList {
        values.forEach { value ->
            val current = when (value) {
                is MarkdownInline.Picture -> value.copy(source = url(value.source), title = value.title.orEmpty(), link = link(value.link))
                is MarkdownInline.Words -> value.copy(link = link(value.link))
            }
            val previous = lastOrNull()
            if (current is MarkdownInline.Words && previous is MarkdownInline.Words && current.marks == previous.marks && current.link == previous.link) {
                removeAt(lastIndex); add(previous.copy(value = previous.value + current.value))
            } else if (current !is MarkdownInline.Words || current.value.isNotEmpty()) add(current)
        }
    }
    private fun canonical(values: List<MarkdownBlock>): List<MarkdownBlock> = values.map { when (it) {
        is MarkdownBlock.Paragraph -> it.copy(content = canonicalInline(it.content))
        is MarkdownBlock.Title -> it.copy(content = canonicalInline(it.content))
        is MarkdownBlock.Quote -> it.copy(content = canonical(it.content))
        is MarkdownBlock.Items -> it.copy(items = it.items.map(::canonical))
        else -> it
    } }
    private fun isExtended(values: List<MarkdownBlock>): Boolean = values.any { when (it) {
        is MarkdownBlock.Table -> true
        is MarkdownBlock.Quote -> isExtended(it.content)
        is MarkdownBlock.Items -> it.items.any(::isExtended)
        is MarkdownBlock.Paragraph -> it.content.any { part -> part is MarkdownInline.Words && part.marks.strike }
        is MarkdownBlock.Title -> it.content.any { part -> part is MarkdownInline.Words && part.marks.strike }
        else -> false
    } }
}
