package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import java.io.File

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

actual suspend fun installUpdate(info: UpdateInfo, token: String): String {
    val client = HttpClient()
    try {
        val out = File(System.getProperty("java.io.tmpdir"), "hero-app-${info.version}.jar")
        client.prepareGet(info.asset.url) {
            header("Accept", "application/octet-stream")
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }.execute { resp ->
            out.outputStream().use { fo -> resp.bodyAsChannel().copyTo(fo) }
        }
        return "downloaded v${info.version} → run: java -jar ${out.absolutePath}"
    } catch (e: Throwable) {
        return "update failed: ${e.message}"
    } finally {
        client.close()
    }
}
