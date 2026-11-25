package com.example.ui.markdown

import androidx.compose.runtime.Immutable

/*
 * The parsed document. Everything here is an immutable value with structural equality, so while a
 * reply streams in Compose skips every block whose content did not change and only lays out the
 * last one again.
 */

internal enum class MdEmphasis { Italic, Bold, Strikethrough }

internal enum class MdAlign { Start, Center, End }

@Immutable
internal sealed interface MdInline {
    data class Text(val text: String) : MdInline

    data class Code(val code: String) : MdInline

    data class Styled(val emphasis: MdEmphasis, val children: List<MdInline>) : MdInline

    /** [target] is the raw link destination: a web URL, or a path on the VM. */
    data class Link(val target: String, val children: List<MdInline>) : MdInline

    data object LineBreak : MdInline
}

/** [task] is null for a plain item, otherwise whether the task checkbox is ticked. */
@Immutable
internal data class MdListItem(val task: Boolean?, val blocks: List<MdBlock>)

@Immutable
internal data class MdTableRow(val cells: List<List<MdInline>>)

@Immutable
internal sealed interface MdBlock {
    data class Paragraph(val content: List<MdInline>) : MdBlock

    data class Heading(val level: Int, val content: List<MdInline>) : MdBlock

    data class CodeBlock(val language: String, val code: String) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /** [tight] lists have no blank lines between items and render without paragraph gaps. */
    data class ItemList(
        val ordered: Boolean,
        val start: Int,
        val tight: Boolean,
        val items: List<MdListItem>,
    ) : MdBlock

    data class Table(
        val alignments: List<MdAlign>,
        val header: MdTableRow,
        val rows: List<MdTableRow>,
    ) : MdBlock

    data object Rule : MdBlock
}
