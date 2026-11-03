package dev.tinycast.android

import dev.tinycast.android.extensions.*
import org.junit.Assert.*
import org.junit.Test

class ExtensionMarkdownModelTest {
    private fun paragraph(source: String) = (ExtensionMarkdown.parse(source).blocks.single() as MarkdownBlock.Paragraph).content
    private fun text(content: List<MarkdownInline>) = content.filterIsInstance<MarkdownInline.Words>().joinToString("") { it.value }

    @Test fun headingAndNestedMarksKeepUnicodeAndCombiningText() {
        val document = ExtensionMarkdown.parse("# Owned café é 日本 🌱\n\n**bold _nested_** and *soft*\n")
        val title = document.blocks[0] as MarkdownBlock.Title
        assertEquals(1, title.level); assertEquals("Owned café é 日本 🌱", text(title.content))
        val content = (document.blocks[1] as MarkdownBlock.Paragraph).content
        val nested = content.filterIsInstance<MarkdownInline.Words>().single { it.value == "nested" }
        assertTrue(nested.marks.bold); assertTrue(nested.marks.italic)
        assertEquals("bold nested and soft", text(content)); assertNull(document.problem)
    }
    @Test fun escapesEntitiesAndLineBreaksFollowCommonMark() {
        val content = paragraph("\\*literal\\* &amp; first\nsecond  \nthird\\\nfourth\n")
        assertEquals("*literal* & first second\nthird\nfourth", text(content))
        assertTrue(content.filterIsInstance<MarkdownInline.Words>().none { it.marks.italic })
    }
    @Test fun numericLineFeedsStayTextWhileExplicitBreaksAndCodeKeepTheirLines() {
        assertEquals("foo  bar", text(paragraph("foo&#10;&#10;bar\n")))
        assertEquals("foo\nbar", text(paragraph("foo  \nbar\n")))
        val emphasized = paragraph("**foo&#10;bar**").filterIsInstance<MarkdownInline.Words>().single()
        assertEquals("foo bar", emphasized.value); assertTrue(emphasized.marks.bold)
        assertEquals(MarkdownBlock.Code("foo\n\nbar\n", "text"), ExtensionMarkdown.parse("```text\nfoo\n\nbar\n```\n").blocks.single())
    }
    @Test fun fencedAndIndentedCodeKeepSpacesTabsAndBlankLines() {
        val blocks = ExtensionMarkdown.parse("```kotlin\r\n  val x = \"日本\"\r\n\tline\r\n\r\n```\r\n\n    a\n      b\n").blocks
        assertEquals(MarkdownBlock.Code("  val x = \"日本\"\n\tline\n\n", "kotlin"), blocks[0])
        assertEquals(MarkdownBlock.Code("a\n  b\n", ""), blocks[1])
    }
    @Test fun inlineCodeKeepsItsOwnWhitespaceAndMarks() {
        val code = paragraph("before `a  b\tc` after").filterIsInstance<MarkdownInline.Words>().single { it.marks.code }
        assertEquals("a  b\tc", code.value)
    }
    @Test fun referenceLinksResolveWithoutChangingTargetsOrTitles() {
        val link = paragraph("[Owned][ref]\n\n[ref]: https://example.invalid/a%20b?q=1 \"Title\"\n")
            .filterIsInstance<MarkdownInline.Words>().single()
        assertEquals("Owned", link.value)
        assertEquals(MarkdownLink("https://example.invalid/a%20b?q=1", "Title"), link.link)
    }
    @Test fun linkedImagesPreserveAlternativeAndRenderParameters() {
        val image = paragraph("[![Owned *日本*](asset.png?raycast-width=120&raycast-height=60)](https://example.invalid/owned)")
            .single() as MarkdownInline.Picture
        assertEquals("Owned 日本", image.alternative)
        assertEquals("asset.png?raycast-width=120&raycast-height=60", image.source)
        assertEquals("https://example.invalid/owned", image.link?.destination)
    }
    @Test fun nestedListsPreserveStartingNumberAndParagraphs() {
        val items = ExtensionMarkdown.parse("3. first\n   - nested\n4. second\n").blocks.single() as MarkdownBlock.Items
        assertTrue(items.ordered); assertEquals(3, items.start); assertEquals(2, items.items.size)
        assertEquals("first", text((items.items[0][0] as MarkdownBlock.Paragraph).content))
        val nested = items.items[0][1] as MarkdownBlock.Items
        assertFalse(nested.ordered); assertEquals("nested", text((nested.items.single().single() as MarkdownBlock.Paragraph).content))
        assertEquals(0, (ExtensionMarkdown.parse("0. zero\n").blocks.single() as MarkdownBlock.Items).start)
    }
    @Test fun quotesAndDividersRemainSeparateBlocks() {
        val blocks = ExtensionMarkdown.parse("> first\n>\n> second\n\n---\n").blocks
        assertEquals(2, (blocks[0] as MarkdownBlock.Quote).content.size)
        assertEquals(MarkdownBlock.Divider, blocks[1])
    }
    @Test fun tablesKeepHeaderAlignmentCodeAndCellValues() {
        val table = ExtensionMarkdown.parse("| Key | Value |\n| :--- | ---: |\n| A | `B` |\n").blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("Key", "Value"), table.header.map { text(it.content) })
        assertEquals(listOf(MarkdownAlignment.START, MarkdownAlignment.END), table.header.map { it.alignment })
        assertEquals("B", text(table.rows.single()[1].content))
        assertTrue((table.rows.single()[1].content.single() as MarkdownInline.Words).marks.code)
    }
    @Test fun strikeAndRawHtmlNeverBecomeExecutableMarkup() {
        val document = ExtensionMarkdown.parse("~~removed~~ [bad](javascript:owned)\n\n<script>owned()</script>\n")
        val content = (document.blocks[0] as MarkdownBlock.Paragraph).content.filterIsInstance<MarkdownInline.Words>()
        assertTrue(content.first().marks.strike)
        assertEquals("javascript:owned", content.last().link?.destination)
        // CommonMark's HTML-block literal excludes its terminal line ending.
        assertEquals(MarkdownBlock.Code("<script>owned()</script>", "html"), document.blocks[1])
    }
    @Test fun oversizedSourcesAndNodeCountsDoNotProducePartialDocuments() {
        val source = ExtensionMarkdown.parse("x".repeat(ExtensionMarkdown.MAX_SOURCE_UNITS + 1))
        assertEquals(MarkdownProblem.SOURCE_LIMIT, source.problem); assertTrue(source.blocks.isEmpty())
        val nodes = ExtensionMarkdown.parse("x\n\n".repeat(9000))
        assertEquals(MarkdownProblem.NODE_LIMIT, nodes.problem); assertTrue(nodes.blocks.isEmpty())
    }
    @Test fun cancellationIsObservedBeforeParsing() {
        val expected = IllegalStateException("Owned cancellation")
        val result = runCatching { ExtensionMarkdown.parse("Owned") { throw expected } }
        assertSame(expected, result.exceptionOrNull())
    }
    @Test fun compactPaddedTableLimitBecomesAnUnavailableDocument() {
        val columns = 1001
        val source = "|" + "x|".repeat(columns) + "\n|" + "-|".repeat(columns) + "\n" + "|x|\n".repeat(1000)
        assertEquals(8008, source.length)
        assertTrue(source.length < ExtensionMarkdown.MAX_SOURCE_UNITS)
        val result = ExtensionMarkdown.parse(source)
        assertNotNull("Parser allocation limits must become an unavailable document", result.problem)
        assertTrue("A rejected table must not publish a partial document", result.blocks.isEmpty())
    }
    @Test fun tableCellBudgetAllowsItsBoundaryAndRejectsTheNextPaddedRow() {
        fun table(rows: Int) = "|" + "x|".repeat(64) + "\n|" + "-|".repeat(64) + "\n" + "|x|\n".repeat(rows)
        assertEquals(4096, ExtensionMarkdown.MAX_TABLE_CELLS)
        val accepted = ExtensionMarkdown.parse(table(63))
        assertNull(accepted.problem)
        val block = accepted.blocks.single() as MarkdownBlock.Table
        assertEquals(64, block.header.size); assertEquals(63, block.rows.size)
        assertTrue(block.rows.all { it.size == 64 })
        val rejected = ExtensionMarkdown.parse(table(64))
        assertEquals(MarkdownProblem.PARSER_LIMIT, rejected.problem); assertTrue(rejected.blocks.isEmpty())
    }
    @Test fun cancellationAfterParserLimitIsNotConvertedToAnUnavailableDocument() {
        val source = "|" + "x|".repeat(1001) + "\n|" + "-|".repeat(1001) + "\n" + "|x|\n".repeat(1000)
        val expected = java.util.concurrent.CancellationException("Owned cancelled preview")
        var checks = 0
        val result = runCatching { ExtensionMarkdown.parse(source) { if (++checks > 1) throw expected } }
        assertSame(expected, result.exceptionOrNull())
    }
}
