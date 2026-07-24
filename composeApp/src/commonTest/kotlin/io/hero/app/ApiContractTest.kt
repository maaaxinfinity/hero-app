package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException

// The Api read/operation contract, exercised against a CONTROLLED HTTP engine
// (not pure helpers): status is checked BEFORE any body decode (a valid JSON
// error object must never resolve into an all-defaults DTO "success"), error
// bodies are byte-bounded before read and display-capped after, mutations are
// never replayed while reads retry transient failures under an explicit
// attempts+deadline+jitter policy, every mutation carries a persistent client
// operation id, and a real suspended cancellation propagates out of
// runCatchingCancellable instead of becoming a business failure.
class ApiContractTest {
    private fun apiOver(engine: MockEngine): Api =
        Api("http://cp.test") { block -> HttpClient(engine) { block() } }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // harness()/fleetHero()/nodes() DTOs are nearly all-defaults: before the
    // status-first contract, a 500 carrying `{"error":...}` decoded into an
    // empty HarnessState / "nothing published" fleet — an error presented as
    // authoritative empty truth.
    @Test
    fun errorJsonNeverDecodesAsDefaultTruth() = runTest {
        val engine = MockEngine { respond("""{"error":"denied"}""", HttpStatusCode.InternalServerError, jsonHeaders) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.harness("n1") }
        assertEquals(500, e.status)
        assertFailsWith<ApiHttpException> { api.fleetHero() }
        assertFailsWith<ApiHttpException> { api.nodes() }
        api.close()
    }

    // A mutation's 4xx is a typed failure with a display-capped message, and the
    // request is issued EXACTLY once — no engine or policy replay of mutations.
    @Test
    fun mutationErrorsAreTypedBoundedAndNeverRetried() = runTest {
        val engine = MockEngine { respond("x".repeat(1_000_000), HttpStatusCode.BadRequest) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.addShare("n1", "alice") }
        assertEquals(400, e.status)
        assertEquals("share", e.operation)
        assertTrue((e.message ?: "").length <= ERROR_DISPLAY_CHARS)
        assertEquals(1, engine.requestHistory.size)
        api.close()
    }

