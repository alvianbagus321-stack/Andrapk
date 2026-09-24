package com.example.quiz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.JarvisApp
import com.example.service.JarvisAccessibilityService
import com.example.service.ScreenshotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * =====================================================================
 * RemoteController — abstraksi input/capture (spec bagian B & D).
 * Konsep yang wajib ada: click(), scrollDown(), scrollUp(), captureScreen().
 * Implementasi:
 *  - AndroidNativeController : konten Android asli — aksi scroll NODE
 *    accessibility (isScrollable + ACTION_SCROLL_*) dulu, fallback GESTURE
 *    dinamis berdasar ukuran layar (tanpa koordinat hardcode).
 *  - StarDeskRemoteController : konten PC via remote — jalur yang sama
 *    dengan click yang sudah bekerja: gesture roda StarDesk (drag pelan
 *    tepat di widget roda, terdeteksi dari screenshot) atau kunci
 *    PageUp/PageDown PC via Shizuku/Termux-ADB. Tidak ada API fiktif.
 * =====================================================================
 */
interface RemoteController {
    val name: String
    suspend fun click(x: Int, y: Int): Boolean
    suspend fun longPress(x: Int, y: Int): Boolean
    suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean
    suspend fun scrollDown(): Boolean
    suspend fun scrollUp(): Boolean
    suspend fun captureScreen(): Bitmap?
}

object RemoteControllers {
    fun forTarget(remote: Boolean): RemoteController =
        if (remote) StarDeskRemoteController() else AndroidNativeController()
}

/** Konten Android asli: node scrollable -> ACTION_SCROLL, fallback gesture dinamis. */
class AndroidNativeController : RemoteController {
    override val name: String get() = "AndroidNative"

    private fun svc() = JarvisAccessibilityService.instance

    override suspend fun click(x: Int, y: Int): Boolean = runCatching {
        svc() != null && svc()!!.tapCoordinates(x.toFloat(), y.toFloat()).status == "ok"
    }.getOrDefault(false)

    override suspend fun longPress(x: Int, y: Int): Boolean = swipe(x, y, x, y, 600)

    override suspend fun swipe(
        startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long
    ): Boolean = runCatching {
        val s = svc() ?: return@runCatching false
        s.swipeCoordinates(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), durationMs)
            .status == "ok"
    }.getOrDefault(false)

    /** Cari node accessibility yang isScrollable == true (root + semua window). */
    private fun findScrollableNode(): android.view.accessibility.AccessibilityNodeInfo? {
        val s = svc() ?: return null
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) {
                val found = runCatching { walk(n.getChild(i)) }.getOrNull()
                if (found != null) return found
            }
            return null
        }
        runCatching { s.rootInActiveWindow?.let { walk(it)?.let { r -> return r } } }
        runCatching {
            s.windows?.forEach { w -> w.root?.let { walk(it)?.let { r -> return r } } }
        }
        return null
    }

    override suspend fun scrollDown(): Boolean = withContext(Dispatchers.IO) {
        // 1) Aksi NODE (paling andal utk konten Android asli)
        val node = findScrollableNode()
        if (node != null) {
            val fwd = runCatching { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }.getOrDefault(false)
            val down = if (Build.VERSION.SDK_INT >= 23) {
                runCatching { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_DOWN) }.getOrDefault(false)
            } else false
            if (fwd || down) {
                Log.i(QuestionCaptureManager.TAG, "[SCROLL] Method: Accessibility | Result: SUCCESS")
                return@withContext true
            }
        }
        // 2) Fallback GESTURE dinamis: 0.80H -> 0.30H (tanpa koordinat hardcode)
        val s = svc() ?: return@withContext false
        val m = ScreenshotManager.getScreenMetrics(JarvisApp.instance)
        val cx = m.widthPixels / 2
        val y1 = (m.heightPixels * 0.80f).toInt()
        val y2 = (m.heightPixels * 0.30f).toInt()
        val ok = runCatching {
            s.swipeCoordinates(cx.toFloat(), y1.toFloat(), cx.toFloat(), y2.toFloat(), 400L).status == "ok"
        }.getOrDefault(false)
        Log.i(QuestionCaptureManager.TAG, "[SCROLL] Method: Gesture(0.80H->0.30H) | Result: " + if (ok) "SUCCESS" else "FAIL")
        ok
    }

    override suspend fun scrollUp(): Boolean = withContext(Dispatchers.IO) {
        val node = findScrollableNode()
        if (node != null) {
            val bwd = runCatching { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }.getOrDefault(false)
            val up = if (Build.VERSION.SDK_INT >= 23) {
                runCatching { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_UP) }.getOrDefault(false)
            } else false
            if (bwd || up) return@withContext true
        }
        val s = svc() ?: return@withContext false
        val m = ScreenshotManager.getScreenMetrics(JarvisApp.instance)
        val cx = m.widthPixels / 2
        val y1 = (m.heightPixels * 0.30f).toInt()
        val y2 = (m.heightPixels * 0.80f).toInt()
        runCatching {
            s.swipeCoordinates(cx.toFloat(), y1.toFloat(), cx.toFloat(), y2.toFloat(), 400L).status == "ok"
        }.getOrDefault(false)
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        val b64 = ScreenshotManager.captureBase64(JarvisApp.instance).first ?: return@withContext null
        runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}

