package io.hero.app

import android.app.Notification
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import org.unifiedpush.android.connector.UnifiedPush
import kotlin.coroutines.resume

// Android notifications while the app runs: post directly (remote push covers the
// closed case). Requires the POST_NOTIFICATIONS runtime grant on Android 13+,
// requested by MainActivity — without it the system drops these silently.
actual fun notifyLocal(title: String, body: String) {
    val ctx = appContext ?: return
    val n = Notification.Builder(ctx, NOTIF_CHANNEL)
        .setContentTitle(title)
        .setContentText(body)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setAutoCancel(true)
        .build()
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    nm.notify(kotlin.random.Random.nextInt(), n)
}

// RemotePush prefers FCM when Firebase was configured at build time (a
// google-services.json was present), else falls back to UnifiedPush — a
// self-hosted transport (the user installs a distributor such as ntfy). Either
// way the device is reachable while the app is closed; the subscription is
// posted to the control plane and scoped to the nodes this user can see.
actual object RemotePush {
    actual val supported: Boolean = true
    actual val transport: String
        get() = if (appContext?.let(::firebaseReady) == true) "FCM" else "UnifiedPush"

    actual suspend fun register(api: Api): Boolean {
        val ctx = appContext ?: return false
        if (firebaseReady(ctx)) {
            val token = fcmToken() ?: return false
            api.subscribePush(PushSub(fcm_token = token))
            return true
        }
        // UnifiedPush: adopt an installed distributor, then register with the
        // server's VAPID key. The endpoint arrives in HeroPushService.onNewEndpoint.
        val vapid = api.vapidKey() ?: return false
        val distributors = UnifiedPush.getDistributors(ctx)
        if (distributors.isEmpty()) return false
        if (UnifiedPush.getAckDistributor(ctx) == null) {
            UnifiedPush.saveDistributor(ctx, distributors.first())
        }
        UnifiedPush.register(ctx, vapid = vapid)
        return true
    }

    actual suspend fun unregister(api: Api) {
        val ctx = appContext ?: return
        if (firebaseReady(ctx)) {
            runCatching { FirebaseMessaging.getInstance().deleteToken() }
        } else {
            UnifiedPush.unregister(ctx)
        }
    }
}

private fun firebaseReady(ctx: Context): Boolean =
    runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

private suspend fun fcmToken(): String? = suspendCancellableCoroutine { cont ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
        cont.resume(if (t.isSuccessful) t.result else null)
    }
}
