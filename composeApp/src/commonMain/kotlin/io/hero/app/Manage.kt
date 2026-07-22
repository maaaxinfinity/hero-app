package io.hero.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ============================================================================
// Nodes — top toolbar (collection actions) + right inspector (selected node).
// Every control maps to a real endpoint: nodeAccess/addShare/removeShare/
// setOwner, harness config/apply/install, removeNode, mintJoin.
// ============================================================================
@Composable
internal fun NodesScreen(api: Api, me: Me, focus: String? = null, onFocusConsumed: () -> Unit = {}) {
    var nodes by remember { mutableStateOf<List<NodeView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var joinMode by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { nodes = api.nodes() }.onFailure { status = it.message }
        loading = false
    }
    // Cross-section focus (a user's "owns"/"shared" link): select once, then clear.
    LaunchedEffect(focus) {
        if (focus != null) { selected = focus; joinMode = false; onFocusConsumed() }
    }

    InspectorHost(
        open = joinMode || selected != null,
        onClose = { selected = null; joinMode = false },
        panelTitle = if (joinMode) "Join a node" else selected.orEmpty(),
        main = {
            Column(Modifier.fillMaxSize()) {
                TopToolbar("Nodes") {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (me.admin) {
                        OutlinedButton(onClick = { joinMode = true; selected = null }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add node")
                        }
                    }
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                when {
                    loading -> PaneLoader()
                    nodes.isEmpty() -> Box(Modifier.padding(12.dp)) { HintText("No nodes yet.") }
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                        items(nodes, key = { it.node_id }) { n ->
                            NodeRow(n, selected = selected == n.node_id) {
                                joinMode = false
                                selected = if (selected == n.node_id) null else n.node_id
                            }
                        }
                    }
                }
            }
        },
        panel = {
            if (joinMode) JoinPanel(api)
            else selected?.let { id ->
                val n = nodes.firstOrNull { it.node_id == id }
                if (n == null) Box(Modifier.padding(12.dp)) { HintText("Node not found.") }
                else NodeInspector(api, n, onChanged = { reload++ }, onRemoved = { selected = null; reload++ })
            }
        },
    )
}

/** managePad is the management screens' outer padding — tighter on phones. */
@Composable
internal fun managePad() = if (LocalWindowWidth.current == WindowWidth.Compact) 14.dp else 20.dp

// NodeRow: dense selectable line — connection dot, id, harness glyphs, meta.
@Composable
private fun NodeRow(n: NodeView, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, shape)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        val dim = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            Modifier.size(7.dp).background(
                if (n.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    n.node_id, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp, color = fg,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                n.harnesses.forEach { h ->
                    BackendMark(h, Modifier.size(12.dp), tint = dim)
                    Spacer(Modifier.width(4.dp))
                }
            }
            val meta = buildList {
                if (n.connected) add("${n.os} · ${n.version}")
                add(if (n.owner.isNotEmpty()) "owner ${n.owner}" else "ownerless")
                if (n.shared_with.isNotEmpty()) add("shared ${n.shared_with.joinToString(", ")}")
            }
            Text(
                meta.joinToString("  ·  "),
                fontSize = 11.sp, color = dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(n.scope, style = MaterialTheme.typography.labelSmall, color = dim)
    }
}

