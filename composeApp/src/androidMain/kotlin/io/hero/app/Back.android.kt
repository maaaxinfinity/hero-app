package io.hero.app

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlin.coroutines.cancellation.CancellationException

// Android actual: PredictiveBackHandler drives the OS predictive-back animation.
// Collecting the progress flow lets the system render the gesture; when the flow
// completes the user committed the gesture, so we run onBack. A cancelled gesture
// throws CancellationException — we swallow it and stay put.
@Composable
actual fun PredictiveBack(enabled: Boolean, onBack: () -> Unit) {
    PredictiveBackHandler(enabled) { progress ->
        try {
            progress.collect { /* could animate with event.progress here */ }
            onBack()
        } catch (_: CancellationException) {
            // gesture aborted — no navigation
        }
    }
}
