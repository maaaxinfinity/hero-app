package io.hero.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Conversation.kt holds the pure reconciliation state machine and the native
// re-render of the structured "window" (Turn/TurnPart). The app renders these
// harness-neutral types and dispatches on part.type / turn.role with a default
// fallback — it never parses raw jsonl, and an unknown future kind degrades to a
// monospace block rather than breaking.

// ---- state + reducer (pure; no Compose — unit-testable) ----

/** Cursor tracks the transcript page window for "load earlier". */
data class Cursor(val total: Int, val start: Int, val pageSize: Int) {
    val hasMore get() = start > 0
    fun earlierOffset() = (start - pageSize).coerceAtLeast(0)
}

data class ConvoState(
    val turns: List<Turn> = emptyList(),
    val openAssistant: Boolean = false, // last turn is a live, unfinalized assistant turn
    val cursor: Cursor? = null,
    val status: String? = null,         // transient "thinking"/"stalled"
    val runtimeModel: String? = null,
)

/** reduce folds one live frame into the conversation. Immutable copies so Compose
 *  sees new references; `Unknown`/`Delta` are no-ops (v1 renders committed parts). */
fun ConvoState.reduce(f: LiveFrame): ConvoState = when (f) {
    is LiveFrame.UserTurn -> {
        val dup = turns.lastOrNull()?.let { it.role == "user" && it.content == f.content } == true
        if (dup) copy(openAssistant = false, status = null)
        else copy(turns = turns + Turn(role = "user", content = f.content, ts = f.ts ?: ""),
            openAssistant = false, status = null)
    }
    is LiveFrame.Part -> {
        if (openAssistant && turns.lastOrNull()?.role == "assistant") {
            val last = turns.last()
            copy(turns = turns.dropLast(1) + last.copy(parts = last.parts + f.part))
        } else {
            copy(
                turns = turns + Turn(role = "assistant", parts = listOf(f.part), ts = f.ts ?: "", model = f.model),
                openAssistant = true, status = null,
            )
        }
    }
    is LiveFrame.Delta -> this // v1: rely on committed `part` frames
    is LiveFrame.Status -> copy(status = f.status)
    is LiveFrame.Exit -> {
        val closed = if (openAssistant && f.assistantUuid != null && turns.lastOrNull()?.role == "assistant")
            turns.dropLast(1) + turns.last().copy(uuid = f.assistantUuid) else turns
        copy(turns = closed, openAssistant = false, status = null)
    }
    is LiveFrame.Runtime -> copy(runtimeModel = f.model ?: runtimeModel)
    is LiveFrame.ErrorFrame -> copy(turns = turns + Turn(role = "error", content = f.message),
        openAssistant = false, status = null)
    LiveFrame.TurnActive -> copy(status = status ?: "thinking")
    LiveFrame.TurnIdle -> copy(openAssistant = false, status = null)
    is LiveFrame.Unknown -> this
}

/** optimisticUser appends the user's message locally on send; the canonical
 *  turn.user that follows is deduped by reduce. */
fun ConvoState.optimisticUser(text: String): ConvoState =
    copy(turns = turns + Turn(role = "user", content = text), openAssistant = false)

/** prepend inserts an earlier transcript page before the current window and
 *  advances the cursor. Pure, like reduce. */
fun ConvoState.prepend(page: List<Turn>, total: Int, start: Int): ConvoState =
    copy(
        turns = page + turns,
        cursor = Cursor(total, start, cursor?.pageSize ?: page.size.coerceAtLeast(1)),
    )

/** turnKey is a stable LazyColumn key. Assistant turns prefer uuid (assigned at
 *  exit); before that they key on role+ts ONLY — never parts.size, which grows on
 *  every streamed part and would rebuild the live row (losing tool-card expand
 *  state and scroll) on each append. Non-assistant turns have settled content, so
 *  a length tiebreaker is stable. displayKeys disambiguates same-ts collisions. */
