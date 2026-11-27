package com.appevol.agentsterm.ui.markdown

/**
 * Incrementally parses streaming chat Markdown using parser-provided [org.commonmark.node.SourceSpan]
 * boundaries so earlier completed top-level blocks are parsed once and reused by reference while
 * only the live tail is re-parsed on each delta.
 *
 * Also fast-paths open top-level fenced code blocks in O(delta) time without re-parsing Markdown
 * until a candidate closing fence line arrives.
 *
 * When [isStreaming] transitions to `false` (or for non-streaming renders), runs a single
 * authoritative [MarkdownParser.parse] pass unless the exact same text was already parsed in full.
 */
internal class StreamingMarkdownState {
    private var lastMarkdown: String = ""
    private var wasStreaming: Boolean = false
    private var frozenOffset: Int = 0
    private var frozenBlocks: List<MdBlock> = emptyList()
    private var cachedBlocks: List<MdBlock> = emptyList()
    private var hasLinkRefDef: Boolean = false
    private var activeOpenFence: ActiveOpenFence? = null

    /** Number of characters currently locked into [frozenBlocks]; exposed for tests. */
    internal val frozenLength: Int
        get() = frozenOffset

    /** Number of blocks currently cached in the frozen prefix; exposed for tests. */
    internal val frozenBlockCount: Int
        get() = frozenBlocks.size

    /** Number of streaming updates satisfied by the O(1) open-fence fast path; exposed for tests. */
    internal var openFenceFastPathHits: Int = 0
        private set

    internal val currentBlocks: List<MdBlock>
        get() = cachedBlocks

    internal fun isUpToDate(markdown: String, isStreaming: Boolean): Boolean = markdown == lastMarkdown && isStreaming == wasStreaming

    /**
     * Estimates how many characters will be parsed by [update], so callers can decide whether to
     * run synchronously (small initial renders / small tails / O(1) open-fence appends) or dispatch
     * off the UI thread.
     */
    internal fun estimatedWorkChars(markdown: String, isStreaming: Boolean): Int {
        if (markdown.isEmpty() || isUpToDate(markdown, isStreaming)) return 0
        if (!isStreaming) {
            return if (markdown == lastMarkdown && frozenOffset == 0) 0 else markdown.length
        }
        val canAppend = wasStreaming && markdown.startsWith(lastMarkdown) && markdown.length >= frozenOffset
        if (!canAppend || hasLinkRefDef) return markdown.length
        val fence = activeOpenFence
        if (fence != null) {
            return (markdown.length - fence.lineStartOffset).coerceAtLeast(0)
        }
        return (markdown.length - frozenOffset).coerceAtLeast(0)
    }

    fun update(markdown: String, isStreaming: Boolean): List<MdBlock> {
        if (isUpToDate(markdown, isStreaming)) {
            return cachedBlocks
        }
        if (markdown.isEmpty()) {
            reset()
            lastMarkdown = markdown
            wasStreaming = isStreaming
            return emptyList()
        }
        return if (isStreaming) {
            updateStreaming(markdown)
        } else {
            updateStatic(markdown)
        }
    }

    private fun updateStatic(markdown: String): List<MdBlock> {
        // Static message or turn just finished: run one authoritative full parse unless the
        // exact same text was already parsed in full (frozenOffset == 0 and no fast-path state).
        if (markdown != lastMarkdown || frozenOffset > 0 || activeOpenFence != null) {
            cachedBlocks = MarkdownParser.parse(markdown)
        }
        frozenOffset = 0
        frozenBlocks = emptyList()
        hasLinkRefDef = false
        activeOpenFence = null
        lastMarkdown = markdown
        wasStreaming = false
        return cachedBlocks
    }

