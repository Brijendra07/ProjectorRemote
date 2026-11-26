package com.brijendra.projectorremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brijendra.projectorremote.BuildConfig
import com.brijendra.projectorremote.Conn
import com.brijendra.projectorremote.RemoteViewModel
import com.brijendra.projectorremote.UiState

@Composable
fun SettingsScreen(vm: RemoteViewModel, s: UiState, firstRun: Boolean, onBack: () -> Unit) {
    LaunchedEffect(Unit) { if (!firstRun) vm.checkHelper() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.padding(top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!firstRun) {
                IconKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack, size = 46.dp, tint = TextDim)
                HSpace(14)
            }
            Column {
                Text(
                    if (firstRun) "Welcome" else "Settings",
                    color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.Bold
                )
                if (firstRun) {
                    Text("Let's connect your projector", color = TextDim, fontSize = 14.sp)
                }
            }
        }

        if (firstRun) {
            GlassCard(Modifier.fillMaxWidth()) {
                Step(1, "On the projector, open Developer options → Wireless debugging and switch it on.")
                VSpace(10)
                Step(2, "Enter the IP address and port shown under \"IP address & Port\".")
                VSpace(10)
                Step(3, "Tap \"Pair device with pairing code\" on the projector and enter that port and code below.")
            }
            VSpace(18)
        }

        // ---------- Connection ----------
        SectionLabel("Projector")
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Status", color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                when (s.conn) {
                    Conn.CONNECTED -> StatusPill("Connected", Mint, false)
                    Conn.CONNECTING -> StatusPill("Connecting…", Amber, true)
                    Conn.DISCONNECTED -> StatusPill("Not connected", Rose, false)
                }
            }
            VSpace(14)
            Row {
                OutlinedTextField(
                    value = s.host, onValueChange = vm::setHost, singleLine = true,
                    label = { Text("IP address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1.4f)
                )
                HSpace(10)
                OutlinedTextField(
                    value = s.port, onValueChange = vm::setPort, singleLine = true,
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
            }
            VSpace(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.conn == Conn.CONNECTED) {
                    GhostButton("Disconnect", vm::disconnect, Modifier.weight(1f))
                } else {
                    GradientButton("Connect", vm::connect, Modifier.weight(1f), enabled = s.conn != Conn.CONNECTING)
                }
            }
        }

        // ---------- Pairing ----------
        VSpace(22)
        SectionLabel(if (firstRun) "Pair (first time only)" else "Pair a new phone")
        PairCard(vm, s)

        if (!firstRun) {
            // ---------- Auto-start ----------
            VSpace(22)
            SectionLabel("Auto-start")
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Projector Helper", color = TextHi, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Turns Wireless debugging on after every restart, shares app icons, and helps this app find the projector.",
                            color = TextDim, fontSize = 12.sp
                        )
                    }
                }
                VSpace(12)
                StatusPill(
                    s.helperStatus ?: "Checking…",
                    if (s.helperActive) Mint else Amber,
                    pulsing = s.helperStatus == null
                )
                VSpace(14)
                GradientButton(
                    if (s.helperBusy) "Working…" else if (s.helperActive) "Repair / update helper" else "Set up auto-start",
                    { vm.setupHelper(forceReinstall = s.helperActive) },
                    enabled = !s.helperBusy && s.conn == Conn.CONNECTED
                )
                if (s.helperLog.isNotEmpty()) {
                    VSpace(12)
                    s.helperLog.forEach {
                        Text("•  $it", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }

            // ---------- Preferences ----------
            VSpace(22)
            SectionLabel("Preferences")
            GlassCard(Modifier.fillMaxWidth()) {
                ToggleRow(
                    "Phone volume buttons",
                    "Use your phone's volume keys to change the projector's volume while the app is open",
                    s.volumeKeys, vm::setVolumeKeys
                )
                VSpace(14)
                ToggleRow("Haptic feedback", "Vibrate softly on every button press", s.haptics, vm::setHaptics)
            }

            VSpace(22)
            Text(
                "Projector Remote ${BuildConfig.VERSION_NAME}",
                color = TextFaint, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        VSpace(32)
    }
}

@Composable
private fun PairCard(vm: RemoteViewModel, s: UiState) {
    var pairPort by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "On the projector tap \"Pair device with pairing code\". Enter the port from that pop-up (not the one above) and the 6-digit code.",
            color = TextDim, fontSize = 13.sp
        )
        VSpace(12)
        Row {
            OutlinedTextField(
                value = pairPort, onValueChange = { pairPort = it.filter(Char::isDigit) },
                singleLine = true, label = { Text("Pairing port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
            HSpace(10)
            OutlinedTextField(
                value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
                singleLine = true, label = { Text("6-digit code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = fieldColors(), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
        }
        VSpace(14)
        GradientButton(
            "Pair & connect",
            { vm.pair(pairPort, code) },
            enabled = s.conn != Conn.CONNECTING && code.length == 6 && pairPort.isNotEmpty()
        )
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AccentBrush),
            contentAlignment = Alignment.Center
        ) {
            Text("$n", color = Color(0xFF1B1408), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        HSpace(12)
        Text(text, color = TextHi, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(subtitle, color = TextDim, fontSize = 12.sp)
        }
        HSpace(12)
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors())
    }
}
