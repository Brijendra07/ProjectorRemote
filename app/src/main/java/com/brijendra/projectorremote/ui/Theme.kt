package com.brijendra.projectorremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette
val Ink = Color(0xFF0A0C11)
val Ink2 = Color(0xFF121520)
val Glass = Color(0xFF1A1E2A)
val GlassHi = Color(0xFF232838)
val Stroke = Color(0xFF2A3042)
val TextHi = Color(0xFFF3F5FA)
val TextDim = Color(0xFF8A91A5)
val TextFaint = Color(0xFF5B6275)
val Amber = Color(0xFFFFB86B)
val Coral = Color(0xFFFF7A59)
val Mint = Color(0xFF4ADE80)
val Rose = Color(0xFFF87171)

// Kept for older references
val Accent = Amber
val Surface1 = Glass
val Surface2 = GlassHi

val AccentBrush = Brush.linearGradient(listOf(Amber, Coral))
val BackgroundBrush = Brush.verticalGradient(listOf(Ink2, Ink))
val KeyBrush = Brush.verticalGradient(listOf(Color(0xFF262B3B), Color(0xFF1A1E2A)))

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1B1408),
    secondary = Coral,
    background = Ink,
    onBackground = TextHi,
    surface = Glass,
    onSurface = TextHi,
    surfaceVariant = GlassHi,
    onSurfaceVariant = TextDim,
    outline = Stroke,
    error = Rose,
)

private val Type = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
