package io.hero.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Ceilings that keep a hand-edited, truncated, or hostile file from turning into
// unbounded work or blocking launch. A file over the total cap — or any single
// over-long key/value inside it — is treated as recoverable rather than loaded.
private const val MAX_FILE_BYTES = 64 * 1024
private const val MAX_KEY_CHARS = 256
private const val MAX_VALUE_CHARS = 16 * 1024

// encodeSnapshot renders the snapshot in java.util.Properties syntax but
// DETERMINISTICALLY: keys sorted, ISO-8859-1 with \uXXXX escapes (so the bytes
// are pure ASCII and stable) and — unlike Properties.store — no leading
// timestamp comment. Identical settings therefore produce identical bytes, so an
// unchanged commit is a true no-op. Output stays loadable by Properties.load,
// and older files written by the previous Properties.store path still decode.
internal fun encodeSnapshot(values: Map<String, String>): ByteArray {
    val props = Properties()
    for ((k, v) in values) props.setProperty(k, v)
    val raw = ByteArrayOutputStream().use { out ->
        props.store(out, null) // ISO-8859-1, \uXXXX-escaped, plus a #<date> comment line
        out.toString("ISO-8859-1")
    }
    val body = raw.split(Regex("\\r?\\n"))
        .filter { it.isNotEmpty() && !it.startsWith("#") } // drop the timestamp comment
        .sorted()
        .joinToString("") { it + "\n" }
    return body.toByteArray(Charsets.ISO_8859_1)
}

// decodeSnapshot parses either the deterministic encoding above or a legacy
// Properties.store file. Over-long keys/values are dropped (bounded recovery);
// malformed input (e.g. a bad \u escape) throws and is handled by the loader as
// a corrupt file.
internal fun decodeSnapshot(bytes: ByteArray): Map<String, String> {
    val props = Properties()
    ByteArrayInputStream(bytes).use { props.load(it) }
    val out = LinkedHashMap<String, String>()
    for ((k, v) in props) {
        val key = k as? String ?: continue
        val value = v as? String ?: continue
        if (key.length <= MAX_KEY_CHARS && value.length <= MAX_VALUE_CHARS) out[key] = value
    }
    return out
}

internal class DesktopSettingsIo(
    private val file: File = File(System.getProperty("user.home"), ".hero-app/settings.properties"),
) : SettingsIo {

    // Loads once at startup. NEVER throws: an oversized, unreadable, or malformed
    // file is renamed aside (quarantined) and we start from an empty default, so a
    // bad file can't block the first frame.
    override fun load(): Map<String, String> = runCatching {
        if (!file.isFile) return emptyMap()
        if (file.length() > MAX_FILE_BYTES) { quarantine(); return emptyMap() }
        decodeSnapshot(file.readBytes())
    }.getOrElse { quarantine(); emptyMap() }

    // Persists the WHOLE snapshot atomically, off the UI thread: write the
    // deterministic bytes to a same-directory temp file, flush + fsync, then
    // atomically rename over the real file (falling back to a plain replace where
    // ATOMIC_MOVE is unsupported). On any failure the old file is left intact and
    // false is returned.
    override suspend fun persist(values: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching { encodeSnapshot(values) }.getOrNull() ?: return@withContext false
        if (bytes.size > MAX_FILE_BYTES) return@withContext false
        runCatching {
            val dir = file.parentFile
            dir?.mkdirs()
            val tmp = File.createTempFile("settings", ".tmp", dir)
            try {
                FileOutputStream(tmp).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    fos.fd.sync()
                }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }.isSuccess
    }

    private fun quarantine() {
        runCatching {
            if (file.isFile) {
                file.renameTo(File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis()))
            }
        }
    }
}

actual fun defaultSettingsIo(): SettingsIo = DesktopSettingsIo()
