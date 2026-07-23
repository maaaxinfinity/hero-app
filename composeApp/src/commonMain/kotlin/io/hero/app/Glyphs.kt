package io.hero.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Hand-drawn line glyphs for the dock and small controls. The bundled
// icons-core set has no honest chat/server/update marks, and 1.8f round-cap
// line art matches the ink identity better than the filled Material style —
// authored in the same 24×24 reference space as BackendMark.

private const val GLYPH_REF = 24f
private const val LINE = 1.8f

private fun DrawScope.lineStroke() = Stroke(width = LINE, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** SectionGlyph draws the dock mark for one navigation section. */
@Composable
fun SectionGlyph(section: Section, modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val s = size.minDimension / GLYPH_REF
        scale(s, s, pivot = Offset.Zero) {
            when (section) {
                Section.Sessions -> sessionsGlyph(tint)
                Section.Attention -> bellGlyph(tint)
                Section.Nodes -> nodesGlyph(tint)
                Section.Control -> updatesGlyph(tint)
                Section.Users -> usersGlyph(tint)
                Section.Audit -> auditGlyph(tint)
                Section.Prefs -> prefsGlyph(tint)
            }
        }
    }
}

// Terminal: window outline, prompt chevron, input line — the workspace is
// where you drive coding agents, not a chat app.
private fun DrawScope.sessionsGlyph(tint: Color) {
    drawRoundRect(
        color = tint, topLeft = Offset(3f, 4.5f), size = Size(18f, 15f),
        cornerRadius = CornerRadius(2.5f, 2.5f), style = lineStroke(),
    )
    val prompt = Path().apply {
        moveTo(6.8f, 9.4f); lineTo(10f, 12f); lineTo(6.8f, 14.6f)
    }
    drawPath(prompt, tint, style = lineStroke())
    drawLine(tint, Offset(12.4f, 15.6f), Offset(17.2f, 15.6f), LINE, StrokeCap.Round)
}

// Bell: dome + rim + clapper — the attention inbox.
private fun DrawScope.bellGlyph(tint: Color) {
    val bell = Path().apply {
        moveTo(6.5f, 16.5f)
        cubicTo(6.5f, 11f, 7.5f, 6.5f, 12f, 6.5f)
        cubicTo(16.5f, 6.5f, 17.5f, 11f, 17.5f, 16.5f)
    }
    drawPath(bell, tint, style = lineStroke())
    drawLine(tint, Offset(4.6f, 16.5f), Offset(19.4f, 16.5f), LINE, StrokeCap.Round)
    drawLine(tint, Offset(12f, 4.4f), Offset(12f, 6.5f), LINE, StrokeCap.Round)
    drawCircle(tint, radius = 1.5f, center = Offset(12f, 18.4f), style = lineStroke())
}

// Two stacked machines, each with a status dot.
private fun DrawScope.nodesGlyph(tint: Color) {
    drawRoundRect(
        color = tint, topLeft = Offset(4f, 4.5f), size = Size(16f, 6.2f),
        cornerRadius = CornerRadius(2f, 2f), style = lineStroke(),
    )
    drawRoundRect(
        color = tint, topLeft = Offset(4f, 13.3f), size = Size(16f, 6.2f),
        cornerRadius = CornerRadius(2f, 2f), style = lineStroke(),
    )
    drawCircle(tint, radius = 1.1f, center = Offset(7.3f, 7.6f))
    drawCircle(tint, radius = 1.1f, center = Offset(7.3f, 16.4f))
}

// Head + shoulders.
private fun DrawScope.usersGlyph(tint: Color) {
    drawCircle(tint, radius = 3.4f, center = Offset(12f, 7.8f), style = lineStroke())
    val shoulders = Path().apply {
        moveTo(5.5f, 19.5f)
        cubicTo(5.5f, 15.2f, 8.4f, 13.6f, 12f, 13.6f)
        cubicTo(15.6f, 13.6f, 18.5f, 15.2f, 18.5f, 19.5f)
    }
    drawPath(shoulders, tint, style = lineStroke())
}

// Circle with an up arrow.
private fun DrawScope.updatesGlyph(tint: Color) {
    drawCircle(tint, radius = 8.2f, center = Offset(12f, 12f), style = lineStroke())
    drawLine(tint, Offset(12f, 16.2f), Offset(12f, 8.4f), LINE, StrokeCap.Round)
    drawLine(tint, Offset(9f, 11.3f), Offset(12f, 8.2f), LINE, StrokeCap.Round)
    drawLine(tint, Offset(15f, 11.3f), Offset(12f, 8.2f), LINE, StrokeCap.Round)
}

// Bulleted list.
private fun DrawScope.auditGlyph(tint: Color) {
    for (y in listOf(6.5f, 12f, 17.5f)) {
        drawCircle(tint, radius = 1.2f, center = Offset(5.2f, y))
        drawLine(tint, Offset(9.2f, y), Offset(19.5f, y), LINE, StrokeCap.Round)
    }
}

// Gear: ring + six radial teeth + hub.
private fun DrawScope.prefsGlyph(tint: Color) {
    drawCircle(tint, radius = 5.6f, center = Offset(12f, 12f), style = lineStroke())
    drawCircle(tint, radius = 1.9f, center = Offset(12f, 12f), style = lineStroke())
    for (i in 0 until 6) {
        val a = (PI / 3.0 * i).toFloat()
        val cosA = cos(a); val sinA = sin(a)
        drawLine(
            tint,
            Offset(12f + 5.6f * cosA, 12f + 5.6f * sinA),
            Offset(12f + 8.6f * cosA, 12f + 8.6f * sinA),
            LINE, StrokeCap.Round,
        )
    }
}

/** ViewGlyph is the card/list view-mode mark: a 2×2 grid or three list lines. */
@Composable
fun ViewGlyph(grid: Boolean, modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val s = size.minDimension / GLYPH_REF
        scale(s, s, pivot = Offset.Zero) {
            if (grid) {
                for (x in listOf(4.5f, 13.5f)) for (y in listOf(4.5f, 13.5f)) {
                    drawRoundRect(
                        color = tint, topLeft = Offset(x, y), size = Size(6f, 6f),
                        cornerRadius = CornerRadius(1.5f, 1.5f), style = lineStroke(),
                    )
                }
            } else {
                for (y in listOf(6.5f, 12f, 17.5f)) {
                    drawLine(tint, Offset(4.5f, y), Offset(19.5f, y), LINE, StrokeCap.Round)
                }
            }
        }
    }
}

/** EyeGlyph is the password show/hide mark: almond outline + pupil, slashed when hidden. */
@Composable
fun EyeGlyph(open: Boolean, modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val s = size.minDimension / GLYPH_REF
        scale(s, s, pivot = Offset.Zero) {
            val eye = Path().apply {
                moveTo(3f, 12f)
                cubicTo(6f, 6.6f, 18f, 6.6f, 21f, 12f)
                cubicTo(18f, 17.4f, 6f, 17.4f, 3f, 12f)
                close()
            }
            drawPath(eye, tint, style = lineStroke())
            drawCircle(tint, radius = 2.4f, center = Offset(12f, 12f))
            if (!open) drawLine(tint, Offset(5.2f, 19f), Offset(18.8f, 5f), LINE, StrokeCap.Round)
        }
    }
}
