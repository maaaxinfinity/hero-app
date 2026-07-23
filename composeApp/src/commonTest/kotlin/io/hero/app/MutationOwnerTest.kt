package io.hero.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The shared management mutation owner (the Remove/Delete pattern generalized
// to transfer/share/unshare, password/admin, create/join, apply/install and
// the control-plane toggles): single-flight admission, the immutable
// {operation id, fingerprint, generation} captured at launch, failed-retry id
// reuse (the idempotency key a server-side dedupe converges on), and the
// late-completion CAS that keeps an old generation's completion from writing
// into a retargeted surface. Pure — no composition, no HTTP.
class MutationOwnerTest {
    // While one mutation is in flight, EVERY other begin on the surface is
    // refused: the double click on the same button and the sibling button
    // alike. Settling frees the surface.
    @Test
    fun singleFlightRefusesWhileBusy() {
        val o = MutationOwner()
        val op = o.begin("remove:n1")
        assertNotNull(op)
        assertTrue(o.busy)
        assertNull(o.begin("remove:n1"), "double click must not admit a second operation")
        assertNull(o.begin("share:n1:bob"), "a sibling mutation on the same surface must wait")
        assertTrue(o.settle(op, failed = false))
        assertFalse(o.busy)
        assertNotNull(o.begin("share:n1:bob"))
    }

    // An explicit retry of the SAME logical mutation after a failure reuses
    // the operation id — a response-lost resubmission must be able to converge
    // to one terminal state server-side. A different logical mutation is a
    // fresh operation.
    @Test
    fun failedRetryReusesTheOperationId() {
        val o = MutationOwner()
        val first = o.begin("share:n1:bob")!!
        o.settle(first, failed = true)
        val retry = o.begin("share:n1:bob")!!
        assertEquals(first.id, retry.id)
        o.settle(retry, failed = true)
        val other = o.begin("share:n1:alice")!!
        assertNotEquals(first.id, other.id)
    }

    // Success retires the reuse: running the same-looking mutation again later
    // is a NEW logical operation, not a replay of the finished one.
    @Test
    fun successRetiresTheRetryId() {
        val o = MutationOwner()
        val first = o.begin("share:n1:bob")!!
        o.settle(first, failed = true)
        val retry = o.begin("share:n1:bob")!!
        assertEquals(first.id, retry.id)
        o.settle(retry, failed = false)
        val next = o.begin("share:n1:bob")!!
        assertNotEquals(first.id, next.id)
    }

    // A success of a DIFFERENT operation must not retire another failed op's
    // retry id: the failed share can still be retried under its original id.
    @Test
    fun unrelatedSuccessKeepsAnotherOpsRetryId() {
        val o = MutationOwner()
        val failed = o.begin("share:n1:bob")!!
        o.settle(failed, failed = true)
        val unrelated = o.begin("unshare:n1:carol")!!
        o.settle(unrelated, failed = false)
        val retry = o.begin("share:n1:bob")!!
        assertEquals(failed.id, retry.id)
    }

    // The late-completion CAS: a completion whose generation predates a
    // retarget lost ownership — it must not close panels, write status, clear
    // inputs, or reload. The surface itself is free again afterwards.
    @Test
    fun retargetFencesLateCompletions() {
        val o = MutationOwner()
        val op = o.begin("remove:n1")!!
        o.retarget() // the surface moved on while the request was in flight
        assertFalse(o.settle(op, failed = false), "a completion that lost ownership must not write state")
        assertFalse(o.busy, "the settled surface is free for the new target")
        val fresh = o.begin("remove:n1")!!
        assertTrue(o.settle(fresh, failed = false))
    }

    @Test
    fun generationIsCapturedPerOperation() {
        val o = MutationOwner()
        val a = o.begin("x")!!
        assertEquals(0L, a.generation)
        o.settle(a, failed = false)
        o.retarget()
        o.retarget()
        val b = o.begin("x")!!
        assertEquals(2L, b.generation)
        assertTrue(o.settle(b, failed = false))
    }
}
