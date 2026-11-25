package com.example.ui.markdown

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.HerdrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Inline content as an `AnnotatedString`: links (#12: `[label](url)` rendered as raw Markdown, and
 * web links were not tappable) and inline code (#39, #55).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownInlineTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val style = InlineTextStyle(codeColor = Color.Blue, linkColor = Color.Blue, linkHandler = null)

    private fun parse(text: String): AnnotatedString {
        val paragraph = MarkdownParser.parse(text).single() as MdBlock.Paragraph
        return buildInlineText(paragraph.content, style)
    }

    /** The text as the user reads and copies it, without padding and break hints. */
    private fun AnnotatedString.visible(): String = withoutLayoutOnlyChars(text)

    private data class Link(val text: String, val target: String)

    private fun AnnotatedString.visible(start: Int, end: Int): String = withoutLayoutOnlyChars(text.substring(start, end))

    private fun links(s: AnnotatedString): List<Link> = s.getLinkAnnotations(0, s.length).map {
        val target = when (val item = it.item) {
            is LinkAnnotation.Url -> item.url
            is LinkAnnotation.Clickable -> item.tag
            else -> error("unexpected link $item")
        }
        Link(s.visible(it.start, it.end), target)
    }

    @Test
    fun webLinkShowsOnlyItsLabel() {
        val s = parse("See [PR #11](https://github.com/nedn/agents-term/pull/11) for details.")
        assertEquals("See PR #11 for details.", s.text)
        assertEquals(listOf(Link("PR #11", "https://github.com/nedn/agents-term/pull/11")), links(s))
    }

    @Test
    fun fileLinkShowsOnlyItsLabelInsideParentheses() {
        val s = parse(
            "([AgentsTermE2ETest.kt](file:///home/me/projects/app/AgentsTermE2ETest.kt#L10-L20)) all passed.",
        )
        assertEquals("(AgentsTermE2ETest.kt) all passed.", s.text)
        assertEquals(listOf(Link("AgentsTermE2ETest.kt", FILE_LINK_TAG)), links(s))
    }

    @Test
    fun vmPathDropsSchemeFragmentAndDecodes() {
        assertEquals("/home/me/a b.kt", vmPathFromLink("file:///home/me/a%20b.kt#L10-L20"))
        assertEquals("src/Main.kt", vmPathFromLink("src/Main.kt"))
    }

    @Test
    fun bareUrlsAreLinkedWithoutTrailingPunctuation() {
        val s = parse("Open https://example.com/a_(b). Or (http://x.io/y), done")
        assertEquals("Open https://example.com/a_(b). Or (http://x.io/y), done", s.text)
        assertEquals(
            listOf(
                Link("https://example.com/a_(b)", "https://example.com/a_(b)"),
                Link("http://x.io/y", "http://x.io/y"),
            ),
            links(s),
        )
    }

    @Test
    fun linksNestWithCodeAndBold() {
        val s = parse("**[PR #11](https://e.com/11)** and [`file.kt`](file:///a/file.kt)")
        assertEquals("PR #11 and file.kt", s.visible())
        assertEquals(
            listOf(Link("PR #11", "https://e.com/11"), Link("file.kt", FILE_LINK_TAG)),
            links(s),
        )
        val bold = s.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("PR #11", s.text.substring(bold.start, bold.end))
        val code = s.getStringAnnotations(INLINE_CODE_TAG, 0, s.length).single()
        assertEquals("file.kt", s.visible(code.start, code.end))
    }

    /** #39: the chip padding was literal spaces, which showed as gaps and ended up in copied text. */
    @Test
    fun inlineCodeIsAnnotatedAndCopiesWithoutItsPadding() {
        val s = parse("run `ls -la` then **bold** and `pwd`.")
        assertEquals("run ls -la then bold and pwd.", s.visible())
        val code = s.getStringAnnotations(INLINE_CODE_TAG, 0, s.length)
        assertEquals(listOf("ls -la", "pwd"), code.map { s.visible(it.start, it.end) })
        // The chip's padding is part of the annotated range, so the chip is drawn around it.
        assertTrue(code.all { s.text[it.start] == '\u00A0' && s.text[it.end - 1] == '\u00A0' })
        // A no-break space the author wrote is not padding and stays.
        assertEquals("10\u00A0km and x", parse("10\u00A0km and `x`").visible())
        // The chip is drawn from the annotation. A background span would fill the whole line height.
        assertTrue(s.spanStyles.none { it.item.background != Color.Unspecified })
    }

    /** #55: a long identifier broke wherever the line ended, mid-word. */
    @Test
    fun longCodeWordsGetBreakHintsAtSeparatorsAndCamelCaseHumps() {
        fun pieces(word: String): List<String> {
            val points = listOf(0) + codeBreakPoints(word) + word.length
            return points.zipWithNext { a, b -> word.substring(a, b) }
        }
        assertEquals(
            listOf("Root", "View", "Without", "Focus", "Exception"),
            pieces("RootViewWithoutFocusException"),
        )
        assertEquals(
            listOf("app/", "src/", "main/", "java/", "Markdown", "Text.kt"),
            pieces("app/src/main/java/MarkdownText.kt"),
        )
        // Short words stay whole and move to the next line instead.
        assertEquals(listOf("MarkdownText.kt"), pieces("MarkdownText.kt"))
        // No stub of a few characters at either end.
        assertEquals(listOf("aB_cdefghijklmnopqrstuvw.x"), pieces("aB_cdefghijklmnopqrstuvw.x"))

        val s = parse("see `RootViewWithoutFocusException` here")
        assertEquals("see RootViewWithoutFocusException here", s.visible())
        assertEquals(4, s.text.count { it == '\u200B' })
    }

    @Test
    fun nonLinksStayVerbatim() {
        for (text in listOf("array[0] (x)", "[a] (b)", "[x](has space)", "`https://in.code`", "a[b](c")) {
            assertTrue(text, links(parse(text)).isEmpty())
        }
        assertEquals("array[0] (x)", parse("array[0] (x)").text)
    }

    @Test
    fun tappingWebLinkOpensUrl() {
        val opened = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(
                LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) {
                        opened.add(uri)
                    }
                },
            ) {
                HerdrTheme { MarkdownText(markdown = "[here](https://example.com/x)") }
            }
        }
        compose.onNodeWithText("here").performFirstLinkClick()
        assertEquals(listOf("https://example.com/x"), opened)
    }
}
