package io.hero.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File

// appContext is set by MainActivity so the updater can reach the package
// installer and FileProvider without threading a Context through common code.
var appContext: Context? = null

actual fun updateAssetSuffix(): String = ".apk"

actual suspend fun installUpdate(info: UpdateInfo, token: String): String {
    val ctx = appContext ?: return "no app context"
    val client = HttpClient()
    try {
        val out = File(ctx.cacheDir, "hero-update.apk")
        client.prepareGet(info.asset.url) {
            header("Accept", "application/octet-stream")
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }.execute { resp ->
            out.outputStream().use { fo -> resp.bodyAsChannel().copyTo(fo) }
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