    // Even a TRANSIENT-looking 5xx must not re-run a mutation: at-least-once
    // semantics belong to the user's explicit retry (with the same op id).
    @Test
    fun mutation5xxIsNotRetried() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val api = apiOver(engine)
        assertFailsWith<ApiHttpException> { api.send("n1", "s1", "hi") }
        assertEquals(1, engine.requestHistory.size)
        api.close()
    }

    // The pre-decode ceiling: at most MAX_ERROR_BODY_BYTES of a failed body are
    // ever read/decoded; the rest of the channel is dropped, not buffered.
    @Test
    fun errorBodyReadIsByteBounded() = runTest {
        val text = boundedBodyText(ByteReadChannel(ByteArray(100_000) { 'a'.code.toByte() }), MAX_ERROR_BODY_BYTES)
        assertEquals(MAX_ERROR_BODY_BYTES, text.length)
        // A short body comes back whole.
        assertEquals("tiny", boundedBodyText(ByteReadChannel("tiny".encodeToByteArray()), MAX_ERROR_BODY_BYTES))
    }

    @Test
    fun httpErrorMessageIsDisplayCapped() {
        assertEquals("nodes failed (HTTP 500)", httpErrorMessage(500, "nodes", null))
        assertEquals("boom", httpErrorMessage(400, "share", "  boom  "))
        assertEquals("share failed (HTTP 400)", httpErrorMessage(400, "share", "   "))
        assertEquals(ERROR_DISPLAY_CHARS, httpErrorMessage(400, "share", "y".repeat(10_000)).length)
    }

    // startSession: a non-2xx keeps the node's validation message (typed +
    // capped, so the dialog stays open with a readable error), a success
    // returns EXACTLY the id the server sent, and a missing id / malformed
    // body folds to "" (the dialog's "create failed", never a navigation).
    @Test
    fun startSessionErrorIsTypedAndCapped() = runTest {
        val engine = MockEngine { respond("cwd is required" + "x".repeat(500_000), HttpStatusCode.BadRequest) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.startSession("n1", StartSessionReq("/w", "hello")) }
        assertEquals(400, e.status)
        assertTrue(e.message!!.startsWith("cwd is required"))
        assertTrue(e.message!!.length <= ERROR_DISPLAY_CHARS)
        assertEquals(1, engine.requestHistory.size)
        api.close()
    }

    @Test
    fun startSessionReturnsExactIdOrEmpty() = runTest {
        var body = """{"id":"abc123"}"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }
        val api = apiOver(engine)
        assertEquals("abc123", api.startSession("n1", StartSessionReq("/w", "hello")))
        body = """{}"""
        assertEquals("", api.startSession("n1", StartSessionReq("/w", "hello")))
        body = """not json"""
        assertEquals("", api.startSession("n1", StartSessionReq("/w", "hello")))
        api.close()
    }

    // Reads retry TRANSIENT failures (5xx here) under the bounded policy and
    // then succeed…
    @Test
    fun readsRetryTransientFailuresThenSucceed() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls += 1
            if (calls < 3) respond("bad gateway", HttpStatusCode.BadGateway)
            else respond("[]", HttpStatusCode.OK, jsonHeaders)
        }
        val api = apiOver(engine)
        assertEquals(emptyList<NodeView>(), api.nodes())
        assertEquals(3, calls)
        api.close()
    }

    // …but a deterministic 4xx re-fails immediately (one request)…
    @Test
    fun readsFailFastOnDeterministic4xx() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.NotFound) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.users() }
        assertEquals(404, e.status)
        assertEquals(1, engine.requestHistory.size)
        api.close()
    }

    // …and a persistent outage exhausts the attempt budget, then surfaces.
    @Test
    fun readsExhaustAttemptsAndSurfaceTheError() = runTest {
        val engine = MockEngine { respond("down", HttpStatusCode.ServiceUnavailable) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.attention() }
        assertEquals(503, e.status)
        assertEquals(3, engine.requestHistory.size)
        api.close()
    }

    // me() is the authoritative identity read: 401 is an immediate typed
    // failure (never retried, never guessed), and a 200 with a malformed body
    // is a failure too — the login paths must not commit an unconfirmed Me.
    @Test
    fun meNeverGuessesIdentity() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.me() }
        assertEquals(401, e.status)
        assertEquals(1, engine.requestHistory.size)
        api.close()

        val badEngine = MockEngine { respond("""{"user":""", HttpStatusCode.OK, jsonHeaders) }
        val badApi = apiOver(badEngine)
        assertFails { badApi.me() }
        assertEquals(1, badEngine.requestHistory.size) // decode failures are not transient
        badApi.close()
    }

    // A REAL suspended call cancelled mid-flight: the CancellationException
    // must escape runCatchingCancellable (tearing the effect down), never land
    // in onFailure and run the non-suspend tail.
    @Test
    fun realSuspendedCancellationPropagates() = runTest {
        val engine = MockEngine { awaitCancellation() }
        val api = apiOver(engine)
        var sawFailure = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { api.nodes() }.onFailure { sawFailure = true }
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(sawFailure, "a cancelled suspend call must not be reported as a business failure")
        api.close()
    }

    // Every mutation carries X-Hero-Op; an EXPLICIT retry of the same logical
    // operation reuses the id (the server-side dedupe key), distinct logical
    // operations mint distinct ids.
    @Test
    fun mutationsCarryAPersistentOperationId() = runTest {
        val engine = MockEngine { respond("fail", HttpStatusCode.InternalServerError) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.send("n1", "s1", "hi", operationId = "op-retry") }
        assertEquals("op-retry", e.operationId)
        assertFailsWith<ApiHttpException> { api.send("n1", "s1", "hi", operationId = "op-retry") }
        assertEquals(listOf("op-retry", "op-retry"), engine.requestHistory.map { it.headers[HERO_OP_HEADER] })
        api.close()
    }

    @Test
    fun distinctMutationsMintDistinctIds() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val api = apiOver(engine)
        api.send("n1", "s1", "a")
        api.send("n1", "s1", "b")
        val ids = engine.requestHistory.map { it.headers[HERO_OP_HEADER] }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { !it.isNullOrEmpty() })
        api.close()
    }

    // Native-client CSRF credential: the control-plane boundary admits a
    // state-changing request only with a same-origin Origin (browsers) or the
    // dedicated X-Hero-Client header (apps, which send no Origin). Every request
    // the app makes — the login POST, reads, and mutations — must carry it, so
    // no boundary-gated call 403s and no future mutation can silently omit it.
    @Test
    fun everyRequestCarriesTheNativeClientHeader() = runTest {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, jsonHeaders) }
        val api = apiOver(engine)
        api.login("alice", "pw")   // the login POST (boundary-gated; no cookie back here)
        api.nodes()                // a read
        api.send("n1", "s1", "hi") // a mutation
        api.close()
        assertEquals(3, engine.requestHistory.size)
        assertTrue(
            engine.requestHistory.all { it.headers[HERO_CLIENT_HEADER] == HERO_CLIENT_VALUE },
            "every request must carry $HERO_CLIENT_HEADER=$HERO_CLIENT_VALUE, got " +
                "${engine.requestHistory.map { it.method.value to it.headers[HERO_CLIENT_HEADER] }}",
        )
    }

    @Test
    fun operationIdsAreWellFormedAndUnique() {
        val ids = (1..200).map { newOperationId() }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all { id -> id.length == 32 && id.all { it in "0123456789abcdef" } })
    }

    // The explicit read-retry policy is bounded three ways: attempt count, a
    // total elapsed budget (deadline), and a jittered per-retry ceiling.
    @Test
    fun readRetryDelaysAreBoundedJitteredAndBudgeted() {
        val p = ReadRetryPolicy(attempts = 3, baseDelayMs = 200, maxDelayMs = 2_000, budgetMs = 8_000)
        val rnd = Random(11)
        repeat(200) {
            assertTrue(readRetryDelayMs(p, 0, 0, rnd)!! in 0L..200L)
            assertTrue(readRetryDelayMs(p, 1, 0, rnd)!! in 0L..400L)
        }
        assertNull(readRetryDelayMs(p, 2, 0, rnd), "attempts exhausted")
        assertNull(readRetryDelayMs(p, 0, 8_000, rnd), "deadline budget spent")
        assertNull(readRetryDelayMs(p, 0, 9_000, rnd), "past the deadline")
        // Deep attempt indexes neither overflow nor exceed the max ceiling.
        val deep = ReadRetryPolicy(attempts = Int.MAX_VALUE)
        repeat(200) { assertTrue(readRetryDelayMs(deep, 1_000_000, 0, rnd)!! in 0L..deep.maxDelayMs) }
        // Jitter actually spreads.
        val draws = (1..200).map { readRetryDelayMs(p, 1, 0, rnd)!! }.toSet()
        assertTrue(draws.size > 20, "expected spread-out jitter, got ${draws.size} distinct values")
    }

    @Test
    fun transientClassificationIsExact() {
        assertFalse(isTransientReadError(CancellationException("nav")))
        assertFalse(isTransientReadError(ApiHttpException(400, "x")))
        assertFalse(isTransientReadError(ApiHttpException(404, "x")))
        assertTrue(isTransientReadError(ApiHttpException(408, "x")))
        assertTrue(isTransientReadError(ApiHttpException(429, "x")))
        assertTrue(isTransientReadError(ApiHttpException(500, "x")))
        assertTrue(isTransientReadError(ApiHttpException(503, "x")))
        assertFalse(isTransientReadError(SerializationException("bad json")))
        assertTrue(isTransientReadError(RuntimeException("connection reset")))
    }
}
