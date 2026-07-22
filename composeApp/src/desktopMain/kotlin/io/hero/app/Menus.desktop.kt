package io.hero.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun ItemContextMenu(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit) {
    ContextMenuArea(items = { items.map { (label, action) -> ContextMenuItem(label, action) } }) {
        content()
    }
}
