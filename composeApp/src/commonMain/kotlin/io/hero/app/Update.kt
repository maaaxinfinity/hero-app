package io.hero.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// AppVersion is compared against the latest GitHub release tag. Bump it before
// tagging a release so the running app can tell it is out of date.
const val AppVersion = "0.5.5"
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
    val client = HttpClient { install(ContentNegotiation) { json(updaterJson) } }
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
