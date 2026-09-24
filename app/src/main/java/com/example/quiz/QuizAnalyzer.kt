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
        7. SOAL PANJANG/TERPOTONG: bila soal atau opsi terpotong di tepi layar sehingga kamu
           BELUM BISA menjawab dengan yakin, isi "needsMore": true dan "missing" berisi
           bagian yang belum terlihat (mis. "opsi C-D" / "akhir pertanyaan"). Sistem akan
           men-scroll layar dan mengirimkan lanjutan teksnya. Jika sudah terbaca utuh:
           "needsMore": false dan jawab normal.
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

    // Mode multi-capture soal panjang: true=OTOMATIS (AI atur), false=PILIHAN (user atur)
    private val _captureModeAuto = MutableStateFlow(true)
    val captureModeAuto: StateFlow<Boolean> = _captureModeAuto.asStateFlow()

    // Mode PILIHAN: jumlah scroll+capture tambahan (1-4) sebelum dikirim ke AI
    private val _manualExtraCount = MutableStateFlow(2)
    val manualExtraCount: StateFlow<Int> = _manualExtraCount.asStateFlow()

    // Overlap antar tangkapan (%): 70 = tiap scroll cuma geser 30% layar agar
    // tidak ada bagian soal yang terlewat di antara dua frame berurutan.
    private val _scrollOverlapPercent = MutableStateFlow(70)
    val scrollOverlapPercent: StateFlow<Int> = _scrollOverlapPercent.asStateFlow()

    // Mode Advance terbuka/tertutup — tersimpan; overlay berikutnya mengikuti pilihan terakhir
    private val _advancedShown = MutableStateFlow(false)
    val advancedShown: StateFlow<Boolean> = _advancedShown.asStateFlow()

    // Jumlah jari utk scroll multi-capture: 0=otomatis (2 jari bila remote PC terdeteksi),
    // 1=swipe biasa, 2=dua jari (= roda mouse di app remote PC)
    private val _scrollFingers = MutableStateFlow(0)
    val scrollFingers: StateFlow<Int> = _scrollFingers.asStateFlow()

    // AUTO JAWAB: loop analisis+jawab+submit soal berikutnya sampai selesai/batas
    private val _autoAnswerLoop = MutableStateFlow(false)
    val autoAnswerLoop: StateFlow<Boolean> = _autoAnswerLoop.asStateFlow()
    private val _autoAnswerProgress = MutableStateFlow(0)
    val autoAnswerProgress: StateFlow<Int> = _autoAnswerProgress.asStateFlow()
    private var autoAnswerJob: kotlinx.coroutines.Job? = null

    // Batas soal dikerjakan: true=Otomatis (sampai soal habis/tak terdeteksi), false=Isi sendiri (N soal)
    private val _answerLimitAuto = MutableStateFlow(true)
    val answerLimitAuto: StateFlow<Boolean> = _answerLimitAuto.asStateFlow()
    private val _answerLimitN = MutableStateFlow(5)
    val answerLimitN: StateFlow<Int> = _answerLimitN.asStateFlow()

    // Tangkapan manual utk AI: user tap 📷 sebanyak apa pun lalu Kirim
    private val _manualCaptures = MutableStateFlow(0)
    val manualCaptures: StateFlow<Int> = _manualCaptures.asStateFlow()
    private val manualLock = Any()
    private val manualSeen = LinkedHashSet<String>()
    private var manualBufferText = ""
    private var manualB64Last: String? = null

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
        _captureModeAuto.value = submitPrefs.getBoolean("quiz_capture_auto", true)
        _manualExtraCount.value = submitPrefs.getInt("quiz_manual_extra", 2).coerceIn(1, 100)
        _scrollOverlapPercent.value = submitPrefs.getInt("quiz_scroll_overlap", 70).coerceIn(40, 90)
        _advancedShown.value = submitPrefs.getBoolean("quiz_advance_shown", false)
        _scrollFingers.value = submitPrefs.getInt("quiz_scroll_fingers", 0).coerceIn(0, 2)
        _answerLimitAuto.value = submitPrefs.getBoolean("quiz_answer_limit_auto", true)
        _answerLimitN.value = submitPrefs.getInt("quiz_answer_limit_n", 5).coerceIn(1, 100)
        // autoAnswerLoop SENGAJA tidak dimuat: loop ketuk otomatis tak boleh hidup sendiri saat app restart
    }

    fun setDelayMs(ms: Long) {
        _delayMs.value = ms.coerceIn(300L, 10_000L)
    }

    fun setCaptureModeAuto(auto: Boolean) {
        _captureModeAuto.value = auto
        submitPrefs.edit().putBoolean("quiz_capture_auto", auto).apply()
    }

    fun setManualExtraCount(n: Int) {
        _manualExtraCount.value = n.coerceIn(1, 100) // bebas: user yang putuskan (s.d. 100)
        submitPrefs.edit().putInt("quiz_manual_extra", _manualExtraCount.value).apply()
    }

    /** Overlap 40-90%: makin tinggi = geser makin pendek per langkah (makin aman dari terlewat). */
    fun setScrollOverlap(p: Int) {
        _scrollOverlapPercent.value = p.coerceIn(40, 90)
        submitPrefs.edit().putInt("quiz_scroll_overlap", _scrollOverlapPercent.value).apply()
    }

    fun setAdvancedShown(shown: Boolean) {
        _advancedShown.value = shown
        submitPrefs.edit().putBoolean("quiz_advance_shown", shown).apply()
    }

    fun setScrollFingers(n: Int) {
        _scrollFingers.value = n.coerceIn(0, 2)
        submitPrefs.edit().putInt("quiz_scroll_fingers", _scrollFingers.value).apply()
    }

    fun setAnswerLimitAuto(auto: Boolean) {
        _answerLimitAuto.value = auto
        submitPrefs.edit().putBoolean("quiz_answer_limit_auto", auto).apply()
    }

    fun setAnswerLimitN(n: Int) {
        _answerLimitN.value = n.coerceIn(1, 100) // bebas: user yang putuskan (s.d. 100)
        submitPrefs.edit().putInt("quiz_answer_limit_n", _answerLimitN.value).apply()
    }

    /** Toggle Auto Jawab: ON = mulai loop kerja soal, OFF = berhenti kapan pun. */
    fun setAutoAnswerLoop(enabled: Boolean) {
        _autoAnswerLoop.value = enabled
        submitPrefs.edit().putBoolean("quiz_auto_answer", enabled).apply()
        if (enabled) startAutoAnswerLoop() else autoAnswerJob?.cancel()
    }

    private fun remoteActive(): Boolean =
        com.example.service.JarvisAccessibilityService.instance?.foregroundPackageName()
            ?.let { pkg -> REMOTE_PKG_KEYWORDS.any { pkg.contains(it) } } ?: false

    /**
     * FOKUS STARDESK: streaming remote terkompresi & teks PC kecil di layar HP —
     * perbesar bitmap hingga sisi panjang ±2400px (maks 2.5x) sebelum OCR agar
     * ML Kit sanggup membaca teks mungil dari video remote. Bitmap sumber
     * didaur ulang bila menghasilkan bitmap baru.
     */
    private fun prepRemoteOcr(src: Bitmap): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide >= 2200) return src
        val scale = (2400f / longSide).coerceIn(1f, 2.5f)
        val out = Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (out !== src) src.recycle()
        return out
    }

    private fun startAutoAnswerLoop() {
        autoAnswerJob?.cancel()
        autoAnswerJob = scope.launch {
            var done = 0
            _autoAnswerProgress.value = 0
            DiagnosticLogger.update(captureDetail = "\ud83e\udd16 Auto jawab MULAI (batas: " +
                (if (_answerLimitAuto.value) "sampai selesai" else _answerLimitN.value.toString() + " soal") + ")")
            while (isActive && _autoAnswerLoop.value) {
                // cek batas SEBELUM mengerjakan soal berikutnya
                if (!_answerLimitAuto.value && done >= _answerLimitN.value) {
                    DiagnosticLogger.update(captureDetail = "\ud83e\udd16 Auto jawab SELESAI: batas " + _answerLimitN.value + " soal tercapai")
                    break
                }
                if (_answerLimitAuto.value && done >= 100) { // pengaman loop tak terbatas
                    DiagnosticLogger.update(captureDetail = "\ud83e\udd16 Auto jawab berhenti: pengaman 100 soal")
                    break
                }
                // reset hasil lama supaya tidak salah submit ke soal baru
                _answer.value = null
                _lastError.value = null
                DiagnosticLogger.reset()
                runAnalysis(skipAutoSubmit = true) // submit dikendalikan loop (anti dobel ketukan)
                if (!_autoAnswerLoop.value) break
                val r = _answer.value
                if (r == null || r.isUncertain || r.confidence < 0.5f) {
                    DiagnosticLogger.update(captureDetail = "\ud83e\udd16 Auto jawab BERHENTI: soal tidak terdeteksi / AI tidak yakin (total " + done + " soal)")
                    break
                }
                // kerjakan: ketuk jawaban + tombol submit/berikutnya
                kotlinx.coroutines.delay(400)
                val msg = try {
                    if (r.isFillIn) AutoSubmitter.fillAnswer(r, lastAnalysisBase64)
                    else AutoSubmitter.submit(r, lastAnalysisBase64)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                val ok = msg.startsWith("Ketuk") || msg.startsWith("Isi")
                DiagnosticLogger.update(
                    autoSubmit = if (ok) QuizStepStatus.OK else QuizStepStatus.FAILED,
                    autoSubmitDetail = msg
                )
                _submitInfo.value = msg
                if (!ok) {
                    // aksi ketuk gagal -> lebih aman berhenti daripada menabrak layar buta
                    DiagnosticLogger.update(captureDetail = "\ud83e\udd16 Auto jawab berhenti: aksi ketuk gagal (" + done + " soal selesai)")
                    break
                }
                done++
                _autoAnswerProgress.value = done
                // beri waktu soal berikutnya termuat (remote PC = latency lebih besar)
                delay(if (remoteActive()) 3000 else 2200)
            }
            _autoAnswerLoop.value = false
            submitPrefs.edit().putBoolean("quiz_auto_answer", false).apply()
        }
    }

    /**
     * Mode tangkapan MANUAL: user tap 📷 = simpan OCR layar SEKARANG ke buffer
     * (scroll manual sendiri di antara tap). Dedup otomatis: baris yang sama
     * tidak ditambah dua kali. Lalu "Kirim" menggabungkan semuanya utk AI.
     */
    fun addManualCapture() {
        if (_isAnalyzing.value) return
        scope.launch {
            val b64 = ScreenshotManager.captureBase64(JarvisApp.instance).first
            if (b64 == null) {
                DiagnosticLogger.update(captureDetail = "\ud83d\udcf7 gagal: izin Screen Capture tidak tersedia")
                return@launch
            }
            val remoteNow = remoteActive()
            var bmp = decodeSampled(b64, if (remoteNow) 1568 else 1280)
            if (bmp == null) {
                DiagnosticLogger.update(captureDetail = "\ud83d\udcf7 gagal: tangkapan tidak bisa diproses")
                return@launch
            }
            // FIX: halaman quiz PUTIH POLOS itu KONTEN SAH — hanya frame GELAP seragam
            // yang dianggap kosong (bug lama: halaman putih dibuang diam-diam).
            val (uni, lum) = captureIsUniform(bmp)
            if (uni && lum < 45) {
                bmp.recycle()
                DiagnosticLogger.update(captureDetail = "\ud83d\udcf7 gagal: tangkapan kosong/hitam - coba lagi")
                return@launch
            }
            if (remoteNow) bmp = prepRemoteOcr(bmp) // teks PC kecil di StarDesk
            val boxes = OcrEngine.recognizeWithBoxes(bmp).getOrNull()
            bmp.recycle()
            if (boxes == null) {
                DiagnosticLogger.update(captureDetail = "\ud83d\udcf7 gagal: OCR tidak bisa membaca tangkapan")
                return@launch
            }
            var added = 0
            synchronized(manualLock) {
                if (_manualCaptures.value == 0) { manualSeen.clear(); manualBufferText = "" }
                for (line in boxes.map { it.text }) {
                    val n = normalizeLine(line)
                    if (n.length < 2) continue
                    if (!manualSeen.add(n)) continue
                    manualBufferText += "\n" + line.trim()
                    added++
                }
                manualB64Last = b64
                _manualCaptures.value = _manualCaptures.value + 1
            }
            DiagnosticLogger.update(
                captureDetail = "\ud83d\udcf7 tangkapan ke-" + _manualCaptures.value + " tersimpan (+" + added + " baris baru) - tap Kirim bila sudah"
            )
        }
    }

    /** Kirim semua tangkapan manual (gabungan OCR) ke AI untuk dianalisis. */
    fun sendManualCaptures() {
        if (_isAnalyzing.value) return
        val pair = synchronized(manualLock) {
            val t = Pair(manualBufferText, manualB64Last)
            manualBufferText = ""
            manualB64Last = null
            manualSeen.clear()
            _manualCaptures.value = 0
            t
        }
        if (pair.first.isBlank()) return
        scope.launch { runAnalysis(manualText = pair.first, manualB64 = pair.second) }
    }

    /** Buang buffer tangkapan manual tanpa mengirim. */
    fun clearManualCaptures() {
        synchronized(manualLock) {
            manualBufferText = ""
            manualB64Last = null
            manualSeen.clear()
            _manualCaptures.value = 0
        }
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

    private suspend fun runAnalysis(
        preCapturedBase64: String? = null,
        manualText: String? = null,
        manualB64: String? = null,
        skipAutoSubmit: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        _isAnalyzing.value = true
        _lastError.value = null
        DiagnosticLogger.reset()
        val startedAt = System.currentTimeMillis()
        var ocrBitmap: Bitmap? = null

        // Konteks otomatis: app remote desktop (StarDesk/AnyDesk/dll) sedang tampil?
        val fgPkg = com.example.service.JarvisAccessibilityService.instance?.foregroundPackageName()
        val remoteMode = fgPkg != null && REMOTE_PKG_KEYWORDS.any { fgPkg.contains(it) }
        if (remoteMode) {
            DiagnosticLogger.update(captureDetail = "Mode remote PC terdeteksi ($fgPkg) - scroll pakai 2 jari (= roda mouse)")
        }

        try {
            // 1. CAPTURE
            _phase.value = QuizPhase.CAPTURING
            val capTrace = StringBuilder()
            val capTraceFn: (String) -> Unit = { s ->
                if (capTrace.isNotEmpty()) capTrace.append("; ")
                capTrace.append(s)
            }
            var b64 = preCapturedBase64 ?: manualB64 ?: ScreenshotManager.captureBase64(JarvisApp.instance, capTraceFn).first
            if (b64 == null) {
                DiagnosticLogger.update(capture = QuizStepStatus.FAILED, error = "Izin Screen Capture belum diberikan / screenshot gagal")
                fail("Izin Screen Capture belum aktif. Buka app JARVIS dan izinkan 'Screen Capture' (yang dipakai untuk screenshot), lalu Analyze lagi.")
                return@withContext
            }
            DiagnosticLogger.update(capture = QuizStepStatus.OK)
            lastAnalysisBase64 = b64

            // 2. DECODE + OCR
            _phase.value = QuizPhase.OCR
            ocrBitmap = decodeSampled(b64, if (remoteMode) 1568 else 1280)
            if (ocrBitmap == null) {
                DiagnosticLogger.update(ocr = QuizStepStatus.FAILED, error = "Gagal decode screenshot")
                fail("Gagal memproses screenshot (decode gagal).")
                return@withContext
            }
            // FOKUS STARDESK: teks PC kecil di video remote -> perbesar sebelum OCR
            if (remoteMode && ocrBitmap != null) {
                val before = ocrBitmap!!
                val up = prepRemoteOcr(before)
                if (up !== before) {
                    DiagnosticLogger.update(
                        captureDetail = "OCR di-upscale " + before.width + "x" + before.height + " -> " + up.width + "x" + up.height + " (teks PC kecil)"
                    )
                }
                ocrBitmap = up
            }
            // Pratinjau tangkapan untuk overlay (setelah ini OCR bisa mendaur ulang bitmapnya)
            runCatching {
                val pv = decodeSampled(b64, 480)
                if (pv != null) _capturePreview.value = pv.asImageBitmap()
            }

            // Deteksi tangkapan kosong/hitam (FLAG_SECURE / display salah / frame pertama hitam)
            val (uniform, meanLum) = captureIsUniform(ocrBitmap)
            DiagnosticLogger.update(
                captureDetail = (if (remoteMode) "[RemotePC] " else "") +
                    (if (capTrace.isNotEmpty()) capTrace.toString() + " | " else "") +
                    (ocrBitmap.width.toString() + "x" + ocrBitmap.height + "px, terang rata-rata " + meanLum) +
                    if (uniform && meanLum < 45) " - GELAP SERAGAM (frame gagal tangkap)" else ""
            )
            if (uniform && meanLum < 45) { // hanya frame GELAP yang dianggap gagal tangkap
                val first = ocrBitmap
                ocrBitmap = null
                first.recycle()
                delay(700) // frame pertama projection sering hitam -> capture ulang sekali
                val retryB64 = ScreenshotManager.captureBase64(JarvisApp.instance, capTraceFn).first
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

            val ocrBoxesResult = OcrEngine.recognizeWithBoxes(ocrBitmap)
            val ocrBoxes = ocrBoxesResult.getOrElse {
                DiagnosticLogger.update(ocr = QuizStepStatus.FAILED, error = it.message)
                fail("OCR gagal: ${it.message ?: "tidak diketahui"}. Coba Analyze lagi.")
                return@withContext
            }
            val ocrText = ocrBoxes.joinToString("\n") { it.text }
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

            if (manualText != null) {
                ocrTextFinal = manualText
                DiagnosticLogger.update(captureDetail = "Kirim manual: gabungan tangkapan user dianalisis")
            }

            val parsed = QuestionParser.parse(ocrTextFinal)
            DiagnosticLogger.update(questionDetected = parsed != null, optionCount = parsed?.options?.size ?: 0)

            // 3+4. AI + parse — multi-capture dua mode:
            //   OTOMATIS: AI minta lanjutan via needsMore sampai soal lengkap (maks 4).
            //   PILIHAN : jumlah scroll+capture tambahan ditentukan user (1-4) di HUD.
            // Scroll overlap ~70% (geser 30% tinggi layar per langkah) agar tidak ada
            // bagian soal yang terlewat di antara dua tangkapan berurutan.
            _phase.value = QuizPhase.AI
            val autoDriven = _captureModeAuto.value
            val maxExtras = if (autoDriven) 8 else _manualExtraCount.value
            var extraScrolls = 0
            var addedTotal = 0
            var result: QuizAnswerResult? = null
            var failReason: String? = null
            val seen = ocrTextFinal.lines().map { normalizeLine(it) }.filter { it.length >= 2 }.toMutableSet()
            val svc = com.example.service.JarvisAccessibilityService.instance
            val met = ScreenshotManager.getScreenMetrics(JarvisApp.instance)

            val geser = (100 - _scrollOverlapPercent.value.coerceIn(40, 90)) / 100f
            val yAtas = met.heightPixels * 0.75f
            val yBawah = yAtas - met.heightPixels * geser
            val jari = if (_scrollFingers.value == 0) (if (remoteMode) 2 else 1) else _scrollFingers.value
            suspend fun geserLayar(atas: Boolean) {
                if (svc == null) return
                val y1 = if (atas) yAtas else yBawah
                val y2 = if (atas) yBawah else yAtas
                runCatching {
                    if (jari == 2) svc.twoFingerSwipeCoordinates(met.widthPixels / 2f, y1, met.widthPixels / 2f, y2, 400)
                    else svc.swipeCoordinates(met.widthPixels / 2f, y1, met.widthPixels / 2f, y2, 400)
                }
            }
            suspend fun scrollCaptureMerge(): Int {
                if (svc == null) return -1
                geserLayar(atas = true)
                delay(if (remoteMode) 1500 else 800) // StarDesk butuh waktu render frame baru
                val b64x = ScreenshotManager.captureBase64(JarvisApp.instance, capTraceFn).first ?: return -1
                var bx = decodeSampled(b64x, if (remoteMode) 1568 else 1280) ?: return -1
                if (captureIsUniform(bx).first) { bx.recycle(); return -1 }
                bx = prepRemoteOcr(bx)
                val boxesX = OcrEngine.recognizeWithBoxes(bx).getOrNull()
                if (boxesX == null) { bx.recycle(); return -1 }
                var added = 0
                for (line in boxesX.map { it.text }) {
                    val n = normalizeLine(line)
                    if (n.length < 2) continue
                    if (!seen.add(n)) continue
                    ocrTextFinal += "\n" + line.trim()
                    added++
                }
                bx.recycle()
                return added
            }

            // Mode PILIHAN: lakukan semua scroll+capture dulu (tanpa AI), lalu 1x panggil AI.
            if (!autoDriven && preCapturedBase64 == null && manualText == null) {
                repeat(_manualExtraCount.value) {
                    val added = scrollCaptureMerge()
                    if (added < 0) return@repeat
                    extraScrolls++
                    addedTotal += added
                }
            }

            var aiPass = 0
            while (true) {
                aiPass++
                val userPrompt = buildString {
                    appendLine("Analisa soal pada screenshot berikut.")
                    if (remoteMode) {
                        appendLine("Konteks: layar adalah remote desktop PC ($fgPkg). Konten PC tampil di layar HP - baca teks kecil dengan teliti, abaikan UI remote desktop-nya.")
                    }
                    if (extraScrolls > 0) {
                        appendLine("Catatan: OCR diambil dari " + (extraScrolls + 1) + " tangkapan berurutan (layar di-scroll, saling tumpang-tindih) - duplikat dihapus, urutan baris = urutan baca.")
                        if (autoDriven) {
                            appendLine("Bila soal & SEMUA opsinya sudah terbaca utuh: jawab normal (needsMore=false). Bila masih terpotong: needsMore=true.")
                        }
                    }
                    if (manualText != null) {
                        appendLine("Catatan: teks OCR ini gabungan beberapa tangkapan yang dipilih pengguna - analisis sebagai satu soal utuh.")
                    }
                    if (parsed != null) {
                        appendLine("Hasil OCR layar:")
                        appendLine(ocrTextFinal.take(if (extraScrolls > 0) 4200 else 2800))
                    } else {
                        appendLine("OCR mentah (soal mungkin dalam gambar/WebView, parser tidak menemukan opsi):")
                        appendLine(ocrTextFinal.take(if (extraScrolls > 0) 4200 else 1500))
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
                    failReason = aiError ?: "AI tidak mengembalikan jawaban."
                    break
                }
                val r = AiResponseParser.toAnswerResult(AiResponseParser.extractJsonBlock(aiText))
                if (r == null) {
                    failReason = "Respons AI tidak bisa diparse. Coba Analyze lagi."
                    break
                }
                result = r
                // Lanjutan otomatis hanya di mode OTOMATIS & Analyze manual biasa;
                // di mode PILIHAN jumlahnya sudah ditentukan user di atas.
                if (!r.needsMore || !autoDriven || preCapturedBase64 != null || manualText != null || extraScrolls >= maxExtras) break
                val added = scrollCaptureMerge()
                if (added < 0) break
                extraScrolls++
                addedTotal += added
                DiagnosticLogger.update(captureDetail = "AI minta lanjutan (pass " + (aiPass + 1) + "): +" + added + " baris baru")
                if (added < 2) break // tangkapan baru tak membawa info baru
            }
            // kembalikan posisi scroll (kebalikan arah, jarak sama dgn yang digeser)
            if (extraScrolls > 0 && svc != null) {
                repeat(extraScrolls) {
                    geserLayar(atas = false)
                    delay(350)
                }
                DiagnosticLogger.update(
                    captureDetail = "Multi-scroll (" + (if (autoDriven) "otomatis" else "pilihan") + ", overlap " + _scrollOverlapPercent.value + "%): " + extraScrolls + " capture tambahan (+" + addedTotal + " baris), posisi dikembalikan"
                )
            }
            if (result == null) {
                DiagnosticLogger.update(response = QuizStepStatus.FAILED, error = failReason)
                fail(failReason ?: "AI tidak mengembalikan jawaban.")
                return@withContext
            }
            DiagnosticLogger.update(aiApi = QuizStepStatus.OK)
            DiagnosticLogger.update(
                response = QuizStepStatus.OK,
                latencyMs = System.currentTimeMillis() - startedAt
            )
            _answer.value = result
            _phase.value = QuizPhase.DONE

            // 5. AUTO SUBMIT (opsional — default OFF, hanya bila AI yakin)
            // skipAutoSubmit=true dipakai loop Auto Jawab (submit dikendalikan loop itu sendiri)
            if (!skipAutoSubmit && _autoSubmit.value && !result.isUncertain && result.confidence >= 0.5f) {
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

    // Keyword package app remote desktop (deteksi otomatis konteks PC)
    private val REMOTE_PKG_KEYWORDS = listOf(
        "stardesk", "anydesk", "teamviewer", "rustdesk", "chromeremotedesktop",
        "todesk", "sunlogin", "parsec", "splashtop", "vnc"
    )

    /** Normalisasi baris OCR utk dedup lintas tangkapan. */
    private fun normalizeLine(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Cek "frame gagal tangkap": seragam DAN gelap (hitam). Frame pertama projection /
     * FLAG_SECURE menghasilkan HITAM — itu satu-satunya sinyal kegagalan. Halaman putih
     * polos (mis. quiz di remote desktop StarDesk/AnyDesk) adalah KONTEN SAH, bukan kosong.
     * Sampling diperpadat (grid /48) agar teks tipis tidak lolos dari hitungan.
     * @return Pair(seragam?, kecerahan rata-rata 0-255)
     */
    private fun captureIsUniform(b: Bitmap): Pair<Boolean, Int> {
        val w = b.width
        val h = b.height
        val sx = (w / 48).coerceAtLeast(1)
        val sy = (h / 48).coerceAtLeast(1)
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
