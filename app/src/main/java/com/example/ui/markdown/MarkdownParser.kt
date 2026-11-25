package com.example.ui.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Parses chat Markdown into [MdBlock]s with commonmark-java, the JVM reference implementation of
 * the CommonMark spec, plus the GitHub extensions agents use: tables, strikethrough, task lists
 * and bare URLs.
 *
 * Deliberate differences from the spec, because this renders chat messages and not documents:
 * - A single newline is a line break (as in GitHub comments), not a space.
 * - Indented code blocks are off. Agents fence their code, while text that merely happens to be
 *   indented by four spaces is common and must not turn into a code block.
 * - Raw HTML is shown as text; nothing is interpreted except `<br>`.
 * - `•` starts a list item like `-` does, because agents print it.
 */
internal object MarkdownParser {
    // Thread-safe and reusable, per the commonmark-java docs.
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .enabledBlockTypes(
            setOf(
                Heading::class.java,
                FencedCodeBlock::class.java,
                ThematicBreak::class.java,
                ListBlock::class.java,
                BlockQuote::class.java,
            ),
        )
        .build()

    fun parse(markdown: String): List<MdBlock> = blocks(parser.parse(normalizeBullets(markdown)))

    private val fenceRegex = Regex("^\\s*(```|~~~)")
    private val dotBulletRegex = Regex("^(\\s*)• ")

    /** Rewrites `• item` to `- item` outside fenced code. */
    private fun normalizeBullets(markdown: String): String {
        if ('•' !in markdown) return markdown
        var inFence = false
        return markdown.lineSequence().joinToString("\n") { line ->
            when {
                fenceRegex.containsMatchIn(line) -> {
                    inFence = !inFence
                    line
                }

                inFence -> line

                else -> dotBulletRegex.replaceFirst(line, "$1- ")
            }
        }
    }

    private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

    private fun blocks(parent: Node): List<MdBlock> = parent.children().mapNotNull(::block).toList()

    private fun block(node: Node): MdBlock? = when (node) {
        is Paragraph -> MdBlock.Paragraph(inlines(node))

        is Heading -> MdBlock.Heading(node.level, inlines(node))

        is FencedCodeBlock -> MdBlock.CodeBlock(
            language = node.info.orEmpty().trim().substringBefore(' '),
            code = node.literal.orEmpty().trimEnd('\n'),
        )

        is IndentedCodeBlock -> MdBlock.CodeBlock("", node.literal.orEmpty().trimEnd('\n'))

        is BlockQuote -> MdBlock.Quote(blocks(node))

        is BulletList -> itemList(node, ordered = false, start = 1)

        is OrderedList -> itemList(node, ordered = true, start = node.markerStartNumber ?: 1)

        is ThematicBreak -> MdBlock.Rule

        is TableBlock -> table(node)

        is HtmlBlock -> MdBlock.Paragraph(listOf(MdInline.Text(node.literal.orEmpty().trimEnd('\n'))))

        is LinkReferenceDefinition -> null

        // An extension block this renderer does not know: keep its text rather than drop it.
        else -> MdBlock.Paragraph(inlines(node)).takeIf { it.content.isNotEmpty() }
    }

    private fun itemList(list: ListBlock, ordered: Boolean, start: Int): MdBlock.ItemList {
        val items = list.children().filterIsInstance<ListItem>().map { item ->
            val marker = item.firstChild as? TaskListItemMarker
            MdListItem(task = marker?.isChecked, blocks = blocks(item))
        }
        return MdBlock.ItemList(ordered, start, list.isTight, items.toList())
    }

    private fun table(table: TableBlock): MdBlock.Table {
        // TableBlock > TableHead/TableBody > TableRow > TableCell
        val rows = table.children().flatMap { it.children() }.filterIsInstance<TableRow>().toList()
        val cells = rows.map { row -> row.children().filterIsInstance<TableCell>().toList() }
        val header = cells.firstOrNull().orEmpty()
        val alignments = header.map {
            when (it.alignment) {
                TableCell.Alignment.CENTER -> MdAlign.Center
                TableCell.Alignment.RIGHT -> MdAlign.End
                else -> MdAlign.Start
            }
        }
        val converted = cells.map { row -> MdTableRow(row.map(::inlines)) }
        return MdBlock.Table(
            alignments = alignments,
            header = converted.firstOrNull() ?: MdTableRow(emptyList()),
            rows = converted.drop(1),
        )
    }

    private fun inlines(parent: Node): List<MdInline> {
        val out = mutableListOf<MdInline>()
        for (child in parent.children()) appendInline(child, out)
        return out
    }

    private fun appendInline(node: Node, out: MutableList<MdInline>) {
        when (node) {
            is Text -> out.addText(node.literal.orEmpty())

            is Code -> out.add(MdInline.Code(node.literal.orEmpty()))

            is Emphasis -> out.add(MdInline.Styled(MdEmphasis.Italic, inlines(node)))

            is StrongEmphasis -> out.add(MdInline.Styled(MdEmphasis.Bold, inlines(node)))

            is Strikethrough -> out.add(MdInline.Styled(MdEmphasis.Strikethrough, inlines(node)))

            is Link -> out.add(MdInline.Link(node.destination.orEmpty(), inlines(node)))

            // No image loading in chat: an image is a link to itself, labelled with its alt text.
            is Image -> {
                val alt = plainText(inlines(node)).ifBlank { node.destination.orEmpty() }
                out.add(MdInline.Link(node.destination.orEmpty(), listOf(MdInline.Text(alt))))
            }

            is SoftLineBreak, is HardLineBreak -> out.add(MdInline.LineBreak)

            is HtmlInline -> {
                val html = node.literal.orEmpty()
                if (htmlBreakRegex.matches(html)) out.add(MdInline.LineBreak) else out.addText(html)
            }

            is TaskListItemMarker -> Unit

            else -> for (child in node.children()) appendInline(child, out)
        }
    }

    private val htmlBreakRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /** Adjacent text nodes (commonmark splits at every `[`, `_`, …) become one. */
    private fun MutableList<MdInline>.addText(text: String) {
        if (text.isEmpty()) return
        val last = lastOrNull()
        if (last is MdInline.Text) set(lastIndex, MdInline.Text(last.text + text)) else add(MdInline.Text(text))
    }

    private fun plainText(inlines: List<MdInline>): String = inlines.joinToString("") {
        when (it) {
            is MdInline.Text -> it.text
            is MdInline.Code -> it.code
            is MdInline.Styled -> plainText(it.children)
            is MdInline.Link -> plainText(it.children)
            MdInline.LineBreak -> " "
        }
    }
}
