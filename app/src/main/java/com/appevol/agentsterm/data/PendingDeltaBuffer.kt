package com.appevol.agentsterm.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Buffers high-frequency `message_delta` chunks per `(tabId, messageId)` in mutable
 * [StringBuilder]s so streaming avoids O(N^2) immutable string allocations and background-tab
 * updates do not emit [WorkspaceState] copies until flushed.
 *
 * Callers must hold their synchronization lock (for example `HerdrRepository.socketLock`) when
 * invoking methods on this buffer.
 */
internal class PendingDeltaBuffer(
    private val scope: CoroutineScope,
    private val flushIntervalMs: Long,
    private val clockMs: () -> Long,
) {
    private data class Key(val tabId: String, val messageId: String)

    private class Entry(
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        var hasText: Boolean = false,
        var hasThinking: Boolean = false,
    ) {
        fun append(textDelta: String?, thinkingDelta: String?) {
            if (textDelta != null) {
                text.append(textDelta)
                hasText = true
            }
            if (thinkingDelta != null) {
                thinking.append(thinkingDelta)
                hasThinking = true
            }
        }

        fun applyTo(msg: ChatMessage): ChatMessage? {
            val textChunk = if (hasText) text.toString() else null
            val thinkingChunk = if (hasThinking) thinking.toString() else null
            if (textChunk == null && thinkingChunk == null) return null
            return msg.copy(
                text = if (textChunk != null) msg.text + textChunk else msg.text,
                thinking = if (thinkingChunk != null) msg.thinking.orEmpty() + thinkingChunk else msg.thinking,
                segments = if (textChunk != null) appendTextDeltaToSegments(msg.segments, textChunk) else msg.segments,
            )
        }
    }

    private val entries = LinkedHashMap<Key, Entry>()
    private var flushJob: Job? = null
    private var hasFlushedActive = false
    private var lastActiveFlushMs = 0L

    /**
     * Records a `message_delta` frame. Active-tab deltas use leading+trailing throttling via
     * [flushIntervalMs]; background-tab deltas stay buffered without emitting state.
     */
    fun recordDelta(
        envelope: WsServerEnvelope,
        activeTabId: String?,
        flushNow: (tabId: String) -> Unit,
        onScheduledFlush: () -> Unit,
    ) {
        val tabId = envelope.tabId ?: return
        val messageId = envelope.messageId ?: return
        if (envelope.textDelta == null && envelope.thinkingDelta == null) return
        entries.getOrPut(Key(tabId, messageId)) { Entry() }.append(envelope.textDelta, envelope.thinkingDelta)
        if (tabId != activeTabId) return
        val now = clockMs()
        val elapsed = now - lastActiveFlushMs
        if (flushIntervalMs <= 0L || !hasFlushedActive || elapsed >= flushIntervalMs) {
            cancelScheduledFlush()
            flushNow(tabId)
            markActiveFlushed(now)
        } else if (flushJob?.isActive != true) {
            val delayMs = (flushIntervalMs - elapsed).coerceAtLeast(1L)
            flushJob = scope.launch {
                delay(delayMs)
                onScheduledFlush()
            }
        }
    }

    fun onScheduledFlushEntered() {
        flushJob = null
    }

    fun markActiveFlushed(nowMs: Long = clockMs()) {
        hasFlushedActive = true
        lastActiveFlushMs = nowMs
    }

    fun cancelScheduledFlush() {
        flushJob?.cancel()
        flushJob = null
    }

    fun clear(tabId: String? = null) {
        if (tabId == null) {
            entries.clear()
            cancelScheduledFlush()
            hasFlushedActive = false
            return
        }
        val iter = entries.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next().tabId == tabId) iter.remove()
        }
        if (entries.isEmpty()) {
            cancelScheduledFlush()
        }
    }

    fun prepareStateForEvent(current: WorkspaceState, envelope: WsServerEnvelope): WorkspaceState = when (envelope.type) {
        "snapshot" -> {
            clear()
            current
        }

        "tab_deleted" -> {
            envelope.tabId?.let { clear(tabId = it) }
            current
        }

        "tool_update", "message_updated", "message_created" -> {
            envelope.tabId?.let { applyTo(current, tabId = it, consume = true) } ?: current
        }

        "tab_updated" -> {
            val tab = envelope.tab
            if (tab != null && tab.status != "working") {
                applyTo(current, tabId = tab.id, consume = true)
            } else {
                current
            }
        }

        else -> current
    }

    fun applyTo(state: WorkspaceState, tabId: String? = null, consume: Boolean = true): WorkspaceState {
        if (entries.isEmpty()) return state
        val matched = collectMatching(tabId, consume)
        if (matched.isEmpty()) return state
        return applyEntriesToState(state, matched)
    }

    private fun collectMatching(tabId: String?, consume: Boolean): List<Pair<Key, Entry>> {
        val matched = mutableListOf<Pair<Key, Entry>>()
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (tabId == null || entry.key.tabId == tabId) {
                matched.add(entry.key to entry.value)
                if (consume) iter.remove()
            }
        }
        return matched
    }

    private fun applyEntriesToState(state: WorkspaceState, matched: List<Pair<Key, Entry>>): WorkspaceState {
        val byTab = matched.groupBy({ it.first.tabId }, { it.first.messageId to it.second })
        var updatedMap: MutableMap<String, List<ChatMessage>>? = null
        for ((targetTabId, msgDeltas) in byTab) {
            val existing = state.messagesByTab[targetTabId] ?: continue
            val updated = applyDeltasToMessages(existing, msgDeltas.toMap()) ?: continue
            val map = updatedMap ?: state.messagesByTab.toMutableMap().also { updatedMap = it }
            map[targetTabId] = updated
        }
        return updatedMap?.let { state.copy(messagesByTab = it) } ?: state
    }

    private fun applyDeltasToMessages(
        existing: List<ChatMessage>,
        deltaByMsg: Map<String, Entry>,
    ): List<ChatMessage>? {
        var changed = false
        val updated = existing.map { msg ->
            val applied = deltaByMsg[msg.id]?.applyTo(msg)
            if (applied != null) {
                changed = true
                applied
            } else {
                msg
            }
        }
        return if (changed) updated else null
    }
}
