package io.hero.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

// The pure snapshot codec and the Settings transaction core are testable without
// a Compose UI. Real file I/O is exercised through DesktopSettingsIo pointed at a
// temp file. runBlocking lives in the desktop (JVM) test source set — there is no
// coroutine-test dependency.
class SettingsTest {

    // ---- encode / decode -----------------------------------------------------

    @Test
    fun encodeIsDeterministicAndOrderIndependent() {
        val a = encodeSnapshot(linkedMapOf("b" to "2", "a" to "1", "c" to "3"))
        val b = encodeSnapshot(linkedMapOf("c" to "3", "a" to "1", "b" to "2"))
        // Same logical content -> identical bytes regardless of insertion order.
        assertContentEquals(a, b)
        // No timestamp/comment line, so re-encoding is byte-identical (enables the
        // skip-if-unchanged no-op).
        assertContentEquals(a, encodeSnapshot(mapOf("a" to "1", "b" to "2", "c" to "3")))
        assertTrue(a.toString(Charsets.ISO_8859_1).lineSequence().none { it.startsWith("#") })
    }

    @Test
    fun decodeReversesEncodeIncludingSpecialCharacters() {
        val m = mapOf(
            Keys.ServerUrl to "https://ex.com:8443/base",
            Keys.Cookie to "a=b; c=d\\e f#g!h",
            Keys.Username to "naïve 测试 user",
            "multiline" to "line1\nline2\ttab",
        )
        assertEquals(m, decodeSnapshot(encodeSnapshot(m)))
    }

    @Test
    fun decodeReadsLegacyPropertiesStoreFormat() {
        // A file written the OLD way (Properties.store with a comment + timestamp)
        // must still load.
        val legacy = Properties().apply {
            setProperty(Keys.ServerUrl, "https://old.example")
            setProperty(Keys.Remember, "1")
        }
        val bytes = ByteArrayOutputStream().use { legacy.store(it, "hero-app"); it.toByteArray() }
        val decoded = decodeSnapshot(bytes)
        assertEquals("https://old.example", decoded[Keys.ServerUrl])
        assertEquals("1", decoded[Keys.Remember])
    }

    @Test
    fun decodeDropsOverLongEntries() {
        val huge = "x".repeat(20 * 1024) // > MAX_VALUE_CHARS
        val decoded = decodeSnapshot(encodeSnapshot(mapOf("ok" to "v", "big" to huge)))
        assertEquals("v", decoded["ok"])
        assertFalse(decoded.containsKey("big"))
    }

    // ---- DesktopSettingsIo: atomic write + recoverable load ------------------

    @Test
    fun persistThenLoadRoundTripsAtomically() = runBlocking {
        val dir = Files.createTempDirectory("hero-settings").toFile()
        val io = DesktopSettingsIo(File(dir, "settings.properties"))
        val values = mapOf(Keys.ServerUrl to "https://x", Keys.Username to "sam", Keys.Remember to "1")
        assertTrue(io.persist(values))
        assertEquals(values, io.load())
        // No stray temp files left behind, no comment lines written.
        assertEquals(listOf("settings.properties"), dir.list()!!.toList())
        assertTrue(File(dir, "settings.properties").readLines().none { it.startsWith("#") })
    }

    @Test
    fun loadQuarantinesOversizedFileToRecoverableDefault() {
        val dir = Files.createTempDirectory("hero-settings").toFile()
        val f = File(dir, "settings.properties")
        f.writeBytes(ByteArray(70 * 1024) { 'a'.code.toByte() }) // > MAX_FILE_BYTES
        val io = DesktopSettingsIo(f)
        assertEquals(emptyMap(), io.load())
        // The bad file is renamed aside (recoverable), not left to fail again.
        assertFalse(f.exists())
        assertTrue(dir.list()!!.any { it.startsWith("settings.properties.corrupt-") })
    }

    @Test
    fun loadQuarantinesMalformedFileWithoutThrowing() {
        val dir = Files.createTempDirectory("hero-settings").toFile()
        val f = File(dir, "settings.properties")
        f.writeText("server_url=\\uZZZZ\n") // malformed unicode escape -> Properties.load throws
        val io = DesktopSettingsIo(f)
        assertEquals(emptyMap(), io.load()) // recovered, no exception escapes
        assertFalse(f.exists())
    }

    @Test
    fun loadIsEmptyWhenFileAbsent() {
        val dir = Files.createTempDirectory("hero-settings").toFile()
        assertEquals(emptyMap(), DesktopSettingsIo(File(dir, "settings.properties")).load())
    }

    // ---- Settings transaction core (fake IO) ---------------------------------

    private class FakeIo(initial: Map<String, String> = emptyMap()) : SettingsIo {
        var stored = HashMap(initial)
        var persistCalls = 0
        var failNext = false
        override fun load(): Map<String, String> = HashMap(stored)
        override suspend fun persist(values: Map<String, String>): Boolean {
            persistCalls++
            if (failNext) return false
            stored = HashMap(values)
            return true
        }
    }