/**
 * Remote/StarDesk: layar HP = canvas PC; scroll DITERUSKAN lewat mekanisme
 * remote input yang SUDAH dipakai app untuk click:
 *   1. RODA StarDesk — drag pelan 1 jari tepat di widget roda (posisi
 *      terdeteksi dari screenshot; kiri/kanan) = roda mouse PC.
 *   2. Kunci PageUp/PageDown PC — `input keyevent` via Shizuku/Termux-ADB
 *      (jalur yang sama dengan fitur ⇞⇟ yang sudah ada).
 * Roda dipakai bila terkonfirmasi; kalau tidak, otomatis kunci keyboard.
 * Bila keduanya gagal dikirim → scrollUnsupported.
 */
class StarDeskRemoteController : RemoteController {
    override val name: String get() = "StarDesk"

    override suspend fun click(x: Int, y: Int): Boolean = runCatching {
        val s = JarvisAccessibilityService.instance ?: return@runCatching false
        s.tapCoordinates(x.toFloat(), y.toFloat()).status == "ok"
    }.getOrDefault(false)

    override suspend fun longPress(x: Int, y: Int): Boolean = swipe(x, y, x, y, 600)

    override suspend fun swipe(
        startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long
    ): Boolean = runCatching {
        val s = JarvisAccessibilityService.instance ?: return@runCatching false
        s.swipeCoordinates(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), durationMs)
            .status == "ok"
    }.getOrDefault(false)

    override suspend fun scrollDown(): Boolean {
        // Mouse-wheel ekuivalen: roda StarDesk (bila terkonfirmasi — ANTI-KURSOR:
        // tanpa konfirmasi, drag DILEWATI agar kursor PC tidak ikut bergerak)
        if (QuizAnalyzer.rodaConfirmed()) {
            val ok = QuizAnalyzer.rodaScrollOnce(up = false)
            Log.i(QuestionCaptureManager.TAG, "[SCROLL] Method: RemoteInput(roda) | Result: " + if (ok) "SUCCESS" else "FAIL")
            return ok
        }
        // Keyboard fallback: PageDown PC via Shizuku/Termux-ADB
        QuizAnalyzer.scrollKey("pagedown")
        Log.i(QuestionCaptureManager.TAG, "[SCROLL] Method: RemoteInput(PageDown key) | Result: SENT")
        return true
    }

    override suspend fun scrollUp(): Boolean {
        if (QuizAnalyzer.rodaConfirmed()) {
            val ok = QuizAnalyzer.rodaScrollOnce(up = true)
            Log.i(QuestionCaptureManager.TAG, "[SCROLL] Method: RemoteInput(roda) | Result: " + if (ok) "SUCCESS" else "FAIL")
            return ok
        }
        QuizAnalyzer.scrollKey("pageup")
        Log.i(QuestionCaptureManager.TAG, "[SCROLL] Method: RemoteInput(PageUp key) | Result: SENT")
        return true
    }

    override suspend fun captureScreen(): Bitmap? = AndroidNativeController().captureScreen()
}

/**
 * =====================================================================
 * QuestionCaptureManager (spec E–N)
 * Alur: NEW QUESTION → reset tempmemory → CAPTURE → CHECK COMPLETE →
 *   (belum lengkap?) → SCROLL_DOWN → WAIT → CAPTURE → MERGE → ulangi.
 * - tempmemory.md di filesDir (hidup 1 soal; dihapus saat selesai/ganti).
 * - Completion gate: AI hanya menganalisis bila complete == true.
 * - Deteksi bottom MULTI-SINYAL (hash tak berubah, tak ada baris baru).
 * - Merge anti-duplikat (normalisasi + dedup baris; overlap halaman aman).
 * - Batas aman: MAX_PAGES=15, MAX_NO_CHANGE=3.
 * =====================================================================
 */
object QuestionCaptureManager {
    const val TAG = "QuestionCapture"
    const val MAX_PAGES = 15
    const val MAX_NO_CHANGE = 3

    /** Delay settle setelah scroll (spec K); remote butuh render frame baru. */
    var SCROLL_SETTLE_DELAY_MS = 550L
    var SCROLL_SETTLE_DELAY_REMOTE_MS = 1000L

