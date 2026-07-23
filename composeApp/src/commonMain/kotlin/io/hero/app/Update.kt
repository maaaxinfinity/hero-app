package io.hero.app

import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// AppVersion is compared against the latest GitHub release tag. Bump it before
// tagging a release so the running app can tell it is out of date.
const val AppVersion = "0.5.15"
const val Repo = "maaaxinfinity/hero-app"

@Serializable
data class GhAsset(val name: String = "", val browser_download_url: String = "")

@Serializable
data class GhRelease(val tag_name: String = "", val assets: List<GhAsset> = emptyList())

data class UpdateInfo(val version: String, val downloadUrl: String)

private val updaterJson = Json { ignoreUnknownKeys = true }

// checkForUpdate queries the latest release and returns an UpdateInfo when it is
// newer than AppVersion and has an asset ending in assetSuffix (.apk / os.jar).
// No credentials: the release is PUBLIC, so the app hits the anonymous GitHub
// API and downloads via the public browser_download_url — no token to manage.
// Returns null ONLY for "genuinely up to date"; every failure mode (network,
// rate limit, missing asset) THROWS so the UI can say "check failed" instead
// of lying with "up to date".
suspend fun checkForUpdate(assetSuffix: String): UpdateInfo? {
    // Unary metadata call, so a 30s bound is safe; the download itself runs on
    // the platform installUpdate client, which sets no request timeout.
    val client = heroHttpClient {
        install(ContentNegotiation) { json(updaterJson) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    try {
        val r = client.get("https://api.github.com/repos/$Repo/releases/latest") {
            header("Accept", "application/vnd.github+json")
        }
        if (!r.status.isSuccess()) error("GitHub API HTTP ${r.status.value}")
        val rel: GhRelease = r.body()
        if (!isNewer(rel.tag_name, AppVersion)) return null
        val asset = rel.assets.firstOrNull { it.name.endsWith(assetSuffix) && it.browser_download_url.isNotEmpty() }
            ?: error("release ${rel.tag_name} has no *$assetSuffix asset")
        return UpdateInfo(rel.tag_name.removePrefix("v"), asset.browser_download_url)
    } finally {
        client.close()
    }
}

// isNewer compares dotted/dashed numeric versions (v-prefix tolerated).
fun isNewer(tag: String, current: String): Boolean {
    fun parse(s: String) = s.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
    val a = parse(tag)
    val b = parse(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

// installUpdate downloads the public asset and applies it: Android launches the
// package installer; desktop relaunches onto the new jar. onProgress streams
// (bytesReceived, totalOrNull) so the UI can show real download progress.
expect suspend fun installUpdate(info: UpdateInfo, onProgress: (Long, Long?) -> Unit = { _, _ -> }): String

// updateAssetSuffix is the release-asset name suffix this platform installs.
expect fun updateAssetSuffix(): String

// ---- Bounded, validated asset download -------------------------------------
// Shared by both platform actuals. The rule: never let a 404/rate-limit HTML
// body, a truncated stream, or a runaway chunked response reach the install
// path. The actuals stream into a same-directory temp file and only rename it
// onto the target after these checks pass, so a cancel/timeout/error leaves the
// real path untouched.

// MaxUpdateBytes caps what we will ever write for a single asset. The real APK /
// desktop jars are tens of MiB; this ceiling stops a mis-served HTML error page
// or a Content-Length-less stream that never ends from filling the disk. Kept
// generous so a legitimately larger future build still installs.
const val MaxUpdateBytes: Long = 300L * 1024 * 1024

// UpdateDownloadException marks a response that failed validation (non-2xx
// status, empty/oversized body, or a truncated stream) so the caller surfaces
// "update failed" instead of committing a bogus file to the install path.
class UpdateDownloadException(message: String) : Exception(message)

// validateDownloadStatus runs BEFORE a byte is streamed: reject a non-2xx
// response, and a declared Content-Length that is empty or over the ceiling.
// Returns the declared length (null when the server sent none) for the caller to
// verify against the received count afterwards.
fun validateDownloadStatus(
    statusCode: Int,
    contentLength: Long?,
    maxBytes: Long = MaxUpdateBytes,
): Long? {
    if (statusCode !in 200..299) throw UpdateDownloadException("download rejected: HTTP $statusCode")
    if (contentLength != null && contentLength <= 0L) {
        throw UpdateDownloadException("download rejected: empty body (Content-Length $contentLength)")
    }
    if (contentLength != null && contentLength > maxBytes) {
        throw UpdateDownloadException("download rejected: $contentLength bytes exceeds the $maxBytes-byte ceiling")
    }
    return contentLength
}

// validateDownloadSize runs AFTER the stream ends: nothing downloaded is a
// failure, and when the server declared a length the received count MUST match
// exactly. This is what catches a truncated stream (e.g. an idle read aborting a
// quiet source) instead of renaming a half file onto the install path.
fun validateDownloadSize(received: Long, expected: Long?) {
    if (received <= 0L) throw UpdateDownloadException("download produced no bytes")
    if (expected != null && received != expected) {
        throw UpdateDownloadException("download truncated: got $received of $expected bytes")
    }
}

// streamDownload copies [channel] through [write], enforcing the byte ceiling as
// it goes — the guard for a stream with no or a lying Content-Length — and
// reporting progress. Returns the total bytes written. Callers validate the
// status before calling this and the returned total with validateDownloadSize
// after. Kept engine-agnostic (a plain ByteReadChannel + write sink) so it is
// unit-testable without a live HTTP client.
suspend fun streamDownload(
    channel: ByteReadChannel,
    expected: Long?,
    onProgress: (Long, Long?) -> Unit,
    maxBytes: Long = MaxUpdateBytes,
    write: (ByteArray, Int) -> Unit,
): Long {
    val buf = ByteArray(64 * 1024)
    var received = 0L
    while (true) {
        val n = channel.readAvailable(buf, 0, buf.size)
        if (n == -1) break
        if (n > 0) {
            received += n
            if (received > maxBytes) {
                throw UpdateDownloadException("download exceeded the $maxBytes-byte ceiling")
            }
            write(buf, n)
            onProgress(received, expected)
        }
    }
    return received
}
