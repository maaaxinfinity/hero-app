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

/**
 * ConvoState is the conversation window split in two so a live part append is
 * near O(1) instead of a whole-list rebuild:
 *
 *  - [history] holds every SETTLED turn. Its list reference is untouched while
 *    parts stream into the open turn, so `remember(history)`-keyed derivations
 *    and LazyColumn rows stay valid across part frames.
 *  - [open] is the single live, unfinalized assistant turn — the only row a
 *    part frame may grow. Exit/idle settles it: one append onto history.
 *
 * Every turn gets a stable UI id when it FIRST enters the state — the server
 * uuid when it arrives committed, else a local monotonic id ("~n", from
 * [nextLocalId]) — carried in [historyKeys]/[openKey], parallel to the turns.
 * That id never changes for the life of the row: the exit frame stamping the
 * canonical uuid, a truth-up swapping in the committed version, and a prepend
 * landing a same-identity record all keep the original id. Rows are therefore
 * never deleted+reinserted on commit (tool-card expansion and scroll anchors
 * survive), and keys never shift the way the old displayKeys occurrence
 * recompute shifted them on prepend.
 *
 * [children] is the incrementally maintained subagent index — always equal to
 * collectChildSessions(turns) — with [childIds] as its dedup set; live part
 * frames fold in only the new part instead of rescanning the history.
 *
 * Construct via the ConvoState(turns, …) factory below; the primary
 * constructor is the internal split representation.
 */
data class ConvoState(
    val history: List<Turn>,
    val historyKeys: List<Any>,
    val open: Turn?,
    val openKey: Any?,
    val cursor: Cursor?,
    val status: String?,         // transient "thinking"/"stalled"
    // The node-authoritative ACTUAL runtime (model / effort / live context),
    // field-level: seeded from the session snapshot on open, truthed-up while
    // idle, and patched by live session.runtime frames. Distinct from the picker's
    // PENDING target (the composer's switch selection) — a successful send does
    // NOT promote pending to actual; only a runtime projection here does.
    val runtime: RuntimeState,
    val children: List<Pair<String, String>>,
    val childIds: Set<String>,
    val nextLocalId: Long,
) {
    /** True while the last turn is a live, unfinalized assistant turn. */
    val openAssistant: Boolean get() = open != null
    /** The full window, oldest→newest. Materializes a copy — for page-scale
     *  callers and tests; per-frame code reads [history]/[open] directly. */
    val turns: List<Turn> get() = if (open == null) history else history + open
    /** Stable UI ids parallel to [turns]; same materialization caveat. */
    val uiKeys: List<Any> get() = openKey?.let { historyKeys + it } ?: historyKeys
    val turnCount: Int get() = history.size + (if (open == null) 0 else 1)
    val lastTurn: Turn? get() = open ?: history.lastOrNull()
    val lastUiKey: Any? get() = openKey ?: historyKeys.lastOrNull()
    /** UI id of the [i]-th turn without materializing [uiKeys]. */
    fun uiKeyAt(i: Int): Any? = when {
        i in historyKeys.indices -> historyKeys[i]
        i == historyKeys.size -> openKey
        else -> null
    }

    /** The node-reported ACTUAL model (convenience for the header/inspector). */
    val runtimeModel: String? get() = runtime.model
}

/** ConvoState factory: folds a plain turn list (a transcript page seed, a test
 *  fixture) into the split representation, assigning each turn its UI id —
 *  uuid when it arrived committed, else a fresh local id. [openAssistant]
 *  marks a trailing assistant turn as live (it moves into the open slot). */
fun ConvoState(
    turns: List<Turn> = emptyList(),
    openAssistant: Boolean = false,
    cursor: Cursor? = null,
    status: String? = null,
    runtime: RuntimeState = RuntimeState(),
): ConvoState {
    val (keys, next) = assignUiKeys(turns, HashSet(), 0L)
    val opensLast = openAssistant && turns.lastOrNull()?.role == "assistant"
    val (kids, kidIds) = childIndex(turns)
    return ConvoState(
        history = if (opensLast) turns.dropLast(1) else turns,
        historyKeys = if (opensLast) keys.dropLast(1) else keys,
        open = if (opensLast) turns.last() else null,
        openKey = if (opensLast) keys.last() else null,
        cursor = cursor, status = status, runtime = runtime,
        children = kids, childIds = kidIds,
        nextLocalId = next,
    )
}

