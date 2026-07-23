package io.hero.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

// HeroApplication runs before any Activity or push Service, so it is where the
// global appContext is set and the notification channel is created. A push
// delivered while the UI is closed starts this Application (not MainActivity),
// so relying on MainActivity to set appContext would leave the push Service
// without a Context for Settings-backed HTTP.
class HeroApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "Attention", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Permission prompts and turn activity from your agents" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}

const val NOTIF_CHANNEL = "hero_attention"
