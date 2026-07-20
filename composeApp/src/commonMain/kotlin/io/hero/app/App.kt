package io.hero.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun App() {
    HeroTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Edge-to-edge: the Surface paints under the system bars (set up by
            // enableEdgeToEdge in MainActivity); this keeps content inside the safe
            // area. On desktop system-bar insets are zero, so it is a no-op there.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                var api by remember { mutableStateOf<Api?>(null) }
                var me by remember { mutableStateOf(Me()) }
                val current = api
                if (current == null) {
                    LoginScreen(onLogin = { a, m -> api = a; me = m })
                } else {
                    MainScreen(current, me, onSignOut = { api = null; me = Me() })
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(onLogin: (Api, Me) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("http://127.0.0.1:7801") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ParticleLoader(
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(184.dp),
            )
            Text(
                "HERO",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Harness Everything Routing Orchestrator",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        url, { url = it }, Modifier.fillMaxWidth(),
                        label = { Text("Control-plane URL") }, singleLine = true,
                    )
                    OutlinedTextField(
                        user, { user = it }, Modifier.fillMaxWidth(),
                        label = { Text("User") }, singleLine = true,
                    )
                    OutlinedTextField(
                        pass, { pass = it }, Modifier.fillMaxWidth(),
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val a = Api(url.trimEnd('/'))
                                    if (a.login(user, pass)) {
                                        val m = runCatching { a.me() }.getOrDefault(Me(user = user))
                                        onLogin(a, m)
                                    } else error = "Invalid credentials"
                                } catch (e: Throwable) {
                                    error = e.message ?: "login failed"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Signing in…" else "Sign in") }
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced" else "Advanced · Updates")
            }
            if (showAdvanced) UpdatePanel()
        }
    }
}

@Composable
private fun UpdatePanel() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("v$AppVersion") }
    var pending by remember { mutableStateOf<UpdateInfo?>(null) }

    // No token: updates come from the project's PUBLIC releases, checked and
    // downloaded anonymously (see Update.kt).
    OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 380.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        status = "checking…"
                        val info = runCatching { checkForUpdate(updateAssetSuffix()) }.getOrNull()
                        if (info == null) {
                            status = "up to date (v$AppVersion)"; pending = null
                        } else {
                            status = "update available: v${info.version}"; pending = info
                        }
                    }
                }) { Text("Check for updates") }
                pending?.let { info ->
                    Button(onClick = { scope.launch { status = installUpdate(info) } }) { Text("Update") }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Section is the top-level navigation target within MainScreen.
enum class Section(val label: String, val adminOnly: Boolean) {
    Sessions("Sessions", false), Nodes("Nodes", false), Users("Users", true), Audit("Audit", true)
}

@Composable
private fun MainScreen(api: Api, me: Me, onSignOut: () -> Unit) {
    var section by remember { mutableStateOf(Section.Sessions) }
    // Back from a non-Sessions tab returns to Sessions (SessionsScreen registers
    // its own inner handler for closing an open session, which wins first).
    PredictiveBack(enabled = section != Section.Sessions) { section = Section.Sessions }
    Column(Modifier.fillMaxSize()) {
        HeroTopBar(me, section, onSelect = { section = it }, onSignOut = onSignOut)
        when (section) {
            Section.Sessions -> SessionsScreen(api)
            Section.Nodes -> NodesScreen(api, me)
            Section.Users -> UsersScreen(api, me)
            Section.Audit -> AuditScreen(api)
        }
    }
}

@Composable
private fun SessionsScreen(api: Api) {
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<NodeView>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }
    var pend by remember { mutableStateOf<List<Pending>>(emptyList()) }
    var node by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var nodesLoading by remember { mutableStateOf(true) }
    var sessionsLoading by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { nodes = api.nodes().filter { it.connected } }
        nodesLoading = false
    }
    LaunchedEffect(node) {
        val n = node ?: return@LaunchedEffect
        events = emptyList()
        api.events(n).catch { }.collect { ev ->
            if (session == null || ev.session_id.isEmpty() || ev.session_id == session) {
                events = (events + renderEvent(ev)).takeLast(500)
            }
        }
    }
    // Poll pending permission requests for the open session.
    LaunchedEffect(node, session) {
        while (node != null && session != null) {
            runCatching { pend = api.pending(node!!).filter { it.session_id == session } }
            kotlinx.coroutines.delay(3000)
        }
        pend = emptyList()
    }

    if (showStart && node != null) {
        StartSessionDialog(api, node!!, onDismiss = { showStart = false }) {
            showStart = false
            scope.launch { runCatching { sessions = api.sessions(node!!) } }
        }
    }

    // Back closes the open session (returns to the session list) before the
    // outer tab handler runs.
    PredictiveBack(enabled = session != null) { session = null; events = emptyList() }

    Row(Modifier.fillMaxSize()) {
        Surface(Modifier.width(248.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                SectionLabel("NODES")
                if (nodesLoading) PaneLoader()
                else if (nodes.isEmpty()) HintText("No nodes online")
                else nodes.forEach { n ->
                    NavItem("${n.node_id}  ·  ${n.scope}", selected = node == n.node_id) {
                        node = n.node_id; session = null; sessions = emptyList(); sessionsLoading = true; events = emptyList()
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
                    NavItem(label, selected = session == s.id) { session = s.id; events = emptyList() }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.fillMaxSize()) {
            if (session == null) EmptyState()
            else {
                val listState = rememberLazyListState()
                LaunchedEffect(events.size) { if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState) {
                    items(events) { line -> EventRow(line) }
                }
                pend.forEach { p ->
                    PendingBar(p) { behavior -> scope.launch { runCatching { api.respond(node!!, p.id, behavior) } } }
                }
                InputBar(input, { input = it }) {
                    val n = node; val s = session; val t = input.trim()
                    if (n != null && s != null && t.isNotEmpty()) { input = ""; scope.launch { runCatching { api.send(n, s, t) } } }
                }
            }
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
private fun HeroTopBar(me: Me, section: Section, onSelect: (Section) -> Unit, onSignOut: () -> Unit) {
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
private fun EventRow(line: String) {
    Text(
        line,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
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

// renderEvent is a readable one-line summary; rich per-harness rendering (the
// web console's markdown/tool cards) is the next step here too.
private fun renderEvent(ev: Event): String {
    val role = ev.type.ifEmpty { "?" }
    val body = ev.raw?.toString()?.take(200).orEmpty()
    return "[$role] $body"
}