fun turnKey(t: Turn): Any {
    t.uuid?.let { return it }
    return if (t.role == "assistant") "assistant:${t.ts}"
    else "${t.role}:${t.ts}:${t.content?.length ?: 0}"
}

/** collectChildSessions lists the subagent transcripts spawned in this
 *  conversation (display label → child session id), in order of first
 *  appearance, deduped by child id. Pure — feeds the session inspector. */
fun collectChildSessions(turns: List<Turn>): List<Pair<String, String>> {
    val out = LinkedHashMap<String, String>()
    turns.forEach { t ->
        t.parts.forEach { p ->
            val child = p.childSessionId
            if (child != null && child !in out) {
                out[child] = p.toolTarget?.takeIf { it.isNotEmpty() } ?: (p.toolName ?: "subagent")
            }
        }
    }
    return out.map { (id, label) -> label to id }
}

/** displayKeys makes turnKey collision-proof for LazyColumn: live turns without
 *  a uuid can legitimately repeat (same role/ts/size), which crashes the list.
 *  Duplicates get an occurrence suffix; uuid'd history stays untouched, so keys
 *  are stable across prepends and appends. */
fun displayKeys(turns: List<Turn>): List<Any> {
    val seen = HashMap<Any, Int>()
    return turns.map { t ->
        val base = turnKey(t)
        val n = seen[base] ?: 0
        seen[base] = n + 1
        if (n == 0) base else "$base#$n"
    }
}

// ---- rendering ----

@Composable
fun TurnView(turn: Turn, backend: String, onOpenChild: ((String) -> Unit)? = null) {
    when (turn.role) {
        "user" -> UserBubble(turn)
        "assistant" -> AssistantTurn(turn, backend, onOpenChild)
        "system" -> SystemMarker(turn)
        "error" -> ErrorLine(turn)
        else -> AssistantTurn(turn, backend, onOpenChild) // graceful default for a future role
    }
}

