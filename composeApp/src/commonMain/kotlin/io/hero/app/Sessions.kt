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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    var input by remember { mutableStateOf("") }
    var sessionsLoading by remember { mutableStateOf(false) }
    var nodesError by remember { mutableStateOf<String?>(null) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
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
            // Cache-first so opening a session is instant. The cache is refilled
            // by the Nodes inspector and invalidated on Save+apply/Install; a hit
            // here deliberately skips the heavy status RPC.
            val hs = FleetCache.harness[n]
                ?: runCatching { api.harness(n) }.getOrNull()?.also { FleetCache.harness[n] = it }
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
        while (isActive) {
            runCatching { api.nodes() }
                .onSuccess { FleetCache.nodes.value = it; nodesError = null }
                .onFailure { if (FleetCache.nodes.value == null) nodesError = it.message ?: "couldn't load nodes" }
            delay(15_000)
        }
    }
    LaunchedEffect(sel.node, sessionsReload) {
        val n = sel.node
        if (n == null) { sessions = emptyList(); sessionsError = null; return@LaunchedEffect }
        // Cached page first — the spinner is reserved for a truly cold node.
        val cached = FleetCache.sessions[n]
        sessions = cached.orEmpty()
        sessionsLoading = cached == null
        sessionsError = null
        while (isActive) {
            runCatching { api.sessions(n) }
                .onSuccess { sessions = it; FleetCache.sessions[n] = it; sessionsError = null }
                .onFailure { if (sessions.isEmpty()) sessionsError = it.message ?: "couldn't load sessions" }
            sessionsLoading = false
            delay(10_000)
        }
    }
    // Seed the newest transcript page, then subscribe the grouped live tail and
    // reconcile it. Keyed on (node, active session) so it cancels + restarts on
    // switch; the live loop reconnects with backoff (SSE drops are routine).
    LaunchedEffect(sel.node, active) {
        val n = sel.node; val s = active
        if (n == null || s == null) { convo = ConvoState(); return@LaunchedEffect }
        convo = ConvoState()
        runCatching { api.transcript(n, s, TRANSCRIPT_PAGE) }.getOrNull()?.let { page ->
            convo = ConvoState(turns = page.turns, cursor = Cursor(page.total, page.start, TRANSCRIPT_PAGE))
        }
        if (readonly) return@LaunchedEffect // subagent transcripts are complete + read-only
        while (isActive) {
            runCatching {
                api.liveFrames(n, s).collect { frame -> convo = convo.reduce(frame) }
            }
            if (!isActive) break
            delay(1500)
        }
    }
    // Poll pending permission requests for the WHOLE node while a session is
    // open: the composer bar shows the current session's, the inspector's
    // permission center shows (and answers) all of them.
    LaunchedEffect(sel.node, active, readonly) {
        while (sel.node != null && active != null && !readonly) {
            runCatching { pend = api.pending(sel.node!!) }
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

    val toggleCollapse = {
        collapsed = !collapsed
        settings.putString(Keys.SidebarCollapsed, if (collapsed) "1" else "0")
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
            readonly = readonly, showBack = showBack, sessionKey = sel.active.orEmpty(),
            onToggleInspector = { inspector = !inspector },
            onLoadEarlier = {
                // Fetch the page before the window and prepend it; returns the
                // number of turns added so the pane can re-anchor its scroll.
                val n = sel.node; val s = sel.active; val cur = convo.cursor
                if (n == null || s == null || cur == null || !cur.hasMore) 0
                else runCatching { api.transcript(n, s, cur.pageSize, offset = cur.earlierOffset()) }
                    .getOrNull()?.let { page ->
                        convo = convo.prepend(page.turns, page.total, page.start)
                        page.turns.size
                    } ?: 0
            },
            input = input, onInput = { input = it },
            switchModels = catModels, effortLevels = catEffortLevels,
            model = switchModel, onModel = { switchModel = it },
            effort = switchEffort, onEffort = { switchEffort = it },
            onSend = {
                val n = sel.node; val s = sel.session; val t = input.trim()
                if (n != null && s != null && t.isNotEmpty()) {
                    input = ""
                    convo = convo.optimisticUser(t)
                    val m = switchModel; val e = if (catEffortLevels.isNotEmpty()) switchEffort else ""
                    scope.launch {
                        // The optimistic echo must not mask a failed send.
                        runCatching { api.send(n, s, t, m, e) }
                            .onFailure { convo = convo.reduce(LiveFrame.ErrorFrame("send failed: ${it.message ?: "error"}")) }
                    }
                }
            },
            onRespond = { p, behavior ->
                scope.launch {
                    runCatching { api.respond(sel.node!!, p.id, behavior) }
                        .onFailure { convo = convo.reduce(LiveFrame.ErrorFrame("respond failed: ${it.message ?: "error"}")) }
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
                    model = convo.runtimeModel, pend = pend, turns = convo.turns,
                    onRespond = { p, behavior ->
                        scope.launch {
                            runCatching { api.respond(sel.node!!, p.id, behavior) }
                                .onFailure { convo = convo.reduce(LiveFrame.ErrorFrame("respond failed: ${it.message ?: "error"}")) }
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

// SessionInspector: what you're talking to and the levers you have — all wired
// to real endpoints (respond, startSession) or real transcript/list data.
@Composable
private fun SessionInspector(
    session: Session?, sessionId: String, model: String?,
    pend: List<Pending>, turns: List<Turn>,
    onRespond: (Pending, String) -> Unit,
    onOpenChild: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
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
                pend.sortedByDescending { it.session_id == sessionId }.forEachIndexed { i, p ->
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
        // Memoized: scans every loaded turn/part; the inspector recomposes on
        // each live frame and every pending-poll tick.
        val children = remember(turns) { collectChildSessions(turns) }
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

// ConversationPane renders one open session: header, transcript, pending
// permission bars and the composer. Stateless apart from its scroll position.
@Composable
private fun ConversationPane(
    convo: ConvoState, pend: List<Pending>, backend: String, title: String,
    readonly: Boolean, showBack: Boolean, sessionKey: String,
    onToggleInspector: () -> Unit,
    onLoadEarlier: suspend () -> Int,
    input: String, onInput: (String) -> Unit, onSend: () -> Unit,
    switchModels: List<HarnessModel> = emptyList(), effortLevels: List<String> = emptyList(),
    model: String = "", onModel: (String) -> Unit = {},
    effort: String = "", onEffort: (String) -> Unit = {},
    onRespond: (Pending, String) -> Unit,
    onOpenChild: (String) -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier) {
        ConversationHeader(title, convo.runtimeModel, backend, showBack, onBack, onToggleInspector)
        if (readonly) SubagentBar(onBack)
        // Scroll state is per session (keyed), not shared across switches.
        val listState = remember(sessionKey) { LazyListState() }
        var didInitialScroll by remember(sessionKey) { mutableStateOf(false) }
        var loadingEarlier by remember(sessionKey) { mutableStateOf(false) }
        // Stick to the bottom only when already there — don't yank a user who
        // scrolled up to read history. Keyed on the LAST turn (not list size) so
        // prepending an earlier page never triggers a re-stick.
        val atBottom by remember(sessionKey) {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                last == null || last.index >= info.totalItemsCount - 1
            }
        }
        val lastKey = convo.turns.lastOrNull()?.let { turnKey(it) }
        LaunchedEffect(sessionKey, lastKey, convo.turns.lastOrNull()?.parts?.size) {
            if (convo.turns.isEmpty()) return@LaunchedEffect
            if (!didInitialScroll) {
                // The seeded page must land at its newest turn unconditionally —
                // deciding via atBottom races the first non-empty layout pass.
                listState.scrollToItem(convo.turns.lastIndex + 1) // +1: load-earlier item may sit at index 0
                didInitialScroll = true
            } else if (atBottom) {
                listState.animateScrollToItem(convo.turns.lastIndex + 1)
            }
        }
        val hasEarlier = convo.cursor?.hasMore == true
        // Hoisted out of the LazyColumn builder + memoized: it scans every loaded
        // turn, and the builder re-runs on each recomposition (every live frame).
        val keys = remember(convo.turns) { displayKeys(convo.turns) }
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
                                    // Anchor the first visible turn so the list doesn't jump
                                    // when the page lands above it.
                                    val first = listState.firstVisibleItemIndex
                                    val offset = listState.firstVisibleItemScrollOffset
                                    val before = listState.layoutInfo.totalItemsCount
                                    val added = onLoadEarlier()
                                    if (added > 0) {
                                        // Scroll only after the new page reaches layout —
                                        // earlier, scrollToItem clamps to the OLD item count
                                        // and the view lands at the bottom instead.
                                        withTimeoutOrNull(1000) {
                                            snapshotFlow { listState.layoutInfo.totalItemsCount }
                                                .first { it >= before + added }
                                        }
                                        val anchor = maxOf(first, 1)
                                        listState.scrollToItem(anchor + added, if (first >= 1) offset else 0)
                                    }
                                    loadingEarlier = false
                                }
                            }) { Text("Load earlier", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
            itemsIndexed(convo.turns, key = { i, _ -> keys[i] }) { _, turn ->
                TurnView(turn, backend, onOpenChild = onOpenChild)
            }
        }
        convo.status?.takeIf { !readonly }?.let { ThinkingRow(it) }
        if (!readonly) {
            // The composer bar surfaces only THIS session's requests; the
            // inspector's permission center handles the rest of the node.
            pend.filter { it.session_id == sessionKey }.forEach { p ->
                PendingBar(p) { behavior -> onRespond(p, behavior) }
            }
            InputBar(input, onInput, onSend, switchModels, effortLevels, model, onModel, effort, onEffort, backend)
        }
    }
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
                        it,
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
