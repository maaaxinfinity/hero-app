package io.hero.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
            var api by remember { mutableStateOf<Api?>(null) }
            val current = api
            if (current == null) {
                LoginScreen(onLogin = { api = it })
            } else {
                MainScreen(current, onSignOut = { api = null })
            }
        }
    }
}

@Composable
private fun LoginScreen(onLogin: (Api) -> Unit) {
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
                                    if (a.login(user, pass)) onLogin(a) else error = "Invalid credentials"
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
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("v$AppVersion") }
    var pending by remember { mutableStateOf<UpdateInfo?>(null) }

    OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 380.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(),
                label = { Text("GitHub token (private repo)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        status = "checking…"
                        val info = runCatching { checkForUpdate(token, updateAssetSuffix()) }.getOrNull()
                        if (info == null) {
                            status = "up to date (v$AppVersion)"; pending = null
                        } else {
                            status = "update available: v${info.version}"; pending = info
                        }
                    }
                }) { Text("Check") }
                pending?.let { info ->
                    Button(onClick = { scope.launch { status = installUpdate(info, token) } }) { Text("Update") }
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MainScreen(api: Api, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<NodeView>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }
    var node by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<String?>(null) }
    var sessionTitle by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var nodesLoading by remember { mutableStateOf(true) }
    var sessionsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { nodes = api.nodes() }
        nodesLoading = false
    }

    LaunchedEffect(node) {
        val n = node ?: return@LaunchedEffect
        events = emptyList()
        api.events(n).catch { }.collect { ev -> events = (events + renderEvent(ev)).takeLast(500) }
    }

    Column(Modifier.fillMaxSize()) {
        HeroTopBar(node, sessionTitle ?: session, onSignOut)
        Row(Modifier.fillMaxSize()) {
            Surface(
                Modifier.width(248.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                    SectionLabel("NODES")
                    if (nodesLoading) {
                        PaneLoader()
                    } else if (nodes.isEmpty()) {
                        HintText("No nodes online")
                    } else {
                        nodes.forEach { n ->
                            NavItem("${n.node_id}  ·  ${n.scope}", selected = node == n.node_id) {
                                node = n.node_id; session = null; sessionTitle = null
                                sessions = emptyList(); sessionsLoading = true
                                scope.launch {
                                    runCatching { sessions = api.sessions(n.node_id) }
                                    sessionsLoading = false
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SectionLabel("SESSIONS")
                    if (sessionsLoading) {
                        PaneLoader()
                    } else if (node != null && sessions.isEmpty()) {
                        HintText("No sessions")
                    } else {
                        sessions.forEach { s ->
                            val label = if (s.title.isNotEmpty()) s.title else s.id
                            NavItem(label, selected = session == s.id) {
                                session = s.id; sessionTitle = label
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.fillMaxSize()) {
                if (session == null) {
                    EmptyState()
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(events.size) {
                        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = listState) {
                        items(events) { line -> EventRow(line) }
                    }
                    InputBar(input, { input = it }) {
                        val n = node; val s = session; val t = input.trim()
                        if (n != null && s != null && t.isNotEmpty()) {
                            input = ""
                            scope.launch { runCatching { api.send(n, s, t) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroTopBar(node: String?, session: String?, onSignOut: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark(Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Text("HERO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            if (node != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    buildString { append(node); if (session != null) append("  /  $session") },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun HintText(text: String) {
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
private fun PaneLoader() {
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
