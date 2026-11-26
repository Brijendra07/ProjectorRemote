package com.brijendra.projectorremote.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Frosted card used for every section. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .clip(shape)
            .background(Glass.copy(alpha = 0.82f))
            .border(1.dp, Stroke.copy(alpha = 0.7f), shape)
            .padding(padding),
        content = content
    )
}

/**
 * A pressable surface with a press animation and haptics.
 * repeat = fire on touch and keep firing while held (arrows, volume).
 * Otherwise fires on release; onLongPress fires after holding ~0.45 s.
 */
@Composable
fun PressSurface(
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    repeat: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    background: Brush = KeyBrush,
    bordered: Boolean = true,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val hapticsOn by rememberUpdatedState(LocalHapticsEnabled.current)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    val long by rememberUpdatedState(onLongPress)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(110), label = "press")

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(background)
            .then(if (bordered) Modifier.border(1.dp, Stroke, shape) else Modifier)
            .background(if (pressed) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .pointerInput(repeat, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    if (repeat) {
                        if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        press()
                        val job = scope.launch {
                            delay(420)
                            while (true) {
                                press()
                                delay(110)
                            }
                        }
                        waitForUpOrCancellation()
                        job.cancel()
                    } else {
                        var longFired = false
                        val job = scope.launch {
                            delay(450)
                            long?.let {
                                longFired = true
                                if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                it()
                            }
                        }
                        val up = waitForUpOrCancellation()
                        job.cancel()
                        if (up != null && !longFired) {
                            if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            press()
                        }
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Round icon key. */
@Composable
fun IconKey(
    icon: ImageVector,
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    tint: Color = TextHi,
    repeat: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    background: Brush = KeyBrush,
    enabled: Boolean = true,
) {
    PressSurface(
        onPress = onPress,
        modifier = modifier.size(size),
        repeat = repeat,
        onLongPress = onLongPress,
        background = background,
        enabled = enabled,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/** Little coloured dot + text, pulses while searching. */
@Composable
fun StatusPill(text: String, color: Color, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "a"
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(a)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = TextFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** Gradient call-to-action button. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PressSurface(
        onPress = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        background = if (enabled) AccentBrush else SolidColor(GlassHi),
        bordered = false,
        enabled = enabled,
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF1B1408) else TextDim,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

/** Secondary outlined button. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PressSurface(
        onPress = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        background = SolidColor(Color.Transparent),
        enabled = enabled,
    ) {
        Text(text, color = TextHi, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

// ---------- Network images (app icons/banners from the projector) ----------

object IconCache {
    private val mem = object : LruCache<String, ImageBitmap>(120) {}
    private val missing = HashSet<String>()

    suspend fun load(url: String): ImageBitmap? {
        mem.get(url)?.let { return it }
        if (url in missing) return null
        return withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 2500
                c.readTimeout = 5000
                if (c.responseCode != 200) {
                    missing += url
                    return@withContext null
                }
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    ?.also { mem.put(url, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: @Composable () -> Unit = {},
) {
    val img by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { IconCache.load(it) }
    }
    val bmp = img
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { placeholder() }
    }
}

/** Two-letter fallback badge when an app has no icon. */
@Composable
fun LetterBadge(label: String, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(AccentBrush),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label.take(1).uppercase(),
            color = Color(0xFF1B1408),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp
        )
    }
}
