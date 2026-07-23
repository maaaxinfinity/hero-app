package io.hero.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

// The single fleet poll owner: one loop refreshes nodes + attention, screens
// only REQUEST (wake / attentionFast). These tests pin the owner loop's
// counters (one fetch of each per cycle — never a competing concurrent fetch),
// the wake fast-path, the fast cadence while the Attention screen is open, and
// the pure jitter helper's bounds.
class FleetPollTest {

    // pollDelayMillis: uniform in [base/2, 3·base/2] — the average cadence stays
    // the base while simultaneous pollers decorrelate (no synchronized wake-up).
    @Test
    fun pollDelayIsJitteredWithinBounds() {
        val r = Random(7)
        val draws = (1..1000).map { pollDelayMillis(7_000, r) }
        assertTrue(draws.all { it in 3_500..10_500 })
        assertTrue(draws.distinct().size > 1) // real jitter, not a constant
        // Deterministic for a seeded source (testable contract).
        assertEquals(pollDelayMillis(4_000, Random(1)), pollDelayMillis(4_000, Random(1)))
    }

    // wake() conflates: a burst of refresh requests (Retry taps, several screens
    // waking at once) collapses into AT MOST one pending cycle, never a queue of
    // storm fetches.
    @Test
    fun wakeRequestsConflate() {
        val poll = FleetPoll()
        repeat(5) { poll.wake() }
        assertTrue(poll.wakes.tryReceive().isSuccess)
        assertTrue(poll.wakes.tryReceive().isFailure) // nothing else queued
    }

    // One cycle = exactly one nodes fetch + one attention fetch, sequentially —
    // and the wait base switches to the FAST cadence while the Attention screen
    // is open. The injected wait exposes the delay the owner asked for.
    @Test
    fun ownerLoopFetchesOncePerCycleAndHonorsTheFastBase() = runTest {
        val poll = FleetPoll()
        var nodesFetches = 0
        var attentionFetches = 0
        val waitArgs = mutableListOf<Long>()
        var gate = CompletableDeferred<Unit>()
        val job = launch {
            fleetPollOwner(
                poll,
                fetchNodes = { nodesFetches++ },
                fetchAttention = { attentionFetches++ },
                random = Random(3),
                wait = { ms -> waitArgs += ms; gate.await() },
            )
        }
        yield() // first cycle runs, owner parks in wait
        assertEquals(1, nodesFetches)
        assertEquals(1, attentionFetches)
        assertTrue(waitArgs[0] in 3_500..10_500) // slow-base jitter window

        poll.attentionFast = true // the Attention screen opened
        val g1 = gate
        gate = CompletableDeferred()
        g1.complete(Unit) // release the wait -> next cycle
        yield()
        assertEquals(2, nodesFetches)
        assertEquals(2, attentionFetches)
        assertTrue(waitArgs[1] in 2_000..6_000) // fast-base jitter window

        job.cancelAndJoin()
        assertEquals(2, nodesFetches) // cancelling the owner stops the loop
    }

    // With the DEFAULT wait, a wake request cuts the delay short: the next cycle
    // starts immediately instead of waiting out the full poll interval.
    @Test
    fun wakeCutsTheDefaultWaitShort() = runTest {
        val poll = FleetPoll()
        var fetches = 0
        val job = launch {
            fleetPollOwner(poll, fetchNodes = { fetches++ }, fetchAttention = {}, random = Random(1))
        }
        yield() // first cycle, owner parked on the (virtual-time) wait
        assertEquals(1, fetches)
        poll.wake() // no time advance needed — the wake alone wakes the owner
        yield()
        assertEquals(2, fetches)
        job.cancelAndJoin()
    }
}