    private fun updateStreaming(markdown: String): List<MdBlock> {
        if (!wasStreaming || !markdown.startsWith(lastMarkdown) || markdown.length < frozenOffset) {
            reset()
        }
        if (hasLinkRefDef) {
            return parseFullStreaming(markdown)
        }
        val fence = activeOpenFence
        if (fence != null) {
            val fastBlock = fence.tryAppend(markdown)
            if (fastBlock != null) {
                openFenceFastPathHits++
                cachedBlocks = combineBlocks(frozenBlocks, listOf(fastBlock))
                lastMarkdown = markdown
                wasStreaming = true
                return cachedBlocks
            }
            // Candidate closing fence or edge case arrived: fall back to authoritative tail parse.
            activeOpenFence = null
        }

        val tail = if (frozenOffset == 0) markdown else markdown.substring(frozenOffset)
        val parsed = MarkdownParser.parseDocument(tail)
        if (parsed.hasLinkReferenceDefinition) {
            // A link reference definition can retroactively resolve `[ref]` links in earlier blocks.
            if (frozenOffset > 0) {
                reset()
                hasLinkRefDef = true
                return parseFullStreaming(markdown)
            }
            hasLinkRefDef = true
            activeOpenFence = null
            cachedBlocks = parsed.blocks.map { it.block }
            lastMarkdown = markdown
            wasStreaming = true
            return cachedBlocks
        }

        val freezeCount = findSafeFreezeCount(tail, parsed.blocks)
        if (freezeCount > 0) {
            val newlyFrozen = parsed.blocks.subList(0, freezeCount).map { it.block }
            frozenBlocks = if (frozenBlocks.isEmpty()) newlyFrozen else frozenBlocks + newlyFrozen
            frozenOffset += parsed.blocks[freezeCount].startOffset
        }

        val tailBlocks = if (freezeCount == 0) {
            parsed.blocks.map { it.block }
        } else {
            parsed.blocks.subList(freezeCount, parsed.blocks.size).map { it.block }
        }
        activeOpenFence = maybeCreateOpenFence(
            markdown = markdown,
            tailStartOffset = frozenOffset,
            tailBlockCount = tailBlocks.size,
            parsedTailBaseOffset = frozenOffset - if (freezeCount > 0) parsed.blocks[freezeCount].startOffset else 0,
            openFenceInfo = parsed.trailingOpenFence,
        )
        cachedBlocks = combineBlocks(frozenBlocks, tailBlocks)
        lastMarkdown = markdown
        wasStreaming = true
        return cachedBlocks
    }

    private fun parseFullStreaming(markdown: String): List<MdBlock> {
        activeOpenFence = null
        cachedBlocks = MarkdownParser.parse(markdown)
        lastMarkdown = markdown
        wasStreaming = true
        return cachedBlocks
    }

    private fun reset() {
        frozenOffset = 0
        frozenBlocks = emptyList()
        cachedBlocks = emptyList()
        hasLinkRefDef = false
        activeOpenFence = null
    }

