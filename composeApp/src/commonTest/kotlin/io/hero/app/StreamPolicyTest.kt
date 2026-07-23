package io.hero.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The live-stream lifecycle policies: permanent-vs-transient error
// classification (what stops the reconnect loop), the capped full-jitter
// backoff schedule (what replaced the fixed 1.5s bare loop), the server-status
// live decision for drilled-in subagents, and the stable-key scroll anchor for
// "Load earlier" (including the header-disappears-on-last-page case). All pure.
class StreamPolicyTest {
    // 401/403 and protocol violations (204 / non-SSE content type) are
    // permanent — reconnecting re-fetches the same failure. Everything else
    // (proxy 5xx, EOF, resets, over-limit lines) stays transient.
    @Test
    fun classifiesPermanentVersusTransient() {
        assertTrue(isPermanentStreamError(StreamAuthException(401)))
        assertTrue(isPermanentStreamError(StreamAuthException(403)))
        assertTrue(isPermanentStreamError(StreamProtocolException("not an event stream: text/html")))
        assertTrue(isPermanentStreamError(StreamProtocolException("empty stream (204 No Content)")))
        assertFalse(isPermanentStreamError(IllegalStateException("stream 502")))
        assertFalse(isPermanentStreamError(IllegalStateException("stream 404")))
        assertFalse(isPermanentStreamError(RuntimeException("connection reset")))
        assertFalse(isPermanentStreamError(IllegalStateException("SSE line exceeds 16777216 bytes")))
    }

    // Full jitter: a uniform draw from [0, ceiling], ceiling doubling from 1s
    // and capped at 30s. Deep attempt counts must neither overflow nor exceed
    // the cap.
    @Test
    fun backoffIsBoundedFullJitter() {
        val rnd = Random(7)
        repeat(200) {
            assertTrue(streamBackoffMillis(0, rnd) in 0L..1_000L)
            assertTrue(streamBackoffMillis(1, rnd) in 0L..2_000L)
            assertTrue(streamBackoffMillis(2, rnd) in 0L..4_000L)
            assertTrue(streamBackoffMillis(5, rnd) in 0L..30_000L)
            assertTrue(streamBackoffMillis(50, rnd) in 0L..30_000L)
            assertTrue(streamBackoffMillis(Int.MAX_VALUE, rnd) in 0L..30_000L)
        }
        // Jitter actually spreads (not a fixed delay): many draws, many values.
        val draws = (1..200).map { streamBackoffMillis(5, rnd) }.toSet()
        assertTrue(draws.size > 20, "expected spread-out jitter, got ${draws.size} distinct values")
    }

    // Live subscription follows the SERVER's status for the opened session:
    // only an explicitly finished state skips the tail. Unknown/missing status
    // subscribes — wrongly skipping froze still-running subagent drill-ins.
    @Test
    fun sessionLiveFollowsServerStatus() {
        assertTrue(sessionLive("running"))
        assertTrue(sessionLive("idle"))
        assertTrue(sessionLive(""))
        assertTrue(sessionLive(null))
        assertTrue(sessionLive("some-future-status"))
        listOf(
            "completed", "Complete", "FINISHED", "done", "exited",
            "exit", "stopped", "errored", "error", "failed", " Completed ",
        ).forEach { assertFalse(sessionLive(it), "expected '$it' to skip the live tail") }
    }

    // The regression this anchor exists for: loading the LAST page flips
    // hasMore false, the "Load earlier" header item vanishes, and index math
    // (old + added) would land one row down. Anchoring by the first visible
    // turn's stable key keeps it stationary either way.
    @Test
    fun anchorKeepsFirstTurnStationaryWhenHeaderDisappears() {
        // Before: [header, B, C], first visible turn B at scroll offset 13.
        // The last page prepends A and removes the header.
        val keysAfter = listOf<Any>("A", "B", "C")
        assertEquals(1 to 13, anchorAfterPrepend("B", keysAfter, hasHeaderAfter = false, offset = 13))
        // A non-final page keeps the header at index 0: same turn, one lower.
        assertEquals(2 to 13, anchorAfterPrepend("B", keysAfter, hasHeaderAfter = true, offset = 13))
    }

    @Test
    fun anchorFallsBackToTopWhenTheKeyVanished() {
        val keysAfter = listOf<Any>("A", "B")
        assertEquals(0 to 0, anchorAfterPrepend("Z", keysAfter, hasHeaderAfter = true, offset = 13))
        assertEquals(0 to 0, anchorAfterPrepend(null, keysAfter, hasHeaderAfter = false, offset = 13))
        assertEquals(0 to 0, anchorAfterPrepend("A", emptyList(), hasHeaderAfter = false, offset = 13))
    }
}
