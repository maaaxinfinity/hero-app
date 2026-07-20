package io.hero.app

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
        setContent { App() }
    }
}
