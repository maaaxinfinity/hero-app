package io.hero.app

// Platform push seams.
//
// notifyLocal posts a lightweight OS notification for an attention item that
// arrived while the app is running. Desktop shows a system-tray balloon; Android
// is a no-op (its remote push already delivers when closed, and the in-app inbox
// + dock count cover the foreground case).
expect fun notifyLocal(title: String, body: String)

// RemotePush registers this device for server-sent push so it is notified even
// when the app is closed. Meaningful only where a push transport exists (Android
// via UnifiedPush by default, or FCM in the firebase build flavor); desktop
// reports unsupported and no-ops.
expect object RemotePush {
    val supported: Boolean
    // transport names the active mechanism for the settings UI ("UnifiedPush",
    // "FCM", or "" when unsupported).
    val transport: String
    suspend fun register(api: Api): Boolean
    suspend fun unregister(api: Api)
}
