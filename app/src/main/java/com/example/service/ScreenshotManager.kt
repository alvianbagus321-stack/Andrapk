package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    @Volatile
    private var lastCaptureInfoStr: String = "belum ada tangkapan"

    /** Info tangkapan terakhir: dimensi + orientasi + sumber (untuk tool & debugging). */
    fun lastCaptureInfo(): String = lastCaptureInfoStr

    private fun captureInfo(w: Int, h: Int, source: String): String {
        val orientasi = when {
            w > h -> "landscape"
            h > w -> "portrait"
            else -> "persegi"
        }
        lastCaptureInfoStr = w.toString() + "x" + h + " (" + orientasi + ") via " + source
        return lastCaptureInfoStr
    }

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
            // maximumWindowMetrics: dimensi penuh display di rotasi AKTIF (anti salah skala)
            val windowMetrics = windowManager.maximumWindowMetrics
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

    /** Rotasi display saat ini: 0/1/2/3 (Surface.ROTATION_*). */
    fun currentRotation(context: Context): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /** Proyeksi layar aktif (untuk ScreenRecordManager & fitur lain yang butuh konsent yang sama). */
    internal fun activeProjection(): MediaProjection? = mediaProjection

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
            // Paksa rebuild ulang pada capture berikutnya — tanpa ini state terlihat
            // "sudah segar" padahal VirtualDisplay null, dan early-return membuat
            // jalur projection mati selamanya.
            createdRotation = -1
            width = -1
            height = -1
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
     * Cek gambar "kosong": seragam DAN gelap. Frame gagal tangkap selalu HITAM;
     * halaman terang polos adalah konten sah (jangan dibuang!).
     * Dipakai memilih sumber tangkapan terbaik (projection vs accessibility).
     */
    private fun isUniformBlank(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        val sx = (w / 64).coerceAtLeast(1)
        val sy = (h / 64).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = b.getPixel(x, y)
                sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                n++
                x += sx
            }
            y += sy
        }
        if (n == 0) return true
        val mean = sum / n
        var dev = 0
        var m = 0
        y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = b.getPixel(x, y)
                val l = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                if (kotlin.math.abs(l - mean) > 24.0) dev++
                m++
                x += sx
            }
            y += sy
        }
        if (dev.toDouble() / m >= 0.02) return false // ada konten yang jelas
        return mean < 40.0 // seragam + gelap = frame gagal tangkap (halaman putih = konten sah)
    }

    /**
     * Captures current screen and returns Base64 encoded JPEG.
     *
     * Chain cerdas:
     *   1. MediaProjection — hasil DIPERIKSA dulu; kalau kosong/hitam (frame pertama,
     *      FLAG_SECURE, virtual display bermasalah) JANGAN langsung dipakai.
     *   2. AccessibilityService.takeScreenshot (API 30+) — jalur tangkap TERPISAH yang
     *      sering berhasil saat projection bermasalah; dipakai bila hasilnya tidak kosong.
     *   3. Dua-duanya kosong → kembalikan gambar kosong (tanpa error) agar pemanggil
     *      (QuizAnalyzer) bisa capture-ulang & menampilkan pesan penyebab yang tepat.
     *
     * @param trace callback opsional (default null) untuk diagnostic sumber tangkapan.
     */
    suspend fun captureBase64(
        context: Context,
        trace: ((String) -> Unit)? = null
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        // FIX rotasi (Bug A): pastikan VirtualDisplay mengikuti orientasi layar SAAT INI.
        // Tanpa ini, layar landscape (mis. 1600x720 saat game/remote dibuka) tetap
        // ditangkap ke buffer portrait 720x1600 -> citra ter-letterbox, OCR gagal.
        ensureFreshVirtualDisplay(context)
        var blankFallbackBase64: String? = null
        val reader = imageReader
        if (reader != null) {
            var image: Image? = null
            try {
                image = acquireLatestImageWithRetry(reader)
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    if (bitmap != null) {
                        if (isUniformBlank(bitmap)) {
                            trace?.invoke("MediaProjection: gambar kosong/hitam")
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                            blankFallbackBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            captureInfo(bitmap.width, bitmap.height, "MediaProjection (kosong)")
                        } else {
                            // GUARD ORIENTASI AKTIF: dimensi frame HARUS cocok dengan layar
                            // SAAT INI (portrait atau landscape). Tidak cocok = frame usang
                            // dari orientasi lama (race rotasi) -> rebuild + capture ulang 1x.
                            val m = getScreenMetrics(context)
                            val cocok = (bitmap.width == m.widthPixels && bitmap.height == m.heightPixels) ||
                                (bitmap.width == m.heightPixels && bitmap.height == m.widthPixels)
                            if (!cocok) {
                                trace?.invoke("Frame usang (" + bitmap.width + "x" + bitmap.height + ", layar " + m.widthPixels + "x" + m.heightPixels + ") - rebuild & ulang")
                                bitmap.recycle()
                                createdRotation = -1
                                width = -1
                                height = -1
                                ensureFreshVirtualDisplay(context)
                                val reader2 = imageReader
                                if (reader2 != null) {
                                    var img2: Image? = null
                                    try {
                                        img2 = acquireLatestImageWithRetry(reader2)
                                        if (img2 != null) {
                                            val bmp2 = imageToBitmap(img2)
                                            if (bmp2 != null && !isUniformBlank(bmp2)) {
                                                val b64 = bitmapToBase64(bmp2)
                                                trace?.invoke("Sumber: MediaProjection (frame segar " + bmp2.width + "x" + bmp2.height + ")")
                                                captureInfo(bmp2.width, bmp2.height, "MediaProjection")
                                                bmp2.recycle()
                                                return@withContext Pair(b64, null)
                                            }
                                            bmp2?.recycle()
                                        }
                                    } finally {
                                        try { img2?.close() } catch (_: Exception) {}
                                    }
                                }
                                // retry gagal -> lanjut ke jalur fallback di bawah
                            } else {
                                val base64 = bitmapToBase64(bitmap)
                                trace?.invoke("Sumber: MediaProjection")
                                captureInfo(bitmap.width, bitmap.height, "MediaProjection")
                                bitmap.recycle()
                                return@withContext Pair(base64, null)
                            }
                        }
                        bitmap.recycle()
                    }
                } else {
                    trace?.invoke("MediaProjection: tidak ada frame")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection capture failed: ${e.message}")
                trace?.invoke("MediaProjection: error")
            } finally {
                // PENTING: image harus SELALU ditutup — kebocoran 1 image mengunci
                // ImageReader (maxImages) sehingga SEMUA capture berikutnya gagal.
                try { image?.close() } catch (_: Exception) {}
            }
        }

        // Attempt 2: AccessibilityService Fallback (jalur tangkap berbeda)
        val accessibilityService = JarvisAccessibilityService.instance
        if (accessibilityService != null) {
            val bitmap = suspendCoroutine<Bitmap?> { cont ->
                accessibilityService.takeScreenshotCompat { bmp ->
                    cont.resume(bmp)
                }
            }
            if (bitmap != null) {
                if (isUniformBlank(bitmap)) {
                    trace?.invoke("Accessibility: gambar kosong/hitam (app mungkin FLAG_SECURE)")
                    bitmap.recycle()
                } else {
                    val base64 = bitmapToBase64(bitmap)
                    trace?.invoke("Sumber: Accessibility screenshot")
                    captureInfo(bitmap.width, bitmap.height, "Accessibility screenshot")
                    bitmap.recycle()
                    return@withContext Pair(base64, null)
                }
            } else {
                trace?.invoke("Accessibility: gagal/tidak tersedia")
            }
        }

        // Attempt 3: screencap via Shizuku (level shell) — SELALU ikut rotasi dan
        // satu-satunya jalur yang menangkap konten FLAG_SECURE (game/app proteksi).
        // Hanya dipakai bila Shizuku berjalan & izinnya sudah diberikan.
        val shizukuPng = AdbShizukuManager.screencapIfPermitted()
        val shizukuBitmap = if (shizukuPng != null) BitmapFactory.decodeByteArray(shizukuPng, 0, shizukuPng.size) else null
        if (shizukuBitmap != null) {
            if (isUniformBlank(shizukuBitmap)) {
                trace?.invoke("Shizuku screencap: kosong")
                shizukuBitmap.recycle()
            } else {
                val base64 = bitmapToBase64(shizukuBitmap)
                trace?.invoke("Sumber: Shizuku screencap (tahan FLAG_SECURE)")
                captureInfo(shizukuBitmap.width, shizukuBitmap.height, "Shizuku screencap")
                shizukuBitmap.recycle()
                return@withContext Pair(base64, null)
            }
        } else {
            trace?.invoke("Shizuku: " + AdbShizukuManager.diagnose())
        }

        if (blankFallbackBase64 != null) return@withContext Pair(blankFallbackBase64, null)

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
