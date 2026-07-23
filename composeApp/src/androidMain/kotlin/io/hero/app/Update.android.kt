package io.hero.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import java.io.File

// appContext is set by MainActivity so the updater can reach the package
// installer and FileProvider without threading a Context through common code.
var appContext: Context? = null

actual fun updateAssetSuffix(): String = ".apk"

actual suspend fun installUpdate(info: UpdateInfo, onProgress: (Long, Long?) -> Unit): String {
    val ctx = appContext ?: return "no app context"
    // No HttpTimeout: the download is progress-streamed and may take minutes.
    val client = heroHttpClient()
    try {
        // Download into a same-directory temp first; the installer only ever sees
        // the final path after a complete, verified commit.
        val finalFile = File(ctx.cacheDir, "hero-update.apk")
        val tmp = File.createTempFile("hero-update-", ".apk.part", ctx.cacheDir)
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
            // Complete + verified: publish onto the path the installer reads.
            publishAtomically(tmp, finalFile)
        } finally {
            // No-op after a successful move; deletes the partial on any
            // failure/cancel so a broken APK is never handed to the installer.
            tmp.delete()
        }
        val uri = FileProvider.getUriForFile(ctx, "io.hero.app.fileprovider", finalFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        return "installing v${info.version}…"
    } catch (e: Throwable) {
        return "update failed: ${e.message}"
    } finally {
        client.close()
    }
}

// publishAtomically renames the completed temp onto the target in a single move.
// On Android/Linux File.renameTo is rename(2): atomic within one filesystem
// (tmp and dest share cacheDir) and it replaces an existing dest. java.nio.file
// is avoided deliberately — it is API 26+, but this app's minSdk is 24. If the
// rename fails, retry once after removing a stale dest before giving up.
private fun publishAtomically(tmp: File, dest: File) {
    if (tmp.renameTo(dest)) return
    dest.delete()
    if (!tmp.renameTo(dest)) throw UpdateDownloadException("could not publish the downloaded update")
}
