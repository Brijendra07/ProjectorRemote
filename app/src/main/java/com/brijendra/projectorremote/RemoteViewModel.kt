package com.brijendra.projectorremote

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brijendra.projectorremote.adb.RemoteController
import com.brijendra.projectorremote.adb.TvApp
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
)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val remote = RemoteController(app)

    private val _state = MutableStateFlow(
        UiState(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getString("port", "") ?: "",
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (_state.value.host.isNotBlank()) connect()
    }

    fun setHost(v: String) = _state.update { it.copy(host = v.trim()) }
    fun setPort(v: String) = _state.update { it.copy(port = v.filter(Char::isDigit)) }
    fun clearMessage() = _state.update { it.copy(message = null) }

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

    fun connect() {
        val s = _state.value
        if (s.host.isBlank()) {
            _state.update { it.copy(message = "Enter the projector IP address") }
            return
        }
        prefs.edit().putString("host", s.host).putString("port", s.port).apply()
        _state.update { it.copy(conn = Conn.CONNECTING) }
        viewModelScope.launch {
            var ok = false
            var error: String? = null
            // 1) Saved IP:port
            s.port.toIntOrNull()?.let { port ->
                ok = try { remote.connect(s.host, port) } catch (e: Throwable) { error = e.message ?: e.javaClass.simpleName; false }
            }
            // 2) Port changes after a projector reboot — discover it automatically.
            if (!ok) {
                ok = try { remote.autoConnect() } catch (e: Throwable) { error = e.message ?: e.javaClass.simpleName; false }
            }
            _state.update {
                if (ok) it.copy(conn = Conn.CONNECTED, message = "Connected")
                else it.copy(
                    conn = Conn.DISCONNECTED,
                    message = "Couldn't connect. Check Wireless debugging is ON and the port is correct" +
                        (error?.let { e -> " ($e)" } ?: "")
                )
            }
            if (ok) loadApps()
        }
    }

    fun disconnect() {
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

    private fun send(block: suspend () -> Unit) {
        if (_state.value.conn != Conn.CONNECTED) {
            _state.update { it.copy(message = "Not connected") }
            return
        }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                _state.update { it.copy(conn = Conn.DISCONNECTED, message = "Connection lost. Tap Connect.") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
