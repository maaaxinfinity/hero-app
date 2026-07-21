package io.hero.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// A deliberately MINIMAL, dependency-free markdown renderer — no library, per the
// project's few-deps ethos. It handles the subset assistant/tool text uses in
// practice; anything unrecognized falls through as literal text (never throws).
//
// Block level:  fenced ```code```, ATX headings (#..######), bullet (- * +) and
//               ordered (1.) lists, blank-line paragraph breaks.
// Inline level: **bold**/__bold__, *italic*/_italic_, `code`, [label](url)
//               (styled; not clickable in v1). Selection is enabled for copy.

internal sealed interface MdBlock {
    data class Code(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val marker: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val blocks = remember(md) { parseBlocks(md) }
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Code -> MonoBlock(b.text)
                    is MdBlock.Heading -> Text(
                        parseInline(b.text, link, codeBg),
                        style = when (b.level) {
                            1 -> MaterialTheme.typography.titleMedium
                            2 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.bodyLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    is MdBlock.Bullet -> Row {
                        Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(parseInline(b.text, link, codeBg), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    is MdBlock.Ordered -> Row {
                        Text("${b.marker}  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(parseInline(b.text, link, codeBg), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    is MdBlock.Paragraph -> Text(
                        parseInline(b.text, link, codeBg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// parseBlocks is a line-based pre-pass. Fenced code is captured verbatim; other
// lines are classified individually (adjacent paragraph lines are kept separate,
// which is fine with `breaks`-style rendering).
internal fun parseBlocks(md: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = md.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(lines[i])
                    i++
                }
                i++ // consume closing fence (or run off the end)
                out.add(MdBlock.Code(buf.toString()))
            }
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                out.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                out.add(MdBlock.Bullet(trimmed.drop(2).trim()))
                i++
            }
            orderedMarker(trimmed) != null -> {
                val m = orderedMarker(trimmed)!!
                out.add(MdBlock.Ordered(m, trimmed.drop(m.length).trim()))
                i++
            }
            trimmed.isEmpty() -> i++ // paragraph break (spacing handled by the Column)
            else -> {
                out.add(MdBlock.Paragraph(line))
                i++
            }
        }
    }
    return out
}

// orderedMarker returns "N." when the line starts an ordered-list item, else null.
private fun orderedMarker(s: String): String? {
    val digits = s.takeWhile { it.isDigit() }
    if (digits.isEmpty() || digits.length > 3) return null
    return if (s.length > digits.length && s[digits.length] == '.' &&
        s.length > digits.length + 1 && s[digits.length + 1] == ' '
    ) "$digits." else null
}

// parseInline is a single left-to-right scanner over buildAnnotatedString. On any
// unbalanced marker it emits the literal char and moves on — so malformed markdown
// degrades to plain text rather than dropping content.
internal fun parseInline(s: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val token = s.substring(i, i + 2)
                val end = s.indexOf(token, i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else { append(c); i++ }
            }
            c == '*' || c == '_' -> {
                val end = s.indexOf(c, i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                    val paren = s.indexOf(')', close + 2)
                    if (paren > close) {
                        withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) {
                            append(s.substring(i + 1, close))
                        }
                        i = paren + 1
                    } else { append(c); i++ }
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}
