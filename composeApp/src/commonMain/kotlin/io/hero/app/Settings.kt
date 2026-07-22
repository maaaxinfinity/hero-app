package io.hero.app

// Settings is a tiny persistent key/value store (Android SharedPreferences,
// desktop a properties file) so the app can remember the server, the user, the
// chosen theme, and — when "remember me" is on — the session cookie for silent
// re-login. It holds no plaintext password; the cookie is an opaque, server-
// revocable token that a password change invalidates.
expect class Settings() {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

object Keys {
    const val ServerUrl = "server_url"
    const val Username = "username"
    const val Cookie = "session_cookie"
    const val Remember = "remember"
    const val ThemeMode = "theme_mode" // "system" | "light" | "dark"
    const val SidebarCollapsed = "sidebar_collapsed" // "1" = mini rail
}

// ThemeMode drives HeroTheme; System follows the OS.
enum class ThemeMode(val id: String, val label: String) {
    System("system", "System"), Light("light", "Light"), Dark("dark", "Dark");

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}
