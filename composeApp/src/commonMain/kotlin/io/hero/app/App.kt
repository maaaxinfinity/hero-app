package io.hero.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun App() {
    val settings = remember { Settings() }
    var themeMode by remember { mutableStateOf(ThemeMode.from(settings.getString(Keys.ThemeMode))) }
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    HeroTheme(dark = dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Edge-to-edge: the Surface paints under the system bars; this keeps
            // content inside the safe area. Desktop insets are zero (no-op).
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                var api by remember { mutableStateOf<Api?>(null) }
                var me by remember { mutableStateOf(Me()) }
                var showSettings by remember { mutableStateOf(false) }
                var booting by remember { mutableStateOf(true) }

                // Silent re-login: if "remember me" saved a session cookie, try it.
                LaunchedEffect(Unit) {
                    val url = settings.getString(Keys.ServerUrl)
                    val cookie = settings.getString(Keys.Cookie)
                    if (settings.getString(Keys.Remember) == "1" && !url.isNullOrBlank() && !cookie.isNullOrBlank()) {
                        val a = Api(url, cookie)
                        val m = runCatching { a.me() }.getOrNull()
                        if (m != null) { api = a; me = m }
                    }
                    booting = false
                }

                val screen = when {
                    booting -> "boot"
                    showSettings -> "settings"
                    api == null -> "login"
                    else -> "main"
                }
                // Gentle crossfade between top-level screens — subtle, ink-quiet.
                Crossfade(targetState = screen, animationSpec = tween(260), label = "screen") { s ->
                    when (s) {
                        "boot" -> BootSplash()
                        "settings" -> SettingsScreen(
                            settings = settings,
                            themeMode = themeMode,
                            onThemeMode = { themeMode = it; settings.putString(Keys.ThemeMode, it.id) },
                            onForget = {
                                settings.remove(Keys.Cookie); settings.remove(Keys.Remember); settings.remove(Keys.Username)
                            },
                            onClose = { showSettings = false },
                        )
                        "login" -> LoginScreen(
                            settings = settings,
                            onLogin = { a, m -> api = a; me = m },
                            onOpenSettings = { showSettings = true },
                        )
                        else -> MainScreen(
                            api!!, me,
                            onSignOut = {
                                api = null; me = Me()
                                settings.remove(Keys.Cookie); settings.remove(Keys.Remember)
                            },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BootSplash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ParticleLoader(tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(148.dp))
    }
}

// LoginScreen is a two-step flow: enter the control-plane URL and validate it
// (reachable + really a HERO plane) before asking for credentials. Server + user
// (and, with "remember me", the session cookie) are persisted for next launch.
@Composable
private fun LoginScreen(settings: Settings, onLogin: (Api, Me) -> Unit, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) } // 0 = URL, 1 = credentials
    var url by remember { mutableStateOf(settings.getString(Keys.ServerUrl) ?: "https://") }
    var user by remember { mutableStateOf(settings.getString(Keys.Username) ?: "") }
    var pass by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(settings.getString(Keys.Remember) == "1") }
    var checkedApi by remember { mutableStateOf<Api?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ParticleLoader(tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(184.dp))
            Text("HERO", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
            Spacer(Modifier.height(2.dp))
            Text("Harness Everything Routing Orchestrator", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            OutlinedCard(Modifier.fillMaxWidth()) {
                // Slide + fade between the two steps; direction follows step order.
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        val dir = if (forward) 1 else -1
                        (slideInHorizontally(tween(280)) { w -> dir * w } + fadeIn(tween(280)))
                            .togetherWith(slideOutHorizontally(tween(240)) { w -> -dir * w } + fadeOut(tween(180)))
                    },
                    label = "loginStep",
                ) { s ->
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (s == 0) {
                            OutlinedTextField(
                                url, { url = it; error = null }, Modifier.fillMaxWidth(),
                                label = { Text("Control-plane URL") }, singleLine = true,
                            )
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Button(
                                onClick = {
                                    busy = true; error = null
                                    scope.launch {
                                        val a = Api(url.trim().trimEnd('/'))
                                        if (a.probe()) { checkedApi = a; step = 1 }
                                        else error = "Not reachable, or not a HERO control plane."
                                        busy = false
                                    }
                                },
                                enabled = !busy && url.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (busy) "Checking…" else "Continue") }
                        } else {
                            Text(url.trim().trimEnd('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(
                                user, { user = it }, Modifier.fillMaxWidth(),
                                label = { Text("User") }, singleLine = true,
                            )
                            OutlinedTextField(
                                pass, { pass = it }, Modifier.fillMaxWidth(),
                                label = { Text("Password") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(remember, { remember = it })
                                Text("Remember me on this device", style = MaterialTheme.typography.bodySmall)
                            }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Button(
                                onClick = {
                                    busy = true; error = null
                                    scope.launch {
                                        try {
                                            val a = checkedApi ?: Api(url.trim().trimEnd('/'))
                                            if (a.login(user, pass)) {
                                                val m = runCatching { a.me() }.getOrDefault(Me(user = user))
                                                settings.putString(Keys.ServerUrl, url.trim().trimEnd('/'))
                                                settings.putString(Keys.Username, user)
                                                if (remember) {
                                                    settings.putString(Keys.Remember, "1")
                                                    a.sessionCookie?.let { settings.putString(Keys.Cookie, it) }
                                                } else {
                                                    settings.remove(Keys.Remember); settings.remove(Keys.Cookie)
                                                }
                                                onLogin(a, m)
                                            } else error = "Invalid credentials"
                                        } catch (e: Throwable) {
                                            error = e.message ?: "sign-in failed"
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy && user.isNotBlank() && pass.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (busy) "Signing in…" else "Sign in") }
                            TextButton(onClick = { step = 0; error = null }, modifier = Modifier.fillMaxWidth()) {
                                Text("‹  Change server")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

// Section is the top-level navigation target within MainScreen.
enum class Section(val label: String, val adminOnly: Boolean) {
    Sessions("Sessions", false), Nodes("Nodes", false), Users("Users", true), Audit("Audit", true)
}

@Composable
private fun MainScreen(api: Api, me: Me, onSignOut: () -> Unit, onOpenSettings: () -> Unit) {
    var section by remember { mutableStateOf(Section.Sessions) }
    // Back from a non-Sessions tab returns to Sessions (SessionsScreen registers
    // its own inner handler for closing an open session, which wins first).
    PredictiveBack(enabled = section != Section.Sessions) { section = Section.Sessions }
    Column(Modifier.fillMaxSize()) {
        HeroTopBar(me, section, onSelect = { section = it }, onSignOut = onSignOut, onOpenSettings = onOpenSettings)
        when (section) {
            Section.Sessions -> SessionsScreen(api)
            Section.Nodes -> NodesScreen(api, me)
            Section.Users -> UsersScreen(api, me)
            Section.Audit -> AuditScreen(api)
        }
    }
}

// TRANSCRIPT_PAGE is the turns-per-page the app requests (newest page on open;
// "load earlier" prepends further pages — wiring is a follow-up).
private const val TRANSCRIPT_PAGE = 40

@Composable
private fun SessionsScreen(api: Api) {
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<NodeView>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var convo by remember { mutableStateOf(ConvoState()) }
    var pend by remember { mutableStateOf<List<Pending>>(emptyList()) }
    var node by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<String?>(null) }
    // drill is the stack of subagent child sessions opened on top of `session`
    // (agent-team drill-in). The active, rendered session is its top, else `session`.
    var drill by remember { mutableStateOf<List<String>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var nodesLoading by remember { mutableStateOf(true) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }

    // Backend tag for the open session — drives the harness icon only, never parsing.
    // Subagents share their parent's harness, so the root session's tag applies.
    val backend = sessions.firstOrNull { it.id == session }?.backend.orEmpty()
    val active = drill.lastOrNull() ?: session
    val readonly = drill.isNotEmpty()

    LaunchedEffect(Unit) {
        runCatching { nodes = api.nodes().filter { it.connected } }
        nodesLoading = false
    }
    // Seed the newest transcript page, then subscribe the grouped live tail and
    // reconcile it. Keyed on (node, session) so it cancels + restarts on switch;
    // the live loop reconnects with backoff (SSE drops are routine).
    LaunchedEffect(node, active) {
        val n = node; val s = active
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
    // Poll pending permission requests for the (root) open session only.
    LaunchedEffect(node, active, readonly) {
        while (node != null && active != null && !readonly) {
            runCatching { pend = api.pending(node!!).filter { it.session_id == active } }
            delay(3000)
        }
        pend = emptyList()
    }

    if (showStart && node != null) {
        StartSessionDialog(api, node!!, onDismiss = { showStart = false }) {
            showStart = false
            scope.launch { runCatching { sessions = api.sessions(node!!) } }
        }
    }

    // Back pops a subagent drill-in first, then closes the open session (returns
    // to the session list) before the outer tab handler runs.
    PredictiveBack(enabled = session != null) {
        if (drill.isNotEmpty()) drill = drill.dropLast(1)
        else { session = null; convo = ConvoState() }
    }

    Row(Modifier.fillMaxSize()) {
        Surface(Modifier.width(248.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                SectionLabel("NODES")
                if (nodesLoading) PaneLoader()
                else if (nodes.isEmpty()) HintText("No nodes online")
                else nodes.forEach { n ->
                    NavItem("${n.node_id}  ·  ${n.scope}", selected = node == n.node_id) {
                        node = n.node_id; session = null; drill = emptyList(); sessions = emptyList(); sessionsLoading = true; convo = ConvoState()
                        scope.launch { runCatching { sessions = api.sessions(n.node_id) }; sessionsLoading = false }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("SESSIONS")
                    Spacer(Modifier.weight(1f))
                    if (node != null) TextButton(onClick = { showStart = true }) { Text("+ New") }
                }
                if (sessionsLoading) PaneLoader()
                else if (node != null && sessions.isEmpty()) HintText("No sessions")
                else sessions.forEach { s ->
                    val label = if (s.title.isNotEmpty()) s.title else s.id
                    NavItem(label, selected = session == s.id) { session = s.id; drill = emptyList(); convo = ConvoState() }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.fillMaxSize()) {
            if (session == null) EmptyState()
            else {
                if (readonly) SubagentBar { drill = drill.dropLast(1) }
                val listState = rememberLazyListState()
                // Stick to the bottom only when already there — don't yank a user
                // who scrolled up to read history. React to new turns AND the open
                // turn growing parts (streaming appends don't change list size).
                val atBottom by remember(active) {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()
                        last == null || last.index >= info.totalItemsCount - 1
                    }
                }
                LaunchedEffect(convo.turns.size, convo.turns.lastOrNull()?.parts?.size) {
                    if (atBottom && convo.turns.isNotEmpty()) listState.animateScrollToItem(convo.turns.lastIndex)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState) {
                    items(convo.turns, key = { turnKey(it) }) { turn ->
                        TurnView(turn, backend, onOpenChild = { childId -> drill = drill + childId })
                    }
                }
                if (!readonly) {
                    pend.forEach { p ->
                        PendingBar(p) { behavior -> scope.launch { runCatching { api.respond(node!!, p.id, behavior) } } }
                    }
                    InputBar(input, { input = it }) {
                        val n = node; val s = session; val t = input.trim()
                        if (n != null && s != null && t.isNotEmpty()) {
                            input = ""
                            convo = convo.optimisticUser(t)
                            scope.launch { runCatching { api.send(n, s, t) } }
                        }
                    }
                }
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
            Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹  Subagent transcript (read-only)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingBar(p: Pending, onRespond: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Permission: ${p.tool_name.ifEmpty { p.event.ifEmpty { "request" } }}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = { onRespond("allow") }) { Text("Allow") }
            TextButton(onClick = { onRespond("deny") }) { Text("Deny") }
        }
    }
}

@Composable
private fun HeroTopBar(me: Me, section: Section, onSelect: (Section) -> Unit, onSignOut: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark(Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text("HERO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.width(16.dp))
            Section.entries.filter { !it.adminOnly || me.admin }.forEach { s ->
                TabButton(s.label, selected = s == section) { onSelect(s) }
            }
            Spacer(Modifier.weight(1f))
            if (me.user.isNotEmpty()) {
                Identicon(me.user, size = 22.dp)
                Spacer(Modifier.width(6.dp))
                Text(me.user, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (me.admin) {
                    Spacer(Modifier.width(6.dp))
                    Text("admin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.padding(horizontal = 2.dp).clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
    )
}

@Composable
internal fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
    )
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun PaneLoader() {
    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        ParticleLoader(tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
    }
}

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
private fun InputBar(value: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value, onChange, Modifier.weight(1f),
                placeholder = { Text("Message") }, maxLines = 4,
            )
            Button(onClick = onSend, enabled = value.isNotBlank()) { Text("Send") }
        }
    }
}

