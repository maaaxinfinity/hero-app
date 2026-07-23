package io.hero.app

import android.content.Context
import android.content.Intent
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// appContext is set by MainActivity so the updater can reach the package
// installer and FileProvider without threading a Context through common code.
var appContext: Context? = null

actual fun updateAssetSuffix(): String = ".apk"

actual suspend fun installUpdate(info: UpdateInfo, onProgress: (Long, Long?) -> Unit): String {
    val ctx = appContext ?: return "no app context"
    // No HttpTimeout: the download is progress-streamed and may take minutes;
    // a STALLED stream is bounded by the per-read idle budget instead.
    val client = heroHttpClient()
    try {
        // Every blocking file operation (temp create, 64 KiB writes, fsync,
        // rename) runs on Dispatchers.IO — installUpdate is launched from a
        // Compose UI scope, and Android main must never execute disk I/O.
        // Progress callbacks land here on IO threads; Compose snapshot state
        // writes are thread-safe, and streamDownload already throttles them.
        val finalFile = withContext(Dispatchers.IO) {
            // Download into a same-directory temp first; the installer only
            // ever sees the final path after a complete, verified commit.
            val dest = File(ctx.cacheDir, "hero-update.apk")
            val tmp = File.createTempFile("hero-update-", ".apk.part", ctx.cacheDir)
            try {
                tmp.outputStream().use { fo ->
                    downloadUpdateAsset(client, info.downloadUrl, onProgress) { b, n -> fo.write(b, 0, n) }
                    // flush() only hands bytes to the OS; sync() makes the DATA
                    // durable before the rename can publish the file's name.
                    fo.flush()
                    fo.fd.sync()
                }
                // Transfer checks prove one self-consistent transfer, not that
                // the body is an installable APK: parse it and pin its identity
                // before anything reaches the installer path.
                verifyApkPackage(ctx, tmp, info)
                publishDurably(tmp, dest)
                dest
            } finally {
                // No-op after a successful move; deletes the partial on any
                // failure/cancel so a broken APK is never handed to the installer.
                tmp.delete()
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "io.hero.app.fileprovider", finalFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return "installing v${info.version}…"
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

// verifyApkPackage refuses to publish anything the platform cannot parse as
// this app's APK at the advertised version. A 200 with matching sizes can
// still be a mis-served body; PackageManager parsing is the real "container
// opens + expected metadata" gate (signature checks stay with the installer).
private fun verifyApkPackage(ctx: Context, file: File, info: UpdateInfo) {
    @Suppress("DEPRECATION") // string-path overload: the API-33+ replacement is unavailable at minSdk 24
    val pi = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        ?: throw UpdateDownloadException("downloaded file is not a parseable APK")
    if (pi.packageName != ctx.packageName) {
        throw UpdateDownloadException("downloaded APK is '${pi.packageName}', expected '${ctx.packageName}'")
    }
    if (pi.versionName != info.version) {
        throw UpdateDownloadException("downloaded APK reports v${pi.versionName}, expected v${info.version}")
    }
}

// publishDurably renames the completed, fsynced temp onto the target in ONE
// move. On Android/Linux File.renameTo is rename(2): atomic within one
// filesystem (tmp and dest share cacheDir) and it replaces an existing dest.
// java.nio.file is avoided deliberately — it is API 26+, but minSdk is 24.
// There is intentionally NO delete-dest-and-retry: destroying the old complete
// file to retry a failed rename is exactly what breaks the "complete old file
// or complete new file" invariant. One attempt; on failure the old dest (if
// any) is untouched and the caller discards the temp. The parent directory is
// then fsynced so the published NAME survives power loss, not just the bytes.
private fun publishDurably(tmp: File, dest: File) {
    if (!tmp.renameTo(dest)) throw UpdateDownloadException("could not publish the downloaded update")
    fsyncDir(dest.parentFile)
}

// fsyncDir makes a just-renamed directory entry durable (fsync on the directory
// fd — android.system.Os exists since API 21, unlike java.nio.file). Best
// effort by design: the rename above is already atomic for crash consistency
// within the running system; the directory sync only widens durability to
// power loss, so an esoteric filesystem refusing it must not fail the update.
private fun fsyncDir(dir: File?) {
    if (dir == null) return
    try {
        val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (_: Exception) {
        // best effort — see above
    }
}
