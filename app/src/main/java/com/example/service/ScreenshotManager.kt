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
    private var projectionCallback: MediaProjection.Callback? = null
    private var width = 720
    private var height = 1280
    private var densityDpi = 320
    private var createdRotation: Int = -1

    private val _isMediaProjectionActive = MutableStateFlow(false)
    val isMediaProjectionActive = _isMediaProjectionActive.asStateFlow()

    data class ScreenMetrics(
        val widthPixels: Int,
        val heightPixels: Int,
        val densityDpi: Int,
        val density: Float
    )

    fun getScreenMetrics(context: Context): ScreenMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val densityDpi = context.resources.configuration.densityDpi
            val density = context.resources.displayMetrics.density
            ScreenMetrics(bounds.width(), bounds.height(), densityDpi, density)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            ScreenMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, metrics.density)
        }
    }

    fun setMediaProjection(context: Context, projection: MediaProjection) {
        release()
        mediaProjection = projection

        // Register callback BEFORE createVirtualDisplay (MANDATORY on Android 14+ / API 34+)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.i(TAG, "MediaProjection session stopped by system")
                release()
            }
        }
        projectionCallback = callback
        try {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register MediaProjection callback: ${e.message}")
        }

        // Use EXACT native hardware display metrics for virtualDisplay to prevent SurfaceFlinger top-cropping
        val metrics = getScreenMetrics(context)
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        try {
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
            createdRotation = currentRotation(context)
            _isMediaProjectionActive.value = true
            Log.i(TAG, "MediaProjection initialized 100% full screen: ${width}x${height} @ ${densityDpi}dpi")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection virtual display: ${e.message}", e)
            release()
        }
    }

    /**
     * Memastikan VirtualDisplay mengikuti orientasi layar SAAT INI.
     * Tanpa ini, screenshot setelah layar berputar (mis. remote desktop landscape)
     * menghasilkan citra salah orientasi / salah skala — OCR dan koordinat tap jadi miss.
     * Aman dipanggil kapan pun: hanya rebuild bila rotasi/dimensi benar-benar berubah,
     * dan TIDAK butuh consent ulang (memakai MediaProjection yang sama).
     */
    private fun ensureFreshVirtualDisplay(context: Context) {
        val projection = mediaProjection ?: return
        val rotation = currentRotation(context)
        val metrics = getScreenMetrics(context)
        if (rotation == createdRotation && metrics.widthPixels == width && metrics.heightPixels == height) {
            return
        }
        Log.i(
            TAG,
            "Display berubah (rot $createdRotation->$rotation, ${width}x${height} -> ${metrics.widthPixels}x${metrics.heightPixels}) — rebuild VirtualDisplay"
        )
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi
        createdRotation = rotation
        try {
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
            // Beri waktu SurfaceFlinger merender frame pertama ke surface baru.
            Thread.sleep(150)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal rebuild VirtualDisplay: ${e.message}", e)
        }
    }

    fun release() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtualDisplay", e)
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing imageReader", e)
        }
        try {
            projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering callback", e)
        }
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaProjection", e)
        }
        projectionCallback = null
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        _isMediaProjectionActive.value = false
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
        val maxDim = 1568
        val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
