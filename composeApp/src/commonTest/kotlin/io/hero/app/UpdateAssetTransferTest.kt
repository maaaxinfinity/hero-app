package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

// The updater's TRANSFER contract, exercised end to end against a CONTROLLED
// HTTP engine (not just pure helpers): only a complete-response 200 is
// admitted (a 206 segment, a 202 status body and a rate-limit HTML page are
// all rejected before/without committing), a declared Content-Length must
// match the received count exactly (a truncated stream is a failure, never a
// completed download), a silent peer aborts within the idle budget, and a real
// suspended cancellation propagates as CancellationException instead of being
// folded into a result. File placement/container verification live in the
// platform actuals and their desktop tests.
class UpdateAssetTransferTest {
    private class Sink {
        val out = ArrayList<Byte>()
        val write: (ByteArray, Int) -> Unit = { b, n -> repeat(n) { out.add(b[it]) } }
        fun bytes() = out.toByteArray()
    }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun complete200WithMatchingLengthIsAccepted() = runTest {
        val data = bytes(150_000)
        val engine = MockEngine {
            respond(ByteReadChannel(data), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, data.size.toString()))
        }
        val client = HttpClient(engine)
        val sink = Sink()
        val received = downloadUpdateAsset(client, "https://host/asset", { _, _ -> }, write = sink.write)
        assertEquals(data.size.toLong(), received)
        assertContentEquals(data, sink.bytes())
        // The asset request declares itself a binary download.
        assertEquals("application/octet-stream", engine.requestHistory.single().headers[HttpHeaders.Accept])
        client.close()
    }

    // The updater never sends Range: a 206's Content-Length describes the
    // SEGMENT, so "received == declared" would verify a PARTIAL package as
    // complete. It must be rejected on status — before a byte is written.
    @Test
    fun partial206SegmentIsRejectedBeforeAnyWrite() = runTest {
        val segment = bytes(4_000)
        val engine = MockEngine {
            respond(
                ByteReadChannel(segment),
                HttpStatusCode.PartialContent,
                headersOf(
                    HttpHeaders.ContentLength to listOf(segment.size.toString()),
                    HttpHeaders.ContentRange to listOf("bytes 0-3999/100000"),
                ),
            )
        }
        val client = HttpClient(engine)
        val sink = Sink()
        val ex = assertFailsWith<UpdateDownloadException> {
            downloadUpdateAsset(client, "https://host/asset", { _, _ -> }, write = sink.write)
        }
        assertTrue(ex.message!!.contains("206"))
        assertEquals(0, sink.bytes().size)
        client.close()
    }

    // Other non-200 success shapes don't carry the verbatim complete asset
    // either: a 202 progress body must never be committed as a package.
    @Test
    fun accepted202StatusBodyIsRejected() = runTest {
        val engine = MockEngine {
            respond(ByteReadChannel("{\"status\":\"processing\"}".encodeToByteArray()), HttpStatusCode.Accepted)
        }
        val client = HttpClient(engine)
        val sink = Sink()
        assertFailsWith<UpdateDownloadException> {
            downloadUpdateAsset(client, "https://host/asset", { _, _ -> }, write = sink.write)
        }
        assertEquals(0, sink.bytes().size)
        client.close()
    }

    @Test
    fun rateLimitHtmlErrorPageIsRejected() = runTest {
        val engine = MockEngine {
            respond(ByteReadChannel("<html>rate limited</html>".encodeToByteArray()), HttpStatusCode.NotFound)
        }
        val client = HttpClient(engine)
        val sink = Sink()
        assertFailsWith<UpdateDownloadException> {
            downloadUpdateAsset(client, "https://host/asset", { _, _ -> }, write = sink.write)
        }
        assertEquals(0, sink.bytes().size)
        client.close()
    }

    // A stream that ends early against its declared Content-Length is a
    // truncation failure — the caller must clean up, never publish.
    @Test
    fun truncatedBodyAgainstDeclaredLengthIsRejected() = runTest {
        val engine = MockEngine {
            respond(ByteReadChannel(bytes(400)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000"))
        }
        val client = HttpClient(engine)
        val ex = assertFailsWith<UpdateDownloadException> {
            downloadUpdateAsset(client, "https://host/asset", { _, _ -> }) { _, _ -> }
        }
        assertTrue(ex.message!!.contains("truncated"), "got: ${ex.message}")
        client.close()
    }

    // With no Content-Length the transfer layer admits the stream but stays
    // bounded by the byte ceiling; completeness is then proven by package
    // verification before publish (platform actuals).
    @Test
    fun noContentLengthIsAdmittedButCeilingBounded() = runTest {
        val small = bytes(9_000)
        val engineOk = MockEngine { respond(ByteReadChannel(small), HttpStatusCode.OK) }
        val ok = HttpClient(engineOk)
        assertEquals(small.size.toLong(), downloadUpdateAsset(ok, "https://host/asset", { _, _ -> }) { _, _ -> })
        ok.close()

        val engineBig = MockEngine { respond(ByteReadChannel(bytes(60_000)), HttpStatusCode.OK) }
        val big = HttpClient(engineBig)
        val ex = assertFailsWith<UpdateDownloadException> {
            downloadUpdateAsset(big, "https://host/asset", { _, _ -> }, maxBytes = 50_000) { _, _ -> }
        }
        assertTrue(ex.message!!.contains("ceiling"))
        big.close()
    }

    // A peer that completes the handshake and then never sends a byte must
    // fail within the idle budget (virtual time here) — not hang forever.
    @Test
    fun silentPeerFailsWithinTheIdleBudget() = runTest {
        val engine = MockEngine { respond(ByteChannel(), HttpStatusCode.OK) } // headers sent, body never fed
        val client = HttpClient(engine)
        val ex = assertFailsWith<UpdateDownloadException> {
            downloadUpdateAsset(client, "https://host/asset", { _, _ -> }) { _, _ -> }
        }
        assertTrue(ex.message!!.contains("stalled"))
        client.close()
    }

    // A REAL suspended cancellation (caller leaves mid-download) propagates as
    // CancellationException through the engine-driven path — never normalized
    // into a business failure/result string.
    @Test
    fun suspendedCancellationPropagates() = runTest {
        val engine = MockEngine { respond(ByteChannel(), HttpStatusCode.OK) }
        val client = HttpClient(engine)
        var outcome: Throwable? = null
        val job = launch {
            try {
                // idleBudget off so the only way out is the caller's cancel.
                downloadUpdateAsset(client, "https://host/asset", { _, _ -> }, idleBudgetMillis = null) { _, _ -> }
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        testScheduler.runCurrent() // let it reach the suspended read
        job.cancel()
        job.join()
        assertTrue(outcome is CancellationException, "expected CancellationException, got $outcome")
        client.close()
    }
}
