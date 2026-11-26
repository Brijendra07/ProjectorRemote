package com.brijendra.projectorhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Keeps Wireless debugging switched on (after boot, Wi-Fi reconnects, standby)
 * and answers the phone app on port 8765 with the current IP and ADB port.
 */
class KeeperService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var mdnsPort: Int? = null

    private val periodic = object : Runnable {
        override fun run() {
            AdbWifi.ensureEnabled(this@KeeperService)
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())

        AdbWifi.ensureEnabled(this)
        handler.postDelayed(periodic, 5_000)

        val cm = getSystemService(ConnectivityManager::class.java)
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Give Wi-Fi a moment, then make sure debugging is on for this network.
                handler.postDelayed({ AdbWifi.ensureEnabled(this@KeeperService) }, 3_000)
            }
        }.also {
            try { cm.registerDefaultNetworkCallback(it) } catch (_: Throwable) {}
        }

        startServer()
        startPortDiscovery()
    }

    /** Backup way to learn our own ADB port: listen for the projector's own mDNS announcement. */
    private fun startPortDiscovery() {
        try {
            val nsd = getSystemService(NsdManager::class.java)
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD,
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        try {
                            @Suppress("DEPRECATION")
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                                override fun onServiceResolved(i: NsdServiceInfo) {
                                    @Suppress("DEPRECATION")
                                    val host = i.host?.hostAddress
                                    val mine = AdbWifi.ip(this@KeeperService)
                                    if (host == null || mine == null || host == mine) mdnsPort = i.port
                                }
                            })
                        } catch (_: Throwable) {}
                    }
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onDiscoveryStarted(type: String) {}
                    override fun onDiscoveryStopped(type: String) {}
                    override fun onStartDiscoveryFailed(type: String, code: Int) {}
                    override fun onStopDiscoveryFailed(type: String, code: Int) {}
                })
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AdbWifi.ensureEnabled(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { server?.close() } catch (_: Throwable) {}
        callback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Throwable) {}
        }
        super.onDestroy()
    }

    private fun startServer() {
        thread(isDaemon = true, name = "helper-http") {
            try {
                val s = ServerSocket(PORT)
                server = s
                while (!s.isClosed) {
                    val client = s.accept()
                    thread(isDaemon = true) { answer(client) }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun answer(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 3_000
            val reader = sock.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            // Skip headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            if (requestLine.contains("/enable")) AdbWifi.ensureEnabled(this)

            val body = JSONObject()
                .put("app", "ProjectorHelper")
                .put("ip", AdbWifi.ip(this) ?: "")
                .put("port", AdbWifi.port() ?: mdnsPort ?: 0)
                .put("adbWifi", AdbWifi.isEnabled(this))
                .put("permission", AdbWifi.hasPermission(this))
                .toString()
            val bytes = body.toByteArray()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            sock.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Projector Helper", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Projector Helper")
            .setContentText("Keeping phone remote available")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8765
        private const val CHANNEL = "keeper"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, KeeperService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
