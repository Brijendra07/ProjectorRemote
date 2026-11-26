package com.brijendra.projectorremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFB86B)
val Bg = Color(0xFF12151C)
val Surface1 = Color(0xFF1B1F2A)
val Surface2 = Color(0xFF262B38)
val TextDim = Color(0xFF9AA3B5)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1B1F2A),
    background = Bg,
    onBackground = Color.White,
    surface = Surface1,
    onSurface = Color.White,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
)

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
