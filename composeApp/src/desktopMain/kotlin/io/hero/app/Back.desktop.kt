package io.hero.app

import androidx.compose.runtime.Composable

// Desktop has no system back gesture; navigation is via the top-bar tabs.
@Composable
actual fun PredictiveBack(enabled: Boolean, onBack: () -> Unit) {
}