    /**
     * Tracks an open top-level fenced code block and appends incoming lines in O(delta) time
     * until a candidate closing fence arrives.
     */
    private class ActiveOpenFence(
        private val fenceChar: Char,
        private val openingLength: Int,
        private val indent: Int,
        private val language: String,
        bodyStartOffset: Int,
    ) {
        private val codeBuilder = StringBuilder()
        var lineStartOffset: Int = bodyStartOffset
            private set
        private var currentLineCodeStart: Int = 0

        /**
         * Scans newly arrived lines from [lineStartOffset] to `markdown.length`.
         * Returns the updated [MdBlock.CodeBlock], or `null` if a candidate closing fence or
         * uncommon case requires falling back to commonmark-java.
         */
        fun tryAppend(markdown: String): MdBlock.CodeBlock? {
            if (markdown.isNotEmpty() && markdown[markdown.length - 1] == '\r') {
                return null
            }
            codeBuilder.setLength(currentLineCodeStart)
            var pos = lineStartOffset
            val len = markdown.length
            while (pos < len) {
                val nlIdx = markdown.indexOf('\n', pos)
                val lineEnd = if (nlIdx == -1) len else nlIdx
                val hasNewline = nlIdx != -1
                val contentEnd = if (lineEnd > pos && markdown[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
                if (!appendCodeLine(markdown, pos, contentEnd, hasNewline)) {
                    return null
                }
                if (hasNewline) {
                    pos = nlIdx + 1
                    lineStartOffset = pos
                    currentLineCodeStart = codeBuilder.length
                } else {
                    pos = len
                }
            }
            return MdBlock.CodeBlock(
                language = language,
                code = codeBuilder.toString().trimEnd('\n'),
            )
        }

        private fun appendCodeLine(
            markdown: String,
            lineStart: Int,
            contentEnd: Int,
            hasNewline: Boolean,
        ): Boolean {
            if (containsSpecialChar(markdown, lineStart, contentEnd)) {
                return false
            }
            if (isCandidateClosingFence(markdown, lineStart, contentEnd, fenceChar, openingLength)) {
                return false
            }
            var strippedStart = lineStart
            if (indent > 0) {
                var stripped = 0
                while (stripped < indent && strippedStart < contentEnd) {
                    val ch = markdown[strippedStart]
                    if (ch == ' ') {
                        stripped++
                        strippedStart++
                    } else if (ch == '\t') {
                        // CommonMark expands tabs to tab-stop 4 when stripping fence indent; let parser handle it.
                        return false
                    } else {
                        break
                    }
                }
            }
            codeBuilder.append(markdown, strippedStart, contentEnd)
            if (hasNewline) {
                codeBuilder.append('\n')
            }
            return true
        }
    }

    companion object {
        private const val MAX_CLOSING_FENCE_INDENT = 3

        private fun combineBlocks(prefix: List<MdBlock>, tail: List<MdBlock>): List<MdBlock> = when {
            prefix.isEmpty() -> tail
            tail.isEmpty() -> prefix
            else -> prefix + tail
        }

        /**
         * Returns how many leading blocks of [blocks] are permanently finalized and safe to freeze.
         *
         * Requiring a `\n` at or after the next block's start offset guarantees that the next
         * block's first line is complete and can never mutate into a list marker (`2.` / `• `),
         * a backtick-invalidated fence info string, or a non-interrupting thematic break that
         * retroactively merges back into the preceding block.
         */
        private fun findSafeFreezeCount(tail: String, blocks: List<TopLevelBlock>): Int {
            if (blocks.size < 2) return 0
            val lastIdx = blocks.lastIndex
            val lastStart = blocks[lastIdx].startOffset
            if (lastStart > 0 && tail.indexOf('\n', lastStart) != -1) {
                return lastIdx
            }
            if (blocks.size >= 3) {
                val prevStart = blocks[lastIdx - 1].startOffset
                if (prevStart > 0 && tail.indexOf('\n', prevStart) != -1) {
                    return lastIdx - 1
                }
            }
            return 0
        }

        private fun maybeCreateOpenFence(
            markdown: String,
            tailStartOffset: Int,
            tailBlockCount: Int,
            parsedTailBaseOffset: Int,
            openFenceInfo: OpenFenceInfo?,
        ): ActiveOpenFence? {
            if (openFenceInfo == null || tailBlockCount != 1) return null
            val relBodyStart = openFenceInfo.bodyStartOffset ?: return null
            val absBlockStart = parsedTailBaseOffset + openFenceInfo.blockStartOffset
            if (absBlockStart < tailStartOffset) return null
            val absBodyStart = parsedTailBaseOffset + relBodyStart
            val fence = ActiveOpenFence(
                fenceChar = openFenceInfo.fenceChar,
                openingLength = openFenceInfo.openingLength,
                indent = openFenceInfo.indent,
                language = openFenceInfo.language,
                bodyStartOffset = absBodyStart,
            )
            return if (fence.tryAppend(markdown) != null) fence else null
        }

        private fun containsSpecialChar(text: String, start: Int, end: Int): Boolean {
            for (i in start until end) {
                val ch = text[i]
                if (ch == '\u0000' || ch == '\r') return true
            }
            return false
        }

        private fun isCandidateClosingFence(
            text: String,
            start: Int,
            end: Int,
            fenceChar: Char,
            openingLength: Int,
        ): Boolean {
            var i = start
            var spaces = 0
            while (i < end && text[i] == ' ') {
                spaces++
                i++
            }
            if (spaces > MAX_CLOSING_FENCE_INDENT || i >= end || text[i] != fenceChar) {
                return false
            }
            val fenceStart = i
            while (i < end && text[i] == fenceChar) i++
            if (i - fenceStart < openingLength) return false
            while (i < end) {
                if (text[i] != ' ' && text[i] != '\t') return false
                i++
            }
            return true
        }
    }
}
