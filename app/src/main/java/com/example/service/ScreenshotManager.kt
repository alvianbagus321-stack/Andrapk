package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ScreenshotManager {
    private const val TAG = "ScreenshotManager"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width = 720
    private var height = 1280
    private var densityDpi = 320

    private val _isMediaProjectionActive = MutableStateFlow(false)
    val isMediaProjectionActive = _isMediaProjectionActive.asStateFlow()

    fun setMediaProjection(context: Context, projection: MediaProjection) {
        release()
        mediaProjection = projection

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // Scale down for fast transfer & lower memory consumption in base64
        val maxDim = 1080
        val scale = if (metrics.widthPixels > maxDim || metrics.heightPixels > maxDim) {
            maxDim.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels)
        } else {
            1.0f
        }

        width = ((metrics.widthPixels * scale).toInt() / 2) * 2 // ensure even number
        height = ((metrics.heightPixels * scale).toInt() / 2) * 2
        densityDpi = (metrics.densityDpi * scale).toInt()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "JarvisScreenCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(Looper.getMainLooper())
        )

        _isMediaProjectionActive.value = true
        Log.i(TAG, "MediaProjection initialized: ${width}x${height} @ ${densityDpi}dpi")
    }

    fun release() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaProjection", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            _isMediaProjectionActive.value = false
        }
    }

    /**
     * Captures current screen and returns Base64 encoded JPEG.
     */
    suspend fun captureBase64(context: Context): Pair<String?, String?> = withContext(Dispatchers.IO) {
        // Attempt 1: MediaProjection
        val reader = imageReader
        if (reader != null) {
            try {
                val image = acquireLatestImageWithRetry(reader)
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    if (bitmap != null) {
                        val base64 = bitmapToBase64(bitmap)
                        bitmap.recycle()
                        return@withContext Pair(base64, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection capture failed: ${e.message}")
            }
        }

        // Attempt 2: AccessibilityService Fallback
        val accessibilityService = JarvisAccessibilityService.instance
        if (accessibilityService != null) {
            val bitmap = suspendCoroutine<Bitmap?> { cont ->
                accessibilityService.takeScreenshotCompat { bmp ->
                    cont.resume(bmp)
                }
            }
            if (bitmap != null) {
                val base64 = bitmapToBase64(bitmap)
                bitmap.recycle()
                return@withContext Pair(base64, null)
            }
        }

        Pair(
            null,
            "Screenshot requires MediaProjection consent or AccessibilityService on Android 11+. Please grant Screen Capture in JARVIS app."
        )
    }

    private fun acquireLatestImageWithRetry(reader: ImageReader): Image? {
        for (i in 0 until 5) {
            val image = reader.acquireLatestImage()
            if (image != null) return image
            Thread.sleep(60)
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
