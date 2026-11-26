package com.brijendra.projectorremote.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brijendra.projectorremote.Conn
import com.brijendra.projectorremote.RemoteViewModel
import com.brijendra.projectorremote.Restorable
import com.brijendra.projectorremote.UiState
import com.brijendra.projectorremote.adb.TvApp

@Composable
fun ToolsTab(vm: RemoteViewModel, s: UiState) {
    var showManager by rememberSaveable { mutableStateOf(false) }
    if (s.restorable != null) {
        RestoreScreen(vm, s.restorable, busy = s.toolBusy == "restore")
        return
    }
    if (showManager) {
        AppManager(vm, s, onClose = { showManager = false })
        return
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.castMedia(listOf(uri), video = true)
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) vm.castMedia(uris, video = false)
    }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installApk(uri)
    }
    var link by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(s.conn) { if (s.conn == Conn.CONNECTED) vm.loadSysInfo() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---- Play from phone ----
        SectionLabel("Play from your phone")
        GlassCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Rounded.VideoLibrary, "Video", Modifier.weight(1f), busy = s.toolBusy == "cast") {
                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
                ToolTile(Icons.Rounded.PhotoLibrary, "Photos", Modifier.weight(1f)) {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            s.castingLabel?.let { label ->
                VSpace(14)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassHi)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconKey(Icons.Rounded.SkipPrevious, "Previous / −10 s", { vm.key(K.LEFT) }, size = 40.dp)
                    HSpace(6)
                    IconKey(
                        Icons.Rounded.PlayArrow, "Play / Pause", { vm.key(K.PLAY_PAUSE) }, size = 44.dp,
                        tint = Color(0xFF1B1408), background = AccentBrush
                    )
                    HSpace(6)
                    IconKey(Icons.Rounded.SkipNext, "Next / +10 s", { vm.key(K.RIGHT) }, size = 40.dp)
                    HSpace(6)
                    IconKey(Icons.Rounded.Close, "Stop", vm::stopCasting, size = 40.dp, tint = Rose)
                }
            }
            VSpace(10)
            Text(
                "Your phone streams over Wi-Fi — keep it on the same network while it plays.",
                color = TextFaint, fontSize = 12.sp
            )
        }

        // ---- Open a link ----
        VSpace(22)
        SectionLabel("Open a link")
        GlassCard(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                placeholder = { Text("Paste a YouTube or web link", color = TextFaint) },
                leadingIcon = { Icon(Icons.Rounded.Link, null, tint = TextDim) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.openLink(link) }),
                modifier = Modifier.fillMaxWidth()
            )
            VSpace(12)
            GradientButton("Open on projector", { vm.openLink(link) }, enabled = link.isNotBlank())
            VSpace(10)
            Text(
                "Tip: in YouTube, Instagram or Chrome tap Share → Play on projector.",
                color = TextFaint, fontSize = 12.sp
            )
        }

        // ---- Projector tools ----
        VSpace(22)
        SectionLabel("Projector")
        GlassCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Rounded.Visibility, "Screen peek", Modifier.weight(1f), busy = s.toolBusy == "peek") {
                    vm.takeScreenshot()
                }
                ToolTile(Icons.Rounded.Memory, "Free memory", Modifier.weight(1f), busy = s.toolBusy == "ram") {
                    vm.freeMemory()
                }
            }
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Rounded.Download, "Install APK", Modifier.weight(1f), busy = s.toolBusy == "apk") {
                    pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                }
                ToolTile(Icons.Rounded.Apps, "App manager", Modifier.weight(1f)) { showManager = true }
            }
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTile(Icons.Rounded.Restore, "Restore apps", Modifier.weight(1f), busy = s.toolBusy == "restore") {
                    vm.findRestorable()
                }
                Box(Modifier.weight(1f))
            }
        }

        // ---- System info ----
        if (s.sysInfo.isNotEmpty()) {
            VSpace(22)
            SectionLabel("System")
            GlassCard(Modifier.fillMaxWidth()) {
                s.sysInfo.forEachIndexed { i, (k, v) ->
                    if (i > 0) VSpace(10)
                    Row {
                        Text(k, color = TextDim, fontSize = 13.sp, modifier = Modifier.weight(0.38f))
                        Text(v, color = TextHi, fontSize = 13.sp, modifier = Modifier.weight(0.62f), textAlign = TextAlign.End)
                    }
                }
            }
        }
        VSpace(20)
    }

    // ---- Screenshot viewer ----
    s.screenshot?.let { png ->
        val bmp = remember(png) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }
        Dialog(onDismissRequest = vm::closeScreenshot, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Glass)
                    .padding(14.dp)
            ) {
                Text("Projector screen", color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                VSpace(12)
                if (bmp != null) {
                    Image(
                        bmp, contentDescription = "Projector screen",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Stroke, RoundedCornerShape(12.dp))
                    )
                }
                VSpace(14)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Refresh", vm::takeScreenshot, Modifier.weight(1f), enabled = s.toolBusy == null)
                    GradientButton("Close", vm::closeScreenshot, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToolTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    PressSurface(
        onClick,
        modifier.height(84.dp),
        shape = RoundedCornerShape(18.dp),
        background = KeyBrush,
        enabled = !busy
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Amber, modifier = Modifier.size(26.dp))
            VSpace(6)
            Text(if (busy) "Working…" else label, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AppManager(vm: RemoteViewModel, s: UiState, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<TvApp?>(null) }
    var confirm by remember { mutableStateOf<Pair<TvApp, String>?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            IconKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClose, size = 44.dp, tint = TextDim)
            HSpace(12)
            Column {
                Text("App manager", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Tap an app for options", color = TextDim, fontSize = 12.sp)
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(s.apps, key = { it.pkg }) { app ->
                PressSurface(
                    { selected = app },
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    background = SolidColor(Glass)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlassHi),
                            contentAlignment = Alignment.Center
                        ) {
                            RemoteImage(app.iconUrl, Modifier.size(30.dp)) { LetterBadge(app.label, 30.dp) }
                        }
                        HSpace(12)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = TextHi, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.pkg, color = TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Glass,
            title = { Text(app.label, color = TextHi) },
            text = {
                Column {
                    ActionRow("Open") { vm.launchApp(app); selected = null }
                    ActionRow("Force stop") { vm.forceStop(app); selected = null }
                    ActionRow("Clear cache") { vm.clearAppCache(app); selected = null }
                    ActionRow("Update in Play Store") { vm.openPlayStore(app.pkg); selected = null }
                    ActionRow("Reset app (clear data)", Amber) { confirm = app to "reset"; selected = null }
                    ActionRow("Uninstall", Rose) { confirm = app to "uninstall"; selected = null }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Close", color = TextDim) }
            }
        )
    }

    confirm?.let { (app, action) ->
        val reset = action == "reset"
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Glass,
            title = { Text(if (reset) "Reset ${app.label}?" else "Uninstall ${app.label}?", color = TextHi) },
            text = {
                Text(
                    if (reset) "Clears the app's data, like a fresh install. You'll need to sign in again."
                    else "Preinstalled apps are only hidden and can be brought back with Tools → Restore apps. " +
                        "Apps you installed yourself are removed completely.",
                    color = TextDim
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (reset) vm.clearData(app) else vm.uninstall(app)
                    confirm = null
                }) { Text(if (reset) "Reset" else "Uninstall", color = if (reset) Amber else Rose) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel", color = TextHi) }
            }
        )
    }
}

@Composable
private fun ActionRow(label: String, color: Color = TextHi, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp)
    )
}

