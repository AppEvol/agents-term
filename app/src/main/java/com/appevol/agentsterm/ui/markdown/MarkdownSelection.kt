package com.appevol.agentsterm.ui.markdown

import android.content.ClipData
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard

/**
 * A [SelectionContainer] whose "Copy" leaves out the characters that exist only for layout, so
 * copied code has no stray padding or invisible break hints in it.
 */
@Composable
internal fun MarkdownSelectionContainer(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val cleaning = remember(clipboard) {
        object : Clipboard {
            override val nativeClipboard get() = clipboard.nativeClipboard

            override suspend fun getClipEntry(): ClipEntry? = clipboard.getClipEntry()

            override suspend fun setClipEntry(clipEntry: ClipEntry?) = clipboard.setClipEntry(clipEntry?.let(::withoutLayoutOnlyChars))
        }
    }
    CompositionLocalProvider(LocalClipboard provides cleaning) {
        SelectionContainer(content = content)
    }
}

private fun withoutLayoutOnlyChars(entry: ClipEntry): ClipEntry {
    val clip = entry.clipData
    val text = clip.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text ?: return entry
    return ClipEntry(ClipData.newPlainText(clip.description.label, withoutLayoutOnlyChars(text.toString())))
}
