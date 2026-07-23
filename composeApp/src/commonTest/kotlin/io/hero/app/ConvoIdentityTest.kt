package io.hero.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// ConvoState's identity + incrementality contract: every turn gets ONE stable
// UI id when it first enters the state (server uuid when committed, else a
// local monotonic id) and keeps it through live streaming, exit, prepends and
// truth-ups — so a row is never deleted+reinserted and LazyColumn state
// survives. The reducer's hot path (a part for the open turn) must not touch
// the settled window: `history` keeps its reference, proving part appends are
// incremental, and the subagent index stays equal to a full scan.
class ConvoIdentityTest {
    private fun user(c: String, ts: String) = Turn(role = "user", content = c, ts = ts)
    private fun asst(ts: String, uuid: String?, vararg texts: String) =
        Turn(role = "assistant", parts = texts.map { TurnPart(type = "text", content = it) }, ts = ts, uuid = uuid)

    // A seed page can legitimately carry same-ts, uuid-less turns (second-
    // resolution timestamps collide). Their UI ids must differ and stay put as
    // the conversation moves on.
    @Test
    fun sameTimestampCollisionKeysStayStableAndUnique() {
        val s0 = ConvoState(
            turns = listOf(
                Turn(role = "assistant", parts = listOf(TurnPart(content = "a")), ts = "now"),
                Turn(role = "assistant", parts = listOf(TurnPart(content = "b")), ts = "now"),
            ),
            cursor = Cursor(2, 0, 40),
        )
        val keys = s0.uiKeys
        assertEquals(2, keys.size)
        assertEquals(2, keys.toSet().size)
        // Streaming and settling new work leaves both untouched.
        var s = s0.reduce(LiveFrame.Part(TurnPart(type = "text", content = "c"), "later", null))
        s = s.reduce(LiveFrame.Exit(reason = null, assistantUuid = "u1", assistantTs = null))
        assertEquals(keys, s.uiKeys.take(2))
        assertEquals(3, s.uiKeys.toSet().size)
    }

    // "Load earlier" landing a record with the same canonical identity (or even
    // a colliding uuid) must NOT shift a loaded row's key: the old occurrence
    // suffix turned `base` into `base#1` here, rebuilding the row.
    @Test
    fun prependSameBaseRecordKeepsExistingKeys() {
        var s = ConvoState(
            turns = listOf(user("same", "t5"), asst("t6", "u6", "a")),
            cursor = Cursor(10, 4, 2),
        )
        val before = s.uiKeys
        val page = listOf(user("same", "t5"), asst("t3", "u6", "b"))
        s = s.prepend(page, total = 10, start = 2)
        assertEquals(4, s.turns.size)
        assertEquals(before, s.uiKeys.takeLast(2)) // loaded rows keep EXACTLY their keys
        assertEquals(4, s.uiKeys.toSet().size)     // incoming dups still get unique ids
        assertEquals("u6", s.uiKeys[3])            // the row that owned the uuid still owns it
    }

