package io.hero.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Panels.kt is the shared sidebar kit. Placement language across the app:
// LEFT panes navigate collections (pick a node/session), RIGHT panels inspect
// and operate on the selected object, TOP toolbars act on the whole collection.

/** TopToolbar heads a management screen: title + collection-wide actions.
 *  Callers align their actions with weights (e.g. a stretching filter field). */
@Composable
fun TopToolbar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                actions()
            }
            HorizontalDivider()
        }
    }
}

/**
 * InspectorHost lays out a screen with its right-hand inspector. Expanded: the
 * panel slides in beside the content (300dp, closable). Compact: the panel is a
 * fullscreen layer over the content with its own back bar — same stacked
 * navigation (and predictive-back pop) as the conversation.
 */
@Composable
fun InspectorHost(
    open: Boolean,
    onClose: () -> Unit,
    panelTitle: String,
    main: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    val compact = LocalWindowWidth.current == WindowWidth.Compact
    // Registered here (inside the screen) so it pops before the screen's own
    // handlers and MainScreen's tab handler (LIFO).
    PredictiveBack(enabled = compact && open) { onClose() }
    if (compact) {
        AnimatedContent(
            targetState = open,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (slideInHorizontally(tween(280)) { w -> dir * w } + fadeIn(tween(280)))
                    .togetherWith(slideOutHorizontally(tween(240)) { w -> -dir * w } + fadeOut(tween(180)))
            },
            modifier = Modifier.fillMaxSize(),
            label = "inspector",
        ) { o ->
            if (o) {
                Column(Modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onClose) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Text(panelTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { panel() }
                }
            } else {
                Box(Modifier.fillMaxSize()) { main() }
            }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { main() }
            AnimatedVisibility(
                visible = open,
                enter = expandHorizontally(tween(200)),
                exit = shrinkHorizontally(tween(200)),
            ) {
                Row(Modifier.fillMaxHeight()) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Surface(Modifier.width(300.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    panelTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = onClose, modifier = Modifier.height(28.dp).width(28.dp)) {
                                    Icon(
                                        Icons.Filled.Close, contentDescription = "Close panel",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.height(15.dp).width(15.dp),
                                    )
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) { panel() }
                        }
                    }
                }
            }
        }
    }
}

/** PanelSection is a titled card block, used by inspectors and the settings page. */
@Composable
fun PanelSection(title: String, modifier: Modifier = Modifier.fillMaxWidth(), body: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            body()
        }
    }
}
