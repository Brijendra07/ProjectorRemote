package com.brijendra.projectorremote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.brijendra.projectorremote.adb.Connector
import com.brijendra.projectorremote.adb.RemoteController
import com.brijendra.projectorremote.media.Caster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Appears in the phone's Share menu as "Projector Remote".
 *  - Links (YouTube, Instagram, any URL) open on the projector
 *  - Plain text is typed into the projector
 *  - Shared videos/photos play on the projector
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        toast("Sending to projector…")
        lifecycleScope.launch {
            val error = try {
                handle(intent)
            } catch (e: Throwable) {
                e.message ?: "Something went wrong"
            }
            toast(error ?: "Sent to projector ✓")
            finish()
        }
    }

    private suspend fun handle(intent: Intent): String? {
        if (!Connector.ensure(this)) return "Projector not found. Is it on and on the same Wi-Fi?"
        val type = intent.type ?: ""

        // Media files
        val streams = streamsOf(intent)
        if (streams.isNotEmpty() && (type.startsWith("video/") || type.startsWith("image/") || type == "*/*")) {
            val isVideo = type.startsWith("video/") ||
                streams.any { (contentResolver.getType(it) ?: "").startsWith("video/") }
            // Shared files are only readable briefly, so keep a private copy while it plays.
            val local = withContext(Dispatchers.IO) { streams.take(30).mapNotNull { copyToCache(it) } }
            return Caster.cast(this, local, isVideo)
        }

        // Links and text
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return "Nothing to send"
        val url = Regex("""https?://\S+""").find(text)?.value
        val remote = RemoteController.get(this)
        return if (url != null) remote.openUrl(url) else {
            remote.text(text)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            ) ?: emptyList()
        else -> emptyList()
    }

    private fun copyToCache(uri: Uri): Uri? = try {
        val dir = File(cacheDir, "cast").apply { mkdirs() }
        val mime = contentResolver.getType(uri) ?: ""
        val ext = when {
            mime.startsWith("video/") -> "mp4"
            mime == "image/png" -> "png"
            else -> "jpg"
        }
        val file = File(dir, "share_${System.currentTimeMillis()}_${uri.hashCode()}.$ext")
        contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        // Clean up older copies (keep the latest 40)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        FileProvider.getUriForFile(this, "$packageName.files", file)
    } catch (_: Throwable) {
        null
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
