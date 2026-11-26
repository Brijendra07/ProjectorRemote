package com.brijendra.projectorremote.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Tiny HTTP server on the phone that streams picked videos/photos to the projector.
 * Supports Range requests so videos can seek.
 */
object MediaHub {

    private data class Item(val uri: Uri, val mime: String, val name: String)

    private val items = ConcurrentHashMap<String, Item>()
    @Volatile private var server: ServerSocket? = null
    private lateinit var appCtx: Context

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (server?.isClosed == false) return
        val s = ServerSocket(0)
        server = s
        thread(isDaemon = true, name = "media-hub") {
            try {
                while (!s.isClosed) {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Throwable) {}
        server = null
        items.clear()
    }

    /** Registers a file and returns its URL on this phone. */
    fun publish(ctx: Context, uri: Uri): String? {
        start(ctx)
        val ip = phoneIp(ctx) ?: return null
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val ext = when {
            mime.startsWith("video/") -> "mp4"
            mime == "image/png" -> "png"
            mime.startsWith("image/") -> "jpg"
            else -> "bin"
        }
        items[token] = Item(uri, mime, displayName(ctx, uri) ?: "media")
        return "http://$ip:$port/m/$token.$ext"
    }

    fun nameOf(ctx: Context, uri: Uri): String = displayName(ctx, uri) ?: "media"

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) { null }

    private fun sizeOf(uri: Uri): Long {
        try {
            appCtx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) return afd.length
            }
        } catch (_: Throwable) {}
        return try {
            appCtx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Throwable) { -1L }
    }

    fun phoneIp(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Throwable) { null }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30_000
            val input = sock.getInputStream()
            val requestLine = readLine(input) ?: return
            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
            }
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            val token = path.substringAfter("/m/", "").substringBefore('.')
            val item = items[token]
            val out = sock.getOutputStream()
            if (item == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val total = sizeOf(item.uri)
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            var partial = false
            if (rangeHeader != null && total > 0 && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
                val a = spec.substringBefore('-')
                val b = spec.substringAfter('-')
                if (a.isNotEmpty()) {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), total - 1)
                } else if (b.isNotEmpty()) {
                    start = maxOf(0L, total - b.toLong())
                }
                partial = true
            }
            if (total > 0 && start >= total) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val length = if (total > 0) end - start + 1 else -1L

            val head = StringBuilder()
            head.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            head.append("Content-Type: ${item.mime}\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            if (length >= 0) head.append("Content-Length: $length\r\n")
            if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (method == "HEAD") { out.flush(); return }

            appCtx.contentResolver.openInputStream(item.uri)?.use { src ->
                var skipped = 0L
                while (skipped < start) {
                    val n = src.skip(start - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                copy(src, out, length)
            }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun copy(src: InputStream, out: OutputStream, limit: Long) {
        val buf = ByteArray(256 * 1024)
        var left = limit
        while (limit < 0 || left > 0) {
            val want = if (limit < 0) buf.size else minOf(buf.size.toLong(), left).toInt()
            val n = src.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}
