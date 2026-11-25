package com.example

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
import com.example.ui.FILE_LINK_TAG
import com.example.ui.INLINE_CODE_TAG
import com.example.ui.MarkdownText
import com.example.ui.parseInlineStyles
import com.example.ui.theme.HerdrTheme
import com.example.ui.vmPathFromLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for #12: `[label](url)` rendered as raw Markdown, and web links were not tappable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarkdownLinksTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun parse(text: String) = parseInlineStyles(text, Color.Blue)

    private data class Link(val text: String, val target: String)

    private fun links(s: AnnotatedString): List<Link> = s.getLinkAnnotations(0, s.length).map {
        val target = when (val item = it.item) {
            is LinkAnnotation.Url -> item.url
            is LinkAnnotation.Clickable -> item.tag
            else -> error("unexpected link $item")
        }
        Link(s.text.substring(it.start, it.end), target)
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
        assertEquals("PR #11 and  file.kt ", s.text)
        assertEquals(
            listOf(Link("PR #11", "https://e.com/11"), Link(" file.kt ", FILE_LINK_TAG)),
            links(s),
        )
        val bold = s.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("PR #11", s.text.substring(bold.start, bold.end))
        val code = s.getStringAnnotations(INLINE_CODE_TAG, 0, s.length).single()
        assertEquals(" file.kt ", s.text.substring(code.start, code.end))
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
