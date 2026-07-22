package io.hero.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsTest {
    // Back order: pop drill entries one at a time, then close the session.
    @Test
    fun popUnwindsDrillThenSession() {
        var s = SessionSel(node = "n1", session = "root", drill = listOf("c1", "c2"))
        assertEquals("c2", s.active)
        assertTrue(s.readonly)

        s = s.pop()
        assertEquals(SessionSel(node = "n1", session = "root", drill = listOf("c1")), s)
        assertEquals("c1", s.active)

        s = s.pop()
        assertEquals(SessionSel(node = "n1", session = "root"), s)
        assertEquals("root", s.active)
        assertFalse(s.readonly)

        s = s.pop()
        assertEquals(SessionSel(node = "n1"), s)
        assertNull(s.active)

        // Popping with nothing open keeps the node selection and stays closed.
        assertEquals(SessionSel(node = "n1"), s.pop())
    }

    // "Load earlier": the fetched page lands before the window and the cursor
    // walks back until start reaches 0.
    @Test
    fun prependWalksCursorBack() {
        val newest = listOf(Turn(role = "user", content = "new", ts = "t2"))
        var s = ConvoState(turns = newest, cursor = Cursor(total = 100, start = 60, pageSize = 40))
        assertTrue(s.cursor!!.hasMore)
        assertEquals(20, s.cursor!!.earlierOffset())

        val earlier = listOf(
            Turn(role = "user", content = "old", ts = "t0"),
            Turn(role = "assistant", parts = listOf(TurnPart(type = "text", content = "a")), ts = "t1", uuid = "u1"),
        )
        s = s.prepend(earlier, total = 100, start = 20)
        assertEquals(listOf("old", null, "new"), s.turns.map { it.content })
        assertEquals(20, s.cursor!!.start)
        assertEquals(40, s.cursor!!.pageSize)
        assertTrue(s.cursor!!.hasMore)

        s = s.prepend(emptyList(), total = 100, start = 0)
        assertTrue(!s.cursor!!.hasMore)
    }

    @Test
    fun cwdTailKeepsLastTwoSegments() {
        assertEquals("workspace/hero-app", cwdTail("/root/workspace/hero-app"))
        assertEquals("work", cwdTail("/work/"))
        assertEquals("", cwdTail(""))
    }

    // Subagent collection: order of first appearance, deduped by child id,
    // labelled by toolTarget with toolName as fallback.
    @Test
    fun collectChildSessionsDedupesAndLabels() {
        val turns = listOf(
            Turn(role = "assistant", parts = listOf(
                TurnPart(type = "tool", toolName = "Agent", toolTarget = "explore layout", childSessionId = "c1"),
                TurnPart(type = "text", content = "no child"),
            ), uuid = "u1"),
            Turn(role = "assistant", parts = listOf(
                TurnPart(type = "tool", toolName = "Agent", toolTarget = "", childSessionId = "c2"),
                TurnPart(type = "tool", toolName = "Agent", toolTarget = "explore again", childSessionId = "c1"),
            ), uuid = "u2"),
        )
        assertEquals(listOf("explore layout" to "c1", "Agent" to "c2"), collectChildSessions(turns))
        assertEquals(emptyList(), collectChildSessions(emptyList()))
    }

    // Audit filtering matches any displayed field, case-insensitively; a blank
    // query is a no-op.
    @Test
    fun filterAuditMatchesAllFields() {
        val recs = listOf(
            AuditRecord(ts = "t1", action = "login", user = "max", node = "", detail = "password"),
            AuditRecord(ts = "t2", action = "session.start", user = "kai", node = "dev-1", detail = "s1"),
        )
        assertEquals(recs, filterAudit(recs, ""))
        assertEquals(listOf(recs[0]), filterAudit(recs, "LOGIN"))
        assertEquals(listOf(recs[1]), filterAudit(recs, "dev-1"))
        assertEquals(listOf(recs[1]), filterAudit(recs, "kai"))
        assertEquals(emptyList(), filterAudit(recs, "nothing"))
    }

    // Two live assistant turns without uuids can produce identical turnKeys
    // (same ts + part count); displayKeys must disambiguate them and stay
    // stable when the list grows.
    @Test
    fun displayKeysDisambiguateCollisions() {
        val live1 = Turn(role = "assistant", parts = listOf(TurnPart(content = "a")), ts = "now")
        val live2 = Turn(role = "assistant", parts = listOf(TurnPart(content = "b")), ts = "now")
        val done = Turn(role = "assistant", parts = emptyList(), ts = "t1", uuid = "u1")

        val keys = displayKeys(listOf(done, live1, live2))
        assertEquals(3, keys.toSet().size)
        assertEquals("u1", keys[0])
        assertEquals(keys[1], displayKeys(listOf(done, live1, live2, live1.copy(ts = "later")))[1])
    }
}
