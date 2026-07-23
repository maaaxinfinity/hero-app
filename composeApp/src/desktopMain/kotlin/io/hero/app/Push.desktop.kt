package io.hero.app

import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

// Desktop notifications use the AWT system tray — the only stdlib path to a
// native OS notification from the JVM. The tray icon is created lazily on the
// first notification, so a user who never gets one never sees a tray entry.
private val trayIcon: TrayIcon? by lazy {
    if (!SystemTray.isSupported()) return@lazy null
    runCatching {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0x5B, 0x8D, 0xEF)
        g.fillOval(2, 2, 12, 12)
        g.dispose()
        val icon = TrayIcon(img, "HERO")
        icon.isImageAutoSize = true
        SystemTray.getSystemTray().add(icon)
        icon
    }.getOrNull()
}

actual fun notifyLocal(title: String, body: String) {
    trayIcon?.displayMessage(title, body, TrayIcon.MessageType.INFO)
}

// Desktop has no remote push transport: it stays connected while open and shows
// tray notifications from the live poll. Nothing to register with a server.
actual object RemotePush {
    actual val supported: Boolean = false
    actual val transport: String = ""
    actual suspend fun register(api: Api): Boolean = false
    actual suspend fun unregister(api: Api) {}
}
