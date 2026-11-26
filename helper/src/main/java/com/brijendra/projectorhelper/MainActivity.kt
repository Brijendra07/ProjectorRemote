package com.brijendra.projectorhelper

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Shows status on the projector. The phone app does all the setup. */
class MainActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#12151C"))
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        setContentView(text)
        KeeperService.start(this)
    }

    override fun onResume() {
        super.onResume()
        val perm = AdbWifi.hasPermission(this)
        val on = AdbWifi.ensureEnabled(this)
        text.text = buildString {
            appendLine("Projector Helper")
            appendLine()
            appendLine("Permission: " + if (perm) "granted ✓" else "missing — open the phone app → Auto-start → Set up")
            appendLine("Wireless debugging: " + if (on) "ON ✓" else "OFF")
            appendLine("Address: ${AdbWifi.ip(this@MainActivity) ?: "?"} : ${AdbWifi.port() ?: "?"}")
            appendLine()
            append("You can press Back. It keeps running in the background and starts by itself after every restart.")
        }
    }
}