// NodeInspector: overview facts, access editing, harness management, removal.
@Composable
private fun NodeInspector(api: Api, n: NodeView, onChanged: () -> Unit, onRemoved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember(n.node_id) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PanelSection("Overview") {
            KeyValueRow("Status", if (n.connected) "online" else "offline")
            if (n.os.isNotEmpty()) KeyValueRow("OS", n.os)
            if (n.version.isNotEmpty()) KeyValueRow("Version", n.version)
            KeyValueRow("Scope", n.scope)
            KeyValueRow("Enrolled", if (n.enrolled) "yes" else "no")
            if (n.connected_at.isNotEmpty()) KeyValueRow("Connected", n.connected_at.replace('T', ' ').removeSuffix("Z"))
            if (n.harnesses.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    n.harnesses.forEach { h ->
                        BackendMark(h, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
        if (n.scope == "admin") {
            PanelSection("Access") {
                AccessEditor(api, n.node_id, onChanged = onChanged, onError = { status = it })
            }
            if (n.connected) {
                SectionLabel("HARNESS")
                HarnessPanel(api, n.node_id, onResult = { status = it })
            }
            PanelSection("Danger") {
                ConfirmButton("Remove node") {
                    scope.launch {
                        runCatching { api.removeNode(n.node_id) }
                            .onSuccess { onRemoved() }
                            .onFailure { status = it.message ?: "remove failed" }
                    }
                }
            }
        }
    }
}

// AccessEditor: owner display + transfer (setOwner), share add/remove.
@Composable
private fun AccessEditor(api: Api, nodeId: String, onChanged: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var acc by remember(nodeId) { mutableStateOf<NodeAccess?>(null) }
    var shareUser by remember(nodeId) { mutableStateOf("") }
    var ownerUser by remember(nodeId) { mutableStateOf("") }
    var reload by remember(nodeId) { mutableStateOf(0) }
    LaunchedEffect(nodeId, reload) {
        runCatching { acc = api.nodeAccess(nodeId) }.onFailure { onError(it.message ?: "access load failed") }
    }
    val a = acc
    if (a == null) { HintText("Loading…"); return }

    KeyValueRow("Owner", a.owner.ifEmpty { "(ownerless)" })
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            ownerUser, { ownerUser = it }, Modifier.weight(1f),
            label = { Text("Transfer to user") }, singleLine = true,
        )
        TextButton(onClick = {
            val u = ownerUser.trim()
            if (u.isNotEmpty()) scope.launch {
                runCatching { api.setOwner(nodeId, u) }.onFailure { onError(it.message ?: "transfer failed") }
                ownerUser = ""; reload++; onChanged()
            }
        }) { Text("Set") }
    }
    Spacer(Modifier.height(8.dp))
    Text("Shared with (send access)", style = MaterialTheme.typography.labelMedium)
    if (a.shared_with.isEmpty()) HintText("Not shared with anyone.")
    a.shared_with.forEach { u ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(u, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                scope.launch { runCatching { api.removeShare(nodeId, u) }; reload++; onChanged() }
            }) { Text("remove") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            shareUser, { shareUser = it }, Modifier.weight(1f),
            label = { Text("User id") }, singleLine = true,
        )
        TextButton(onClick = {
            val u = shareUser.trim()
            if (u.isNotEmpty()) scope.launch {
                runCatching { api.addShare(nodeId, u) }.onFailure { onError(it.message ?: "share failed") }
                shareUser = ""; reload++; onChanged()
            }
        }) { Text("Share") }
    }
}

// HarnessPanel: per-backend cards (config + apply + install), pull-managed note.
@Composable
private fun HarnessPanel(api: Api, nodeId: String, onResult: (String) -> Unit) {
    var st by remember(nodeId) { mutableStateOf<HarnessState?>(null) }
    var reload by remember(nodeId) { mutableStateOf(0) }
    LaunchedEffect(nodeId, reload) {
        runCatching { st = api.harness(nodeId) }.onFailure { onResult(it.message ?: "harness load failed") }
    }
    val state = st
    if (state == null) { HintText("Loading…"); return }
    if (state.pull_managed) {
        Text(
            "Config is pull-managed from ${state.pull_url}. Per-node edits are refused; you can still apply and install.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
    }
    val cfg = parseConfig(state.config)
    state.backends.forEach { b ->
        HarnessBackendCard(api, nodeId, b, cfg[b.backend], state.pull_managed) { msg ->
            onResult(msg); reload++
        }
        Spacer(Modifier.height(8.dp))
    }
}

// JoinPanel: mint a one-time enroll script (the panel's "nothing selected" mode
// is reached via the toolbar's Add node).
@Composable
private fun JoinPanel(api: Api) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var nodeId by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JoinResult?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Mint a one-time join command; run it on the new machine to install HERO, self-enroll, and come online.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            nodeId, { nodeId = it }, Modifier.fillMaxWidth(),
            label = { Text("Node id (optional)") }, singleLine = true,
        )
        OutlinedButton(onClick = {
            scope.launch {
                status = null
                runCatching { api.mintJoin(JoinReq(node_id = nodeId.trim())) }
                    .onSuccess { result = it }.onFailure { status = it.message }
            }
        }) { Text("Generate") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        result?.let { r ->
            Text("Run on the new node (expires ${r.expires_at}):", style = MaterialTheme.typography.labelSmall)
            OutlinedCard {
                Text(
                    r.script, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(r.script)) }) { Text("Copy script") }
        }
    }
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

