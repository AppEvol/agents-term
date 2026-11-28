package com.appevol.agentsterm.ui.markdown

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
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
            assertStreamingMatchesFullParse(loadSample(sample), chunkSizes = listOf(1, 7, 29))
        }
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
        assertStreamingMatchesFullParse(tricky, chunkSizes = listOf(1, 3, 11, 37))
    }

    @Test
    fun matchesFullParseOnBlockInterruptionEdgeCasesAndCrlf() {
        val edgeCases = listOf(
            // Backtick added to fence info string turns apparent fence back into inline code in paragraph.
            "Hello\n```foo` bar\n\nAfter.\n",
            // Thematic break invalidated by trailing character merges back into preceding paragraph.
            "Hello\n***not a rule\n\nAfter.\n",
            // Indented fenced code block with blank lines and partial closing fences inside.
            "Intro.\n\n  ~~~~python\n  x = 1\n\n  ~~~\n    ~~~~\n  y = 2\n  ~~~~\n\nOutro.\n",
            // CRLF line endings mixed with dot bullets and fenced code.
            "• one\r\n\r\n• two\r\n\r\n```sh\r\necho hi\r\n• literal\r\n```\r\n\r\nDone.\r\n",
            // Multi-line link reference definition and link reference inside blockquote.
            "Ref [one][a] and [two][b].\n\nMiddle.\n\n[a]:\nhttps://example.com/a\n\n> [b]: https://example.com/b\n",
        )
        for (doc in edgeCases) {
            assertStreamingMatchesFullParse(doc, chunkSizes = listOf(1, 5))
        }
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
    fun openCodeFenceFastPathHandlesGrowingFenceInO1UntilClosingFenceArrives() {
        val state = StreamingMarkdownState()
        val sb = StringBuilder("Intro paragraph.\n\n```kotlin\n")
        assertEquals(MarkdownParser.parse(sb.toString()), state.update(sb.toString(), isStreaming = true))
        assertEquals(1, state.frozenBlockCount)

        // Append 150 lines including blank lines, dot bullets, and shorter backtick runs.
        for (i in 1..150) {
            val line = when (i) {
                25 -> "\n"
                50 -> "• not a bullet\n"
                75 -> "``not closing\n"
                100 -> "    ```indented by four is literal\n"
                else -> "val line$i = $i * 2\n"
            }
            sb.append(line)
            val current = sb.toString()
            // Estimated work stays proportional to the delta line, not the entire 150-line block.
            assertTrue(
                "Expected O(delta) work estimate, got ${state.estimatedWorkChars(current, true)}",
                state.estimatedWorkChars(current, isStreaming = true) <= line.length + 1,
            )
            val streamed = state.update(current, isStreaming = true)
            assertEquals(2, streamed.size)
            assertEquals(MarkdownParser.parse(current), streamed)
        }
        assertEquals(150, state.openFenceFastPathHits)

        // Stream closing fence 1 char at a time followed by trailing prose.
        for (ch in "```\n\nAfter code.\n") {
            sb.append(ch)
            val current = sb.toString()
            assertEquals(MarkdownParser.parse(current), state.update(current, isStreaming = true))
        }
        assertEquals(2, state.frozenBlockCount)
        assertEquals(MarkdownParser.parse(sb.toString()), state.update(sb.toString(), isStreaming = false))
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

    @Test
    fun asyncSessionParsesSmallDocsSyncAndCoalescesLargeBackgroundParsesWithMonotonicRevisions() = runTest {
        val bgDispatcher = StandardTestDispatcher(testScheduler)
        val commits = mutableListOf<List<MdBlock>>()
        val session = AsyncMarkdownSession(
            scope = this,
            parseDispatcher = bgDispatcher,
            syncThresholdChars = 128,
            onAsyncCommit = { commits.add(it) },
        )

        // Small static document parses synchronously on frame 1.
        val small = "# Small\n\nShort reply."
        val smallBlocks = session.requestBlocks(small, isStreaming = false)
        session.launchIfNeeded()
        assertEquals(MarkdownParser.parse(small), smallBlocks)
        assertEquals(0, session.asyncDispatchCount)
        assertTrue(commits.isEmpty())

        // Burst of large updates while background worker hasn't run yet coalesces to the latest revision.
        val large1 = (1..20).joinToString("\n") { "| col$it | val$it |" }
        val large2 = "$large1\n| col21 | val21 |"
        val large3 = "| H1 | H2 |\n| --- | --- |\n$large2"

        session.requestBlocks(large1, isStreaming = true)
        session.launchIfNeeded()
        session.requestBlocks(large2, isStreaming = true)
        session.launchIfNeeded()
        session.requestBlocks(large3, isStreaming = false)
        session.launchIfNeeded()

        assertEquals(1, session.asyncDispatchCount)
        advanceUntilIdle()

        assertEquals(1, commits.size)
        assertEquals(MarkdownParser.parse(large3), session.blocks)
        assertEquals(MarkdownParser.parse(large3), commits.single())
    }

    @Test
    fun asyncSessionCommitsOnOwnerScopeDispatcher() {
        val ownerThreadRef = AtomicReference<Thread?>(null)
        val ownerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-markdown-owner-main").also { ownerThreadRef.set(it) }
        }
        val ownerDispatcher = ownerExecutor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        val observedCommitThread = AtomicReference<Thread?>(null)
        val committed = CountDownLatch(1)
        val largeDoc = (1..20).joinToString("\n\n") { "Paragraph $it with **bold** and `code`." }

        try {
            val session = AsyncMarkdownSession(
                scope = ownerScope,
                parseDispatcher = Dispatchers.Default,
                syncThresholdChars = 0,
                onAsyncCommit = {
                    observedCommitThread.set(Thread.currentThread())
                    committed.countDown()
                },
            )

            // Queue and launch on the owner thread (matching Compose UI thread ownership).
            ownerExecutor.submit {
                session.requestBlocks(largeDoc, isStreaming = false)
                session.launchIfNeeded()
            }.get(5, TimeUnit.SECONDS)

            assertTrue("Timed out waiting for async markdown commit", committed.await(5, TimeUnit.SECONDS))
            assertSame(ownerThreadRef.get(), observedCommitThread.get())
            assertEquals(MarkdownParser.parse(largeDoc), session.blocks)
        } finally {
            ownerScope.cancel()
            ownerDispatcher.close()
            ownerExecutor.shutdownNow()
        }
    }
}
