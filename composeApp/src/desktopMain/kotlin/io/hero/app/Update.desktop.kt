package io.hero.app

import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import java.io.File
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

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

// Downloads the new jar (streaming progress), then relaunches onto it and
// exits — an actual in-place update, not a "here's the file" note. If the
// relaunch can't start (unusual java.home layouts), it falls back to telling
// you how to run the downloaded jar.
actual suspend fun installUpdate(info: UpdateInfo, onProgress: (Long, Long?) -> Unit): String {
    // No HttpTimeout: the download is progress-streamed and may take minutes.
    val client = heroHttpClient()
    try {
        val out = File(System.getProperty("java.io.tmpdir"), "hero-app-${info.version}.jar")
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
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), exe)
        return try {
            ProcessBuilder(java.absolutePath, "-jar", out.absolutePath).start()
            delay(600) // let the child get on its feet before we vanish
            exitProcess(0)
        } catch (_: Throwable) {
            "downloaded v${info.version} → run: java -jar ${out.absolutePath}"
        }
    } catch (e: Throwable) {
        return "update failed: ${e.message}"
    } finally {
        client.close()
    }
}
