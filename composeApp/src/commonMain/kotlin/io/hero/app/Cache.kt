package io.hero.app

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// FleetCache is the process-wide, identity-scoped store behind the cache-first
// UX: management screens and the session list render their last-known data
// instantly on tab re-entry while a background poll refreshes it, so section
// switches never flash a spinner. Snapshot-backed, so a cached update recomposes
// its readers.
//
// It is deliberately not a naked map — it owns the data it caches:
//  - Identity. Every entry belongs to one {server, user} app-session generation.
//    bindIdentity() bumps the generation and drops all entries when the identity
//    changes, so a server switch / re-login invalidates the cache on its own
//    instead of relying on a clear() call being threaded through every Api path.
//    Reads and writes are generation-guarded: an entry left over from an old
//    generation is invisible, and a late completion carrying a stale generation
//    is refused (it cannot overwrite the current session's data).
//  - Reclamation. putNodes() reconciles the per-node caches against the
//    authoritative fleet snapshot: entries for nodes that left the fleet are
//    evicted, and a node that reconnected / changed scope or version has its
//    (volatile) harness entry dropped so a picker can't keep showing stale
//    install/model state. The per-node session and harness caches are bounded by
//    a small weighted LRU (entry count + estimated bytes), so retained heap
//    tracks the current + recently-viewed working set, not every node ever
//    visited. The byte budget is a HARD cap: a single oversize entry is refused
//    at the door instead of being exempted as "the freshest one".
//  - Shared fetches. Cache-miss (and management-refresh) downloads go through a
//    per-key single-flight owner, so concurrent callers share one in-flight
//    request instead of racing duplicate downloads whose completions could
//    apply out of order.
//  - Freshness. Parameterised data (audit) is keyed by its request parameter, so
//    a fetch at one limit is never shown as the result for another.
//
// Note the two per-node caches differ in refresh policy: `sessions` is
// stale-while-revalidate (the caller re-polls and re-puts), while `harness` is
// cache-first-*no*-refresh — a hit is served as-is and only dropped by an
// explicit invalidateHarness (apply/install) or a putNodes reconcile, never by a
// background poll.
object FleetCache {
    // Monotonic app-session identity token. Bumped on identity change / sign-out
    // and stamped onto every per-node entry.
    var generation: Int = 0
        private set
    private var identity: String? = null

    // Whole-fleet single-slot collections: small, replaced wholesale, so they
    // need identity scoping but no LRU. Exposed read-only — writes go through the
    // guarded putters so nothing can bypass the generation contract.
    private val _nodes = mutableStateOf<List<NodeView>?>(null)
    val nodes: State<List<NodeView>?> get() = _nodes
    private val _users = mutableStateOf<List<UserInfo>?>(null)
    val users: State<List<UserInfo>?> get() = _users
    // Cross-fleet attention inbox (GET /api/attention), polled by MainScreen so
    // the dock count and the Attention screen share one fetch.
    private val _attention = mutableStateOf<List<AttentionItem>?>(null)
    val attention: State<List<AttentionItem>?> get() = _attention

    // Audit keyed by fetch limit: loading 1000 rows then re-entering at 300 must
    // not show the 1000-row set as if it were the 300 result.
    private val auditByLimit = mutableStateMapOf<Int, List<AuditRecord>>()

    // Per-node caches. Values are wrapped so each carries its source generation,
    // an estimated byte weight and an LRU recency tick; readers see the plain
    // value via sessionsOf / harnessOf.
    private val sessionEntries = mutableStateMapOf<String, Entry<List<Session>>>()
    private val harnessEntries = mutableStateMapOf<String, Entry<HarnessState>>()
    private var tick: Long = 0L

    private class Entry<T>(val value: T, val gen: Int, val bytes: Int, var tick: Long)

    // Retention budgets — small on purpose: the working set is the current node
    // plus a few recently-viewed ones, not the entire visit history.
    private const val MAX_SESSION_NODES = 12
    private const val MAX_SESSION_BYTES = 4 * 1024 * 1024
    private const val MAX_HARNESS_NODES = 12
    private const val MAX_HARNESS_BYTES = 2 * 1024 * 1024

    /**
     * bindIdentity scopes the cache to the current {server, user}. On a change it
     * bumps the generation and drops every entry, so identity replacement
     * invalidates the cache without a scattered clear(). Idempotent for the same
     * identity, so the warm cache-first set survives a same-identity remount.
     *
     * Returns the bound generation, and is called SYNCHRONOUSLY from MainScreen's
     * composition (not from an effect): the cold-start / re-login generation
     * boundary must exist before any child composes or any launched effect
     * captures [generation] — an effect-phase bind let the first frame read the
     * previous identity's snapshot, and let a poll capture the pre-bind
     * generation whose every put would then be silently refused.
     */
    fun bindIdentity(server: String, user: String): Int {
        val id = "$server|$user"
        if (id != identity) {
            identity = id
            generation++
            evictAll()
        }
        return generation
    }

