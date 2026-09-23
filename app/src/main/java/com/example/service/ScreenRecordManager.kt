package com.example.service

import android.content.Context
import com.example.model.ToolResult
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Merekam layar perangkat menjadi video MP4 memakai MediaProjection yang sama
 * dengan screenshot (tanpa minta consent lagi) + MediaRecorder.
 *
 * Pemakaian via tool `record_screen`: {"action": "start"|"stop"|"status"}.
 * Dibatasi MAX_RECORD_MS (default 3 menit) agar tidak ada rekam liar yang lupa di-stop.
 * Tanpa audio (video-only) agar tidak butuh izin mikrofon tambahan.
 */
object ScreenRecordManager {
    private const val TAG = "ScreenRecordManager"
    private const val MAX_RECORD_MS = 180_000L
    private const val VIDEO_BIT_RATE = 8_000_000
    private const val VIDEO_FPS = 30

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var autoStop: Runnable? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private fun recordingsDir(context: Context): File {
        // Prioritas folder publik /sdcard/JARVIS/recordings (tahan uninstall) bila izin ada,
        // fallback ke folder app-specific eksternal (selalu bisa ditulis tanpa izin).
        return try {
            if (AdbShizukuManager.isManageStorageGranted()) {
                val sd = android.os.Environment.getExternalStorageDirectory()
                if (sd != null) {
                    val d = File(sd, "JARVIS/recordings")
                    if (d.mkdirs() || d.isDirectory) return d
                }
            }
            File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        } catch (_: Exception) {
            File(context.filesDir, "recordings").apply { mkdirs() }
        }
    }

    suspend fun start(context: Context): ToolResult = withContext(Dispatchers.IO) {
        if (_isRecording.value) {
            return@withContext ToolResult("error", message = "Perekaman SUDAH berjalan. Panggil {\"action\":\"stop\"} dulu untuk menyimpan.")
        }
        val projectionActive = ScreenshotManager.activeProjection()
            ?: return@withContext ToolResult(
                "error",
                message = "Belum ada izin Screen Capture (MediaProjection). Buka app JARVIS dan izinkan 'Screen Capture' dulu (yang biasa dipakai untuk screenshot), lalu ulangi."
            )

        val metrics = ScreenshotManager.getScreenMetrics(context)
        // Encoder menuntut dimensi genap
        val w = metrics.widthPixels / 2 * 2
        val h = metrics.heightPixels / 2 * 2

        val dir = recordingsDir(context)
        val name = "jarvis_rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val outFile = File(dir, name)

        val rec = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
        } catch (e: Exception) {
            return@withContext ToolResult("error", message = "Gagal membuat MediaRecorder: ${e.message}")
        }

        var display: android.hardware.display.VirtualDisplay? = null
        try {
            rec.reset()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(VIDEO_FPS)
            rec.setVideoEncodingBitRate(VIDEO_BIT_RATE)
            rec.setOutputFile(outFile.absolutePath)
            rec.prepare()

            display = projectionActive.createVirtualDisplay(
                "JarvisScreenRecord",
                w, h, metrics.densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, mainHandler
            )

            projection = projectionActive
            recorder = rec
            virtualDisplay = display
            outputFile = outFile
            startedAt = System.currentTimeMillis()
            rec.start()
            _isRecording.value = true

            // Safety net: auto-stop agar tidak ada rekaman lupa dimatikan
            autoStop = Runnable { stopAsync() }.also { mainHandler.postDelayed(it, MAX_RECORD_MS) }

            // Matikan perekaman otomatis kalau user mencabut izin capture
            try {
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        if (_isRecording.value) stopAsync()
                    }
                }
                projectionCallback = cb
                projectionActive.registerCallback(cb, mainHandler)
            } catch (_: Exception) {}

            Log.i(TAG, "Mulai merekam: ${outFile.absolutePath} (${w}x${h})")
            ToolResult(
                status = "ok",
                result = "🔴 Merekam layar dimulai (${w}x${h}, ${VIDEO_FPS}fps, maks ${MAX_RECORD_MS / 1000} detik).\n" +
                        "📁 File: ${outFile.absolutePath}\n" +
                        "Panggil {\"tool\":\"record_screen\",\"params\":{\"action\":\"stop\"}} untuk menyimpan."
            )
        } catch (e: Exception) {
            try { display?.release() } catch (_: Exception) {}
            try { rec.reset(); rec.release() } catch (_: Exception) {}
            recorder = null; virtualDisplay = null; projection = null; outputFile = null
            _isRecording.value = false
            ToolResult("error", message = "Gagal memulai perekaman: ${e.message}")
        }
    }

    /** Dipanggil dari callback non-suspend (Runnable/Camera callback) — aman dibatalkan. */
    private fun stopAsync() {
        CoroutineScope(Dispatchers.Main.immediate).launch { runCatching { stop() } }
    }

    suspend fun stop(): ToolResult = withContext(Dispatchers.IO) {
        if (!_isRecording.value) {
            return@withContext ToolResult("error", message = "Tidak ada perekaman yang berjalan (panggil {\"action\":\"start\"} dulu).")
        }
        autoStop?.let { mainHandler.removeCallbacks(it) }
        autoStop = null
        val file = outputFile
        try { recorder?.stop() } catch (e: Exception) {
            Log.w(TAG, "recorder.stop: ${e.message}")
        }
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        try {
            projectionCallback?.let { cb -> projection?.unregisterCallback(cb) }
        } catch (_: Exception) {}
        recorder = null; virtualDisplay = null; projection = null; projectionCallback = null
        _isRecording.value = false

        if (file != null && file.exists() && file.length() > 0) {
            val dur = (System.currentTimeMillis() - startedAt) / 1000
            ToolResult(
                status = "ok",
                result = "✅ Perekaman disimpan: ${file.absolutePath}\n⏱️ Durasi ±${dur}s | 📦 Ukuran ${file.length() / 1024} KB"
            )
        } else {
            file?.delete()
            ToolResult("error", message = "Perekaman terlalu pendek / tidak ada data frame yang terekam. Coba lagi dengan durasi lebih panjang.")
        }
    }

    fun status(): ToolResult {
        return if (_isRecording.value) {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            ToolResult("ok", result = "🔴 Sedang merekam (${elapsed}s berjalan, maks ${MAX_RECORD_MS / 1000}s).\n📁 ${outputFile?.absolutePath ?: "-"}")
        } else {
            ToolResult("ok", result = "⚪ Tidak sedang merekam. Mulai dengan {\"action\":\"start\"}.")
        }
    }
}
