package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

// The session-runtime + model-catalog backend contract the app now adopts:
//
//  - the Session DTO mirrors the FULL node runtime (model / effort / context
//    tokens+window); an old node that omits it decodes to all-unknown, never a
//    decode failure.
//  - runtime is consumed FIELD-LEVEL: a partial session.runtime patch (only the
//    model, only the window) overlays without blanking what it doesn't carry, so
//    a live frame can't erase a field the snapshot established (and vice versa).
//  - the picker distinguishes ACTUAL (the node's authoritative current value)
//    from PENDING (the user's queued target) — a send does not promote pending.
//  - GET /models decodes the lightweight picker snapshot; an old node's typed 501
//    is a discriminable capability code the picker/send degrade on, and a 501 is
//    NOT retried by the read layer.
class RuntimeContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun ev(type: String, raw: String?) =
        Event(session_id = "s", type = type, raw = raw?.let { json.parseToJsonElement(it) })
    private fun apiOver(engine: MockEngine): Api =
        Api("http://cp.test") { block -> HttpClient(engine) { block() } }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // ---- Session runtime DTO ----

    @Test
    fun sessionDecodesFullRuntime() {
        val s = json.decodeFromString(
            Session.serializer(),
            """{"id":"s1","backend":"codex","status":"idle",
                "runtime":{"model":"gpt-5","effort":"high","context_tokens":1500,"context_window":200000}}""",
        )
        assertEquals("gpt-5", s.runtime.model)
        assertEquals("high", s.runtime.effort)
        assertEquals(1500L, s.runtime.context_tokens)
        assertEquals(200000L, s.runtime.context_window)
    }

    @Test
    fun oldNodeSessionDecodesToUnknownRuntimeNotAFailure() {
        // No runtime object at all (a pre-projection node): the field defaults, and
        // its projection is all-unknown (null) — so the picker shows "current".
        val s = json.decodeFromString(Session.serializer(), """{"id":"s2","backend":"claude"}""")
        assertEquals(RuntimeState(), s.runtime.toRuntimeState())
    }

    @Test
    fun toRuntimeStateTreatsEmptyAndZeroAsUnknown() {
        assertEquals(RuntimeState(), SessionRuntime().toRuntimeState())
        assertEquals(
            RuntimeState(model = "m", contextWindow = 200000),
            SessionRuntime(model = "m", effort = "", context_tokens = 0, context_window = 200000).toRuntimeState(),
        )
    }

    // ---- field-level merge (the "not frozen, doesn't blank" contract) ----

    @Test
    fun mergeOverlaysKnownFieldsOnly() {
        val base = RuntimeState(model = "a", effort = "low", contextWindow = 200000)
        // A patch carrying only the model keeps effort and window.
        assertEquals(
            RuntimeState(model = "b", effort = "low", contextWindow = 200000),
            base.merge(RuntimeState(model = "b")),
        )
        // A patch carrying only the window keeps model and effort.
        assertEquals(
            RuntimeState(model = "a", effort = "low", contextWindow = 100000),
            base.merge(RuntimeState(contextWindow = 100000)),
        )
        // An all-unknown patch changes nothing.
        assertEquals(base, base.merge(RuntimeState()))
    }

    @Test
    fun runtimeFrameDecodesAsFieldLevelPatch() {
        val f = decodeLiveFrame(ev("session.runtime", """{"model":"m1"}"""), json)
        assertIs<LiveFrame.Runtime>(f)
        assertEquals("m1", f.patch.model)
        assertNull(f.patch.contextWindow, "an absent window must be unknown, not 0")

        val f2 = decodeLiveFrame(ev("session.runtime", """{"context_window":200000,"context_tokens":1500}"""), json)
        assertIs<LiveFrame.Runtime>(f2)
        assertNull(f2.patch.model, "an absent model must be unknown, not blank")
        assertEquals(200000L, f2.patch.contextWindow)
        assertEquals(1500L, f2.patch.contextTokens)
    }

    @Test
    fun reduceAppliesRuntimePatchesFieldLevel() {
        // Seed the ACTUAL from a snapshot, then stream two partial patches; each one
        // updates only its field and never blanks the others — the frozen-state bug.
        var s = ConvoState(runtime = RuntimeState(model = "a", contextWindow = 200000))
        s = s.reduce(LiveFrame.Runtime(RuntimeState(model = "b")))
        assertEquals("b", s.runtime.model)
        assertEquals(200000L, s.runtime.contextWindow)
        s = s.reduce(LiveFrame.Runtime(RuntimeState(effort = "high", contextTokens = 4200)))
        assertEquals("b", s.runtime.model)
        assertEquals("high", s.runtime.effort)
        assertEquals(4200L, s.runtime.contextTokens)
        assertEquals(200000L, s.runtime.contextWindow)
    }

    @Test
    fun withActualMergesSnapshotWhileKeepingLiveFields() {
        val s = ConvoState(runtime = RuntimeState(model = "a", effort = "high"))
        val merged = s.withActual(RuntimeState(contextWindow = 200000))
        assertEquals("a", merged.runtime.model)
        assertEquals("high", merged.runtime.effort)
        assertEquals(200000L, merged.runtime.contextWindow)
    }

    // ---- presentation ----

    @Test
    fun runtimeSummaryShowsOnlyKnownFields() {
        assertNull(runtimeSummary(RuntimeState()))
        assertEquals("gpt-5", runtimeSummary(RuntimeState(model = "gpt-5")))
        assertEquals(
            "gpt-5  ·  high  ·  1k/200k",
            runtimeSummary(RuntimeState(model = "gpt-5", effort = "high", contextTokens = 1500, contextWindow = 200000)),
        )
    }

    @Test
    fun contextUsageNeedsAWindow() {
        assertEquals("1k/200k", contextUsage(1500, 200000))
        assertEquals("0/8k", contextUsage(0, 8000))
        assertNull(contextUsage(100, null))
        assertNull(contextUsage(100, 0))
    }

    @Test
    fun pickerActualVsPendingContract() {
        // Rendered value: pending wins, else actual, else a placeholder.
        assertEquals("current", pickerShown(null, ""))
        assertEquals("gpt-5", pickerShown("gpt-5", ""))
        assertEquals("o3", pickerShown("gpt-5", "o3"))
        // "has pending" only when the queued target DIFFERS from actual (a send
        // doesn't promote pending, so equal means already-in-effect, not queued).
        assertFalse(pickerHasPending("gpt-5", ""))
        assertFalse(pickerHasPending("gpt-5", "gpt-5"))
        assertTrue(pickerHasPending("gpt-5", "o3"))
        assertTrue(pickerHasPending(null, "o3"))
    }

    // ---- model catalog decode ----

    @Test
    fun modelCatalogDecodes() = runTest {
        val body = """{"backends":[
            {"backend":"claude","models":[
                {"slug":"claude-sonnet-4-5","label":"Sonnet 4.5"},
                {"slug":"internal-only","hidden":true}],
             "default":"claude-sonnet-4-5"},
            {"backend":"codex","models":[{"slug":"gpt-5"}],
             "effort_levels":["low","medium","high"],"default_effort":"medium",
             "free_form":true,"catalog_error":"listing failed; using defaults"}],
            "generation":42}"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }
        val api = apiOver(engine)
        val snap = api.nodeModels("n1")
        assertEquals(42L, snap.generation)
        assertEquals(2, snap.backends.size)
        val claude = snap.backends.first { it.backend == "claude" }
        assertEquals(2, claude.models.size)                 // hidden model still decoded
        assertTrue(claude.models.any { it.hidden })
        assertEquals("claude-sonnet-4-5", claude.default)
        val codex = snap.backends.first { it.backend == "codex" }
        assertEquals(listOf("low", "medium", "high"), codex.effort_levels)
        assertEquals("medium", codex.default_effort)
        assertTrue(codex.free_form)
        assertEquals("listing failed; using defaults", codex.catalog_error)
        api.close()
    }

    // ---- 501 capability degradation ----

    @Test
    fun modelsUnsupportedIsTypedCodeAndNotRetried() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":"node does not support model catalog snapshot","code":"model_catalog_unsupported"}""",
                HttpStatusCode.NotImplemented, jsonHeaders,
            )
        }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.nodeModels("old") }
        assertEquals(501, e.status)
        assertEquals("model_catalog_unsupported", e.code)
        // 501 is a deterministic capability gap, not a transient fault: fail fast.
        assertEquals(1, engine.requestHistory.size)
        api.close()
    }

    @Test
    fun sendModelSwitchUnsupportedIsTypedCodeAndNotRetried() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":"node does not support per-turn model/effort override","code":"model_switch_unsupported"}""",
                HttpStatusCode.NotImplemented, jsonHeaders,
            )
        }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.send("old", "s1", "hi", model = "gpt-5") }
        assertEquals(501, e.status)
        assertEquals("model_switch_unsupported", e.code)
        // The friendly message prefers the envelope's error field over raw JSON.
        assertTrue((e.message ?: "").contains("per-turn model/effort override"))
        assertEquals(1, engine.requestHistory.size) // mutations are never replayed
        api.close()
    }

    @Test
    fun errorCodeParsingIsBestEffort() {
        assertEquals("model_switch_unsupported", errorCode("""{"error":"x","code":"model_switch_unsupported"}"""))
        assertNull(errorCode("plain text error"), "a non-JSON body carries no code")
        assertNull(errorCode("""{"error":"no code here"}"""))
        assertNull(errorCode(null))
        assertNull(errorCode("""{"error":"truncated","code":"mod"""), "a body cut at the ceiling just fails to decode")
        // A plain-text error still renders as itself (no envelope to prefer).
        assertEquals("cwd is required", httpErrorMessage(400, "create session", "cwd is required"))
    }

    @Test
    fun fiveOhOneIsNotTransient() {
        assertFalse(isTransientReadError(ApiHttpException(501, "models")))
        assertTrue(isTransientReadError(ApiHttpException(503, "models")))
    }
}