/** localUiKey formats local monotonic UI id [n]. The "~" prefix keeps it out
 *  of the uuid namespace (harness uuids never start with "~"). */
private fun localUiKey(n: Long): Any = "~$n"

/** assignUiKeys allocates one UI id per incoming page turn: its uuid when
 *  present and not already [used] (a colliding record — server dup or page
 *  overlap — falls back to a local id instead of stealing or shifting an
 *  existing row's key), else the next local id. Adds every issued key to
 *  [used]; returns the keys and the advanced local-id counter. */
private fun assignUiKeys(page: List<Turn>, used: MutableSet<Any>, firstLocalId: Long): Pair<List<Any>, Long> {
    var next = firstLocalId
    val keys: List<Any> = page.map { t ->
        val key: Any = if (t.uuid != null && t.uuid !in used) t.uuid else {
            while (localUiKey(next) in used) next++
            localUiKey(next++)
        }
        used += key
        key
    }
    return keys to next
}

/** childIndex is the full-scan (re)build of the subagent index — used by the
 *  page-scale paths (seed, prepend, truth-up, mid-window resume) where
 *  first-appearance order can change; live appends use [withChild]. */
private fun childIndex(turns: List<Turn>): Pair<List<Pair<String, String>>, Set<String>> {
    val kids = collectChildSessions(turns)
    return kids to kids.mapTo(HashSet()) { it.second }
}

/** settle folds the open turn (if any) into history — ONE append, keeping its
 *  UI id and optionally stamping the canonical [uuid] the exit frame carried.
 *  live→terminal is thus an in-place commit, never a delete+insert. */
private fun ConvoState.settle(uuid: String? = null): ConvoState =
    if (open == null) this
    else copy(
        history = history + (if (uuid == null) open else open.copy(uuid = uuid)),
        historyKeys = historyKeys + checkNotNull(openKey) { "open turn without a UI id" },
        open = null, openKey = null,
    )

/** appendSettled adds one settled row (user turn, error line) under a fresh
 *  local UI id. */
private fun ConvoState.appendSettled(t: Turn): ConvoState =
    copy(
        history = history + t,
        historyKeys = historyKeys + localUiKey(nextLocalId),
        nextLocalId = nextLocalId + 1,
    )

/** withChild folds ONE streamed part into the subagent index — the O(1) live
 *  replacement for rescanning every loaded turn on each part frame. */
private fun ConvoState.withChild(p: TurnPart): ConvoState {
    val id = p.childSessionId ?: return this
    if (id in childIds) return this
    val label = p.toolTarget?.takeIf { it.isNotEmpty() } ?: (p.toolName ?: "subagent")
    return copy(children = children + (label to id), childIds = childIds + id)
}

/** reduce folds one live frame into the conversation. Immutable copies so Compose
 *  sees new references; `Unknown`/`Delta` are no-ops (v1 renders committed parts).
 *  UserTurn/Part tolerate RE-DELIVERY: after a truth-up (or a snapshot taken
 *  mid-turn) the stream replays work the merged page already contains — those
 *  frames must fold away instead of duplicating turns or parts. A part for the
 *  open turn touches ONLY the open slot ([history] keeps its reference), so the
 *  hot streaming path does no per-frame window rebuild. */
