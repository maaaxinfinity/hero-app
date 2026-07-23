package io.hero.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// AttentionScreen is the cross-fleet inbox: one list of everything wanting the
// user, aggregated by the control plane (GET /api/attention) across every
// accessible connected node. Actionable items (permission / AskUserQuestion) are
// answered in place through the same relay respond endpoint; informational ones
// (finished / errored) link to the session. Data is shared with the dock count
// via FleetCache.attention — MainScreen polls it; this screen polls faster while
// open and re-polls immediately after an answer so a resolved item drops out.

private val attnJson = Json { ignoreUnknownKeys = true }

@Composable
fun AttentionScreen(api: Api, onOpen: (node: String, session: String) -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedRefresh(api)
    val refresh: () -> Unit = { scope.launch { val gen = FleetCache.generation; runCatching { FleetCache.putAttention(gen, api.attention()) } } }

    Column(Modifier.fillMaxSize()) {
        TopToolbar("Attention")
        val items = FleetCache.attention.value
        when {
            items == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Nothing needs your attention.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.node + "|" + it.kind + "|" + it.id.ifEmpty { it.session_id } }) { item ->
                    AttentionCard(api, item, onOpen, refresh)
                }
            }
        }
    }
}

// LaunchedRefresh keeps the inbox fresh while it is on screen (snappier than the
// 7s dock poll). Split into its own composable so the effect key stays Unit.
@Composable
private fun LaunchedRefresh(api: Api) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val gen = FleetCache.generation
        // Prime immediately, then poll.
        runCatching { FleetCache.putAttention(gen, api.attention()) }
        while (isActive) {
            delay(4000)
            runCatching { FleetCache.putAttention(gen, api.attention()) }
        }
    }
}

@Composable
private fun AttentionCard(api: Api, item: AttentionItem, onOpen: (String, String) -> Unit, onAnswered: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember(item.id) { mutableStateOf(false) }
    var err by remember(item.id) { mutableStateOf<String?>(null) }
    val send: (RespondReq) -> Unit = { req ->
        busy = true; err = null
        scope.launch {
            runCatching { api.respond(item.node, item.id, req) }
                .onSuccess { onAnswered() }
                .onFailure { busy = false; err = it.message ?: "failed" }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            AttentionHeader(item) { onOpen(item.node, item.session_id) }
            when (item.kind) {
                "permission" -> PermissionActions(item, busy, send)
                "question" -> QuestionBody(item, busy, send)
                else -> TerminalRow(item) { onOpen(item.node, item.session_id) }
            }
            err?.let {
                Spacer(Modifier.height(6.dp))
                Text("Failed: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AttentionHeader(item: AttentionItem, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindChip(item.kind)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                headline(item),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(
                item.node + " · " + (item.title.ifEmpty { "session " + item.session_id.take(8) }),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

private fun headline(item: AttentionItem): String = when (item.kind) {
    "permission" -> item.tool_name.ifEmpty { item.event.ifEmpty { "Permission request" } }
    "question" -> "Question"
    "errored" -> "Turn errored"
    else -> "Turn finished"
}

@Composable
private fun KindChip(kind: String) {
    val (bg, fg) = when (kind) {
        "permission" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "question" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "errored" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            kind, color = fg,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PermissionActions(item: AttentionItem, busy: Boolean, send: (RespondReq) -> Unit) {
    item.tool_input?.let { ti ->
        Text(
            ti.toString().take(400),
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { send(RespondReq("allow", scope = "once", reason = "via HERO app")) }, enabled = !busy) { Text("Allow") }
        if (item.allow_always) {
            OutlinedButton(onClick = { send(RespondReq("allow", scope = "session", reason = "via HERO app")) }, enabled = !busy) { Text("Always") }
        }
        OutlinedButton(onClick = { send(RespondReq("deny", scope = "once", reason = "via HERO app")) }, enabled = !busy) { Text("Deny") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionBody(item: AttentionItem, busy: Boolean, send: (RespondReq) -> Unit) {
    val questions = remember(item.id) { questionsOf(item) }
    // chosen[qi] = the answer for question qi (option label or typed text).
    val chosen = remember(item.id) { mutableStateListOf<String>().apply { repeat(questions.size) { add("") } } }
    if (questions.isEmpty()) {
        // Fall back to a plain allow/deny if we couldn't parse the questions.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { send(RespondReq("allow", reason = "via HERO app")) }, enabled = !busy) { Text("Allow") }
            OutlinedButton(onClick = { send(RespondReq("deny", reason = "via HERO app")) }, enabled = !busy) { Text("Ignore") }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        questions.forEachIndexed { qi, q ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (q.header.isNotEmpty()) {
                    Text(q.header.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (q.question.isNotEmpty()) Text(q.question, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    q.options.forEach { o ->
                        val selected = chosen[qi] == o.label
                        if (selected) {
                            Button(onClick = { chosen[qi] = o.label }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(o.label) }
                        } else {
                            OutlinedButton(onClick = { chosen[qi] = o.label }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(o.label) }
                        }
                    }
                }
                OutlinedTextField(
                    value = if (q.options.none { it.label == chosen[qi] }) chosen[qi] else "",
                    onValueChange = { chosen[qi] = it },
                    placeholder = { Text("or type your own answer…") },
                    singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val answers = questions.mapIndexed { qi, q -> q.question to chosen[qi] }.toMap()
                    send(RespondReq("allow", answers = answers, reason = "via HERO app"))
                },
                enabled = !busy && chosen.all { it.isNotBlank() },
            ) { Text("Answer") }
            TextButton(
                onClick = { send(RespondReq("deny", reason = "The user declined to answer; continue in the conversation.")) },
                enabled = !busy,
            ) { Text("Ignore") }
        }
    }
}

@Composable
private fun TerminalRow(item: AttentionItem, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (item.detail.isNotEmpty()) {
            Text(
                item.detail, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onOpen) { Text("Open") }
    }
}

// questionsOf parses the AskUserQuestion questions out of an item's tool_input.
private fun questionsOf(item: AttentionItem): List<AskQuestion> {
    val obj = item.tool_input as? JsonObject ?: return emptyList()
    val qs = obj["questions"] ?: return emptyList()
    return runCatching { attnJson.decodeFromJsonElement(ListSerializer(AskQuestion.serializer()), qs) }.getOrDefault(emptyList())
}