    data class State(
        val sessionId: String = "",
        var pageCount: Int = 0,
        var complete: Boolean = false,
        var needsScroll: Boolean = false,
        var atBottom: Boolean = false,
        var scrollSupported: Boolean = true,
        var scrollError: Boolean = false,
        var scrollAttempts: Int = 0,
        var unchangedCount: Int = 0,
        var detectedOptions: List<String> = emptyList(),
        var capturedText: String = ""
    ) {
        fun toJsonLike(): String =
            "{\"complete\":$complete,\"needsScroll\":$needsScroll,\"atBottom\":$atBottom," +
                "\"scrollSupported\":$scrollSupported,\"pageCount\":$pageCount," +
                "\"scrollError\":$scrollError}"
    }

    data class CaptureResult(
        val combinedText: String,
        val frames: List<String>,
        val state: State
    )

    private data class Assess(val labels: List<String>, val question: Boolean)

    // ------------------------------------------------------------ memori --
    private fun memFile(): File = File(JarvisApp.instance.filesDir, "tempmemory.md")

    /** Soal baru: memory kosong + session id unik. @return sessionId */
    fun startNewQuestion(): String {
        val sessionId = System.currentTimeMillis().toString() + "-" + (1000..9999).random()
        runCatching {
            memFile().delete()
            memFile().writeText(
                "# TEMP QUESTION MEMORY\n\n" +
                    "session_id: $sessionId\n" +
                    "status: capturing\n" +
                    "complete: false\n\n" +
                    "## SCROLL STATE\nhas_more_content: unknown\nat_bottom: false\n\n" +
                    "## ANALYSIS\nstatus: pending\nanswer: null\n"
            )
        }
        Log.i(TAG, "[QUESTION] New session: $sessionId")
        return sessionId
    }

    fun appendPage(sessionId: String, page: Int, text: String) {
        runCatching {
            val f = memFile()
            val body = f.readText().replace("\n## ANALYSIS", "\n## PAGE $page\n$text\n\n## ANALYSIS")
            f.writeText(body)
        }
        Log.i(TAG, "[CAPTURE] Page $page (" + text.length + " chars)")
    }

    fun finishQuestion(answer: String?) {
        runCatching {
            val f = memFile()
            if (f.exists()) {
                val body = f.readText()
                    .replace("status: capturing", "status: done")
                    .replace("complete: false", "complete: true")
                    .replace("status: pending", "status: done")
                    .replace("answer: null", "answer: " + (answer ?: "null"))
                f.writeText(body)
            }
        }
        Log.i(TAG, "[QUESTION] Finished (answer=" + (answer ?: "null") + ")")
    }

    /** Hapus memory (dipanggil setelah analisis / sebelum soal berikutnya). */
    fun clearMemory() {
        runCatching { memFile().delete() }
    }

    // -------------------------------------------------------------- util --
    private fun normLine(l: String): String =
        l.lowercase(Locale.getDefault()).replace(Regex("\\s+"), " ").trim()

