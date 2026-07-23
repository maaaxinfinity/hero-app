package io.hero.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking

// PushActionReceiver answers a permission prompt from a notification's inline
// Allow/Deny/Always button — no app open required. It rebuilds an Api from the
// persisted server URL + session cookie (the same "remember me" credentials the
// UI uses) and posts to the owning node's relay, then cancels the notification.
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val node = intent.getStringExtra("node") ?: return
        val id = intent.getStringExtra("id") ?: return
        val behavior = intent.getStringExtra("behavior") ?: return
        val scope = intent.getStringExtra("scope") ?: "once"
        val notifId = intent.getIntExtra("notif_id", 0)

        val api = apiFromSettings() ?: return
        val pending = goAsync()
        Thread {
            try {
                runCatching {
                    runBlocking { api.respond(node, id, RespondReq(behavior, scope = scope, reason = "via HERO app")) }
                }
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancel(notifId)
                }
            } finally {
                // The receiver's two owner obligations hold on EVERY exit —
                // success, HTTP failure, or a Throwable anywhere on this thread:
                // the one-shot client is closed (it used to leak per tap) and the
                // broadcast is finished (an unfinished goAsync() pins the process
                // and eventually ANRs the receiver).
                api.close()
                pending.finish()
            }
        }.start()
    }
}

// apiFromSettings reconstructs an authenticated Api from persisted login state,
// for the out-of-UI push Service / action receiver. Returns null when there is
// no saved server + cookie (nothing to answer with).
fun apiFromSettings(): Api? {
    val s = Settings()
    val url = s.getString(Keys.ServerUrl)?.takeIf { it.isNotBlank() } ?: return null
    val cookie = s.getString(Keys.Cookie)?.takeIf { it.isNotBlank() } ?: return null
    return Api(url, cookie)
}
