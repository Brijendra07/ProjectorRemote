package com.brijendra.projectorremote.adb

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds and connects to the projector. Shared by the app screen, share sheet,
 * notification controls and widget, so all of them work even when the app isn't open.
 */
object Connector {

    private val lock = Mutex()

    /** Why the last attempt failed (shown in the app). */
    @Volatile var lastError: String? = null
        private set

    /** True if the projector says this phone isn't paired any more. */
    @Volatile var needsPairing: Boolean = false
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun savedHost(ctx: Context): String = prefs(ctx).getString("host", "") ?: ""
    fun savedPort(ctx: Context): Int? = prefs(ctx).getString("port", "")?.toIntOrNull()

    private fun save(ctx: Context, host: String, port: Int?) {
        prefs(ctx).edit()
            .putString("host", host)
            .putString("port", port?.toString() ?: "")
            .apply()
    }

    /** True if a working connection exists (checked with a tiny ping). */
    suspend fun isAlive(ctx: Context): Boolean {
        val r = RemoteController.get(ctx)
        if (!r.isConnected) return false
        return try { r.ping(); true } catch (_: Throwable) { false }
    }

    /** Connect if needed. Quick first, then the full search. */
    suspend fun ensure(ctx: Context): Boolean {
        if (isAlive(ctx)) return true
        return attempt(ctx, full = false) || attempt(ctx, full = true)
    }

    /**
     * One attempt: saved IP:port → helper on saved IP → (full) mDNS → (full) Wi-Fi scan.
     */
    suspend fun attempt(ctx: Context, full: Boolean): Boolean =
        withTimeoutOrNull(if (full) 45_000L else 25_000L) { attemptLocked(ctx, full) } ?: false

    private suspend fun attemptLocked(ctx: Context, full: Boolean): Boolean = lock.withLock {
        if (isAlive(ctx)) return@withLock true
        val remote = RemoteController.get(ctx)
        val host = savedHost(ctx)
        val port = savedPort(ctx)

        suspend fun tryConnect(h: String, p: Int): Boolean =
            try {
                val ok = remote.connect(h, p)
                if (ok) { lastError = null; needsPairing = false }
                ok
            } catch (e: Throwable) {
                if (e.javaClass.simpleName.contains("Pairing")) needsPairing = true
                lastError = (e.message ?: e.javaClass.simpleName).take(120)
                false
            }

        if (host.isNotBlank() && port != null && tryConnect(host, port)) {
            return@withLock true
        }
        if (host.isNotBlank()) {
            viaHelper(host)?.let { (ip, p) ->
                if (tryConnect(ip, p)) { save(ctx, ip, p); return@withLock true }
            }
        }
        if (full) {
            if (try { remote.autoConnect() } catch (_: Throwable) { false }) return@withLock true
            HelperClient.scan(ctx)?.let { found ->
                viaHelper(found.ip)?.let { (ip, p) ->
                    if (tryConnect(ip, p)) { save(ctx, ip, p); return@withLock true }
                }
            }
        }
        false
    }

    private suspend fun viaHelper(ip: String): Pair<String, Int>? {
        var info = HelperClient.query(ip, enable = true) ?: return null
        if (info.port == null || !info.adbWifi) {
            delay(3000)
            info = HelperClient.query(ip) ?: return null
        }
        return info.port?.let { info.ip to it }
    }
}
