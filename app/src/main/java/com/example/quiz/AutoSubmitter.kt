package com.example.quiz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.JarvisApp
import com.example.model.UiElementInfo
import com.example.service.JarvisAccessibilityService
import com.example.service.ScreenshotManager

/**
 * Auto Submit (opsional, default OFF):
 * 1) Ketuk opsi yang dipilih AI, lalu tombol kirim.
 * Strategi berlapis (fallback):
 *   a. Elemen accessibility (paling akurat bila UI-nya standar).
 *   b. BILA TIDAK KETEMU (UI Canvas/game/WebView tanpa elemen) → pakai SCREENSHOT
 *      HASIL ANALISIS AWAL: cari teks opsi/tombol lewat bounding box OCR, konversi
 *      ke koordinat layar, lalu ketuk koordinatnya (gesture tak butuh elemen).
 * Tidak pernah menebak buta: bila dua-duanya gagal, TIDAK ADA yang diketuk.
 */
object AutoSubmitter {

    // Label tombol submit — mencakup ID/EN umum, termasuk "berikutnya" (Ruangguru dll)
    private val SUBMIT_STRONG = listOf(
        "submit", "check", "cek", "periksa", "kirim", "jawab", "konfirmasi",
        "confirm", "next", "lanjut", "lanjutkan", "selanjutnya", "berikutnya",
        "selesai", "verifikasi", "verify", "answer", "continue", "done"
    )

    // Label pendek ambigu: hanya dicocokkan PERSIS (hindari salah ketuk "login", "kok", dll)
    private val SUBMIT_EXACT_ONLY = listOf("ok", "go")

    /**
     * Pencocokan bertingkat (angka kecil = lebih diprioritaskan):
     *   tier 0: persis / diawali label (guard panjang)
     *   tier 1: MENGANDUNG label (guard panjang) — menangkap "Soal Berikutnya 9/18",
     *           "Kirim Jawaban", tombol ber-teks panjang.
     * Label pendek (ok/go) hanya cocok persis.
     */
    private fun submitTier(text: String, seed: String): Int {
        val low = text.trim().lowercase()
        if (seed in SUBMIT_EXACT_ONLY) return if (low == seed) 0 else 9
        return when {
            low == seed || (low.startsWith(seed) && low.length <= seed.length + 10) -> 0
            low.contains(seed) && low.length <= seed.length + 20 -> 1
            else -> 9
        }
    }

    /** @return ringkasan hasil aksi (untuk diagnostic overlay). */
    suspend fun submit(result: QuizAnswerResult, screenshotBase64: String? = null): String {
        val service = JarvisAccessibilityService.instance
            ?: return "Accessibility Service belum aktif — tidak ada yang diketuk"

        val letter = result.answer.trim().uppercase().take(1)
        if (letter.isEmpty() || letter == "?") return "Jawaban tidak jelas — tidak ada yang diketuk"

        // ---------- 1) Ketuk opsi yang dipilih ----------
        var optionVia = ""
        val optionPoint = pickOptionViaElements(service, letter, result.answerText)?.let {
            optionVia = "elemen"
            it
        } ?: locateViaScreenshot(
            screenshotBase64,
            listOf(
                { t -> t.equals(letter, ignoreCase = true) },
                { t -> Regex("^$letter[).\\]:\\-\\s]", RegexOption.IGNORE_CASE).containsMatchIn(t) },
                { t -> result.answerText.trim().length >= 3 && t.contains(result.answerText.trim(), ignoreCase = true) }
            )
        )?.also { optionVia = "OCR screenshot" }

        if (optionPoint == null) {
            return "Opsi '$letter' tidak ketemu (elemen & OCR screenshot) — tidak ada yang diketuk"
        }
        service.tapCoordinates(optionPoint.first, optionPoint.second)
        kotlinx.coroutines.delay(600)

        // ---------- 2) Cari & ketuk tombol submit ----------
        var submitVia = ""
        val submitPoint = pickSubmitViaElements(service)?.let {
            submitVia = "elemen"
            it
        } ?: locateViaScreenshot(screenshotBase64, submitOcrMatchers())?.also { submitVia = "OCR screenshot" }

        return if (submitPoint != null) {
            service.tapCoordinates(submitPoint.first, submitPoint.second)
            "Ketuk opsi '$letter' [$optionVia] + tombol [$submitVia]"
        } else {
            "Ketuk opsi '$letter' [$optionVia]; tombol submit tidak ketemu"
        }
    }

    /**
     * Soal ISIAN (bukan pilihan ganda): ketik jawaban ke kolom isian lalu ketuk tombol submit.
     * Kolom dicari lewat elemen accessibility (node fokus / editable pertama di semua window,
     * SET_TEXT dengan fallback PASTE). Kolom canvas murni tanpa node text -> TIDAK diisi
     * (tidak menebak buta) dan dilaporkan di diagnostic.
     */
    suspend fun fillAnswer(result: QuizAnswerResult, screenshotBase64: String? = null): String {
        val service = JarvisAccessibilityService.instance
            ?: return "Accessibility Service belum aktif - tidak ada yang diisi"
        val text = result.answerText.trim()
        if (text.isEmpty()) return "Jawaban kosong - tidak ada yang diisi"

        val typed = service.typeText(null, text)
        if (typed.status != "ok") {
            return "Kolom isian tidak ketemu/ditolak - tidak ada yang diisi"
        }
        kotlinx.coroutines.delay(600)

        val submitPoint = pickSubmitViaElements(service)?.let { Pair(it, "elemen") }
            ?: locateViaScreenshot(screenshotBase64, submitOcrMatchers())?.let { Pair(it, "OCR screenshot") }

        return if (submitPoint != null) {
            service.tapCoordinates(submitPoint.first.first, submitPoint.first.second)
            "Isi jawaban '$text' + tombol [${submitPoint.second}]"
        } else {
            "Jawaban '$text' sudah diisi; tombol submit tidak ketemu"
        }
    }

