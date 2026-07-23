package io.hero.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

// SingleFlight is the shared request owner behind FleetCache's fetchSessions /
// fetchHarness: concurrent same-key callers must share ONE in-flight execution
// (the `executions` counter is the regression), failure is delivered to the
// whole flight rather than retried per caller, and cancellation is per caller —
// a cancelled owner hands the work over to an awake joiner instead of
// stranding it. The gates (CompletableDeferred) make every overlap window
// deterministic under runTest's single-queue scheduler.
class SingleFlightTest {

    @Test
    fun concurrentSameKeyCallersShareOneExecution() = runTest {
        val f = SingleFlight()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val callers = (1..3).map {
            async { f.run("k") { entered.complete(Unit); gate.await() } }
        }
        entered.await() // the owner is inside the block; joiners share its flight
        yield() // let both joiners reach their await
        gate.complete("v")
        val out = callers.awaitAll()
        assertEquals(1, f.executions)
        assertTrue(out.all { it.getOrNull() == "v" })
    }

    @Test
    fun distinctKeysAndCompletedFlightsRunIndependently() = runTest {
        val f = SingleFlight()
        assertEquals("a", f.run("k1") { "a" }.getOrNull())
        assertEquals("b", f.run("k2") { "b" }.getOrNull())
        // A COMPLETED flight is never reused as a cache — the next call re-runs.
        assertEquals("c", f.run("k1") { "c" }.getOrNull())
        assertEquals(3, f.executions)
    }

    // A failed flight is shared: every caller who joined it gets the same
    // failure Result (no per-joiner retry storm); only a LATER call re-executes.
    @Test
    fun failureIsSharedWithTheWholeFlight() = runTest {
        val f = SingleFlight()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val a = async { f.run<String>("k") { entered.complete(Unit); gate.await(); error("boom") } }
        entered.await()
        val b = async { f.run("k") { "never runs" } }
        yield() // b joins the in-flight execution
        gate.complete(Unit)
        assertEquals("boom", a.await().exceptionOrNull()?.message)
        assertEquals("boom", b.await().exceptionOrNull()?.message)
        assertEquals(1, f.executions)
    }

    // Cancelling the OWNER must not strand the flight: the joiner takes the work
    // over under its own ownership and completes with its own block.
    @Test
    fun cancelledOwnerHandsTheFlightToAJoiner() = runTest {
        val f = SingleFlight()
        val entered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<String>()
        val owner = async { f.run("k") { entered.complete(Unit); neverReleased.await() } }
        entered.await()
        val joiner = async { f.run("k") { "taken over" } }
        yield() // the joiner is awaiting the owner's flight
        owner.cancelAndJoin()
        assertEquals("taken over", joiner.await().getOrNull())
        assertEquals(2, f.executions) // owner started once, joiner re-ran once
    }

    // A joiner cancelling detaches only itself: the flight completes for the
    // owner (and anyone else) exactly once.
    @Test
    fun cancelledJoinerDetachesWithoutKillingTheFlight() = runTest {
        val f = SingleFlight()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val owner = async { f.run("k") { entered.complete(Unit); gate.await() } }
        entered.await()
        val joiner = async { f.run("k") { "never runs" } }
        yield()
        joiner.cancelAndJoin()
        gate.complete("v")
        assertEquals("v", owner.await().getOrNull())
        assertEquals(1, f.executions)
    }
}
