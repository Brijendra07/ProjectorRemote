package com.brijendra.projectorremote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brijendra.projectorremote.Conn
import com.brijendra.projectorremote.RemoteViewModel
import com.brijendra.projectorremote.UiState
import com.brijendra.projectorremote.adb.TvApp
import kotlin.math.abs
import kotlin.math.roundToInt

// Android key codes
object K {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22
    const val OK = 23
    const val HOME = 3
    const val BACK = 4
    const val MENU = 82
    const val POWER = 26
    const val VOL_UP = 24
    const val VOL_DOWN = 25
    const val MUTE = 164
    const val PLAY_PAUSE = 85
    const val REWIND = 89
    const val FFWD = 90
    const val SETTINGS = 176
    const val INPUT = 178
    const val DEL = 67
    const val ENTER = 66
    const val SEARCH = 84
    const val RECENTS = 187
}

// ============================ Remote ============================

@Composable
fun RemoteTab(vm: RemoteViewModel, s: UiState) {
    val on = s.conn == Conn.CONNECTED
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Navigation keys
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { vm.key(K.BACK) }, size = 54.dp, enabled = on)
            IconKey(Icons.Rounded.Home, "Home", { vm.key(K.HOME) }, size = 54.dp, enabled = on)
            IconKey(
                Icons.Rounded.Menu, "Menu", { vm.key(K.MENU) },
                size = 54.dp, onLongPress = { vm.key(K.RECENTS) }, enabled = on
            )
            @Suppress("DEPRECATION")
            IconKey(Icons.Rounded.Input, "Input / HDMI", { vm.key(K.INPUT) }, size = 54.dp, enabled = on)
        }

        // D-pad sized to whatever space is left — never scrolls
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val d = minOf(maxWidth * 0.80f, maxHeight * 0.90f, 300.dp)
            DPad(vm, on, d)
        }

        // Volume + media
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            VolumePill(vm, on)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconKey(Icons.Rounded.FastRewind, "Rewind", { vm.key(K.REWIND) }, size = 48.dp, repeat = true, enabled = on)
                IconKey(
                    Icons.Rounded.PlayArrow, "Play / Pause", { vm.key(K.PLAY_PAUSE) },
                    size = 58.dp, tint = Color(0xFF1B1408), background = AccentBrush, enabled = on
                )
                IconKey(Icons.Rounded.FastForward, "Fast forward", { vm.key(K.FFWD) }, size = 48.dp, repeat = true, enabled = on)
            }
        }

        VSpace(18)
        QuickLaunch(vm, s)
        VSpace(10)
    }
}

