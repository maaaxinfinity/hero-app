package io.hero.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The flattened conversation presentation contract:
//
//  - renderSlices: an over-cap body's default view is a code-point-safe head +
//    tail that reconstruct the original EXACTLY with the omitted count between
//    them (Show full renders that original; Copy raw hands it out verbatim).
//  - partFoldPlan: one turn's part rows are bounded by explicit row + rendered-
//    char budgets; the overflow folds into a counted, expandable row.
//  - turnRowSuffixes/flattenRowSuffixes: row keys are turn-UI-id + part ordinal,
//    stable across live appends, settle and prepend — the LazyColumn identity
//    contract behind row-local state (tool cards, Show full) surviving.
//  - the window's tail rows belong to the open turn, so the pane's follow /
//    bottom logic (totalRows - 1, isReallyAtBottom) targets live content.
class ConvoPresentationTest {
    private fun textPart(c: String) = TurnPart(type = "text", content = c)
    private fun asst(parts: List<TurnPart>) = Turn(role = "assistant", parts = parts, ts = "t")
    private fun suffixes(s: ConvoState, expanded: Set<Any> = emptySet()) =
        flattenRowSuffixes(s.turns, s.uiKeys, expanded)

    // ---- head+tail truncation ----

    private val pair = "𝕏" // 𝕏 U+1D54F — one code point, two UTF-16 units

    @Test
    fun renderSlicesIsCodePointSafeAtBothCuts() {
        // Surrogate pair straddling the HEAD cut: s[HEAD-1] high, s[HEAD] low.
        val atHead = "x".repeat(RENDER_HEAD_CHARS - 1) + pair + "y".repeat(RENDER_TAIL_CHARS + 128)
        val h = renderSlices(atHead)!!
        assertEquals(RENDER_HEAD_CHARS - 1, h.head.length) // backed off — the pair drops whole
        assertFalse(h.head.last().isHighSurrogate())
        assertTrue(atHead.startsWith(h.head))
        assertTrue(atHead.endsWith(h.tail))
        assertEquals(atHead.length, h.head.length + h.omitted + h.tail.length)

        // Surrogate pair straddling the TAIL cut: the naive start lands on the low half.
        val a = RENDER_HEAD_CHARS + 100
        val atTail = "x".repeat(a) + pair + "y".repeat(RENDER_TAIL_CHARS - 1)
        val t = renderSlices(atTail)!!
        assertFalse(t.tail.first().isLowSurrogate()) // advanced — never starts mid-pair
        assertEquals("y".repeat(RENDER_TAIL_CHARS - 1), t.tail)
        assertTrue(atTail.startsWith(t.head))
        assertTrue(atTail.endsWith(t.tail))
        assertEquals(atTail.length, t.head.length + t.omitted + t.tail.length)
    }

    @Test
    fun renderSlicesReconstructsTheOriginalAndShowFullUsesIt() {
        // The sliced view is pure presentation: head + hidden middle + tail is the
        // original, char for char. Show full renders (and Copy raw copies) the
        // original string itself, so equality here is the losslessness proof —
        // including a final error line that lives at the very END of the body.
        val original = buildString {
            repeat(3000) { append("line $it $pair\n") }
            append("Error: the decisive failure is at the tail")
        }
        assertTrue(isRenderCapped(original))
        val s = renderSlices(original)!!
        val middle = original.substring(s.head.length, original.length - s.tail.length)
        assertEquals(original, s.head + middle + s.tail)
        assertEquals(middle.length, s.omitted)
        assertTrue(s.omitted > 0)
        assertTrue(s.tail.endsWith("Error: the decisive failure is at the tail"))
        // At (or under) the cap nothing is sliced — the body renders whole.
        assertNull(renderSlices("a".repeat(RENDER_CHAR_CAP)))
    }

