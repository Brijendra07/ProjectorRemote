package com.brijendra.projectorremote.adb

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Talks to the Projector Helper app (port 8765) to learn the projector's current IP and ADB port. */
object HelperClient {
    const val PORT = 8765
    const val PACKAGE = "com.brijendra.projectorhelper"
    const val APK_URL =
        "https://github.com/Brijendra07/ProjectorRemote/releases/latest/download/ProjectorHelper-debug.apk"

    const val LATEST_VERSION = 4

    data class Info(
        val ip: String,
        val port: Int?,
        val adbWifi: Boolean,
        val permission: Boolean,
        val version: Int,
        val name: String?,
    )

    /** Ask the helper at [ip]. Returns null if no helper answers there. */
    suspend fun query(ip: String, timeoutMs: Int = 1500, enable: Boolean = false): Info? =
        withContext(Dispatchers.IO) {
            try {
                val path = if (enable) "/enable" else "/info"
                val c = URL("http://$ip:$PORT$path").openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                val text = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val j = JSONObject(text)
                if (j.optString("app") != "ProjectorHelper") return@withContext null
                Info(
                    ip = j.optString("ip").ifBlank { ip },
                    port = j.optInt("port", 0).takeIf { it > 0 },
                    adbWifi = j.optBoolean("adbWifi"),
                    permission = j.optBoolean("permission"),
                    version = j.optInt("version", 1),
                    name = j.optString("name").ifBlank { null },
                )
            } catch (_: Exception) {
                null
            }
        }

    fun iconUrl(ip: String, pkg: String) =
        "http://$ip:$PORT/icon?pkg=" + URLEncoder.encode(pkg, "UTF-8")

    /** Apps with real names, icons and banners (helper v2+). Null if the helper can't list them. */
    suspend fun apps(ip: String): List<TvApp>? = withContext(Dispatchers.IO) {
        try {
            val c = URL("http://$ip:$PORT/apps").openConnection() as HttpURLConnection
            c.connectTimeout = 2500
            c.readTimeout = 5000
            val text = c.inputStream.bufferedReader().use { it.readText() }.trim()
            c.disconnect()
            if (!text.startsWith("[")) return@withContext null
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pkg = o.getString("pkg")
                val component = o.getString("component")
                TvApp(
                    pkg = pkg,
                    component = component,
                    label = o.optString("label").ifBlank { RemoteController.friendlyName(pkg) },
                    iconUrl = "http://$ip:$PORT/icon?pkg=" + URLEncoder.encode(pkg, "UTF-8"),
                    bannerUrl = if (o.optBoolean("banner"))
                        "http://$ip:$PORT/banner?c=" + URLEncoder.encode(component, "UTF-8") else null,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Scan the phone's Wi-Fi subnet (x.x.x.1–254) for the helper. Takes ~2 seconds. */
    suspend fun scan(context: Context): Info? = coroutineScope {
        val prefix = localPrefix(context) ?: return@coroutineScope null
        val hits = (1..254).map { n ->
            async(Dispatchers.IO) {
                val ip = "$prefix.$n"
                val open = try {
                    Socket().use { it.connect(InetSocketAddress(ip, PORT), 500) }
                    true
                } catch (_: Exception) {
                    false
                }
                if (open) query(ip) else null
            }
        }.awaitAll()
        hits.firstOrNull { it != null }
    }

    private fun localPrefix(context: Context): String? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
            ?.substringBeforeLast('.')
    } catch (_: Exception) {
        null
    }

    /** Download the helper APK from GitHub Releases. */
    suspend fun downloadApk(): ByteArray = withContext(Dispatchers.IO) {
        val c = URL(APK_URL).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode !in 200..299) error("Download failed (HTTP ${c.responseCode})")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
