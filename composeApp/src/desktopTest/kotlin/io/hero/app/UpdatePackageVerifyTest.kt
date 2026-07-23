package io.hero.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Covers what the transfer checks CANNOT prove: that the downloaded bytes are
// this app's jar at the advertised version (verifyJarPackage) and that the
// commit protocol publishes complete-new without ever destroying complete-old
// (publishDurably/moveIntoPlace). Jars are built in-memory with the real zip
// machinery so truncation/corruption behave exactly like a broken download.
class UpdatePackageVerifyTest {
    private val payloadMarker = "HERO-PAYLOAD-MARKER".encodeToByteArray()

    // A minimal but REAL jar: manifest with a main class, a fake VersionKt.class
    // whose bytes embed the constant-pool encoding of [version], and a STORED
    // payload entry (uncompressed, so a corruption test can flip a byte at a
    // findable offset and hit the CRC check, not the inflater).
    private fun jarBytes(
        version: String = "9.9.9",
        mainClass: String? = UpdateMainClass,
        withVersionClass: Boolean = true,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            if (mainClass != null) {
                zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zos.write("Manifest-Version: 1.0\r\nMain-Class: $mainClass\r\n\r\n".encodeToByteArray())
                zos.closeEntry()
            }
            if (withVersionClass) {
                zos.putNextEntry(ZipEntry("io/hero/app/VersionKt.class"))
                // Fake class bytes: header-ish filler + the constant-pool UTF-8
                // encoding of the version — what the verifier scans for.
                zos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                zos.write(constantPoolUtf8(version))
                zos.write(ByteArray(64) { 3 })
                zos.closeEntry()
            }
            val payload = payloadMarker + ByteArray(100_000) { (it % 13).toByte() }
            val stored = ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED
                size = payload.size.toLong()
                compressedSize = payload.size.toLong()
                crc = CRC32().apply { update(payload) }.value
            }
            zos.putNextEntry(stored)
            zos.write(payload)
            zos.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun tempDir(): File = Files.createTempDirectory("hero-update-test").toFile()

    private fun writeFile(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).also { it.writeBytes(bytes) }

    @Test
    fun acceptsACompleteHeroJarAtTheAdvertisedVersion() {
        val dir = tempDir()
        try {
            verifyJarPackage(writeFile(dir, "ok.jar", jarBytes(version = "9.9.9")), "9.9.9")
        } finally {
            dir.deleteRecursively()
        }
    }

