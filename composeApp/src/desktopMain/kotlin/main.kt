package io.hero.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    val icon = remember { BitmapPainter(logoImageBitmap(128, fg = Color(0xFFFAFAFA), bg = Color(0xFF1A1A1A))) }
    // HERO_WINDOW_SIZE=WxH (e.g. 420x800) pins the initial size — used to
    // exercise the compact layout and take fixed-size screenshots in dev.
    val initialSize = remember {
        System.getenv("HERO_WINDOW_SIZE")?.split('x', 'X')?.takeIf { it.size == 2 }
            ?.let { (w, h) ->
                val ww = w.trim().toIntOrNull(); val hh = h.trim().toIntOrNull()
                if (ww != null && hh != null) DpSize(ww.dp, hh.dp) else null
            } ?: DpSize(1100.dp, 720.dp)
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "HERO",
        icon = icon,
        state = rememberWindowState(size = initialSize),
        onPreviewKeyEvent = { AppKeys.dispatch(it) },
    ) {
        // Floor, not a target: small enough that the compact layout still fits,
        // large enough that nothing degenerate renders.
        LaunchedEffect(Unit) { window.minimumSize = Dimension(380, 520) }
        App()
    }
}
