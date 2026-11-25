package com.example.ui.markdown

import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.example.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * #39: selecting a message and tapping "Copy" must give the message's own text. The no-break
 * spaces that pad inline code chips and the zero-width break hints are layout only; in a copied
 * shell command they would be invisible and break it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [MarkdownCopyTest.NoMagnifier::class])
class MarkdownCopyTest {
    /**
     * Selecting text shows the platform magnifier, which draws to a surface that Robolectric does
     * not have. The magnifier is not under test, so it does nothing here.
     */
    @Implements(Magnifier::class)
    class NoMagnifier {
        // The parameters are unused, but the signature has to match the method it replaces.
        @Suppress("UnusedParameter")
        @Implementation
        fun show(sourceX: Float, sourceY: Float, magnifierX: Float, magnifierY: Float) = Unit

        @Implementation
        fun dismiss() = Unit

        @Implementation
        fun update() = Unit
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeClipboard(private val real: Clipboard) : Clipboard {
        var copied: String? = null
        override val nativeClipboard get() = real.nativeClipboard

        override suspend fun getClipEntry(): ClipEntry? = null

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            copied = clipEntry?.clipData?.getItemAt(0)?.text?.toString()
        }
    }

    /** Stands in for the floating Copy / Select all toolbar and remembers its Copy action. */
    private class FakeToolbar : TextToolbar {
        var onCopy: (() -> Unit)? = null
        var onSelectAll: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            onCopy = onCopyRequested
            onSelectAll = onSelectAllRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            status = TextToolbarStatus.Hidden
        }
    }

    @Test
    fun copyingSelectedTextLeavesOutChipPaddingAndBreakHints() {
        lateinit var clipboard: FakeClipboard
        val toolbar = FakeToolbar()
        compose.setContent {
            val real = LocalClipboard.current
            clipboard = remember { FakeClipboard(real) }
            CompositionLocalProvider(LocalClipboard provides clipboard, LocalTextToolbar provides toolbar) {
                HerdrTheme {
                    MarkdownText(markdown = "Run `gradle :app:connectedDebugAndroidTest --stacktrace` now")
                }
            }
        }
        compose.onNodeWithText("Run", substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        assertNotNull("long press selects text and opens the toolbar", toolbar.onSelectAll)
        compose.runOnUiThread { toolbar.onSelectAll?.invoke() }
        compose.waitForIdle()
        compose.runOnUiThread { checkNotNull(toolbar.onCopy).invoke() }
        compose.waitForIdle()
        assertEquals("Run gradle :app:connectedDebugAndroidTest --stacktrace now", clipboard.copied)
    }
}