@OptIn(ExperimentalLayoutApi::class)
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
                    trailingIcon = {
                        IconButton(onClick = { modelMenu = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick model") }
                    },
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
// Users (admin) — top toolbar + right inspector. Real endpoints only:
// users/createUser/setPassword/setAdmin/deleteUser; owns/shared entries jump
// to the Nodes section with that node selected.
// ============================================================================
@Composable
internal fun UsersScreen(api: Api, me: Me, onOpenNode: (String) -> Unit) {
    var users by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var createMode by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { users = api.users() }.onFailure { status = it.message }
        loading = false
    }

    InspectorHost(
        open = createMode || selected != null,
        onClose = { selected = null; createMode = false },
        panelTitle = if (createMode) "New user" else selected.orEmpty(),
        main = {
            Column(Modifier.fillMaxSize()) {
                TopToolbar("Users") {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { createMode = true; selected = null }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New user")
                    }
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                when {
                    loading -> PaneLoader()
                    users.isEmpty() -> Box(Modifier.padding(12.dp)) { HintText("No users.") }
                    else -> LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                        items(users, key = { it.user }) { u ->
                            UserRow(u, selected = selected == u.user) {
                                createMode = false
                                selected = if (selected == u.user) null else u.user
                            }
                        }
                    }
                }
            }
        },
        panel = {
            if (createMode) CreateUserPanel(api, onDone = { createMode = false; reload++ })
            else selected?.let { id ->
                val u = users.firstOrNull { it.user == id }
                if (u == null) Box(Modifier.padding(12.dp)) { HintText("User not found.") }
                else UserInspector(
                    api, u, me,
                    onOpenNode = onOpenNode,
                    onChanged = { reload++ },
                    onDeleted = { selected = null; reload++ },
                )
            }
        },
    )
}

@Composable
private fun UserRow(u: UserInfo, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, shape)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        val dim = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Identicon(u.user, size = 22.dp)
        Spacer(Modifier.width(9.dp))
        Text(
            u.user, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp, color = fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (u.admin) {
            Spacer(Modifier.width(8.dp))
            Text("admin", style = MaterialTheme.typography.labelSmall, color = if (selected) fg else MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        val counts = buildList {
            if (u.owns.isNotEmpty()) add("${u.owns.size} owned")
            if (u.shared.isNotEmpty()) add("${u.shared.size} shared")
        }
        if (counts.isNotEmpty()) {
            Text(counts.joinToString(" · "), fontSize = 11.sp, color = dim)
        }
    }
}

@Composable
private fun UserInspector(
    api: Api, u: UserInfo, me: Me,
    onOpenNode: (String) -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember(u.user) { mutableStateOf<String?>(null) }
    var pass by remember(u.user) { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PanelSection("Identity") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Identicon(u.user, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(u.user, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (u.admin) "control-plane admin" else "member",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (u.admin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (u.owns.isNotEmpty() || u.shared.isNotEmpty()) {
            PanelSection("Nodes") {
                if (u.owns.isNotEmpty()) {
                    Text("Owns", style = MaterialTheme.typography.labelMedium)
                    u.owns.forEach { NodeLinkRow(it) { onOpenNode(it) } }
                }
                if (u.shared.isNotEmpty()) {
                    if (u.owns.isNotEmpty()) Spacer(Modifier.height(6.dp))
                    Text("Shared with them", style = MaterialTheme.typography.labelMedium)
                    u.shared.forEach { NodeLinkRow(it) { onOpenNode(it) } }
                }
            }
        }
        PanelSection("Security") {
            PasswordField(pass, { pass = it }, label = "New password (≥ 8)", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = pass.length >= 8,
                onClick = {
                    scope.launch {
                        runCatching { api.setPassword(u.user, pass) }
                            .onSuccess { pass = ""; status = null }
                            .onFailure { status = it.message ?: "password change failed" }
                    }
                },
            ) { Text("Set password") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    runCatching { api.setAdmin(u.user, !u.admin) }
                        .onFailure { status = it.message ?: "admin change failed" }
                    onChanged()
                }
            }) { Text(if (u.admin) "Revoke admin" else "Make admin") }
        }
        if (u.user != me.user) {
            PanelSection("Danger") {
                ConfirmButton("Delete user") {
                    scope.launch {
                        runCatching { api.deleteUser(u.user) }
                            .onSuccess { onDeleted() }
                            .onFailure { status = it.message ?: "delete failed" }
                    }
                }
            }
        }
    }
}

// NodeLinkRow deep-links into the Nodes section with that node selected.
@Composable
private fun NodeLinkRow(nodeId: String, onOpen: () -> Unit) = LinkRow(nodeId, onOpen)

@Composable
private fun CreateUserPanel(api: Api, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("User id") }, singleLine = true)
        PasswordField(pass, { pass = it }, label = "Password (≥ 8)", modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(admin, { admin = it })
            Text("Control-plane admin", style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            enabled = user.isNotBlank() && pass.length >= 8,
            onClick = {
                scope.launch {
                    runCatching { api.createUser(CreateUserReq(user.trim(), pass, admin)) }
                        .onSuccess { onDone() }.onFailure { status = it.message }
                }
            },
        ) { Text("Create") }
    }
}