fun ConvoState.reduce(f: LiveFrame): ConvoState = when (f) {
    is LiveFrame.UserTurn -> {
        // Duplicate if it's the optimistic echo (last turn, same content) or a
        // commit the loaded window already holds (same non-empty ts + content —
        // an SSE re-delivery across a truth-up, never a genuinely repeated
        // message, which gets its own ts).
        val echoDup = lastTurn?.let { it.role == "user" && it.content == f.content } == true
        val committedDup = !f.ts.isNullOrEmpty() && history.any { it.role == "user" && it.ts == f.ts && it.content == f.content }
        if (echoDup || committedDup) settle().copy(status = null)
        else settle().appendSettled(Turn(role = "user", content = f.content, ts = f.ts ?: "")).copy(status = null)
    }
    is LiveFrame.Part -> {
        val resumeIdx = if (open != null || f.ts.isNullOrEmpty()) -1
        else history.indexOfLast { it.role == "assistant" && it.ts == f.ts }
        when {
            // Hot path: grow the open turn in place. History (and its keys)
            // keep their references — no O(turns) copy per streamed part.
            open != null -> withChild(f.part).copy(open = open.copy(parts = open.parts + f.part))
            // Not open, but the frame's ts names a turn the seed/truth-up page
            // already holds: a part the page committed is a re-delivery (no-op);
            // a new one RESUMES that turn in place instead of opening a split
            // duplicate turn for the same ts.
            resumeIdx >= 0 && f.part in history[resumeIdx].parts -> this
            resumeIdx >= 0 && resumeIdx == history.lastIndex -> {
                // Reopen the tail turn: it and its UI id MOVE into the open slot,
                // so the row's key survives the settle→live→settle round trip.
                val t = history[resumeIdx]
                withChild(f.part).copy(
                    history = history.dropLast(1),
                    historyKeys = historyKeys.dropLast(1),
                    open = t.copy(parts = t.parts + f.part),
                    openKey = historyKeys[resumeIdx],
                    status = null,
                )
            }
            resumeIdx >= 0 -> {
                // Mid-window resume (rare: replay races a truth-up): in-place
                // append; positions and keys unchanged, child order re-derived.
                val t = history[resumeIdx]
                val updated = history.toMutableList().also { it[resumeIdx] = t.copy(parts = t.parts + f.part) }
                val (kids, kidIds) = childIndex(updated)
                copy(history = updated, children = kids, childIds = kidIds, status = null)
            }
            else -> withChild(f.part).copy(
                open = Turn(role = "assistant", parts = listOf(f.part), ts = f.ts ?: "", model = f.model),
                openKey = localUiKey(nextLocalId),
                nextLocalId = nextLocalId + 1,
                status = null,
            )
        }
    }
    // v1: rely on committed `part` frames. The subscription asks the server to
    // drop deltas (include_deltas=false), but an OLD server ignores the param
    // and keeps sending them — this branch must stay a no-op sink, not vanish.
    is LiveFrame.Delta -> this
    is LiveFrame.Status -> copy(status = f.status)
    // Exit only stamps the canonical uuid and settles — the UI id is untouched,
    // so the row is not rebuilt when a live turn goes terminal.
    is LiveFrame.Exit -> settle(uuid = f.assistantUuid).copy(status = null)
    // Field-level: a partial runtime patch (only the model, only the window, …)
    // overlays the current actual without blanking the fields it doesn't carry.
    is LiveFrame.Runtime -> copy(runtime = runtime.merge(f.patch))
    is LiveFrame.ErrorFrame -> settle().appendSettled(Turn(role = "error", content = f.message)).copy(status = null)
    LiveFrame.TurnActive -> copy(status = status ?: "thinking")
    LiveFrame.TurnIdle -> settle().copy(status = null)
    is LiveFrame.Unknown -> this
}

/** optimisticUser appends the user's message locally on send; the canonical
 *  turn.user that follows is deduped by reduce. */
fun ConvoState.optimisticUser(text: String): ConvoState =
    settle().appendSettled(Turn(role = "user", content = text))

/** withActual folds a session-snapshot runtime (the node-authoritative truth from
 *  the sessions list) into the conversation's actual, field-level — the idle-open
 *  and reconnect/foreign-turn path that a live session.runtime frame covers during
 *  a turn. Pure, like reduce. */
fun ConvoState.withActual(snapshot: RuntimeState): ConvoState = copy(runtime = runtime.merge(snapshot))

