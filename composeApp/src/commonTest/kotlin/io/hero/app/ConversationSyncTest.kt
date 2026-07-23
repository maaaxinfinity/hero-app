package io.hero.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// truthUp is the reconnect reconciliation contract: after every SSE
// (re)subscribe the newest transcript page is re-read and merged by stable turn
// identity, so turns committed in the snapshot→subscribe gap or during an
// outage appear EXACTLY ONCE — no gap, no duplicates. applyEarlierPage is the
// owner CAS that keeps a late "Load earlier" response from prepending into a
// conversation it wasn't issued for. Both are pure, like reduce.
class ConversationSyncTest {
    private fun user(c: String, ts: String) = Turn(role = "user", content = c, ts = ts)
    private fun asst(ts: String, uuid: String?, vararg texts: String) =
        Turn(role = "assistant", parts = texts.map { TurnPart(type = "text", content = it) }, ts = ts, uuid = uuid)

    // The core gap: frames committed between the HTTP snapshot and the SSE
    // subscribe are in neither. The truth-up page carries them; the overlap
    // (t1, t2) must not double.
    @Test
    fun truthUpFillsTheGapWithoutDuplicates() {
        val t1 = user("hi", "t1")
        val t2 = asst("t2", "u2", "a")
        val s = ConvoState(turns = listOf(t1, t2), cursor = Cursor(total = 2, start = 0, pageSize = 40))
        val t3 = user("more", "t3")
        val t4 = asst("t4", "u4", "b")

        val merged = s.truthUp(listOf(t1, t2, t3, t4), total = 4, start = 0)
        assertEquals(listOf(t1, t2, t3, t4), merged.turns)
        // Mechanical adaptation: displayKeys(turns) was replaced by ConvoState's
        // incrementally maintained UI id index; the uniqueness assertion is the same.
        assertEquals(4, merged.uiKeys.toSet().size)
        assertEquals(0, merged.cursor?.start)
        assertFalse(merged.openAssistant)
    }

    // A live assistant turn has no uuid yet; its committed version in the page
    // does (identity changed). It must be REPLACED, not kept alongside.
    @Test
    fun truthUpReplacesLiveTurnWithItsCommittedVersion() {
        val live = asst("t2", null, "partial")
        val s = ConvoState(
            turns = listOf(user("hi", "t1"), live),
            cursor = Cursor(2, 0, 40), openAssistant = true,
        )
        val committed = asst("t2", "u2", "partial", "rest")

        val merged = s.truthUp(listOf(user("hi", "t1"), committed), total = 2, start = 0)
        assertEquals(2, merged.turns.size)
        assertEquals(committed, merged.turns[1])
        assertFalse(merged.openAssistant)
    }

    // Prepended older history is NOT in the newest page; it must survive, and
    // the cursor must keep reaching back to it (no skip, no re-fetch).
    @Test
    fun truthUpKeepsOlderHistoryAndCursorStart() {
        val h1 = user("h1", "t1")
        val h2 = user("h2", "t2")
        val h3 = user("h3", "t3")
        // Loaded window [1, 4) of an old total 4; server grew to 6 and the
        // newest page now covers [2, 6).
        val s = ConvoState(turns = listOf(h1, h2, h3), cursor = Cursor(total = 4, start = 1, pageSize = 3))
        val n4 = user("n4", "t4")
        val n5 = asst("t5", "u5", "x")

        val merged = s.truthUp(listOf(h2, h3, n4, n5), total = 6, start = 2)
        assertEquals(listOf(h1, h2, h3, n4, n5), merged.turns)
        assertEquals(1, merged.cursor?.start) // still reaches back to the kept history
        assertEquals(6, merged.cursor?.total)
        assertTrue(merged.cursor!!.hasMore)
    }

    // An outage longer than a page: the new window starts beyond everything we
    // know. Bridging would render a silent hole while the cursor claims
    // contiguity — the stale window is dropped instead.
    @Test
    fun truthUpDisjointWindowResetsToThePage() {
        val s = ConvoState(
            turns = listOf(user("old", "t1"), Turn(role = "error", content = "boom")),
            cursor = Cursor(total = 3, start = 0, pageSize = 40),
        )
        val page = listOf(user("new", "t9"), asst("t10", "u10", "y"))

        val merged = s.truthUp(page, total = 9, start = 5)
        assertEquals(page, merged.turns)
        assertEquals(5, merged.cursor?.start)
        assertEquals(9, merged.cursor?.total)
    }

    // Client-only rows the server can't know: error lines and an optimistic
    // user echo that hasn't committed yet. They survive at the tail — but an
    // echo whose commit IS in the page folds into it.
    @Test
    fun truthUpKeepsClientOnlyTailAndDropsCommittedEcho() {
        val t1 = user("hi", "t1")
        val err = Turn(role = "error", content = "send failed: x")
        val pendingEcho = Turn(role = "user", content = "not yet committed")
        val committedEcho = Turn(role = "user", content = "already committed")
        val s = ConvoState(
            turns = listOf(t1, err, committedEcho, pendingEcho),
            cursor = Cursor(1, 0, 40),
        )
        val page = listOf(t1, user("already committed", "t2"))

        val merged = s.truthUp(page, total = 2, start = 0)
        assertEquals(listOf(t1, page[1], err, pendingEcho), merged.turns)
        assertEquals(1, merged.turns.count { it.content == "already committed" })
    }