    // The no-Content-Length / mid-stream-interruption backstop: a truncated
    // archive has no readable central directory and must fail verification.
    @Test
    fun rejectsATruncatedArchive() {
        val dir = tempDir()
        try {
            val whole = jarBytes()
            val truncated = writeFile(dir, "trunc.jar", whole.copyOf(whole.size - 40))
            assertFailsWith<UpdateDownloadException> { verifyJarPackage(truncated, "9.9.9") }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsANonArchiveBody() {
        val dir = tempDir()
        try {
            val html = writeFile(dir, "err.jar", "<html>rate limited</html>".encodeToByteArray())
            assertFailsWith<UpdateDownloadException> { verifyJarPackage(html, "9.9.9") }
        } finally {
            dir.deleteRecursively()
        }
    }

    // "Some jar" is not enough: the version baked into VersionKt must be the
    // advertised one, and the manifest must name THIS app's main class.
    @Test
    fun rejectsWrongVersionOrForeignJar() {
        val dir = tempDir()
        try {
            val stale = writeFile(dir, "stale.jar", jarBytes(version = "9.9.8"))
            val wrongVersion = assertFailsWith<UpdateDownloadException> { verifyJarPackage(stale, "9.9.9") }
            assertTrue(wrongVersion.message!!.contains("embed"), "got: ${wrongVersion.message}")

            val foreign = writeFile(dir, "foreign.jar", jarBytes(mainClass = "com.other.MainKt"))
            val wrongApp = assertFailsWith<UpdateDownloadException> { verifyJarPackage(foreign, "9.9.9") }
            assertTrue(wrongApp.message!!.contains("main class"), "got: ${wrongApp.message}")

            val unidentified = writeFile(dir, "noid.jar", jarBytes(withVersionClass = false))
            assertFailsWith<UpdateDownloadException> { verifyJarPackage(unidentified, "9.9.9") }
        } finally {
            dir.deleteRecursively()
        }
    }

    // A mid-file corruption leaves the central directory readable; only the
    // full CRC drain catches it.
    @Test
    fun rejectsACorruptedEntryByCrc() {
        val dir = tempDir()
        try {
            val bytes = jarBytes()
            var at = -1
            outer@ for (i in 0..bytes.size - payloadMarker.size) {
                for (j in payloadMarker.indices) {
                    if (bytes[i + j] != payloadMarker[j]) continue@outer
                }
                at = i
                break
            }
            assertTrue(at > 0, "payload marker must be findable in the STORED entry")
            bytes[at + payloadMarker.size + 10] = bytes[at + payloadMarker.size + 10].plus(1).toByte()
            val corrupted = writeFile(dir, "corrupt.jar", bytes)
            assertFailsWith<UpdateDownloadException> { verifyJarPackage(corrupted, "9.9.9") }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- Commit protocol -----------------------------------------------------

    @Test
    fun publishReplacesAnExistingCompleteTarget() {
        val dir = tempDir()
        try {
            val dest = writeFile(dir, "hero-app-1.2.3.jar", "OLD".encodeToByteArray())
            val tmp = writeFile(dir, "hero-app-1.2.3.jar.part", "NEW".encodeToByteArray())
            val published = publishDurably(tmp, dest)
            assertEquals(dest.absolutePath, published.absolutePath)
            assertContentEquals("NEW".encodeToByteArray(), dest.readBytes())
            assertFalse(tmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // When the target is genuinely irreplaceable (here: the name is held by a
    // non-empty directory, the same shape as a locked file on Windows), the
    // old target must be left UNTOUCHED and the complete new file published
    // under a fresh numbered sibling.
    @Test
    fun irreplaceableTargetPublishesAFreshSiblingWithoutDestroyingIt() {
        val dir = tempDir()
        try {
            val dest = File(dir, "hero-app-1.2.3.jar")
            check(dest.mkdir())
            val occupant = writeFile(dest, "keep.txt", "old artifact".encodeToByteArray())
            val tmp = writeFile(dir, "hero-app-1.2.3.jar.part", "NEW".encodeToByteArray())

            val published = publishDurably(tmp, dest)

            assertEquals("hero-app-1.2.3-1.jar", published.name)
            assertContentEquals("NEW".encodeToByteArray(), published.readBytes())
            assertTrue(dest.isDirectory, "the irreplaceable old target must not be deleted for a retry")
            assertContentEquals("old artifact".encodeToByteArray(), occupant.readBytes())
            assertFalse(tmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // A failure with NO existing target is a real I/O error and propagates
    // as-is (nothing to fall back to, nothing was destroyed).
    @Test
    fun realIoFailurePropagatesWhenNoTargetExists() {
        val dir = tempDir()
        try {
            val tmp = writeFile(dir, "src.part", "NEW".encodeToByteArray())
            val dest = File(File(dir, "missing-subdir"), "hero-app-1.2.3.jar")
            assertFailsWith<IOException> { publishDurably(tmp, dest) }
            assertTrue(tmp.exists(), "the temp must survive for the caller's cleanup path")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun numberedSiblingKeepsTheExtension() {
        assertEquals("hero-app-1.2.3-1.jar", numberedSibling("hero-app-1.2.3.jar", 1))
        assertEquals("hero-app-1.2.3-2.jar", numberedSibling("hero-app-1.2.3.jar", 2))
        assertEquals("noext-3", numberedSibling("noext", 3))
    }
}
