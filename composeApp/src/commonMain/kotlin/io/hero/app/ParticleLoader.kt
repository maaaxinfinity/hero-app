package io.hero.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Faithful Compose port of the "Quantum Dot Matrix" loader. Physics runs in a
// fixed 500×500 reference space (matching the original canvas) and is uniformly
// scaled to fit whatever bounds the composable is given, so the motion looks
// identical at any size. Particle/ink color is passed in (theme onBackground).

private const val REF = 500f
private const val LOGO_SCALE = 2.6f
private const val SPACING = 3.5f

private enum class Phase { Assembling, Stable, Dispersing }

private fun duration(p: Phase) = when (p) {
    Phase.Assembling -> 120
    Phase.Stable -> 250
    Phase.Dispersing -> 80
}

private fun next(p: Phase) = when (p) {
    Phase.Assembling -> Phase.Stable
    Phase.Stable -> Phase.Dispersing
    Phase.Dispersing -> Phase.Assembling
}

private class Particle(val targetX: Float, val targetY: Float) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    val baseRadius = 2.2f
    var radius = baseRadius
    val friction = Random.nextFloat() * 0.04f + 0.88f
    val spring = Random.nextFloat() * 0.02f + 0.03f
    var wanderX = 0f
    var wanderY = 0f

    init {
        val angle = Random.nextFloat() * (2f * PI.toFloat())
        val r = Random.nextFloat() * 500f + 250f
        x = REF / 2f + cos(angle) * r
        y = REF / 2f + sin(angle) * r
        wanderX = x
        wanderY = y
    }

    fun reseedWander() {
        val angle = Random.nextFloat() * (2f * PI.toFloat())
        val r = Random.nextFloat() * 200f + 50f
        wanderX = REF / 2f + cos(angle) * r
        wanderY = REF / 2f + sin(angle) * r
    }

    fun update(phase: Phase, mouse: Offset, timeMs: Float) {
        if (phase == Phase.Assembling || phase == Phase.Stable) {
            vx += (targetX - x) * spring
            vy += (targetY - y) * spring
        } else {
            vx += (wanderX - x) * 0.01f
            vy += (wanderY - y) * 0.01f
            wanderX += (Random.nextFloat() - 0.5f) * 4f
            wanderY += (Random.nextFloat() - 0.5f) * 4f
        }

        val dx = mouse.x - x
        val dy = mouse.y - y
        val dist = sqrt(dx * dx + dy * dy)
        val mouseRadius = 45f
        if (dist > 0.001f && dist < mouseRadius) {
            val force = (mouseRadius - dist) / mouseRadius
            vx -= (dx / dist) * force * 4f
            vy -= (dy / dist) * force * 4f
        }

        vx *= friction
        vy *= friction
        x += vx
        y += vy

        if (phase == Phase.Stable) {
            val d = sqrt((targetX - REF / 2f) * (targetX - REF / 2f) + (targetY - REF / 2f) * (targetY - REF / 2f))
            val ripple = sin(d * 0.04f - timeMs * 0.005f)
            val shimmer = sin(targetX * 0.05f + timeMs * 0.008f) * cos(targetY * 0.05f - timeMs * 0.006f)
            radius = if (ripple + shimmer > 0.6f) baseRadius * 1.9f
            else radius + (baseRadius - radius) * 0.15f
        } else {
            radius += (baseRadius - radius) * 0.15f
        }
    }
}

private fun buildParticles(mask: PixelMap): List<Particle> {
    val list = ArrayList<Particle>()
    val offset = (REF - LOGO_VIEWBOX * LOGO_SCALE) / 2f
    val res = mask.width
    var yy = 0f
    while (yy < LOGO_VIEWBOX) {
        var xx = 0f
        while (xx < LOGO_VIEWBOX) {
            val px = (xx / LOGO_VIEWBOX * res).toInt().coerceIn(0, res - 1)
            val py = (yy / LOGO_VIEWBOX * res).toInt().coerceIn(0, res - 1)
            if (mask[px, py].alpha > 0.5f) {
                list.add(Particle(offset + xx * LOGO_SCALE, offset + yy * LOGO_SCALE))
            }
            xx += SPACING
        }
        yy += SPACING
    }
    return list
}

@Composable
fun ParticleLoader(
    tint: Color,
    modifier: Modifier = Modifier,
    running: Boolean = true,
) {
    val mask = remember { logoAlphaMask(150) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var mouse by remember { mutableStateOf(Offset(-10000f, -10000f)) }
    val particles = remember(size) { if (size == IntSize.Zero) emptyList() else buildParticles(mask) }

    var phase by remember { mutableStateOf(Phase.Assembling) }
    var phaseTimer by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0L) }

    LaunchedEffect(size, running, particles) {
        if (size == IntSize.Zero || !running || particles.isEmpty()) return@LaunchedEffect
        while (true) {
            withFrameNanos { nanos ->
                phaseTimer++
                if (phaseTimer > duration(phase)) {
                    phaseTimer = 0
                    phase = next(phase)
                    if (phase == Phase.Dispersing) particles.forEach { it.reseedWander() }
                }
                val f = minOf(size.width, size.height) / REF
                val gx = (size.width - REF * f) / 2f
                val gy = (size.height - REF * f) / 2f
                val refMouse = Offset((mouse.x - gx) / f, (mouse.y - gy) / f)
                val timeMs = nanos / 1_000_000f
                particles.forEach { it.update(phase, refMouse, timeMs) }
                tick = nanos
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Press ->
                                event.changes.firstOrNull()?.let { mouse = it.position }
                            PointerEventType.Exit, PointerEventType.Release ->
                                mouse = Offset(-10000f, -10000f)
                        }
                    }
                }
            }
    ) {
        if (tick < 0L) return@Canvas // read `tick` so each frame invalidates the draw
        if (particles.isEmpty()) return@Canvas
        val f = minOf(size.width, size.height) / REF
        val gx = (size.width - REF * f) / 2f
        val gy = (size.height - REF * f) / 2f
        particles.forEach { p ->
            drawCircle(
                color = tint,
                radius = max(0.1f, p.radius) * f,
                center = Offset(gx + p.x * f, gy + p.y * f),
            )
        }
    }
}
