package com.brijendra.projectorremote

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class RemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Conscrypt provides TLS 1.3, which Wireless debugging (pairing + connect) requires.
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        } catch (_: Throwable) {
        }
    }
}
