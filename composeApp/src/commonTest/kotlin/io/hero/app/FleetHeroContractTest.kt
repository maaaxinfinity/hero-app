package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The /api/fleet/hero self-update contract across BOTH server generations,
// against a controlled MockEngine: the split-policy status/PUT/receipt shapes
// (independent control/node bits, generation CAS, operation-id receipts, no
// secret anywhere), the legacy single-bit degradation the nullable fields
// discriminate, the exact wire bodies each PUT sends (a null bit must be
// OMITTED — "leave unchanged" — and the legacy body must never grow split
// fields), and the 409 stale-generation refusal as a typed, discriminable,
// never-replayed failure.
class FleetHeroContractTest {
    private fun apiOver(engine: MockEngine): Api =
        Api("http://cp.test") { block -> HttpClient(engine) { block() } }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private suspend fun MockEngine.singleRequestBody(): JsonObject =
        Json.parseToJsonElement(requestHistory.single().body.toByteArray().decodeToString()).jsonObject

    // A split-policy server's status decodes with both per-scope bits and the
    // generation identity; the legacy single bit stays null (the discriminator).
    @Test
    fun splitPolicyStatusDecodes() = runTest {
        val engine = MockEngine {
            respond(
                """{"version":"1.2.0","generation":7,"published_at":1753000000,"running":"1.1.0",
                   "running_generation":6,"platforms":{"linux-amd64":{"sha256":"ab12cd","size":1048576}},
                   "control_auto_update":true,"node_auto_update":false,"defined":true}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val api = apiOver(engine)
        val f = api.fleetHero()
        assertTrue(f.hasSplitPolicy)
        assertEquals(true, f.control_auto_update)
        assertEquals(false, f.node_auto_update)
        assertEquals(7L, f.generation)
        assertEquals(6L, f.running_generation)
        assertEquals(1753000000L, f.published_at)
        assertNull(f.auto_update, "a split-policy server sends no legacy bit")
        assertFalse(f.upToDate, "running generation 6 has not reached published 7")
        assertEquals(1048576L, f.platforms["linux-amd64"]?.size)
        api.close()
    }

    // A legacy server's status (single auto_update + the token/pull_url it
    // still ships) decodes into the degraded mode: split fields null, the
    // credential nowhere — HeroFleet has no field it could land in.
    @Test
    fun legacyStatusDecodesIntoDegradedMode() = runTest {
        val engine = MockEngine {
            respond(
                """{"version":"1.1.0","running":"1.1.0","auto_update":true,"defined":true,
                   "platforms":{},"pull_url":"https://cp/fleet/hero/manifest.json","token":"SECRET"}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val api = apiOver(engine)
        val f = api.fleetHero()
        assertFalse(f.hasSplitPolicy)
        assertEquals(true, f.auto_update)
        assertNull(f.control_auto_update)
        assertNull(f.node_auto_update)
        assertNull(f.generation)
        assertTrue(f.upToDate, "no generation published → the version-string fallback")
        api.close()
    }

    // "Up to date" is decided on the release GENERATION when the server
    // publishes one — a same-version rebuild is a distinct generation — and
    // only falls back to the version string for a legacy server.
    @Test
    fun upToDateIsGenerationDecided() {
        assertFalse(HeroFleet(version = "1.2.0", running = "1.2.0", defined = true, generation = 7, running_generation = 6).upToDate)
        assertTrue(HeroFleet(version = "1.2.0", running = "1.2.0-dev", defined = true, generation = 7, running_generation = 7).upToDate)
        assertFalse(HeroFleet(version = "1.2.0", running = "1.2.0", defined = true, generation = 0, running_generation = 0).upToDate,
            "generation 0 is the unpublished sentinel, never up to date")
        assertTrue(HeroFleet(version = "1.1.0", running = "1.1.0", defined = true).upToDate)
        assertFalse(HeroFleet(version = "1.2.0", running = "1.1.0", defined = true).upToDate)
        assertFalse(HeroFleet(version = "1.2.0", running = "1.2.0", defined = false).upToDate)
    }

    // The split-policy PUT sends ONLY the changed bit plus the CAS base: the
    // untouched scope's bit must be absent from the wire (null = "leave
    // unchanged"), not sent as false — sending it would flip that policy.
    @Test
    fun policyPutSendsOnlyTheChangedBitWithGenerationCas() = runTest {
        val engine = MockEngine {
            respond("""{"control_auto_update":true,"node_auto_update":false,"version":"1.2.0","generation":7}""",
                HttpStatusCode.OK, jsonHeaders)
        }
        val api = apiOver(engine)
        val r = api.setFleetHeroPolicy(control = true, expectedGeneration = 7)
        val body = engine.singleRequestBody()
        assertEquals(setOf("control_auto_update", "expected_generation"), body.keys)
        assertEquals(true, body["control_auto_update"]!!.jsonPrimitive.boolean)
        assertEquals(7L, body["expected_generation"]!!.jsonPrimitive.long)
        assertTrue(!engine.requestHistory.single().headers[HERO_OP_HEADER].isNullOrEmpty())
        assertEquals(true, r.control_auto_update)
        assertEquals(false, r.node_auto_update)
        assertEquals(7L, r.generation)
        api.close()
    }

    @Test
    fun nodePolicyPutLeavesTheControlBitUnsent() = runTest {
        val engine = MockEngine {
            respond("""{"control_auto_update":false,"node_auto_update":true,"version":"1.2.0","generation":9}""",
                HttpStatusCode.OK, jsonHeaders)
        }
        val api = apiOver(engine)
        api.setFleetHeroPolicy(node = true, expectedGeneration = 9)
        assertEquals(setOf("node_auto_update", "expected_generation"), engine.singleRequestBody().keys)
        api.close()
    }

    // The degraded path against an OLD server: the legacy PUT carries exactly
    // the single bit (no split fields, no CAS the server wouldn't understand),
    // and its answer decodes with the split fields null.
    @Test
    fun legacyAutoPutSpeaksTheOldContractExactly() = runTest {
        val engine = MockEngine {
            respond("""{"auto_update":false,"version":"1.1.0"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val api = apiOver(engine)
        val r = api.setFleetHeroAutoLegacy(false)
        val body = engine.singleRequestBody()
        assertEquals(setOf("auto_update"), body.keys)
        assertEquals(false, body["auto_update"]!!.jsonPrimitive.boolean)
        assertTrue(!engine.requestHistory.single().headers[HERO_OP_HEADER].isNullOrEmpty())
        assertEquals(false, r.auto_update)
        assertNull(r.control_auto_update)
        assertNull(r.generation)
        api.close()
    }

    // A stale expected_generation is refused with 409: a typed, discriminable
    // failure (isStaleConflict) issued EXACTLY once — the CAS loser reloads and
    // re-decides, it never blind-resubmits.
    @Test
    fun staleGenerationIsADiscriminable409AndNeverReplayed() = runTest {
        val engine = MockEngine { respond("stale generation; reload and retry", HttpStatusCode.Conflict) }
        val api = apiOver(engine)
        val e = assertFailsWith<ApiHttpException> { api.setFleetHeroPolicy(node = true, expectedGeneration = 3) }
        assertEquals(409, e.status)
        assertTrue(isStaleConflict(e))
        assertEquals("stale generation; reload and retry", e.message)
        assertEquals(1, engine.requestHistory.size)
        api.close()
    }

    @Test
    fun staleConflictClassifierIsExact() {
        assertTrue(isStaleConflict(ApiHttpException(409, "set update policy")))
        assertFalse(isStaleConflict(ApiHttpException(400, "set update policy")))
        assertFalse(isStaleConflict(ApiHttpException(500, "set update policy")))
        assertFalse(isStaleConflict(RuntimeException("conflict")))
        assertFalse(isStaleConflict(IllegalStateException("409")))
    }

    // The self-update receipt decodes from both contracts: a split-policy
    // server returns {message, operation_id, generation} (the identity the
    // in-progress state is presented under); a legacy server's bare message
    // leaves both null and the client op id stands in.
    @Test
    fun controlUpdateReceiptDecodesBothContracts() = runTest {
        val newEngine = MockEngine {
            respond("""{"message":"updating to 1.2.0","operation_id":"op-abc","generation":7}""",
                HttpStatusCode.OK, jsonHeaders)
        }
        val newApi = apiOver(newEngine)
        val r = newApi.controlSelfUpdate(operationId = "client-op")
        assertEquals("updating to 1.2.0", r.message)
        assertEquals("op-abc", r.operation_id)
        assertEquals(7L, r.generation)
        assertEquals("client-op", newEngine.requestHistory.single().headers[HERO_OP_HEADER])
        newApi.close()

        val oldEngine = MockEngine { respond("""{"message":"started"}""", HttpStatusCode.OK, jsonHeaders) }
        val oldApi = apiOver(oldEngine)
        val legacy = oldApi.controlSelfUpdate()
        assertEquals("started", legacy.message)
        assertNull(legacy.operation_id)
        assertNull(legacy.generation)
        oldApi.close()
    }

    // Convergence of a launched update is generation-first: the receipt's
    // target generation decides when the server publishes one; a legacy
    // receipt falls back to the running version string (and an empty running
    // — the CP mid-restart — never converges).
    @Test
    fun controlUpdateConvergenceIsGenerationFirst() {
        val byGen = PendingControlUpdate("op1", 7L, "1.2.0")
        assertTrue(controlUpdateConverged(byGen, HeroFleet(running_generation = 7)))
        assertFalse(controlUpdateConverged(byGen, HeroFleet(running = "1.2.0", running_generation = 6)))
        assertFalse(controlUpdateConverged(byGen, HeroFleet(running = "1.2.0")))
        val byVersion = PendingControlUpdate("op1", null, "1.2.0")
        assertTrue(controlUpdateConverged(byVersion, HeroFleet(running = "1.2.0")))
        assertFalse(controlUpdateConverged(byVersion, HeroFleet(running = "1.1.0")))
        assertFalse(controlUpdateConverged(byVersion, HeroFleet()))
    }
}
