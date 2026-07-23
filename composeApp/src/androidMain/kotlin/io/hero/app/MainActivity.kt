package io.hero.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars (the Android 15 default); the Compose layer
        // insets its content via WindowInsets.systemBars, and the bars stay
        // transparent with auto light/dark icon contrast.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        // Android 13+ gates notifications behind a runtime grant; ask once so push
        // and local attention notifications can appear (best-effort — denial just
        // means the system drops them silently).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent { App() }
    }
}