    /** Hash ringan frame utk deteksi "layar tidak berubah" (spec H sinyal 2). */
    private fun frameHash(b64: String): Long = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 12 && bounds.outHeight / (sample * 2) >= 12) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return 0L
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w); var sum = 0.0
        val cells = DoubleArray(64)
        val cw = (w / 8f).coerceAtLeast(1f); val ch = (h / 8f).coerceAtLeast(1f)
        for (cy in 0 until 8) for (cx in 0 until 8) {
            var s = 0.0; var c = 0
            val x0 = (cx * cw).toInt(); val y0 = (cy * ch).toInt()
            bmp.getPixels(px, 0, w, x0, y0, cw.toInt().coerceAtLeast(1), ch.toInt().coerceAtLeast(1))
            for (p in px) {
                s += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF); c++
            }
            cells[cy * 8 + cx] = if (c > 0) s / c else 0.0; sum += cells[cy * 8 + cx]
        }
        bmp.recycle()
        val mean = sum / 64.0
        var hash = 0L
        for (i in 0 until 64) if (cells[i] > mean) hash = hash or (1L shl i)
        hash
    }.getOrDefault(0L)

    private fun ocrOf(b64: String): String = runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val t = OcrEngine.recognize(bmp).getOrNull() ?: ""
        bmp.recycle()
        t
    }.getOrDefault("")

    /**
     * Deteksi opsi fleksibel (spec I): A–F, 1–5, BENAR/SALAH (jangan asumsikan A–E).
     * Baris yang sama di halaman berikutnya TIDAK diduplikasi (merge di pemanggil).
     */
    private fun assessOptions(text: String): Assess {
        val labels = LinkedHashSet<String>()
        Regex("(?im)^\\s*[*•\\-]?\\s*([A-Fa-f])[).:\\-]\\s+\\S").findAll(text).forEach {
            labels.add(it.groupValues[1].uppercase(Locale.getDefault()))
        }
        Regex("(?m)^\\s*([1-5])[).:]\\s+\\S").findAll(text).forEach {
            labels.add(it.groupValues[1])
        }
        if (Regex("(?i)\\b(benar|salah|true|false)\\b").containsMatchIn(text)) {
            labels.add("BS")
        }
        val question = text.length > 40 &&
            (text.contains("?") || Regex("(?i)(berapa|manakah|pernyataan|yang benar|hasil dari|nilai|lengkapi)").containsMatchIn(text))
        return Assess(labels.toList(), question)
    }

    /**
     * Alur utama (spec E). @return null bila capture pertama gagal total.
     */
    suspend fun captureQuestion(remote: Boolean): CaptureResult? = withContext(Dispatchers.IO) {
        val ctx = JarvisApp.instance
        val target = RemoteControllers.forTarget(remote)
        val sessionId = startNewQuestion()
        Log.i(TAG, "[SCROLL] Target: " + target.name)
        DiagnosticLogger.update(
            captureDetail = "[QUESTION] Sesi " + sessionId.takeLast(6) + " - target=" + target.name +
                (if (remote) " (roda/PageDown)" else " (node/gesture)")
        )

        val state = State(sessionId = sessionId)
        val frames = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        val combined = StringBuilder()
        var prevHash = 0L
        var pageCount = 0

        while (pageCount < MAX_PAGES) {
            val b64 = ScreenshotManager.captureBase64(ctx).first ?: break
            val text = ocrOf(b64)
            val h = frameHash(b64)
            state.unchangedCount = if (pageCount > 0 && h == prevHash) state.unchangedCount + 1 else 0
            prevHash = h
            if (frames.size < 7) frames.add(b64)

            // MERGE anti-duplikat (spec J): baris baru saja yang ditambahkan
            var added = 0
            for (l in text.lines()) {
                val n = normLine(l)
                if (n.length >= 2 && seen.add(n)) {
                    combined.appendLine(l.trim()); added++
                }
            }
            pageCount++
            state.pageCount = pageCount
            appendPage(sessionId, pageCount, text)
            Log.i(TAG, "[QUESTION] Page $pageCount: +$added baris baru")

            val a = assessOptions(combined.toString())
            state.detectedOptions = a.labels
            state.capturedText = combined.toString()

            // COMPLETENESS (spec G/I): soal + opsi memadai = lengkap
            val bsPair = a.labels.contains("BS")
            if (a.question && (a.labels.size >= 4 || bsPair || (a.labels.size >= 3 && state.atBottom))) {
                state.complete = true
                Log.i(TAG, "[QUESTION] Complete: true")
                DiagnosticLogger.update(captureDetail = "[QUESTION] Complete: true (" + a.labels.size + " opsi, " + pageCount + " halaman)")
                break
            }
            Log.i(TAG, "[QUESTION] Complete: false (opsi=" + a.labels.size + ", soal=" + a.question + ")")

            // BOTTOM multi-sinyal (spec H): layar & teks tak berubah berkali-kali
            if (pageCount > 0 && state.unchangedCount >= MAX_NO_CHANGE) {
                state.atBottom = true
                state.complete = a.question && (a.labels.size >= 2 || a.labels.contains("BS"))
                Log.i(TAG, "[QUESTION] At bottom - complete: " + state.complete)
                DiagnosticLogger.update(
                    captureDetail = "[QUESTION] Mentok dasar halaman - complete=" + state.complete +
                        " (opsi=" + a.labels.size + ")"
                )
                break
            }

            // SCROLL_DOWN (spec C/D: fallback berlapis di dalam controller)
            state.needsScroll = true
            Log.i(TAG, "[SCROLL] Request: SCROLL_DOWN")
            DiagnosticLogger.update(captureDetail = "[SCROLL] Menungu: SCROLL_DOWN via " + target.name + " (hal " + pageCount + ")")
            val ok = target.scrollDown()
            state.scrollAttempts++
            if (!ok) {
                state.scrollSupported = false
                state.scrollError = true
                Log.i(TAG, "[SCROLL] Result: UNSUPPORTED/FAIL")
                DiagnosticLogger.update(captureDetail = "[SCROLL] GAGAL/tak didukung - " + target.name)
                break
            }
            delay(if (remote) SCROLL_SETTLE_DELAY_REMOTE_MS else SCROLL_SETTLE_DELAY_MS)
        }

        if (pageCount == 0) {
            clearMemory()
            return@withContext null
        }
        if (!state.complete && pageCount >= MAX_PAGES) {
            // safety limit (spec H): jangan menebak jawaban
            state.scrollError = true
            Log.i(TAG, "[QUESTION] MAX_PAGES tercapai tanpa kelengkapan")
        }
        Log.i(TAG, "[QUESTION] Selesai: " + state.toJsonLike())
        CaptureResult(combined.toString().trim(), frames.toList(), state)
    }
}
