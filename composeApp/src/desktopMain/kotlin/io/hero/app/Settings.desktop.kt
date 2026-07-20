package io.hero.app

import java.io.File
import java.util.Properties

actual class Settings actual constructor() {
    private val file = File(System.getProperty("user.home"), ".hero-app/settings.properties")
    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    actual fun getString(key: String): String? = props.getProperty(key)

    actual fun putString(key: String, value: String) {
        props.setProperty(key, value); save()
    }

    actual fun remove(key: String) {
        props.remove(key); save()
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "hero-app") }
    }
}
