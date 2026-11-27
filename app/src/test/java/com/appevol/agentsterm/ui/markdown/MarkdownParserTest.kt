package com.appevol.agentsterm.ui.markdown

import com.appevol.agentsterm.ui.markdown.MdBlock.CodeBlock
import com.appevol.agentsterm.ui.markdown.MdBlock.Heading
import com.appevol.agentsterm.ui.markdown.MdBlock.ItemList
import com.appevol.agentsterm.ui.markdown.MdBlock.Paragraph
import com.appevol.agentsterm.ui.markdown.MdBlock.Quote
import com.appevol.agentsterm.ui.markdown.MdBlock.Rule
import com.appevol.agentsterm.ui.markdown.MdBlock.Table
import com.appevol.agentsterm.ui.markdown.MdInline.Code
import com.appevol.agentsterm.ui.markdown.MdInline.LineBreak
import com.appevol.agentsterm.ui.markdown.MdInline.Link
import com.appevol.agentsterm.ui.markdown.MdInline.Styled
import com.appevol.agentsterm.ui.markdown.MdInline.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    private fun parse(markdown: String) = MarkdownParser.parse(markdown)

    private fun text(vararg parts: String) = parts.map { Text(it) }

    @Test
    fun blocksOfEveryKind() {
        val blocks = parse(
            """
            # Title

            Intro **bold** text.

            ---

            > quoted

            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Heading(1, text("Title")),
                Paragraph(listOf(Text("Intro "), Styled(MdEmphasis.Bold, text("bold")), Text(" text."))),
                Rule,
                Quote(listOf(Paragraph(text("quoted")))),
                CodeBlock("kotlin", "val x = 1"),
            ),
            blocks,
        )
    }

    /** #39: `---` and tables used to show as raw text. */
    @Test
    fun tableWithAlignmentAndInlineContent() {
        val table = parse(
            """
            | Exit | Meaning | Count |
            | :--- | :---: | ---: |
            | `0` | Success | 1 |
            | `3` | short row |
            """.trimIndent(),
        ).single() as Table
        assertEquals(listOf(MdAlign.Start, MdAlign.Center, MdAlign.End), table.alignments)
        assertEquals(MdTableRow(listOf(text("Exit"), text("Meaning"), text("Count"))), table.header)
        assertEquals(listOf(Code("0")), table.rows[0].cells[0])
        assertEquals(2, table.rows.size)
    }

    @Test
    fun nestedOrderedAndTaskLists() {
        val blocks = parse(
            """
            8. eight
            9. nine
               - nested
            
            - [x] done
            - [ ] open
            """.trimIndent(),
        )
        val ordered = blocks[0] as ItemList
        assertTrue(ordered.ordered)
        assertEquals(8, ordered.start)
        assertEquals(2, ordered.items.size)
        val nested = ordered.items[1].blocks[1] as ItemList
        assertEquals(listOf(Paragraph(text("nested"))), nested.items.single().blocks)

        val tasks = blocks[1] as ItemList
        assertEquals(listOf(true, false), tasks.items.map { it.task })
        assertEquals(listOf(Paragraph(text("done"))), tasks.items[0].blocks)
    }

    @Test
    fun looseListsAreNotTight() {
        assertTrue((parse("- a\n- b").single() as ItemList).tight)
        assertTrue(!(parse("- a\n\n- b").single() as ItemList).tight)
    }

    @Test
    fun dotBulletsAreListItemsExceptInCode() {
        val blocks = parse("• one\n• two\n\n```\n• literal\n```")
        assertEquals(2, (blocks[0] as ItemList).items.size)
        assertEquals(CodeBlock("", "• literal"), blocks[1])
    }

    @Test
    fun singleNewlineIsALineBreak() {
        assertEquals(
            listOf(Paragraph(listOf(Text("one"), LineBreak, Text("two"), LineBreak, Text("three")))),
            parse("one\ntwo<br>three"),
        )
    }

    @Test
    fun indentedTextAndHtmlStayText() {
        assertEquals(listOf(Paragraph(text("indented by four"))), parse("    indented by four"))
        assertEquals(listOf(Paragraph(text("<div>raw</div>"))), parse("<div>raw</div>"))
        assertEquals(listOf(Paragraph(text("a <b>b</b> c"))), parse("a <b>b</b> c"))
    }

    /** While a reply streams in, the closing fence has not arrived yet. */
    @Test
    fun unclosedFenceIsACodeBlockToTheEnd() {
        assertEquals(
            listOf(Paragraph(text("Run:")), CodeBlock("bash", "ls -la\ncd /tmp")),
            parse("Run:\n```bash\nls -la\ncd /tmp"),
        )
    }

    @Test
    fun strikethroughAutolinkAndImage() {
        val inlines = (parse("~~old~~ https://example.com/a. ![alt text](https://e.com/i.png)").single() as Paragraph).content
        assertEquals(
            listOf(
                Styled(MdEmphasis.Strikethrough, text("old")),
                Text(" "),
                Link("https://example.com/a", text("https://example.com/a")),
                Text(". "),
                Link("https://e.com/i.png", text("alt text")),
            ),
            inlines,
        )
    }

    /** Text that only looks like Markdown must come through unchanged. */
    @Test
    fun literalCharactersSurvive() {
        for (literal in listOf("2 * 3 * 4", "snake_case_name", "array[0] (x)", "a < b && c > d", "50% of \$5")) {
            assertEquals(literal, listOf(Paragraph(text(literal))), parse(literal))
        }
    }

    @Test
    fun emptyAndBlankInput() {
        assertEquals(emptyList<MdBlock>(), parse(""))
        assertEquals(emptyList<MdBlock>(), parse("  \n\n"))
    }
}
