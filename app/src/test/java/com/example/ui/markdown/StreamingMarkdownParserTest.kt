package com.example.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownParserTest {

    private fun loadSample(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("markdown-samples/$name.md")) {
            "Missing sample resource: $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun assertStreamingMatchesFullParse(markdown: String, chunkSizes: List<Int>) {
        for (chunkSize in chunkSizes) {
            val state = StreamingMarkdownState()
            var offset = 0
            while (offset < markdown.length) {
                offset = (offset + chunkSize).coerceAtMost(markdown.length)
                val prefix = markdown.substring(0, offset)
                val streamed = state.update(prefix, isStreaming = true)
                val full = MarkdownParser.parse(prefix)
                assertEquals(
                    "Mismatch at offset=$offset (chunkSize=$chunkSize)",
                    full,
                    streamed,
                )
            }
            assertEquals(
                "Mismatch on finalization (chunkSize=$chunkSize)",
                MarkdownParser.parse(markdown),
                state.update(markdown, isStreaming = false),
            )
        }
    }

    @Test
    fun matchesFullParseAcrossAllGoldenSamplesWhenStreamedIncrementally() {
        val samples = listOf(
            "headings",
            "inline_styles",
            "inline_code",
            "lists",
            "code_blocks",
            "quotes_and_rules",
            "tables",
            "chat_reply",
        )
        for (sample in samples) {
            assertStreamingMatchesFullParse(loadSample(sample), chunkSizes = listOf(7, 29))
        }
        // Verify 1-char-at-a-time streaming on loose/ordered lists so partial markers like `"2"` never split early.
        assertStreamingMatchesFullParse(loadSample("lists"), chunkSizes = listOf(1))
    }

    @Test
    fun matchesFullParseOnTrickyLooseListsFencesAndQuotes() {
        val tricky = """
            Intro paragraph with `code`.

            - item 1

              continuation paragraph inside item 1

              ```kotlin
              val insideList = 1

              val stillInsideFence = 2
              ```

            - item 2

            1. ordered one

            2. ordered two

            > quote line 1
            >
            > quote line 2

            > second independent quote

            • dot bullet one
            • dot bullet two

            Setext heading
            ---

            | Col A | Col B |
            | :--- | ---: |
            | `a` | 1 |
            | `b` | 2 |

            ```bash
            echo "fence 1"

            echo "still fence 1"
            ```
            Immediately after fence without blank line.

            Final paragraph.
        """.trimIndent()
        assertStreamingMatchesFullParse(tricky, chunkSizes = listOf(3, 11, 37))
    }

    @Test
    fun freezesCompletedTopLevelBlocksAndReusesExactBlockReferences() {
        val state = StreamingMarkdownState()
        val part1 = "# Summary\n\nFirst paragraph.\n\n```kotlin\nval x = 1\n```\n\nSecond line\n"
        val blocks1 = state.update(part1, isStreaming = true)
        assertTrue("Expected earlier blocks to be frozen", state.frozenLength > 0)
        assertEquals(3, state.frozenBlockCount)
        assertEquals(4, blocks1.size)

        val part2 = part1 + "growing with **more** text."
        val blocks2 = state.update(part2, isStreaming = true)
        assertEquals(4, blocks2.size)
        // Earlier completed blocks keep exact object identity so Compose skips them cleanly.
        assertSame(blocks1[0], blocks2[0])
        assertSame(blocks1[1], blocks2[1])
        assertSame(blocks1[2], blocks2[2])
        assertEquals(MarkdownParser.parse(part2), blocks2)
    }

    @Test
    fun invalidatesFrozenPrefixWhenLinkReferenceDefinitionArrivesLater() {
        val state = StreamingMarkdownState()
        val beforeDef = "See [the docs][d] for details.\n\nMiddle paragraph.\n\n"
        val blocksBefore = state.update(beforeDef, isStreaming = true)
        assertTrue(state.frozenLength > 0)
        assertEquals(MarkdownParser.parse(beforeDef), blocksBefore)

        val withDef = beforeDef + "[d]: https://example.com/docs\n"
        val blocksAfter = state.update(withDef, isStreaming = true)
        assertEquals(MarkdownParser.parse(withDef), blocksAfter)
        val firstPara = blocksAfter.first() as MdBlock.Paragraph
        assertTrue(
            "Expected [the docs][d] to resolve into a link once definition streamed in",
            firstPara.content.any { it is MdInline.Link && it.target == "https://example.com/docs" },
        )
    }

    @Test
    fun resetsCleanlyWhenTextIsReplacedOrEmptied() {
        val state = StreamingMarkdownState()
        state.update("Para 1.\n\nPara 2.\n\nTail", isStreaming = true)
        assertTrue(state.frozenLength > 0)

        val replaced = state.update("Completely different text", isStreaming = true)
        assertEquals(MarkdownParser.parse("Completely different text"), replaced)
        assertEquals(0, state.frozenLength)

        assertEquals(emptyList<MdBlock>(), state.update("", isStreaming = true))
        assertEquals(emptyList<MdBlock>(), state.update("", isStreaming = false))
    }
}