    /**
     * clear drops everything and bumps the generation. Used on sign-out; the
     * bump means any in-flight write from the old session is refused by its
     * generation guard even if it lands after the clear.
     */
    fun clear() {
        generation++
        identity = null
        evictAll()
    }

    private fun evictAll() {
        _nodes.value = null
        _users.value = null
        _attention.value = null
        auditByLimit.clear()
        sessionEntries.clear()
        harnessEntries.clear()
    }

    // ---- whole-fleet slots (generation-guarded writers) ----

    /**
     * putNodes stores the authoritative fleet snapshot and reconciles the
     * per-node caches against it: session/harness entries for nodes that left the
     * fleet are reclaimed, and a node that reconnected / changed scope or version
     * has its volatile harness entry dropped so a picker cannot keep serving
     * stale install/model state.
     */
    fun putNodes(gen: Int, list: List<NodeView>) {
        if (gen != generation) return
        val prev = _nodes.value
        val live = list.mapTo(HashSet()) { it.node_id }
        // Reclaim nodes that dropped out of the fleet entirely.
        (sessionEntries.keys - live).forEach { sessionEntries.remove(it) }
        (harnessEntries.keys - live).forEach { harnessEntries.remove(it) }
        // Drop harness for a node whose volatile facts changed (reconnect / scope
        // grant / version upgrade) — its cached admin DTO is now suspect.
        if (prev != null) {
            val prevById = prev.associateBy { it.node_id }
            for (n in list) {
                val p = prevById[n.node_id] ?: continue
                if (p.connected != n.connected || p.scope != n.scope ||
                    p.version != n.version || p.connected_at != n.connected_at
                ) {
                    harnessEntries.remove(n.node_id)
                }
            }
        }
        _nodes.value = list
    }

    fun putUsers(gen: Int, list: List<UserInfo>) {
        if (gen == generation) _users.value = list
    }

    fun putAttention(gen: Int, list: List<AttentionItem>) {
        if (gen == generation) _attention.value = list
    }

    // ---- per-node sessions (stale-while-revalidate, weighted LRU) ----

    fun sessionsOf(node: String): List<Session>? =
        sessionEntries[node]?.takeIf { it.gen == generation }?.value

    fun putSessions(node: String, gen: Int, list: List<Session>) {
        if (gen != generation) return
        val bytes = estimateSessions(list)
        // HARD per-entry cap: an anomalous oversize response is never retained
        // (the caller still renders its own local copy) — and the node's previous,
        // now-stale entry is dropped rather than left masquerading as current.
        if (bytes > MAX_SESSION_BYTES) { sessionEntries.remove(node); return }
        sessionEntries[node] = Entry(list, gen, bytes, ++tick)
        enforce(sessionEntries, MAX_SESSION_NODES, MAX_SESSION_BYTES)
    }

    // ---- per-node harness (cache-first-no-refresh, weighted LRU) ----

    fun harnessOf(node: String): HarnessState? =
        harnessEntries[node]?.takeIf { it.gen == generation }?.value

    fun putHarness(node: String, gen: Int, st: HarnessState) {
        if (gen != generation) return
        val bytes = estimateHarness(st)
        // Same hard per-entry cap as sessions: oversize is refused, not retained.
        if (bytes > MAX_HARNESS_BYTES) { harnessEntries.remove(node); return }
        harnessEntries[node] = Entry(st, gen, bytes, ++tick)
        enforce(harnessEntries, MAX_HARNESS_NODES, MAX_HARNESS_BYTES)
    }

    /** invalidateHarness drops one node's harness entry after a mutation
     *  (Save + apply / Install) so the pickers re-read fresh state. */
    fun invalidateHarness(node: String) {
        harnessEntries.remove(node)
    }

    // ---- shared request owner (single-flight per key) ----

    // Concurrent same-key cache misses used to each download the full DTO
    // (conversation picker, Start dialog and management status could all fetch
    // one node's harness at once). All fetches for a key now go through ONE
    // in-flight request whose result every concurrent caller shares; the flight
    // itself stamps and publishes into the cache exactly once. Because at most
    // one request per key is ever in flight, same-generation completions can no
    // longer complete out of order and resurrect an older snapshot.
    private val flights = SingleFlight()

    /**
     * fetchSessions is the shared session fetch for one node: cache-first unless
     * [refresh], and single-flighted across every caller (list poll, Start
     * dialog cwd derivation, quick switcher). Cancellation of one caller only
     * detaches that caller — a joiner takes the flight over rather than losing it.
     */
    suspend fun fetchSessions(api: Api, node: String, refresh: Boolean = false): Result<List<Session>> {
        if (!refresh) sessionsOf(node)?.let { return Result.success(it) }
        return flights.run("s:$node") {
            val gen = generation
            api.sessions(node).also { putSessions(node, gen, it) }
        }
    }

