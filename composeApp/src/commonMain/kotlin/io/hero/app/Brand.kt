package io.hero.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.PathParser

// The HERO "H" monogram: two interlocking woven strokes. Authored in a 150×150
// space as three sub-shapes that are each filled independently (NonZero
// winding) — the negative-space inner channels come from each shape's own
// sub-path winding, so they must NOT be merged into a single path.
const val LOGO_VIEWBOX = 150f

private val LOGO_PATHS = listOf(
    "m90.3 2.6v28.2c3.1 0.7 6.1 1.7 9 3v-22.5h10.3v28.7c3.3 2.5 6.2 5.6 8.5 9.2l-0.4-0.6c0.6-2.2 0.9-4.7 0.9-7.2v-38.8h-28.3z",
    "m50.8 74.6v22.9h-10.4v-30c-2.9-3-5.9-6.5-8.2-9.8-0.7 2.2-1.1 4.7-1.1 7.3v41.4l28.7-0.1v-28.3c-2.9-0.4-6-1.5-9-3m46.5-19c-2.1 2.7-4.7 4.5-7.2 6.2 0.1 0.7 0.2 1.5 0.2 2.3v41.9h28.3v-43.4c0-11.4-11.6-28.2-37.2-30.6-1.8-0.2-3.7-0.3-6.2-0.3-4.6 0-8.8 0.4-12.2 1.2v8.7c3.3-0.9 7.6-1.6 12.2-1.5 17.1 0.1 34.4 9.5 34.4 23v33.6h-9.9v-33.5c-0.3-2.7-0.9-5.3-2.4-7.6",
    "m40.2 11.3h10.6v29.6c0 12.7 9.6 21.4 23.9 21.4 7.3 0.2 11.9-1.3 15.3-3.4 3.2-1.9 6.7-5.3 7.5-8l0.1-0.3c-0.9-0.8-5.7-3.8-8.1-5.1-1.6 3.3-5.9 7.7-14.5 7.7-8.8 0.1-15-3.9-15-12v-38.6h-28.9v37.4c0 18.7 14.8 36.8 43.9 36.9 4.4 0 8.9-0.5 12-1.2v-9.2c-3 0.8-7.3 1.6-12 1.6-16 0.1-25-6.2-29-11.2-2.8-3.5-5.8-7.7-5.8-15.9v-29.7z",
)

fun heroLogoPaths(): List<Path> = LOGO_PATHS.map { PathParser().parsePathString(it).toPath() }

/** Union bounds of the mark inside the 150×150 authoring space. */
fun heroLogoBounds(paths: List<Path>): Rect {
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    for (p in paths) {
        val bb = p.getBounds()
        if (bb.left < l) l = bb.left
        if (bb.top < t) t = bb.top
        if (bb.right > r) r = bb.right
        if (bb.bottom > b) b = bb.bottom
    }
    return Rect(l, t, r, b)
}

/** Static logo lockup: fills the mark centered into [modifier]'s bounds. */
@Composable
fun LogoMark(modifier: Modifier = Modifier, tint: Color) {
    val paths = remember { heroLogoPaths() }
    val bounds = remember(paths) { heroLogoBounds(paths) }
    Canvas(modifier) {
        val pad = 0.06f * size.minDimension
        val s = minOf((size.width - 2 * pad) / bounds.width, (size.height - 2 * pad) / bounds.height)
        val dx = (size.width - bounds.width * s) / 2f - bounds.left * s
        val dy = (size.height - bounds.height * s) / 2f - bounds.top * s
        translate(dx, dy) {
            scale(s, s, pivot = Offset.Zero) {
                paths.forEach { drawPath(it, color = tint) }
            }
        }
    }
}

/**
 * Fills the mark centered into a square [sizePx] bitmap (optionally over [bg]).
 * Pure (no Compose runtime) so it doubles as the desktop window icon source.
 */
fun logoImageBitmap(sizePx: Int, fg: Color, bg: Color? = null): ImageBitmap {
    val paths = heroLogoPaths()
    val bounds = heroLogoBounds(paths)
    val bmp = ImageBitmap(sizePx, sizePx)
    val canvas = GraphicsCanvas(bmp)
    if (bg != null) {
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), Paint().apply { color = bg })
    }
    val pad = 0.10f * sizePx
    val s = minOf((sizePx - 2 * pad) / bounds.width, (sizePx - 2 * pad) / bounds.height)
    val dx = (sizePx - bounds.width * s) / 2f - bounds.left * s
    val dy = (sizePx - bounds.height * s) / 2f - bounds.top * s
    val paint = Paint().apply { color = fg; isAntiAlias = true }
    canvas.save()
    canvas.translate(dx, dy)
    canvas.scale(s, s)
    paths.forEach { canvas.drawPath(it, paint) }
    canvas.restore()
    return bmp
}

/** Raw (native-coordinate) alpha fill of the mark, for particle sampling. */
fun logoAlphaMask(res: Int): PixelMap {
    val paths = heroLogoPaths()
    val bmp = ImageBitmap(res, res)
    val canvas = GraphicsCanvas(bmp)
    val s = res / LOGO_VIEWBOX
    val paint = Paint().apply { color = Color.Black; isAntiAlias = true }
    canvas.save()
    canvas.scale(s, s)
    paths.forEach { canvas.drawPath(it, paint) }
    canvas.restore()
    return bmp.toPixelMap()
}
