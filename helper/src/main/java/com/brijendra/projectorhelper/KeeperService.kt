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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.net.URLDecoder
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
            sock.soTimeout = 5_000
            val reader = sock.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            // "GET /path?query HTTP/1.1"
            val target = requestLine.split(" ").getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
                .split('&')
                .mapNotNull {
                    val k = it.substringBefore('=', "")
                    if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }
                .toMap()

            when (path) {
                "/apps" -> send(sock, "application/json",
                    AppsProvider.list(this).toString().toByteArray())
                "/icon" -> {
                    val png = query["pkg"]?.let { AppsProvider.iconPng(this, it) }
                    if (png != null) send(sock, "image/png", png) else notFound(sock)
                }
                "/banner" -> {
                    val png = query["c"]?.let { AppsProvider.bannerPng(this, it) }
                    if (png != null) send(sock, "image/png", png) else notFound(sock)
                }
                else -> {
                    if (path == "/enable") AdbWifi.ensureEnabled(this)
                    val body = JSONObject()
                        .put("app", "ProjectorHelper")
                        .put("version", VERSION)
                        .put("name", deviceName())
                        .put("ip", AdbWifi.ip(this) ?: "")
                        .put("port", AdbWifi.port() ?: mdnsPort ?: 0)
                        .put("adbWifi", AdbWifi.isEnabled(this))
                        .put("permission", AdbWifi.hasPermission(this))
                        .toString()
                    send(sock, "application/json", body.toByteArray())
                }
            }
        }
    }

    private fun deviceName(): String {
        val global = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (_: Throwable) { null }
        return global?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Projector"
    }

    private fun send(sock: Socket, type: String, bytes: ByteArray) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: max-age=3600\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun notFound(sock: Socket) {
        sock.getOutputStream().apply {
            write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
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
        const val VERSION = 4
        private const val CHANNEL = "keeper"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, KeeperService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
