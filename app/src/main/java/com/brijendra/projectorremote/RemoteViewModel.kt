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
    val apps: List<TvApp> = emptyList(),
    val loadingApps: Boolean = false,
    val helperBusy: Boolean = false,
    val helperLog: List<String> = emptyList(),
    val helperStatus: String? = null,
)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val remote = RemoteController(app)

    private var connectJob: Job? = null
    private var userDisconnected = false
    private var inForeground = true


    private val _state = MutableStateFlow(
        UiState(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getString("port", "") ?: "",
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Always try: even with no saved IP, the helper scan can find the projector.
        connect()
        startAutoReconnect()
    }

    fun setHost(v: String) = _state.update { it.copy(host = v.trim()) }
    fun setPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun save(host: String, port: Int?) {
        prefs.edit().putString("host", host).putString("port", port?.toString() ?: "").apply()
        _state.update { it.copy(host = host, port = port?.toString() ?: it.port) }
    }

    fun pair(pairPort: String, code: String) {
        val host = _state.value.host
        val p = pairPort.toIntOrNull()
        if (host.isBlank() || p == null || code.length != 6) {
            _state.update { it.copy(message = "Enter the projector IP, pairing port and the 6-digit code") }
            return
        }
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

    /** Ask the helper app for the current port (switching Wireless debugging on if needed). */
    private suspend fun viaHelper(ip: String): Pair<String, Int>? {
        var info = HelperClient.query(ip, enable = true) ?: return null
        if (info.port == null || !info.adbWifi) {
            delay(3000)
            info = HelperClient.query(ip) ?: return null
        }
        return info.port?.let { info.ip to it }
    }

    /** Connect now (button). Also re-enables automatic reconnecting. */
    fun connect() {
        userDisconnected = false
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch { attempt(full = true, showErrors = true) }
    }

    /** One connection attempt. full = also try mDNS and a Wi-Fi scan (slower). */
    private suspend fun attempt(full: Boolean, showErrors: Boolean): Boolean {
        val s = _state.value
        _state.update { it.copy(conn = Conn.CONNECTING) }
        var ok = false

        // 1) Saved IP:port (fastest)
        val savedPort = s.port.toIntOrNull()
        if (s.host.isNotBlank() && savedPort != null) {
            ok = tryConnect(s.host, savedPort)
            if (ok) save(s.host, savedPort)
        }

        // 2) Helper on the saved IP tells us the new port
        if (!ok && s.host.isNotBlank()) {
            _state.update { it.copy(message = "Looking for the projector…") }
            viaHelper(s.host)?.let { (ip, port) ->
                ok = tryConnect(ip, port)
                if (ok) save(ip, port)
            }
        }

        if (full) {
            // 3) Projector's own Wi-Fi announcement (mDNS)
            if (!ok) {
                ok = try { remote.autoConnect() } catch (_: Throwable) { false }
            }
            // 4) IP changed too: scan the Wi-Fi for the helper
            if (!ok) {
                _state.update { it.copy(message = "Scanning Wi-Fi for the projector…") }
                HelperClient.scan(getApplication<Application>())?.let { found ->
                    viaHelper(found.ip)?.let { (ip, port) ->
                        ok = tryConnect(ip, port)
                        if (ok) save(ip, port)
                    }
                }
            }
        }

        _state.update {
            if (ok) it.copy(conn = Conn.CONNECTED, message = "Connected")
            else it.copy(
                conn = Conn.DISCONNECTED,
                message = when {
                    it.host.isBlank() -> null
                    showErrors -> "Waiting for the projector… (retrying automatically)"
                    else -> it.message
                }
            )
        }
        if (ok) loadApps()
        return ok
    }

    /**
     * Runs while the app is open:
     *  - not connected → keep retrying (quick every 4 s, full search every 4th try)
     *  - connected → ping every 8 s so a dropped connection is noticed and fixed
     */
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
                    if (!alive) {
                        _state.update { it.copy(conn = Conn.DISCONNECTED, message = "Connection lost — reconnecting…") }
                    }
                    delay(4_000)
                } else if (st.host.isNotBlank()) {
                    tries++
                    val full = tries % 4 == 0
                    connectJob = viewModelScope.launch { attempt(full = full, showErrors = true) }
                }
            }
        }
    }

    /** Called when the app comes to the front / goes to the background. */
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

    fun key(code: Int) = send { remote.key(code) }
    fun longKey(code: Int) = send { remote.longKey(code) }
    fun text(value: String) = send { remote.text(value) }
    fun launch(app: TvApp) = send { remote.launch(app) }

    fun loadApps() {
        _state.update { it.copy(loadingApps = true) }
        viewModelScope.launch {
            val apps = try { remote.listApps() } catch (_: Throwable) { emptyList() }
            _state.update { it.copy(apps = apps, loadingApps = false) }
        }
    }

    // ---------- Auto-start helper ----------

    private fun log(line: String) = _state.update { it.copy(helperLog = it.helperLog + line) }

    fun checkHelper() {
        viewModelScope.launch {
            val info = HelperClient.query(_state.value.host)
            _state.update {
                it.copy(
                    helperStatus = when {
                        info == null -> "Not running on the projector yet"
                        !info.permission -> "Running, but permission missing — tap Set up"
                        else -> "Active ✓ — Wireless debugging will turn on by itself after restarts"
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
                    log("Downloading helper app…")
                    val apk = HelperClient.downloadApk()
                    log("Installing on projector (${apk.size / 1024} KB)…")
                    val result = remote.installApk(apk)
                    if (!result.contains("Success")) error("Install failed: $result")
                    log("Installed ✓")
                } else {
                    log("Helper already installed ✓")
                }

                log("Giving it permission to switch on Wireless debugging…")
                val grant = remote.shellOutput("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS").trim()
                if (grant.isNotEmpty()) log(grant)

                log("Starting helper…")
                remote.shellOutput("am start-foreground-service -n $pkg/.KeeperService")
                delay(2500)

                val info = HelperClient.query(_state.value.host)
                when {
                    info == null -> log("Started. (Couldn't reach it yet — tap Check status in a few seconds.)")
                    !info.permission -> log("Running, but permission wasn't granted: $grant")
                    else -> log("Helper is running ✓ (port ${info.port ?: "?"})")
                }
                log("Done! After a restart, just open this app — it will find the projector by itself.")
                checkHelper()
            } catch (e: Throwable) {
                log("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(helperBusy = false) }
            }
        }
    }

    private fun send(block: suspend () -> Unit) {
        if (_state.value.conn != Conn.CONNECTED) {
            _state.update { it.copy(message = "Not connected") }
            return
        }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                _state.update { it.copy(conn = Conn.DISCONNECTED, message = "Connection lost — reconnecting…") }
            }
        }
    }
}
