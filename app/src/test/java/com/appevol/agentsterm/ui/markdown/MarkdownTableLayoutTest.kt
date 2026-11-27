package com.appevol.agentsterm.ui.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MarkdownTableLayoutTest {
    @Test
    fun columnsTakeTheirContentWidthWhenEverythingFits() {
        assertArrayEquals(
            intArrayOf(100, 200),
            tableColumnWidths(intArrayOf(50, 80), intArrayOf(100, 200), available = 400, scrollColumn = 180),
        )
    }

    @Test
    fun columnsShareTheWidthInProportionWhenCellsMustWrap() {
        // 100 spare pixels over the minimum; the columns want 50 and 150 more.
        assertArrayEquals(
            intArrayOf(75, 175),
            tableColumnWidths(intArrayOf(50, 100), intArrayOf(100, 250), available = 250, scrollColumn = 180),
        )
    }

    @Test
    fun aTableThatMustScrollGivesItsColumnsRoom() {
        // Scrolling anyway, so cells need not wrap: content width, up to the scroll column width.
        // A column whose longest word is wider than that keeps its minimum.
        assertArrayEquals(
            intArrayOf(150, 180, 220),
            tableColumnWidths(intArrayOf(120, 150, 220), intArrayOf(150, 400, 300), available = 200, scrollColumn = 180),
        )
    }
}
