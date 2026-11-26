package com.brijendra.projectorremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brijendra.projectorremote.Conn
import com.brijendra.projectorremote.RemoteViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

// Android key codes
private const val K_UP = 19
private const val K_DOWN = 20
private const val K_LEFT = 21
private const val K_RIGHT = 22
private const val K_OK = 23
private const val K_HOME = 3
private const val K_BACK = 4
private const val K_MENU = 82
private const val K_POWER = 26
private const val K_VOL_UP = 24
private const val K_VOL_DOWN = 25
private const val K_MUTE = 164
private const val K_PLAY_PAUSE = 85
private const val K_REWIND = 89
private const val K_FFWD = 90
private const val K_SETTINGS = 176
private const val K_DEL = 67
private const val K_ENTER = 66
private const val K_SEARCH = 84
private const val K_RECENTS = 187

private enum class Tab(val title: String) { REMOTE("Remote"), PAD("Touchpad"), TYPE("Type"), APPS("Apps") }

@Composable
fun RemoteScreen(vm: RemoteViewModel) {
    val s by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.REMOTE) }

    // Auto-hide status messages
    LaunchedEffect(s.message) {
        if (s.message != null) {
            delay(4000)
            vm.clearMessage()
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp)
        ) {
            Header(s.conn, s.host, onConnect = vm::connect, onDisconnect = vm::disconnect)

            s.message?.let {
                Text(
                    it,
                    color = TextDim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (s.conn != Conn.CONNECTED) {
                SetupCard(
                    host = s.host,
                    port = s.port,
                    busy = s.conn == Conn.CONNECTING,
                    onHost = vm::setHost,
                    onPort = vm::setPort,
                    onConnect = vm::connect,
                    onPair = vm::pair
                )
            } else {
                TabRow(tab) { tab = it }
                Spacer(Modifier.height(12.dp))

                when (tab) {
                    Tab.REMOTE -> RemotePad(vm)
                    Tab.PAD -> TouchPad(vm)
                    Tab.TYPE -> TypePad(vm)
                    Tab.APPS -> AppsPad(vm, s.apps, s.loadingApps)
                }
            }
        }
    }
}

