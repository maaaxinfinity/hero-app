package io.hero.app

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Settings is a tiny persistent key/value store (Android SharedPreferences,
// desktop a properties file) so the app can remember the server, the user, the
// chosen theme, and — when "remember me" is on — the session cookie for silent
// re-login. It holds no plaintext password; the cookie is an opaque, server-
// revocable token that a password change invalidates.
//
// Writes go through a single `update {}` transaction: the caller mutates a copy
// of the current snapshot and the WHOLE result is committed as ONE atomic unit,
// so a multi-key change (login writes server+user+remember+cookie; Forget clears
// three keys) can never persist a half-written combination. Reads go through the
// observable `snapshot`, so a committed change (e.g. Forget clearing the saved
// login) immediately recomposes any screen that read it.

// SettingsSnapshot is an immutable view of every persisted key/value.
class SettingsSnapshot internal constructor(internal val values: Map<String, String>) {
    fun get(key: String): String? = values[key]

    override fun equals(other: Any?): Boolean = other is SettingsSnapshot && other.values == values
    override fun hashCode(): Int = values.hashCode()
}

// SettingsIo is the platform persistence seam. `load` runs once at startup and
// must NEVER throw — a corrupt, oversized or unreadable store is quarantined and
// reported as an empty (recoverable) default so it can't block the first frame.
// `persist` writes a WHOLE snapshot atomically and off the UI thread, returning
// true ONLY on durable success (desktop: temp file + fsync + atomic rename;
// Android: a single SharedPreferences editor commit).
interface SettingsIo {
    fun load(): Map<String, String>
    suspend fun persist(values: Map<String, String>): Boolean
}

expect fun defaultSettingsIo(): SettingsIo

class Settings(private val io: SettingsIo = defaultSettingsIo()) {
    // The published, observable state. Seeded from disk once; only replaced after
    // a durable write, so composition never sees a half-committed batch.
    private val _snapshot = mutableStateOf(SettingsSnapshot(io.load()))
    // Serializes commits so overlapping updates apply on top of each other and
    // publish in order.
    private val writeLock = Mutex()

    // Observable current settings — read this from composition.
    val snapshot: State<SettingsSnapshot> = _snapshot

    // Convenience single-key read off the observable snapshot. Reading this from
    // composition subscribes the caller, so a later commit recomposes it; it is
    // equally safe to call off the UI thread (e.g. the push action receiver).
    fun getString(key: String): String? = _snapshot.value.get(key)

    // update commits ONE consistent snapshot. `mutate` edits a copy of the
    // current values (put or remove keys); the whole result is persisted
    // atomically and, ONLY on durable success, published. Returns true on success
    // (including a no-op that changed nothing, which skips the write entirely),
    // false if the durable write failed — in which case both the old on-disk file
    // and the old published snapshot are kept.
    suspend fun update(mutate: (MutableMap<String, String>) -> Unit): Boolean = writeLock.withLock {
        val current = _snapshot.value.values
        val next = HashMap(current).apply(mutate)
        if (next == current) return@withLock true // no-op: nothing to persist
        val ok = io.persist(next)
        if (ok) _snapshot.value = SettingsSnapshot(next)
        ok
    }
}

object Keys {
    const val ServerUrl = "server_url"
    const val Username = "username"
    const val Cookie = "session_cookie"
    const val Remember = "remember"
    const val ThemeMode = "theme_mode" // "system" | "light" | "dark"
    const val SidebarCollapsed = "sidebar_collapsed" // "1" = mini rail
    const val NodesView = "nodes_view" // "card" (default) | "list"
}

// ThemeMode drives HeroTheme; System follows the OS.
enum class ThemeMode(val id: String, val label: String) {
    System("system", "System"), Light("light", "Light"), Dark("dark", "Dark");

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: System
    }
}
