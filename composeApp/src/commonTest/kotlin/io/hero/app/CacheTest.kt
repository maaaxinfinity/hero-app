package io.hero.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// FleetCache's ownership logic (generation identity, unreachable-node eviction,
// weighted LRU bound, audit-by-limit) is pure enough to exercise against the
// singleton directly — no Compose runtime needed. Each test starts from clear()
// so the monotonic generation is a known-fresh baseline and no entry leaks in
// from a prior test.
class CacheTest {
    private fun session(id: String, cwd: String = "") = Session(id = id, cwd = cwd)
    private fun harness() = HarnessState(
        backends = listOf(HarnessBackend(backend = "claude", catalog = HarnessCatalog(models = listOf(HarnessModel(slug = "m"))))),
    )

    // A write stamped with an old generation (a completion that lands after the
    // identity was replaced) must not overwrite the current session's cache.
    @Test
    fun staleGenerationWriteIsIgnored() {
        FleetCache.clear()
        val gen0 = FleetCache.generation
        FleetCache.putSessions("n1", gen0, listOf(session("a")))
        assertEquals(listOf(session("a")), FleetCache.sessionsOf("n1"))

        // Identity replacement bumps the generation and drops everything.
        FleetCache.bindIdentity("https://other", "bob")
        assertTrue(FleetCache.generation != gen0)
        assertNull(FleetCache.sessionsOf("n1"))

        // The late completion from the old generation is refused.
        FleetCache.putSessions("n1", gen0, listOf(session("b")))
        assertNull(FleetCache.sessionsOf("n1"))

        // A write under the current generation lands.
        val gen1 = FleetCache.generation
        FleetCache.putSessions("n1", gen1, listOf(session("c")))
        assertEquals(listOf(session("c")), FleetCache.sessionsOf("n1"))
    }

    // Re-binding the same {server, user} is a no-op: the warm cache-first set
    // survives a same-identity remount (the UX this whole cache exists for).
    @Test
    fun sameIdentityRebindKeepsWarmSet() {
        FleetCache.clear()
        FleetCache.bindIdentity("https://cp", "alice")
        val gen = FleetCache.generation
        FleetCache.putSessions("n1", gen, listOf(session("a")))
        FleetCache.putHarness("n1", gen, harness())

        FleetCache.bindIdentity("https://cp", "alice")
        assertEquals(gen, FleetCache.generation)
        assertNotNull(FleetCache.sessionsOf("n1"))
        assertNotNull(FleetCache.harnessOf("n1"))
    }

    // When the authoritative nodes snapshot drops a node, its per-node session
    // and harness entries are reclaimed; nodes still present are kept.
    @Test
    fun putNodesEvictsUnreachableNodes() {
        FleetCache.clear()
        val gen = FleetCache.generation
        FleetCache.putSessions("n1", gen, listOf(session("a")))
        FleetCache.putSessions("n2", gen, listOf(session("b")))
        FleetCache.putHarness("n1", gen, harness())
        FleetCache.putHarness("n2", gen, harness())

        // n2 has left the fleet.
        FleetCache.putNodes(gen, listOf(NodeView(node_id = "n1", connected = true)))

        assertNotNull(FleetCache.sessionsOf("n1"))
        assertNotNull(FleetCache.harnessOf("n1"))
        assertNull(FleetCache.sessionsOf("n2"))
        assertNull(FleetCache.harnessOf("n2"))
    }

    // A node that reconnects (or changes scope/version) has its volatile harness
    // entry dropped so a picker can't keep serving stale install/model state.
    // Sessions, which re-poll on their own, are left in place.
    @Test
    fun putNodesDropsHarnessOnReconnect() {
        FleetCache.clear()
        val gen = FleetCache.generation
        FleetCache.putNodes(gen, listOf(NodeView(node_id = "n1", connected = false, connected_at = "t1")))
        FleetCache.putHarness("n1", gen, harness())
        FleetCache.putSessions("n1", gen, listOf(session("a")))
        assertNotNull(FleetCache.harnessOf("n1"))

        // Reconnect: connected flips and connected_at changes.
        FleetCache.putNodes(gen, listOf(NodeView(node_id = "n1", connected = true, connected_at = "t2")))
        assertNull(FleetCache.harnessOf("n1"))
        assertNotNull(FleetCache.sessionsOf("n1"))
    }

    // The per-node session cache is bounded by entry count: inserting past the
    // cap evicts the least-recently-written node, keeping the recent working set.
    @Test
    fun sessionsAreBoundedByEntryCount() {
        FleetCache.clear()
        val gen = FleetCache.generation
        // 13 nodes with a 12-entry cap → the oldest write ("s0") is evicted.
        for (i in 0..12) FleetCache.putSessions("s$i", gen, listOf(session("x")))
        assertNull(FleetCache.sessionsOf("s0"))
        assertNotNull(FleetCache.sessionsOf("s1"))
        assertNotNull(FleetCache.sessionsOf("s12"))
    }

