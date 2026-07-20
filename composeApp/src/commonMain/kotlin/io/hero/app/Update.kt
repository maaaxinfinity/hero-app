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
const val AppVersion = "0.1.0"
const val Repo = "maaaxinfinity/hero-app"

@Serializable
data class GhAsset(val name: String = "", val url: String = "")

@Serializable
data class GhRelease(val tag_name: String = "", val assets: List<GhAsset> = emptyList())

data class UpdateInfo(val version: String, val asset: GhAsset)

private val updaterJson = Json { ignoreUnknownKeys = true }

// checkForUpdate queries the latest release and returns an UpdateInfo when it is
// newer than AppVersion and has an asset ending in assetSuffix (.apk / os.jar).
// token authenticates against the private repo (via the GitHub API asset URL).
suspend fun checkForUpdate(token: String, assetSuffix: String): UpdateInfo? {
    val client = HttpClient { install(ContentNegotiation) { json(updaterJson) } }
    try {
        val r = client.get("https://api.github.com/repos/$Repo/releases/latest") {
            header("Accept", "application/vnd.github+json")
            if (token.isNotBlank()) header("Authorization", "Bearer $token")
        }
        if (!r.status.isSuccess()) return null
        val rel: GhRelease = r.body()
        if (!isNewer(rel.tag_name, AppVersion)) return null
        val asset = rel.assets.firstOrNull { it.name.endsWith(assetSuffix) } ?: return null
        return UpdateInfo(rel.tag_name.removePrefix("v"), asset)
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

// installUpdate downloads the asset (with token auth) and applies it: Android
// launches the package installer; desktop saves the OS jar and prints how to run.
expect suspend fun installUpdate(info: UpdateInfo, token: String): String

// updateAssetSuffix is the release-asset name suffix this platform installs.
expect fun updateAssetSuffix(): String
