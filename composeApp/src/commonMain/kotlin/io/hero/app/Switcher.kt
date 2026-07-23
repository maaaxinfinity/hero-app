package io.hero.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// QuickSwitcher (Ctrl/Cmd+K) is the command-palette jump: fuzzy-ish filter over
// every session on every connected node, ↑↓ + Enter to open. Fetches the fleet
// once per open, in parallel.

private data class SwitchEntry(val node: String, val session: Session)

@Composable
fun QuickSwitcher(api: Api, onDismiss: () -> Unit, onPick: (node: String, session: String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SwitchEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var highlighted by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        runCatchingCancellable {
            coroutineScope {
                // The node list reuses the fleet poll owner's warm snapshot (same
                // generation, at most one cycle old) instead of a fresh download
                // per open; only a cold cache falls back to the direct call.
                val nodes = (FleetCache.nodes.value ?: api.nodes()).filter { it.connected }
                entries = nodes.map { n ->
                    async {
                        // Per-node fan-out through the SHARED session fetch: a warm
                        // cache hit costs no request, and a miss joins any in-flight
                        // download (single-flight) instead of duplicating it. An
                        // offline/errored node collapses to an empty slice, but a
                        // cancelled child must still propagate so
                        // awaitAll/coroutineScope tear down instead of "succeeding" empty.
                        FleetCache.fetchSessions(api, n.node_id)
                            .map { list -> list.map { s -> SwitchEntry(n.node_id, s) } }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
        loading = false
        // Not a suspend point — a genuine "focus target not attached yet" catch.
        runCatching { focusRequester.requestFocus() }
    }

    val filtered = remember(query, entries) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) entries
        else entries.filter { e ->
            e.node.lowercase().contains(q) ||
                e.session.title.lowercase().contains(q) ||
                e.session.id.lowercase().contains(q) ||
                e.session.cwd.lowercase().contains(q)
        }
    }
    LaunchedEffect(filtered.size) {
        highlighted = highlighted.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(highlighted) {
        if (filtered.isNotEmpty()) runCatchingCancellable { listState.animateScrollToItem(highlighted) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        ) {
            Column {
                BasicTextField(
                    query, { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { if (filtered.isNotEmpty()) highlighted = (highlighted + 1) % filtered.size; true }
                                Key.DirectionUp -> { if (filtered.isNotEmpty()) highlighted = (highlighted - 1 + filtered.size) % filtered.size; true }
                                Key.Enter -> { filtered.getOrNull(highlighted)?.let { onPick(it.node, it.session.id) }; true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    "Jump to a session…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                HorizontalDivider()
                when {
                    loading -> PaneLoader()
                    filtered.isEmpty() -> HintText(if (entries.isEmpty()) "No sessions anywhere." else "No matches.")
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp), state = listState) {
                        itemsIndexed(filtered, key = { _, e -> "${e.node}/${e.session.id}" }) { i, e ->
                            SwitchRow(e, highlighted = i == highlighted) { onPick(e.node, e.session.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(e: SwitchEntry, highlighted: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        val dim = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        BackendMark(e.session.backend, Modifier.size(13.dp), tint = dim)
        Spacer(Modifier.width(7.dp))
        Text(
            e.session.title.ifEmpty { e.session.id },
            style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp,
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            e.node,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = dim, maxLines = 1,
        )
        if (e.session.status.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                e.session.status,
                style = MaterialTheme.typography.labelSmall,
                color = if (e.session.status.equals("running", true)) MaterialTheme.colorScheme.primary else dim,
            )
        }
    }
}