@Composable
private fun QuickLaunch(vm: RemoteViewModel, s: UiState) {
    val apps = s.favoriteApps.take(RemoteViewModel.MAX_PINS)
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("Quick launch · hold to unpin")
        if (apps.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Glass)
                    .border(1.dp, Stroke, RoundedCornerShape(18.dp))
                    .padding(vertical = 16.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Hold any app in the Apps tab to pin it here",
                    color = TextFaint, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                apps.forEach { app ->
                    AppIconTile(
                        app, Modifier.weight(1f),
                        onClick = { vm.launch(app) },
                        onLongPress = { vm.toggleFavorite(app.pkg) }
                    )
                }
                repeat(RemoteViewModel.MAX_PINS - apps.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DPad(vm: RemoteViewModel, enabled: Boolean, size: androidx.compose.ui.unit.Dp) {
    val shape = CircleShape
    val arrow = size * 0.26f
    val icon = size * 0.13f
    val edge = size * 0.02f
    Box(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(Brush.radialGradient(listOf(Color(0xFF252A3A), Color(0xFF171A25))))
            .border(1.dp, Stroke, shape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(size * 0.60f)
                .clip(CircleShape)
                .border(1.dp, Stroke.copy(alpha = 0.6f), CircleShape)
        )
        val clear = SolidColor(Color.Transparent)
        PressSurface({ vm.key(K.UP) }, Modifier.align(Alignment.TopCenter).padding(top = edge).size(arrow),
            repeat = true, background = clear, bordered = false, enabled = enabled) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Up", tint = TextHi, modifier = Modifier.size(icon))
        }
        PressSurface({ vm.key(K.DOWN) }, Modifier.align(Alignment.BottomCenter).padding(bottom = edge).size(arrow),
            repeat = true, background = clear, bordered = false, enabled = enabled) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Down", tint = TextHi, modifier = Modifier.size(icon))
        }
        @Suppress("DEPRECATION")
        PressSurface({ vm.key(K.LEFT) }, Modifier.align(Alignment.CenterStart).padding(start = edge).size(arrow),
            repeat = true, background = clear, bordered = false, enabled = enabled) {
            Icon(Icons.Rounded.KeyboardArrowLeft, "Left", tint = TextHi, modifier = Modifier.size(icon))
        }
        @Suppress("DEPRECATION")
        PressSurface({ vm.key(K.RIGHT) }, Modifier.align(Alignment.CenterEnd).padding(end = edge).size(arrow),
            repeat = true, background = clear, bordered = false, enabled = enabled) {
            Icon(Icons.Rounded.KeyboardArrowRight, "Right", tint = TextHi, modifier = Modifier.size(icon))
        }
        PressSurface(
            { vm.key(K.OK) },
            Modifier.size(size * 0.38f),
            onLongPress = { vm.longKey(K.OK) },
            background = AccentBrush,
            bordered = false,
            enabled = enabled
        ) {
            Text("OK", color = Color(0xFF1B1408), fontSize = (size.value * 0.07f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Horizontal volume pill: −  [speaker = mute]  + */
@Composable
private fun VolumePill(vm: RemoteViewModel, enabled: Boolean) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(KeyBrush)
            .border(1.dp, Stroke, shape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val clear = SolidColor(Color.Transparent)
        PressSurface({ vm.key(K.VOL_DOWN) }, Modifier.size(52.dp), repeat = true,
            background = clear, bordered = false, enabled = enabled) {
            Icon(Icons.Rounded.Remove, "Volume down", tint = TextHi, modifier = Modifier.size(22.dp))
        }
        PressSurface({ vm.key(K.MUTE) }, Modifier.size(44.dp),
            background = clear, bordered = false, enabled = enabled) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Mute", tint = TextDim, modifier = Modifier.size(20.dp))
        }
        PressSurface({ vm.key(K.VOL_UP) }, Modifier.size(52.dp), repeat = true,
            background = clear, bordered = false, enabled = enabled) {
            Icon(Icons.Rounded.Add, "Volume up", tint = TextHi, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun AppIconTile(app: TvApp, modifier: Modifier, onClick: () -> Unit, onLongPress: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PressSurface(
            onClick,
            Modifier.size(54.dp),
            shape = RoundedCornerShape(16.dp),
            background = SolidColor(Glass),
            onLongPress = onLongPress
        ) {
            RemoteImage(app.iconUrl, Modifier.size(34.dp)) { LetterBadge(app.label, 34.dp) }
        }
        VSpace(6)
        Text(
            app.label, fontSize = 11.sp, color = TextDim, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )
    }
}

// ============================ Touchpad ============================

@Composable
fun TouchpadTab(vm: RemoteViewModel) {
    val haptic = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val stepPx = with(LocalDensity.current) { 46.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val glow by animateFloatAsState(if (touch != null) 1f else 0f, tween(250), label = "glow")
    val shape = RoundedCornerShape(30.dp)

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1D2130), Color(0xFF151824))))
                .border(1.dp, Stroke, shape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { touch = it; tryAwaitRelease(); touch = null },
                        onTap = {
                            if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.key(K.OK)
                        },
                        onDoubleTap = { vm.key(K.PLAY_PAUSE) },
                        onLongPress = {
                            if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.key(K.BACK)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f; touch = it },
                        onDragEnd = { touch = null },
                        onDragCancel = { touch = null },
                        onDrag = { change, amount ->
                            change.consume()
                            touch = change.position
                            dx += amount.x
                            dy += amount.y
                            if (abs(dx) >= stepPx || abs(dy) >= stepPx) {
                                val code = if (abs(dx) > abs(dy)) {
                                    if (dx > 0) K.RIGHT else K.LEFT
                                } else {
                                    if (dy > 0) K.DOWN else K.UP
                                }
                                vm.key(code)
                                if (hapticsOn) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                dx = 0f; dy = 0f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Swipe to move", color = TextDim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                VSpace(6)
                Text(
                    "Tap · OK     Double-tap · Play/Pause     Hold · Back",
                    color = TextFaint, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }
            touch?.let { p ->
                val r = with(LocalDensity.current) { 60.dp.toPx() }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset((p.x - r).roundToInt(), (p.y - r).roundToInt()) }
                        .size(120.dp)
                        .alpha(glow)
                        .background(Brush.radialGradient(listOf(Amber.copy(alpha = 0.35f), Color.Transparent)))
                )
            }
        }
        VSpace(16)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { vm.key(K.BACK) })
            IconKey(Icons.Rounded.Home, "Home", { vm.key(K.HOME) })
            IconKey(Icons.Rounded.PlayArrow, "Play / Pause", { vm.key(K.PLAY_PAUSE) })
            IconKey(Icons.AutoMirrored.Rounded.VolumeDown, "Volume down", { vm.key(K.VOL_DOWN) }, repeat = true)
            IconKey(Icons.AutoMirrored.Rounded.VolumeUp, "Volume up", { vm.key(K.VOL_UP) }, repeat = true)
        }
    }
}

// ============================ Apps ============================

@Composable
fun AppsTab(vm: RemoteViewModel, s: UiState) {
    var query by rememberSaveable { mutableStateOf("") }
    val apps = remember(s.apps, query) {
        if (query.isBlank()) s.apps
        else s.apps.filter { it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps", color = TextFaint) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = TextDim) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Icon(Icons.Rounded.Close, "Clear", tint = TextDim,
                            modifier = Modifier.clip(CircleShape).pointerInput(Unit) {
                                detectTapGestures { query = "" }
                            })
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = fieldColors(),
                modifier = Modifier.weight(1f)
            )
            HSpace(10)
            IconKey(Icons.Rounded.Refresh, "Refresh", vm::loadApps, size = 54.dp, tint = TextDim)
        }
        VSpace(6)
        Text(
            when {
                s.loadingApps -> "Loading apps…"
                apps.isEmpty() -> "No apps found"
                else -> "${apps.size} apps · hold an app to pin or unpin it"
            },
            color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(apps, key = { it.pkg }) { app ->
                AppCard(
                    app = app,
                    pinned = app.pkg in s.favorites,
                    onClick = { vm.launch(app) },
                    onLongPress = { vm.toggleFavorite(app.pkg) }
                )
            }
        }
    }
}

