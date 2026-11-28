package com.appevol.agentsterm.ui.markdown

import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import kotlinx.coroutines.CompletableDeferred
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

    /** Stands in for the floating Copy / Select all toolbar and exposes its active actions. */
    private class FakeTextContextMenuProvider : TextContextMenuProvider {
        private var dataProvider: TextContextMenuDataProvider? = null
        private var session: TextContextMenuSession? = null

        fun action(key: Any): (() -> Unit)? {
            val activeSession = session ?: return null
            val item = dataProvider?.data()?.components
                ?.filterIsInstance<TextContextMenuItem>()
                ?.firstOrNull { it.key == key }
                ?: return null
            return { item.onClick.invoke(activeSession) }
        }

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            val closed = CompletableDeferred<Unit>()
            val activeSession = object : TextContextMenuSession {
                override fun close() {
                    closed.complete(Unit)
                }
            }
            this.dataProvider = dataProvider
            this.session = activeSession
            try {
                closed.await()
            } finally {
                if (this.session === activeSession) {
                    this.session = null
                    this.dataProvider = null
                }
            }
        }
    }

    @Test
    fun copyingSelectedTextLeavesOutChipPaddingAndBreakHints() {
        lateinit var clipboard: FakeClipboard
        val toolbar = FakeTextContextMenuProvider()
        compose.setContent {
            val real = LocalClipboard.current
            clipboard = remember { FakeClipboard(real) }
            CompositionLocalProvider(
                LocalClipboard provides clipboard,
                LocalTextContextMenuToolbarProvider provides toolbar,
            ) {
                AgentsTermTheme {
                    MarkdownText(markdown = "Run `gradle :app:connectedDebugAndroidTest --stacktrace` now")
                }
            }
        }
        compose.onNodeWithText("Run", substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        val selectAll = compose.runOnUiThread { toolbar.action(TextContextMenuKeys.SelectAllKey) }
        assertNotNull("long press selects text and opens the toolbar", selectAll)
        compose.runOnUiThread { checkNotNull(selectAll).invoke() }
        compose.waitForIdle()
        compose.runOnUiThread { checkNotNull(toolbar.action(TextContextMenuKeys.CopyKey)).invoke() }
        compose.waitForIdle()
        assertEquals("Run gradle :app:connectedDebugAndroidTest --stacktrace now", clipboard.copied)
    }
}
