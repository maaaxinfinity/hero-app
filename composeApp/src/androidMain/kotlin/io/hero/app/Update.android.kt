package io.hero.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.File

// appContext is set by MainActivity so the updater can reach the package
// installer and FileProvider without threading a Context through common code.
var appContext: Context? = null

actual fun updateAssetSuffix(): String = ".apk"

actual suspend fun installUpdate(info: UpdateInfo, onProgress: (Long, Long?) -> Unit): String {
    val ctx = appContext ?: return "no app context"
    val client = HttpClient()
    try {
        val out = File(ctx.cacheDir, "hero-update.apk")
        client.prepareGet(info.downloadUrl) {
            header("Accept", "application/octet-stream")
        }.execute { resp ->
            val total = resp.contentLength()
            val ch = resp.bodyAsChannel()
            out.outputStream().use { fo ->
                val buf = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    if (n > 0) {
                        fo.write(buf, 0, n)
                        received += n
                        onProgress(received, total)
                    }
                }
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "io.hero.app.fileprovider", out)
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