@Composable
private fun AppCard(app: TvApp, pinned: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            PressSurface(
                onClick,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(14.dp),
                background = SolidColor(Glass),
                onLongPress = onLongPress
            ) {
                if (app.bannerUrl != null) {
                    // TV banners are 16:9 — show them whole, never cropped
                    RemoteImage(app.bannerUrl, Modifier.fillMaxSize(), ContentScale.Fit) {
                        RemoteImage(app.iconUrl, Modifier.size(32.dp)) { LetterBadge(app.label, 32.dp) }
                    }
                } else {
                    RemoteImage(app.iconUrl, Modifier.size(34.dp)) { LetterBadge(app.label, 34.dp) }
                }
            }
            if (pinned) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(AccentBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PushPin, "Pinned", tint = Color(0xFF1B1408), modifier = Modifier.size(12.dp))
                }
            }
        }
        VSpace(6)
        Text(
            app.label,
            color = TextHi,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
        )
    }
}

// ============================ Keyboard ============================

@Composable
fun KeyboardTab(vm: RemoteViewModel) {
    var live by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf("") }

    fun onChange(new: String) {
        val old = text
        text = new
        if (!live) return
        when {
            new.startsWith(old) -> vm.text(new.substring(old.length))
            old.startsWith(new) -> repeat(old.length - new.length) { vm.key(K.DEL) }
            else -> {
                repeat(old.length) { vm.key(K.DEL) }
                vm.text(new)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Live typing", color = TextHi, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (live) "Each letter appears on the projector as you type"
                        else "Type, then tap Send",
                        color = TextDim, fontSize = 12.sp
                    )
                }
                Switch(checked = live, onCheckedChange = { live = it }, colors = switchColors())
            }
            VSpace(14)
            OutlinedTextField(
                value = text,
                onValueChange = ::onChange,
                placeholder = { Text("Select a search box on the projector, then type…", color = TextFaint) },
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = if (live) ImeAction.Search else ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSearch = { vm.key(K.ENTER) },
                    onSend = { vm.text(text); text = "" }
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (!live) {
                VSpace(12)
                GradientButton("Send to projector", { vm.text(text); text = "" }, enabled = text.isNotEmpty())
            }
        }

        VSpace(18)
        SectionLabel("Keys")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconKey(Icons.AutoMirrored.Rounded.Backspace, "Delete", {
                vm.key(K.DEL)
                if (text.isNotEmpty()) text = text.dropLast(1)
            }, repeat = true)
            IconKey(Icons.AutoMirrored.Rounded.KeyboardReturn, "Enter", { vm.key(K.ENTER) })
            IconKey(Icons.Rounded.Search, "Search", { vm.key(K.SEARCH) })
            IconKey(Icons.Rounded.Close, "Clear field", {
                repeat(40) { vm.key(K.DEL) }
                text = ""
            })
            IconKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { vm.key(K.BACK) })
        }
        VSpace(6)
        Text(
            "Hold ⌫ to keep deleting · ✕ clears the projector's text box",
            color = TextFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)
        )
    }
}

// ============================ Shared styles ============================

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amber,
    unfocusedBorderColor = Stroke,
    focusedContainerColor = Glass,
    unfocusedContainerColor = Glass,
    cursorColor = Amber,
    focusedTextColor = TextHi,
    unfocusedTextColor = TextHi,
    focusedLabelColor = Amber,
    unfocusedLabelColor = TextDim,
)

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFF1B1408),
    checkedTrackColor = Amber,
    uncheckedThumbColor = TextDim,
    uncheckedTrackColor = GlassHi,
    uncheckedBorderColor = Stroke,
)
