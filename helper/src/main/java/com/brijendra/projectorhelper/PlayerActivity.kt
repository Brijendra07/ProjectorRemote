package com.brijendra.projectorhelper

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Plays videos and shows photos streamed from the phone.
 * Remote keys: ◀ ▶ = previous/next photo (or seek ±10 s), OK / Play-Pause = pause or slideshow, Back = close.
 */
class PlayerActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var video: VideoView
    private lateinit var image: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var urls: List<String> = emptyList()
    private var index = 0
    private var isVideo = false
    private var slideshow = false

    private val nextSlide = object : Runnable {
        override fun run() {
            if (slideshow && !isVideo && urls.size > 1) {
                show((index + 1) % urls.size)
                handler.postDelayed(this, 6_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, match)
        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(120, 120, Gravity.CENTER))
        hint = TextView(this).apply {
            setTextColor(Color.argb(170, 255, 255, 255))
            textSize = 14f
            setPadding(32, 16, 32, 24)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        setContentView(root)
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(i: Intent) {
        handler.removeCallbacksAndMessages(null)
        slideshow = false
        urls = i.getStringArrayExtra("urls")?.toList()?.filter { it.isNotBlank() }
            ?: listOfNotNull(i.dataString)
        isVideo = i.getStringExtra("type") == "video"
        if (urls.isEmpty()) { finish(); return }
        show(i.getIntExtra("index", 0).coerceIn(0, urls.size - 1))
    }

    private fun show(i: Int) {
        index = i
        val url = urls[i]
        spinner.visibility = View.VISIBLE
        if (isVideo) {
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.setVideoURI(Uri.parse(url))
            video.setOnPreparedListener {
                spinner.visibility = View.GONE
                video.start()
            }
            video.setOnErrorListener { _, _, _ ->
                spinner.visibility = View.GONE
                hint.text = "Couldn't play this video"
                true
            }
            video.setOnCompletionListener {
                if (index + 1 < urls.size) show(index + 1) else finish()
            }
            flashHint("◀ ▶ seek 10 s · OK pause · Back close")
        } else {
            video.stopPlayback()
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            thread {
                val bmp = fetchBitmap(url)
                runOnUiThread {
                    if (index == i) {
                        spinner.visibility = View.GONE
                        if (bmp != null) image.setImageBitmap(bmp) else hint.text = "Couldn't load this photo"
                    }
                }
            }
            flashHint(
                if (urls.size > 1) "${i + 1} / ${urls.size} · ◀ ▶ browse · OK slideshow · Back close"
                else "Back to close"
            )
        }
    }

    private fun flashHint(text: String) {
        hint.text = text
        hint.alpha = 1f
        hint.animate().alpha(0f).setStartDelay(3500).setDuration(600).start()
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        val bytes = c.inputStream.use { it.readBytes() }
        c.disconnect()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 || bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (isVideo) video.seekTo((video.currentPosition + 10_000).coerceAtMost(video.duration.coerceAtLeast(0)))
                else if (urls.size > 1) show((index + 1) % urls.size)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (isVideo) video.seekTo((video.currentPosition - 10_000).coerceAtLeast(0))
                else if (urls.size > 1) show((index - 1 + urls.size) % urls.size)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (isVideo) {
                    if (video.isPlaying) { video.pause(); flashHint("Paused") } else { video.start(); flashHint("Playing") }
                } else if (urls.size > 1) {
                    slideshow = !slideshow
                    handler.removeCallbacks(nextSlide)
                    if (slideshow) { flashHint("Slideshow on"); handler.postDelayed(nextSlide, 6_000) }
                    else flashHint("Slideshow off")
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { video.stopPlayback() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
