package io.hero.app

import android.content.Context

actual class Settings actual constructor() {
    // appContext is set by MainActivity before the UI composes; fall back to an
    // in-memory map only if it isn't (never happens in normal launch).
    private val prefs = appContext?.getSharedPreferences("hero_settings", Context.MODE_PRIVATE)
    private val mem = HashMap<String, String>()

    actual fun getString(key: String): String? = prefs?.getString(key, null) ?: mem[key]
    actual fun putString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply() ?: run { mem[key] = value }
    }
    actual fun remove(key: String) {
        prefs?.edit()?.remove(key)?.apply() ?: run { mem.remove(key) }
    }
}
