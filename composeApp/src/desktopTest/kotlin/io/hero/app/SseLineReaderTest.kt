package io.hero.app

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// SseLineReader is the strict encoded-byte bound on one SSE line, enforced by
// COUNTING bytes before any String is materialized. It replaces Ktor's
// readUTF8Line(max), whose max is soft: the check runs only while the delimiter
// hasn't been seen, so a line whose newline and over-limit tail arrive in the
// same buffer was accepted past the declared ceiling (measured +47 bytes).
// Driven with in-memory channels, like StreamDownloadTest (runBlocking is JVM;
// there is no coroutine-test dependency in commonTest).
class SseLineReaderTest {
    @Test
    fun readsLinesStripsCrlfAndDeliversEofTail() = runBlocking {
        val r = SseLineReader(ByteReadChannel("data: a\r\ndata: b\n\nlast".encodeToByteArray()), 1024)
        assertEquals("data: a", r.readLine())
        assertEquals("data: b", r.readLine())
        assertEquals("", r.readLine())
        assertEquals("last", r.readLine()) // unterminated tail at EOF still delivered
        assertNull(r.readLine())
        assertNull(r.readLine()) // stays drained
    }

    @Test
    fun acceptsALineExactlyAtTheLimit() = runBlocking {
        val line = "x".repeat(64)
        val r = SseLineReader(ByteReadChannel("$line\nok\n".encodeToByteArray()), 64)
        assertEquals(line, r.readLine())
        assertEquals("ok", r.readLine())
        assertNull(r.readLine())
    }

    // The readUTF8Line(max) regression class: the whole over-limit line AND its
    // newline AND a trailing next line arrive in one buffered chunk. The budget
    // must still hold exactly — one byte over throws.
    @Test
    fun rejectsOneByteOverEvenWhenNewlineArrivesInTheSameBuffer() = runBlocking {
        val body = "x".repeat(65) + "\n" + "tail\n"
        val r = SseLineReader(ByteReadChannel(body.encodeToByteArray()), 64)
        val ex = assertFailsWith<IllegalStateException> { r.readLine() }
        assertTrue(ex.message!!.contains("64"))
    }

    // A line that never sends a newline must fail at the budget, not accumulate.
    @Test
    fun rejectsANeverEndingLineAtTheBudget(): Unit = runBlocking {
        val r = SseLineReader(ByteReadChannel(ByteArray(4096) { 'y'.code.toByte() }), 64)
        assertFailsWith<IllegalStateException> { r.readLine() }
    }

    // The ceiling counts ENCODED bytes, not chars: 30 three-byte chars = 90
    // bytes, over a 64-byte budget despite being only 30 chars.
    @Test
    fun countsEncodedBytesNotChars(): Unit = runBlocking {
        val r = SseLineReader(ByteReadChannel(("€".repeat(30) + "\n").encodeToByteArray()), 64)
        assertFailsWith<IllegalStateException> { r.readLine() }
    }

    // An over-limit unterminated tail at EOF is rejected too (defensive: the
    // running budget check usually fires first).
    @Test
    fun rejectsOverLimitEofTail(): Unit = runBlocking {
        val r = SseLineReader(ByteReadChannel("ok\n".encodeToByteArray() + ByteArray(70) { 'z'.code.toByte() }), 64)
        assertEquals("ok", r.readLine())
        assertFailsWith<IllegalStateException> { r.readLine() }
    }

    // Lines split across separate chunk arrivals (flush boundaries) reassemble;
    // multi-byte UTF-8 content survives the buffer compaction/growth path.
    @Test
    fun reassemblesLinesAcrossChunkArrivals() = runBlocking {
        val chan = ByteChannel()
        launch {
            chan.writeStringUtf8("data: he")
            chan.flush()
            chan.writeStringUtf8("llo €42\ndata: world\n")
            chan.flushAndClose()
        }
        val r = SseLineReader(chan, 1024)
        assertEquals("data: hello €42", r.readLine())
        assertEquals("data: world", r.readLine())
        assertNull(r.readLine())
    }

    // Many small lines through a small buffer exercise repeated compaction.
    @Test
    fun handlesManyLinesThroughATinyBuffer() = runBlocking {
        val body = (1..50).joinToString("") { "line-$it\n" }
        val r = SseLineReader(ByteReadChannel(body.encodeToByteArray()), 15)
        for (i in 1..50) assertEquals("line-$i", r.readLine())
        assertNull(r.readLine())
    }
}
