package com.example.data

import kotlin.math.roundToInt

/**
 * Remote UI control: lets agents on the VM read the on-screen UI tree and drive the app with taps,
 * swipes, text entry and Back, so they can act as QA testers.
 *
 * Every coordinate crossing the wire is in **screenshot pixels**: the space of the PNG returned by
 * the `screenshot` command (the app window, downscaled to at most [SCREENSHOT_MAX_WIDTH] wide).
 * An agent can therefore read a position off a screenshot, or take a node's bounds from `ui`, and
 * pass it straight to `tap`/`swipe`.
 *
 * This file is plain Kotlin (unit-testable on the JVM); [AppUiDriver] hides the Android parts.
 */

const val SCREENSHOT_MAX_WIDTH = 1080

/** Factor from window pixels to screenshot pixels for a window [windowWidth] pixels wide. */
fun screenshotScale(windowWidth: Int): Float =
    if (windowWidth > SCREENSHOT_MAX_WIDTH) SCREENSHOT_MAX_WIDTH.toFloat() / windowWidth else 1f

/** One of the app's windows (the Activity, a dialog/bottom sheet, or a popup menu), bottom-most first. */
data class UiWindow(
    val index: Int,
    /** "activity", "dialog" or "popup". */
    val type: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** True if this window takes the taps that land outside of it (a dialog or a focused popup). */
    val modal: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "index" to index,
        "type" to type,
        "bounds" to listOf(left, top, right, bottom),
        "modal" to modal,
    )
}

/** One element of the merged semantics tree (what a screen reader would see). */
data class UiNode(
    val id: Int,
    val window: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String? = null,
    /** Current content of a text field. Null for anything else, and for password fields. */
    val value: String? = null,
    val description: String? = null,
    val tag: String? = null,
    val role: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean? = null,
    /** "on", "off" or "indeterminate" for switches and checkboxes. */
    val toggle: String? = null,
    val password: Boolean = false,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = (right - left).toLong() * (bottom - top)

    /** Compact JSON form: false flags and null fields are left out. */
    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        put("window", window)
        put("bounds", listOf(left, top, right, bottom))
        put("center", listOf(centerX, centerY))
        text?.let { put("text", it) }
        value?.let { put("value", it) }
        description?.let { put("description", it) }
        tag?.let { put("tag", it) }
        role?.let { put("role", it) }
        if (clickable) put("clickable", true)
        if (longClickable) put("longClickable", true)
        if (scrollable) put("scrollable", true)
        if (editable) put("editable", true)
        if (focused) put("focused", true)
        if (!enabled) put("enabled", false)
        selected?.let { put("selected", it) }
        toggle?.let { put("toggle", it) }
        if (password) put("password", true)
    }
}

data class UiSnapshot(
    /** Size of the screenshot space. */
    val width: Int,
    val height: Int,
    val windows: List<UiWindow>,
    /** Nodes of the top-most window first, each window in traversal (roughly top-to-bottom) order. */
    val nodes: List<UiNode>,
)

/** Picks nodes by test tag and/or text. */
data class UiSelector(
    val tag: String? = null,
    val text: String? = null,
    val exact: Boolean = false,
    val index: Int? = null,
) {
    fun matches(node: UiNode): Boolean {
        if (tag != null && node.tag != tag) return false
        if (text != null) {
            val candidates = listOfNotNull(node.text, node.value, node.description)
            val hit = if (exact) {
                candidates.any { it == text }
            } else {
                candidates.any { it.contains(text, ignoreCase = true) }
            }
            if (!hit) return false
        }
        return true
    }

    fun describe(): String = listOfNotNull(
        tag?.let { "tag '$it'" },
        text?.let { (if (exact) "exact text '" else "text '") + it + "'" },
        index?.let { "index $it" },
    ).joinToString(", ")

    /** The node to act on: the [index]-th match, by default the first one. */
    fun find(snapshot: UiSnapshot): UiNode {
        val matches = snapshot.nodes.filter(::matches)
        if (matches.isEmpty()) {
            throw AppStateCommandException(
                "No on-screen element matches ${describe()}. Run 'ui' to list what is on screen; " +
                    "elements scrolled out of view are not listed (use 'scroll')."
            )
        }
        val i = index ?: 0
        return matches.getOrNull(i) ?: throw AppStateCommandException(
            "Only ${matches.size} element(s) match ${describe()}; index must be below ${matches.size}."
        )
    }

    companion object {
        /** Null when [args] names no element. */
        fun fromArgs(args: Map<String, Any?>): UiSelector? {
            val tag = (args["tag"] as? String)?.takeIf { it.isNotEmpty() }
            val text = (args["text"] as? String)?.takeIf { it.isNotEmpty() }
            if (tag == null && text == null) return null
            val index = (args["index"] as? Number)?.toInt()
            if (index != null && index < 0) throw AppStateCommandException("index must be 0 or greater.")
            return UiSelector(tag = tag, text = text, exact = args["exact"] == true, index = index)
        }
    }
}

/** What actually happened on a Back press. */
enum class BackOutcome { KEYBOARD_HIDDEN, HANDLED }