@Composable
private fun UserBubble(turn: Turn) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            MarkdownText(turn.content.orEmpty(), Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun AssistantTurn(turn: Turn, backend: String, onOpenChild: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackendMark(backend, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                turn.model ?: "assistant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(2.dp))
        if (turn.parts.isEmpty()) {
            Text("…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            turn.parts.forEach { PartView(it, onOpenChild) }
        }
    }
}

@Composable
private fun SystemMarker(turn: Turn) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            turn.content.orEmpty().ifEmpty { "—" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorLine(turn: Turn) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            turn.content.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun PartView(part: TurnPart, onOpenChild: ((String) -> Unit)? = null) {
    when {
        part.workflow != null -> WorkflowCard(part.workflow) // P1b — a Workflow tool part
        part.type == "text" -> MarkdownText(part.content, Modifier.fillMaxWidth().padding(vertical = 2.dp))
        part.type == "tool" -> ToolCard(part, onOpenChild)
        else -> MonoBlock(part.content) // DEFAULT: an unknown kind still renders legibly
    }
}

// WorkflowCard renders a dynamic-workflow run: name + status, the phase
// breadcrumb, a one-line summary, and fleet totals. Fits the monochrome ink theme.
@Composable
private fun WorkflowCard(wf: WorkflowInfo) {
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⌘ Workflow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    wf.name.ifEmpty { "workflow" },
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (wf.status.isNotEmpty()) StatusChip(wf.status)
            }
            if (wf.phases.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    wf.phases.joinToString("  ›  ") { it.title },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wf.summary.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    wf.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            val metrics = buildList {
                if (wf.agentCount > 0) add("${wf.agentCount} agents")
                if (wf.totalToolCalls > 0) add("${wf.totalToolCalls} tools")
                if (wf.totalTokens > 0) add("${fmtCount(wf.totalTokens)} tokens")
            }
            if (metrics.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    metrics.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val done = status.equals("completed", true)
    val failed = status.equals("failed", true) || status.equals("error", true)
    val fg = when {
        failed -> MaterialTheme.colorScheme.error
        done -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary // running / other = active
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
        Text(status, style = MaterialTheme.typography.labelSmall, color = fg, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// fmtCount renders a count compactly (2321785 -> "2.3M") without String.format,
// which isn't in the common stdlib.
private fun fmtCount(n: Long): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}.${(n / 100_000) % 10}M"
    n >= 1_000 -> "${n / 1_000}.${(n / 100) % 10}k"
    else -> n.toString()
}

@Composable
private fun ToolCard(part: TurnPart, onOpenChild: ((String) -> Unit)?) {
    val expandDefault = part.toolName?.let { it.equals("Edit", true) || it.equals("Write", true) } == true
    var expanded by rememberSaveable(part.toolName, part.toolTarget) { mutableStateOf(expandDefault) }
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, tween(180), label = "chevron")
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.animateContentSize(tween(200))) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).rotate(chevron),
                )
                Spacer(Modifier.width(6.dp))
                Text(part.toolName ?: "tool", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                part.toolTarget?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Agent-team drill-in: open the spawned subagent's own transcript.
            val child = part.childSessionId
            if (child != null && onOpenChild != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenChild(child) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("↳ Open subagent transcript", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text("›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (expanded && part.content.isNotEmpty()) MonoBlock(part.content)
        }
    }
}

/** MonoBlock renders code / tool output / unknown-kind content as a scrollable
 *  monospace block. Shared by ToolCard, the PartView fallback, and Markdown code.
 *  A very long block (e.g. a 64 KiB tool output) is bounded to a head + transcript
 *  affordance so one giant Text is never measured/laid out; normal-sized content
 *  keeps the exact original single-Text layout. */
@Composable
fun MonoBlock(text: String) {
    val truncated = text.length > RENDER_CHAR_CAP
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (!truncated) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            )
        } else {
            Column {
                Text(
                    capForRender(text),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
                )
                Text(
                    RENDER_TRUNCATION_NOTICE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
                )
            }
        }
    }
}

// codexMarkPath is render.js's codex glyph (a single filled superellipse) in the
// 24×24 authoring space; parsed once.
private const val CODEX_MARK =
    "M13.81,5.24A3.680 3.680 0 0 1 18.76,10.19A3.680 3.680 0 0 1 16.95,16.95" +
        "A3.680 3.680 0 0 1 10.19,18.76A3.680 3.680 0 0 1 5.24,13.81A3.680 3.680 0 0 1 7.05,7.05" +
        "A3.680 3.680 0 0 1 13.81,5.24Z"

// claudeSpokes are render.js's 10 round-capped spokes from center (12,12).
private val CLAUDE_SPOKES = listOf(
    12.84f to 2.44f, 18.30f to 4.75f, 21.35f to 9.84f, 20.84f to 15.75f, 16.94f to 20.23f,
    11.16f to 21.56f, 5.70f to 19.25f, 2.65f to 14.16f, 3.16f to 8.25f, 7.06f to 3.77f,
)

/** BackendMark draws the harness glyph (claude spokes / codex superellipse),
 *  mirroring render.js BACKEND_MARKS. Unknown/empty backend falls back to claude. */
@Composable
fun BackendMark(backend: String, modifier: Modifier = Modifier, tint: Color) {
    val codex = backend.equals("codex", ignoreCase = true)
    val codexPath = remember { PathParser().parsePathString(CODEX_MARK).toPath() }
    Canvas(modifier) {
        val s = size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            if (codex) {
                drawPath(codexPath, color = tint)
            } else {
                CLAUDE_SPOKES.forEach { (x, y) ->
                    drawLine(tint, Offset(12f, 12f), Offset(x, y), strokeWidth = 2.1f, cap = StrokeCap.Round)
                }
            }
        }
    }
}
