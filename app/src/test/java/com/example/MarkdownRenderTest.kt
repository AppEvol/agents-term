package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.example.ui.INLINE_CODE_TAG
import com.example.ui.MarkdownText
import com.example.ui.parseInlineStyles
import com.example.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Renders [MarkdownText] to real pixels (Robolectric native graphics) at phone width.
 *
 * Regression: inline code used `SpanStyle(background = …)`, which fills the full line height. Code
 * on consecutive lines fused into one solid slab, so plain text surrounded by code on three sides
 * ("should report", ". Then try" in the message below) looked like it sat in a dark cut-out box.
 * Code is now drawn as a separate rounded chip per line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-420dpi")
class MarkdownRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val message =
        "3. **Check from the VM:** `python3 app_state_cli/app_state_cli.py status` should report " +
            "`\"ready\": true`. Then try `state`, `logs` and `screenshot -o /tmp/s.png`.\n\n" +
            "The app itself also works against the old Node daemon if you skip step 1; only the " +
            "`app_state_cli` commands need the new daemon."

    private class Rendered(val bitmap: Bitmap, val background: Int, val codeBackground: Int)

    private fun render(darkTheme: Boolean): Rendered {
        var background = 0
        var codeBackground = 0
        compose.setContent {
            HerdrTheme(darkTheme = darkTheme) {
                background = MaterialTheme.colorScheme.background.toArgb()
                codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                Box(
                    Modifier
                        .testTag("root")
                        .width(411.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    MarkdownText(markdown = message)
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        val view = compose.activity.window.decorView
        val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(full)) }
        val offset = IntArray(2)
        compose.runOnUiThread {
            compose.activity.findViewById<android.view.View>(android.R.id.content).getLocationInWindow(offset)
        }
        val cropped = Bitmap.createBitmap(
            full,
            offset[0] + bounds.left.toInt(),
            offset[1] + bounds.top.toInt(),
            bounds.width.toInt().coerceAtMost(full.width - offset[0]),
            bounds.height.toInt().coerceAtMost(full.height - offset[1]),
        )
        // Set MARKDOWN_RENDER_DUMP=/some/dir to save the rendering for a visual check.
        System.getenv("MARKDOWN_RENDER_DUMP")?.let { dir ->
            FileOutputStream(File(dir, "markdown-${if (darkTheme) "dark" else "light"}.png")).use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        return Rendered(cropped, background, codeBackground)
    }

    private fun near(a: Int, b: Int, tolerance: Int = 6): Boolean = abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance

    /**
     * Heights of the vertical bands of rows that contain code background. Each text line with inline
     * code should be its own band, separated from the next by rows of pure page color.
     */
    private fun chipBandHeights(r: Rendered): List<Int> {
        val row = IntArray(r.bitmap.width)
        val bands = mutableListOf<Int>()
        var run = 0
        for (y in 0 until r.bitmap.height) {
            r.bitmap.getPixels(row, 0, r.bitmap.width, 0, y, r.bitmap.width, 1)
            if (row.count { near(it, r.codeBackground) } > 20) {
                run++
            } else if (run > 0) {
                bands.add(run)
                run = 0
            }
        }
        if (run > 0) bands.add(run)
        return bands
    }

    private fun assertWrappedCodeRendersAsSeparateChips(darkTheme: Boolean) {
        val r = render(darkTheme)
        val bands = chipBandHeights(r)
        val lineHeightPx = 22 * r.bitmap.width / 411f // list items use a 22sp line height
        // The list item wraps to 4 lines that all contain code, and the paragraph has 1 code line.
        // Fused backgrounds would give one tall band for the whole list item.
        assertEquals("one chip band per code line, got band heights $bands", 5, bands.size)
        assertTrue(
            "each chip must be shorter than a line so page color shows between lines: $bands",
            bands.all { it < lineHeightPx - 2 },
        )
    }

    @Test
    fun wrappedInlineCodeRendersAsSeparateChipsInDarkTheme() = assertWrappedCodeRendersAsSeparateChips(darkTheme = true)

    @Test
    fun wrappedInlineCodeRendersAsSeparateChipsInLightTheme() = assertWrappedCodeRendersAsSeparateChips(darkTheme = false)

    @Test
    fun inlineCodeIsAnnotatedAndCarriesNoBackgroundSpan() {
        val styled = parseInlineStyles("run `ls -la` then **bold** and `pwd`", Color.Blue)
        assertEquals("run  ls -la  then bold and  pwd ", styled.text)
        val code = styled.getStringAnnotations(INLINE_CODE_TAG, 0, styled.length)
        assertEquals(listOf(" ls -la ", " pwd "), code.map { styled.text.substring(it.start, it.end) })
        assertTrue(styled.spanStyles.none { it.item.background != Color.Unspecified })
    }
}
