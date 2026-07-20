package io.hero.app

import androidx.compose.runtime.Composable

// PredictiveBack registers a back handler when [enabled]. On Android 13+ it wires
// the system predictive-back gesture (the OS shows the peek/animation as you drag
// from the edge, and commits [onBack] on release, cancels on abort). On desktop
// it is a no-op. Handlers are LIFO — the innermost enabled one intercepts first —
// so a per-screen handler (e.g. "close the open session") takes priority over an
// outer one (e.g. "return to the Sessions tab").
@Composable
expect fun PredictiveBack(enabled: Boolean, onBack: () -> Unit)
