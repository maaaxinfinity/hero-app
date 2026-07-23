package io.hero.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

// showPushNotification renders a server push payload (the same JSON the web
// service worker receives) as an Android notification. A permission prompt is
// high-priority with inline Allow / Deny / Allow-always actions wired to
// PushActionReceiver, which posts the answer to the owning node without opening
// the app; other kinds are quiet informational notes.
private val pushJson = Json { ignoreUnknownKeys = true }

fun showPushNotification(ctx: Context, payloadJson: String) {
    val obj = runCatching { pushJson.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return
    fun str(k: String) = (obj[k]?.jsonPrimitive?.content).orEmpty()
    val kind = str("kind")
    val title = str("title").ifEmpty { "HERO" }
    val body = str("body")
    val node = str("node")
    val interactionId = str("interaction_id")
    val allowAlways = obj["allow_always"]?.jsonPrimitive?.content == "true"

    val b = Notification.Builder(ctx, NOTIF_CHANNEL)
        .setContentTitle(title)
        .setContentText(body)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setAutoCancel(true)

    val notifId = Random.nextInt()
    if (kind == "permission" && node.isNotEmpty() && interactionId.isNotEmpty()) {
        b.setOngoing(false)
        b.addAction(action(ctx, "Allow", node, interactionId, "allow", "once", notifId))
        if (allowAlways) {
            b.addAction(action(ctx, "Always", node, interactionId, "allow", "session", notifId))
        }
        b.addAction(action(ctx, "Deny", node, interactionId, "deny", "once", notifId))
    }
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    nm.notify(notifId, b.build())
}

private fun action(ctx: Context, label: String, node: String, id: String, behavior: String, scope: String, notifId: Int): Notification.Action {
    val intent = Intent(ctx, PushActionReceiver::class.java).apply {
        this.action = "io.hero.app.RESPOND"
        putExtra("node", node)
        putExtra("id", id)
        putExtra("behavior", behavior)
        putExtra("scope", scope)
        putExtra("notif_id", notifId)
    }
    // Distinct requestCode per (action) so the extras don't collapse into one PI.
    val pi = PendingIntent.getBroadcast(
        ctx, Random.nextInt(), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return Notification.Action.Builder(null, label, pi).build()
}
