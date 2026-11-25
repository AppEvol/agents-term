package com.example.ui.markdown

/**
 * Incrementally parses streaming chat Markdown so earlier completed top-level blocks are parsed
 * once and reused by reference while only the live tail is re-parsed on each delta.
 *
 * When [isStreaming] transitions to `false` (or for non-streaming renders), falls back to a single
 * authoritative [MarkdownParser.parse] pass so output always matches the full CommonMark+GFM spec.
 */
internal class StreamingMarkdownState {
    private var lastMarkdown: String = ""
    private var wasStreaming: Boolean = false
    private var frozenOffset: Int = 0
    private var frozenBlocks: List<MdBlock> = emptyList()
    private var cachedBlocks: List<MdBlock> = emptyList()
    private var hasLinkRefDef: Boolean = false

    /** Number of characters currently locked into [frozenBlocks]; exposed for tests. */
    internal val frozenLength: Int
        get() = frozenOffset

    /** Number of blocks currently cached in the frozen prefix; exposed for tests. */
    internal val frozenBlockCount: Int
        get() = frozenBlocks.size

    fun update(markdown: String, isStreaming: Boolean): List<MdBlock> {
        if (markdown == lastMarkdown && isStreaming == wasStreaming) {
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
        // exact same text was already parsed in full (frozenOffset == 0).
        if (markdown != lastMarkdown || frozenOffset > 0) {
            cachedBlocks = MarkdownParser.parse(markdown)
        }
        frozenOffset = 0
        frozenBlocks = emptyList()
        hasLinkRefDef = false
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
        val scanResult = findSafeSplitOffset(markdown, frozenOffset)
        if (scanResult == LINK_REF_DEF_DETECTED) {
            // A link reference definition can retroactively resolve `[ref]` links in earlier blocks.
            reset()
            hasLinkRefDef = true
            return parseFullStreaming(markdown)
        }
        if (scanResult > frozenOffset) {
            val newlyFinalized = MarkdownParser.parse(markdown.substring(frozenOffset, scanResult))
            if (newlyFinalized.isNotEmpty()) {
                frozenBlocks = if (frozenBlocks.isEmpty()) newlyFinalized else frozenBlocks + newlyFinalized
            }
            frozenOffset = scanResult
        }
        val tail = if (frozenOffset == 0) markdown else markdown.substring(frozenOffset)
        val tailBlocks = if (tail.isEmpty()) emptyList() else MarkdownParser.parse(tail)
        cachedBlocks = combineBlocks(frozenBlocks, tailBlocks)
        lastMarkdown = markdown
        wasStreaming = true
        return cachedBlocks
    }

    private fun parseFullStreaming(markdown: String): List<MdBlock> {
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
    }

    companion object {
        private const val LINK_REF_DEF_DETECTED = -1
        private const val MIN_FENCE_LEN = 3
        private const val MAX_TOP_LEVEL_INDENT = 3
        private const val TAB_WIDTH = 4
        private const val MAX_ORDERED_DIGITS = 9

        private fun combineBlocks(prefix: List<MdBlock>, tail: List<MdBlock>): List<MdBlock> = when {
            prefix.isEmpty() -> tail
            tail.isEmpty() -> prefix
            else -> prefix + tail
        }

        /**
         * Scans [markdown] from [startOffset] (always 0 or right after `\n`) and returns the
         * furthest char offset where splitting `parse(prefix) + parse(tail)` produces the exact
         * same blocks as `parse(markdown)`, or [LINK_REF_DEF_DETECTED] if a link reference
         * definition appears outside fenced code.
         */
        internal fun findSafeSplitOffset(markdown: String, startOffset: Int): Int = PrefixScanner(markdown, startOffset).scan()

        private class PrefixScanner(
            private val markdown: String,
            private val startOffset: Int,
        ) {
            private var inFence = false
            private var fenceChar = '\u0000'
            private var fenceLen = 0
            private var inList = false
            private var prevWasBlank = false
            private var sawNonBlank = false
            private var lastSafeSplit = startOffset

            fun scan(): Int {
                var pos = startOffset
                val len = markdown.length
                while (pos < len) {
                    val nlIdx = markdown.indexOf('\n', pos)
                    val lineEnd = if (nlIdx == -1) len else nlIdx
                    val nextLineStart = if (nlIdx == -1) len else nlIdx + 1
                    val contentEnd = if (lineEnd > pos && markdown[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
                    if (!processLine(pos, contentEnd, nlIdx != -1, nextLineStart)) {
                        return LINK_REF_DEF_DETECTED
                    }
                    pos = nextLineStart
                }
                return lastSafeSplit
            }

            /** Returns `false` if a link reference definition is detected. */
            private fun processLine(
                lineStart: Int,
                contentEnd: Int,
                hasNewline: Boolean,
                nextLineStart: Int,
            ): Boolean {
                val (contentStart, leadingSpaces) = measureIndent(lineStart, contentEnd)
                if (contentStart == contentEnd) {
                    if (!inFence) prevWasBlank = true
                    return true
                }
                if (inFence) {
                    processFencedLine(contentStart, contentEnd, leadingSpaces, hasNewline, nextLineStart)
                    return true
                }
                if (leadingSpaces <= MAX_TOP_LEVEL_INDENT && isLinkReferenceDefinition(markdown, contentStart, contentEnd)) {
                    return false
                }
                processNormalLine(lineStart, contentStart, contentEnd, leadingSpaces, hasNewline)
                return true
            }

            private fun measureIndent(lineStart: Int, contentEnd: Int): Pair<Int, Int> {
                var pos = lineStart
                var spaces = 0
                while (pos < contentEnd) {
                    when (markdown[pos]) {
                        ' ' -> spaces++
                        '\t' -> spaces += TAB_WIDTH
                        else -> break
                    }
                    pos++
                }
                return pos to spaces
            }

            private fun processFencedLine(
                contentStart: Int,
                contentEnd: Int,
                leadingSpaces: Int,
                hasNewline: Boolean,
                nextLineStart: Int,
            ) {
                val allowedIndent = inList || leadingSpaces <= MAX_TOP_LEVEL_INDENT
                if (!allowedIndent || !isClosingFence(markdown, contentStart, contentEnd, fenceChar, fenceLen)) {
                    return
                }
                inFence = false
                if (!inList && hasNewline && nextLineStart < markdown.length) {
                    lastSafeSplit = nextLineStart
                }
                prevWasBlank = false
                sawNonBlank = true
            }

            private fun processNormalLine(
                lineStart: Int,
                contentStart: Int,
                contentEnd: Int,
                leadingSpaces: Int,
                hasNewline: Boolean,
            ) {
                val startsListItem = isListItemStart(markdown, contentStart, contentEnd)
                // Only freeze a preceding block once the deciding line is complete (`\n` arrived):
                // a partial line like `"2"` may still become `"2. item"` on the next delta.
                if (prevWasBlank && sawNonBlank && hasNewline) {
                    advanceSplitAfterBlank(lineStart, leadingSpaces, startsListItem)
                }
                val openingFence = if (inList || leadingSpaces <= MAX_TOP_LEVEL_INDENT) {
                    parseOpeningFence(markdown, contentStart, contentEnd)
                } else {
                    0
                }
                if (openingFence != 0) {
                    inFence = true
                    fenceChar = markdown[contentStart]
                    fenceLen = openingFence
                } else if (!inList && leadingSpaces <= MAX_TOP_LEVEL_INDENT && startsListItem) {
                    inList = true
                }
                prevWasBlank = false
                sawNonBlank = true
            }

            private fun advanceSplitAfterBlank(lineStart: Int, leadingSpaces: Int, startsListItem: Boolean) {
                if (!inList) {
                    lastSafeSplit = lineStart
                } else if (leadingSpaces == 0 && !startsListItem) {
                    inList = false
                    lastSafeSplit = lineStart
                }
            }
        }

        /** Returns fence length (>= 3) if `[start, end)` opens a fenced code block, else 0. */
        private fun parseOpeningFence(text: String, start: Int, end: Int): Int {
            val ch = text[start]
            if (ch != '`' && ch != '~') return 0
            var i = start
            while (i < end && text[i] == ch) i++
            val count = i - start
            if (count < MIN_FENCE_LEN) return 0
            if (ch == '`' && hasCharInRange(text, i, end, '`')) return 0
            return count
        }

        private fun hasCharInRange(text: String, start: Int, end: Int, target: Char): Boolean {
            for (i in start until end) {
                if (text[i] == target) return true
            }
            return false
        }

        private fun isClosingFence(
            text: String,
            start: Int,
            end: Int,
            fenceChar: Char,
            fenceLen: Int,
        ): Boolean {
            if (text[start] != fenceChar) return false
            var i = start
            while (i < end && text[i] == fenceChar) i++
            if (i - start < fenceLen) return false
            while (i < end) {
                if (text[i] != ' ' && text[i] != '\t') return false
                i++
            }
            return true
        }

        private fun isListItemStart(text: String, start: Int, end: Int): Boolean {
            if (isUnorderedListStart(text, start, end)) return true
            return isOrderedListStart(text, start, end)
        }

        private fun isUnorderedListStart(text: String, start: Int, end: Int): Boolean {
            if (text[start] !in UNORDERED_MARKERS) return false
            return isDelimiterEnd(text, start + 1, end)
        }

        private fun isOrderedListStart(text: String, start: Int, end: Int): Boolean {
            if (text[start] !in '0'..'9') return false
            var i = start
            while (i < end && text[i] in '0'..'9' && i - start < MAX_ORDERED_DIGITS) i++
            if (i >= end) return false
            val delim = text[i]
            if (delim != '.' && delim != ')') return false
            return isDelimiterEnd(text, i + 1, end)
        }

        private fun isDelimiterEnd(text: String, index: Int, end: Int): Boolean = index == end || text[index] == ' ' || text[index] == '\t'

        private val UNORDERED_MARKERS = setOf('-', '*', '+', '•')

        private fun isLinkReferenceDefinition(text: String, start: Int, end: Int): Boolean {
            if (text[start] != '[') return false
            var i = start + 1
            var hasNonSpace = false
            while (i < end) {
                val c = text[i]
                if (c == '\\' && i + 1 < end) {
                    hasNonSpace = true
                    i += 2
                    continue
                }
                if (c == '[') return false
                if (c == ']') {
                    return hasNonSpace && i + 1 < end && text[i + 1] == ':'
                }
                if (c != ' ' && c != '\t') hasNonSpace = true
                i++
            }
            return false
        }
    }
}
