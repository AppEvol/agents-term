package com.example.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CellPaddingHorizontal = 10.dp
private val CellPaddingVertical = 7.dp
private val MinColumnWidth = 56.dp
private val MaxColumnWidth = 260.dp
private val ScrollColumnWidth = 180.dp

/**
 * Column widths for a table that has [available] pixels, the way browsers size an HTML table
 * (CSS 2.1 "automatic table layout"). [min] is the narrowest each column can be without breaking
 * a word, [max] is its width with every cell on one line.
 *
 * - Everything fits on one line per cell: each column is as wide as its content.
 * - Otherwise cells wrap, and the columns share the width in proportion to how much each wants.
 * - Not even the narrowest layout fits: the caller scrolls sideways. Width is then no longer
 *   scarce, so a column is as wide as its content up to [scrollColumn], instead of squeezed.
 */
internal fun tableColumnWidths(min: IntArray, max: IntArray, available: Int, scrollColumn: Int): IntArray {
    val minTotal = min.sum()
    val maxTotal = max.sum()
    return when {
        maxTotal <= available -> max.copyOf()

        minTotal >= available -> IntArray(min.size) { max[it].coerceAtMost(maxOf(min[it], scrollColumn)) }

        else -> {
            val spare = (available - minTotal).toFloat() / (maxTotal - minTotal)
            IntArray(min.size) { min[it] + ((max[it] - min[it]) * spare).toInt() }
        }
    }
}

/**
 * A GitHub-style table. It is as wide as its content needs, wraps text inside cells before it
 * grows past the available width, and scrolls sideways only when wrapping is not enough.
 */
@Composable
internal fun MarkdownTable(table: MdBlock.Table, style: MarkdownStyle) {
    val columns = table.header.cells.size
    if (columns == 0) return
    val rows = listOf(table.header) + table.rows
    val shape = RoundedCornerShape(8.dp)
    val cellStyle = style.body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    BoxWithConstraints {
        val available = constraints.maxWidth
        Box(
            modifier = Modifier
                .clip(shape)
                .border(1.dp, style.outlineColor, shape)
                .horizontalScroll(rememberScrollState()),
        ) {
            Layout(
                content = {
                    rows.forEachIndexed { rowIndex, row ->
                        for (column in 0 until columns) {
                            MarkdownInlineText(
                                // A row may have fewer cells than the header.
                                content = row.cells.getOrNull(column).orEmpty(),
                                textStyle = if (rowIndex == 0) headerStyle else cellStyle,
                                style = style,
                                textAlign = when (table.alignments.getOrNull(column)) {
                                    MdAlign.Center -> TextAlign.Center
                                    MdAlign.End -> TextAlign.End
                                    else -> TextAlign.Start
                                },
                                modifier = Modifier.padding(CellPaddingHorizontal, CellPaddingVertical),
                            )
                        }
                    }
                    Box(Modifier.background(style.headerBackground))
                    // Lines between rows, then lines between columns.
                    repeat(rows.size - 1 + columns - 1) { Box(Modifier.background(style.outlineColor)) }
                },
            ) { measurables, _ ->
                val cellCount = rows.size * columns
                val cells = measurables.subList(0, cellCount)
                val headerBackground = measurables[cellCount]
                val rowLines = measurables.subList(cellCount + 1, cellCount + rows.size)
                val columnLines = measurables.subList(cellCount + rows.size, measurables.size)

                val widths = measureColumnWidths(cells, columns, available)
                val placeables = cells.mapIndexed { i, cell -> cell.measure(Constraints.fixedWidth(widths[i % columns])) }
                // Cells of a row sit on a common baseline. Aligning their tops is not enough: a cell
                // that holds only inline code has its baseline elsewhere than one with plain text.
                val baselines = placeables.map { it[FirstBaseline].takeIf { line -> line != AlignmentLine.Unspecified } ?: 0 }
                val rowBaselines = IntArray(rows.size) { row -> (0 until columns).maxOf { baselines[row * columns + it] } }
                val rowHeights = IntArray(rows.size) { row ->
                    (0 until columns).maxOf { column ->
                        val cell = row * columns + column
                        rowBaselines[row] - baselines[cell] + placeables[cell].height
                    }
                }
                val width = widths.sum()
                val height = rowHeights.sum()
                val line = 1.dp.roundToPx()

                val header = headerBackground.measure(Constraints.fixed(width, rowHeights[0]))
                val horizontal = rowLines.map { it.measure(Constraints.fixed(width, line)) }
                val vertical = columnLines.map { it.measure(Constraints.fixed(line, height)) }

                layout(width, height) {
                    header.place(0, 0, zIndex = -1f)
                    var y = 0
                    for (row in rows.indices) {
                        var x = 0
                        for (column in 0 until columns) {
                            val cell = row * columns + column
                            placeables[cell].place(x, y + rowBaselines[row] - baselines[cell])
                            x += widths[column]
                        }
                        y += rowHeights[row]
                        horizontal.getOrNull(row)?.place(0, y)
                    }
                    var x = 0
                    for (column in 0 until columns - 1) {
                        x += widths[column]
                        vertical[column].place(x, 0)
                    }
                }
            }
        }
    }
}

private fun Density.measureColumnWidths(cells: List<Measurable>, columns: Int, available: Int): IntArray {
    val minColumn = MinColumnWidth.roundToPx()
    val maxColumn = MaxColumnWidth.roundToPx()
    val min = IntArray(columns)
    val max = IntArray(columns)
    cells.forEachIndexed { i, cell ->
        val column = i % columns
        // One extra pixel: a text measured at exactly its intrinsic width can still wrap, because
        // the intrinsic width is rounded.
        val wanted = cell.maxIntrinsicWidth(Constraints.Infinity) + 1
        val narrowest = cell.minIntrinsicWidth(Constraints.Infinity) + 1
        max[column] = maxOf(max[column], wanted)
        // Wrapping a short cell into a sliver gains nothing: keep it whole up to minColumn.
        min[column] = maxOf(min[column], narrowest, minOf(wanted, minColumn))
    }
    for (column in 0 until columns) {
        max[column] = minOf(max[column], maxColumn)
        min[column] = minOf(min[column], max[column])
    }
    return tableColumnWidths(min, max, available, ScrollColumnWidth.roundToPx())
}
