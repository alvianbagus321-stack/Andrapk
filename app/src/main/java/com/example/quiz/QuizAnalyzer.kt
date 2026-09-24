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
import kotlinx.coroutines.withTimeoutOrNull

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

    // SOP soal panjang langkah 1: swipe ke puncak dulu sebelum capture pertama
    // (jaminan soal terbaca dari baris pertamanya). Bisa dimatikan di Mode Advance.
    private val _scrollToTopOnAnalyze = MutableStateFlow(true)
    val scrollToTopOnAnalyze: StateFlow<Boolean> = _scrollToTopOnAnalyze.asStateFlow()

    // Auto-sweep bila hasil kurang yakin (fallback otomatis) — toggleable
    private val _autoSweep = MutableStateFlow(false)
    val autoSweep: StateFlow<Boolean> = _autoSweep.asStateFlow()

    // MODE ANALYZER: true=PRO (lengkap: multi-capture, sweep, gulir, diagnostic, dll)
    // false=DEFAULT (bersih: Analyze + Auto Jawab + Auto Submit + auto-minimize)
    private val _proMode = MutableStateFlow(true)
    val proMode: StateFlow<Boolean> = _proMode.asStateFlow()

    // FALLBACK sweep: scroll terus dari atas ke bawah sambil menangkap frame
    private val _sweeping = MutableStateFlow(false)
    val sweeping: StateFlow<Boolean> = _sweeping.asStateFlow()

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
    private val manualB64List = ArrayList<String>() // SEMUA gambar tangkapan manual (maks 6)

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
        _scrollFingers.value = submitPrefs.getInt("quiz_scroll_fingers", 0).coerceIn(0, 3)
        _answerLimitAuto.value = submitPrefs.getBoolean("quiz_answer_limit_auto", true)
        _answerLimitN.value = submitPrefs.getInt("quiz_answer_limit_n", 5).coerceIn(1, 100)
        _scrollToTopOnAnalyze.value = submitPrefs.getBoolean("quiz_scroll_to_top", true)
        _autoSweep.value = submitPrefs.getBoolean("quiz_auto_sweep", false)
        _proMode.value = submitPrefs.getBoolean("quiz_pro_mode", true)
        _wheelSide.value = submitPrefs.getInt("quiz_wheel_side", 0).coerceIn(0, 2)
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

    fun setScrollToTopOnAnalyze(v: Boolean) {
        _scrollToTopOnAnalyze.value = v
        submitPrefs.edit().putBoolean("quiz_scroll_to_top", v).apply()
    }

    fun setAutoSweep(v: Boolean) {
        _autoSweep.value = v
        submitPrefs.edit().putBoolean("quiz_auto_sweep", v).apply()
    }

    fun setProMode(pro: Boolean) {
        _proMode.value = pro
        submitPrefs.edit().putBoolean("quiz_pro_mode", pro).apply()
    }

    fun setScrollFingers(n: Int) {
        _scrollFingers.value = n.coerceIn(0, 3)
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

    private fun capTraceFun2Mark() {} // penanda: langkah mulai-dari-atas selesai (log lewat captureDetail di bawah)

    // ===== WIDGET RODA STARDESK: deteksi posisi dari screenshot =====
    // Roda bisa di tepi KIRI atau KANAN layar (umumnya KIRI saat landscape).
    // Jangan menebak: deteksi dulu dari screenshot, lalu drag TEPAT di rodanya.

    /** Posisi roda: 0=otomatis (deteksi; fallback kiri landscape / kanan portrait), 1=kiri, 2=kanan */
    private val _wheelSide = MutableStateFlow(0)
    val wheelSide: StateFlow<Int> = _wheelSide.asStateFlow()

    fun setWheelSide(v: Int) {
        _wheelSide.value = v.coerceIn(0, 2)
        submitPrefs.edit().putInt("quiz_wheel_side", _wheelSide.value).apply()
    }

    /** Deteksi terakhir (xNorm, yNorm) 0..1; null = belum terdeteksi sesi ini. */
    @Volatile
    private var wheelDetected: Pair<Float, Float>? = null

    /**
     * Cari widget roda StarDesk lewat UI HIERARCHY (accessibility) — TANPA
     * screenshot, instan. Roda = view SEMPIT & menjulur di strip tepi
     * kiri/kanan layar (pilar gelap tanpa teks).
     * @return (xNorm, yNorm) pusat roda 0..1, atau null bila tidak ketemu.
     */
    private fun findWheelViaHierarchy(): Pair<Float, Float>? {
        val svc = com.example.service.JarvisAccessibilityService.instance ?: return null
        return runCatching {
            val roots = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
            runCatching { svc.windows?.forEach { w -> w.root?.let { roots.add(it) } } }
            if (roots.isEmpty()) svc.rootInActiveWindow?.let { roots.add(it) }
            if (roots.isEmpty()) return null
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val sw = dm.widthPixels.toFloat()
            val sh = dm.heightPixels.toFloat()
            var best: Pair<Float, Float>? = null
            var bestScore = 0f
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
                if (n == null) return
                val r = android.graphics.Rect()
                if (n.isVisibleToUser && n.getBoundsInScreen(r)) {
                    val w = r.width().toFloat()
                    val h = r.height().toFloat()
                    val cx = r.centerX().toFloat()
                    val cy = r.centerY().toFloat()
                    val nearEdge = cx < sw * 0.12f || cx > sw * 0.88f
                    val narrow = w <= sw * 0.09f
                    val tallEnough = h >= sh * 0.06f && h <= sh * 0.45f
                    val midY = cy in sh * 0.15f..sh * 0.85f
                    if (nearEdge && narrow && tallEnough && midY && h > w) {
                        val edgeDist = minOf(cx, sw - cx) / sw
                        val score = (h / sh) * 2f - edgeDist * 4f
                        if (score > bestScore) {
                            bestScore = score
                            best = Pair(cx / sw, cy / sh)
                        }
                    }
                }
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            roots.forEach { walk(it) }
            best
        }.getOrNull()
    }

    /** Deteksi posisi roda SEKALI (hasil di-cache); dipanggil di awal analisis/sweep.
     *  Urutan: PINDAI SCREENSHOT dulu (roda = overlay gambar StarDesk, bukan
     *  node UI) -> UI hierarchy hanya sebagai cadangan. */
    private suspend fun ensureWheelDetected(remote: Boolean) {
        if (wheelDetected != null) return
        if (!_proMode.value) return // mode DEFAULT: tanpa gesture, roda tak dibutuhkan
        val jariPref = _scrollFingers.value
        if (jariPref == 1 || jariPref == 2) return // mode ini tidak memakai roda
        if (jariPref == 0 && !remote) return // otomatis + bukan remote = swipe biasa
        // 1) UTAMA: pindai SCREENSHOT (widget roda = overlay gambar, bukan node UI)
        runCatching {
            val b64 = ScreenshotManager.captureBase64(JarvisApp.instance).first
            val det = b64?.let { detectWheelNorm(it) }
            if (det != null) {
                wheelDetected = det
                DiagnosticLogger.update(
                    captureDetail = "\ud83d\udd04 Roda StarDesk terdeteksi via SCREENSHOT di sisi " +
                        (if (det.first < 0.5f) "KIRI" else "KANAN") + " layar"
                )
                return
            }
        }
        // 2) CADANGAN: UI hierarchy (kalau suatu saat roda diekspos sbg node)
        runCatching {
            val viaHierarchy = findWheelViaHierarchy()
            if (viaHierarchy != null) {
                wheelDetected = viaHierarchy
                DiagnosticLogger.update(
                    captureDetail = "\ud83d\udd04 Roda StarDesk terdeteksi via UI HIERARCHY di sisi " +
                        (if (viaHierarchy.first < 0.5f) "KIRI" else "KANAN") + " layar"
                )
            }
        }
    }

    /**
     * Deteksi widget roda dari screenshot: cari pita gelap vertikal di strip
     * tepi kiri/kanan (roda = pill semi-transparan gelap dgn tinggi wajar).
     * @return (xNorm, yNorm) pusat roda 0..1, atau null bila tak ditemukan.
     */
    private fun detectWheelNorm(base64: String): Pair<Float, Float>? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeStream(b64ToStream(base64), null, opts)
                ?: return null
            val w = bmp.width
            val h = bmp.height
            if (w < 40 || h < 60) return null
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            fun lum(x: Int, y: Int): Float {
                val c = px[y * w + x]
                return 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            val stripW = (w * 0.09f).toInt().coerceIn(4, 24)
            var bestLen = 0
            var bestSide = -1
            var bestCenterY = 0.5f
            for (side in 0..1) {
                val x0 = if (side == 0) 0 else w - stripW
                val rowMean = FloatArray(h) { y ->
                    var s = 0f
                    for (x in x0 until x0 + stripW) s += lum(x, y)
                    s / stripW
                }
                val sorted = rowMean.copyOf()
                sorted.sort()
                val med = sorted[h / 2]
                var runStart = -1
                var bl = 0
                var bs = 0
                for (y in 0..h) {
                    val dark = y < h && rowMean[y] < med - 28f
                    if (dark) {
                        if (runStart < 0) runStart = y
                    } else if (runStart >= 0) {
                        if (y - runStart > bl) { bl = y - runStart; bs = runStart }
                        runStart = -1
                    }
                }
                val minLen = (h * 0.06f).toInt()
                val maxLen = (h * 0.45f).toInt()
                if (bl in minLen..maxLen && bl > bestLen) {
                    bestLen = bl
                    bestSide = side
                    bestCenterY = (bs + bl / 2f) / h
                }
            }
            if (bestSide < 0) null
            else Pair(
                if (bestSide == 0) (stripW * 0.55f) / w else (w - stripW * 0.55f) / w,
                bestCenterY
            )
        }.getOrNull()
    }

    /** Koordinat drag roda (px): override manual > deteksi screenshot > fallback orientasi. */
    private fun resolveWheelPos(w: Int, h: Int): Pair<Float, Float> {
        when (_wheelSide.value) {
            1 -> return Pair(w * 0.045f, h * 0.5f)
            2 -> return Pair(w - w * 0.045f, h * 0.5f)
        }
        wheelDetected?.let { return Pair(it.first * w, it.second * h) }
        // Fallback: landscape -> KIRI (posisi umum roda saat remote), portrait -> kanan
        return if (h > w) Pair(w * 0.045f, h * 0.5f) else Pair(w - 30f, h * 0.5f)
    }

    /**
     * Mode gulir jari: 1=swipe biasa, 2=dua jari (roda mouse), 3=RODA STARDESK
     * (drag pelan 1 jari tepat di widget roda — posisinya DIDETEKSI dari
     * screenshot, kiri/kanan — ditarik atas/bawah = menggulung PC; PALING
     * andal untuk StarDesk).
     */
    private fun resolveJari(remote: Boolean): Int =
        if (_scrollFingers.value == 0) (if (remote) 3 else 1) else _scrollFingers.value

    /** Eksekusi gesture gulir sesuai mode jari. atas=true = konten bergulir ke bawah. */
    private suspend fun doScrollGesture(
        svc: com.example.service.JarvisAccessibilityService,
        met: ScreenshotManager.ScreenMetrics,
        jari: Int,
        atas: Boolean,
        jarakFraksi: Float = 0.40f
    ) {
        runCatching {
            if (jari == 3) {
                // RODA STARDESK: drag pelan TEPAT di widget roda — posisinya
                // dideteksi dari screenshot (kiri/kanan), bukan ditebak.
                // PENTING: drag yang menebak di area streaming PC justru
                // MENGERAKKAN KURSOR remote (bukan scroll). Maka bila roda
                // tidak terkonfirmasi, drag DILEWATI — bukan ditebak.
                val manual = _wheelSide.value != 0
                if (!manual && wheelDetected == null) {
                    DiagnosticLogger.update(
                        captureDetail = "Roda StarDesk tidak terkonfirmasi - drag roda dilewati (agar kursor PC tidak ikut bergerak)"
                    )
                    return
                }
                val p = resolveWheelPos(met.widthPixels, met.heightPixels)
                val wx = p.first
                val cy = p.second
                val off = met.heightPixels * 0.06f
                val y1 = if (atas) cy - off else cy + off
                val y2 = if (atas) cy + off else cy - off
                svc.swipeCoordinates(wx, y1, wx, y2, 500)
            } else {
                val cx = met.widthPixels / 2f
                val y1 = met.heightPixels * (if (atas) 0.75f else 0.35f)
                val y2 = y1 + (if (atas) -1f else 1f) * met.heightPixels * jarakFraksi
                if (jari == 2) svc.twoFingerSwipeCoordinates(cx, y1, cx, y2, 400)
                else svc.swipeCoordinates(cx, y1, cx, y2, 400)
            }
        }
    }

    /**
     * Scroll ke atas SAMPAI BENAR-BENAR MENTOK (bukan swipe buta).
     * Siklus: [swipe-ke-atas beberapa kali] -> [capture+OCR pembanding] ->
     * bila masih ada baris baru = halaman masih bergerak -> ulangi;
     * bila layar tidak berubah = puncak tercapai -> selesai.
     * @return jumlah siklus yang dilakukan (0 = Accessibility tidak aktif).
     */
    private suspend fun scrollToTopUntilStuck(): Int = withContext(Dispatchers.IO) {
        var cycles = 0
        val svc = com.example.service.JarvisAccessibilityService.instance
            ?: return@withContext 0
        val met = ScreenshotManager.getScreenMetrics(JarvisApp.instance)
        val remoteNow = remoteActive()
        val jari = resolveJari(remoteNow)
        suspend fun swipeUp() { doScrollGesture(svc, met, jari, atas = false, jarakFraksi = 0.40f) }
        val seen = HashSet<String>()
        var prevNew = -1
        while (cycles < 6) { // pengaman: maks 6 siklus
            repeat(3) { swipeUp(); delay(if (remoteNow) 900 else 450) }
            val b64 = ScreenshotManager.captureBase64(JarvisApp.instance).first
                ?: break
            var bmp = decodeSampled(b64, if (remoteNow) 1568 else 1280) ?: break
            val (uni, lum) = captureIsUniform(bmp)
            if (uni && lum < 45) { bmp.recycle(); break }
            bmp = prepRemoteOcr(bmp)
            val boxes = OcrEngine.recognizeWithBoxes(bmp).getOrNull()
            bmp.recycle()
            if (boxes == null) break
            var newLines = 0
            for (line in boxes.map { it.text }) {
                val n = normalizeLine(line)
                if (n.length >= 2 && seen.add(n)) newLines++
            }
            cycles++
            DiagnosticLogger.update(
                captureDetail = "Ke atas: siklus " + cycles + " (+" + newLines + " baris baru)" +
                    (if (newLines == 0) " - PUNCAK tercapai" else "")
            )
            if (newLines == 0) break // layar tak menghasilkan konten baru -> mentok atas
            if (prevNew == 0 && newLines == 0) break
            prevNew = newLines
        }
        cycles
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
            // Auto-minimize: HUD tidak ikut tertangkap di tangkapan manual
            val hudWasMin = !com.example.ui.QuizOverlayManager.isMinimized.value
            if (hudWasMin) {
                com.example.ui.QuizOverlayManager.minimize()
                delay(600)
            }
            try {
            val b64 = ScreenshotManager.captureBase64(JarvisApp.instance).first
            if (b64 == null) {
                DiagnosticLogger.update(
                    captureDetail = "\ud83d\udcf7 gagal: sesi tangkap layar MATI - ketuk " +
                        "peringatan merah di HUD utk izin ulang (Termux-ADB tetap dicoba otomatis)"
                )
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
                if (manualB64List.size < 6) manualB64List.add(b64)
                _manualCaptures.value = _manualCaptures.value + 1
            }
            DiagnosticLogger.update(
                captureDetail = "\ud83d\udcf7 tangkapan ke-" + _manualCaptures.value + " tersimpan (+" + added + " baris baru) - tap Kirim bila sudah"
            )
            } finally {
                // HUD kembali tampil supaya bisa tap Tambah/Kirim berikutnya
                if (hudWasMin) com.example.ui.QuizOverlayManager.expand()
            }
        }
    }

    /** Kirim semua tangkapan manual (gabungan OCR) ke AI untuk dianalisis. */
    fun sendManualCaptures() {
        if (_isAnalyzing.value) return
        val pair = synchronized(manualLock) {
            val t = Pair(manualBufferText, ArrayList(manualB64List))
            manualBufferText = ""
            manualB64List.clear()
            manualSeen.clear()
            _manualCaptures.value = 0
            t
        }
        if (pair.first.isBlank()) return
        scope.launch { runAnalysis(manualText = pair.first, manualFrames = pair.second) }
    }

    /**
     * KONTROL GULIR MANUAL (tombol di HUD): gulung layar/PC sekarang tanpa Analyze.
     * Di StarDesk memakai gesture 2 jari (= roda mouse di PC), di app biasa swipe 1 jari —
     * mengikuti setelan "Gulir saat multi-capture" (Otomatis/1 jari/2 jari).
     */
    fun remoteScroll(up: Boolean) {
        if (_isAnalyzing.value) return
        scope.launch {
            val svc = com.example.service.JarvisAccessibilityService.instance
            if (svc == null) {
                DiagnosticLogger.update(captureDetail = "Gulir gagal: Accessibility Service belum aktif")
                return@launch
            }
            val met = ScreenshotManager.getScreenMetrics(JarvisApp.instance)
            val jari = resolveJari(remoteActive())
            doScrollGesture(svc, met, jari, atas = up, jarakFraksi = 0.35f)
            val ok = true
            DiagnosticLogger.update(
                captureDetail = (if (up) "\u25b2" else "\u25bc") + " gulir " + (if (jari == 2) "2 jari (roda mouse)" else "1 jari") + (if (ok) "" else " - gagal")
            )
        }
    }

    /**
     * Gulir via TOMBOL KEYBOARD PC — dikirim `input keyevent` lewat Shizuku; app remote
     * (StarDesk) meneruskan keyevent Android ke PC. Ini jalur paling pasti menggulung
     * halaman browser PC saat gesture 2 jari tidak mempan.
     * mode: pageup=PageUp(92), pagedown=PageDown(93)
     */
    fun scrollKey(mode: String) {
        if (_isAnalyzing.value) return
        scope.launch {
            val code = when (mode) {
                "pageup" -> 92
                "pagedown" -> 93
                else -> 93
            }
            // Jalur 1: Shizuku (paling cepat)
            var ok = com.example.service.AdbShizukuManager.inputKeyevent(code)
            var via = "Shizuku"
            // Jalur 2: Termux-ADB (fire-and-forget via RUN_COMMAND; butuh setup_adb.sh
            // dijalankan sekali di Termux: adb connect + allow-external-apps)
            if (!ok) {
                val ctx = JarvisApp.instance
                if (com.example.service.AdbShizukuManager.isTermuxInstalled(ctx)) {
                    runCatching {
                        com.example.service.AdbShizukuManager.sendTermuxRunCommandIntent(
                            ctx,
                            "/data/data/com.termux/files/usr/bin/adb",
                            arrayOf("shell", "input", "keyevent", code.toString())
                        )
                    }
                    ok = true
                    via = "Termux-ADB (tanpa konfirmasi hasil)"
                }
            }
            DiagnosticLogger.update(
                captureDetail = if (ok) "\u2328 kunci terkirim via " + via
                else "\u2328 gagal: Shizuku belum terhubung & Termux/ADB belum disiapkan (jalankan termux/setup_adb.sh)"
            )
        }
    }

    /**
     * FALLBACK terakhir: scroll TERUS dari atas ke bawah sambil menangkap frame di
     * tiap langkah. Berhenti otomatis saat MENTOK (2 langkah berturut tanpa baris baru),
     * saat batas 12 langkah, atau saat stopSweep() dipanggil (dari user / keputusan AI).
     * @return Pair(teks gabungan tanpa duplikat, daftar frame base64 utk AI) atau null.
     */
    private suspend fun performSweep(): Pair<String, List<String>>? = withContext(Dispatchers.IO) {
        _sweeping.value = true
        try {
            val svc = com.example.service.JarvisAccessibilityService.instance
                ?: return@withContext null
            val met = ScreenshotManager.getScreenMetrics(JarvisApp.instance)
            val remoteNow = remoteActive()
            val jari = resolveJari(remoteNow)
            ensureWheelDetected(remoteNow)
            suspend fun swipeTo(y1: Float, y2: Float, d: Long) {
                // arah: y1->y2 dgn y2<y1 = scroll ke bawah; y2>y1 = scroll ke atas
                doScrollGesture(svc, met, jari, atas = y2 < y1, jarakFraksi = kotlin.math.abs(y2 - y1) / met.heightPixels)
            }
            // mulai dari PUNCAK halaman — sampai BENAR-BENAR mentok (swipe+cek siklus)
            scrollToTopUntilStuck()
            val seen = HashSet<String>()
            val combined = StringBuilder()
            val frames = ArrayList<String>()
            val geser = (100 - _scrollOverlapPercent.value.coerceIn(40, 90)) / 100f
            val yAtas = met.heightPixels * 0.75f
            val yBawah = yAtas - met.heightPixels * geser
            var noNew = 0
            var step = 0
            val sweepStart = System.currentTimeMillis()
            while (_sweeping.value && step < 12 && noNew < 2) {
                if (System.currentTimeMillis() - sweepStart > 120_000L) {
                    DiagnosticLogger.update(captureDetail = "Sweep dihentikan: batas 120 detik")
                    break
                }
                val b64 = ScreenshotManager.captureBase64(JarvisApp.instance).first ?: break
                var bmp = decodeSampled(b64, if (remoteNow) 1568 else 1280) ?: break
                val (uni, lum) = captureIsUniform(bmp)
                if (uni && lum < 45) { bmp.recycle(); break }
                bmp = prepRemoteOcr(bmp)
                val boxes = OcrEngine.recognizeWithBoxes(bmp).getOrNull()
                bmp.recycle()
                if (boxes == null) break
                var added = 0
                for (line in boxes.map { it.text }) {
                    val n = normalizeLine(line)
                    if (n.length < 2) continue
                    if (seen.add(n)) { combined.append("\n").append(line.trim()); added++ }
                }
                if (frames.size < 6) frames.add(b64) // semua frame ikut ke AI (multi-gambar)
                if (added == 0) noNew++ else noNew = 0
                step++
                DiagnosticLogger.update(
                    captureDetail = "Sweep: " + step + " frame, +" + added + " baris" + (if (noNew >= 1) " (mendekati bawah)" else "")
                )
                if (noNew >= 2) break // MENTOK bawah
                swipeTo(yAtas, yBawah, 400)
                delay(if (remoteNow) 1500 else 800)
            }
            // rapikan: kembali ke puncak
            repeat(2) {
                swipeTo(met.heightPixels * 0.35f, met.heightPixels * 0.75f, 350)
                delay(300)
            }
            if (combined.isBlank()) null else Pair(combined.toString(), frames)
        } finally {
            _sweeping.value = false
        }
    }

    /** Mulai sweep penuh lalu analisis gabungannya (tombol HUD). */
    fun startSweepAnalyze() {
        if (_isAnalyzing.value || _sweeping.value) return
        scope.launch {
            val swept = performSweep()
            if (swept == null) {
                fail("Sweep tidak mendapatkan teks apa pun. Pastikan Accessibility aktif dan layar berisi soal.")
                return@launch
            }
            runAnalysis(manualText = swept.first, manualFrames = swept.second)
        }
    }

    /** Hentikan sweep yang sedang berjalan (dari user atau keputusan AI). */
    fun stopSweep() { _sweeping.value = false }

    /** Buang buffer tangkapan manual tanpa mengirim. */
    fun clearManualCaptures() {
        synchronized(manualLock) {
            manualBufferText = ""
            manualB64List.clear()
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
    @Volatile
    private var analysisJob: kotlinx.coroutines.Job? = null

    fun analyzeOnce(preCapturedBase64: String? = null) {
        if (_isAnalyzing.value) return
        analysisJob = scope.launch { runAnalysis(preCapturedBase64) }
    }

    /** Batalkan analisis yang nyangkut (tombol Stop di HUD). */
    fun cancelAnalysis() {
        analysisJob?.cancel()
        _isAnalyzing.value = false
    }

    private suspend fun runAnalysis(
        preCapturedBase64: String? = null,
        manualText: String? = null,
        manualFrames: List<String>? = null,
        skipAutoSubmit: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        _isAnalyzing.value = true
        _lastError.value = null
        DiagnosticLogger.reset()
        val startedAt = System.currentTimeMillis()

        // AUTO-MINIMIZE HUD: kartu HUD ikut tertangkap di screenshot & menutupi soal.
        // Minimize (BUKAN tutup) -> tunggu frame stabil -> baru tangkap.
        val hudKamiMinimize = !com.example.ui.QuizOverlayManager.isMinimized.value
        if (hudKamiMinimize) {
            com.example.ui.QuizOverlayManager.minimize()
            delay(700) // beri waktu animasi minimize & frame layar stabil
        }

        // RODA STARDESK: deteksi posisi widget SEKALI di awal (setelah HUD di-
        // minimize agar HUD tak ikut terdeteksi) — sebelum scroll-to-top yang
        // sudah membutuhkan roda.
        if (preCapturedBase64 == null && manualText == null) {
            wheelDetected = null
            ensureWheelDetected(remoteActive())
        }

        // SOP langkah 1: balik ke puncak dulu SAMPAI BENAR-BENAR MENTOK (swipe+cek
        // siklus) agar soal mulai dari baris pertamanya — mencegah menjawab dari
        // soal yang setengah tampil.
        if (preCapturedBase64 == null && manualText == null &&
            _scrollToTopOnAnalyze.value && _proMode.value
        ) {
            val c = scrollToTopUntilStuck()
            if (c > 0) capTraceFun2Mark()
        }
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
            var b64 = preCapturedBase64 ?: manualFrames?.firstOrNull() ?: ScreenshotManager.captureBase64(JarvisApp.instance, capTraceFn).first
            if (b64 == null) {
                DiagnosticLogger.update(capture = QuizStepStatus.FAILED, error = "Izin Screen Capture belum diberikan / screenshot gagal")
                fail("Izin Screen Capture belum aktif. Buka app JARVIS dan izinkan 'Screen Capture' (yang dipakai untuk screenshot), lalu Analyze lagi.")
                return@withContext
            }
            DiagnosticLogger.update(capture = QuizStepStatus.OK)
            lastAnalysisBase64 = b64

            // SEMUA frame tangkapan dikirim ke AI (bukan cuma yang terakhir)
            val frames = ArrayList<String>()
            frames.add(b64)
            if (manualFrames != null) {
                for (f in manualFrames) if (f != b64 && frames.size < 6) frames.add(f)
            }

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
            val jari = resolveJari(remoteMode)
            suspend fun geserLayar(atas: Boolean) {
                if (svc == null) return
                val y1 = if (atas) yAtas else yBawah
                val y2 = if (atas) yBawah else yAtas
                doScrollGesture(svc, met, jari, atas = atas, jarakFraksi = kotlin.math.abs(y2 - y1) / met.heightPixels)
            }
            suspend fun scrollCaptureMerge(): Int {
                if (svc == null) return -1
                val prevHash = runCatching { quickHash(frames.last()) }.getOrDefault(0L)
                geserLayar(atas = true)
                delay(if (remoteMode) 1500 else 800) // StarDesk butuh waktu render frame baru
                val b64x = ScreenshotManager.captureBase64(JarvisApp.instance, capTraceFn).first ?: return -1
                // ANTI-LOOP: layar tak berubah setelah scroll = scroll GAGAL (widget
                // roda StarDesk nonaktif / mode jari salah) -> HENTIKAN loop, jangan
                // biarkan AI meminta lanjutan yang tidak pernah datang.
                val newHash = runCatching { quickHash(b64x) }.getOrDefault(0L)
                if (prevHash != 0L && newHash != 0L && hammingDistance(prevHash, newHash) < 6) {
                    DiagnosticLogger.update(
                        captureDetail = "Scroll tidak menggerakkan layar - loop dihentikan. Cek widget Roda StarDesk aktif / ganti mode Gulir di Mode advance"
                    )
                    return -2
                }
                var bx = decodeSampled(b64x, if (remoteMode) 1568 else 1280) ?: return -1
                if (captureIsUniform(bx).first) { bx.recycle(); return -1 }
                bx = prepRemoteOcr(bx)
                val boxesX = OcrEngine.recognizeWithBoxes(bx).getOrNull()
                if (boxesX == null) { bx.recycle(); return -1 }
                if (frames.size < 6) frames.add(b64x) // gambar frame baru ikut ke AI
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
            if (!autoDriven && _proMode.value && preCapturedBase64 == null && manualText == null) {
                var donePilihan = 0
                while (donePilihan < _manualExtraCount.value) {
                    val added = scrollCaptureMerge()
                    if (added < 0) break // scroll gagal/tak bergerak -> jangan ulangi
                    extraScrolls++
                    addedTotal += added
                    donePilihan++
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
                    }
                    if (frames.size > 1) {
                        appendLine("Terlampir " + frames.size + " GAMBAR tangkapan berurutan - perhatikan SEMUANYA (bukan hanya gambar pertama); gambar-gambar itu satu konten kontinyu yang di-scroll.")
                        if (autoDriven && manualText == null) {
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
                        // Prioritas read_screen: bila WebView mengekspos DOM ke accessibility,
                        // teks elemen sering berisi soal UTUH (walau tak tampil di layar)
                        val uiTexts = com.example.service.JarvisAccessibilityService.instance
                            ?.readScreenElements()
                            ?.mapNotNull { el -> (el.text.ifBlank { el.contentDescription }).trim().take(150) }
                            ?.filter { it.length >= 3 }
                            ?.distinct()
                            ?.take(40)
                            .orEmpty()
                        if (uiTexts.isNotEmpty()) {
                            appendLine("Teks elemen UI (accessibility) yang terdeteksi di layar:")
                            appendLine(uiTexts.joinToString("\n").take(1200))
                        }
                    }
                    appendLine("Balas HANYA JSON sesuai instruksi sistem.")
                }
                // WATCHDOG: 1 panggilan AI maks 90 dtk; total analisis maks 240 dtk
                // (dilonggarkan: panggilan ber-gambar butuh waktu lebih dgn koneksi lambat)
                if (System.currentTimeMillis() - startedAt > 240_000L) {
                    failReason = "Analisis melebihi 240 detik - dihentikan (kurangi capture tambahan / periksa koneksi AI)."
                    break
                }
                val aiResult = withTimeoutOrNull(90_000L) {
                    com.example.service.AiChatService.rawCompletion(
                        systemInstruction = AI_SYSTEM_INSTRUCTION,
                        prompt = userPrompt,
                        imageBase64 = frames.firstOrNull()?.let { compressForAi(it) },
                        extraImagesBase64 = frames.drop(1).map { compressForAi(it) }
                    )
                }
                if (aiResult == null) {
                    DiagnosticLogger.update(aiApi = QuizStepStatus.FAILED, error = "AI timeout 90 detik")
                    failReason = "AI tidak merespons dalam 90 detik (koneksi lambat / provider sibuk). Coba lagi."
                    break
                }
                val (aiText, aiError) = aiResult
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
                if (!r.needsMore || !autoDriven || !_proMode.value ||
                    preCapturedBase64 != null || manualText != null || extraScrolls >= maxExtras) break
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
            // FALLBACK OTOMATIS: hasil tak terdeteksi / AI tidak yakin -> SWEEP penuh
            // sekali (scroll terus sampai mentok) lalu analisis ulang dgn semua frame.
            val resCheck = result
            if ((resCheck == null || resCheck.isUncertain) && _autoSweep.value &&
                preCapturedBase64 == null && manualText == null && !_sweeping.value && !skipAutoSubmit
            ) {
                DiagnosticLogger.update(captureDetail = "Kurang yakin -> fallback SWEEP penuh (scroll sampai mentok)")
                val swept = performSweep()
                if (swept != null) {
                    runAnalysis(manualText = swept.first, manualFrames = swept.second, skipAutoSubmit = skipAutoSubmit)
                    return@withContext
                }
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
            // HUD muncul lagi otomatis menampilkan hasil (kecuali Auto Jawab loop
            // sedang berjalan - iterasi berikutnya akan minimize lagi)
            if (hudKamiMinimize && !_autoAnswerLoop.value) {
                com.example.ui.QuizOverlayManager.expand()
            }
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

    /**
     * Kompres tangkapan SEBELUM dikirim ke AI: sisi maks 1280px + JPEG kualitas 80.
     * Payload screenshot penuh sangat berat (jutaan byte base64) -> upload lambat
     * di jaringan HP -> AI terlihat "tidak merespons". Kualitas soal tetap terbaca.
     */
    private fun compressForAi(base64: String, maxDim: Int = 1280): String {
        return runCatching {
            val bmp = decodeSampled(base64, maxDim) ?: return base64
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault(base64)
    }

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
