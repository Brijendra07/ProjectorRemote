package com.brijendra.projectorremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brijendra.projectorremote.Conn
import com.brijendra.projectorremote.RemoteViewModel
import com.brijendra.projectorremote.UiState
import kotlinx.coroutines.delay

private enum class Tab(val label: String, val icon: ImageVector) {
    REMOTE("Remote", Icons.Rounded.SettingsRemote),
    PAD("Touchpad", Icons.Rounded.TouchApp),
    APPS("Apps", Icons.Rounded.Apps),
    KEYS("Keyboard", Icons.Rounded.Keyboard),
    TOOLS("Tools", Icons.Rounded.Widgets),
}

@Composable
fun RemoteScreen(vm: RemoteViewModel) {
    val s by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.REMOTE) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val firstRun = s.host.isBlank() && s.conn != Conn.CONNECTED

    BackHandler(enabled = showSettings && !firstRun) { showSettings = false }

    CompositionLocalProvider(LocalHapticsEnabled provides s.haptics) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BackgroundBrush)
        ) {
            // Soft warm glow in the corner
            Box(
                Modifier
                    .size(420.dp)
                    .offset((-140).dp, (-160).dp)
                    .background(
                        Brush.radialGradient(listOf(Amber.copy(alpha = 0.13f), Color.Transparent))
                    )
            )

            if (showSettings || firstRun) {
                SettingsScreen(
                    vm = vm,
                    s = s,
                    firstRun = firstRun,
                    onBack = { showSettings = false }
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    TopBar(s, onSettings = { showSettings = true })
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        Crossfade(targetState = tab, label = "tab") { t ->
                            when (t) {
                                Tab.REMOTE -> RemoteTab(vm, s)
                                Tab.PAD -> TouchpadTab(vm)
                                Tab.APPS -> AppsTab(vm, s)
                                Tab.KEYS -> KeyboardTab(vm)
                                Tab.TOOLS -> ToolsTab(vm, s)
                            }
                        }
                    }
                    BottomBar(tab) { tab = it }
                }
            }

            Toast(s.message, onDone = vm::clearMessage)
        }
    }
}

@Composable
private fun TopBar(s: UiState, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                s.deviceName ?: "Projector",
                style = MaterialTheme.typography.headlineSmall,
                color = TextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            when {
                s.conn == Conn.CONNECTED -> StatusPill("Connected · ${s.host}", Mint, pulsing = false)
                s.message == "Disconnected" -> StatusPill("Disconnected", Rose, pulsing = false)
                com.brijendra.projectorremote.adb.Connector.needsPairing ->
                    StatusPill("Pairing needed · open Settings", Rose, pulsing = false)
                else -> StatusPill("Searching for projector…", Amber, pulsing = true)
            }
        }
        IconKey(
            icon = Icons.Rounded.Settings,
            label = "Settings",
            onPress = onSettings,
            size = 46.dp,
            tint = TextDim
        )
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Glass.copy(alpha = 0.92f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Tab.entries.forEach { t ->
            val on = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (on) GlassHi else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(t) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    t.icon,
                    contentDescription = t.label,
                    tint = if (on) Amber else TextFaint,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    t.label,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) TextHi else TextFaint
                )
            }
        }
    }
}

@Composable
private fun Toast(message: String?, onDone: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            onDone()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                message ?: "",
                color = TextHi,
                fontSize = 13.sp,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(GlassHi)
                    .padding(horizontal = 18.dp, vertical = 11.dp)
            )
        }
    }
}

// Spacer helper used by tab files
@Composable
internal fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
internal fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
