package com.example.data

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

/**
 * The root views of every window this app shows, bottom-most first: the Activity, then dialogs,
 * bottom sheets and popup menus in the order they were opened.
 */
fun appWindowRoots(activity: Activity): List<View> {
    val decorView = activity.window.decorView
    val all: List<View> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WindowInspector.getGlobalWindowViews()
        } else {
            // minSdk 24..28: the same list, read from WindowManagerGlobal.
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val field = global.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (field.get(instance) as List<View>).toList()
        }
    }.getOrDefault(emptyList())
    val overlays = all.filter {
        it !== decorView && it.isAttachedToWindow && it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 &&
            // Another Activity's window is not part of this screen.
            (it.layoutParams as? WindowManager.LayoutParams)?.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION
    }
    return listOf(decorView) + overlays
}

/**
 * [AppUiDriver] for the foreground Activity. Needs no permission: it reads the Compose semantics
 * tree and hands touch and key events to the app's own windows, so it can only ever act on this app.
 * The system keyboard and system dialogs (permission prompts, share sheets) are out of reach.
 */
class AndroidUiDriver(
    private val activity: () -> Activity?,
    private val uiContext: CoroutineContext = Dispatchers.Main,
) : AppUiDriver {

    /** The app's windows and the mapping between screen pixels and screenshot pixels. */
    private class Screen(val activity: Activity) {
        val decorView: View = activity.window.decorView
        val origin = IntArray(2).also { decorView.getLocationOnScreen(it) }
        val scale = screenshotScale(decorView.width)

        // Same arithmetic as the screenshot's downscaling, so both agree to the pixel.
        val width = minOf(decorView.width, SCREENSHOT_MAX_WIDTH)
        val height = if (decorView.width > SCREENSHOT_MAX_WIDTH) {
            (decorView.height.toFloat() * SCREENSHOT_MAX_WIDTH / decorView.width).toInt().coerceAtLeast(1)
        } else {
            decorView.height
        }
        val roots: List<View> = appWindowRoots(activity)

        fun toShotX(screenX: Float): Int = ((screenX - origin[0]) * scale).roundToInt()
        fun toShotY(screenY: Float): Int = ((screenY - origin[1]) * scale).roundToInt()
        fun toScreenX(shotX: Float): Float = shotX / scale + origin[0]
        fun toScreenY(shotY: Float): Float = shotY / scale + origin[1]

        fun flags(root: View): Int = (root.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0

        /** A window that cannot take focus is never modal (FLAG_NOT_FOCUSABLE implies FLAG_NOT_TOUCH_MODAL). */
        fun isModal(index: Int): Boolean = index > 0 && flags(roots[index]) and NOT_MODAL_FLAGS == 0

        fun contains(root: View, screenX: Float, screenY: Float): Boolean {
            val loc = IntArray(2).also { root.getLocationOnScreen(it) }
            return screenX >= loc[0] && screenX < loc[0] + root.width && screenY >= loc[1] && screenY < loc[1] + root.height
        }

        fun window(index: Int): UiWindow {
            val root = roots[index]
            val loc = IntArray(2).also { root.getLocationOnScreen(it) }
            val type = (root.layoutParams as? WindowManager.LayoutParams)?.type
            return UiWindow(
                index = index,
                type = when {
                    index == 0 -> "activity"

                    type != null && type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                        type <= WindowManager.LayoutParams.LAST_SUB_WINDOW -> "popup"

                    else -> "dialog"
                },
                left = toShotX(loc[0].toFloat()),
                top = toShotY(loc[1].toFloat()),
                right = toShotX((loc[0] + root.width).toFloat()),
                bottom = toShotY((loc[1] + root.height).toFloat()),
                modal = isModal(index),
            )
        }
    }

    private fun screen(): Screen {
        val current = activity()
            ?: throw AppStateCommandException("The app is in the background; it must be on screen to read or control its UI.")
        val decorView = current.window?.decorView
        if (decorView == null || decorView.width <= 0 || decorView.height <= 0) {
            throw AppStateCommandException("The app window is not laid out yet; try again.")
        }
        return Screen(current)
    }

    override suspend fun snapshot(): UiSnapshot = withContext(uiContext) {
        val screen = screen()
        val nodes = mutableListOf<UiNode>()
        // Top-most window first, so a selector prefers what is in front.
        for (index in screen.roots.indices.reversed()) {
            val root = screen.roots[index]
            val loc = IntArray(2).also { root.getLocationOnScreen(it) }
            for (composeRoot in composeRoots(root)) {
                for (node in composeRoot.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)) {
                    toUiNode(node, index, loc, screen)?.let(nodes::add)
                }
            }
        }
        UiSnapshot(
            width = screen.width,
            height = screen.height,
            windows = screen.roots.indices.map(screen::window),
            nodes = nodes,
        )
    }

    private fun composeRoots(view: View): List<ViewRootForTest> {
        val out = mutableListOf<ViewRootForTest>()
        fun visit(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is ViewRootForTest) {
                out += v
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
        }
        visit(view)
        return out
    }

    private fun toUiNode(node: SemanticsNode, window: Int, windowLoc: IntArray, screen: Screen): UiNode? {
        val config = node.config
        val bounds = node.boundsInWindow
        if (bounds.width <= 0f || bounds.height <= 0f) return null
        val password = config.contains(SemanticsProperties.Password)
        val editable = config.contains(SemanticsActions.SetText)
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.clean()
        val value = if (password) null else config.getOrNull(SemanticsProperties.EditableText)?.text.clean()
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").clean()
        val tag = config.getOrNull(SemanticsProperties.TestTag)
        val clickable = config.contains(SemanticsActions.OnClick)
        val longClickable = config.contains(SemanticsActions.OnLongClick)
        val scrollable = config.contains(SemanticsActions.ScrollBy)
        if (text == null && value == null && description == null && tag == null &&
            !clickable && !longClickable && !scrollable && !editable
        ) {
            return null
        }
        return UiNode(
            id = node.id,
            window = window,
            left = screen.toShotX(bounds.left + windowLoc[0]),
            top = screen.toShotY(bounds.top + windowLoc[1]),
            right = screen.toShotX(bounds.right + windowLoc[0]),
            bottom = screen.toShotY(bounds.bottom + windowLoc[1]),
            text = text,
            value = value,
            description = description,
            tag = tag,
            role = config.getOrNull(SemanticsProperties.Role)?.toString(),
            clickable = clickable,
            longClickable = longClickable,
            scrollable = scrollable,
            editable = editable,
            focused = config.getOrNull(SemanticsProperties.Focused) == true,
            enabled = !config.contains(SemanticsProperties.Disabled),
            selected = config.getOrNull(SemanticsProperties.Selected),
            toggle = when (config.getOrNull(SemanticsProperties.ToggleableState)) {
                ToggleableState.On -> "on"
                ToggleableState.Off -> "off"
                ToggleableState.Indeterminate -> "indeterminate"
                null -> null
            },
            password = password,
        )
    }

    private fun String?.clean(): String? = this?.takeIf { it.isNotBlank() }?.let { sanitizeDebugText(it).take(MAX_TEXT_CHARS) }

    override suspend fun stroke(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, holdMs: Long): UiWindow = withContext(uiContext) {
        val screen = screen()
        val startX = screen.toScreenX(x1.toFloat())
        val startY = screen.toScreenY(y1.toFloat())
        val endX = screen.toScreenX(x2.toFloat())
        val endY = screen.toScreenY(y2.toFloat())

        // Like the system: the top-most window under the finger gets the stroke, except that a
        // modal window in front takes it even when the finger is outside (that is how tapping
        // next to a dialog or menu dismisses it).
        var target = 0
        val outsideWatchers = mutableListOf<View>()
        for (index in screen.roots.indices.reversed()) {
            val root = screen.roots[index]
            val flags = screen.flags(root)
            if (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) continue
            if (index == 0 || screen.contains(root, startX, startY) || screen.isModal(index)) {
                target = index
                break
            }
            if (flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0) outsideWatchers += root
        }
        val root = screen.roots[target]
        val window = screen.window(target)

        val downTime = SystemClock.uptimeMillis()
        for (watcher in outsideWatchers) {
            send(watcher, downTime, downTime, MotionEvent.ACTION_OUTSIDE, startX, startY)
        }
        send(root, downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY)
        try {
            val moves = startX != endX || startY != endY
            if (moves) {
                val steps = (durationMs / FRAME_MS).toInt().coerceAtLeast(2)
                for (step in 1..steps) {
                    delay(FRAME_MS)
                    val f = step.toFloat() / steps
                    send(
                        root,
                        downTime,
                        SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE,
                        startX + (endX - startX) * f,
                        startY + (endY - startY) * f,
                    )
                }
                // Resting on the end point brings the finger's velocity to zero: no fling.
                var held = 0L
                while (held < holdMs) {
                    delay(FRAME_MS)
                    held += FRAME_MS
                    send(root, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, endX, endY)
                }
            } else {
                delay(durationMs)
            }
            send(root, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, endY)
        } catch (e: Throwable) {
            // Never leave a finger down.
            send(root, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, endX, endY)
            throw e
        }
        window
    }

    /** Dispatches one touch event at screen position ([screenX], [screenY]) to the window of [root]. */
    private fun send(root: View, downTime: Long, eventTime: Long, action: Int, screenX: Float, screenY: Float) {
        if (!root.isAttachedToWindow) return // The window closed mid-stroke (e.g. a menu on finger-down).
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = screenX
                y = screenY
                pressure = 1f
                size = 1f
            },
        )
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, properties, coords,
            0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            // Built in screen coordinates and then offset, so rawX/rawY stay correct.
            val loc = IntArray(2).also { root.getLocationOnScreen(it) }
            event.offsetLocation(-loc[0].toFloat(), -loc[1].toFloat())
            root.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun findNode(screen: Screen, nodeId: Int): SemanticsNode {
        for (root in screen.roots.asReversed()) {
            for (composeRoot in composeRoots(root)) {
                composeRoot.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                    .firstOrNull { it.id == nodeId }
                    ?.let { return it }
            }
        }
        throw AppStateCommandException("That element is no longer on screen.")
    }

    override suspend fun setText(nodeId: Int, text: String, replace: Boolean) = withContext(uiContext) {
        val config = findNode(screen(), nodeId).config
        config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke()
        val insert = config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action
        val set = config.getOrNull(SemanticsActions.SetText)?.action
        val done = when {
            !replace && insert != null -> insert(AnnotatedString(text))

            set != null -> {
                val existing = if (replace) "" else config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
                set(AnnotatedString(existing + text))
            }

            else -> throw AppStateCommandException("That element is not a text field.")
        }
        if (!done) throw AppStateCommandException("The text field did not accept the text (is it read-only or disabled?).")
    }

    override suspend fun imeAction(nodeId: Int) = withContext(uiContext) {
        val screen = screen()
        val action = findNode(screen, nodeId).config.getOrNull(SemanticsActions.OnImeAction)?.action
        if (action == null || !action()) {
            // No keyboard action on this field: press Enter instead.
            sendKey(screen.roots.last { screen.flags(it) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0 }, KeyEvent.KEYCODE_ENTER)
        }
    }

    override suspend fun pressBack(): BackOutcome = withContext(uiContext) {
        val screen = screen()
        val decorView = screen.decorView
        // With the keyboard up, Back only puts the keyboard away.
        val keyboardShown = runCatching {
            ViewCompat.getRootWindowInsets(decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }.getOrDefault(false)
        if (keyboardShown) {
            WindowCompat.getInsetsController(screen.activity.window, decorView).hide(WindowInsetsCompat.Type.ime())
            return@withContext BackOutcome.KEYBOARD_HIDDEN
        }
        // Back goes to the focused window: the top-most one that takes focus.
        val top = screen.roots.last { it === decorView || screen.flags(it) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0 }
        if (!sendKey(top, KeyEvent.KEYCODE_BACK)) {
            // Windows that opted in to predictive back ignore the key event; ask their dispatcher.
            val dispatcher = top.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
                ?: (screen.activity as? ComponentActivity)?.onBackPressedDispatcher
            if (dispatcher != null) {
                dispatcher.onBackPressed()
            } else {
                @Suppress("DEPRECATION")
                screen.activity.onBackPressed()
            }
        }
        BackOutcome.HANDLED
    }

    /**
     * Sends a key press and release to the window of [root]; true if the release was handled.
     * (Back acts on release, and an Activity claims the press of Back even when it then ignores it.)
     */
    private fun sendKey(root: View, keyCode: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        root.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        return root.isAttachedToWindow &&
            root.dispatchKeyEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    private companion object {
        const val NOT_MODAL_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        const val FRAME_MS = 16L
        const val MAX_TEXT_CHARS = 500
    }
}
