package com.appevol.agentsterm.ui.markdown

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.appevol.agentsterm.data.AppThemeId
import com.appevol.agentsterm.ui.theme.AgentsTermTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Golden image tests for the Markdown renderer.
 *
 * Each test renders one document from `src/test/resources/markdown-samples` with [MarkdownText]
 * alone (no app screen around it) at the width and pixel density of a popular phone, and compares
 * the pixels with the image checked in under `src/test/snapshots/markdown`.
 *
 * The images are drawn by Robolectric's native graphics, which is Android's real text and drawing
 * code running on the JVM, so they need no emulator and come out the same on every Linux machine.
 *
 * When a rendering change is intended, record new images and review them like code:
 *
 *     gradle :app:testDebugUnitTest --tests '*MarkdownGoldenTest*' -Pgoldens=record
 *
 * When a test fails, `app/build/outputs/roborazzi` has an image for each failure that shows the
 * golden, the new rendering and their difference side by side.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MarkdownGoldenTest(private val sample: String, private val viewport: Viewport) {
    /**
     * A phone screen to render at. Only the width, density and font scale change how Markdown is
     * laid out; the height is whatever the document needs.
     */
    enum class Viewport(
        val widthDp: Int,
        val dpi: Int,
        val darkTheme: Boolean = true,
        val fontScale: Float = 1f,
    ) {
        /** Small phones, and larger ones with Android's "Display size" turned up. */
        COMPACT_320(widthDp = 320, dpi = 320),

        /** Samsung Galaxy S and A series, the most common Android width. */
        GALAXY_360(widthDp = 360, dpi = 480),

        /** Google Pixel 8, 9 and 9a. */
        PIXEL_412(widthDp = 412, dpi = 420),
        PIXEL_412_LIGHT(widthDp = 412, dpi = 420, darkTheme = false),

        /** Android's "Font size" setting two steps up, on the Pixel. */
        PIXEL_412_LARGE_FONT(widthDp = 412, dpi = 420, fontScale = 1.3f),

        /** Large phones: Pixel Pro XL, Galaxy Ultra. */
        LARGE_448(widthDp = 448, dpi = 480),
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun matchesGolden() {
        // Taller than any sample, so the whole document is on screen and gets captured.
        RuntimeEnvironment.setQualifiers("w${viewport.widthDp}dp-h${WINDOW_HEIGHT_DP}dp-${viewport.dpi}dpi")
        RuntimeEnvironment.setFontScale(viewport.fontScale)
        val markdown = checkNotNull(javaClass.getResource("/markdown-samples/$sample.md")) { "no sample $sample" }.readText()

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AgentsTermTheme(theme = if (viewport.darkTheme) AppThemeId.MIDNIGHT else AppThemeId.DAYLIGHT) {
                        Box(
                            Modifier
                                .testTag(ROOT_TAG)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(16.dp),
                        ) {
                            MarkdownText(markdown = markdown)
                        }
                    }
                }
            }
            compose.onNodeWithTag(ROOT_TAG).captureRoboImage(
                File(GOLDEN_DIR, "${sample}__${viewport.name.lowercase()}.png"),
            )
        }
    }

    companion object {
        private const val ROOT_TAG = "markdown-golden-root"
        private const val GOLDEN_DIR = "src/test/snapshots/markdown"
        private const val WINDOW_HEIGHT_DP = 3000

        private val samples = listOf(
            "inline_styles",
            "inline_code",
            "headings",
            "lists",
            "code_blocks",
            "tables",
            "quotes_and_rules",
            "chat_reply",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} on {1}")
        fun parameters(): List<Array<Any>> = samples.flatMap { sample ->
            Viewport.entries.map { viewport -> arrayOf(sample, viewport) }
        }
    }
}
