package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

private sealed class MarkdownBlock {
    data class Paragraph(val lines: List<String>) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<Pair<String, String>>) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
}

private val headingRegex = Regex("^(#{1,4})\\s+(.*)$")
private val numberedItemRegex = Regex("^(\\d+\\.)\\s+(.*)$")

private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Fenced code block ```lang
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        // Heading (#, ##, ###, ####)
        val headingMatch = headingRegex.matchEntire(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        // Bullet list (-, *, •)
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) {
                    items.add(t.drop(2))
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.BulletList(items))
            continue
        }

        // Numbered list (1. ..., 2. ...)
        if (numberedItemRegex.matches(trimmed)) {
            val items = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val m = numberedItemRegex.matchEntire(lines[i].trim()) ?: break
                items.add(m.groupValues[1] to m.groupValues[2])
                i++
            }
            blocks.add(MarkdownBlock.NumberedList(items))
            continue
        }

        // Paragraph
        val para = mutableListOf<String>()
        while (i < lines.size) {
            val t = lines[i].trim()
            if (
                t.isEmpty() ||
                t.startsWith("```") ||
                t.startsWith("- ") ||
                t.startsWith("* ") ||
                t.startsWith("• ") ||
                headingRegex.matches(t) ||
                numberedItemRegex.matches(t)
            ) {
                break
            }
            para.add(lines[i])
            i++
        }
        if (para.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(para))
        }
    }
    return blocks
}

private fun parseInlineStyles(
    text: String,
    inlineCodeBg: Color,
    inlineCodeFg: Color,
): AnnotatedString {
    return buildAnnotatedString {
        var idx = 0
        val len = text.length
        while (idx < len) {
            // Inline code `...`
            if (text[idx] == '`') {
                val end = text.indexOf('`', idx + 1)
                if (end > idx) {
                    withStyle(
                        SpanStyle(
                            fontFamily = MonoFontFamily,
                            background = inlineCodeBg,
                            color = inlineCodeFg,
                            fontSize = 13.sp,
                        )
                    ) {
                        append(" ${text.substring(idx + 1, end)} ")
                    }
                    idx = end + 1
                    continue
                }
            }
            // Bold **...**
            if (idx + 1 < len && text[idx] == '*' && text[idx + 1] == '*') {
                val end = text.indexOf("**", idx + 2)
                if (end > idx) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(idx + 2, end))
                    }
                    idx = end + 2
                    continue
                }
            }
            // Italic *...*
            if (text[idx] == '*') {
                val end = text.indexOf('*', idx + 1)
                if (end > idx + 1 && !text.substring(idx + 1, end).contains('\n')) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(idx + 1, end))
                    }
                    idx = end + 1
                    continue
                }
            }
            append(text[idx])
            idx++
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeFg = MaterialTheme.colorScheme.primary

    SelectionContainer {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (block in blocks) {
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Text(
                            text = parseInlineStyles(block.text, codeBg, codeFg),
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor,
                        )
                    }

                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = parseInlineStyles(block.lines.joinToString("\n"), codeBg, codeFg),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                            color = textColor,
                        )
                    }

                    is MarkdownBlock.BulletList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (item in block.items) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = parseInlineStyles(item, codeBg, codeFg),
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                                        color = textColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.NumberedList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((num, item) in block.items) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = num,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = MonoFontFamily,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = parseInlineStyles(item, codeBg, codeFg),
                                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                                        color = textColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    is MarkdownBlock.CodeBlock -> {
                        CodeBlockCard(language = block.language, code = block.code)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = MonoFontFamily,
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy_code),
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                text = code,
                fontFamily = MonoFontFamily,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
