package io.hero.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Sessions.kt is the Sessions section: the node/session lists and the open
// conversation (transcript + composer + pending permissions), plus the
// subagent drill-in stack. App.kt owns only the navigation chrome.

// TRANSCRIPT_PAGE is the turns-per-page the app requests (newest page on open;
// "Load earlier" prepends further pages).
private const val TRANSCRIPT_PAGE = 40

/** streamBackoffMillis is the delay before live-stream reconnect [attempt]
 *  (0-based, reset on every successful subscribe): capped full-jitter exponential
 *  backoff — a uniform draw from [0, min(1s·2^attempt, 30s)]. Replaces the fixed
 *  1.5s bare loop, which hammered dead endpoints and thundering-herded every
 *  viewer at a control-plane restart. Pure given [random]; unit-tested. */
internal fun streamBackoffMillis(attempt: Int, random: Random = Random.Default): Long {
    val exp = attempt.coerceIn(0, 5) // 1s·2^5 is already past the cap
    val ceiling = (1_000L shl exp).coerceAtMost(30_000L)
    return random.nextLong(ceiling + 1)
}

/** sessionLive decides whether an opened session still needs the live tail from
 *  its SERVER-reported list status. Only an explicitly finished state skips the
 *  subscription; "running", anything unknown, and a session missing from the
 *  list (null status) all subscribe — the /parts feed is node-wide, so an extra
 *  idle subscription is free, while the old inference ("drilled-in ⇒ transcript
 *  complete") froze still-running subagents at their open snapshot. Pure. */
internal fun sessionLive(status: String?): Boolean = when (status?.trim()?.lowercase()) {
    "completed", "complete", "finished", "done", "exited", "exit", "stopped", "errored", "error", "failed" -> false
    else -> true
}

/** anchorAfterPrepend computes the LazyColumn (item index, scroll offset) that
 *  keeps the pre-prepend first visible row stationary after an earlier page
 *  lands above it. [anchorKey] is that row's stable display key recorded before
 *  the load (since the part-level flatten: turn UI id + part ordinal — stable
 *  because prepends never touch a loaded turn's parts); [keysAfter] the merged
 *  window's row keys; [hasHeaderAfter] whether the "Load earlier" item still
 *  occupies index 0 — on the LAST page it disappears, which the old
 *  `oldIndex + added` arithmetic ignored, landing one row down. A vanished key
 *  falls back to the top of the window. Pure; unit-tested. */
internal fun anchorAfterPrepend(
    anchorKey: Any?,
    keysAfter: List<Any>,
    hasHeaderAfter: Boolean,
    offset: Int,
): Pair<Int, Int> {
    val turnIdx = if (anchorKey == null) -1 else keysAfter.indexOf(anchorKey)
    if (turnIdx < 0) return 0 to 0
    return (turnIdx + if (hasHeaderAfter) 1 else 0) to offset
}

// Bounds for DraftStore: how many conversations keep a parked draft and how
// large one parked draft may be. The store is a navigation convenience (bring
// back what you were typing), not a document store — a pathological draft is
// dropped rather than retained forever, and old conversations' drafts are
// evicted put-recency-first.
internal const val MAX_DRAFT_ENTRIES = 32
internal const val MAX_DRAFT_CHARS = 16 * 1024

/** DraftStore owns the composer draft PER {node, session}. The composer used to
 *  share one identity-less string across every conversation: text typed for A
 *  appeared verbatim in B's composer after a switch and could be sent to B.
 *  Now a switch parks A's draft under A's identity and restores B's own (or
 *  empty). Bounded (MAX_DRAFT_ENTRIES / MAX_DRAFT_CHARS); pure; unit-tested. */
internal class DraftStore {
    private val drafts = LinkedHashMap<String, String>()

    // A NUL separator cannot appear in a node/session id, so the pair key is
    // unambiguous ("a"+"b c" never collides with "a b"+"c").
    private fun key(node: String, session: String) = node + "\u0000" + session

    fun get(node: String?, session: String?): String {
        if (node == null || session == null) return ""
        return drafts[key(node, session)].orEmpty()
    }

    fun put(node: String?, session: String?, text: String) {
        if (node == null || session == null) return
        val k = key(node, session)
        drafts.remove(k) // re-insert so put order approximates recency
        if (text.isEmpty() || text.length > MAX_DRAFT_CHARS) return
        drafts[k] = text
        while (drafts.size > MAX_DRAFT_ENTRIES) drafts.remove(drafts.keys.first())
    }

    fun clear(node: String?, session: String?) {
        if (node == null || session == null) return
        drafts.remove(key(node, session))
    }

    /** size is exposed for the bound regressions only. */
    val size: Int get() = drafts.size
}

/** EarlierPage reports one applied "Load earlier" fetch back to the pane: how
 *  many turns landed and the merged window (whose stable UI ids the pane
 *  flattens into row keys to re-anchor scroll, and whose cursor says whether
 *  the header item still renders). null = nothing applied (no more pages,
 *  fetch failed, or the response outlived its conversation and was discarded
 *  by the owner CAS). */
data class EarlierPage(val added: Int, val merged: ConvoState) {
    val hasMore: Boolean get() = merged.cursor?.hasMore == true
}

/**
 * SessionSel is the hoisted selection state: which node, which root session,
 * and the stack of subagent child sessions drilled into on top of it. Held by
 * MainScreen so the chrome (dock visibility, compact navigation) can react;
 * everything else about the Sessions screen stays private to it. Pure — the
 * back-navigation order (pop drill, then close the session) is unit-tested.
 */