    @Test
    fun updateSkipsWriteWhenNothingChanges() = runBlocking {
        val io = FakeIo(mapOf("a" to "1"))
        val s = Settings(io)
        assertTrue(s.update { it["a"] = "1" }) // same value
        assertEquals(0, io.persistCalls) // no-op skipped
        assertEquals("1", s.getString("a"))
    }

    @Test
    fun updateCommitsAsOneBatchAndPublishes() = runBlocking {
        val io = FakeIo()
        val s = Settings(io)
        assertTrue(s.update { it["server"] = "u"; it["user"] = "sam"; it["remember"] = "1" })
        assertEquals(1, io.persistCalls) // multi-key change = ONE persist
        assertEquals("sam", s.getString("user")) // observable snapshot updated
        assertEquals(mapOf("server" to "u", "user" to "sam", "remember" to "1"), io.stored)
    }

    @Test
    fun failedWriteKeepsOldPublishedAndOnDiskState() = runBlocking {
        val io = FakeIo(mapOf("a" to "1"))
        val s = Settings(io)
        io.failNext = true
        assertFalse(s.update { it["a"] = "2" })
        assertEquals("1", s.getString("a")) // old published snapshot kept
        assertEquals(mapOf("a" to "1"), io.stored) // old on-disk kept
    }

    // ---- commit owner vs caller cancellation ---------------------------------

    // BarrierIo makes the cancellation window deterministic: `persist` performs
    // its durable commit (stored is replaced), signals `committed`, then parks on
    // `release` — exactly the platform actuals' shape, where the file rename /
    // SharedPreferences commit has already happened but the suspension has not
    // resumed yet.
    private class BarrierIo : SettingsIo {
        var stored: Map<String, String> = emptyMap()
        var persistCalls = 0
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override fun load(): Map<String, String> = emptyMap()
        override suspend fun persist(values: Map<String, String>): Boolean {
            persistCalls++
            stored = HashMap(values) // the durable commit happens HERE
            committed.complete(Unit)
            release.await() // hold the resume until the test decides
            return true
        }
    }

    // The barrier regression from the review: the underlying commit completes,
    // the CALLER is cancelled before persist resumes, and memory must still
    // publish the same generation the disk now holds. Under the old cancellable
    // commit segment the success was dropped on resume: disk new, snapshot old,
    // and the follow-up update overwrote the just-persisted key from the stale
    // in-memory base.
    @Test
    fun commitThenCancelBeforeResumeStillPublishes() = runBlocking {
        val io = BarrierIo()
        val s = Settings(io)
        val job = launch { s.update { it["k"] = "v" } }
        io.committed.await() // barrier: bytes are durable, persist not yet resumed
        job.cancel() // the composition scope goes away
        io.release.complete(Unit)
        job.join()
        // Memory and disk publish the SAME generation.
        assertEquals("v", s.getString("k"))
        assertEquals("v", io.stored["k"])
        // And a later update builds on the COMMITTED snapshot — it must not
        // overwrite the just-persisted key from a stale base.
        assertTrue(s.update { it["other"] = "1" })
        assertEquals("v", io.stored["k"])
        assertEquals("1", io.stored["other"])
    }

    // The counterpart boundary: a mutation cancelled while still QUEUED on the
    // write lock (not yet admitted to the commit segment) persists nothing and
    // publishes nothing — caller cancellation cancels the waiting, never the
    // owner.
    @Test
    fun cancelWhileQueuedPersistsNothing() = runBlocking {
        val io = BarrierIo()
        val s = Settings(io)
        val first = launch { s.update { it["a"] = "1" } }
        io.committed.await() // first holds the lock inside its commit segment
        val second = launch { s.update { it["b"] = "2" } }
        yield() // let second suspend on the write lock
        second.cancel()
        second.join()
        io.release.complete(Unit)
        first.join()
        assertEquals(mapOf("a" to "1"), io.stored) // only the admitted commit landed
        assertEquals(1, io.persistCalls)
        assertNull(s.getString("b"))
    }

    @Test
    fun forgetClearsPublishedSnapshotImmediately() = runBlocking {
        val io = FakeIo(mapOf(Keys.ServerUrl to "https://x", Keys.Username to "sam", Keys.Remember to "1", Keys.Cookie to "c"))
        val s = Settings(io)
        assertEquals("https://x", s.getString(Keys.ServerUrl))
        // Forget commits ONE batch clearing every saved-login key, so the
        // Saved-login gate (server blank && user blank) flips to "Nothing saved".
        assertTrue(s.update { it.remove(Keys.ServerUrl); it.remove(Keys.Username); it.remove(Keys.Remember); it.remove(Keys.Cookie) })
        assertEquals(null, s.getString(Keys.ServerUrl))
        assertEquals(null, s.getString(Keys.Username))
        assertEquals(null, s.getString(Keys.Remember))
        assertEquals(1, io.persistCalls) // whole clear = ONE persist
    }
}
