package io.hero.app

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.jar.Manifest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// updateAssetSuffix picks the per-OS desktop jar the CI publishes
// (hero-app-linux.jar / hero-app-macos.jar / hero-app-windows.jar).
actual fun updateAssetSuffix(): String {
    val os = System.getProperty("os.name").lowercase()
    val tag = when {
        os.contains("mac") -> "macos"
        os.contains("win") -> "windows"
        else -> "linux"
    }
    return "$tag.jar"
}

// UpdateMainClass is the manifest identity every published desktop jar carries;
// a mis-served body that happens to be a zip still fails verification on it.
const val UpdateMainClass = "io.hero.app.MainKt"

// Desktop is a portable jar with no installer framework to hand the update to,
// and nothing owns the launcher path we would have to overwrite. This
// downloads the new jar, verifies it is really this app's jar at the
// advertised version (see verifyJarPackage), publishes it durably, and returns
// the exact path for the user to run / swap in. The running app is left alone.
actual suspend fun installUpdate(info: UpdateInfo, onProgress: (Long, Long?) -> Unit): String {
    // No HttpTimeout: the download is progress-streamed and may take minutes;
    // a STALLED stream is bounded by the per-read idle budget instead.
    val client = heroHttpClient()
    try {
        // Every blocking file operation (temp create, 64 KiB writes, fsync,
        // move, jar verification) runs on Dispatchers.IO — installUpdate is
        // launched from a Compose UI scope and must never block a frame.
        // Progress callbacks land here on IO threads; Compose snapshot state
        // writes are thread-safe, and streamDownload already throttles them.
        val published = withContext(Dispatchers.IO) {
            // Land the jar somewhere persistent and easy to find (temp dirs get
            // swept). The temp file shares this directory so the publish is a
            // rename within one filesystem — i.e. atomic.
            val dir = File(System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir"))
            val finalFile = File(dir, "hero-app-${info.version}.jar")
            val tmp = File.createTempFile("hero-app-${info.version}-", ".jar.part", dir)
            try {
                tmp.outputStream().use { fo ->
                    downloadUpdateAsset(client, info.downloadUrl, onProgress) { b, n -> fo.write(b, 0, n) }
                    // flush() only hands bytes to the OS; sync() makes the DATA
                    // durable before the move can publish the file's name.
                    fo.flush()
                    fo.fd.sync()
                }
                // Transfer checks prove one self-consistent transfer, not that
                // the body is this app's jar: verify the container before it
                // can be published under a runnable name.
                verifyJarPackage(tmp, info.version)
                publishDurably(tmp, finalFile)
            } finally {
                // No-op after a successful move (tmp is gone); on any failure or
                // cancel this deletes the partial so nothing bogus is left behind.
                tmp.delete()
            }
        }
        return "Downloaded v${info.version} → ${published.absolutePath} " +
            "(complete: size, archive integrity and version checked) — quit HERO and run: " +
            "java -jar \"${published.absolutePath}\" (replace your current jar with this file to keep the update)"
    } catch (e: CancellationException) {
        // Structured cancellation must propagate — the temp is already cleaned
        // up above; normalizing this into an error string would detach the
        // download from the caller's lifetime.
        throw e
    } catch (e: Throwable) {
        return "update failed: ${e.message}"
    } finally {
        client.close()
    }
}

// verifyJarPackage proves the downloaded bytes are THIS app's desktop jar at
// the advertised version, before anything is published under a runnable name:
//   1. the zip central directory parses (a truncated stream or an HTML error
//      body dies here even when no Content-Length was declared);
//   2. the manifest names this app's main class — the executable-jar identity
//      every published desktop jar carries;
//   3. io/hero/app/VersionKt.class embeds [expectedVersion] in its constant
//      pool. That class is the artifact's machine-readable version identity:
//      proguard-desktop.pro keeps it explicitly and releaseArtifactSmoke reads
//      AppVersion from it on every release, so a published jar can't lack it;
//   4. every entry inflates end to end with its CRC verified (ZipFile streams
//      alone don't CRC-check), so a mid-file corruption also fails closed.
// Signature/publisher trust is out of scope here — this is installation
// correctness ("is this the complete advertised package"), not code signing.
internal fun verifyJarPackage(file: File, expectedVersion: String) {
    try {
        ZipFile(file).use { zip ->
            val mfEntry = zip.getEntry("META-INF/MANIFEST.MF")
                ?: throw UpdateDownloadException("downloaded jar has no manifest — not the HERO app jar")
            val mainClass = zip.getInputStream(mfEntry).use { Manifest(it) }.mainAttributes.getValue("Main-Class")
            if (mainClass != UpdateMainClass) {
                throw UpdateDownloadException("downloaded jar's main class is '$mainClass' — not the HERO app jar")
            }
            val versionEntry = zip.getEntry("io/hero/app/VersionKt.class")
                ?: throw UpdateDownloadException("downloaded jar carries no version identity (VersionKt.class missing)")
            val versionClass = zip.getInputStream(versionEntry).use { it.readBytes() }
            if (!containsBytes(versionClass, constantPoolUtf8(expectedVersion))) {
                throw UpdateDownloadException("downloaded jar does not embed v$expectedVersion")
            }
        }
        // Full sequential drain: ZipInputStream verifies each entry's CRC at
        // entry end, which ZipFile's random-access streams never do.
        ZipInputStream(file.inputStream().buffered()).use { zin ->
            val sink = ByteArray(64 * 1024)
            while (zin.nextEntry != null) {
                @Suppress("ControlFlowWithEmptyBody")
                while (zin.read(sink) != -1) {
                }
                zin.closeEntry()
            }
        }
    } catch (e: UpdateDownloadException) {
        throw e
    } catch (e: IOException) { // ZipException included: not a valid/complete archive
        throw UpdateDownloadException("downloaded jar failed verification: ${e.message}")
    }
}

// constantPoolUtf8 is the JVM class-file CONSTANT_Utf8_info encoding of [s]:
// tag 0x01, big-endian u2 length, then (for ASCII version strings) the bytes
// themselves. A const val String's value is stored exactly this way in the
// declaring class's constant pool, so "VersionKt.class contains this pattern"
// == "the jar's baked-in AppVersion is this version".
internal fun constantPoolUtf8(s: String): ByteArray {
    val utf = s.encodeToByteArray()
    require(utf.size <= 0xFFFF) { "constant too long" }
    return byteArrayOf(0x01, (utf.size ushr 8).toByte(), (utf.size and 0xFF).toByte()) + utf
}

// containsBytes: naive subsequence search (needles here are ~10 bytes against
// a few-KiB class file; no need for anything cleverer).
internal fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > haystack.size) return false
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}

