package io.hero.app

import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

// HeroPushService receives UnifiedPush events. On a new endpoint it registers
// the (Web Push) subscription with the control plane; on a message it renders
// the decrypted notification. UnifiedPush 3.x decrypts Web Push payloads for us,
// so message.content is the same JSON the browser service worker receives.
class HeroPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val api = apiFromSettings() ?: return
        val keys = endpoint.pubKeySet
        val sub = PushSub(
            endpoint = endpoint.url,
            keys = PushKeys(p256dh = keys?.pubKey.orEmpty(), auth = keys?.auth.orEmpty()),
        )
        runCatching { runBlocking { api.subscribePush(sub) } }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        showPushNotification(applicationContext, message.content.decodeToString())
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {}

    override fun onUnregistered(instance: String) {}
}