    /**
     * fetchHarness is the shared harness fetch for one node: cache-first unless
     * [refresh] (management reads pass refresh = true for a fresh DTO but still
     * share their in-flight request with any concurrent picker/dialog miss).
     */
    suspend fun fetchHarness(api: Api, node: String, refresh: Boolean = false): Result<HarnessState> {
        if (!refresh) harnessOf(node)?.let { return Result.success(it) }
        return flights.run("h:$node") {
            val gen = generation
            api.harness(node).also { putHarness(node, gen, it) }
        }
    }

    // ---- audit (keyed by fetch limit) ----

    fun auditOf(limit: Int): List<AuditRecord>? = auditByLimit[limit]

    fun putAudit(limit: Int, gen: Int, recs: List<AuditRecord>) {
        if (gen == generation) auditByLimit[limit] = recs
    }

    // ---- weighted LRU + size estimates ----

    private fun <T> enforce(map: MutableMap<String, Entry<T>>, maxCount: Int, maxBytes: Int) {
        while (map.size > maxCount) evictLru(map)
        // The byte budget is a HARD total cap — no "only one entry left" waiver.
        // Every admitted entry is individually <= maxBytes (the putters refuse
        // oversize ones), so this always terminates with the freshest entry kept.
        while (map.isNotEmpty() && map.values.sumOf { it.bytes.toLong() } > maxBytes) evictLru(map)
    }

    private fun <T> evictLru(map: MutableMap<String, Entry<T>>) {
        val lru = map.minByOrNull { it.value.tick } ?: return
        map.remove(lru.key)
    }

    // Rough char-count estimates (≈ bytes) — enough to weight the LRU, not exact
    // accounting.
    private fun estimateSessions(list: List<Session>): Int {
        var n = 16
        for (s in list) n += 48 + s.id.length + s.title.length + s.backend.length + s.cwd.length + s.status.length
        return n
    }

    private fun estimateHarness(st: HarnessState): Int {
        var n = 64 + st.config_path.length + st.pull_url.length + (st.config?.toString()?.length ?: 0)
        for (b in st.backends) {
            n += 64 + b.backend.length + b.installed_version.length + b.version_status.length +
                b.version_range.length + b.install_hint.length + b.home.length
            val c = b.catalog
            n += 32 + c.default.length + c.provider_name.length + c.base_url.length + c.live_error.length
            for (m in c.models) n += 24 + m.slug.length + m.label.length + m.default_effort.length
            for (e in c.effort_levels) n += 8 + e.length
        }
        return n
    }
}

/**
 * SingleFlight collapses concurrent same-key work into ONE in-flight execution
 * whose [Result] every concurrent caller shares — success AND failure (a failed
 * flight is delivered to everyone who joined it; the NEXT call re-executes).
 *
 * Cancellation is per caller, never per flight: a joiner cancelling only
 * cancels its own await, and an OWNER cancelling publishes a takeover marker so
 * an awake joiner re-runs the work under its own ownership instead of hanging
 * on a dead flight or inheriting someone else's CancellationException.
 */
internal class SingleFlight {
    private val lock = Mutex()
    private val flights = HashMap<String, CompletableDeferred<Any?>>()

    /** executions counts how many times a block ACTUALLY ran — the regression
     *  counter behind "N concurrent callers, one download". */
    var executions = 0
        private set

    private object OwnerCancelled

    suspend fun <T> run(key: String, block: suspend () -> T): Result<T> {
        while (true) {
            var mine: CompletableDeferred<Any?>? = null
            val flight = lock.withLock {
                flights[key] ?: CompletableDeferred<Any?>().also { mine = it; flights[key] = it }
            }
            if (flight !== mine) {
                // Joiner: share the in-flight result. await is cancellable by THIS
                // caller only; an owner that got cancelled publishes the takeover
                // marker and the loop re-runs the work as the new owner.
                val out = flight.await()
                if (out !== OwnerCancelled) {
                    @Suppress("UNCHECKED_CAST")
                    return out as Result<T>
                }
                continue
            }
            // Owner: run the block, then ALWAYS clear the slot and complete the
            // flight — cancellation included (NonCancellable: a plain suspend lock
            // in a cancelled coroutine would throw and strand every joiner).
            var res: Result<T>? = null
            try {
                executions++
                res = runCatchingCancellable { block() }
            } finally {
                withContext(NonCancellable) { lock.withLock { flights.remove(key) } }
                flight.complete(res ?: OwnerCancelled)
            }
            return res!!
        }
    }
}
