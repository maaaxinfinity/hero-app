package io.hero.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun App() {
    MaterialTheme {
        var api by remember { mutableStateOf<Api?>(null) }
        val current = api
        if (current == null) {
            LoginScreen(onLogin = { api = it })
        } else {
            MainScreen(current)
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

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HERO", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Control-plane URL") }, singleLine = true)
        OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true)
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy, onClick = {
            busy = true
            error = null
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
        }) { Text(if (busy) "…" else "Sign in") }
        Spacer(Modifier.height(24.dp))
        UpdatePanel()
    }
}

@Composable
private fun UpdatePanel() {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("v$AppVersion") }
    var pending by remember { mutableStateOf<UpdateInfo?>(null) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Updates", style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(token, { token = it }, label = { Text("GitHub token (private repo)") }, singleLine = true)
        Row {
            Button(onClick = {
                scope.launch {
                    status = "checking…"
                    val info = runCatching { checkForUpdate(token, updateAssetSuffix()) }.getOrNull()
                    if (info == null) {
                        status = "up to date (v$AppVersion)"
                        pending = null
                    } else {
                        status = "update available: v${info.version}"
                        pending = info
                    }
                }
            }) { Text("Check") }
            pending?.let { info ->
                Spacer(Modifier.width(8.dp))
                Button(onClick = { scope.launch { status = installUpdate(info, token) } }) { Text("Update") }
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MainScreen(api: Api) {
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<NodeView>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }
    var node by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { runCatching { nodes = api.nodes() } }

    LaunchedEffect(node) {
        val n = node ?: return@LaunchedEffect
        events = emptyList()
        api.events(n).catch { }.collect { ev ->
            events = (events + renderEvent(ev)).takeLast(500)
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(8.dp)) {
            Text("NODES", style = MaterialTheme.typography.labelSmall)
            nodes.forEach { n ->
                TextButton(onClick = {
                    node = n.node_id
                    session = null
                    sessions = emptyList()
                    scope.launch { runCatching { sessions = api.sessions(n.node_id) } }
                }) { Text("${n.node_id} (${n.scope})") }
            }
            Spacer(Modifier.height(8.dp))
            Text("SESSIONS", style = MaterialTheme.typography.labelSmall)
            sessions.forEach { s ->
                TextButton(onClick = { session = s.id }) {
                    Text(if (s.title.isNotEmpty()) s.title else s.id)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
            LazyColumn(Modifier.weight(1f)) {
                items(events) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    input, { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                Button(onClick = {
                    val n = node
                    val s = session
                    val t = input.trim()
                    if (n != null && s != null && t.isNotEmpty()) {
                        input = ""
                        scope.launch { runCatching { api.send(n, s, t) } }
                    }
                }) { Text("Send") }
            }
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
