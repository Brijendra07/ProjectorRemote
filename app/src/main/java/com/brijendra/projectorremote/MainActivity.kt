package com.brijendra.projectorremote

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.brijendra.projectorremote.ui.RemoteScreen
import com.brijendra.projectorremote.ui.RemoteTheme

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            RemoteTheme {
                LifecycleResumeEffect(vm) {
                    vm.onForeground(true)
                    onPauseOrDispose { vm.onForeground(false) }
                }
                RemoteScreen(vm)
            }
        }
    }

    /** Phone volume buttons control the projector while the app is open. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val s = vm.state.value
        if (s.volumeKeys && s.conn == Conn.CONNECTED) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { vm.key(24); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { vm.key(25); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val s = vm.state.value
        if (s.volumeKeys && s.conn == Conn.CONNECTED &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.onKeyUp(keyCode, event)
    }
}