/** runtimeSummary is the header's one-line "what am I talking to": the ACTUAL
 *  model, its reasoning effort, and live context usage — each shown only when the
 *  node reported it. null when nothing is known yet (an old node, or a session
 *  opened before its first runtime snapshot). Pure; unit-tested. */
internal fun runtimeSummary(rt: RuntimeState): String? {
    val parts = buildList {
        rt.model?.takeIf { it.isNotEmpty() }?.let { add(capDisplay(it)) }
        rt.effort?.takeIf { it.isNotEmpty() }?.let { add(it) }
        contextUsage(rt.contextTokens, rt.contextWindow)?.let { add(it) }
    }
    return parts.joinToString("  ·  ").ifEmpty { null }
}

/** contextUsage renders live context as "used/window" (e.g. "45k/200k") when the
 *  node reported a window; null otherwise. Pure; unit-tested. */
internal fun contextUsage(tokens: Long?, window: Long?): String? {
    val w = window ?: return null
    if (w <= 0) return null
    return "${fmtTokens(tokens ?: 0)}/${fmtTokens(w)}"
}

/** fmtTokens renders a token count compactly (203_000 -> "203k") without
 *  String.format (absent from the common stdlib). */
private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}.${(n / 100_000) % 10}M"
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
}

/** prepend inserts an earlier transcript page before the current window and
 *  advances the cursor. Page turns get their own UI ids (uuid or fresh local);
 *  EXISTING rows keep theirs — a same-identity record landing above no longer
 *  shifts a loaded turn's key the way the old occurrence suffix did. The
 *  subagent index merges page-first (first-appearance order). Pure, like
 *  reduce. */
fun ConvoState.prepend(page: List<Turn>, total: Int, start: Int): ConvoState {
    val used = HashSet<Any>(historyKeys)
    openKey?.let { used += it }
    val (pageKeys, next) = assignUiKeys(page, used, nextLocalId)
    val (pageKids, pageKidIds) = childIndex(page)
    return copy(
        history = page + history,
        historyKeys = pageKeys + historyKeys,
        cursor = Cursor(total, start, cursor?.pageSize ?: page.size.coerceAtLeast(1)),
        children = pageKids + children.filter { it.second !in pageKidIds },
        childIds = childIds + pageKidIds,
        nextLocalId = next,
    )
}

/** truthUp reconciles a freshly re-read NEWEST transcript page into the loaded
 *  window after every SSE (re)subscribe: turns committed in the snapshot→subscribe
 *  gap or during an outage appear exactly once, already-loaded turns are replaced
 *  by their authoritative versions, older prepended history stays put, and
 *  client-only rows behind the window (error lines, an uncommitted optimistic
 *  user echo) survive at the tail. Matching uses the stable turn identity of
 *  turnKey (uuid, else role+ts) plus two commit transitions that change a key:
 *  a live assistant turn later committed with a uuid (matched by assistant ts)
 *  and an optimistic user echo later committed with a ts (matched by content).
 *  When the page no longer overlaps the loaded window (the outage outlasted a
 *  whole page: page start is beyond the last-known total) the stale window is
 *  dropped rather than bridging a silent hole — the cursor stays honest about
 *  contiguity, so "Load earlier" never skips server turns. Replaced turns
 *  donate their UI ids to their authoritative versions, so the merge re-keys
 *  no surviving row. Pure, like reduce. */
