package io.hero.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ============================================================================
// Nodes
// ============================================================================
@Composable
internal fun NodesScreen(api: Api, me: Me) {
    val scope = rememberCoroutineScope()
    var nodes by remember { mutableStateOf<List<NodeView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var showJoin by remember { mutableStateOf(false) }
    var harnessNode by remember { mutableStateOf<NodeView?>(null) }
    var accessNode by remember { mutableStateOf<NodeView?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { nodes = api.nodes() }.onFailure { status = it.message }
        loading = false
    }

    if (showJoin) JoinDialog(api) { showJoin = false }
    harnessNode?.let { HarnessDialog(api, it) { harnessNode = null } }
    accessNode?.let { AccessDialog(api, it, me) { accessNode = null; reload++ } }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Nodes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { reload++ }) { Text("Refresh") }
            if (me.admin) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showJoin = true }) { Text("+ Add node") }
            }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        if (loading) PaneLoader()
        else if (nodes.isEmpty()) HintText("No nodes yet.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(nodes) { n ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.node_id, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Pill(if (n.connected) "online" else "offline",
                                if (n.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Pill(n.scope, MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.weight(1f))
                            if (n.connected) Text("${n.os} · ${n.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (n.harnesses.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                n.harnesses.forEach { h -> Pill(h, MaterialTheme.colorScheme.tertiary) }
                            }
                        }
                        if (n.owner.isNotEmpty() || n.shared_with.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                (if (n.owner.isNotEmpty()) "owner: ${n.owner}" else "ownerless") +
                                    (if (n.shared_with.isNotEmpty()) "   ·   shared: ${n.shared_with.joinToString(", ")}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (n.scope == "admin") {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (n.connected) OutlinedButton(onClick = { harnessNode = n }) { Text("Harness") }
                                OutlinedButton(onClick = { accessNode = n }) { Text("Access") }
                                OutlinedButton(onClick = { scope.launch { runCatching { api.removeNode(n.node_id) }; reload++ } }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinDialog(api: Api, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var nodeId by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JoinResult?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a node") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Mint a one-time join command; run it on the new machine to install HERO, self-enroll, and come online.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(nodeId, { nodeId = it }, Modifier.fillMaxWidth(),
                    label = { Text("Node id (optional)") }, singleLine = true)
                status?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                result?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    Text("Run on the new node (expires ${r.expires_at}):", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    OutlinedCard {
                        Text(r.script, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    status = null
                    runCatching { api.mintJoin(JoinReq(node_id = nodeId.trim())) }
                        .onSuccess { result = it }.onFailure { status = it.message }
                }
            }) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AccessDialog(api: Api, node: NodeView, me: Me, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var acc by remember { mutableStateOf<NodeAccess?>(null) }
    var shareUser by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { runCatching { acc = api.nodeAccess(node.node_id) }.onFailure { status = it.message } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Access · ${node.node_id}") },
        text = {
            Column {
                val a = acc
                if (a == null) HintText("Loading…")
                else {
                    Text("Owner: ${a.owner.ifEmpty { "(ownerless)" }}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text("Shared with (send access)", style = MaterialTheme.typography.labelMedium)
                    if (a.shared_with.isEmpty()) HintText("Not shared with anyone.")
                    a.shared_with.forEach { u ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(u, Modifier.weight(1f))
                            TextButton(onClick = { scope.launch { runCatching { api.removeShare(node.node_id, u) }; reload++ } }) { Text("remove") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(shareUser, { shareUser = it }, Modifier.weight(1f), label = { Text("user id") }, singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val u = shareUser.trim(); if (u.isNotEmpty()) scope.launch { runCatching { api.addShare(node.node_id, u) }.onFailure { status = it.message }; shareUser = ""; reload++ }
                        }) { Text("Share") }
                    }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ============================================================================
// Harness management dialog
// ============================================================================
@Composable
private fun HarnessDialog(api: Api, node: NodeView, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<HarnessState?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { runCatching { st = api.harness(node.node_id) }.onFailure { status = it.message } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Harness · ${node.node_id}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                val state = st
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state == null) HintText("Loading…")
                else {
                    if (state.pull_managed) {
                        Text("Config is pull-managed from ${state.pull_url}. Per-node edits are refused; you can still apply and install.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                    }
                    val cfg = parseConfig(state.config)
                    state.backends.forEach { b ->
                        HarnessBackendCard(api, node.node_id, b, cfg[b.backend], state.pull_managed) { msg ->
                            status = msg; reload++
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// backendConfig is the editable slice of harness.json for one backend.
private data class BackendConfig(val defaultModel: String, val defaultEffort: String, val autoUpdate: Boolean)

private fun parseConfig(config: JsonElement?): Map<String, BackendConfig> {
    val obj = (config as? JsonObject) ?: return emptyMap()
    val out = mutableMapOf<String, BackendConfig>()
    obj.forEach { (backend, v) ->
        val o = v as? JsonObject ?: return@forEach
        out[backend] = BackendConfig(
            defaultModel = o["default_model"]?.jsonPrimitive?.contentOrNull ?: "",
            defaultEffort = o["default_effort"]?.jsonPrimitive?.contentOrNull ?: "",
            autoUpdate = o["auto_update"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }
    return out
}

@Composable
private fun HarnessBackendCard(
    api: Api, nodeId: String, b: HarnessBackend, existing: BackendConfig?, pullManaged: Boolean,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var model by remember(b.backend) { mutableStateOf(existing?.defaultModel ?: "") }
    var effort by remember(b.backend) { mutableStateOf(existing?.defaultEffort ?: "") }
    var autoUpd by remember(b.backend) { mutableStateOf(existing?.autoUpdate ?: false) }
    var modelMenu by remember { mutableStateOf(false) }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b.backend, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Pill(if (b.enabled) "enabled" else "not enabled",
                    if (b.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(6.dp))
                val vlabel = if (b.version_status == "missing") "not installed" else "${b.installed_version} · ${b.version_status.replace('_', ' ')}"
                Pill(vlabel, if (b.version_status == "ok") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (b.installing) { Spacer(Modifier.width(6.dp)); Pill("installing…", MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(4.dp))
            val cat = b.catalog
            Text(
                "supported ${b.version_range}  ·  ${if (cat.custom_provider) (cat.provider_name.ifEmpty { "custom" }) else "default provider"}" +
                    (if (cat.default.isNotEmpty()) "  ·  default ${cat.default}" else "") +
                    (if (cat.default_effort.isNotEmpty()) "  ·  effort ${cat.default_effort}" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // Model picker (from the node's catalog) + free text fallback.
            Box {
                OutlinedTextField(
                    model, { model = it }, Modifier.fillMaxWidth(),
                    label = { Text("Default model") }, singleLine = true,
                    trailingIcon = { TextButton(onClick = { modelMenu = true }) { Text("▼") } },
                )
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    DropdownMenuItem(text = { Text("(CLI default)") }, onClick = { model = ""; modelMenu = false })
                    cat.models.filter { !it.hidden }.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label.ifEmpty { m.slug }) }, onClick = { model = m.slug; modelMenu = false })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                effort, { effort = it }, Modifier.fillMaxWidth(),
                label = { Text(if (b.backend == "codex") "Default effort (e.g. low/medium/high)" else "Default effort (codex only)") },
                singleLine = true, enabled = b.backend == "codex",
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(autoUpd, { autoUpd = it })
                Text("auto_update — apply config + keep CLI in version window", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !pullManaged, onClick = {
                    scope.launch {
                        // Rebuild the whole harness.json with this backend's slice merged in.
                        val next = buildMergedConfig(api, nodeId, b.backend, model, if (b.backend == "codex") effort else "", autoUpd)
                        runCatching {
                            api.setHarnessConfig(nodeId, next)
                            onResult(api.harnessApply(nodeId, b.backend))
                        }.onFailure { onResult(it.message ?: "error") }
                    }
                }) { Text("Save + apply") }
                OutlinedButton(onClick = {
                    scope.launch { runCatching { onResult(api.harnessInstall(nodeId, b.backend)) }.onFailure { onResult(it.message ?: "error") } }
                }) { Text(if (b.version_status == "missing") "Install" else "Reinstall") }
            }
        }
    }
}

// buildMergedConfig fetches the current config and returns it with one backend's
// slice replaced — so editing codex never drops a claude entry.
private suspend fun buildMergedConfig(api: Api, nodeId: String, backend: String, model: String, effort: String, autoUpd: Boolean): JsonObject {
    val cur = runCatching { api.harness(nodeId).config as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    val out = buildJsonObject {
        cur.forEach { (k, v) -> if (k != backend) put(k, v) }
        val slice = buildJsonObject {
            if (model.isNotEmpty()) put("default_model", model)
            if (effort.isNotEmpty()) put("default_effort", effort)
            if (autoUpd) put("auto_update", true)
        }
        if (slice.isNotEmpty()) put(backend, slice)
    }
    return out
}

// ============================================================================
// Users (admin)
// ============================================================================
@Composable
internal fun UsersScreen(api: Api, me: Me) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var pwUser by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) { loading = true; runCatching { users = api.users() }.onFailure { status = it.message }; loading = false }

    if (showCreate) CreateUserDialog(api) { showCreate = false; reload++ }
    pwUser?.let { u -> PasswordDialog(api, u) { pwUser = null } }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Users", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { showCreate = true }) { Text("+ New user") }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        if (loading) PaneLoader()
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(users) { u ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Identicon(u.user, size = 26.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(u.user, fontWeight = FontWeight.SemiBold)
                            if (u.admin) { Spacer(Modifier.width(8.dp)); Pill("admin", MaterialTheme.colorScheme.primary) }
                            Spacer(Modifier.weight(1f))
                        }
                        if (u.owns.isNotEmpty() || u.shared.isNotEmpty()) {
                            Text(
                                (if (u.owns.isNotEmpty()) "owns: ${u.owns.joinToString(", ")}" else "") +
                                    (if (u.shared.isNotEmpty()) "   shared: ${u.shared.joinToString(", ")}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pwUser = u.user }) { Text("Password") }
                            TextButton(onClick = { scope.launch { runCatching { api.setAdmin(u.user, !u.admin) }; reload++ } }) {
                                Text(if (u.admin) "Revoke admin" else "Make admin")
                            }
                            if (u.user != me.user) {
                                TextButton(onClick = { scope.launch { runCatching { api.deleteUser(u.user) }; reload++ } }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateUserDialog(api: Api, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("New user") },
        text = {
            Column {
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("User id") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password (≥ 8)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(admin, { admin = it }); Text("Control-plane admin") }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { api.createUser(CreateUserReq(user.trim(), pass, admin)) }
                        .onSuccess { onDone() }.onFailure { status = it.message }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(api: Api, user: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Set password · $user") },
        text = {
            Column {
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("New password (≥ 8)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { runCatching { api.setPassword(user, pass) }.onSuccess { onDone() }.onFailure { status = it.message } }
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ============================================================================
// Audit (admin)
// ============================================================================
@Composable
internal fun AuditScreen(api: Api) {
    var recs by remember { mutableStateOf<List<AuditRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { loading = true; runCatching { recs = api.audit(300) }; loading = false }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Audit", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { reload++ }) { Text("Refresh") }
        }
        Spacer(Modifier.height(12.dp))
        if (loading) PaneLoader()
        else if (recs.isEmpty()) HintText("No audit records yet.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(recs) { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Row {
                        Text(r.action, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Text(listOf(r.user, r.node, r.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(r.ts.replace('T', ' ').removeSuffix("Z"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Divider(Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ============================================================================
// Start session dialog (used by SessionsScreen)
// ============================================================================
@Composable
internal fun StartSessionDialog(api: Api, node: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var cwd by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<HarnessModel>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(node) {
        runCatching {
            api.harness(node).backends.flatMap { it.catalog.models }.filter { !it.hidden }
        }.onSuccess { models = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New session on $node") },
        text = {
            Column {
                OutlinedTextField(cwd, { cwd = it }, Modifier.fillMaxWidth(), label = { Text("Working directory") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model (blank = default)") }, singleLine = true,
                        trailingIcon = { TextButton(onClick = { modelMenu = true }) { Text("▼") } })
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { m -> DropdownMenuItem(text = { Text(m.label.ifEmpty { m.slug }) }, onClick = { model = m.slug; modelMenu = false }) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(msg, { msg = it }, Modifier.fillMaxWidth(), label = { Text("Initial message (optional)") })
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { api.startSession(node, StartSessionReq(cwd.trim(), msg, model)) }
                        .onSuccess { onStarted() }.onFailure { status = it.message }
                }
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) {
    OutlinedCard { Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) }
}
