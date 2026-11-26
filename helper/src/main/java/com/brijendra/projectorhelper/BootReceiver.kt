package com.brijendra.projectorhelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AdbWifi.ensureEnabled(context)
        KeeperService.start(context)
    }
}
