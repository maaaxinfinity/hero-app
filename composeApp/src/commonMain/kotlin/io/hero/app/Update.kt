package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// AppVersion (the runtime constant compared against the latest release tag) is
// no longer hand-maintained here: it is GENERATED into Version.kt from
// gradle.properties#appVersion, the single version source that also drives
// Android versionName/versionCode and desktop packageVersion. To bump the
// version, edit gradle.properties (appVersion + appVersionCode) — never a
// source file.
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

// isNewer compares dotted/dashed numeric versions (a "v" prefix is tolerated).
// FAIL CLOSED: a version that doesn't fully parse — empty, or with any
// non-numeric segment ("v0.6.0-rc1", "abc", "0..1") — is never "newer". A
// malformed or experimental tag must not raise an update prompt, and a
// malformed local version must not turn every release into an install loop;
// the release gate only ever publishes vMAJOR.MINOR.PATCH tags, so anything
// outside that grammar is unknown, not orderable.
fun isNewer(tag: String, current: String): Boolean {
    val a = parseVersion(tag) ?: return false
    val b = parseVersion(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

// parseVersion accepts the release version grammar — an optional "v" prefix,
// then dot/dash-separated decimal segments ("0.5.20", "v1.2", "0.5.20-1") —
// and returns null for anything else: empty input, a non-numeric or empty
// segment, or a number that overflows Int. The old parser silently DROPPED bad
// segments (mapNotNull), so "v1.junk.9" quietly became [1, 9]; unknown input
// must be unorderable, not guessed at.
internal fun parseVersion(s: String): List<Int>? {
    val body = s.removePrefix("v")
    if (body.isEmpty()) return null
    return body.split(".", "-").map { seg -> seg.toIntOrNull() ?: return null }
}

// installUpdate downloads the public asset and applies it: Android hands the
// verified APK to the package installer; desktop publishes a verified jar and
// returns the path to swap in. onProgress streams (bytesReceived, totalOrNull),
// already throttled at the source (see streamDownload). Contract for both
// actuals: every blocking file operation (temp create, chunk writes, fsync,
// rename) runs on Dispatchers.IO — never the caller's UI thread — and caller
// cancellation propagates as CancellationException (after deleting the partial
// temp) instead of being folded into an error string.
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

// DownloadIdleBudgetMillis bounds "connection established, then silence". The
// download client deliberately has no socket read timeout (the same OkHttp
// config also carries SSE streams), so a peer that stops sending bytes after
// the handshake would otherwise hang the install forever. The budget applies
// PER READ: an actively flowing transfer of any total duration is fine, only a
// stall aborts — as a download failure, not a coroutine cancellation.
const val DownloadIdleBudgetMillis: Long = 30_000

// DownloadProgressStepBytes throttles progress when the total size is unknown:
// at most one report per step plus the final one, instead of one per 64 KiB
// chunk. With a known total, reports fire on whole-percent changes (≤ ~101 per
// download). Either way the UI owner sees a bounded callback rate instead of a
// recomposition per chunk.
const val DownloadProgressStepBytes: Long = 1024 * 1024

// UpdateDownloadException marks a response that failed validation (wrong
// status, empty/oversized/truncated/stalled body, or a package that fails
// verification) so the caller surfaces "update failed" instead of committing a
// bogus file to the install path.
class UpdateDownloadException(message: String) : Exception(message)

// validateDownloadStatus runs BEFORE a byte is streamed. ONLY a plain 200 is a
// complete-object response: the updater never sends Range, so a 206 means some
// origin/CDN/proxy answered with a segment whose Content-Length describes the
// SEGMENT — accepting it would let validateDownloadSize "verify" a partial
// APK/JAR as complete. Other 2xx equally do not carry the verbatim complete
// asset (202 accepted-with-status-body, 203 transformed-by-proxy, 204 empty).
// Redirects are followed by the client, so the FINAL status of a healthy
// download is exactly 200. Also rejects a declared Content-Length that is
// empty or over the ceiling. Returns the declared length (null when the server
// sent none) for the caller to verify against the received count afterwards.
fun validateDownloadStatus(
    statusCode: Int,
    contentLength: Long?,
    maxBytes: Long = MaxUpdateBytes,
): Long? {
    if (statusCode != 200) {
        val detail = if (statusCode == 206) " (partial content — refusing a segment as the whole asset)" else ""
        throw UpdateDownloadException("download rejected: HTTP $statusCode$detail")
    }
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
// it goes — the guard for a stream with no or a lying Content-Length — plus a
// per-read idle budget (a quiet peer aborts as a download failure; a caller
// cancellation still surfaces as CancellationException) and THROTTLED progress.
// Returns the total bytes written. Callers validate the status before calling
// this and the returned total with validateDownloadSize after. Kept
// engine-agnostic (a plain ByteReadChannel + write sink) so it is unit-testable
// without a live HTTP client.
suspend fun streamDownload(
    channel: ByteReadChannel,
    expected: Long?,
    onProgress: (Long, Long?) -> Unit,
    maxBytes: Long = MaxUpdateBytes,
    idleBudgetMillis: Long? = DownloadIdleBudgetMillis,
    write: (ByteArray, Int) -> Unit,
): Long {
    val buf = ByteArray(64 * 1024)
    var received = 0L
    var lastReported = -1L
    var lastPercent = -1L
    var lastStep = -1L
    while (true) {
        val n = if (idleBudgetMillis == null) {
            channel.readAvailable(buf, 0, buf.size)
        } else {
            try {
                withTimeout(idleBudgetMillis) { channel.readAvailable(buf, 0, buf.size) }
            } catch (e: TimeoutCancellationException) {
                // The TIMEOUT is a transfer failure. An outer cancellation
                // reaches here as a plain CancellationException and propagates.
                throw UpdateDownloadException("download stalled: no data for ${idleBudgetMillis / 1000}s")
            }
        }
        if (n == -1) break
        if (n > 0) {
            received += n
            if (received > maxBytes) {
                throw UpdateDownloadException("download exceeded the $maxBytes-byte ceiling")
            }
            write(buf, n)
            // Throttle: whole-percent granularity when the total is known, one
            // report per DownloadProgressStepBytes otherwise — never per-chunk.
            if (expected != null && expected > 0) {
                val p = received * 100 / expected
                if (p != lastPercent) { lastPercent = p; lastReported = received; onProgress(received, expected) }
            } else {
                val s = received / DownloadProgressStepBytes
                if (s != lastStep) { lastStep = s; lastReported = received; onProgress(received, expected) }
            }
        }
    }
    // The final count always reaches the UI even when throttling skipped it.
    if (received > 0 && lastReported != received) onProgress(received, expected)
    return received
}

// downloadUpdateAsset runs the full transfer contract against [client]: request
// the asset, admit ONLY a complete-response 200 with a sane declared length,
// stream through the byte ceiling + idle budget with throttled progress, then
// require the received count to equal the declared length exactly — a
// truncated or interrupted stream is a failure, never a committable file.
// (With no Content-Length the transfer alone cannot prove completeness; the
// platform actuals close that with package verification before publishing.)
// Engine-injectable so the partial/truncated/stalled/cancelled shapes are
// locked by tests over a controlled engine; the platform actuals own only file
// placement, container verification and the durable publish.
suspend fun downloadUpdateAsset(
    client: HttpClient,
    url: String,
    onProgress: (Long, Long?) -> Unit,
    maxBytes: Long = MaxUpdateBytes,
    idleBudgetMillis: Long? = DownloadIdleBudgetMillis,
    write: (ByteArray, Int) -> Unit,
): Long = client.prepareGet(url) {
    header("Accept", "application/octet-stream")
}.execute { resp ->
    val expected = validateDownloadStatus(resp.status.value, resp.contentLength(), maxBytes)
    val received = streamDownload(resp.bodyAsChannel(), expected, onProgress, maxBytes, idleBudgetMillis, write)
    validateDownloadSize(received, expected)
    received
}
