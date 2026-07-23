package io.hero.app

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// streamDownload takes a plain ByteReadChannel + write sink (no HTTP engine), so
// it is driven here with in-memory channels: the copy loop, the byte ceiling,
// the progress throttle, the idle budget (a stalled peer is a DOWNLOAD failure)
// and real coroutine cancellation (which must stay a CancellationException).
// runBlocking lives in the desktop (JVM) test source set.
class StreamDownloadTest {
    // A payload larger than the internal 64 KiB read buffer, so multiple reads
    // are exercised.
    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun copiesEveryByteAndReportsFinalProgress() = runBlocking {
        val data = payload(200_000)
        val out = ByteArrayOutputStream()
        var lastReceived = 0L
        var lastTotal: Long? = -1L
        val total = streamDownload(
            channel = ByteReadChannel(data),
            expected = data.size.toLong(),
            onProgress = { received, t -> lastReceived = received; lastTotal = t },
        ) { b, n -> out.write(b, 0, n) }

        assertEquals(data.size.toLong(), total)
        // Throttling may skip intermediate reports but never the final state.
        assertEquals(data.size.toLong(), lastReceived)
        assertEquals(data.size.toLong(), lastTotal)
        assertContentEquals(data, out.toByteArray())
    }

    @Test
    fun enforcesByteCeilingWithoutWritingPastIt() {
        val data = payload(200_000)
        val out = ByteArrayOutputStream()
        val ex = assertFailsWith<UpdateDownloadException> {
            runBlocking {
                streamDownload(
                    channel = ByteReadChannel(data),
                    expected = null, // no declared length → the mid-stream guard is the only bound
                    onProgress = { _, _ -> },
                    maxBytes = 100_000,
                ) { b, n -> out.write(b, 0, n) }
            }
        }
        assertTrue(ex.message!!.contains("ceiling"))
        // The over-ceiling chunk is rejected before it is written, so we never
        // persist the whole payload nor exceed the ceiling.
        assertTrue(out.size() < data.size)
        assertTrue(out.size() <= 100_000)
    }

    // With a known total, progress is throttled to whole-percent changes: 300
    // one-KB chunks must produce at most ~101 callbacks (plus the final one),
    // not one per chunk — the UI owner must see a bounded callback rate.
    @Test
    fun progressIsThrottledToPercentGranularity() = runBlocking {
        val chunk = ByteArray(1000) { 7 }
        val chunks = 300
        val chan = ByteChannel()
        val writer = launch {
            repeat(chunks) { chan.writeFully(chunk, 0, chunk.size) }
            chan.flushAndClose()
        }
        var calls = 0
        var lastReceived = 0L
        val total = streamDownload(
            channel = chan,
            expected = (chunk.size * chunks).toLong(),
            onProgress = { received, _ -> calls++; lastReceived = received },
        ) { _, _ -> }
        writer.join()
        assertEquals((chunk.size * chunks).toLong(), total)
        assertEquals(total, lastReceived)
        assertTrue(calls <= 102, "expected ≤102 throttled progress callbacks, got $calls")
    }

    // With NO declared total, progress is throttled to one report per
    // DownloadProgressStepBytes (plus the final state).
    @Test
    fun unknownLengthProgressIsThrottledByStep() = runBlocking {
        val size = (3.5 * 1024 * 1024).toInt() // ~56 reads of the 64 KiB buffer
        var calls = 0
        var lastReceived = 0L
        val total = streamDownload(
            channel = ByteReadChannel(payload(size)),
            expected = null,
            onProgress = { received, _ -> calls++; lastReceived = received },
        ) { _, _ -> }
        assertEquals(size.toLong(), total)
        assertEquals(total, lastReceived)
        assertTrue(calls <= 6, "expected ≤6 step-throttled progress callbacks, got $calls")
    }

    // A peer that goes silent after the handshake must abort as a DOWNLOAD
    // failure within the idle budget — the client has no socket read timeout,
    // so this budget is the only thing standing between "quiet peer" and
    // "hangs forever".
    @Test
    fun stalledStreamFailsWithinIdleBudget() {
        val ex = assertFailsWith<UpdateDownloadException> {
            runBlocking {
                streamDownload(
                    channel = ByteChannel(), // never written, never closed
                    expected = 10_000L,
                    onProgress = { _, _ -> },
                    idleBudgetMillis = 200,
                ) { _, _ -> }
            }
        }
        assertTrue(ex.message!!.contains("stalled"))
    }

    // Caller cancellation must propagate as CancellationException — the idle
    // budget's internal timeout handling must not convert an OUTER cancel into
    // an UpdateDownloadException (and nothing may swallow it into a string).
    @Test
    fun cancellationStaysACancellationException() = runBlocking {
        var outcome: Throwable? = null
        val job = launch {
            try {
                streamDownload(
                    channel = ByteChannel(), // never written: suspends on the first read
                    expected = null,
                    onProgress = { _, _ -> },
                    idleBudgetMillis = 30_000, // the default-shaped budget must not fire here
                ) { _, _ -> }
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        delay(50) // let it reach the suspended read
        job.cancel()
        job.join()
        assertTrue(outcome is CancellationException, "expected CancellationException, got $outcome")
    }
}
