package io.hero.app

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

// Desktop is a portable jar with no installer framework to hand the update to,
// and nothing owns the launcher path we would have to overwrite. Rather than the
// old "write to temp, spawn `java -jar`, exitProcess(0)" — which lost both
// instances if the new jar died young and never actually replaced the shortcut's
// jar — this downloads and *fully verifies* the new jar, publishes it atomically,
// and returns the exact path for the user to run / swap in. The running app is
// left alone (no exit).
actual suspend fun installUpdate(info: UpdateInfo, onProgress: (Long, Long?) -> Unit): String {
    // No HttpTimeout: the download is progress-streamed and may take minutes.
    val client = heroHttpClient()
    try {
        // Land the jar somewhere persistent and easy to find (temp dirs get
        // swept). The temp file shares this directory so the publish is a rename
        // within one filesystem — i.e. atomic.
        val dir = File(System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir"))
        val finalFile = File(dir, "hero-app-${info.version}.jar")
        val tmp = File.createTempFile("hero-app-${info.version}-", ".jar.part", dir)
        try {
            client.prepareGet(info.downloadUrl) {
                header("Accept", "application/octet-stream")
            }.execute { resp ->
                val expected = validateDownloadStatus(resp.status.value, resp.contentLength())
                val received = tmp.outputStream().use { fo ->
                    val r = streamDownload(resp.bodyAsChannel(), expected, onProgress) { b, n -> fo.write(b, 0, n) }
                    fo.flush() // push bytes to the OS before we size-check and rename
                    r
                }
                validateDownloadSize(received, expected)
            }
            // Only now, with a complete+verified file, publish it onto the final
            // path in one atomic move.
            publishAtomically(tmp, finalFile)
        } finally {
            // No-op after a successful move (tmp is gone); on any failure or
            // cancel this deletes the partial so nothing bogus is left behind.
            tmp.delete()
        }
        return "Downloaded & verified v${info.version} → ${finalFile.absolutePath} — quit HERO and run: " +
            "java -jar \"${finalFile.absolutePath}\" (replace your current jar with this file to keep the update)"
    } catch (e: Throwable) {
        return "update failed: ${e.message}"
    } finally {
        client.close()
    }
}

// publishAtomically renames the completed temp onto the target in a single move.
// Prefers ATOMIC_MOVE (POSIX rename semantics); falls back to a plain replace
// only where the platform can't do an atomic rename or the target already exists
// (Windows). A real I/O error still propagates.
private fun publishAtomically(tmp: File, dest: File) {
    val src = tmp.toPath()
    val dst = dest.toPath()
    try {
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        when (e) {
            is AtomicMoveNotSupportedException, is FileAlreadyExistsException ->
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            else -> throw e
        }
    }
}