data class SessionSel(
    val node: String? = null,
    val session: String? = null,
    val drill: List<String> = emptyList(),
) {
    /** The session whose transcript is rendered: top of the drill stack, else the root. */
    val active: String? get() = drill.lastOrNull() ?: session
    /** Drilled-in subagent transcripts are complete and read-only. */
    val readonly: Boolean get() = drill.isNotEmpty()
    /** One back step: pop the drill stack first, then close the open session. */
    fun pop(): SessionSel =
        if (drill.isNotEmpty()) copy(drill = drill.dropLast(1))
        else copy(session = null, drill = emptyList())
}

@Composable
internal fun SessionsScreen(api: Api, settings: Settings, sel: SessionSel, onSel: (SessionSel) -> Unit) {
    val scope = rememberCoroutineScope()
    // The CURRENT selection for async completions. Handlers launched from a
    // previous composition captured a stale `sel`; any late response must CAS
    // against this before touching shared conversation state.
    val currentSel = rememberUpdatedState(sel)
    // Sidebar collapse survives restarts (a workspace-layout choice, not UI froth).
    var collapsed by remember { mutableStateOf(settings.getString(Keys.SidebarCollapsed) == "1") }
    // Nodes render straight from the fleet cache (fed here and by the badge
    // poll) — re-entering the tab shows data instantly, no spinner.
    val cachedNodes by FleetCache.nodes
    val nodes = cachedNodes?.filter { it.connected } ?: emptyList()
    val nodesLoading = cachedNodes == null
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var convo by remember { mutableStateOf(ConvoState()) }
    var pend by remember { mutableStateOf<List<Pending>>(emptyList()) }
    // The composer draft is OWNED per {node, root session} (the composer always
    // sends to the ROOT session; drill-ins are read-only): switching
    // conversations parks this one's text in the bounded DraftStore and
    // restores the target's own draft — text typed for A can no longer appear
    // in (or be sent to) B. Keyed remember drops the state on switch.
    val drafts = remember { DraftStore() }
    var input by remember(sel.node, sel.session) { mutableStateOf(drafts.get(sel.node, sel.session)) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var nodesError by remember { mutableStateOf<String?>(null) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    // A warm node whose refresh keeps failing keeps its last-good list, but is
    // flagged stale so it doesn't masquerade as authoritative/fresh.
    var sessionsStale by remember { mutableStateOf(false) }
    var nodesReload by remember { mutableStateOf(0) }
    var sessionsReload by remember { mutableStateOf(0) }
    var showStart by remember { mutableStateOf(false) }
    var inspector by remember { mutableStateOf(false) }

    // Backend tag for the open session — drives the harness icon only, never parsing.
    // Subagents share their parent's harness, so the root session's tag applies.
    // Memoized on (sessions, sel.session): without this the whole list is
    // rescanned twice on every live frame (each part/status recomposes here).
    val rootSession = remember(sessions, sel.session) { sessions.firstOrNull { it.id == sel.session } }
    val backend = rootSession?.backend.orEmpty()
    val title = rootSession?.let { it.title.ifEmpty { it.id } } ?: sel.session.orEmpty()
    val active = sel.active
    val readonly = sel.readonly

    // Mid-conversation model/effort switcher: this session's backend catalog and
    // the current selection (reset when the open session changes; "" = keep current).
    var switchModel by remember(sel.session) { mutableStateOf("") }
    var switchEffort by remember(sel.session) { mutableStateOf("") }
    var catModels by remember { mutableStateOf<List<HarnessModel>>(emptyList()) }
    var catEffortLevels by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(sel.node, backend) {
        catModels = emptyList(); catEffortLevels = emptyList()
        val n = sel.node
        if (n != null && backend.isNotEmpty()) {
            val gen = FleetCache.generation
            // Cache-first so opening a session is instant. The cache is refilled
            // by the Nodes inspector and invalidated on Save+apply/Install; a hit
            // here deliberately skips the heavy status RPC.
            val hs = FleetCache.harnessOf(n)
                ?: runCatchingCancellable { api.harness(n) }.getOrNull()?.also { FleetCache.putHarness(n, gen, it) }
            hs?.backends?.firstOrNull { it.backend == backend }?.catalog?.let { c ->
                catModels = c.models.filter { !it.hidden }
                catEffortLevels = c.effort_levels
            }
        }
    }

    // Node and session lists poll quietly (cheap GETs) so they stay live
    // without pull-to-refresh; results land in FleetCache so revisits are
    // instant. A failure only surfaces when there is nothing usable on screen,
    // and Retry bumps the reload key.
    LaunchedEffect(nodesReload) {
        val gen = FleetCache.generation
        while (isActive) {
            runCatchingCancellable { api.nodes() }
                .onSuccess { FleetCache.putNodes(gen, it); nodesError = null }
                .onFailure { if (FleetCache.nodes.value == null) nodesError = it.message ?: "couldn't load nodes" }
            delay(15_000)
        }
    }
    LaunchedEffect(sel.node, sessionsReload) {
        val n = sel.node
        if (n == null) { sessions = emptyList(); sessionsError = null; sessionsStale = false; return@LaunchedEffect }
        val gen = FleetCache.generation
        // Cached page first — the spinner is reserved for a truly cold node.
        val cached = FleetCache.sessionsOf(n)
        sessions = cached.orEmpty()
        sessionsLoading = cached == null
        sessionsError = null
        sessionsStale = false
        while (isActive) {
            runCatchingCancellable { api.sessions(n) }
                .onSuccess { sessions = it; FleetCache.putSessions(n, gen, it); sessionsError = null; sessionsStale = false }
                .onFailure {
                    // Cold node: surface the error. Warm node: keep the last-good
                    // list on screen but mark it stale rather than treating the
                    // masked failure as a fresh, authoritative result.
                    if (sessions.isEmpty()) sessionsError = it.message ?: "couldn't load sessions"
                    else sessionsStale = true
                }
            sessionsLoading = false
            delay(10_000)
        }
    }
    // Seed the newest transcript page, then subscribe the grouped live tail and
    // reconcile it. Keyed on (node, active session) so it cancels + restarts on
    // switch. Every successful (re)subscribe TRUTH-UPS: it re-reads the newest
    // page and merges by stable turn identity, so frames committed in the
    // snapshot→subscribe gap or during an outage are recovered instead of lost
    // until reopen. Transient stream failures (EOF, resets, 5xx) reconnect with
    // capped full-jitter backoff; permanent ones (401/403 auth, a non-SSE
    // endpoint) stop the loop and surface an error row — the app's existing 401
    // posture (explicit error, no silent retry), matching orThrow/me().
    LaunchedEffect(sel.node, active) {
        val n = sel.node; val s = active
        if (n == null || s == null) { convo = ConvoState(); return@LaunchedEffect }
        convo = ConvoState()
        runCatchingCancellable { api.transcript(n, s, TRANSCRIPT_PAGE) }.getOrNull()?.let { page ->
            convo = ConvoState(turns = page.turns, cursor = Cursor(page.total, page.start, TRANSCRIPT_PAGE))
        }
        // Whether a drilled-in subagent still needs the live tail is the
        // SERVER's call (its status in the sessions list) — "readonly" only
        // shapes the UI and is not evidence the transcript ended; a child that
        // is still running must keep receiving part/exit frames.
        if (readonly && !sessionLive(sessions.firstOrNull { it.id == s }?.status)) return@LaunchedEffect
        var attempt = 0
        while (isActive) {
            val err = runCatchingCancellable {
                api.liveFrames(n, s, onSubscribed = {
                    val page = api.transcript(n, s, TRANSCRIPT_PAGE)
                    convo = convo.truthUp(page.turns, page.total, page.start)
                    attempt = 0
                }).collect { frame -> convo = convo.reduce(frame) }
            }.exceptionOrNull()
            if (!isActive) break
            if (err != null && isPermanentStreamError(err)) {
                convo = convo.reduce(LiveFrame.ErrorFrame("live updates stopped: ${err.message ?: "stream error"}"))
                break
            }
            delay(streamBackoffMillis(attempt))
            attempt += 1
        }
    }
    // Poll pending permission requests for the WHOLE node while a session is
    // open: the composer bar shows the current session's, the inspector's
    // permission center shows (and answers) all of them.
    LaunchedEffect(sel.node, active, readonly) {
        while (sel.node != null && active != null && !readonly) {
            runCatchingCancellable { pend = api.pending(sel.node!!) }
            delay(3000)
        }
        pend = emptyList()
    }

    if (showStart && sel.node != null) {
        StartSessionDialog(api, sel.node!!, onDismiss = { showStart = false }) { newID ->
            showStart = false
            sessionsReload++
            // Jump straight into the new session instead of leaving the user on the
            // list wondering whether it worked.
            if (newID.isNotEmpty()) onSel(sel.copy(session = newID, drill = emptyList()))
        }
    }

    // One back behavior for every affordance (system gesture, header arrow,
    // subagent bar, Esc): pop a drill-in first, then close the open session.
    val onBackInPane: () -> Unit = {
        val next = sel.pop()
        if (next.session == null) convo = ConvoState()
        onSel(next)
    }
    PredictiveBack(enabled = sel.session != null, onBack = onBackInPane)

    val toggleCollapse: () -> Unit = {
        collapsed = !collapsed
        scope.launch { settings.update { it[Keys.SidebarCollapsed] = if (collapsed) "1" else "0" } }
    }
    // Desktop shortcuts: Ctrl/Cmd+B collapses the sidebar, Ctrl/Cmd+I toggles
    // the session inspector, Esc backs out of the open conversation (drill
    // first). Registered after MainScreen's handler so it gets first look (LIFO).
    KeyHandler { e ->
        when {
            e.type != KeyEventType.KeyDown -> false
            (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.B -> { toggleCollapse(); true }
            (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.I && sel.session != null -> { inspector = !inspector; true }
            e.key == Key.Escape && sel.session != null -> { onBackInPane(); true }
            else -> false
        }
    }

    val listPane: @Composable (Modifier, Boolean, (() -> Unit)?) -> Unit = { m, rail, onToggle ->
        SessionListPane(
            nodes = nodes, nodesLoading = nodesLoading, nodesError = nodesError,
            sessions = sessions, sessionsLoading = sessionsLoading, sessionsError = sessionsError,
            sessionsStale = sessionsStale,
            selNode = sel.node, selSession = sel.session,
            onSelectNode = { onSel(SessionSel(node = it)) },
            onSelectSession = { onSel(sel.copy(session = it, drill = emptyList())) },
            onNewSession = { showStart = true },
            onRetryNodes = { nodesError = null; nodesReload++ },
            onRetrySessions = { sessionsReload++ },
            collapsed = rail, onToggleCollapse = onToggle,
            modifier = m,
        )
    }
    val convoPane: @Composable (Boolean, Modifier) -> Unit = { showBack, m ->
        ConversationPane(
            convo = convo, pend = pend, backend = backend, title = title,
            readonly = readonly, showBack = showBack,
            node = sel.node.orEmpty(), sessionId = sel.active.orEmpty(),
            onToggleInspector = { inspector = !inspector },
            onLoadEarlier = {
                // Fetch the page before the window and prepend it. The request's
                // owner is the immutable {node, session} captured HERE; the
                // response applies only if that owner still matches the current
                // selection (CAS via applyEarlierPage) — a page that returns
                // after switching to another session/node is discarded, never
                // prepended into the now-open conversation.
                val n = sel.node; val s = sel.active; val cur = convo.cursor
                if (n == null || s == null || cur == null || !cur.hasMore) null
                else runCatchingCancellable { api.transcript(n, s, cur.pageSize, offset = cur.earlierOffset()) }
                    .getOrNull()?.let { page ->
                        val now = currentSel.value
                        val nowOwner = now.node?.let { nn -> now.active?.let { ss -> ConvoOwner(nn, ss) } }
                        applyEarlierPage(convo, ConvoOwner(n, s), nowOwner, page)?.let { merged ->
                            convo = merged
                            EarlierPage(page.turns.size, merged)
                        }
                    }
            },
            input = input, onInput = { input = it; drafts.put(sel.node, sel.session, it) },
            switchModels = catModels, effortLevels = catEffortLevels,
            model = switchModel, onModel = { switchModel = it },
            effort = switchEffort, onEffort = { switchEffort = it },
            onSend = {
                val n = sel.node; val s = sel.session; val t = input.trim()
                if (n != null && s != null && t.isNotEmpty()) {
                    input = ""
                    drafts.clear(n, s)
                    convo = convo.optimisticUser(t)
                    val m = switchModel; val e = if (catEffortLevels.isNotEmpty()) switchEffort else ""
                    scope.launch {
                        // The optimistic echo must not mask a failed send — but a
                        // failure that returns after switching conversations must
                        // not inject its error row into the new one either.
                        runCatchingCancellable { api.send(n, s, t, m, e) }
                            .onFailure {
                                val now = currentSel.value
                                if (now.node == n && now.active == s) {
                                    convo = convo.reduce(LiveFrame.ErrorFrame("send failed: ${it.message ?: "error"}"))
                                }
                            }
                    }
                }
            },
            onRespond = { p, behavior ->
                val n = sel.node; val s = sel.active
                if (n != null) scope.launch {
                    runCatchingCancellable { api.respond(n, p.id, behavior) }
                        .onFailure {
                            val now = currentSel.value
                            if (now.node == n && now.active == s) {
                                convo = convo.reduce(LiveFrame.ErrorFrame("respond failed: ${it.message ?: "error"}"))
                            }
                        }
                }
            },
            onOpenChild = { childId -> onSel(sel.copy(drill = sel.drill + childId)) },
            onBack = onBackInPane,
            modifier = m,
        )
    }
    // The conversation area with its right-hand inspector (workspace tools:
    // session facts, the node's permission center, subagents, quick actions).
    val convoWithInspector: @Composable (Boolean) -> Unit = { showBack ->
        InspectorHost(
            open = inspector,
            onClose = { inspector = false },
            panelTitle = "Session",
            main = { convoPane(showBack, Modifier.fillMaxSize()) },
            panel = {
                SessionInspector(
                    session = rootSession, sessionId = sel.session.orEmpty(),
                    model = convo.runtimeModel, pend = pend, children = convo.children,
                    onRespond = { p, behavior ->
                        val n = sel.node; val s = sel.active
                        if (n != null) scope.launch {
                            runCatchingCancellable { api.respond(n, p.id, behavior) }
                                .onFailure {
                                    val now = currentSel.value
                                    if (now.node == n && now.active == s) {
                                        convo = convo.reduce(LiveFrame.ErrorFrame("respond failed: ${it.message ?: "error"}"))
                                    }
                                }
                        }
                    },
                    onOpenChild = { childId -> onSel(sel.copy(drill = sel.drill + childId)) },
                    onNewSession = { showStart = true },
                )
            },
        )
    }

    if (LocalWindowWidth.current == WindowWidth.Compact) {
        // Stacked navigation: list fullscreen ⇄ conversation fullscreen (the
        // inspector stacks once more on top, inside InspectorHost). Same motion
        // as the login steps; direction follows open/close.
        AnimatedContent(
            targetState = sel.session != null,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (slideInHorizontally(tween(280)) { w -> dir * w } + fadeIn(tween(280)))
                    .togetherWith(slideOutHorizontally(tween(240)) { w -> -dir * w } + fadeOut(tween(180)))
            },
            modifier = Modifier.fillMaxSize(),
            label = "sessionsPane",
        ) { open ->
            if (open) convoWithInspector(true)
            else listPane(Modifier.fillMaxSize(), false, null)
        }
    } else {
        val railWidth by animateDpAsState(if (collapsed) 52.dp else 240.dp, tween(200), label = "sidebar")
        Row(Modifier.fillMaxSize()) {
            listPane(Modifier.width(railWidth).fillMaxHeight(), collapsed, toggleCollapse)
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            if (sel.session == null) EmptyState()
            else convoWithInspector(false)
        }
    }
}

/** PendingPartition memoizes the inspector's actionable-first ordering (the
 *  open session's requests before the rest of the node's): it recomputes only
 *  when {pend, sessionId} actually change, so live part frames and equal-list
 *  poll ticks reuse the cached ordering. sortedByDescending is stable, so rows
 *  within each group keep their server order. [computations] backs the unit
 *  test's recompute counter. */
internal class PendingPartition {
    var computations = 0
        private set
    private var lastPend: List<Pending>? = null
    private var lastSession: String? = null
    private var cached: List<Pending> = emptyList()
    fun of(pend: List<Pending>, sessionId: String): List<Pending> {
        if (pend == lastPend && sessionId == lastSession) return cached
        computations++
        lastPend = pend
        lastSession = sessionId
        cached = pend.sortedByDescending { it.session_id == sessionId }
        return cached
    }
}

// SessionInspector: what you're talking to and the levers you have — all wired
// to real endpoints (respond, startSession) or real transcript/list data.
// `children` is ConvoState's incrementally maintained subagent index; nothing
// here rescans the transcript on part frames.
@Composable
private fun SessionInspector(
    session: Session?, sessionId: String, model: String?,
    pend: List<Pending>, children: List<Pair<String, String>>,
    onRespond: (Pending, String) -> Unit,
    onOpenChild: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val partition = remember { PendingPartition() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelSection("Session") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackendMark(session?.backend.orEmpty(), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    session?.let { it.title.ifEmpty { it.id } } ?: sessionId,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            KeyValueRow("Id", sessionId)
            session?.backend?.takeIf { it.isNotEmpty() }?.let { KeyValueRow("Harness", it) }
            model?.let { KeyValueRow("Model", it) }
            session?.cwd?.takeIf { it.isNotEmpty() }?.let { KeyValueRow("Cwd", it) }
            session?.status?.takeIf { it.isNotEmpty() }?.let { KeyValueRow("Status", it) }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { clipboard.setText(AnnotatedString(sessionId)) }) { Text("Copy session id") }
        }
        PanelSection("Pending permissions") {
            if (pend.isEmpty()) HintText("None.")
            else {
                // Current session's requests first; others still answerable here.
                // Memoized ordering + a stable per-request key, so answering or
                // re-sorting one request never hands its row state to another.
                partition.of(pend, sessionId).forEachIndexed { i, p ->
                    key(p.id) {
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text(
                            p.tool_name.ifEmpty { p.event.ifEmpty { "request" } },
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        )
                        if (p.session_id.isNotEmpty() && p.session_id != sessionId) {
                            Text("session ${p.session_id}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            TextButton(onClick = { onRespond(p, "allow") }) { Text("Allow") }
                            TextButton(onClick = { onRespond(p, "deny") }) { Text("Deny") }
                        }
                    }
                }
            }
        }
        if (children.isNotEmpty()) {
            PanelSection("Subagents") {
                children.forEach { (label, id) -> LinkRow(label) { onOpenChild(id) } }
            }
        }
        PanelSection("Quick actions") {
            OutlinedButton(onClick = onNewSession) { Text("New session on this node") }
        }
    }
}

// SessionListPane is the stateless node + session picker. A LazyColumn (with
// the section headers as items) so long session lists scroll instead of clip.
// With onToggleCollapse it grows the workspace header (brand + collapse) and a
// 52dp mini-rail mode; compact passes null and always renders the full list.
@Composable
private fun SessionListPane(
    nodes: List<NodeView>, nodesLoading: Boolean, nodesError: String?,
    sessions: List<Session>, sessionsLoading: Boolean, sessionsError: String?,
    sessionsStale: Boolean = false,
    selNode: String?, selSession: String?,
    onSelectNode: (String) -> Unit, onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onRetryNodes: () -> Unit, onRetrySessions: () -> Unit,
    collapsed: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxSize()) {
            if (onToggleCollapse != null) {
                // The sidebar header carries the brand — desktop has no top bar.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                ) {
                    if (!collapsed) {
                        LogoMark(Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = onToggleCollapse, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight
                            else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (collapsed) {
                CollapsedRail(nodes, sessions, selNode, selSession, onSelectNode, onSelectSession)
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    item(key = "nodes-header") { SectionLabel("NODES") }
                    when {
                        nodesError != null -> item(key = "nodes-error") { ErrorRow(nodesError, onRetryNodes) }
                        nodesLoading -> item(key = "nodes-loading") { PaneLoader() }
                        nodes.isEmpty() -> item(key = "nodes-empty") { HintText("No nodes online") }
                        else -> items(nodes, key = { "n:${it.node_id}" }) { n ->
                            ItemContextMenu(
                                listOf("Copy node id" to { clipboard.setText(AnnotatedString(n.node_id)) }),
                            ) {
                                NodeItem(n, selected = selNode == n.node_id) { onSelectNode(n.node_id) }
                            }
                        }
                    }
                    item(key = "sessions-header") {
                        Column(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel("SESSIONS")
                                Spacer(Modifier.weight(1f))
                                if (selNode != null) {
                                    IconButton(onClick = onNewSession, modifier = Modifier.size(26.dp)) {
                                        Icon(
                                            Icons.Filled.Add, contentDescription = "New session",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                            // Warm-cache refresh is failing: keep the list but say
                            // so, rather than passing off last-good as fresh.
                            if (sessionsStale && sessions.isNotEmpty()) {
                                Text(
                                    "Couldn't refresh — showing last known.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                    when {
                        sessionsError != null -> item(key = "sessions-error") { ErrorRow(sessionsError, onRetrySessions) }
                        sessionsLoading -> item(key = "sessions-loading") { PaneLoader() }
                        selNode != null && sessions.isEmpty() -> item(key = "sessions-empty") { HintText("No sessions") }
                        else -> items(sessions, key = { "s:${it.id}" }) { s ->
                            ItemContextMenu(
                                listOf(
                                    "Open" to { onSelectSession(s.id) },
                                    "Copy session id" to { clipboard.setText(AnnotatedString(s.id)) },
                                ),
                            ) {
                                SessionItem(s, selected = selSession == s.id) { onSelectSession(s.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// CollapsedRail is the 52dp sidebar: nodes as initial tiles, sessions as
// harness-glyph tiles, full names on hover. Click-through works directly —
// expanding is never required to navigate.
@Composable
private fun CollapsedRail(
    nodes: List<NodeView>, sessions: List<Session>,
    selNode: String?, selSession: String?,
    onSelectNode: (String) -> Unit, onSelectSession: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(nodes, key = { "n:${it.node_id}" }) { n ->
            val selected = selNode == n.node_id
            RailTile(label = "${n.node_id}  ·  ${n.scope}", selected = selected, onClick = { onSelectNode(n.node_id) }) {
                Text(
                    n.node_id.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (nodes.isNotEmpty() && sessions.isNotEmpty()) {
            item(key = "rail-divider") { HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
        }
        items(sessions, key = { "s:${it.id}" }) { s ->
            val selected = selSession == s.id
            RailTile(label = s.title.ifEmpty { s.id }, selected = selected, onClick = { onSelectSession(s.id) }) {
                BackendMark(
                    s.backend, Modifier.size(16.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailTile(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.padding(vertical = 2.dp).size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .hoverHighlight(RoundedCornerShape(9.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** isReallyAtBottom reports whether the transcript is scrolled to its true end: the
 *  final item's bottom edge ([lastItemEndOffset]) is at or above the viewport end
 *  ([viewportEndOffset]). Unlike a last-visible-index check it stays false when the
 *  last turn is taller than the viewport and the user is parked at its TOP, so live
 *  appends don't yank a reader. Pure & unit-tested; the composable derives the
 *  offsets from LazyListLayoutInfo (equivalent to !canScrollForward at the end). */
internal fun isReallyAtBottom(lastItemEndOffset: Int, viewportEndOffset: Int): Boolean =
    lastItemEndOffset <= viewportEndOffset

/** followToBottom scrolls to [target] (the last turn) and then, when that turn is
 *  taller than the viewport, pushes the remaining overflow so its END — the newest
 *  content — is what shows, instead of only aligning its start. Used for both the
 *  initial open and live follow. */
private suspend fun LazyListState.followToBottom(target: Int, animate: Boolean) {
    if (animate) animateScrollToItem(target) else scrollToItem(target)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    if (last.index >= info.totalItemsCount - 1) {
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val overflow = last.size - viewport
        if (overflow > 0) {
            if (animate) animateScrollToItem(last.index, overflow) else scrollToItem(last.index, overflow)
        }
    }
}

// ConversationPane renders one open session: header, transcript, pending
// permission bars and the composer. Stateless apart from its scroll position.
@Composable
private fun ConversationPane(
    convo: ConvoState, pend: List<Pending>, backend: String, title: String,
    readonly: Boolean, showBack: Boolean, node: String, sessionId: String,
    onToggleInspector: () -> Unit,
    onLoadEarlier: suspend () -> EarlierPage?,
    input: String, onInput: (String) -> Unit, onSend: () -> Unit,
    switchModels: List<HarnessModel> = emptyList(), effortLevels: List<String> = emptyList(),
    model: String = "", onModel: (String) -> Unit = {},
    effort: String = "", onEffort: (String) -> Unit = {},
    onRespond: (Pending, String) -> Unit,
    onOpenChild: (String) -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Local-state namespace: the FULL conversation identity {node, session}.
    // Session ids are not unique across nodes (mirrored/cloned data dirs), and
    // the Quick Switcher swaps both at once — keying on the session alone would
    // carry node A's scroll/loading/row state onto node B's same-id session.
    val paneKey = "$node\u0000$sessionId"
    Column(modifier) {
        ConversationHeader(title, convo.runtimeModel, backend, showBack, onBack, onToggleInspector)
        if (readonly) SubagentBar(onBack)
        // Scroll state is per conversation (keyed), not shared across switches.
        val listState = remember(paneKey) { LazyListState() }
        var didInitialScroll by remember(paneKey) { mutableStateOf(false) }
        var loadingEarlier by remember(paneKey) { mutableStateOf(false) }
        // Stick to the bottom only when already there — don't yank a user who
        // scrolled up to read history. Keyed on the LAST turn (not list size) so
        // prepending an earlier page never triggers a re-stick.
        val atBottom by remember(paneKey) {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                // Really at bottom only when the LAST item is visible AND its bottom
                // edge is within the viewport. A last turn taller than the viewport
                // parked at its TOP is the last visible item but is NOT at bottom —
                // the old index-only check wrongly returned true, and auto-scroll then
                // only re-aligned that turn's start (see isReallyAtBottom).
                last == null || (last.index >= info.totalItemsCount - 1 &&
                    isReallyAtBottom(last.offset + last.size, info.viewportEndOffset))
            }
        }
        // Per-turn "show all parts" expansion of the budget fold, keyed by the
        // turn's lifetime UI id — so it survives live→settle (the id moves with
        // the turn) and prepends, and resets with the conversation.
        var expandedParts by remember(paneKey) { mutableStateOf(setOf<Any>()) }
        val history = convo.history
        val historyKeys = convo.historyKeys
        // Fold plans are pure in each turn's parts; the history list reference
        // is untouched by live part frames (ConvoState's contract), so this
        // recomputes only on page-scale changes (seed, settle, prepend,
        // truth-up) — never per streamed part.
        val historyPlans = remember(history) {
            history.map { t -> if (turnIsMultiRow(t)) partFoldPlan(t.parts) else null }
        }
        val historyRowCount = remember(history, historyKeys, expandedParts) {
            var n = 0
            for (i in history.indices) n += turnRowCount(history[i], historyPlans[i], historyKeys[i] in expandedParts)
            n
        }
        val open = convo.open
        // Planning the open turn per frame is O(head+tail budget), not O(parts).
        val openPlan = open?.let { partFoldPlan(it.parts) }
        val openExpanded = convo.openKey?.let { it in expandedParts } == true
        val hasEarlier = convo.cursor?.hasMore == true
        val totalRows = (if (hasEarlier) 1 else 0) + historyRowCount +
            (if (open != null) turnRowCount(open, openPlan, openExpanded) else 0)
        val totalRowsNow = rememberUpdatedState(totalRows)
        val togglePartsFold: (Any) -> Unit = { k ->
            expandedParts = if (k in expandedParts) expandedParts - k else expandedParts + k
        }
        val lastKey = convo.lastUiKey
        LaunchedEffect(paneKey, lastKey, convo.lastTurn?.parts?.size) {
            if (convo.turnCount == 0) return@LaunchedEffect
            // The window's last ROW (the open turn's trailing row under the
            // part-level flatten), read post-recomposition so a part landing as
            // a new row is already counted.
            val target = totalRowsNow.value - 1
            if (!didInitialScroll) {
                // The seeded page must land at its newest CONTENT unconditionally —
                // deciding via atBottom races the first non-empty layout pass.
                listState.followToBottom(target, animate = false)
                didInitialScroll = true
            } else if (atBottom) {
                // Live follow: keep the true end in view only while the user is
                // actually parked there; once they scroll up, atBottom is false and
                // we stop pulling them back.
                listState.followToBottom(target, animate = true)
            }
        }
        // Row keys come straight from ConvoState's incremental UI id index —
        // assigned once per turn, stable for the row's whole life (live→exit,
        // prepends, truth-ups) — so nothing is rescanned per frame here.
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            if (hasEarlier) {
                item(key = "load-earlier") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                        if (loadingEarlier) {
                            Text(
                                "Loading…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            TextButton(onClick = {
                                scope.launch {
                                    loadingEarlier = true
                                    // Anchor the first visible ROW by its stable key (turn UI
                                    // id + part ordinal), not by raw item index: when the
                                    // fetched page is the LAST one, hasMore flips false and
                                    // this very header item disappears — index math
                                    // (`old + added`) would land one row down. Row keys
                                    // survive the prepend unchanged (a prepend never touches
                                    // a loaded turn's parts, plans or expansion).
                                    val first = listState.firstVisibleItemIndex
                                    val offset = if (first >= 1) listState.firstVisibleItemScrollOffset else 0
                                    val rowsBefore = flattenRowSuffixes(convo.turns, convo.uiKeys, expandedParts)
                                    val anchorKey = rowsBefore.getOrNull(maxOf(first, 1) - 1)
                                    val res = onLoadEarlier()
                                    if (res != null && res.added > 0) {
                                        // Scroll only after the new page reaches layout —
                                        // earlier, scrollToItem clamps to the OLD item count
                                        // and the view lands at the bottom instead. The expected
                                        // count accounts for this header leaving on the last page.
                                        val merged = res.merged
                                        val rowsAfter = flattenRowSuffixes(merged.turns, merged.uiKeys, expandedParts)
                                        val expected = rowsAfter.size + (if (res.hasMore) 1 else 0)
                                        withTimeoutOrNull(1000) {
                                            snapshotFlow { listState.layoutInfo.totalItemsCount }
                                                .first { it >= expected }
                                        }
                                        val (idx, off) = anchorAfterPrepend(anchorKey, rowsAfter, res.hasMore, offset)
                                        listState.scrollToItem(idx, off)
                                    }
                                    loadingEarlier = false
                                }
                            }) { Text("Load earlier", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
            // Row keys carry the pane namespace: two nodes can hold the same
            // session id with identical turn uuids (mirrored data dirs), and
            // un-namespaced keys would hand node A's row state (tool-card
            // expansion) to node B's rows on a Quick Switcher jump. Settled
            // turns and the open turn are separate item blocks over the SAME
            // key space: when the open turn settles, its rows' keys simply move
            // from the trailing block into the history block (fold plans are
            // pure in the unchanged parts), so every row — and its tool-card /
            // Show-full state — is reused, never deleted+reinserted. A live
            // part append adds ONE trailing row; existing row keys never change.
            history.forEachIndexed { i, turn ->
                turnRowItems(
                    paneKey, historyKeys[i], turn, historyPlans[i], historyKeys[i] in expandedParts,
                    backend, onOpenChild, togglePartsFold,
                )
            }
            open?.let { live ->
                turnRowItems(
                    paneKey, checkNotNull(convo.openKey), live, openPlan, openExpanded,
                    backend, onOpenChild, togglePartsFold,
                )
            }
        }
        convo.status?.takeIf { !readonly }?.let { ThinkingRow(it) }
        if (!readonly) {
            // The composer bar surfaces only THIS session's requests; the
            // inspector's permission center handles the rest of the node.
            pend.filter { it.session_id == sessionId }.forEach { p ->
                PendingBar(p) { behavior -> onRespond(p, behavior) }
            }
            InputBar(input, onInput, onSend, switchModels, effortLevels, model, onModel, effort, onEffort, backend)
        }
    }
}

// turnRowItems emits ONE turn's flattened rows into the transcript LazyColumn:
// user/system/error turns stay single items under their original bare-UI-id
// key; an assistant turn becomes [header][part…]([fold])[part…][footer], every
// key built by the same suffix helpers flattenRowSuffixes/turnRowCount use, so
// the emitted keys, the anchor math and the follow target can never drift.
private fun LazyListScope.turnRowItems(
    paneKey: String, uiKey: Any, turn: Turn, plan: PartFoldPlan?, expanded: Boolean,
    backend: String, onOpenChild: (String) -> Unit, onToggleMore: (Any) -> Unit,
) {
    if (plan == null) {
        item(key = "$paneKey|${headerRowSuffix(uiKey)}") { TurnView(turn, backend, onOpenChild = onOpenChild) }
        return
    }
    // Header top padding + footer spacer reproduce the whole-turn Column's
    // `padding(vertical = 3.dp)` so the flatten is visually invisible.
    item(key = "$paneKey|${headerRowSuffix(uiKey)}") {
        AssistantTurnHeader(turn, backend, Modifier.padding(top = 3.dp))
    }
    val (head, tail) = visiblePartRanges(plan, expanded)
    items(rangeSize(head), key = { i -> "$paneKey|${partRowSuffix(uiKey, i)}" }) { i ->
        PartView(turn.parts[i], onOpenChild)
    }
    if (plan.folded) {
        item(key = "$paneKey|${moreRowSuffix(uiKey)}") {
            MorePartsRow(plan.hiddenCount, expanded) { onToggleMore(uiKey) }
        }
    }
    if (!tail.isEmpty()) {
        val tailFirst = tail.first
        items(rangeSize(tail), key = { i -> "$paneKey|${partRowSuffix(uiKey, tailFirst + i)}" }) { i ->
            PartView(turn.parts[tailFirst + i], onOpenChild)
        }
    }
    item(key = "$paneKey|${footerRowSuffix(uiKey)}") { Spacer(Modifier.height(3.dp)) }
}

// ThinkingRow is the transient activity signal above the composer: staggered
// pulsing dots while the agent works; stalled turns the row error-red.
@Composable
private fun ThinkingRow(status: String) {
    val stalled = status.equals("stalled", true)
    val color = if (stalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "thinking")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(500), RepeatMode.Reverse, initialStartOffset = StartOffset(i * 160)),
                label = "dot$i",
            )
            Box(Modifier.padding(end = 4.dp).size(5.dp).background(color.copy(alpha = alpha), CircleShape))
        }
        Spacer(Modifier.width(4.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ConversationHeader names what you're talking to: harness glyph + session
// title, with the node-reported runtime model underneath. On compact it also
// carries the back affordance (same behavior as the system gesture); the info
// button toggles the session inspector (Ctrl/Cmd+I).
@Composable
private fun ConversationHeader(
    title: String, model: String?, backend: String,
    showBack: Boolean, onBack: () -> Unit, onToggleInspector: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else Spacer(Modifier.width(4.dp))
            BackendMark(backend, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    title.ifEmpty { "session" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                model?.let {
                    Text(
                        capDisplay(it), // node-reported free text — same render budget as turn labels
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleInspector) {
                Icon(
                    Icons.Outlined.Info, contentDescription = "Session inspector",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

// SubagentBar heads a drilled-in subagent transcript: tap (or Back) returns to
// the parent. Subagent transcripts are read-only, so there's no composer below.
@Composable
private fun SubagentBar(onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to parent",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "Subagent transcript (read-only)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingBar(p: Pending, onRespond: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Permission: ${p.tool_name.ifEmpty { p.event.ifEmpty { "request" } }}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Button(onClick = { onRespond("allow") }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text("Allow") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onRespond("deny") }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text("Deny") }
        }
    }
}

// NodeItem: connection dot + id + one glyph per available harness, scope at
// the end — "which machine, running what, may I admin it" in one dense row.
@Composable
private fun NodeItem(n: NodeView, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        val dim = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            Modifier.size(6.dp).background(
                if (n.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            n.node_id,
            style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp,
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        n.harnesses.forEach { h ->
            BackendMark(h, Modifier.size(12.dp), tint = dim)
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(2.dp))
        Text(n.scope, style = MaterialTheme.typography.labelSmall, color = dim)
    }
}

// SessionItem: harness glyph + title + status, with the working directory tail
// underneath — enough to tell sessions apart without opening them.
@Composable
private fun SessionItem(s: Session, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        val dim = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackendMark(s.backend, Modifier.size(13.dp), tint = dim)
            Spacer(Modifier.width(6.dp))
            Text(
                s.title.ifEmpty { s.id },
                style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp,
                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (s.status.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    s.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (s.status.equals("running", true)) MaterialTheme.colorScheme.primary else dim,
                )
            }
        }
        if (s.cwd.isNotEmpty()) {
            Text(
                cwdTail(s.cwd),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 19.dp),
            )
        }
    }
}

/** cwdTail keeps the last two path segments — the part that identifies a workspace. */
internal fun cwdTail(path: String): String =
    path.trimEnd('/').split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(
                Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.outlineVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Pick a node and session to begin",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String, onChange: (String) -> Unit, onSend: () -> Unit,
    switchModels: List<HarnessModel> = emptyList(), effortLevels: List<String> = emptyList(),
    model: String = "", onModel: (String) -> Unit = {},
    effort: String = "", onEffort: (String) -> Unit = {},
    backend: String = "",
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            // Mid-conversation switcher: pick a model (same backend, with its logo)
            // and, for a backend with a reasoning knob, an effort (its OWN levels) —
            // applied to the next message.
            if (switchModels.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PickerChip(
                        "model: " + model.ifEmpty { "current" },
                        listOf("" to "current") + switchModels.map { it.slug to it.label.ifEmpty { it.slug } },
                        onModel,
                        backend = backend,
                    )
                    if (effortLevels.isNotEmpty()) {
                        PickerChip(
                            "effort: " + effort.ifEmpty { "current" },
                            listOf("" to "current") + effortLevels.map { it to it },
                            onEffort,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerField(value, onChange, onSend, Modifier.weight(1f))
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

// PickerChip is a compact label + dropdown for the composer's model/effort switch.
// When backend is set, each option (and the chip) is prefixed with that backend's
// logo — the "Claude/Codex logo on the left" of the model picker.
@Composable
private fun PickerChip(label: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, backend: String = "") {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
            if (backend.isNotEmpty()) {
                BackendMark(backend, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (backend.isNotEmpty() && v.isNotEmpty()) {
                                BackendMark(backend, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(l)
                        }
                    },
                    onClick = { onSelect(v); open = false },
                )
            }
        }
    }
}

// ComposerField is a dense, hand-rolled composer (OutlinedTextField's 56dp
// minimum is chat-hostile). Enter sends, Shift+Enter inserts a newline —
// hardware keys only, so phone soft keyboards still type newlines normally.
@Composable
private fun ComposerField(value: String, onChange: (String) -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    BasicTextField(
        value, onChange,
        modifier = modifier
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                    if (value.isNotBlank()) onSend()
                    true // consume: plain Enter never inserts a newline
                } else false
            },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 5,
        interactionSource = interactions,
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(
                        1.dp,
                        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                if (value.isEmpty()) {
                    Text("Message", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}