// publishDurably commits the completed, verified, fsynced temp next to [dest]
// and fsyncs the parent directory so the published NAME survives power loss
// (the data was synced before the move). Returns the file actually published —
// usually dest itself, or a fresh sibling when dest is genuinely irreplaceable.
internal fun publishDurably(tmp: File, dest: File): File {
    val published = moveIntoPlace(tmp.toPath(), dest.toPath())
    fsyncDir(published.parent)
    return published.toFile()
}

// moveIntoPlace is the verified replace protocol:
//   1. ATOMIC_MOVE — POSIX rename(2) semantics on Linux/macOS (atomically
//      replaces an existing dest); on Windows an atomic replacing move.
//   2. AtomicMoveNotSupportedException — the provider genuinely can't rename
//      atomically: fall back to a plain documented REPLACE_EXISTING.
//   3. Any other I/O failure with an EXISTING dest (Windows with the old jar
//      open/locked, FileAlreadyExistsException providers, …): NEVER delete the
//      complete old file to retry — that is exactly what breaks "complete old
//      file or complete new file". Publish the complete new file under a fresh
//      sibling name instead ("hero-app-1.2.3-1.jar", bounded attempts).
// A failure with NO existing dest is a real I/O error and propagates.
internal fun moveIntoPlace(src: Path, dst: Path): Path {
    try {
        return Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        return Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        if (!Files.exists(dst)) throw e
    }
    for (n in 1..4) {
        val alt = dst.resolveSibling(numberedSibling(dst.fileName.toString(), n))
        if (Files.exists(alt)) continue
        try {
            return Files.move(src, alt, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            return Files.move(src, alt, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            continue // e.g. lost a race to the name; try the next one
        }
    }
    throw UpdateDownloadException("could not publish the downloaded update next to $dst")
}

// numberedSibling("hero-app-1.2.3.jar", 2) == "hero-app-1.2.3-2.jar".
internal fun numberedSibling(name: String, n: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "$name-$n" else name.substring(0, dot) + "-$n" + name.substring(dot)
}

// fsyncDir makes a just-renamed directory entry durable. Best effort by
// design: Windows cannot open a directory channel at all (and journals file
// metadata anyway), and some providers refuse force() on directories — the
// move above already gives atomic crash consistency; the directory sync only
// widens durability to power loss, so refusal must not fail the update.
internal fun fsyncDir(dir: Path?) {
    if (dir == null) return
    try {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
        // best effort — see above
    }
}
