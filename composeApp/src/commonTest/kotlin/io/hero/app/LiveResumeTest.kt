package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

// The /parts resume-cursor adoption (control-plane-api.md § live-stream resume):
// the subscription is session-scoped with replay=1 and include_deltas=false, SSE
// `id:` lines are tracked and committed only AFTER the collector applied their
// frame, the last id (or a transcript watermark) travels back as Last-Event-ID,
// and the handshake verdict decides reconciliation — a RESUMED stream skips the
// truth-up re-read entirely, while gap / stripped headers / an epoch change / an
// old server keep the pre-cursor behavior: truth-up on every (re)subscribe.
class LiveResumeTest {
    private fun apiOver(engine: MockEngine): Api =
        Api("http://cp.test") { block -> HttpClient(engine) { block() } }

    private val sseContentType = HttpHeaders.ContentType to listOf("text/event-stream")

    /** One SSE body: the given lines, newline-joined (pass "" for blank separators). */
    private fun sse(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    private fun userEvent(text: String) =
        """{"session_id":"s1","type":"turn.user","raw":{"content":"$text","ts":"t-$text"}}"""

    private fun deltaEvent() = """{"session_id":"s1","type":"part.delta","raw":{"delta":"d"}}"""

    // ---- pure: id parsing and the handshake verdict ----

    @Test
    fun eventIdEpochParsesTheEpochSeqShape() {
        assertEquals("7", eventIdEpoch("7-42"))
        assertEquals("9", eventIdEpoch("9-0")) // "<epoch>-0" is legal: a silent session
        assertEquals("boot-3", eventIdEpoch("boot-3-9")) // only the LAST '-' splits off the seq
        assertNull(eventIdEpoch(""))
        assertNull(eventIdEpoch("nodash"))
        assertNull(eventIdEpoch("-5")) // empty epoch
        assertNull(eventIdEpoch("7-")) // empty seq
        assertNull(eventIdEpoch("7-x1")) // non-numeric seq
    }

    @Test
    fun resumeNeedsASentIdAnExplicitVerdictAndEpochContinuity() {
        assertTrue(resumedHandshake("7-5", "7", "resume"))
        assertTrue(resumedHandshake("7-5", null, "resume")) // epoch stripped: the explicit verdict stands
        assertFalse(resumedHandshake(null, "7", "resume")) // never sent an id
        assertFalse(resumedHandshake("7-5", "7", "gap"))
        assertFalse(resumedHandshake("7-5", "7", null)) // sent an id, verdict stripped → assume gap
        assertFalse(resumedHandshake("7-5", "8", "resume")) // epoch changed → the id is void
        assertFalse(resumedHandshake("garbage", "7", "resume")) // unparseable id can't prove continuity
        assertFalse(resumedHandshake(null, null, null)) // old server: no cursor machinery at all
    }

    // ---- wire: the subscription request ----

    @Test
    fun subscriptionIsSessionScopedReplayingAndDeltaFree() = runTest {
        val engine = MockEngine { respond(sse(""), HttpStatusCode.OK, headersOf(sseContentType)) }
        val api = apiOver(engine)
        api.liveFrames("n1", "s 1/x").toList()
        val req = engine.requestHistory.single()
        assertTrue(req.url.encodedPath.endsWith("/api/nodes/n1/parts"))
        assertEquals("s 1/x", req.url.parameters["session"]) // query-encoded and decoded intact
        assertEquals("1", req.url.parameters["replay"])
        assertEquals("false", req.url.parameters["include_deltas"])
        assertNull(req.headers[LAST_EVENT_ID_HEADER]) // no cursor yet → no header
        api.close()
    }

    // ---- wire: id tracking, Last-Event-ID, heartbeats ----

    @Test
    fun frameIdsCommitOnlyAfterTheCollectorAppliedTheFrame() = runTest {
        val engine = MockEngine {
            respond(
                sse("id: 7-1", "data: ${userEvent("a")}", "", "id: 7-2", "data: ${userEvent("b")}", ""),
                HttpStatusCode.OK,
                headersOf(sseContentType, PARTS_EPOCH_HEADER to listOf("7")),
            )
        }
        val api = apiOver(engine)
        val ids = mutableListOf<String>()
        val committedAtCollect = mutableListOf<Int>()
        api.liveFrames("n1", "s1", lastEventId = "7-0", onEventId = { ids += it })
            .collect { committedAtCollect += ids.size }
        // Dispatch-time semantics: while frame k is being applied, its id is NOT
        // yet committed — a crash mid-apply resumes from the previous id and the
        // frame re-delivers instead of vanishing.
        assertEquals(listOf(0, 1), committedAtCollect)
        assertEquals(listOf("7-1", "7-2"), ids)
        assertEquals("7-0", engine.requestHistory.single().headers[LAST_EVENT_ID_HEADER])
        api.close()
    }

    @Test
    fun heartbeatsUnsequencedFramesAndBadIdsNeverBreakTheStream() = runTest {
        val engine = MockEngine {
            respond(
                sse(
                    ": hb", "",
                    "id: 7-1", "data: ${userEvent("a")}", "",
                    "data: ${deltaEvent()}", "", // an OLD server still streams deltas, without ids
                    ": hb", "id:", "id: ", "", // empty id values are ignored
                    "id: garbage", "data: ${userEvent("b")}", "", // a malformed id tracks, never throws
                ),
                HttpStatusCode.OK,
                headersOf(sseContentType),
            )
        }
        val api = apiOver(engine)
        val ids = mutableListOf<String>()
        val frames = api.liveFrames("n1", "s1", onEventId = { ids += it }).toList()
        assertEquals(3, frames.size)
        assertTrue(frames[1] is LiveFrame.Delta)
        assertEquals(listOf("7-1", "garbage"), ids) // the delta committed nothing; garbage just gaps later
        api.close()
    }

    // An old-server delta reaching reduce stays a no-op sink — the branch must
    // survive include_deltas=false, which only NEW servers honor.
    @Test
    fun reduceStillAbsorbsDeltasFromOldServers() {
        val s = ConvoState(turns = listOf(Turn(role = "user", content = "hi", ts = "t1")))
        assertSame(s, s.reduce(LiveFrame.Delta("x")))
    }

    // ---- wire: the handshake verdict reaching the caller's truth-up decision ----

    @Test
    fun resumeSkipsTruthUpEverythingElseTriggersIt() = runTest {
        var epoch: String? = "8"
        var replay: String? = "resume"
        val engine = MockEngine {
            val h = buildList {
                add(sseContentType)
                epoch?.let { add(PARTS_EPOCH_HEADER to listOf(it)) }
                replay?.let { add(PARTS_REPLAY_HEADER to listOf(it)) }
            }
            respond(sse("id: 8-1", "data: ${userEvent("a")}", ""), HttpStatusCode.OK, headersOf(*h.toTypedArray()))
        }
        val api = apiOver(engine)
        var truthUps = 0
        suspend fun connect(last: String?) {
            api.liveFrames("n1", "s1", lastEventId = last, onSubscribed = { resumed -> if (!resumed) truthUps++ })
                .toList()
        }
        connect("8-0")
        assertEquals(0, truthUps) // resume → the re-read is skipped
        replay = "gap"
        connect("8-0")
        assertEquals(1, truthUps) // explicit gap → truth-up
        replay = null
        connect("8-0")
        assertEquals(2, truthUps) // sent an id, verdict stripped → assume gap
        replay = "resume"; epoch = "9"
        connect("8-0")
        assertEquals(3, truthUps) // epoch changed under a stale id → not a resume
        epoch = null; replay = null
        connect(null)
        assertEquals(4, truthUps) // no id sent (first connect / old server) → truth-up
        api.close()
    }

    @Test
    fun oldServerStreamsExactlyAsBeforeTheCursor() = runTest {
        val engine = MockEngine {
            respond(
                sse("data: ${userEvent("a")}", "", ": hb", "data: ${userEvent("b")}", ""),
                HttpStatusCode.OK,
                headersOf(sseContentType), // no epoch, no verdict, no ids
            )
        }
        val api = apiOver(engine)
        var resumedSeen: Boolean? = null
        val ids = mutableListOf<String>()
        val frames = api.liveFrames("n1", "s1", onEventId = { ids += it }, onSubscribed = { resumedSeen = it }).toList()
        assertEquals(2, frames.size)
        assertEquals(false, resumedSeen) // → the caller truth-ups on every (re)subscribe: 0.5.21 behavior
        assertTrue(ids.isEmpty()) // nothing tracked → the next reconnect sends no Last-Event-ID
        assertNull(engine.requestHistory.single().headers[LAST_EVENT_ID_HEADER])
        api.close()
    }

    // ---- wire: the transcript snapshot watermark ----

    @Test
    fun transcriptCarriesTheStreamWatermarkWhenPresent() = runTest {
        var seq: String? = "7-42"
        val engine = MockEngine {
            val h = buildList {
                add(HttpHeaders.ContentType to listOf("application/json"))
                add("X-Transcript-Total" to listOf("3"))
                add("X-Transcript-Start" to listOf("1"))
                add("X-Transcript-Has-More" to listOf("true"))
                seq?.let { add(TRANSCRIPT_SEQ_HEADER to listOf(it)) }
            }
            respond("[]", HttpStatusCode.OK, headersOf(*h.toTypedArray()))
        }
        val api = apiOver(engine)
        val page = api.transcript("n1", "s1", 40)
        assertEquals("7-42", page.streamSeq)
        assertEquals(3, page.total)
        seq = null // an old server (or no live feed): the field is simply absent
        assertNull(api.transcript("n1", "s1", 40).streamSeq)
        api.close()
    }

    // ---- LiveResync: the reconnect-time reconciliation owner ----

    private fun pageWith(seq: String?) = TranscriptPage(emptyList(), total = 0, start = 0, hasMore = false, streamSeq = seq)

    @Test
    fun resyncResumeSkipsTheReReadAndKeepsTheCursor() = runTest {
        var fetches = 0
        val sync = LiveResync(fetchPage = { fetches++; pageWith("7-9") }, apply = {})
        sync.seed("7-5")
        sync.onSubscribed(resumed = true)
        assertEquals(0, fetches) // the whole point of the cursor
        assertEquals("7-5", sync.lastEventId)
    }

    @Test
    fun resyncGapReReadsAndAdoptsTheFreshWatermark() = runTest {
        var fetches = 0
        var applies = 0
        val sync = LiveResync(fetchPage = { fetches++; pageWith("8-3") }, apply = { applies++ })
        sync.seed("7-5")
        sync.onSubscribed(resumed = false) // e.g. the epoch changed → gap
        assertEquals(1, fetches)
        assertEquals(1, applies)
        assertEquals("8-3", sync.lastEventId) // the dead 7-5 was reset, then re-seeded off the snapshot
    }

    @Test
    fun resyncGapWithoutAWatermarkLeavesNoCursor() = runTest {
        val sync = LiveResync(fetchPage = { pageWith(null) }, apply = {})
        sync.seed("7-5")
        sync.onSubscribed(resumed = false)
        assertNull(sync.lastEventId) // the spent id must NOT be re-sent next time
    }

    @Test
    fun resyncOldServerTruthUpsEveryReconnect() = runTest {
        var fetches = 0
        val sync = LiveResync(fetchPage = { fetches++; pageWith(null) }, apply = {})
        sync.seed(null) // no watermark on the seed page either
        assertNull(sync.lastEventId)
        sync.onSubscribed(resumed = false)
        sync.onSubscribed(resumed = false)
        assertEquals(2, fetches) // the pre-cursor status quo, verbatim
        assertNull(sync.lastEventId)
    }

    @Test
    fun resyncTracksAppliedIdsLatestWins() {
        val sync = LiveResync(fetchPage = { pageWith(null) }, apply = {})
        sync.seed("7-0")
        sync.record("7-1")
        sync.record("7-2")
        assertEquals("7-2", sync.lastEventId)
    }
}
