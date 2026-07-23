package io.hero.app

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// runCatchingCancellable is the shared contract behind every suspend-call site in
// the effects/handlers: it must behave exactly like kotlin.runCatching EXCEPT it
// never swallows coroutine cancellation. If it caught CancellationException, a
// cancelled effect (node/session switch, Retry, leaving composition) would fall
// into its non-suspend tail — writing loading=false, publishing a stale snapshot,
// or turning a navigation-cancel into a visible error. Pure (no Compose/coroutine
// runtime): a raw CancellationException stands in for a cancelled suspension.
class CancellationTest {
    @Test
    fun rethrowsCancellation() {
        assertFailsWith<CancellationException> {
            runCatchingCancellable { throw CancellationException("cancelled") }
        }
    }

    // A CancellationException raised from DEEP inside the block still escapes —
    // the effect must tear down, not report a business failure.
    @Test
    fun rethrowsNestedCancellation() {
        assertFailsWith<CancellationException> {
            runCatchingCancellable {
                listOf(1).forEach { if (it == 1) throw CancellationException("deep") }
            }
        }
    }

    @Test
    fun capturesOtherThrowables() {
        val result = runCatchingCancellable { throw IllegalStateException("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun returnsSuccessValue() {
        val result = runCatchingCancellable { 21 * 2 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }
}
