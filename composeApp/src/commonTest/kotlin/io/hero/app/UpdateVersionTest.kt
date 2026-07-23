package io.hero.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Table tests for the updater's version grammar. isNewer decides whether the
// app nags the user to install a release, so both directions matter: a lie
// toward "newer" creates an update loop (install v0.5.20, get offered v0.5.20
// again), a lie toward "not newer" hides real releases forever. Anything
// outside the released grammar (vMAJOR.MINOR.PATCH, numeric dash suffix
// tolerated) must FAIL CLOSED — unknown is not orderable, so it is never
// "newer".
class UpdateVersionTest {
    @Test
    fun ordersWellFormedVersionsNumerically() {
        val table = listOf(
            // tag, current, expected isNewer(tag, current)
            Triple("v0.5.20", "0.5.19", true),
            Triple("v0.5.20", "0.5.20", false), // same version: up to date, no loop
            Triple("v0.5.19", "0.5.20", false), // older tag never offered
            Triple("v0.6.0", "0.5.20", true), // minor beats patch
            Triple("v1.0.0", "0.5.20", true), // major beats minor
            Triple("v0.5.9", "0.5.19", false), // numeric, not lexicographic (9 < 19)
            Triple("v0.10.0", "0.9.9", true), // ditto per segment
            Triple("0.5.20", "0.5.19", true), // bare tag (no "v") tolerated
            Triple("v0.5.20", "v0.5.19", true), // "v" on either side tolerated
            Triple("v0.5.20-1", "0.5.20", true), // numeric dash suffix orders after
            Triple("v0.5.20", "0.5.20-1", false),
            Triple("v0.5.20-2", "0.5.20-1", true),
            Triple("v0.5", "0.5.0", false), // missing segments read as 0
            Triple("v0.5.0", "0.5", false),
            Triple("v0.5.1", "0.5", true),
        )
        for ((tag, current, expected) in table) {
            assertEquals(expected, isNewer(tag, current), "isNewer(\"$tag\", \"$current\")")
        }
    }

    @Test
    fun malformedVersionsFailClosed() {
        // Malformed TAG: never an update, regardless of how it would "sort".
        val badTags = listOf("", "v", "v0.6.0-rc1", "beta", "1.2.x", "0..1", "v1.2.-3", "v1. 2", "99999999999.0.0")
        for (tag in badTags) {
            assertEquals(false, isNewer(tag, "0.5.19"), "isNewer(\"$tag\", \"0.5.19\") must fail closed")
        }
        // Malformed CURRENT: a broken local constant must not turn every release
        // into a perpetual install prompt.
        val badCurrents = listOf("", "v", "dev", "0.5.x", "0.5.20-dev")
        for (current in badCurrents) {
            assertEquals(false, isNewer("v99.0.0", current), "isNewer(\"v99.0.0\", \"$current\") must fail closed")
        }
    }

    @Test
    fun parseVersionAcceptsExactlyTheReleaseGrammar() {
        assertEquals(listOf(0, 5, 20), parseVersion("v0.5.20"))
        assertEquals(listOf(0, 5, 20), parseVersion("0.5.20"))
        assertEquals(listOf(0, 5, 20, 1), parseVersion("0.5.20-1"))
        assertEquals(listOf(1, 2), parseVersion("v1.2"))
        assertEquals(listOf(7), parseVersion("7"))
        // The old parser DROPPED unparseable segments ("v1.junk.9" -> [1, 9]);
        // now any bad segment rejects the whole version.
        assertNull(parseVersion("v1.junk.9"))
        assertNull(parseVersion(""))
        assertNull(parseVersion("v"))
        assertNull(parseVersion("v0.6.0-rc1"))
        assertNull(parseVersion("0..1"))
        assertNull(parseVersion("1.2.")) // trailing separator = empty segment
        assertNull(parseVersion("-1.2")) // leading dash = empty segment (and no negatives)
        assertNull(parseVersion(" 1.2")) // no whitespace tolerance
        assertNull(parseVersion("99999999999.0.0")) // Int overflow is unknown, not wraparound
    }
}
