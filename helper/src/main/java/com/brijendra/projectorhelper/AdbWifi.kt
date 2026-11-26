package com.brijendra.projectorhelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.provider.Settings
import java.net.Inet4Address

/** Switches Wireless debugging on and reports the projector's address. */
object AdbWifi {
    private const val SETTING = "adb_wifi_enabled"

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, SETTING, 0) == 1

    /** Returns true if Wireless debugging is (now) on. */
    fun ensureEnabled(ctx: Context): Boolean {
        if (isEnabled(ctx)) return true
        if (!hasPermission(ctx)) return false
        return try {
            Settings.Global.putInt(ctx.contentResolver, SETTING, 1)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Current Wireless debugging port, or null if unknown. */
    fun port(): Int? = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tls.port"))
        p.inputStream.bufferedReader().readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }

    fun ip(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }
}