// ============================================================================
// Audit (admin) — top toolbar: live text filter (client-side over real
// records), a fetch-limit picker (re-queries the API), refresh.
// ============================================================================
/** filterAudit matches the query against every displayed field. Pure, tested. */
internal fun filterAudit(records: List<AuditRecord>, query: String): List<AuditRecord> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return records
    return records.filter { r ->
        r.action.lowercase().contains(q) || r.user.lowercase().contains(q) ||
            r.node.lowercase().contains(q) || r.detail.lowercase().contains(q)
    }
}

@Composable
internal fun AuditScreen(api: Api) {
    var recs by remember { mutableStateOf<List<AuditRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(300) }
    LaunchedEffect(reload, limit) {
        loading = true
        runCatching { recs = api.audit(limit) }
        loading = false
    }
    val filtered = remember(recs, query) { filterAudit(recs, query) }
    Column(Modifier.fillMaxSize()) {
        TopToolbar("Audit") {
            ToolbarSearchField(query, { query = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            LimitPicker(limit) { limit = it }
            IconButton(onClick = { reload++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            loading -> PaneLoader()
            filtered.isEmpty() -> Box(Modifier.padding(12.dp)) {
                HintText(if (recs.isEmpty()) "No audit records yet." else "No matches.")
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered) { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row {
                            Text(r.action, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Text(listOf(r.user, r.node, r.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(r.ts.replace('T', ' ').removeSuffix("Z"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

// ToolbarSearchField: compact filter input for toolbars (OutlinedTextField's
// 56dp minimum doesn't fit a 44dp bar).
@Composable
private fun ToolbarSearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value, onChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(
                Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                if (value.isEmpty()) {
                    Text("Filter action / user / node…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

// LimitPicker: how many records to fetch — switching re-queries the API.
@Composable
private fun LimitPicker(limit: Int, onLimit: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf(100, 300, 1000).forEach { n ->
            val sel = n == limit
            val shape = RoundedCornerShape(6.dp)
            Text(
                "$n",
                style = MaterialTheme.typography.labelSmall,
                color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 1.dp)
                    .clip(shape)
                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .hoverHighlight(shape)
                    .clickable { onLimit(n) }
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
    }
}

// ============================================================================
// Control plane (admin) — the HERO CONTROL-PLANE binary. Nodes/harnesses are
// managed elsewhere; this updates the control plane itself (manual + auto).
// ============================================================================
@Composable
internal fun ControlScreen(api: Api) {
    val scope = rememberCoroutineScope()
    var fleet by remember { mutableStateOf<HeroFleet?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { fleet = api.fleetHero() }.onFailure { status = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopToolbar("Control plane") {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("Update the HERO control-plane binary. Publish new builds with `hero control-publish`.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(10.dp))
            if (loading) PaneLoader()
            else fleet?.let { f ->
                val running = f.running.ifEmpty { "unknown" }
                val target = f.version
                val upToDate = f.defined && target == running
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Control plane", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Pill(running, MaterialTheme.colorScheme.secondary)
                            if (f.defined && !upToDate) { Spacer(Modifier.width(6.dp)); Pill("→ $target", MaterialTheme.colorScheme.primary) }
                            if (upToDate) { Spacer(Modifier.width(6.dp)); Pill("up to date", MaterialTheme.colorScheme.primary) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (f.defined) "Published target: $target" else "Nothing published yet — run `hero control-publish`.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            enabled = f.defined && !upToDate && !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching { api.controlSelfUpdate() }.onSuccess { status = it }.onFailure { status = it.message }
                                    busy = false
                                    reload++ // it drains + restarts; refetch shows the new running version
                                }
                            },
                        ) { Text(if (busy) "Updating…" else "Update control plane") }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = f.auto_update,
                                enabled = f.defined,
                                onCheckedChange = { want ->
                                    scope.launch {
                                        runCatching { api.setFleetHeroAuto(want) }.onSuccess { reload++ }.onFailure { status = it.message }
                                    }
                                },
                            )
                            Text("Auto-update the control plane when a new version is published", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (f.platforms.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            SectionLabel("PUBLISHED BINARIES")
                            f.platforms.entries.sortedBy { it.key }.forEach { (plat, bin) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(plat, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(120.dp))
                                    Text(
                                        "${bin.sha256.take(12)}…  ·  ${fmtBytes(bin.size)}",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            } ?: HintText("No update info.")
        }
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1_000_000 -> "${b / 1_000_000}.${(b / 100_000) % 10} MB"
    b >= 1_000 -> "${b / 1_000} kB"
    else -> "$b B"
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
                        trailingIcon = { IconButton(onClick = { modelMenu = true }) { Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick model") } })
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