fun ConvoState.truthUp(page: List<Turn>, total: Int, start: Int): ConvoState {
    val pageSize = cursor?.pageSize ?: page.size.coerceAtLeast(1)
    if (page.isEmpty()) return settle().copy(cursor = Cursor(total, start, pageSize))
    val old = cursor
    if (old != null && start > old.total) {
        val (pageUiKeys, next) = assignUiKeys(page, HashSet(), nextLocalId)
        val (kids, kidIds) = childIndex(page)
        return copy(
            history = page, historyKeys = pageUiKeys, open = null, openKey = null,
            cursor = Cursor(total, start, pageSize),
            children = kids, childIds = kidIds, nextLocalId = next,
        )
    }
    val loaded = turns          // history + open, materialized once (page-scale op)
    val loadedKeys = uiKeys
    val pageCanon = page.mapTo(HashSet()) { turnKey(it) }
    val committedAssistantTs = page.mapNotNull { t -> t.ts.takeIf { t.role == "assistant" && it.isNotEmpty() } }.toSet()
    fun covered(t: Turn): Boolean = when {
        turnKey(t) in pageCanon -> true
        t.role == "assistant" && t.uuid == null && t.ts.isNotEmpty() && t.ts in committedAssistantTs -> true
        t.role == "user" && t.ts.isEmpty() && page.any { it.role == "user" && it.content == t.content } -> true
        else -> false
    }
    val firstCovered = loaded.indexOfFirst { covered(it) }
    // Nothing covered: the whole local window predates (or exactly abuts) the
    // page — keep it all as older history and append the page after it.
    val prefixEnd = if (firstCovered >= 0) firstCovered else loaded.size
    val prefix = loaded.take(prefixEnd)
    val prefixKeys = loadedKeys.take(prefixEnd)
    // Covered rows are replaced by the page's authoritative versions but DONATE
    // their UI ids to them (matched by canonical identity plus the two commit
    // transitions: live assistant by ts, optimistic user echo by content), so a
    // truth-up re-keys nothing. Uncovered client-only rows keep theirs at the
    // tail; anything else in the covered region drops with its id.
    val donorByCanon = HashMap<Any, Any>()
    val donorLiveAssistant = HashMap<String, Any>()
    val donorEcho = HashMap<String?, Any>()
    val tailTurns = ArrayList<Turn>()
    val tailKeys = ArrayList<Any>()
    for (i in prefixEnd until loaded.size) {
        val t = loaded[i]
        val k = loadedKeys[i]
        when {
            covered(t) -> {
                donorByCanon.putIfAbsent(turnKey(t), k)
                if (t.role == "assistant" && t.uuid == null && t.ts.isNotEmpty()) donorLiveAssistant.putIfAbsent(t.ts, k)
                if (t.role == "user" && t.ts.isEmpty()) donorEcho.putIfAbsent(t.content, k)
            }
            t.role == "error" || (t.role == "user" && t.ts.isEmpty()) -> {
                tailTurns += t
                tailKeys += k
            }
        }
    }
    val used = HashSet<Any>(prefixKeys).apply { addAll(tailKeys) }
    var next = nextLocalId
    val pageUiKeys: List<Any> = page.map { p ->
        val donated = donorByCanon.remove(turnKey(p))
            ?: (if (p.role == "assistant" && p.ts.isNotEmpty()) donorLiveAssistant.remove(p.ts) else null)
            ?: (if (p.role == "user") donorEcho.remove(p.content) else null)
        val key: Any = when {
            donated != null && donated !in used -> donated
            p.uuid != null && p.uuid !in used -> p.uuid
            else -> {
                while (localUiKey(next) in used) next++
                localUiKey(next++)
            }
        }
        used += key
        key
    }
    val merged = prefix + page + tailTurns
    val (kids, kidIds) = childIndex(merged)
    return copy(
        history = merged,
        historyKeys = prefixKeys + pageUiKeys + tailKeys,
        open = null, openKey = null,
        // The loaded region still reaches back to the older of the two starts
        // (kept prepended history), so Load earlier neither skips nor re-fetches.
        cursor = Cursor(total, minOf(old?.start ?: start, start), pageSize),
        children = kids, childIds = kidIds, nextLocalId = next,
    )
}

/** ConvoOwner pins an async conversation request to the immutable {node, session}
 *  it was issued for. Session ids are NOT unique across nodes (mirrored/cloned
 *  data dirs), so the node is part of the identity. */
data class ConvoOwner(val node: String, val session: String)