    @Test
    fun labelsShareTheRenderBudget() {
        // Workflow name/status/summary, system markers and model labels all pass
        // through capDisplay/boundedPhaseTrail: unchanged under the cap, bounded
        // (and code-point safe) past it — the 3 MiB workflow summary can no
        // longer reach layout wholesale.
        val small = "gpt-5.3-codex"
        assertEquals(small, capDisplay(small))
        val capped = capDisplay("m".repeat(3 * 1024 * 1024))
        assertTrue(capped.length <= RENDER_CHAR_CAP + 1)
        assertTrue(capped.endsWith("…"))
        val pairAtCut = "x".repeat(RENDER_CHAR_CAP - 1) + pair + "z".repeat(64)
        assertFalse(capDisplay(pairAtCut).dropLast(1).last().isHighSurrogate())

        // Under the cap the phase trail equals the original joinToString output.
        val phases = listOf(WorkflowPhase("plan"), WorkflowPhase("build"), WorkflowPhase("verify"))
        assertEquals(phases.joinToString("  ›  ") { it.title }, boundedPhaseTrail(phases))
        // A pathological trail stays bounded and never materializes the full join.
        val big = List(4096) { WorkflowPhase("p".repeat(1024)) }
        assertTrue(boundedPhaseTrail(big).length <= RENDER_CHAR_CAP + 8)
    }

    // ---- per-turn part budgets ----

    @Test
    fun partFoldPlanCapsRowsAndRenderedChars() {
        // Row budget: 10k tiny parts render 64 head + 64 tail rows; the counted
        // remainder folds.
        val tiny = List(10_000) { textPart("p$it") }
        val plan = partFoldPlan(tiny)
        assertEquals(TURN_PART_HEAD_ROWS, plan.headCount)
        assertEquals(10_000 - TURN_PART_TAIL_ROWS, plan.tailStart)
        assertEquals(10_000 - TURN_PART_HEAD_ROWS - TURN_PART_TAIL_ROWS, plan.hiddenCount)
        assertTrue(plan.folded)

        // Rendered-char budget: parts at the per-body cap trip the char ceiling
        // long before the row ceiling.
        val fat = List(32) { textPart("a".repeat(RENDER_CHAR_CAP)) }
        val fatPlan = partFoldPlan(fat)
        assertEquals(TURN_PART_HEAD_CHARS / RENDER_CHAR_CAP, fatPlan.headCount)
        assertEquals(32 - TURN_PART_TAIL_CHARS / RENDER_CHAR_CAP, fatPlan.tailStart)
        assertTrue(fatPlan.folded)

        // A part's planning cost is bounded by the render cap even when the RAW
        // field — content or the workflow summary that used to bypass every
        // budget — is multi-MiB.
        val wf = TurnPart(type = "tool", workflow = WorkflowInfo(summary = "s".repeat(3 * 1024 * 1024)))
        assertEquals(fatPlan.headCount, partFoldPlan(List(32) { wf }).headCount)

        // Within both budgets nothing folds (canonical no-fold form), and the
        // empty turn is the trivial plan.
        val ok = List(TURN_PART_HEAD_ROWS + TURN_PART_TAIL_ROWS) { textPart("x") }
        assertEquals(PartFoldPlan(ok.size, ok.size, ok.size), partFoldPlan(ok))
        assertFalse(partFoldPlan(ok).folded)
        assertEquals(PartFoldPlan(0, 0, 0), partFoldPlan(emptyList()))
    }

    @Test
    fun rowCountArithmeticMatchesSuffixList() {
        // turnRowCount (the O(1) per-frame follow-target arithmetic) must agree
        // with turnRowSuffixes (the anchor/emission key list) for every shape,
        // and keys within a turn must be unique.
        val rnd = Random(20260723)
        repeat(200) {
            val n = rnd.nextInt(0, 400)
            val parts = List(n) { i ->
                if (rnd.nextInt(4) == 0) textPart("big".repeat(rnd.nextInt(1, 8000))) else textPart("p$i")
            }
            val turn = asst(parts)
            val expanded = rnd.nextBoolean()
            val sfx = turnRowSuffixes("k", turn, expanded)
            assertEquals(sfx.size, turnRowCount(turn, partFoldPlan(parts), expanded))
            assertEquals(sfx.size, sfx.toSet().size)
        }
        // Single-row roles keep their original bare-UI-id key; unknown roles
        // flatten via the assistant path (they render via AssistantTurn too).
        val user = Turn(role = "user", content = "hi", ts = "t")
        assertEquals(listOf("k"), turnRowSuffixes("k", user, false))
        assertEquals(1, turnRowCount(user, null, false))
        assertFalse(turnIsMultiRow(user))
        assertFalse(turnIsMultiRow(Turn(role = "system")))
        assertFalse(turnIsMultiRow(Turn(role = "error")))
        assertTrue(turnIsMultiRow(Turn(role = "assistant")))
        assertTrue(turnIsMultiRow(Turn(role = "future.kind")))
    }

    // ---- row-key stability across append / settle / prepend ----

