package io.hero.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Widgets.kt holds small shared controls and interaction helpers used across
// screens (kept dependency-free and hand-rolled, like the rest of the UI).

/** hoverHighlight washes the surface with 6% ink while a pointer hovers —
 *  the desktop feedback layer for every custom clickable in the chrome.
 *  Touch never hovers, so this is a no-op on phones. */
fun Modifier.hoverHighlight(shape: Shape = RectangleShape): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    hoverable(interactions).background(
        if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent,
        shape,
    )
}

/** Pill is the small outlined status/label chip used across lists and cards. */
@Composable
internal fun Pill(text: String, color: Color) {
    OutlinedCard {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** PasswordField is an OutlinedTextField with a show/hide eye toggle — used by
 *  the login screen and the user-management dialogs. */
@Composable
internal fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, modifier,
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                EyeGlyph(
                    open = show,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

// AppKeys routes window-level key events into whatever handlers the running
// composition registered — a LIFO stack, like PredictiveBack, so the innermost
// screen wins. Desktop main.kt forwards Window.onPreviewKeyEvent here; Android
// never dispatches (no global shortcuts). Focused text fields see keys first —
// only events they don't consume reach these handlers.
object AppKeys {
    private val handlers = ArrayList<(KeyEvent) -> Boolean>()
    fun push(h: (KeyEvent) -> Boolean) { handlers.add(h) }
    fun remove(h: (KeyEvent) -> Boolean) { handlers.remove(h) }
    fun dispatch(e: KeyEvent): Boolean = handlers.asReversed().any { it(e) }
}

/** KeyHandler registers a window-level shortcut handler for the lifetime of the
 *  composition (always the latest lambda; LIFO priority). */
@Composable
fun KeyHandler(handler: (KeyEvent) -> Boolean) {
    val current = rememberUpdatedState(handler)
    DisposableEffect(Unit) {
        val h: (KeyEvent) -> Boolean = { current.value(it) }
        AppKeys.push(h)
        onDispose { AppKeys.remove(h) }
    }
}

/** ConfirmButton arms on the first click (error-colored "Confirm — …") and
 *  fires on the second; disarms after 3s untouched. Two deliberate clicks for
 *  destructive actions, without a modal. targetKey names the entity the click
 *  would destroy: a target switch inside the 3s window disarms instantly, so
 *  the confirm can never fire against a different target than the one armed.
 *  enabled=false (e.g. while the previous confirm is in flight) blocks both arm
 *  and fire, so a rapid re-arm can't launch a second mutation (single-flight). */
@Composable
internal fun ConfirmButton(label: String, targetKey: Any? = null, enabled: Boolean = true, onConfirm: () -> Unit) {
    var armed by remember(targetKey) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) { delay(3000); armed = false }
    }
    OutlinedButton(
        enabled = enabled,
        onClick = { if (armed) { armed = false; onConfirm() } else armed = true },
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        ),
    ) { Text(if (armed) "Confirm — $label" else label) }
}

/** KeyValueRow is the inspector's dense fact line. */
@Composable
internal fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(86.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** MetricBar is a dense machine-health meter: label, thin fill bar, detail
 *  text. The fill goes error-red past 90% — the one place the monochrome
 *  scheme allows an alarm color. */
@Composable
internal fun MetricBar(label: String, fraction: Float, detail: String) {
    val f = fraction.coerceIn(0f, 1f)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
        )
        Box(
            Modifier.weight(1f).height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(f).fillMaxHeight().background(
                    if (f > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            detail,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** fmtBytes renders byte counts compactly (7.2 GB / 412 MB / 88 kB). */
internal fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "${b / 1_000_000_000}.${(b / 100_000_000) % 10} GB"
    b >= 1_000_000 -> "${b / 1_000_000} MB"
    b >= 1_000 -> "${b / 1_000} kB"
    else -> "$b B"
}

/** InlineSpinner is the quiet in-content loader: a rotating 270° ink arc. The
 *  particle logo stays reserved for brand moments (boot splash, login). */
@Composable
internal fun InlineSpinner(size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(850, easing = LinearEasing)),
        label = "angle",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(size)) {
        drawArc(
            color = color,
            startAngle = angle, sweepAngle = 270f, useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/** LinkRow is a compact in-app navigation line (label + chevron). */
@Composable
internal fun LinkRow(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** ErrorRow surfaces a failed fetch inline, with a retry affordance. */
@Composable
internal fun ErrorRow(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRetry, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Filled.Refresh, contentDescription = "Retry",
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp),
            )
        }
    }
}
