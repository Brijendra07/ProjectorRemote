package com.brijendra.projectorremote

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.brijendra.projectorremote.adb.Connector
import com.brijendra.projectorremote.adb.HelperClient
import com.brijendra.projectorremote.media.Caster
import com.brijendra.projectorremote.service.RemoteService
import com.brijendra.projectorremote.adb.RemoteController
import com.brijendra.projectorremote.adb.TvApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

/** Result of the "Fix an app" scan. */
data class DoctorResult(val query: String, val packages: List<String>, val report: String)

/** A preinstalled app that was uninstalled (kept by Android) or disabled — can be brought back. */
data class Restorable(val pkg: String, val label: String, val disabled: Boolean)

data class UiState(
    val conn: Conn = Conn.DISCONNECTED,
    val message: String? = null,
    val host: String = "",
    val port: String = "",
    val deviceName: String? = null,
    val apps: List<TvApp> = emptyList(),
    val loadingApps: Boolean = false,
    val favorites: List<String> = emptyList(),
    val helperBusy: Boolean = false,
    val helperLog: List<String> = emptyList(),
    val helperStatus: String? = null,
    val helperActive: Boolean = false,
    val volumeKeys: Boolean = true,
    val haptics: Boolean = true,
    val notifRemote: Boolean = true,
    // Tools
    val castingLabel: String? = null,
    val sysInfo: List<Pair<String, String>> = emptyList(),
    val screenshot: ByteArray? = null,
    val toolBusy: String? = null,
    val restorable: List<Restorable>? = null,
    val doctor: DoctorResult? = null,
    val healthLog: List<String> = emptyList(),
) {
    val favoriteApps: List<TvApp>
        get() = favorites.mapNotNull { f -> apps.firstOrNull { it.pkg == f } }
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_PINS = 5
    }

    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val remote = RemoteController.get(app)
    private val ctx: Context get() = getApplication<Application>()

    private var connectJob: Job? = null
    private var userDisconnected = false
    private var inForeground = true
    private var helperUpdateTried = false

    private val _state = MutableStateFlow(
        UiState(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getString("port", "") ?: "",
            deviceName = prefs.getString("name", null),
            favorites = prefs.getString("favs", null)
                ?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
            volumeKeys = prefs.getBoolean("volumeKeys", true),
            haptics = prefs.getBoolean("haptics", true),
            notifRemote = prefs.getBoolean("notifRemote", true),
            healthLog = prefs.getString("healthLog", "")!!.split("\n").filter { it.isNotBlank() },
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (!prefs.getBoolean("favsV2", false)) {
            val migrated = _state.value.favorites.reversed().take(MAX_PINS)
            prefs.edit().putString("favs", migrated.joinToString(",")).putBoolean("favsV2", true).apply()
            _state.update { it.copy(favorites = migrated) }
        }
        connect()
        startAutoReconnect()
    }

    val hasSetup: Boolean get() = _state.value.host.isNotBlank()

    fun setHost(v: String) = _state.update { it.copy(host = v.trim()) }
    fun setPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    fun setVolumeKeys(on: Boolean) {
        prefs.edit().putBoolean("volumeKeys", on).apply()
        _state.update { it.copy(volumeKeys = on) }
    }

    fun setHaptics(on: Boolean) {
        prefs.edit().putBoolean("haptics", on).apply()
        _state.update { it.copy(haptics = on) }
    }

    fun setNotifRemote(on: Boolean) {
        prefs.edit().putBoolean("notifRemote", on).apply()
        _state.update { it.copy(notifRemote = on) }
        if (on && _state.value.conn == Conn.CONNECTED) RemoteService.start(ctx)
        if (!on && _state.value.castingLabel == null) RemoteService.stop(ctx)
    }

    // ---------- Pairing & connecting ----------

    fun pair(pairPort: String, code: String) {
        val host = _state.value.host
        val p = pairPort.toIntOrNull()
        if (host.isBlank() || p == null || code.length != 6) {
            _state.update { it.copy(message = "Enter the projector IP, pairing port and the 6-digit code") }
            return
        }
        prefs.edit().putString("host", host).putString("port", _state.value.port).apply()
        _state.update { it.copy(conn = Conn.CONNECTING, message = "Pairing…") }
        viewModelScope.launch {
            val ok = try {
                remote.pair(host, p, code)
            } catch (e: Throwable) {
                _state.update { it.copy(conn = Conn.DISCONNECTED, message = "Pairing failed: ${e.message ?: e.javaClass.simpleName}") }
                return@launch
            }
            if (ok) {
                _state.update { it.copy(message = "Paired! Connecting…") }
                connect()
            } else {
                _state.update { it.copy(conn = Conn.DISCONNECTED, message = "Pairing failed. Check the code and try again.") }
            }
        }
    }

    fun connect() {
        userDisconnected = false
        // Settings screen may have edited the IP/port
        val st = _state.value
        if (st.host.isNotBlank()) {
            prefs.edit().putString("host", st.host).putString("port", st.port).apply()
        }
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch { attempt(full = true) }
    }

    private suspend fun attempt(full: Boolean): Boolean {
        _state.update { it.copy(conn = Conn.CONNECTING) }
        val ok = Connector.attempt(ctx, full)
        _state.update {
            it.copy(
                conn = if (ok) Conn.CONNECTED else Conn.DISCONNECTED,
                message = when {
                    ok -> null
                    Connector.needsPairing -> "This phone needs to be paired again — open Settings"
                    else -> it.message
                },
                host = Connector.savedHost(ctx).ifBlank { it.host },
                port = Connector.savedPort(ctx)?.toString() ?: it.port,
            )
        }
        if (ok) onConnected()
        return ok
    }

    private fun onConnected() {
        viewModelScope.launch {
            try { healthCheck() } catch (_: Throwable) {}
            loadApps()
        }
        if (_state.value.notifRemote) RemoteService.start(ctx)
        viewModelScope.launch {
            val info = HelperClient.query(_state.value.host)
            info?.name?.let { name ->
                prefs.edit().putString("name", name).apply()
                _state.update { it.copy(deviceName = name) }
            }
            _state.update { it.copy(helperActive = info?.permission == true) }
            // Old helper on the projector? Update it quietly (once per app start).
            if (info != null && info.version < HelperClient.LATEST_VERSION && !helperUpdateTried) {
                helperUpdateTried = true
                setupHelper(forceReinstall = true)
            }
        }
    }

    private fun startAutoReconnect() {
        viewModelScope.launch {
            var tries = 0
            while (true) {
                delay(4_000)
                if (!inForeground || userDisconnected) continue
                if (connectJob?.isActive == true) continue
                val st = _state.value
                if (st.conn == Conn.CONNECTED) {
                    tries = 0
                    val alive = Connector.isAlive(ctx)
                    if (!alive) _state.update { it.copy(conn = Conn.DISCONNECTED) }
                    delay(4_000)
                } else if (st.host.isNotBlank()) {
                    tries++
                    connectJob = viewModelScope.launch { attempt(full = tries % 4 == 0) }
                }
            }
        }
    }

    fun onForeground(visible: Boolean) {
        inForeground = visible
        if (visible && !userDisconnected && _state.value.conn == Conn.DISCONNECTED) connect()
    }

    fun disconnect() {
        userDisconnected = true
        connectJob?.cancel()
        viewModelScope.launch {
            remote.disconnect()
            _state.update { it.copy(conn = Conn.DISCONNECTED, message = "Disconnected") }
        }
    }

    // ---------- Controls ----------

    fun key(code: Int) = send { remote.key(code) }
    fun longKey(code: Int) = send { remote.longKey(code) }
    fun text(value: String) = send { remote.text(value) }
    fun launch(app: TvApp) = send { remote.launch(app) }

    private fun send(block: suspend () -> Unit) {
        if (_state.value.conn != Conn.CONNECTED) {
            _state.update { it.copy(message = "Not connected yet") }
            return
        }
        viewModelScope.launch {
            try {
                block()
            } catch (_: Throwable) {
                _state.update { it.copy(conn = Conn.DISCONNECTED) }
            }
        }
    }

    // ---------- Apps ----------

    fun loadApps() {
        _state.update { it.copy(loadingApps = true) }
        viewModelScope.launch {
            // Use both sources so no app is ever missing:
            // the helper (real names + icons) and ADB (sees everything).
            val host = _state.value.host
            val fromHelper = HelperClient.apps(host)
            val helperUp = fromHelper != null || HelperClient.query(host) != null
            val fromAdb = try { remote.listApps() } catch (_: Throwable) { emptyList() }
            val merged = (fromHelper ?: emptyList()).toMutableList()
            val have = merged.map { it.pkg }.toHashSet()
            fromAdb.filter { it.pkg !in have }.forEach { a ->
                // Icons work as long as the helper answers, even if its list didn't.
                merged += if (helperUp) a.copy(iconUrl = HelperClient.iconUrl(host, a.pkg)) else a
            }
            val apps = merged.sortedBy { it.label.lowercase() }
            _state.update { it.copy(apps = apps, loadingApps = false) }
        }
    }

    fun isFavorite(pkg: String) = pkg in _state.value.favorites

    fun toggleFavorite(pkg: String) {
        val cur = _state.value.favorites
        val label = _state.value.apps.firstOrNull { it.pkg == pkg }?.label ?: "App"
        val next: List<String>
        val msg: String
        if (pkg in cur) {
            next = cur - pkg
            msg = "Removed $label from Quick launch"
        } else if (_state.value.favoriteApps.size >= MAX_PINS) {
            _state.update { it.copy(message = "Quick launch holds $MAX_PINS apps — unpin one first") }
            return
        } else {
            // newest first; drop pins for apps that no longer exist
            val visible = _state.value.apps.map { it.pkg }.toSet()
            next = listOf(pkg) + cur.filter { it in visible }
            msg = "Pinned $label to Quick launch"
        }
        prefs.edit().putString("favs", next.joinToString(",")).apply()
        _state.update { it.copy(favorites = next, message = msg) }
    }

    // ---------- Auto-start helper ----------

    private fun log(line: String) = _state.update { it.copy(helperLog = it.helperLog + line) }

    fun checkHelper() {
        viewModelScope.launch {
            val info = HelperClient.query(_state.value.host)
            _state.update {
                it.copy(
                    helperActive = info?.permission == true,
                    helperStatus = when {
                        info == null -> "Not running on the projector"
                        !info.permission -> "Running, but permission is missing"
                        else -> "Active · v${info.version}"
                    }
                )
            }
        }
    }

    fun setupHelper(forceReinstall: Boolean = false) {
        if (_state.value.conn != Conn.CONNECTED) {
            _state.update { it.copy(message = "Connect first") }
            return
        }
        _state.update { it.copy(helperBusy = true, helperLog = emptyList()) }
        viewModelScope.launch {
            try {
                val pkg = HelperClient.PACKAGE
                log("Checking projector…")
                val installed = remote.shellOutput("pm path $pkg").contains("package:")
                if (!installed || forceReinstall) {
                    log("Downloading helper…")
                    val apk = HelperClient.downloadApk()
                    log("Installing on projector (${apk.size / 1024} KB)…")
                    val result = remote.installApk(apk)
                    if (!result.contains("Success")) error("Install failed: $result")
                    log("Installed")
                } else {
                    log("Helper already installed")
                }
                log("Granting permission…")
                val grant = remote.shellOutput("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS").trim()
                if (grant.isNotEmpty()) log(grant)
                log("Starting helper…")
                remote.shellOutput("am start-foreground-service -n $pkg/.KeeperService")
                delay(2500)
                val info = HelperClient.query(_state.value.host)
                when {
                    info == null -> log("Started — status will update in a few seconds")
                    !info.permission -> log("Permission wasn't granted: $grant")
                    else -> log("Helper running · v${info.version}")
                }
                log("All set")
                checkHelper()
                loadApps()
            } catch (e: Throwable) {
                log("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(helperBusy = false) }
            }
        }
    }

    // ---------- Tools ----------

    private fun tool(label: String, block: suspend () -> Unit) {
        if (_state.value.conn != Conn.CONNECTED) {
            _state.update { it.copy(message = "Not connected yet") }
            return
        }
        _state.update { it.copy(toolBusy = label) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                _state.update { it.copy(message = e.message ?: "Something went wrong") }
            } finally {
                _state.update { it.copy(toolBusy = null) }
            }
        }
    }

    fun castMedia(uris: List<Uri>, video: Boolean) {
        if (uris.isEmpty()) return
        tool("cast") {
            val err = Caster.cast(ctx, uris, video)
            if (err != null) {
                _state.update { it.copy(message = err) }
            } else {
                val label = if (video) "Playing video on projector"
                else if (uris.size == 1) "Showing photo on projector" else "Showing ${uris.size} photos"
                _state.update { it.copy(castingLabel = label, message = "Sent to projector") }
            }
        }
    }

    fun stopCasting() {
        key(4) // Back closes the player
        _state.update { it.copy(castingLabel = null) }
        if (!_state.value.notifRemote) RemoteService.stop(ctx)
    }

    fun openLink(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        tool("link") {
            val full = if (u.startsWith("http")) u else "https://$u"
            val err = remote.openUrl(full)
            _state.update { it.copy(message = err ?: "Opened on projector") }
        }
    }

    fun takeScreenshot() = tool("peek") {
        val png = remote.execBytes("screencap -p")
        if (png.size < 100) error("Couldn't capture the screen")
        _state.update { it.copy(screenshot = png) }
    }

    fun closeScreenshot() = _state.update { it.copy(screenshot = null) }

    private suspend fun memAvailableMb(): Long =
        Regex("""MemAvailable:\s+(\d+)""").find(remote.shellOutput("cat /proc/meminfo"))
            ?.groupValues?.get(1)?.toLongOrNull()?.div(1024) ?: 0L

    fun freeMemory() = tool("ram") {
        val before = memAvailableMb()
        remote.shellOutput("am kill-all; pm trim-caches 999G")
        delay(1500)
        val after = memAvailableMb()
        val freed = (after - before).coerceAtLeast(0)
        _state.update { it.copy(message = "Freed ${freed} MB · ${after} MB available") }
        loadSysInfo()
    }

    fun installApk(uri: Uri) = tool("apk") {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Couldn't read the file")
        _state.update { it.copy(message = "Installing ${bytes.size / 1024 / 1024} MB…") }
        val result = remote.installApk(bytes)
        _state.update {
            it.copy(message = if (result.contains("Success")) "App installed on projector ✓"
            else "Install failed: ${result.take(80)}")
        }
        loadApps()
    }

    fun forceStop(app: TvApp) = tool("app") {
        remote.shellOutput("am force-stop ${app.pkg}")
        _state.update { it.copy(message = "Stopped ${app.label}") }
    }

    fun uninstall(app: TvApp) = tool("app") {
        // You removed it on purpose — never auto-restore it.
        putPrefSet("ignored", prefSet("ignored") + app.pkg)
        putPrefSet("watched", prefSet("watched") - app.pkg)
        if (app.pkg in _state.value.favorites) {
            val next = _state.value.favorites - app.pkg
            prefs.edit().putString("favs", next.joinToString(",")).apply()
            _state.update { it.copy(favorites = next) }
        }
        val system = remote.shellOutput("pm list packages -s").lines()
            .any { it.trim() == "package:${app.pkg}" }
        // Preinstalled apps are only hidden for this user, so "Restore apps" can bring them back.
        val out = if (system) remote.shellOutput("pm uninstall -k --user 0 ${app.pkg}")
        else remote.shellOutput("pm uninstall ${app.pkg}")
        _state.update {
            it.copy(message = if (out.contains("Success")) "Uninstalled ${app.label}"
            else "Couldn't uninstall ${app.label}")
        }
        loadApps()
    }

    fun clearData(app: TvApp) = tool("app") {
        val out = remote.shellOutput("pm clear ${app.pkg}")
        _state.update {
            it.copy(message = if (out.contains("Success")) "${app.label} reset — open it and sign in again"
            else "Couldn't reset ${app.label}")
        }
    }

    fun launchApp(app: TvApp) = launch(app)

    fun clearAppCache(app: TvApp) = tool("app") {
        val out = remote.shellOutput("pm clear --cache-only ${app.pkg}")
        if (out.contains("Error") || out.contains("Unknown")) error("This projector can't clear one app's cache. Use Free memory instead.")
        _state.update { it.copy(message = "Cleared ${app.label} cache") }
    }

    private fun pkgs(out: String) = out.lines()
        .map { it.trim().removePrefix("package:") }
        .filter { it.isNotBlank() }
        .toSet()

    /** Finds preinstalled apps that were uninstalled or disabled. */
    fun findRestorable() = tool("restore") {
        val all = pkgs(remote.shellOutput("pm list packages -u"))
        val installed = pkgs(remote.shellOutput("pm list packages"))
        val disabled = pkgs(remote.shellOutput("pm list packages -d"))
        val list = (all - installed).map { Restorable(it, RemoteController.friendlyName(it), false) } +
            disabled.map { Restorable(it, RemoteController.friendlyName(it), true) }
        val sorted = list
            .filterNot { it.pkg.startsWith("com.android.") && it.pkg != "com.android.vending" }
            .sortedWith(compareBy<Restorable>(
                { !it.pkg.contains("netflix") },
                { !RemoteController.KNOWN.containsKey(it.pkg) },
                { it.label.lowercase() }
            ))
        _state.update { it.copy(restorable = sorted) }
    }

    fun restore(r: Restorable) = tool("restore") {
        putPrefSet("ignored", prefSet("ignored") - r.pkg)
        val out = if (r.disabled) remote.shellOutput("pm enable ${r.pkg}")
        else remote.shellOutput("cmd package install-existing ${r.pkg}")
        val ok = out.contains("installed for user") || out.contains("new state: enabled") ||
            out.contains("Success", ignoreCase = true)
        _state.update {
            it.copy(
                message = if (ok) "${r.label} restored ✓" else "Couldn't restore: ${out.take(80)}",
                restorable = if (ok) it.restorable?.filterNot { x -> x.pkg == r.pkg } else it.restorable
            )
        }
        loadApps()
    }

    fun openPlayStore(pkg: String) = tool("restore") {
        remote.shellOutput("am start -a android.intent.action.VIEW -d 'market://details?id=$pkg'")
        _state.update { it.copy(message = "Opened Play Store on the projector") }
    }

    fun closeRestore() = _state.update { it.copy(restorable = null) }


    // ---------- App health (auto-heal) ----------

    private fun prefSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    private fun putPrefSet(key: String, v: Set<String>) = prefs.edit().putStringSet(key, v).apply()

    private fun logHealth(line: String) {
        val stamp = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val next = (listOf("$stamp · $line") + _state.value.healthLog).take(25)
        prefs.edit().putString("healthLog", next.joinToString("\n")).apply()
        _state.update { it.copy(healthLog = next) }
    }

    /**
     * Runs on every connect. Checks Netflix, pinned apps and any app you repaired before.
     * If the projector removed, hid, disabled or suspended one, it is fixed silently and
     * the evidence (state + who changed it) is written to the health log.
     */
    private suspend fun healthCheck() {
        val ignored = prefSet("ignored")
        val netflix = pkgs(remote.shellOutput("pm list packages -u")).filter { it.contains("netflix", true) }
        val watch = (prefSet("watched") + _state.value.favorites + netflix) - ignored
        var fixed = 0
        for (p in watch) {
            val dump = remote.shellOutput(
                "dumpsys package $p | grep -E 'User 0:|lastDisabledCaller|suspendingPackage' | head -4"
            ).trim()
            if (dump.isEmpty()) continue // not on this projector at all
            val user = dump.lines().firstOrNull { it.contains("User 0:") }.orEmpty()
            val problems = buildList {
                if (user.contains("installed=false")) add("removed")
                if (user.contains("hidden=true")) add("hidden")
                if (user.contains("suspended=true")) add("suspended")
                Regex("""enabled=(\d)""").find(user)?.groupValues?.get(1)?.let { e ->
                    if (e == "2" || e == "3" || e == "4") add("disabled($e)")
                }
            }
            if (problems.isEmpty()) continue
            val by = Regex("""(lastDisabledCaller|suspendingPackage)[:=]\s*(\S+)""").find(dump)?.groupValues?.get(2)
            remote.shellOutput(
                "cmd package install-existing $p; pm enable $p; pm unhide $p; pm unsuspend $p"
            )
            fixed++
            logHealth(
                "${RemoteController.friendlyName(p)} was ${problems.joinToString("+")}" +
                    (by?.let { " by $it" } ?: "") + " → fixed"
            )
        }
        if (fixed > 0) {
            _state.update { it.copy(message = if (fixed == 1) "Fixed 1 app the projector had hidden" else "Fixed $fixed apps the projector had hidden") }
        }
    }

    // ---------- Fix an app (deep repair) ----------

    /** Finds every package matching [query] (installed, removed, disabled or hidden) and reports its state. */
    fun doctorScan(query: String) {
        val q = query.trim().lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' }
        if (q.isEmpty()) return
        tool("doctor") {
            val pkgs = pkgs(remote.shellOutput("pm list packages -u")).filter { it.lowercase().contains(q) }
            val report = StringBuilder()
            for (p in pkgs) {
                report.append("■ ").append(p).append('\n')
                val dump = remote.shellOutput(
                    "dumpsys package $p | grep -E 'versionName|codePath|User 0:|lastDisabledCaller|suspendingPackage' | head -12"
                )
                report.append(dump.trim()).append("\n\n")
            }
            if (pkgs.isEmpty()) report.append("No package containing \"$q\" exists on the projector at all.")
            _state.update { it.copy(doctor = DoctorResult(q, pkgs, report.toString().trim())) }
        }
    }

    /** Tries every safe way to bring a package back to a working state, then opens it. */
    fun doctorRepair(pkg: String) = tool("doctor") {
        putPrefSet("watched", prefSet("watched") + pkg)
        putPrefSet("ignored", prefSet("ignored") - pkg)
        remote.shellOutput(
            "cmd package install-existing $pkg; " +
                "pm enable $pkg; " +
                "pm unhide $pkg; " +
                "pm unsuspend $pkg; " +
                "am force-stop $pkg"
        )
        val launch = remote.shellOutput(
            "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1 || monkey -p $pkg 1 2>&1"
        )
        val opened = launch.contains("Events injected") && !launch.contains("No activities found")
        _state.update {
            it.copy(message = if (opened) "Repaired and opened ✓" else "Repaired — but the app has no screen to open")
        }
        loadApps()
        doctorScan(_state.value.doctor?.query ?: pkg)
    }

    fun closeDoctor() = _state.update { it.copy(doctor = null) }

    fun loadSysInfo() {
        if (_state.value.conn != Conn.CONNECTED) return
        viewModelScope.launch {
            try {
                val out = remote.shellOutput(
                    "echo M=$(getprop ro.product.model); " +
                        "echo V=$(getprop ro.build.version.release); " +
                        "echo U=$(cat /proc/uptime); " +
                        "grep -E 'MemTotal|MemAvailable' /proc/meminfo; " +
                        "df /data | tail -1"
                )
                val lines = out.lines()
                fun v(p: String) = lines.firstOrNull { it.startsWith(p) }?.substringAfter(p)?.trim().orEmpty()
                fun kb(name: String) = Regex("""$name:\s+(\d+)""").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val upSec = v("U=").substringBefore(' ').substringBefore('.').toLongOrNull() ?: 0L
                val total = kb("MemTotal") / 1024
                val avail = kb("MemAvailable") / 1024
                val df = lines.lastOrNull { it.contains("/data") || it.startsWith("/dev") }
                    ?.trim()?.split(Regex("""\s+""")).orEmpty()
                // df columns: Filesystem 1K-blocks Used Available Use% Mounted
                val dfTotal = df.getOrNull(1)?.toLongOrNull()
                val dfFree = df.getOrNull(3)?.toLongOrNull()
                val info = buildList {
                    add("Model" to v("M=").ifBlank { "?" })
                    add("Android" to v("V=").ifBlank { "?" })
                    add("Memory" to "$avail MB free of $total MB")
                    if (dfTotal != null && dfFree != null) {
                        add("Storage" to String.format("%.1f GB free of %.1f GB", dfFree / 1048576.0, dfTotal / 1048576.0))
                    }
                    add("Uptime" to "${upSec / 3600} h ${(upSec % 3600) / 60} min")
                    add("Address" to "${_state.value.host}:${_state.value.port}")
                }
                _state.update { it.copy(sysInfo = info) }
            } catch (_: Throwable) {
            }
        }
    }
}
