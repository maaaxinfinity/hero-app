package io.hero.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Dock is the app's one piece of global chrome: a fixed full-width bar at the
// bottom of the window, on both desktop and phones (selected item gets a dot
// under its glyph). Items sit in four functional blocks separated by dividers —
// workspace | infrastructure | people & permissions | system (the account
// avatar lives in the system block).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dock(
    me: Me,
    section: Section,
    onSelect: (Section) -> Unit,
    onSignOut: () -> Unit,
    attentionCount: Int = 0, // fleet count of actionable items (permission/question)
) {
    val compact = LocalWindowWidth.current == WindowWidth.Compact
    val item = if (compact) 40.dp else 44.dp
    val groups = Section.entries.filter { !it.adminOnly || me.admin }.groupBy { it.group }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider()
            // Centered when the blocks fit; horizontally scrollable when they don't —
            // an admin's cells + dividers can exceed a 320dp phone, and clipping the
            // edge item/avatar (its old behavior) is worse than a scroll. Cells keep
            // full touch size; compact just tightens the gaps.
            Box(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    groups.keys.sorted().forEachIndexed { i, g ->
                        if (i > 0) DockDivider(compact)
                        groups.getValue(g).forEach { s ->
                            DockItem(
                                section = s,
                                selected = s == section,
                                badge = if (s == Section.Attention) attentionCount else 0,
                                size = item,
                                onClick = { onSelect(s) },
                            )
                        }
                        if (g == 4) DockAvatar(me, size = item, onSignOut = onSignOut)
                    }
                }
            }
        }
    }
}

@Composable
private fun DockDivider(compact: Boolean = false) {
    Box(
        Modifier.padding(horizontal = if (compact) 3.dp else 5.dp).width(1.dp).height(22.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockItem(section: Section, selected: Boolean, badge: Int, size: Dp, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(section.label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.size(size)
                .clip(RoundedCornerShape(10.dp))
                .hoverHighlight(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            SectionGlyph(
                section,
                Modifier.size(19.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                        .size(3.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp)
                        .defaultMinSize(minWidth = 14.dp, minHeight = 14.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                        .padding(horizontal = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 9) "9+" else badge.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// DockAvatar anchors the account menu (identity + sign out); app settings have
// their own dock item in the same block. The halo ring marks the account cell
// apart from plain sections — primary for admins, outline otherwise.
@Composable
private fun DockAvatar(me: Me, size: Dp, onSignOut: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(size)
                .clip(RoundedCornerShape(10.dp))
                .hoverHighlight(RoundedCornerShape(10.dp))
                .clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(30.dp).border(
                    1.5.dp,
                    if (me.admin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(8.dp),
                ),
            )
            Identicon(me.user.ifEmpty { "?" }, size = 21.dp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(me.user.ifEmpty { "user" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                if (me.admin) {
                    Text("admin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; onSignOut() })
        }
    }
}
