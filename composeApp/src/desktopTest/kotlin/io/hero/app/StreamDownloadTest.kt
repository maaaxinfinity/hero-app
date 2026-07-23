package io.hero.app

import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

// streamDownload takes a plain ByteReadChannel + write sink (no HTTP engine), so
// it is driven here with an in-memory channel. runBlocking lives in the desktop
// (JVM) test source set; there is no coroutine-test dependency in commonTest.
class StreamDownloadTest {
    // A payload larger than the internal 64 KiB read buffer, so multiple reads
    // are exercised.
    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun copiesEveryByteAndReportsProgress() = runBlocking {
        val data = payload(200_000)
        val out = ByteArrayOutputStream()
        var lastReceived = 0L
        var lastTotal: Long? = -1L
        val total = streamDownload(
            channel = ByteReadChannel(data),
            expected = data.size.toLong(),
            onProgress = { received, t -> lastReceived = received; lastTotal = t },
        ) { b, n -> out.write(b, 0, n) }

        assertEquals(data.size.toLong(), total)
        assertEquals(data.size.toLong(), lastReceived)
        assertEquals(data.size.toLong(), lastTotal)
        assertContentEquals(data, out.toByteArray())
    }

    @Test
    fun enforcesByteCeilingWithoutWritingPastIt() {
        val data = payload(200_000)
        val out = ByteArrayOutputStream()
        val ex = assertFailsWith<UpdateDownloadException> {
            runBlocking {
                streamDownload(
                    channel = ByteReadChannel(data),
                    expected = null, // no declared length → the mid-stream guard is the only bound
                    onProgress = { _, _ -> },
                    maxBytes = 100_000,
                ) { b, n -> out.write(b, 0, n) }
            }
        }
        assertTrue(ex.message!!.contains("ceiling"))
        // The over-ceiling chunk is rejected before it is written, so we never
        // persist the whole payload nor exceed the ceiling.
        assertTrue(out.size() < data.size)
        assertTrue(out.size() <= 100_000)
    }
}
