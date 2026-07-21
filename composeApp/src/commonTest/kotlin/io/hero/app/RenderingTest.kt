package io.hero.app

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ev(type: String, raw: String?) =
        Event(session_id = "s", type = type, raw = raw?.let { json.parseToJsonElement(it) })

    @Test
    fun decodePartFrame() {
        val f = decodeLiveFrame(
            ev("part", """{"role":"assistant","ts":"t","model":"m","part":{"type":"tool","content":"out","toolName":"Bash","toolTarget":"ls"}}"""),
            json,
        )
        assertIs<LiveFrame.Part>(f)
        assertEquals("Bash", f.part.toolName)
        assertEquals("tool", f.part.type)
        assertEquals("m", f.model)
    }

    @Test
    fun decodeUserAndExit() {
        val u = decodeLiveFrame(ev("turn.user", """{"role":"user","content":"hi","ts":"t"}"""), json)
        assertIs<LiveFrame.UserTurn>(u)
        assertEquals("hi", u.content)

        val e = decodeLiveFrame(ev("subprocess.exit", """{"assistant_uuid":"u1","assistant_ts":"t"}"""), json)
        assertIs<LiveFrame.Exit>(e)
        assertEquals("u1", e.assistantUuid)
    }

    // Forward-compat: an unknown frame type maps to Unknown (stream survives), and
    // a malformed body returns null rather than throwing.
    @Test
    fun decodeUnknownAndMalformed() {
        assertIs<LiveFrame.Unknown>(decodeLiveFrame(ev("brand.new.kind", "{}"), json))
        assertNull(decodeLiveFrame(ev("part", """{"part":123}"""), json))
    }

    // A user→part→part→exit sequence yields one user turn + one assistant turn
    // whose parts accumulated in order and whose uuid was set at exit.
    @Test
    fun reduceGroupsOneAssistantTurn() {
        var s = ConvoState()
        s = s.reduce(LiveFrame.UserTurn("hi", "t0"))
        s = s.reduce(LiveFrame.Part(TurnPart(type = "text", content = "a"), "t1", "m"))
        s = s.reduce(LiveFrame.Part(TurnPart(type = "tool", content = "o", toolName = "Bash"), "t1", "m"))
        s = s.reduce(LiveFrame.Exit(reason = null, assistantUuid = "u1", assistantTs = "t2"))

        assertEquals(2, s.turns.size)
        val asst = s.turns[1]
        assertEquals("assistant", asst.role)
        assertEquals(2, asst.parts.size)
        assertEquals("text", asst.parts[0].type)
        assertEquals("Bash", asst.parts[1].toolName)
        assertEquals("u1", asst.uuid)
        assertTrue(!s.openAssistant)
    }

    // The optimistic local echo is not doubled when the canonical turn.user arrives.
    @Test
    fun optimisticUserDeduped() {
        var s = ConvoState().optimisticUser("hello")
        assertEquals(1, s.turns.size)
        s = s.reduce(LiveFrame.UserTurn("hello", "t"))
        assertEquals(1, s.turns.size)
    }

    @Test
    fun markdownBlockParsing() {
        val blocks = parseBlocks("# Title\n\nsome text\n\n```\ncode here\n```\n- item\n1. first")
        assertTrue(blocks.any { it is MdBlock.Heading && it.level == 1 && it.text == "Title" })
        assertTrue(blocks.any { it is MdBlock.Code && it.text == "code here" })
        assertTrue(blocks.any { it is MdBlock.Bullet && it.text == "item" })
        assertTrue(blocks.any { it is MdBlock.Ordered && it.text == "first" })
    }
}