@Composable
private fun Header(conn: Conn, host: String, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Projector Remote", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when (conn) {
                    Conn.CONNECTED -> Color(0xFF4ADE80)
                    Conn.CONNECTING -> Accent
                    Conn.DISCONNECTED -> Color(0xFFF87171)
                }
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when (conn) {
                        Conn.CONNECTED -> "Connected · $host"
                        Conn.CONNECTING -> "Connecting…"
                        Conn.DISCONNECTED -> "Not connected"
                    },
                    color = TextDim,
                    fontSize = 13.sp
                )
            }
        }
        when (conn) {
            Conn.CONNECTED -> TextButton(onClick = onDisconnect) { Text("Disconnect") }
            Conn.CONNECTING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Conn.DISCONNECTED -> if (host.isNotBlank()) TextButton(onClick = onConnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun SetupCard(
    host: String,
    port: String,
    busy: Boolean,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnect: () -> Unit,
    onPair: (String, String) -> Unit,
) {
    var pairPort by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Card {
            Text("1. Projector address", fontWeight = FontWeight.SemiBold)
            Text(
                "On the projector: Developer options → Wireless debugging → \"IP address & Port\"",
                color = TextDim, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = host, onValueChange = onHost, singleLine = true,
                label = { Text("IP address (e.g. 192.168.1.9)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port, onValueChange = onPort, singleLine = true,
                label = { Text("Port (e.g. 40923)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        Card {
            Text("2. First time only: pair", fontWeight = FontWeight.SemiBold)
            Text(
                "On the projector tap \"Pair device with pairing code\". Enter the port shown in that " +
                    "pop-up (it's different from the one above) and the 6-digit code.",
                color = TextDim, fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedTextField(
                    value = pairPort, onValueChange = { pairPort = it.filter(Char::isDigit) },
                    singleLine = true, label = { Text("Pairing port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    singleLine = true, label = { Text("6-digit code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onPair(pairPort, code) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Pair & connect") }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Already paired? Connect")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun TabRow(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(4.dp)
    ) {
        Tab.entries.forEach { t ->
            val on = t == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Surface2 else Color.Transparent)
                    .clickable { onSelect(t) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(t.title, color = if (on) Accent else TextDim, fontSize = 14.sp)
            }
        }
    }
}

/** A round/rounded key that vibrates and sends a key code. */
@Composable
private fun Key(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 64,
    circle: Boolean = true,
    accent: Boolean = false,
    fontSize: Int = 20,
    onLongClick: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .size(size.dp)
            .clip(if (circle) CircleShape else RoundedCornerShape(16.dp))
            .background(if (accent) Accent else Surface2)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                    onLongPress = onLongClick?.let { lc ->
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lc()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = fontSize.sp,
            color = if (accent) Surface1 else Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RemotePad(vm: RemoteViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Key("⏻", { vm.key(K_POWER) }, fontSize = 22)
            Key("⌂", { vm.key(K_HOME) }, fontSize = 26)
            Key("↩", { vm.key(K_BACK) }, fontSize = 24)
            Key("☰", { vm.key(K_MENU) }, onLongClick = { vm.key(K_RECENTS) })
            Key("⚙", { vm.key(K_SETTINGS) })
        }

        Spacer(Modifier.height(24.dp))

        // D-pad
        Box(
            Modifier
                .size(260.dp)
                .clip(CircleShape)
                .background(Surface1)
                .border(1.dp, Surface2, CircleShape)
        ) {
            Key("▲", { vm.key(K_UP) }, Modifier.align(Alignment.TopCenter).padding(top = 8.dp), circle = true, size = 72)
            Key("▼", { vm.key(K_DOWN) }, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), size = 72)
            Key("◀", { vm.key(K_LEFT) }, Modifier.align(Alignment.CenterStart).padding(start = 8.dp), size = 72)
            Key("▶", { vm.key(K_RIGHT) }, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), size = 72)
            Key("OK", { vm.key(K_OK) }, Modifier.align(Alignment.Center), size = 88, accent = true,
                onLongClick = { vm.longKey(K_OK) })
        }

        Spacer(Modifier.height(24.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Key("+", { vm.key(K_VOL_UP) }, circle = false, size = 60, fontSize = 26)
                Spacer(Modifier.height(6.dp))
                Text("VOL", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Key("−", { vm.key(K_VOL_DOWN) }, circle = false, size = 60, fontSize = 26)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    Key("⏪", { vm.key(K_REWIND) }, size = 56, fontSize = 18)
                    Spacer(Modifier.width(8.dp))
                    Key("⏯", { vm.key(K_PLAY_PAUSE) }, size = 56, fontSize = 18)
                    Spacer(Modifier.width(8.dp))
                    Key("⏩", { vm.key(K_FFWD) }, size = 56, fontSize = 18)
                }
                Spacer(Modifier.height(12.dp))
                Key("🔇", { vm.key(K_MUTE) }, size = 56, fontSize = 18)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Swipe to move (each step = one arrow press), tap = OK, long-press = Back. */
@Composable
private fun TouchPad(vm: RemoteViewModel) {
    val haptic = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) { 48.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(380.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Surface1)
                .border(1.dp, Surface2, RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.key(K_OK)
                        },
                        onDoubleTap = { vm.key(K_PLAY_PAUSE) },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.key(K_BACK)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = { dx = 0f; dy = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            dx += amount.x
                            dy += amount.y
                            if (abs(dx) >= stepPx || abs(dy) >= stepPx) {
                                val code = if (abs(dx) > abs(dy)) {
                                    if (dx > 0) K_RIGHT else K_LEFT
                                } else {
                                    if (dy > 0) K_DOWN else K_UP
                                }
                                vm.key(code)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                dx = 0f; dy = 0f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Swipe to move\nTap = OK · Double-tap = Play/Pause\nHold = Back",
                color = TextDim, textAlign = TextAlign.Center, fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Key("↩", { vm.key(K_BACK) }, fontSize = 24)
            Key("⌂", { vm.key(K_HOME) }, fontSize = 26)
            Key("−", { vm.key(K_VOL_DOWN) }, fontSize = 26)
            Key("+", { vm.key(K_VOL_UP) }, fontSize = 26)
        }
    }
}

@Composable
private fun TypePad(vm: RemoteViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Select a search box on the projector first, then type here.",
            color = TextDim, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text to send") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { vm.text(text); text = "" }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.text(text); text = "" },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text("Send text") }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Key("⌫", { vm.key(K_DEL) }, circle = false, onLongClick = { repeat(15) { vm.key(K_DEL) } })
            Key("⏎", { vm.key(K_ENTER) }, circle = false)
            Key("🔍", { vm.key(K_SEARCH) }, circle = false, fontSize = 18)
            Key("↩", { vm.key(K_BACK) }, circle = false)
        }
        Spacer(Modifier.height(8.dp))
        Text("Hold ⌫ to delete 15 characters", color = TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun AppsPad(vm: RemoteViewModel, apps: List<com.brijendra.projectorremote.adb.TvApp>, loading: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Apps on projector", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = vm::loadApps) { Text(if (loading) "Loading…" else "Refresh") }
        }
        if (apps.isEmpty() && !loading) {
            Text("No apps found yet. Tap Refresh.", color = TextDim, fontSize = 13.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(apps, key = { it.pkg }) { app ->
                Box(
                    Modifier
                        .height(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface1)
                        .clickable { vm.launch(app) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        app.label,
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        maxLines = 2
                    )
                }
            }
        }
    }
}