/** applyEarlierPage folds a fetched earlier transcript page into [state] only
 *  when the request's [owner] still equals the currently-selected [current]
 *  owner — a "Load earlier" response that outlived its conversation (the user
 *  switched session or node while it was in flight) is discarded, never
 *  prepended into the now-open one. Returns null when discarded. Pure. */
fun applyEarlierPage(state: ConvoState, owner: ConvoOwner, current: ConvoOwner?, page: TranscriptPage): ConvoState? =
    if (owner != current) null else state.prepend(page.turns, page.total, page.start)

/** turnKey is a turn's CANONICAL identity (uuid, else role+ts[+length]) — what
 *  truth-up merges by. It is NOT the LazyColumn key anymore: rows render under
 *  the UI ids ConvoState assigns on first entry, which survive the uuid landing
 *  at exit (this key flips uuid-ward then; the UI id doesn't). Assistant turns
 *  key on role+ts ONLY before their uuid — never parts.size, which grows on
 *  every streamed part. */
fun turnKey(t: Turn): Any {
    t.uuid?.let { return it }
    return if (t.role == "assistant") "assistant:${t.ts}"
    else "${t.role}:${t.ts}:${t.content?.length ?: 0}"
}

/** collectChildSessions lists the subagent transcripts spawned in this
 *  conversation (display label → child session id), in order of first
 *  appearance, deduped by child id. Pure — the full-scan reference behind
 *  ConvoState.children, run only on page-scale merges (live part frames fold
 *  in incrementally via the reducer). */
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

// ---- flattened presentation rows (pure; no Compose — unit-testable) ----
//
// The transcript LazyColumn virtualizes PART-level rows, not whole turns: an
// assistant turn contributes [header][part…]([fold])[part…][footer] items, so a
// single huge visible turn no longer eagerly composes every part, and a live
// part append composes ONE new row instead of recomposing the growing turn.
// Row keys derive from the turn's lifetime-stable UI id plus the part ordinal —
// parts only ever append, so an existing row's key never changes across live
// streaming, settle (the key set is IDENTICAL before/after: the rows just move
// from the open block to the history block), prepend and truth-up.

/** Per-turn presentation budget: an unexpanded turn renders at most
 *  TURN_PART_HEAD_ROWS + TURN_PART_TAIL_ROWS part rows … */
internal const val TURN_PART_HEAD_ROWS = 64
internal const val TURN_PART_TAIL_ROWS = 64

/** … and at most ~TURN_PART_HEAD_CHARS + TURN_PART_TAIL_CHARS rendered
 *  (post-body-cap) chars of part content — whichever budget fills first. Parts
 *  between head and tail fold into one expandable "… N more parts" row; nothing
 *  is dropped, and expanding stays cheap because rows are virtualized. */
internal const val TURN_PART_HEAD_CHARS = 128 * 1024
internal const val TURN_PART_TAIL_CHARS = 128 * 1024

/** PartFoldPlan is a turn's part-row window: parts [0, headCount) and
 *  [tailStart, partCount) render as rows; the [hiddenCount] between them fold.
 *  Canonical no-fold form is headCount == tailStart == partCount. */
internal data class PartFoldPlan(val partCount: Int, val headCount: Int, val tailStart: Int) {
    val hiddenCount: Int get() = tailStart - headCount
    val folded: Boolean get() = hiddenCount > 0
}

/** partRenderCost estimates the chars a part row can hand to layout — its
 *  content and workflow summary, each bounded by the per-body render cap. */
private fun partRenderCost(p: TurnPart): Int =
    minOf(p.content.length, RENDER_CHAR_CAP) +
        (p.workflow?.summary?.length?.coerceAtMost(RENDER_CHAR_CAP) ?: 0)

/** partFoldPlan computes the default part-row window under the row + char
 *  budgets. The walk is bounded by the head/tail row caps — never O(parts) —
 *  so planning the open turn on every streamed part frame stays O(1). The tail
 *  is kept because the newest (and final result/error) content lives there.
 *  Pure and deterministic in the parts list, so a settled turn re-plans to the
 *  identical window and row keys survive the settle. */