    // The per-node session cache is also bounded by an estimated-byte budget:
    // two heavy entries whose combined weight exceeds the budget evict the older.
    @Test
    fun sessionsAreBoundedByBytes() {
        FleetCache.clear()
        val gen = FleetCache.generation
        val heavy = "x".repeat(2_500_000) // ~2.5 MB each; two exceed the 4 MB budget
        FleetCache.putSessions("big1", gen, listOf(session("a", cwd = heavy)))
        FleetCache.putSessions("big2", gen, listOf(session("b", cwd = heavy)))
        assertNull(FleetCache.sessionsOf("big1"))
        assertNotNull(FleetCache.sessionsOf("big2"))
    }

    // Audit is keyed by fetch limit: loading 1000 rows then re-entering at 300
    // must not surface the 1000-row set as the 300 result.
    @Test
    fun auditIsKeyedByLimit() {
        FleetCache.clear()
        val gen = FleetCache.generation
        val big = List(1000) { AuditRecord(ts = "t$it", action = "a") }
        FleetCache.putAudit(1000, gen, big)
        assertEquals(1000, FleetCache.auditOf(1000)?.size)
        // Re-entering at 300 finds nothing cached for 300 — not the 1000 set.
        assertNull(FleetCache.auditOf(300))

        val small = List(300) { AuditRecord(ts = "u$it", action = "b") }
        FleetCache.putAudit(300, gen, small)
        assertEquals(300, FleetCache.auditOf(300)?.size)
        assertEquals(1000, FleetCache.auditOf(1000)?.size)
    }

    // Cold start / re-login: bindIdentity draws the generation boundary and
    // returns the bound generation. Anything stamped with a pre-bind generation
    // (an effect that raced the bind) is cleared at the boundary and refused
    // afterwards; only writes carrying the bound generation land. Same-identity
    // rebinding is generation-stable, so a warm remount keeps the set.
    @Test
    fun bindIdentityDrawsTheStartupGenerationBoundary() {
        FleetCache.clear()
        val preBind = FleetCache.generation
        FleetCache.putSessions("n1", preBind, listOf(session("boot")))
        val bound = FleetCache.bindIdentity("https://cp", "alice")
        assertTrue(bound != preBind)
        assertEquals(bound, FleetCache.generation)
        assertNull(FleetCache.sessionsOf("n1")) // cleared at the boundary
        FleetCache.putSessions("n1", preBind, listOf(session("late"))) // pre-bind straggler refused
        assertNull(FleetCache.sessionsOf("n1"))
        FleetCache.putSessions("n1", bound, listOf(session("ok")))
        assertNotNull(FleetCache.sessionsOf("n1"))
        assertEquals(bound, FleetCache.bindIdentity("https://cp", "alice"))
        assertNotNull(FleetCache.sessionsOf("n1"))
    }

    // The byte budget is a HARD cap: a single entry beyond it is refused at the
    // door (no "only one entry left" exemption) — and the node's previous,
    // now-stale entry is dropped rather than left masquerading as current.
    @Test
    fun oversizeSessionEntryIsNeverRetained() {
        FleetCache.clear()
        val gen = FleetCache.generation
        FleetCache.putSessions("n1", gen, listOf(session("small")))
        assertNotNull(FleetCache.sessionsOf("n1"))
        val over = "x".repeat(5 * 1024 * 1024) // one entry > the whole 4 MiB budget
        FleetCache.putSessions("n1", gen, listOf(session("big", cwd = over)))
        assertNull(FleetCache.sessionsOf("n1"))
        // The cache keeps admitting normal entries afterwards.
        FleetCache.putSessions("n2", gen, listOf(session("ok")))
        assertNotNull(FleetCache.sessionsOf("n2"))
    }

    @Test
    fun oversizeHarnessEntryIsNeverRetained() {
        FleetCache.clear()
        val gen = FleetCache.generation
        FleetCache.putHarness("n1", gen, HarnessState(pull_url = "x".repeat(3 * 1024 * 1024))) // > 2 MiB cap
        assertNull(FleetCache.harnessOf("n1"))
        FleetCache.putHarness("n1", gen, harness())
        assertNotNull(FleetCache.harnessOf("n1"))
    }

    // Harness invalidation after a mutation (Save + apply / Install) drops just
    // that node's entry so the next picker open re-reads it.
    @Test
    fun invalidateHarnessDropsEntry() {
        FleetCache.clear()
        val gen = FleetCache.generation
        FleetCache.putHarness("n1", gen, harness())
        FleetCache.putHarness("n2", gen, harness())
        FleetCache.invalidateHarness("n1")
        assertNull(FleetCache.harnessOf("n1"))
        assertNotNull(FleetCache.harnessOf("n2"))
    }
}
