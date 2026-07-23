package io.hero.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// AndroidSettingsIo persists the whole snapshot with a SINGLE SharedPreferences
// editor (clear + putAll + commit), so a multi-key change lands as one atomic
// unit with the same "true only on durable success" contract as desktop.
// appContext is set by MainActivity/HeroApplication before the UI composes; the
// in-memory fallback only matters if it somehow isn't (never in a normal launch).
internal class AndroidSettingsIo : SettingsIo {
    private val prefs = appContext?.getSharedPreferences("hero_settings", Context.MODE_PRIVATE)
    private val mem = HashMap<String, String>()

    override fun load(): Map<String, String> {
        val p = prefs ?: return HashMap(mem)
        val out = HashMap<String, String>()
        for ((k, v) in p.all) if (v is String) out[k] = v
        return out
    }

    override suspend fun persist(values: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val p = prefs ?: run { mem.clear(); mem.putAll(values); return@withContext true }
        val editor = p.edit()
        editor.clear() // the snapshot is the full set of keys, so clear+put also applies removals
        for ((k, v) in values) editor.putString(k, v)
        editor.commit()
    }
}

actual fun defaultSettingsIo(): SettingsIo = AndroidSettingsIo()
