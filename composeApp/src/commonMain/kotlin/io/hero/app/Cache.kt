package io.hero.app

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

// FleetCache is the process-wide stale-while-revalidate store for the small
// management collections. Screens render cached data instantly on entry and
// refresh in the background — section switches stop feeling like reloads, and
// a spinner only ever appears when there is genuinely nothing to show yet.
// Snapshot-backed so cached updates recompose readers. Cleared on sign-out.
object FleetCache {
    val nodes = mutableStateOf<List<NodeView>?>(null)
    val users = mutableStateOf<List<UserInfo>?>(null)
    val audit = mutableStateOf<List<AuditRecord>?>(null)
    val sessions = mutableStateMapOf<String, List<Session>>()
    // Per-node harness state (version probes + model catalogs). A heavier node
    // RPC, so it is cached read-through: the model/effort pickers reuse it instead
    // of re-probing on every session open / dialog.
    val harness = mutableStateMapOf<String, HarnessState>()

    fun clear() {
        nodes.value = null
        users.value = null
        audit.value = null
        sessions.clear()
        harness.clear()
    }
}
