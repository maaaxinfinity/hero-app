package io.hero.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

// HeroFirebaseService is the FCM counterpart of HeroPushService: a new token is
// registered with the control plane (kind=fcm), and an incoming data message
// carries the same notification JSON (in data["payload"]) that the web service
// worker renders. Inert unless google-services.json configured Firebase at build
// time; otherwise the app uses UnifiedPush and this service is never invoked.
class HeroFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val api = apiFromSettings() ?: return
        runCatching { runBlocking { api.subscribePush(PushSub(fcm_token = token)) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = message.data["payload"] ?: return
        showPushNotification(applicationContext, payload)
    }
}
