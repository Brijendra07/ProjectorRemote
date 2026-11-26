package com.brijendra.projectorremote

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brijendra.projectorremote.adb.HelperClient
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
) {
    val favoriteApps: List<TvApp>
        get() = favorites.mapNotNull { f -> apps.firstOrNull { it.pkg == f } }
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_PINS = 5
    }

    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val remote = RemoteController(app)

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

    private fun save(host: String, port: Int?) {
        prefs.edit().putString("host", host).putString("port", port?.toString() ?: "").apply()
        _state.update { it.copy(host = host, port = port?.toString() ?: it.port) }
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

    private suspend fun tryConnect(host: String, port: Int): Boolean =
        try { remote.connect(host, port) } catch (_: Throwable) { false }

    private suspend fun viaHelper(ip: String): Pair<String, Int>? {
        var info = HelperClient.query(ip, enable = true) ?: return null
        if (info.port == null || !info.adbWifi) {
            delay(3000)
            info = HelperClient.query(ip) ?: return null
        }
        return info.port?.let { info.ip to it }
    }

    fun connect() {
        userDisconnected = false
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch { attempt(full = true) }
    }

    private suspend fun attempt(full: Boolean): Boolean {
        val s = _state.value
        _state.update { it.copy(conn = Conn.CONNECTING) }
        var ok = false

        val savedPort = s.port.toIntOrNull()
        if (s.host.isNotBlank() && savedPort != null) {
            ok = tryConnect(s.host, savedPort)
            if (ok) save(s.host, savedPort)
        }
        if (!ok && s.host.isNotBlank()) {
            viaHelper(s.host)?.let { (ip, port) ->
                ok = tryConnect(ip, port)
                if (ok) save(ip, port)
            }
        }
        if (full) {
            if (!ok) ok = try { remote.autoConnect() } catch (_: Throwable) { false }
            if (!ok) {
                HelperClient.scan(getApplication<Application>())?.let { found ->
                    viaHelper(found.ip)?.let { (ip, port) ->
                        ok = tryConnect(ip, port)
                        if (ok) save(ip, port)
                    }
                }
            }
        }

        _state.update {
            if (ok) it.copy(conn = Conn.CONNECTED, message = null)
            else it.copy(conn = Conn.DISCONNECTED)
        }
        if (ok) onConnected()
        return ok
    }

    private fun onConnected() {
        loadApps()
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
                    val alive = try { remote.ping(); true } catch (_: Throwable) { false }
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
            val apps = HelperClient.apps(_state.value.host)
                ?: try { remote.listApps() } catch (_: Throwable) { emptyList() }
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
        } else if (cur.size >= MAX_PINS) {
            _state.update { it.copy(message = "Quick launch holds $MAX_PINS apps — unpin one first") }
            return
        } else {
            next = listOf(pkg) + cur   // newest first
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
}