    // live -> terminal is an in-place commit: the exit frame stamps the
    // canonical uuid but the UI id survives, including a later replay that
    // reopens the same turn.
    @Test
    fun liveTurnKeepsItsKeyThroughExit() {
        var s = ConvoState(turns = listOf(user("q", "t0")), cursor = Cursor(1, 0, 40))
        s = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "a"), "t1", "m"))
        val liveKey = checkNotNull(s.openKey)
        s = s.reduce(LiveFrame.Part(TurnPart(type = "tool", content = "o", toolName = "Bash"), "t1", "m"))
        assertEquals(liveKey, s.openKey) // streaming more parts never re-keys the row
        s = s.reduce(LiveFrame.Exit(reason = null, assistantUuid = "u9", assistantTs = null))
        assertNull(s.open)
        assertEquals("u9", s.history.last().uuid)   // canonical identity updated…
        assertEquals(liveKey, s.historyKeys.last()) // …but the row's UI id is unchanged
        // A replayed part for the same ts reopens the turn under the same key.
        s = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "late"), "t1", "m"))
        assertEquals(liveKey, s.openKey)
        s = s.reduce(LiveFrame.Exit(reason = null, assistantUuid = null, assistantTs = null))
        assertEquals(liveKey, s.historyKeys.last())
        assertEquals("u9", s.history.last().uuid)   // a uuid-less exit keeps the earlier uuid
    }

    // truthUp fills the snapshot->subscribe gap: replaced turns donate their UI
    // ids to their committed versions (exact identity, live assistant by ts,
    // optimistic echo by content); only genuinely new turns get new ids. A
    // second identical truth-up (reconnect) re-keys nothing.
    @Test
    fun truthUpKeepsOldKeysAndIndexAcrossTheGap() {
        val t1 = user("hi", "t1")
        var s = ConvoState(turns = listOf(t1), cursor = Cursor(1, 0, 40))
        s = s.optimisticUser("send me")
        s = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "partial"), "t3", "m"))
        val keys = s.uiKeys // [committed user, optimistic echo, live assistant]
        val page = listOf(t1, user("send me", "t2"), asst("t3", "u3", "partial", "rest"), asst("t4", "u4", "new"))
        s = s.truthUp(page, total = 4, start = 0)
        assertEquals(page, s.turns)
        assertEquals(keys[0], s.uiKeys[0]) // exact identity keeps its key
        assertEquals(keys[1], s.uiKeys[1]) // committed echo inherits the optimistic row's key
        assertEquals(keys[2], s.uiKeys[2]) // committed live turn inherits the live row's key
        assertEquals("u4", s.uiKeys[3])    // the genuinely new turn keys on its server uuid
        assertEquals(4, s.uiKeys.toSet().size)
        val again = s.truthUp(page, total = 4, start = 0)
        assertEquals(s.uiKeys, again.uiKeys)
        assertEquals(s.turns, again.turns)
    }

    // The incremental proof for the reducer: streaming hundreds of parts into
    // the open turn never copies the settled window — history, its key index
    // and the child index keep their REFERENCES, so Compose derivations keyed
    // on them stay valid across every part frame.
    @Test
    fun historyReferenceIsStableAcrossPartAppends() {
        var s = ConvoState(
            turns = listOf(user("q", "t0"), asst("t1", "u1", "done")),
            cursor = Cursor(2, 0, 40),
        )
        s = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "p0"), "t2", "m"))
        val history = s.history
        val historyKeys = s.historyKeys
        val children = s.children
        repeat(300) { i ->
            s = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "p${i + 1}"), "t2", "m"))
        }
        assertSame(history, s.history)
        assertSame(historyKeys, s.historyKeys)
        assertSame(children, s.children)
        assertEquals(301, s.open?.parts?.size)
        assertTrue(s.openAssistant)
        // Status/activity frames don't touch it either.
        s = s.reduce(LiveFrame.Status("thinking")).reduce(LiveFrame.TurnActive)
        assertSame(history, s.history)
        // Exit settles with one append and history finally advances.
        s = s.reduce(LiveFrame.Exit(reason = null, assistantUuid = "u2", assistantTs = null))
        assertEquals(3, s.history.size)
        assertNull(s.open)
    }

    private fun toolPart(step: Int, child: String?): TurnPart =
        if (child == null) TurnPart(type = "text", content = "p$step")
        else TurnPart(
            type = "tool", content = "out$step", toolName = "Agent",
            toolTarget = if (step % 3 == 0) "" else "task$step", childSessionId = child,
        )

    // Randomized parity: after EVERY operation (live frames, dedups, resumes,
    // exits, errors, prepends, truth-ups — including uuid collisions and the
    // disjoint-window reset) the incremental child index must equal a full
    // collectChildSessions scan, and the UI key index must stay aligned with
    // the window and collision-free.
    @Test
    fun childIndexAndKeysMatchFullScanUnderRandomOps() {
        val rnd = Random(20260723)
        var s = ConvoState()
        var ts = 0
        var uuidN = 0
        fun freshTs() = "t${ts++}"
        val childPool = List(8) { "c$it" }
        fun maybeChild() = if (rnd.nextInt(3) == 0) childPool[rnd.nextInt(childPool.size)] else null

        repeat(500) { step ->
            when (rnd.nextInt(10)) {
                0 -> s = s.reduce(LiveFrame.UserTurn("m$step", if (rnd.nextBoolean()) freshTs() else null))
                1 -> s = s.optimisticUser("opt$step")
                2, 3, 4 -> s = s.reduce(LiveFrame.Part(toolPart(step, maybeChild()), freshTs(), "m"))
                5 -> {
                    // Resume an existing assistant turn by ts (tail reopen or the
                    // rare mid-window in-place append), sometimes re-delivering
                    // an existing part (must fold away).
                    val candidates = s.history.filter { it.role == "assistant" && it.ts.isNotEmpty() }
                    if (candidates.isNotEmpty() && !s.openAssistant) {
                        val t = candidates[rnd.nextInt(candidates.size)]
                        val p = if (rnd.nextBoolean() && t.parts.isNotEmpty()) t.parts[rnd.nextInt(t.parts.size)]
                        else toolPart(step, maybeChild())
                        s = s.reduce(LiveFrame.Part(p, t.ts, null))
                    }
                }
                6 -> s = s.reduce(LiveFrame.Exit(reason = null, assistantUuid = if (rnd.nextBoolean()) "u${uuidN++}" else null, assistantTs = null))
                7 -> s = s.reduce(if (rnd.nextBoolean()) LiveFrame.TurnIdle else LiveFrame.ErrorFrame("boom$step"))
                8 -> {
                    // Prepend an earlier page, sometimes with a uuid colliding
                    // with a loaded row's key.
                    val stolen = s.uiKeys.filterIsInstance<String>().firstOrNull { !it.startsWith("~") }
                    val early = asst(freshTs(), if (stolen != null && rnd.nextInt(3) == 0) stolen else "pu$step", "e$step")
                    val page = listOf(
                        if (rnd.nextBoolean()) early.copy(parts = listOf(toolPart(step, childPool[rnd.nextInt(childPool.size)]))) else early,
                        user("old$step", freshTs()),
                    )
                    val cur = s.cursor
                    s = s.prepend(page, total = (cur?.total ?: s.turnCount) + page.size, start = maxOf(0, (cur?.start ?: 2) - page.size))
                }
                else -> {
                    // Truth-up with committed versions of the loaded tail plus a
                    // new committed turn; occasionally a disjoint-window reset.
                    val committed = s.turns.takeLast(4).filter { it.role != "error" }.mapIndexed { i, t ->
                        when {
                            t.role == "assistant" && t.uuid == null -> t.copy(uuid = "vu$step-$i")
                            t.role == "user" && t.ts.isEmpty() -> t.copy(ts = freshTs())
                            else -> t
                        }
                    }
                    val page = committed + asst(freshTs(), "xu$step", "n$step")
                    val oldTotal = s.cursor?.total ?: s.turnCount
                    val disjoint = rnd.nextInt(12) == 0
                    s = s.truthUp(page, total = oldTotal + 1, start = if (disjoint) oldTotal + 3 else maxOf(0, oldTotal + 1 - page.size))
                }
            }
            assertEquals(collectChildSessions(s.turns), s.children, "children diverged from full scan at step $step")
            assertEquals(s.children.map { it.second }.toSet(), s.childIds, "childIds diverged at step $step")
            val keys = s.uiKeys
            assertEquals(s.turns.size, keys.size, "key/turn misalignment at step $step")
            assertEquals(keys.size, keys.toSet().size, "duplicate ui keys at step $step")
        }
    }
}