    @Test
    fun rowKeysStableAcrossLiveAppendSettleAndPrepend() {
        var s = ConvoState(turns = listOf(Turn(role = "user", content = "q", ts = "t0")), cursor = Cursor(3, 2, 2))
        s = s.reduce(LiveFrame.Part(textPart("a"), "t1", "m"))
        s = s.reduce(LiveFrame.Part(textPart("b"), "t1", "m"))
        val before = suffixes(s)
        // Live append: exactly ONE new row, inserted before the turn footer;
        // every pre-existing key survives in order.
        val grown = s.reduce(LiveFrame.Part(textPart("c"), "t1", "m"))
        val after = suffixes(grown)
        assertEquals(before.size + 1, after.size)
        assertTrue(after.containsAll(before))
        assertEquals(before.dropLast(1), after.dropLast(2))
        assertEquals(before.last(), after.last()) // the footer stays the last row
        // Settle (live → terminal): the key list is IDENTICAL — rows move from
        // the open block into the history block, none is re-keyed.
        val settled = grown.reduce(LiveFrame.Exit(reason = null, assistantUuid = "u1", assistantTs = null))
        assertEquals(after, suffixes(settled))
        // Prepend: the earlier page's rows land ABOVE; loaded rows keep exactly
        // their keys (turn UI ids are stable, parts untouched, plans pure).
        val page = listOf(
            Turn(role = "user", content = "old", ts = "t-1"),
            Turn(role = "assistant", parts = listOf(textPart("z")), ts = "t-2", uuid = "u0"),
        )
        val prepended = settled.prepend(page, total = 5, start = 0)
        val merged = suffixes(prepended)
        assertEquals(suffixes(settled), merged.takeLast(suffixes(settled).size))
        assertEquals(merged.size, merged.toSet().size)
    }

    @Test
    fun foldedLiveTurnRetainsHeadKeysWhileTailSlides() {
        var s = ConvoState()
        repeat(300) { i -> s = s.reduce(LiveFrame.Part(textPart("p$i"), "t", "m")) }
        val key = checkNotNull(s.openKey)
        val before = turnRowSuffixes(key, s.open!!, expanded = false)
        assertTrue(partFoldPlan(s.open!!.parts).folded)
        s = s.reduce(LiveFrame.Part(textPart("p300"), "t", "m"))
        val after = turnRowSuffixes(key, s.open!!, expanded = false)
        // Head window identical; the tail window slides forward: the new part is
        // the last row before the footer, the oldest tail row leaves the visible
        // set, and every SURVIVING row keeps its key and relative order.
        assertEquals(before.take(1 + TURN_PART_HEAD_ROWS), after.take(1 + TURN_PART_HEAD_ROWS))
        assertEquals(footerRowSuffix(key), after.last())
        assertEquals(partRowSuffix(key, 300), after[after.size - 2])
        val beforeSet = before.toSet()
        val afterSet = after.toSet()
        assertEquals(before.filter { it in afterSet }, after.filter { it in beforeSet })
        // Expanding renders EVERY part as a row (nothing lost), with the fold row
        // still present (as "show fewer") at its stable key.
        val expanded = turnRowSuffixes(key, s.open!!, expanded = true)
        assertEquals(1 + 301 + 1 + 1, expanded.size)
        assertTrue(expanded.containsAll((0..300).map { partRowSuffix(key, it) }))
        assertTrue(moreRowSuffix(key) in expanded)
    }

    // ---- bottom semantics over flattened rows ----

    @Test
    fun windowTailRowsBelongToTheOpenTurn() {
        // The pane follows to totalRows - 1: under the flatten that last row is
        // the open turn's footer, directly under its newest part row — so both
        // initial-open and live follow land on live content, and isReallyAtBottom
        // keeps judging by the last row's END offset (unchanged pure check).
        var s = ConvoState(turns = listOf(Turn(role = "user", content = "q", ts = "t0")))
        s = s.reduce(LiveFrame.Part(textPart("a"), "t1", "m"))
        val key = checkNotNull(s.openKey)
        val rows = suffixes(s)
        assertEquals(footerRowSuffix(key), rows.last())
        assertEquals(partRowSuffix(key, 0), rows[rows.size - 2])
        assertTrue(isReallyAtBottom(lastItemEndOffset = 100, viewportEndOffset = 100))
        assertFalse(isReallyAtBottom(lastItemEndOffset = 101, viewportEndOffset = 100))
    }
}
