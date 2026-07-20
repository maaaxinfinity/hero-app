package io.hero.app

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val icon = remember { BitmapPainter(logoImageBitmap(128, fg = Color(0xFFFAFAFA), bg = Color(0xFF1A1A1A))) }
    Window(onCloseRequest = ::exitApplication, title = "HERO", icon = icon) {
        App()
    }
}