/** The Android side of UI control. Coordinates are screenshot pixels. */
interface AppUiDriver {
    /** Throws [AppStateCommandException] when the app is not on screen. */
    suspend fun snapshot(): UiSnapshot

    /**
     * One finger stroke: down at (x1, y1), move to (x2, y2) over [durationMs], rest there for
     * [holdMs] (so a swipe does not fling), then up. A tap is a stroke that does not move.
     * Returns the window that received it.
     */
    suspend fun stroke(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, holdMs: Long): UiWindow

    /** Replaces ([replace]) or inserts at the cursor the text of the editable node [nodeId]. */
    suspend fun setText(nodeId: Int, text: String, replace: Boolean)

    /** Runs the keyboard action (Send, Done, Search…) of the text field [nodeId]. */
    suspend fun imeAction(nodeId: Int)

    suspend fun pressBack(): BackOutcome
}

/**
 * Turns `ui` / `tap` / `long_press` / `swipe` / `scroll` / `type` / `key` commands into
 * [AppUiDriver] calls. [onControl] runs before every command that changes something.
 */
class AppUiController(
    private val driver: AppUiDriver,
    private val onControl: (command: String) -> Unit = {},
) {
    suspend fun handle(command: String, args: Map<String, Any?>): Map<String, Any?> {
        if (command in CONTROL_COMMANDS) onControl(command)
        return when (command) {
            "ui" -> ui(args)
            "tap" -> press(args, durationMs = TAP_MS, action = "tap")
            "long_press" -> press(
                args,
                durationMs = args.longArg("durationMs", DEFAULT_LONG_PRESS_MS).coerceIn(300L, 5_000L),
                action = "long_press",
            )
            "swipe" -> swipe(args)
            "scroll" -> scroll(args)
            "type" -> type(args)
            "key" -> key(args)
            else -> throw AppStateCommandException("Unknown UI command '$command'.")
        }
    }

    private suspend fun ui(args: Map<String, Any?>): Map<String, Any?> {
        val snap = driver.snapshot()
        val selector = UiSelector.fromArgs(args)
        var nodes = if (selector == null) snap.nodes else snap.nodes.filter(selector::matches)
        if (args["interactiveOnly"] == true) {
            nodes = nodes.filter { it.clickable || it.longClickable || it.scrollable || it.editable }
        }
        return mapOf(
            "width" to snap.width,
            "height" to snap.height,
            "windows" to snap.windows.map { it.toMap() },
            "nodeCount" to nodes.size,
            "nodes" to nodes.map { it.toMap() },
        )
    }

    private suspend fun press(args: Map<String, Any?>, durationMs: Long, action: String): Map<String, Any?> {
        val snap = driver.snapshot()
        val selector = UiSelector.fromArgs(args)
        val node = selector?.find(snap)
        if (node != null && !node.enabled) {
            throw AppStateCommandException("The element matching ${selector.describe()} is disabled.")
        }
        val (x, y) = if (node != null) {
            node.centerX to node.centerY
        } else {
            val px = args.intArgOrNull("x")
            val py = args.intArgOrNull("y")
            if (px == null || py == null) {
                throw AppStateCommandException("$action needs a target: x and y (screenshot pixels), tag, or text.")
            }
            px to py
        }
        requireOnScreen(snap, x, y)
        val window = driver.stroke(x, y, x, y, durationMs = durationMs, holdMs = 0L)
        return buildMap {
            put("action", action)
            put("x", x)
            put("y", y)
            put("window", window.toMap())
            node?.let { put("node", it.toMap()) }
        }
    }

    private suspend fun swipe(args: Map<String, Any?>): Map<String, Any?> {
        val snap = driver.snapshot()
        val coords = listOf("x1", "y1", "x2", "y2").map {
            args.intArgOrNull(it) ?: throw AppStateCommandException("swipe needs x1, y1, x2 and y2 (screenshot pixels).")
        }
        val (x1, y1, x2, y2) = coords
        requireOnScreen(snap, x1, y1)
        requireOnScreen(snap, x2, y2)
        val durationMs = args.longArg("durationMs", DEFAULT_SWIPE_MS).coerceIn(50L, 10_000L)
        val holdMs = args.longArg("holdMs", 0L).coerceIn(0L, 5_000L)
        val window = driver.stroke(x1, y1, x2, y2, durationMs = durationMs, holdMs = holdMs)
        return mapOf(
            "action" to "swipe",
            "from" to listOf(x1, y1),
            "to" to listOf(x2, y2),
            "durationMs" to durationMs,
            "holdMs" to holdMs,
            "window" to window.toMap(),
        )
    }

    /**
     * Scrolls a list by dragging it (no fling, so the distance is predictable). `direction` names
     * the content to reveal: "down" shows what is further down, i.e. the finger moves up.
     */
    private suspend fun scroll(args: Map<String, Any?>): Map<String, Any?> {
        val snap = driver.snapshot()
        val direction = (args["direction"] as? String)?.lowercase() ?: "down"
        if (direction !in DIRECTIONS) {
            throw AppStateCommandException("direction must be one of ${DIRECTIONS.joinToString(", ")}.")
        }
        val amount = ((args["amount"] as? Number)?.toDouble() ?: DEFAULT_SCROLL_AMOUNT).coerceIn(0.05, 0.9)
        val selector = UiSelector.fromArgs(args)
        val top = snap.windows.lastOrNull()?.index
        val container = selector?.find(snap)
            ?: snap.nodes.filter { it.scrollable && it.window == top }.maxByOrNull { it.area }
            ?: snap.nodes.filter { it.scrollable }.maxByOrNull { it.area }
        val left = container?.left ?: 0
        val topEdge = container?.top ?: 0
        val right = container?.right ?: snap.width
        val bottom = container?.bottom ?: snap.height
        val cx = (left + right) / 2
        val cy = (topEdge + bottom) / 2
        val dx = ((right - left) * amount / 2).roundToInt()
        val dy = ((bottom - topEdge) * amount / 2).roundToInt()
        val (x1, y1, x2, y2) = when (direction) {
            "down" -> listOf(cx, cy + dy, cx, cy - dy)
            "up" -> listOf(cx, cy - dy, cx, cy + dy)
            "right" -> listOf(cx + dx, cy, cx - dx, cy)
            else -> listOf(cx - dx, cy, cx + dx, cy)
        }
        val window = driver.stroke(x1, y1, x2, y2, durationMs = SCROLL_DRAG_MS, holdMs = SCROLL_HOLD_MS)
        return buildMap {
            put("action", "scroll")
            put("direction", direction)
            put("from", listOf(x1, y1))
            put("to", listOf(x2, y2))
            put("window", window.toMap())
            container?.let { put("node", it.toMap()) }
        }
    }

    private suspend fun type(args: Map<String, Any?>): Map<String, Any?> {
        // "text" is the text to enter here, so the field is picked by tag or by "field" (its label/content).
        val text = args["text"] as? String ?: throw AppStateCommandException("type needs 'text': the text to enter.")
        val snap = driver.snapshot()
        val selector = UiSelector.fromArgs(
            mapOf("tag" to args["tag"], "text" to args["field"], "exact" to args["exact"], "index" to args["index"])
        )
        val node = if (selector != null) {
            selector.find(snap)
        } else {
            snap.nodes.firstOrNull { it.editable && it.focused }
                ?: snap.nodes.filter { it.editable }.singleOrNull()
                ?: throw AppStateCommandException(
                    "No text field has focus. Tap one first, or name it with tag or field."
                )
        }
        if (!node.editable) {
            throw AppStateCommandException("The element matching ${selector?.describe()} is not a text field.")
        }
        if (!node.enabled) throw AppStateCommandException("That text field is disabled.")
        val replace = args["replace"] == true
        driver.setText(node.id, text, replace = replace)
        val submit = args["submit"] == true
        if (submit) driver.imeAction(node.id)
        return mapOf(
            "action" to "type",
            "chars" to text.length,
            "replace" to replace,
            "submitted" to submit,
            "node" to node.toMap(),
        )
    }

    private suspend fun key(args: Map<String, Any?>): Map<String, Any?> {
        return when (val key = (args["key"] as? String)?.lowercase()) {
            "back" -> mapOf("action" to "key", "key" to "back", "outcome" to driver.pressBack().name.lowercase())
            "enter" -> {
                val snap = driver.snapshot()
                val node = snap.nodes.firstOrNull { it.editable && it.focused }
                    ?: throw AppStateCommandException("No text field has focus; 'enter' runs the focused field's keyboard action.")
                driver.imeAction(node.id)
                mapOf("action" to "key", "key" to "enter", "node" to node.toMap())
            }
            else -> throw AppStateCommandException("Unknown key '${key ?: ""}'. Supported: ${KEYS.joinToString(", ")}.")
        }
    }

    private fun requireOnScreen(snap: UiSnapshot, x: Int, y: Int) {
        if (x < 0 || y < 0 || x >= snap.width || y >= snap.height) {
            throw AppStateCommandException(
                "($x, $y) is outside the app window. Coordinates are screenshot pixels: " +
                    "0..${snap.width - 1} by 0..${snap.height - 1}."
            )
        }
    }

    private fun Map<String, Any?>.intArgOrNull(name: String): Int? = (this[name] as? Number)?.toDouble()?.roundToInt()

    private fun Map<String, Any?>.longArg(name: String, default: Long): Long =
        (this[name] as? Number)?.toLong() ?: default

    companion object {
        /** Read-only; allowed with remote inspection alone. */
        val READ_COMMANDS = listOf("ui")

        /** Change something in the app; need the separate "control the app" opt-in. */
        val CONTROL_COMMANDS = listOf("tap", "long_press", "swipe", "scroll", "type", "key")

        val KEYS = listOf("back", "enter")
        private val DIRECTIONS = listOf("up", "down", "left", "right")

        const val TAP_MS = 60L
        const val DEFAULT_LONG_PRESS_MS = 800L
        const val DEFAULT_SWIPE_MS = 300L
        const val DEFAULT_SCROLL_AMOUNT = 0.6
        const val SCROLL_DRAG_MS = 400L
        const val SCROLL_HOLD_MS = 150L
    }
}
