package io.hero.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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

/** RENDER_CHAR_CAP is the threshold past which a single Text / markdown body is
 *  truncated for the DEFAULT view. The node RPC caps a part at ~64 KiB and a turn
 *  at ~512 KiB, but one legit 64 KiB part of short lines still expands into tens
 *  of thousands of blocks/spans and a single enormous Text. Normal chat messages
 *  are a few KiB, far below it, so they render unchanged. Deliberately generous. */
internal const val RENDER_CHAR_CAP = 16 * 1024

/** A capped body renders its first RENDER_HEAD_CHARS and last RENDER_TAIL_CHARS
 *  (head + tail, so a final result/error living at the END of a huge tool output
 *  stays visible), with an explicit fold row in between. Head + tail == the cap,
 *  so anything at or under RENDER_CHAR_CAP renders exactly as before. */
internal const val RENDER_HEAD_CHARS = 12 * 1024
internal const val RENDER_TAIL_CHARS = 4 * 1024

/** RENDER_BLOCK_CAP bounds how many parsed markdown blocks one body SEGMENT
 *  composes by default: a 12 KiB head of one-char lines is ~6k blocks — thousands
 *  of eager Compose nodes for a single row. Blocks past it fold into the same
 *  Show-full affordance; nothing is lost. */
internal const val RENDER_BLOCK_CAP = 512

/** isRenderCapped reports whether the default view truncates [s]. */
internal fun isRenderCapped(s: String): Boolean = s.length > RENDER_CHAR_CAP

/** RenderSlices is the default view of a capped body: [head] + (omitted middle of
 *  [omitted] chars) + [tail]. head + middle + tail reconstruct the original
 *  exactly — truncation is view-level only, never data loss. */
internal data class RenderSlices(val head: String, val tail: String, val omitted: Int)

/** codePointSafeEnd backs [end] off by one when cutting there would split a
 *  surrogate pair — a head slice must never end in a dangling high surrogate. */
internal fun codePointSafeEnd(s: String, end: Int): Int =
    if (end in 1 until s.length && s[end - 1].isHighSurrogate() && s[end].isLowSurrogate()) end - 1 else end

/** codePointSafeStart advances [start] by one when it would land on the low half
 *  of a surrogate pair — a tail slice must never begin mid-code-point. */
internal fun codePointSafeStart(s: String, start: Int): Int =
    if (start in 1 until s.length && s[start].isLowSurrogate() && s[start - 1].isHighSurrogate()) start + 1 else start

/** renderSlices splits an over-cap body into its rendered head + tail (both
 *  code-point safe: a surrogate pair is dropped whole, never split into lone
 *  halves) and the exact count of chars folded between them. Returns null when
 *  [s] is within the cap and renders whole. Pure; unit-tested. */
internal fun renderSlices(s: String): RenderSlices? {
    if (s.length <= RENDER_CHAR_CAP) return null
    val headEnd = codePointSafeEnd(s, RENDER_HEAD_CHARS)
    val tailStart = codePointSafeStart(s, s.length - RENDER_TAIL_CHARS)
    return RenderSlices(s.substring(0, headEnd), s.substring(tailStart), tailStart - headEnd)
}

/** capDisplay bounds a free-text LABEL (workflow name/status/summary, system
 *  marker, model chip …) to the same render cap: within it the string passes
 *  through untouched; past it a code-point-safe head + "…" is shown. Labels are
 *  decorative one/few-liners — head-only is honest there because the Text itself
 *  already ellipsizes; content-bearing bodies use [renderSlices] instead. */
internal fun capDisplay(s: String): String =
    if (s.length <= RENDER_CHAR_CAP) s
    else s.substring(0, codePointSafeEnd(s, RENDER_CHAR_CAP)) + "…"

/** boundedPhaseTrail renders the workflow phase breadcrumb without ever
 *  materializing an unbounded join: titles append (separated like the original
 *  joinToString) only until the shared render cap, then "…". Under the cap the
 *  output is byte-identical to the old `joinToString("  ›  ") { it.title }`. */