internal fun partFoldPlan(parts: List<TurnPart>): PartFoldPlan {
    val n = parts.size
    var head = 0
    var spent = 0
    while (head < n && head < TURN_PART_HEAD_ROWS && (head == 0 || spent < TURN_PART_HEAD_CHARS)) {
        spent += partRenderCost(parts[head])
        head++
    }
    var tailCount = 0
    spent = 0
    while (tailCount < n - head && tailCount < TURN_PART_TAIL_ROWS && (tailCount == 0 || spent < TURN_PART_TAIL_CHARS)) {
        spent += partRenderCost(parts[n - 1 - tailCount])
        tailCount++
    }
    val tailStart = n - tailCount
    return if (tailStart <= head) PartFoldPlan(n, n, n) else PartFoldPlan(n, head, tailStart)
}

/** visiblePartRanges maps a plan (+ the user's expand choice) to the part index
 *  ranges rendered as rows. Expanded turns render every part — virtualization
 *  keeps that affordable — with the fold row (as "Show fewer") still present at
 *  its stable position between the ranges. */
internal fun visiblePartRanges(plan: PartFoldPlan, expanded: Boolean): Pair<IntRange, IntRange> = when {
    !plan.folded -> (0 until plan.partCount) to IntRange.EMPTY
    expanded -> (0 until plan.headCount) to (plan.headCount until plan.partCount)
    else -> (0 until plan.headCount) to (plan.tailStart until plan.partCount)
}

internal fun rangeSize(r: IntRange): Int = if (r.isEmpty()) 0 else r.last - r.first + 1

/** turnIsMultiRow: assistant turns (and unknown future roles, which render via
 *  the assistant path) flatten into header/part/footer rows; user, system and
 *  error turns stay single rows under their original keys. */
internal fun turnIsMultiRow(t: Turn): Boolean =
    t.role != "user" && t.role != "system" && t.role != "error"

// Row-key suffixes (the pane prefixes its {node, session} namespace). The
// header/single-row suffix is the bare UI id — the exact key rows had before
// the flatten, so a turn's FIRST row key is also its stable anchor key.
internal fun headerRowSuffix(uiKey: Any): String = "$uiKey"
internal fun partRowSuffix(uiKey: Any, index: Int): String = "$uiKey#p$index"
internal fun moreRowSuffix(uiKey: Any): String = "$uiKey#m"
internal fun footerRowSuffix(uiKey: Any): String = "$uiKey#z"

/** turnRowCount is the number of LazyColumn items a turn contributes — the
 *  arithmetic twin of [turnRowSuffixes] (kept O(1) for the per-frame follow
 *  target; parity is unit-tested). */
internal fun turnRowCount(turn: Turn, plan: PartFoldPlan?, expanded: Boolean): Int {
    if (plan == null) return 1
    val (head, tail) = visiblePartRanges(plan, expanded)
    return 2 + rangeSize(head) + rangeSize(tail) + (if (plan.folded) 1 else 0)
}

/** turnRowSuffixes lists one turn's row keys in render order. */
internal fun turnRowSuffixes(uiKey: Any, turn: Turn, expanded: Boolean): List<String> {
    if (!turnIsMultiRow(turn)) return listOf(headerRowSuffix(uiKey))
    val plan = partFoldPlan(turn.parts)
    val (head, tail) = visiblePartRanges(plan, expanded)
    return buildList(3 + rangeSize(head) + rangeSize(tail)) {
        add(headerRowSuffix(uiKey))
        for (i in head) add(partRowSuffix(uiKey, i))
        if (plan.folded) add(moreRowSuffix(uiKey))
        for (i in tail) add(partRowSuffix(uiKey, i))
        add(footerRowSuffix(uiKey))
    }
}

/** flattenRowSuffixes lists the whole window's row keys in render order — the
 *  page-scale reference behind Load-earlier anchoring (the per-frame path uses
 *  [turnRowCount] arithmetic instead). */