    // An empty newest page (empty transcript upstream) must not wipe
    // client-only rows; only the cursor advances.
    @Test
    fun truthUpEmptyPageKeepsLocalRows() {
        val err = Turn(role = "error", content = "boom")
        val s = ConvoState(turns = listOf(err), cursor = null)
        val merged = s.truthUp(emptyList(), total = 0, start = 0)
        assertEquals(listOf(err), merged.turns)
        assertEquals(0, merged.cursor?.total)
    }

    // Fresh state (seed failed → no cursor): truth-up seeds the window.
    @Test
    fun truthUpSeedsAnEmptyState() {
        val page = listOf(user("hi", "t1"), asst("t2", "u2", "a"))
        val merged = ConvoState().truthUp(page, total = 40, start = 12)
        assertEquals(page, merged.turns)
        assertEquals(Cursor(40, 12, 2), merged.cursor)
    }

    // ---- reduce re-delivery guards (frames replayed across a truth-up) ----

    // A part the merged page already committed arrives again over SSE: no
    // duplicate part, no duplicate turn — the state is untouched.
    @Test
    fun reduceSkipsRedeliveredCommittedPart() {
        val seeded = asst("t2", null, "p1", "p2")
        val s = ConvoState(turns = listOf(seeded), openAssistant = false)
        val out = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "p1"), ts = "t2", model = null))
        assertSame(s, out)
    }

    // A genuinely new part for a turn the page holds RESUMES that turn in
    // place instead of opening a duplicate "split" turn for the same ts.
    @Test
    fun reduceResumesSeededTurnInsteadOfSplitting() {
        val seeded = asst("t2", null, "p1")
        val s = ConvoState(turns = listOf(seeded), openAssistant = false)
        val out = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "p2"), ts = "t2", model = null))
        assertEquals(1, out.turns.size)
        assertEquals(listOf("p1", "p2"), out.turns[0].parts.map { it.content })
        assertTrue(out.openAssistant)
    }

    // A part with a NEW ts is a new turn, exactly as before.
    @Test
    fun reduceStillOpensANewTurnForANewTs() {
        val s = ConvoState(turns = listOf(asst("t2", "u2", "p1")), openAssistant = false)
        val out = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "q1"), ts = "t3", model = null))
        assertEquals(2, out.turns.size)
        assertTrue(out.openAssistant)
    }

    // turn.user re-delivered after a truth-up (same ts + content, but no longer
    // the last turn) folds away; the same content at a NEW ts is a genuinely
    // repeated message and appends.
    @Test
    fun reduceSkipsRedeliveredUserTurnButKeepsRepeatedMessages() {
        val s = ConvoState(turns = listOf(user("hi", "t1"), asst("t2", "u2", "a")))
        val redelivered = s.reduce(LiveFrame.UserTurn("hi", "t1"))
        assertEquals(2, redelivered.turns.size)

        val repeated = s.reduce(LiveFrame.UserTurn("hi", "t9"))
        assertEquals(3, repeated.turns.size)
    }

    // ---- late-response owner CAS ----

    private val page = TranscriptPage(turns = listOf(user("old", "t0")), total = 10, start = 4, hasMore = true)

    @Test
    fun applyEarlierPageAppliesForTheMatchingOwner() {
        val state = ConvoState(turns = listOf(user("new", "t5")), cursor = Cursor(10, 5, 40))
        val owner = ConvoOwner("n1", "s1")
        val merged = applyEarlierPage(state, owner, ConvoOwner("n1", "s1"), page)
        assertEquals(listOf("old", "new"), merged!!.turns.map { it.content })
        assertEquals(4, merged.cursor?.start)
    }

    // The response returns after switching to another session: discarded.
    @Test
    fun applyEarlierPageDiscardsAfterSessionSwitch() {
        val state = ConvoState(turns = listOf(user("b's turn", "t5")))
        assertNull(applyEarlierPage(state, ConvoOwner("n1", "s1"), ConvoOwner("n1", "s2"), page))
    }

    // Same session id on a DIFFERENT node is a different conversation — the
    // node is part of the owner identity (ids collide across mirrored nodes).
    @Test
    fun applyEarlierPageDiscardsAcrossNodesWithSameSessionId() {
        val state = ConvoState(turns = listOf(user("b's turn", "t5")))
        assertNull(applyEarlierPage(state, ConvoOwner("n1", "s1"), ConvoOwner("n2", "s1"), page))
        assertNull(applyEarlierPage(state, ConvoOwner("n1", "s1"), null, page)) // conversation closed
    }
}