@Composable
private fun RestoreScreen(vm: RemoteViewModel, items: List<Restorable>, busy: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            IconKey(Icons.AutoMirrored.Rounded.ArrowBack, "Back", vm::closeRestore, size = 44.dp, tint = TextDim)
            HSpace(12)
            Column {
                Text("Restore apps", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Bring back preinstalled apps that were removed or disabled", color = TextDim, fontSize = 12.sp)
            }
        }
        if (items.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Nothing to restore", color = TextHi, fontWeight = FontWeight.SemiBold)
                VSpace(6)
                Text(
                    "No removed or disabled preinstalled apps were found. If Netflix is missing, it was fully " +
                        "uninstalled — reinstall it from the Play Store on the projector.",
                    color = TextDim, fontSize = 13.sp
                )
                VSpace(12)
                GradientButton("Open Netflix in Play Store", { vm.openPlayStore("com.netflix.ninja") }, enabled = !busy)
            }
            return
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(items, key = { it.pkg + it.disabled }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Glass)
                        .border(1.dp, Stroke.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, color = TextHi, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (r.disabled) "Disabled · " else "Removed · ") + r.pkg,
                            color = TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    HSpace(10)
                    PressSurface(
                        { vm.restore(r) },
                        Modifier.height(38.dp).padding(0.dp),
                        shape = RoundedCornerShape(12.dp),
                        background = AccentBrush,
                        bordered = false,
                        enabled = !busy
                    ) {
                        Text(
                            "Restore", color = Color(0xFF1B1408), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
