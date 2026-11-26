package com.brijendra.projectorremote.adb

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

/** A launchable app on the projector. */
data class TvApp(
    val pkg: String,
    val component: String,
    val label: String,
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
)

/**
 * Sends commands to the projector over Wireless debugging (ADB).
 * All calls run on one background thread, in order.
 */
class RemoteController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val manager by lazy { AdbConnectionManager.get(appContext) }
    /** One command at a time. */
    private val opLock = Mutex()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Runs a blocking ADB call with a watchdog. If the projector stops answering
     * (restarted, Wi-Fi dropped), the connection is torn down so nothing stays frozen.
     */
    private suspend fun <T> op(timeoutMs: Long = 10_000, block: () -> T): T = opLock.withLock {
        val dog = watchdogScope.launch {
            delay(timeoutMs)
            hardReset()
        }
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            dog.cancel()
        }
    }

    private fun hardReset() {
        resetShell()
        try { manager.disconnect() } catch (_: Throwable) {}
    }

    private var shell: AdbStream? = null
    private var shellOut: OutputStream? = null

    val isConnected: Boolean
        get() = try { manager.isConnected } catch (_: Throwable) { false }

    /** One-time pairing using the 6-digit code shown on the projector. */
    suspend fun pair(host: String, port: Int, code: String): Boolean = op(20_000) {
        manager.pair(host, port, code)
    }

    /** Connect to host:port. If port is null, find the projector automatically on Wi-Fi. */
    suspend fun connect(host: String, port: Int?): Boolean = op(12_000) {
        resetShell()
        // Drop any stale connection (e.g. projector restarted) before connecting again.
        if (manager.isConnected) try { manager.disconnect() } catch (_: Throwable) {}
        if (port != null) {
            manager.setHostAddress(host)
            manager.connect(host, port)
        } else {
            manager.autoConnect(appContext, 6_000)
        }
    }

    /** Find the projector automatically (mDNS). Useful after the projector reboots and the port changes. */
    suspend fun autoConnect(): Boolean = op(12_000) {
        resetShell()
        if (manager.isConnected) try { manager.disconnect() } catch (_: Throwable) {}
        manager.autoConnect(appContext, 6_000)
    }

    suspend fun disconnect() = op(5_000) {
        resetShell()
        try { manager.disconnect() } catch (_: Throwable) {}
    }

    /** Throws if the connection is gone. */
    suspend fun ping() = op(5_000) {
        if (!manager.isConnected) throw IOException("not connected")
        // A real round trip: a dead connection can't answer, and the watchdog resets it.
        if (!exec("echo ok").contains("ok")) throw IOException("no answer")
    }

    suspend fun key(keyCode: Int) = run("input keyevent $keyCode")

    suspend fun longKey(keyCode: Int) = run("input keyevent --longpress $keyCode")

    suspend fun text(value: String) {
        if (value.isEmpty()) return
        // `input text` uses %s for spaces; wrap in single quotes for the shell.
        val escaped = value.replace(" ", "%s").replace("'", "'\\''")
        run("input text '$escaped'")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) =
        run("input swipe $x1 $y1 $x2 $y2 $ms")

    suspend fun launch(app: TvApp) =
        if (app.component.contains('/')) run("am start -n '${app.component}'")
        else run("monkey -p ${app.pkg} -c android.intent.category.LEANBACK_LAUNCHER 1 || monkey -p ${app.pkg} 1")

    /** Lists apps that appear on the projector's home screen. */
    suspend fun listApps(): List<TvApp> = op(20_000) {
        val out = exec(
            "cmd package query-activities --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LEANBACK_LAUNCHER; " +
                "cmd package query-activities --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER"
        )
        val regex = Regex("""([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_.$]+)""")
        regex.findAll(out)
            .map { m ->
                val pkg = m.groupValues[1]
                TvApp(pkg, m.value, friendlyName(pkg))
            }
            .distinctBy { it.pkg }
            .filterNot { it.pkg.startsWith("com.android.") && it.pkg != "com.android.vending" }
            .sortedWith(compareBy<TvApp>({ !KNOWN.containsKey(it.pkg) }, { it.label.lowercase() }))
            .toList()
    }

    /** Runs one shell command and returns its output. */
    suspend fun shellOutput(cmd: String): String = op(30_000) { exec(cmd) }

    /** Installs an APK on the projector over the ADB connection (like `adb install`). */
    suspend fun installApk(apk: ByteArray): String = op(240_000) {
        val stream = manager.openStream("exec:cmd package install -r -S ${apk.size}")
        val result = ByteArrayOutputStream()
        try {
            val out = stream.openOutputStream()
            var off = 0
            while (off < apk.size) {
                val n = minOf(64 * 1024, apk.size - off)
                out.write(apk, off, n)
                off += n
            }
            out.flush()
            val input = stream.openInputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = try { input.read(buf) } catch (_: IOException) { -1 }
                if (n == -1) break
                result.write(buf, 0, n)
            }
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
        result.toString("UTF-8").trim()
    }

    /** Runs a command through a persistent shell (fast, no output). */
    private suspend fun run(cmd: String) = op(8_000) {
        try {
            writeLine(cmd)
        } catch (e: IOException) {
            // Shell died (projector slept, Wi-Fi blip). Reopen once and retry.
            resetShell()
            writeLine(cmd)
        }
    }

    private fun writeLine(cmd: String) {
        val out = shellOut ?: openShell()
        out.write("$cmd\n".toByteArray())
        out.flush()
    }

    private fun openShell(): OutputStream {
        val stream = manager.openStream("shell:")
        val input = stream.openInputStream()
        // Drain output so the shell never blocks.
        thread(isDaemon = true, name = "adb-shell-drain") {
            val buf = ByteArray(4096)
            try {
                while (input.read(buf) != -1) { /* discard */ }
            } catch (_: Throwable) {
            }
        }
        shell = stream
        return stream.openOutputStream().also { shellOut = it }
    }

    private fun resetShell() {
        try { shell?.close() } catch (_: Throwable) {}
        shell = null
        shellOut = null
    }

    /** Runs one command and returns its output. */
    private fun exec(cmd: String): String {
        val stream = manager.openStream("shell:$cmd")
        val bytes = ByteArrayOutputStream()
        try {
            val input = stream.openInputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = try { input.read(buf) } catch (_: IOException) { -1 }
                if (n == -1) break
                bytes.write(buf, 0, n)
            }
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
        return bytes.toString("UTF-8")
    }

    /** Runs a command without a shell and returns raw bytes (e.g. screencap). */
    suspend fun execBytes(cmd: String): ByteArray = op(20_000) {
        val stream = manager.openStream("exec:$cmd")
        val bytes = ByteArrayOutputStream()
        try {
            val input = stream.openInputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = try { input.read(buf) } catch (_: IOException) { -1 }
                if (n == -1) break
                bytes.write(buf, 0, n)
            }
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
        bytes.toByteArray()
    }

    /**
     * Opens a link on the projector. YouTube links go to the YouTube app.
     * Returns null on success or an error message.
     */
    suspend fun openUrl(url: String): String? {
        val safe = url.replace("'", "%27")
        val yt = youtubeId(url)
        if (yt != null) {
            for (pkg in listOf("com.google.android.youtube.tv", "com.google.android.youtube")) {
                val out = shellOutput("am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=$yt' $pkg")
                if (!out.contains("Error")) return null
            }
        }
        val out = shellOutput("am start -a android.intent.action.VIEW -d '$safe'")
        return if (out.contains("Error")) "No app on the projector can open this link" else null
    }

    companion object {
        @Volatile
        private var instance: RemoteController? = null

        fun get(context: Context): RemoteController =
            instance ?: synchronized(this) {
                instance ?: RemoteController(context.applicationContext).also { instance = it }
            }

        fun youtubeId(url: String): String? {
            val patterns = listOf(
                Regex("""youtu\.be/([A-Za-z0-9_-]{6,})"""),
                Regex("""[?&]v=([A-Za-z0-9_-]{6,})"""),
                Regex("""youtube\.com/(?:shorts|live|embed)/([A-Za-z0-9_-]{6,})"""),
            )
            for (p in patterns) p.find(url)?.let { return it.groupValues[1] }
            return null
        }

        val KNOWN = mapOf(
            "com.netflix.ninja" to "Netflix",
            "com.netflix.mediaclient" to "Netflix",
            "com.google.android.youtube.tv" to "YouTube",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.youtube.tvmusic" to "YouTube Music",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "in.startv.hotstar" to "Hotstar",
            "in.startv.hotstaronly" to "Hotstar",
            "com.spotify.tv.android" to "Spotify",
            "com.spotify.music" to "Spotify",
            "tv.accedo.airtel.wynk" to "Airtel Xstream",
            "com.android.vending" to "Play Store",
            "org.videolan.vlc" to "VLC",
            "com.jio.media.stb.ondemand" to "JioCinema"
        )

        fun friendlyName(pkg: String): String =
            KNOWN[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
