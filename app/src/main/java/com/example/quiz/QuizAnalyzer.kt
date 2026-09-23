package com.example.quiz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.JarvisApp
import com.example.service.ScreenshotManager
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Otak fitur AI Quiz Analyzer.
 *
 * Pipeline: Screenshot (MediaProjection existing) → OCR (ML Kit) → Parser soal →
 * AI API existing (AiChatService.rawCompletion — tanpa API key baru) → Parser respons →
 * State yang dibaca overlay.
 *
 * Mode Auto Analyze: capture berkala (min. Delay ms), bandingkan hash layar,
 * analisis HANYA bila layar berubah signifikan. Tidak ada auto-click/submit.
 */
object QuizAnalyzer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val HASH_BITS_CHANGED = 8          // ambang "layar berubah signifikan"
    private val AI_SYSTEM_INSTRUCTION = """
        Kamu adalah analyzer soal kuis/ujian yang sangat akurat. Pengguna mengirim screenshot layar
        berisi satu soal pilihan ganda (beserta hasil OCR mentah). Analisa gambar & teks tersebut.

        ATURAN MUTLAK:
        1. Balas HANYA satu JSON valid — TANPA markdown, TANPA penjelasan di luar JSON.
        2. Skema JSON:
           {"question":"...","options":{"A":"...","B":"...","C":"...","D":"..."},"answer":"C","answerText":"...","explanation":"...","confidence":0.98}
        3. "answer" = huruf opsi (A/B/C/D/E). "answerText" = isi teks opsi terpilih.
        4. "confidence" angka 0.0-1.0 = keyakinanmu.
        5. Jika gambar buram, soal tidak utuh, atau kamu TIDAK YAKIN: jangan mengarang —
           isi "answer" dengan "?", confidence <= 0.2, dan tulis alasannya di "explanation".
        6. Jika soal ISIAN/bukan pilihan ganda (tidak ada opsi): isi "answer" dengan "?",
           dan "answerText" HANYA jawaban akhir sesingkat mungkin (angka + satuan / kata /
           frasa kunci) TANPA kalimat penjelas — jawaban ini akan diketik ke kolom isian.
    """.trimIndent()

    // ---- State untuk overlay ----
    private val _phase = MutableStateFlow(QuizPhase.IDLE)
    val phase: StateFlow<QuizPhase> = _phase.asStateFlow()

    private val _answer = MutableStateFlow<QuizAnswerResult?>(null)
    val answer: StateFlow<QuizAnswerResult?> = _answer.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _autoAnalyze = MutableStateFlow(false)
    val autoAnalyze: StateFlow<Boolean> = _autoAnalyze.asStateFlow()

    private val _delayMs = MutableStateFlow(500L)
    val delayMs: StateFlow<Long> = _delayMs.asStateFlow()

    private val _autoSubmit = MutableStateFlow(false)
    val autoSubmit: StateFlow<Boolean> = _autoSubmit.asStateFlow()

    private val _submitInfo = MutableStateFlow<String?>(null)
    val submitInfo: StateFlow<String?> = _submitInfo.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Thumbnail tangkapan terakhir — tampil di Diagnostic agar user MELIHAT apa yang
    // "dilihat" analyzer (langsung ketahuan bila tangkapannya hitam/kosong/salah layar).
    private val _capturePreview = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val capturePreview: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = _capturePreview.asStateFlow()

    private var autoJob: Job? = null
    private var lastAutoHash: Long? = null
    // Screenshot dari analisis TERAKHIR — dipakai Auto Submit sebagai fallback
    // saat opsi/tombol tidak terdeteksi sebagai elemen accessibility (UI canvas/game).
    @Volatile
    private var lastAnalysisBase64: String? = null


    private val submitPrefs by lazy {
        JarvisApp.instance.getSharedPreferences("jarvis_quiz_prefs", android.content.Context.MODE_PRIVATE)
    }

    fun setAutoSubmit(enabled: Boolean) {
        _autoSubmit.value = enabled
        submitPrefs.edit().putBoolean("quiz_auto_submit", enabled).apply()
    }

    fun loadPersisted() {
        _autoSubmit.value = submitPrefs.getBoolean("quiz_auto_submit", false)
    }

    fun setDelayMs(ms: Long) {
        _delayMs.value = ms.coerceIn(300L, 10_000L)
    }

    fun setAuto(enabled: Boolean) {
        _autoAnalyze.value = enabled
        if (enabled) startAutoLoop() else autoJob?.cancel()
    }

    /**
     * Analisis sekali (tombol Analyze). Bila preCapturedBase64 diberikan (dari auto loop),
     * capture tidak diulang — hemat performa.
     */
    fun analyzeOnce(preCapturedBase64: String? = null) {
        if (_isAnalyzing.value) return
        scope.launch { runAnalysis(preCapturedBase64) }
    }

    private suspend fun runAnalysis(preCapturedBase64: String?): Unit = withContext(Dispatchers.IO) {
        _isAnalyzing.value = true
        _lastError.value = null
        DiagnosticLogger.reset()
        val startedAt = System.currentTimeMillis()
        var ocrBitmap: Bitmap? = null

        try {
            // 1. CAPTURE
            _phase.value = QuizPhase.CAPTURING
            var b64 = preCapturedBase64 ?: ScreenshotManager.captureBase64(JarvisApp.instance).first
            if (b64 == null) {
                DiagnosticLogger.update(capture = QuizStepStatus.FAILED, error = "Izin Screen Capture belum diberikan / screenshot gagal")
                fail("Izin Screen Capture belum aktif. Buka app JARVIS dan izinkan 'Screen Capture' (yang dipakai untuk screenshot), lalu Analyze lagi.")
                return@withContext
            }
            DiagnosticLogger.update(capture = QuizStepStatus.OK)
            lastAnalysisBase64 = b64

            // 2. DECODE + OCR
            _phase.value = QuizPhase.OCR
            ocrBitmap = decodeSampled(b64, 1280)
            if (ocrBitmap == null) {
                DiagnosticLogger.update(ocr = QuizStepStatus.FAILED, error = "Gagal decode screenshot")
                fail("Gagal memproses screenshot (decode gagal).")
                return@withContext
            }
            // Pratinjau tangkapan untuk overlay (setelah ini OCR bisa mendaur ulang bitmapnya)
            runCatching {
                val pv = decodeSampled(b64, 480)
                if (pv != null) _capturePreview.value = pv.asImageBitmap()
            }

            // Deteksi tangkapan kosong/hitam (FLAG_SECURE / display salah / frame pertama hitam)
            val (uniform, meanLum) = captureIsUniform(ocrBitmap)
            DiagnosticLogger.update(
                captureDetail = (ocrBitmap.width.toString() + "x" + ocrBitmap.height + "px, terang rata-rata " + meanLum) +
                    if (uniform) " - BLOK SERAGAM!" else ""
            )
            if (uniform) {
                val first = ocrBitmap
                ocrBitmap = null
                first.recycle()
                delay(700) // frame pertama projection sering hitam -> capture ulang sekali
                val retryB64 = ScreenshotManager.captureBase64(JarvisApp.instance).first
                val rb = if (retryB64 != null) decodeSampled(retryB64, 1280) else null
                if (retryB64 != null && rb != null && !captureIsUniform(rb).first) {
                    b64 = retryB64
                    lastAnalysisBase64 = b64
                    ocrBitmap = rb
                    DiagnosticLogger.update(captureDetail = (rb.width.toString() + "x" + rb.height + "px (capture ulang OK)"))
                    runCatching {
                        val pv = decodeSampled(b64, 480)
                        if (pv != null) _capturePreview.value = pv.asImageBitmap()
                    }
                } else {
                    rb?.recycle()
                    DiagnosticLogger.update(ocr = QuizStepStatus.FAILED, error = "Capture kosong/seragam")
                    fail(
                        "Tangkapan layar KOSONG/HITAM. Penyebab umum: (1) layar berisi soal bukan layar aktif saat capture - buka soalnya lalu Analyze; " +
                            "(2) app soal melarang screenshot (FLAG_SECURE) - coba buka soal di browser/app lain; " +
                            "(3) izin Screen Capture menunjuk display yang salah - berikan ulang izinnya. " +
                            "Cek pratinjau tangkapan di Diagnostic untuk melihat apa yang tertangkap."
                    )
                    return@withContext
                }
            }

            val ocrResult = OcrEngine.recognize(ocrBitmap)
            val ocrText = ocrResult.getOrElse {
                DiagnosticLogger.update(ocr = QuizStepStatus.FAILED, error = it.message)
                fail("OCR gagal: ${it.message ?: "tidak diketahui"}. Coba Analyze lagi.")
                return@withContext
            }
            DiagnosticLogger.update(ocr = QuizStepStatus.OK)

            // OCR hampir kosong tapi gambar tidak blank -> ulangi dgn skala 2x (teks kecil/layar low-DPI)
            var ocrTextFinal = ocrText
            if (ocrText.replace(Regex("[\\s0.,Oo]"), "").length < 3 && ocrBitmap.width < 2048) {
                runCatching {
                    val big = Bitmap.createScaledBitmap(
                        ocrBitmap,
                        (ocrBitmap.width * 2).coerceAtMost(2048),
                        (ocrBitmap.height * 2).coerceAtMost(2048),
                        true
                    )
                    val r2 = OcrEngine.recognize(big).getOrNull()
                    if (!r2.isNullOrBlank() && r2.length > ocrText.length) {
                        ocrTextFinal = r2
                        DiagnosticLogger.update(captureDetail = "OCR diulang dgn skala 2x (teks kecil terbaca)")
                    }
                    big.recycle()
                }
            }

            val parsed = QuestionParser.parse(ocrTextFinal)
            DiagnosticLogger.update(questionDetected = parsed != null, optionCount = parsed?.options?.size ?: 0)

            // 3. AI (API existing — text + gambar)
            _phase.value = QuizPhase.AI
            val userPrompt = buildString {
                appendLine("Analisa soal pada screenshot berikut.")
                if (parsed != null) {
                    appendLine("Hasil OCR layar:")
                    appendLine(ocrTextFinal.take(2500))
                } else {
                    appendLine("OCR mentah (soal mungkin dalam gambar/WebView, parser tidak menemukan opsi):")
                    appendLine(ocrTextFinal.take(1500))
                }
                appendLine("Balas HANYA JSON sesuai instruksi sistem.")
            }
            val (aiText, aiError) = com.example.service.AiChatService.rawCompletion(
                systemInstruction = AI_SYSTEM_INSTRUCTION,
                prompt = userPrompt,
                imageBase64 = b64
            )
            if (aiText.isNullOrBlank()) {
                DiagnosticLogger.update(aiApi = QuizStepStatus.FAILED, error = aiError ?: "respons kosong")
                fail(aiError ?: "AI tidak mengembalikan jawaban.")
                return@withContext
            }
            DiagnosticLogger.update(aiApi = QuizStepStatus.OK)

            // 4. PARSE RESPONS
            val result = AiResponseParser.toAnswerResult(AiResponseParser.extractJsonBlock(aiText))
            if (result == null) {
                DiagnosticLogger.update(response = QuizStepStatus.FAILED, error = "Format respons AI tidak valid")
                fail("Respons AI tidak bisa diparse. Coba Analyze lagi.")
                return@withContext
            }
            DiagnosticLogger.update(
                response = QuizStepStatus.OK,
                latencyMs = System.currentTimeMillis() - startedAt
            )
            _answer.value = result
            _phase.value = QuizPhase.DONE

            // 5. AUTO SUBMIT (opsional — default OFF, hanya bila AI yakin)
            if (_autoSubmit.value && !result.isUncertain && result.confidence >= 0.5f) {
                kotlinx.coroutines.delay(400) // beri waktu UI menampilkan hasil dulu
                try {
                    val msg = if (result.isFillIn) AutoSubmitter.fillAnswer(result, lastAnalysisBase64)
                              else AutoSubmitter.submit(result, lastAnalysisBase64)
                    val ok = msg.startsWith("Ketuk") || msg.startsWith("Isi")
                    DiagnosticLogger.update(autoSubmit = if (ok) QuizStepStatus.OK else QuizStepStatus.FAILED, autoSubmitDetail = msg)
                    _submitInfo.value = msg
                } catch (e: Exception) {
                    DiagnosticLogger.update(autoSubmit = QuizStepStatus.FAILED, autoSubmitDetail = "Error: ${e.message}")
                    _submitInfo.value = "Auto submit gagal: ${e.message}"
                }
            } else if (_autoSubmit.value) {
                DiagnosticLogger.update(autoSubmit = QuizStepStatus.SKIPPED, autoSubmitDetail = "Dilewati (AI kurang yakin)")
                _submitInfo.value = null
            } else {
                DiagnosticLogger.update(clearAutoSubmitDetail = true)
                _submitInfo.value = null
            }
        } catch (e: Exception) {
            DiagnosticLogger.update(error = e.message)
            fail("Error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            ocrBitmap?.recycle()
            _isAnalyzing.value = false
        }
    }

    private fun fail(message: String) {
        _phase.value = QuizPhase.ERROR
        _lastError.value = message
    }

    // ================= AUTO ANALYZE =================
    private fun startAutoLoop() {
        autoJob?.cancel()
        autoJob = scope.launch {
            while (isActive && _autoAnalyze.value) {
                val interval = _delayMs.value
                if (!_isAnalyzing.value) {
                    try {
                        val b64 = ScreenshotManager.captureBase64(JarvisApp.instance).first
                        if (b64 != null) {
                            val hash = quickHash(b64)
                            val prev = lastAutoHash
                            lastAutoHash = hash
                            val changed = prev == null || hammingDistance(prev, hash) >= HASH_BITS_CHANGED
                            if (changed) analyzeOnce(b64) // hash baru = baseline pertama juga dianalisis
                        } else {
                            // izin capture hilang → matikan auto agar tidak loop error
                            setAuto(false)
                            fail("Auto Analyze berhenti: izin Screen Capture tidak tersedia.")
                        }
                    } catch (_: Exception) {
                    }
                }
                delay(interval)
            }
        }
    }

    /**
     * Cek "gambar seragam" (kosong/hitam/putih polos): sampling berhalajah, hitung
     * fraksi piksel yang menyimpang dari kecerahan rata-rata. < 2% = seragam.
     * @return Pair(seragam?, kecerahan rata-rata 0-255)
     */
    private fun captureIsUniform(b: Bitmap): Pair<Boolean, Int> {
        val w = b.width
        val h = b.height
        val sx = (w / 96).coerceAtLeast(1)
        val sy = (h / 96).coerceAtLeast(1)
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
        if (n == 0) return Pair(true, 0)
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
        return Pair(dev.toDouble() / m < 0.02, mean.toInt())
    }

    /** Hash persepsi 8x8 grayscale (average hash) dari base64 JPEG — murah utk cek perubahan layar. */
    private fun quickHash(base64: String): Long {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(b64ToStream(base64), null, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 8 && bounds.outHeight / (sample * 2) >= 8) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val small = BitmapFactory.decodeStream(b64ToStream(base64), null, opts)
            ?: return 0L
        val w = small.width
        val h = small.height
        var hash = 0L
        var bit = 0
        val cellW = (w / 8f).coerceAtLeast(1f)
        val cellH = (h / 8f).coerceAtLeast(1f)
        val pixels = IntArray(w)
        val grays = DoubleArray(64)
        for (cy in 0 until 8) {
            for (cx in 0 until 8) {
                var sum = 0.0
                var count = 0
                val x0 = (cx * cellW).toInt()
                val y0 = (cy * cellH).toInt()
                small.getPixels(pixels, 0, w, x0, y0, cellW.toInt().coerceAtLeast(1), cellH.toInt().coerceAtLeast(1))
                for (p in pixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sum += 0.299 * r + 0.587 * g + 0.114 * b
                    count++
                }
                grays[cy * 8 + cx] = if (count > 0) sum / count else 0.0
                bit++
            }
        }
        val avg = grays.average()
        for (i in 0 until 64) {
            if (grays[i] > avg) hash = hash or (1L shl i)
        }
        small.recycle()
        return hash
    }

    private fun b64ToStream(base64: String) = java.io.ByteArrayInputStream(Base64.decode(base64, Base64.DEFAULT))

    private fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun decodeSampled(base64: String, maxDim: Int): Bitmap? {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
