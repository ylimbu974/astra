package dev.astra.app.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mirrors this phone's screen into an ImageReader (MediaProjection) and turns the newest
 * picture into a JPEG when the session asks for one. Nothing is written to storage.
 *
 * One VirtualDisplay lives for the whole time the phone shares (Android 14 allows only one
 * per permission grant). When the phone rotates, the display and reader are resized instead.
 */
class ScreenCapturer(context: Context, private val projection: MediaProjection) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val thread = HandlerThread("astra-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var latest: Bitmap? = null
    private val lock = Object()

    /** Goes up by one for every new picture, so the sender can skip unchanged frames. */
    @Volatile var frameNumber = 0L
        private set

    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) handler.post { resizeIfRotated() }
        }
    }

    fun start() {
        handler.post {
            val (w, h, dpi) = captureSize()
            width = w; height = h
            val r = newReader(w, h)
            reader = r
            virtualDisplay = projection.createVirtualDisplay(
                "astra-share", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
            )
        }
        displays.registerDisplayListener(listener, handler)
    }

    fun stop() {
        displays.unregisterDisplayListener(listener)
        handler.post {
            runCatching { virtualDisplay?.release() }
            runCatching { reader?.close() }
            virtualDisplay = null
            reader = null
            synchronized(lock) { latest?.recycle(); latest = null }
            thread.quitSafely()
        }
    }

    /** The real screen in pixels, scaled so the long side is at most [MAX_EDGE]. */
    @Suppress("DEPRECATION")
    private fun captureSize(): Triple<Int, Int, Int> {
        val m = DisplayMetrics()
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        val scale = minOf(1f, MAX_EDGE.toFloat() / max(m.widthPixels, m.heightPixels))
        // Even sizes keep some phones' video hardware happy.
        val w = ((m.widthPixels * scale).roundToInt() / 2) * 2
        val h = ((m.heightPixels * scale).roundToInt() / 2) * 2
        return Triple(w, h, m.densityDpi)
    }

    private fun resizeIfRotated() {
        val vd = virtualDisplay ?: return
        val (w, h, dpi) = captureSize()
        if (w == width && h == height) return
        val old = reader
        val r = newReader(w, h)
        vd.resize(w, h, dpi)
        vd.surface = r.surface
        reader = r
        width = w; height = h
        runCatching { old?.close() }
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r -> copyLatest(r) }, handler)
        }

    private fun copyLatest(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (rowPadding == 0) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            synchronized(lock) {
                latest?.recycle()
                latest = bmp
            }
            frameNumber++
        } catch (e: Exception) {
            // A frame that can't be read is skipped; the next one comes soon.
        } finally {
            image.close()
        }
    }

    /** The newest picture as a JPEG, or null before the first picture arrives. */
    fun jpeg(quality: String): ByteArray? {
        val (jpegQuality, scale) = when (quality) {
            "high" -> 80 to 1f
            "low" -> 45 to 0.6f
            else -> 60 to 0.85f
        }
        synchronized(lock) {
            val src = latest ?: return null
            val bmp = if (scale < 1f) {
                Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt().coerceAtLeast(2),
                    (src.height * scale).roundToInt().coerceAtLeast(2), true)
            } else src
            val out = ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            if (bmp !== src) bmp.recycle()
            return out.toByteArray()
        }
    }

    companion object {
        const val MAX_EDGE = 1280
    }
}
