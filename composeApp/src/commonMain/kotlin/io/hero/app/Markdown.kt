package io.hero.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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

/** Annotation tag carrying a link's target URL through the AnnotatedString. */
internal const val URL_TAG = "URL"

/** RENDER_CHAR_CAP bounds the characters any single Text / markdown body renders.
 *  The node RPC caps a part at ~64 KiB and a turn at ~512 KiB, but one legit 64 KiB
 *  part of short lines still expands into tens of thousands of blocks/spans and a
 *  single enormous Text. This is a defensive *view* cap: content past it stays in
 *  the transcript (reachable there) — normal chat messages are a few KiB, far below
 *  it, so they render unchanged. Deliberately generous. */
internal const val RENDER_CHAR_CAP = 16 * 1024

/** Shown after a body that [capForRender] truncated, so the cut is visible. */
internal const val RENDER_TRUNCATION_NOTICE = "… (truncated in view; full text in transcript)"

/** capForRender returns [s] unchanged when within [RENDER_CHAR_CAP], else its first
 *  RENDER_CHAR_CAP chars. Pure — the caller adds the visible affordance when
 *  [isRenderCapped] is true. */
internal fun capForRender(s: String): String =
    if (s.length <= RENDER_CHAR_CAP) s else s.substring(0, RENDER_CHAR_CAP)

/** isRenderCapped reports whether [capForRender] would drop content. */
internal fun isRenderCapped(s: String): Boolean = s.length > RENDER_CHAR_CAP

internal sealed interface MdBlock {
    data class Code(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val marker: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    // Defensive per-part view cap: parse/annotate only a bounded head so a huge
    // part can't expand into an unbounded block/span tree (the full text stays in
    // the transcript). Normal messages sit far below the cap and are unaffected.
    val capped = remember(md) { capForRender(md) }
    val truncated = md.length > RENDER_CHAR_CAP
    val blocks = remember(capped) { parseBlocks(capped) }
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val body = MaterialTheme.typography.bodyMedium
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Code -> MonoBlock(b.text)
                    is MdBlock.Heading -> LinkableText(
                        parseInline(b.text, link, codeBg),
                        style = when (b.level) {
                            1 -> MaterialTheme.typography.titleMedium
                            2 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.bodyLarge
                        }.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                    )
                    is MdBlock.Bullet -> Row {
                        Text("•  ", style = body, color = MaterialTheme.colorScheme.onSurface)
                        LinkableText(parseInline(b.text, link, codeBg), style = body.copy(color = MaterialTheme.colorScheme.onSurface))
                    }
                    is MdBlock.Ordered -> Row {
                        Text("${b.marker}  ", style = body, color = MaterialTheme.colorScheme.onSurface)
                        LinkableText(parseInline(b.text, link, codeBg), style = body.copy(color = MaterialTheme.colorScheme.onSurface))
                    }
                    is MdBlock.Paragraph -> LinkableText(
                        parseInline(b.text, link, codeBg),
                        style = body.copy(color = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
            if (truncated) {
                Text(
                    RENDER_TRUNCATION_NOTICE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// LinkableText upgrades to ClickableText only when the string carries URL
// annotations — plain text keeps the cheaper composable. ClickableText does not
// inherit LocalTextStyle, so the resolved style is passed explicitly.
@Composable
private fun LinkableText(text: AnnotatedString, style: TextStyle) {
    if (text.getStringAnnotations(URL_TAG, 0, text.length).isEmpty()) {
        Text(text, style = style)
    } else {
        val uriHandler = LocalUriHandler.current
        ClickableText(text, style = style) { offset ->
            text.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()
                ?.let { runCatching { uriHandler.openUri(it.item) } }
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

// INLINE_SCAN_FACTOR/FLOOR size parseInline's forward-scan budget relative to the
// input length: total closer-search work is capped at FACTOR*length + FLOOR, which
// keeps the parser linear. The factor is generous — well-formed text does ~length
// total scan work — so real markup is never starved of budget.
private const val INLINE_SCAN_FACTOR = 8L
private const val INLINE_SCAN_FLOOR = 4096L

// parseInline is a single left-to-right, LINEAR-time tokenizer over
// buildAnnotatedString. Advancing one position at a time, it emits the literal char
// on any unbalanced marker — so malformed markdown degrades to plain text rather
// than dropping content. Every forward search for a marker's closer debits a shared
// budget proportional to the input length (see INLINE_SCAN_FACTOR); once spent, the
// remainder is emitted verbatim. This bounds total work to O(length): the previous
// version re-scanned forward with indexOf(']') for every '[', so a large text with
// many unclosed '[' (or a shared trailing ']') degraded to O(length^2) on the UI
// thread before a single node was created. Normal text closes its markers within a
// few chars, so the budget is never near exhausted and the output is identical.
internal fun parseInline(s: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    var scanBudget = s.length.toLong() * INLINE_SCAN_FACTOR + INLINE_SCAN_FLOOR
    // find advances from `from` to the next `needle`, debiting one unit per char
    // examined; returns -1 if not found before end-of-string or budget exhaustion.
    fun find(needle: Char, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (scanBudget-- <= 0L) return -1
            if (s[j] == needle) return j
            j++
        }
        return -1
    }
    // findDouble finds the next run of two identical `ch` (the ** / __ token),
    // debiting the same budget.
    fun findDouble(ch: Char, from: Int): Int {
        var j = from
        while (j + 1 < s.length) {
            if (scanBudget-- <= 0L) return -1
            if (s[j] == ch && s[j + 1] == ch) return j
            j++
        }
        return -1
    }
    while (i < s.length) {
        val c = s[i]
        when {
            c == '`' -> {
                val end = find('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val end = findDouble(c, i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else { append(c); i++ }
            }
            c == '*' || c == '_' -> {
                val end = find(c, i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                // On '[' try to match ](...); on any failure treat '[' as literal
                // and continue from the NEXT char — never re-scan from the start.
                val close = find(']', i + 1)
                if (close > i && close + 1 < s.length && s[close + 1] == '(') {
                    val paren = find(')', close + 2)
                    if (paren > close) {
                        // The URL rides along as an annotation so the label is
                        // tappable (LinkableText) — the url itself is not shown.
                        pushStringAnnotation(URL_TAG, s.substring(close + 2, paren))
                        withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) {
                            append(s.substring(i + 1, close))
                        }
                        pop()
                        i = paren + 1
                    } else { append(c); i++ }
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}