    /** Matcher berurutan utk OCR: ok/go persis -> semua label tier 0 -> semua label tier 1. */
    private fun submitOcrMatchers(): List<(String) -> Boolean> {
        val ms = mutableListOf<(String) -> Boolean>()
        for (l in SUBMIT_EXACT_ONLY) {
            val seed = l
            ms.add { t -> submitTier(t, seed) == 0 }
        }
        for (l in SUBMIT_STRONG) {
            val seed = l
            ms.add { t -> submitTier(t, seed) == 0 }
        }
        for (l in SUBMIT_STRONG) {
            val seed = l
            ms.add { t -> submitTier(t, seed) == 1 }
        }
        return ms
    }

    // ============================================================
    // Strategi A — elemen accessibility
    // ============================================================

    private fun pickOptionViaElements(
        service: JarvisAccessibilityService,
        letter: String,
        answerText: String
    ): Pair<Float, Float>? {
        val pool = buildList {
            if (answerText.length >= 3) addAll(service.findElementsByText(answerText))
            addAll(service.findElementsByText(letter))
        }.distinctBy { "${it.viewId}|${it.text}|${it.bounds.centerX},${it.bounds.centerY}" }

        val optionStart = Regex("^$letter[).\\]:\\-\\s]", RegexOption.IGNORE_CASE)
        // HANYA kecocokan KUAT yang boleh menang dari jalur elemen (findElementsByText
        // adalah query "mengandung" longgar — bahkan viewId ikut). Tanpa ini, elemen
        // yang sekadar "mirip" menang & jalur screenshot OCR tak pernah dicoba.
        val exact = pool.filter { el ->
            val t = (el.text.ifBlank { el.contentDescription }).trim()
            t.equals(letter, ignoreCase = true) ||
                t.equals(answerText.trim(), ignoreCase = true) ||
                optionStart.containsMatchIn(t)
        }
        return exact
            .sortedWith(
                compareByDescending<UiElementInfo> { it.isClickable }
                    .thenBy { it.bounds.width * it.bounds.height }
            )
            .firstOrNull()
            ?.let { Pair(it.bounds.centerX.toFloat(), it.bounds.centerY.toFloat()) }
        // null -> pemanggil otomatis lanjut ke jalur OCR screenshot
    }

    private fun pickSubmitViaElements(service: JarvisAccessibilityService): Pair<Float, Float>? {
        var bestTier = 9
        var bestClickable = false
        var bestArea = Int.MAX_VALUE
        var bestPoint: Pair<Float, Float>? = null
        val screenArea = android.content.res.Resources.getSystem().displayMetrics.let { it.widthPixels * it.heightPixels }
        for (seed in SUBMIT_STRONG + SUBMIT_EXACT_ONLY) {
            for (el in service.findElementsByText(seed)) {
                val t = (el.text.ifBlank { el.contentDescription }).trim()
                val tier = submitTier(t, seed)
                if (tier >= 9) continue
                val clickable = el.isClickable
                val area = el.bounds.width * el.bounds.height
                // Kecocokan "mengandung" pada elemen raksasa (paragraf/banner) hampir
                // pasti salah sasaran -> tolak, biarkan jalur OCR screenshot memutuskan.
                if (tier == 1 && area > screenArea * 0.15) continue
                val better = tier < bestTier ||
                    (tier == bestTier && ((clickable && !bestClickable) || (clickable == bestClickable && area < bestArea)))
                if (better) {
                    bestTier = tier; bestClickable = clickable; bestArea = area
                    bestPoint = Pair(el.bounds.centerX.toFloat(), el.bounds.centerY.toFloat())
                }
            }
        }
        return bestPoint
    }

    // ============================================================
    // Strategi B — bounding box OCR pada screenshot hasil analisis
    // ============================================================

    /**
     * Cari teks yang cocok dengan salah satu matcher (berurutan = prioritas)
     * pada screenshot, lalu kembalikan TITIK TENGAH-nya dalam koordinat layar.
     */
    private suspend fun locateViaScreenshot(
        screenshotBase64: String?,
        matchers: List<(String) -> Boolean>
    ): Pair<Float, Float>? {
        if (screenshotBase64.isNullOrBlank()) return null
        val bitmap = decodeSampled(screenshotBase64, 1280) ?: return null

        // Skala bitmap screenshot → resolusi layar (crop/resize capture tidak dipakai di sini)
        val metrics = ScreenshotManager.getScreenMetrics(JarvisApp.instance)
        val scaleX = metrics.widthPixels.toFloat() / bitmap.width.toFloat()
        val scaleY = metrics.heightPixels.toFloat() / bitmap.height.toFloat()

        val boxes = OcrEngine.recognizeWithBoxes(bitmap).getOrElse {
            bitmap.recycle()
            return null
        }
        bitmap.recycle()

        for (matcher in matchers) {
            // pilih box TERKECIL yang cocok (paling spesifik, hindari blok paragraf)
            val best = boxes
                .filter { b -> matcher(b.text.trim()) }
                .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
            if (best != null) {
                val cx = (best.left + best.right) / 2f * scaleX
                val cy = (best.top + best.bottom) / 2f * scaleY
                return Pair(cx, cy)
            }
        }
        return null
    }

    private fun decodeSampled(base64: String, maxDim: Int): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }
}
