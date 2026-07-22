package io.hero.app

import androidx.compose.runtime.Composable

// ItemContextMenu wraps content with a right-click context menu on desktop
// (ContextMenuArea is desktop-only API); on Android it is a passthrough — the
// same actions stay reachable through the regular UI.
@Composable
expect fun ItemContextMenu(items: List<Pair<String, () -> Unit>>, content: @Composable () -> Unit)