internal fun flattenRowSuffixes(turns: List<Turn>, uiKeys: List<Any>, expanded: Set<Any>): List<String> {
    val out = ArrayList<String>()
    for (i in turns.indices) out += turnRowSuffixes(uiKeys[i], turns[i], uiKeys[i] in expanded)
    return out
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
    // Whole-turn reference render (single-item callers); the transcript pane
    // flattens the same pieces — AssistantTurnHeader + PartView rows — into
    // individually virtualized LazyColumn items instead.
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        AssistantTurnHeader(turn, backend)
        turn.parts.forEach { PartView(it, onOpenChild) }
    }
}

/** AssistantTurnHeader is an assistant turn's first row: harness glyph + model
 *  label (bounded by the shared render cap — a runaway label can't hand
 *  megabytes to layout), plus the "…" placeholder while a live turn has no
 *  parts yet. Layout matches the pre-flatten header exactly. */
@Composable
internal fun AssistantTurnHeader(turn: Turn, backend: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackendMark(backend, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                capDisplay(turn.model ?: "assistant"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(2.dp))
        if (turn.parts.isEmpty()) {
            Text("…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** MorePartsRow is the per-turn budget fold: the parts between the head and
 *  tail windows collapse into this one expandable row (count always honest,
 *  content never dropped — expanding renders every part, still virtualized). */
@Composable
internal fun MorePartsRow(hiddenCount: Int, expanded: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (expanded) "Show fewer parts" else "… $hiddenCount more parts — show all",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SystemMarker(turn: Turn) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            // System markers are compact separators; the shared cap keeps a
            // pathological one from handing megabytes to a single Text.
            capDisplay(turn.content.orEmpty()).ifEmpty { "—" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorLine(turn: Turn) {
    val text = turn.content.orEmpty()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        if (!isRenderCapped(text)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            // Same budget + affordances as part bodies: head + TAIL (the decisive
            // part of a long error usually sits at the end), Show full on demand,
            // Copy raw for the complete original.
            var showFull by rememberSaveable(text) { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showFull) {
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    TruncationActions("showing full text", showFull = true, onToggle = { showFull = false }, raw = text)
                } else {
                    val slices = remember(text) { checkNotNull(renderSlices(text)) }
                    Text(slices.head, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    TruncationActions("… ${slices.omitted} characters hidden", showFull = false, onToggle = { showFull = true }, raw = text)
                    Text(slices.tail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
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
            // Every free-text field below goes through the shared render cap:
            // a multi-MiB workflow summary/name/phase list is server data too,
            // and used to bypass the per-part budget straight into layout.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⌘ Workflow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    capDisplay(wf.name).ifEmpty { "workflow" },
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (wf.status.isNotEmpty()) StatusChip(capDisplay(wf.status))
            }
            if (wf.phases.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    boundedPhaseTrail(wf.phases),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wf.summary.isNotEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    capDisplay(wf.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
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
 *  A very long block (e.g. a 64 KiB tool output) is bounded to a code-point-safe
 *  head + TAIL (the final result/error usually lives at the end) with an honest
 *  fold row, on-demand "Show full" and "Copy raw" — so one giant Text is never
 *  measured/laid out by default yet no content is lost. Normal-sized content
 *  keeps the exact original single-Text layout. */
@Composable
fun MonoBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (!isRenderCapped(text)) {
            MonoText(text)
        } else {
            var showFull by rememberSaveable(text) { mutableStateOf(false) }
            Column {
                if (showFull) {
                    MonoText(text)
                    Box(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
                        TruncationActions("showing full text", showFull = true, onToggle = { showFull = false }, raw = text)
                    }
                } else {
                    val slices = remember(text) { checkNotNull(renderSlices(text)) }
                    MonoText(slices.head)
                    Box(Modifier.padding(horizontal = 8.dp)) {
                        TruncationActions("… ${slices.omitted} characters hidden", showFull = false, onToggle = { showFull = true }, raw = text)
                    }
                    MonoText(slices.tail)
                }
            }
        }
    }
}

@Composable
private fun MonoText(text: String) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
    )
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