internal fun boundedPhaseTrail(phases: List<WorkflowPhase>): String {
    val sb = StringBuilder()
    for ((i, p) in phases.withIndex()) {
        if (i > 0) sb.append("  ›  ")
        val room = RENDER_CHAR_CAP - sb.length
        if (room <= 0) { sb.append('…'); break }
        val t = p.title
        if (t.length <= room) sb.append(t)
        else {
            sb.append(t, 0, codePointSafeEnd(t, room)).append('…')
            break
        }
    }
    return sb.toString()
}

internal sealed interface MdBlock {
    data class Code(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val marker: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    // Defensive per-part view budget. Under the cap (the overwhelmingly common
    // case) this is exactly the pre-budget render. Past it the DEFAULT view is a
    // code-point-safe head + tail with an honest fold row between them; "Show
    // full" parses the entire body on demand (collapsible) and "Copy raw" puts
    // the complete original on the clipboard. This screen IS the transcript, so
    // the fold row states what is hidden instead of pointing elsewhere. Block
    // counts are budgeted per segment too — a body of one-char lines can't
    // eagerly compose thousands of nodes into a single row.
    val slices = if (isRenderCapped(md)) remember(md) { renderSlices(md) } else null
    var showFull by rememberSaveable(md) { mutableStateOf(false) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (slices == null || showFull) {
                val blocks = remember(md) { parseBlocks(md) }
                // Show full renders EVERYTHING the user asked for; the default
                // path bounds composed blocks and folds the rest.
                val shown = if (showFull) blocks else blocks.take(RENDER_BLOCK_CAP)
                shown.forEach { b -> MdBlockView(b) }
                val hiddenBlocks = blocks.size - shown.size
                when {
                    hiddenBlocks > 0 -> TruncationActions(
                        label = "… $hiddenBlocks more blocks hidden",
                        showFull = false, onToggle = { showFull = true }, raw = md,
                    )
                    showFull -> TruncationActions(
                        label = "showing full text",
                        showFull = true, onToggle = { showFull = false }, raw = md,
                    )
                }
            } else {
                val headBlocks = remember(slices) { parseBlocks(slices.head) }
                val tailBlocks = remember(slices) { parseBlocks(slices.tail) }
                val headShown = headBlocks.take(RENDER_BLOCK_CAP)
                val tailShown = if (tailBlocks.size <= RENDER_BLOCK_CAP) tailBlocks else tailBlocks.subList(tailBlocks.size - RENDER_BLOCK_CAP, tailBlocks.size)
                headShown.forEach { b -> MdBlockView(b) }
                val hiddenBlocks = (headBlocks.size - headShown.size) + (tailBlocks.size - tailShown.size)
                TruncationActions(
                    label = if (hiddenBlocks > 0) "… ${slices.omitted} characters + $hiddenBlocks blocks hidden"
                    else "… ${slices.omitted} characters hidden",
                    showFull = false, onToggle = { showFull = true }, raw = md,
                )
                // The tail keeps its END — the newest/final content — when block-capped.
                tailShown.forEach { b -> MdBlockView(b) }
            }
        }
    }
}

// MdBlockView renders ONE parsed markdown block — the same dispatch the whole-body
// Column used before the per-segment budget split it up.
@Composable
private fun MdBlockView(b: MdBlock) {
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val body = MaterialTheme.typography.bodyMedium
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

/** TruncationActions is the fold row under/inside a budgeted body: an honest
 *  count of what the view hides, "Show full"/"Show less" toggling the on-demand
 *  complete render, and "Copy raw" putting the ENTIRE original text on the
 *  clipboard. Wrapped in DisableSelection so drag-selecting the body never
 *  copies this chrome as if it were content. */
@Composable
internal fun TruncationActions(label: String, showFull: Boolean, onToggle: () -> Unit, raw: String) {
    val clipboard = LocalClipboardManager.current
    DisableSelection {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (showFull) "Show less" else "Show full",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onToggle),
            )
            Text(
                "Copy raw",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(raw)) },
            )
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
