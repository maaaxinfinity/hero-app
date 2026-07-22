package io.hero.app

import androidx.compose.runtime.Composable

@Composable
actual fun ItemContextMenu(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) {
    content()
}
