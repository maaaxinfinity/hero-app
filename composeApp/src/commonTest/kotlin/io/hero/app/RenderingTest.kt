package io.hero.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    // Links: the visible text is the label only; the URL rides in an annotation
    // spanning exactly the label. Unclosed markers still degrade to literals.
    @Test
    fun markdownLinkAnnotations() {
        val out = parseInline(
            "see [docs](https://example.com/d) now",
            link = androidx.compose.ui.graphics.Color.Black,
            codeBg = androidx.compose.ui.graphics.Color.White,
        )
        assertEquals("see docs now", out.text)
        val ann = out.getStringAnnotations(URL_TAG, 0, out.length).single()
        assertEquals("https://example.com/d", ann.item)
        assertEquals("docs", out.text.substring(ann.start, ann.end))

        val broken = parseInline("a [label(no-close", androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.White)
        assertEquals("a [label(no-close", broken.text)
        assertTrue(broken.getStringAnnotations(URL_TAG, 0, broken.length).isEmpty())
    }

    // Well-formed bold / italic / code / link still produce the exact same visible
    // text, span styles and URL annotation after the rewrite to the linear parser.
    @Test
    fun parseInlinePreservesNormalMarkup() {
        val out = parseInline("a **bold** b *italic* c `code` d [x](https://h)", Color.Black, Color.White)
        assertEquals("a bold b italic c code d x", out.text)
        assertTrue(
            out.spanStyles.any { it.item.fontWeight == FontWeight.Bold && out.text.substring(it.start, it.end) == "bold" },
            "bold span missing",
        )
        assertTrue(
            out.spanStyles.any { it.item.fontStyle == FontStyle.Italic && out.text.substring(it.start, it.end) == "italic" },
            "italic span missing",
        )
        assertTrue(
            out.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && out.text.substring(it.start, it.end) == "code" },
            "code span missing",
        )
        val ann = out.getStringAnnotations(URL_TAG, 0, out.length).single()
        assertEquals("https://h", ann.item)
        assertEquals("x", out.text.substring(ann.start, ann.end))
    }

    // Regression for the O(L^2) inline scan: a text of only unclosed '[' used to make
    // every '[' re-scan forward with indexOf(']'). The linear parser bounds total
    // scan work to O(L), so 8/16/32/64 KiB stay ~linear (~x8), not ~quadratic (~x64).
    private fun timeUnclosedBrackets(sizeKib: Int): kotlin.time.Duration {
        val s = "[".repeat(sizeKib * 1024)
        val mark = TimeSource.Monotonic.markNow()
        val out = parseInline(s, Color.Black, Color.White)
        val elapsed = mark.elapsedNow()
        // Every '[' degrades to a literal — nothing is dropped and it terminates.
        assertEquals(s.length, out.text.length)
        assertTrue(out.getStringAnnotations(URL_TAG, 0, out.length).isEmpty())
        return elapsed
    }

    @Test
    fun parseInlineLinearOnUnclosedBrackets() {
        parseUnclosedWarmup()
        val t8 = timeUnclosedBrackets(8)
        val t16 = timeUnclosedBrackets(16)
        val t32 = timeUnclosedBrackets(32)
        val t64 = timeUnclosedBrackets(64)
        // Absolute guard: a linear parser handles 64 KiB of pathological '[' in well
        // under a second; the old O(L^2) scan is billions of char-ops (many seconds).
        assertTrue(t64 < 2.seconds, "64 KiB unclosed '[' took $t64 — expected linear (sub-second)")
        // Scaling guard: 8->64 KiB (x8 input) must not blow up ~x64. Generous slack
        // absorbs JIT/GC noise on sub-millisecond absolute times.
        assertTrue(
            t64 <= t8 * 32 + 500.milliseconds,
            "inline scan looked super-linear: t8=$t8 t16=$t16 t32=$t32 t64=$t64",
        )
    }

    private fun parseUnclosedWarmup() {
        // Prime the JIT so the first measured size isn't unfairly penalised.
        parseInline("[".repeat(4096), Color.Black, Color.White)
    }

    // The per-part view budget keeps a huge body from rendering wholesale: a
    // multi-MiB string is bounded to a code-point-safe head + tail (summing to at
    // most RENDER_CHAR_CAP chars) before layout, and short-line markdown parses a
    // bounded number of blocks per slice instead of one block per line for the
    // whole part. (Mechanical adaptation of the old head-only capForRender test:
    // truncation became head+tail with Show full / Copy raw, so the API is
    // renderSlices now — the pre-layout bound this test guards is unchanged, and
    // sub-cap content still renders untouched.)
    @Test
    fun renderCapBoundsHugeContent() {
        val small = "hello **world**"
        assertNull(renderSlices(small))
        assertFalse(isRenderCapped(small))

        val huge = "a".repeat(4 * 1024 * 1024) // multi-MiB
        assertTrue(isRenderCapped(huge))
        val slices = renderSlices(huge)!!
        assertEquals(RENDER_HEAD_CHARS, slices.head.length)
        assertEquals(RENDER_TAIL_CHARS, slices.tail.length)
        assertTrue(slices.head.length + slices.tail.length <= RENDER_CHAR_CAP)
        assertEquals(huge.length, slices.head.length + slices.omitted + slices.tail.length)

        val manyLines = "x\n".repeat(1_000_000)
        val sliced = renderSlices(manyLines)!!
        assertTrue(parseBlocks(sliced.head).size <= RENDER_CHAR_CAP)
        assertTrue(parseBlocks(sliced.tail).size <= RENDER_CHAR_CAP)
    }
}
