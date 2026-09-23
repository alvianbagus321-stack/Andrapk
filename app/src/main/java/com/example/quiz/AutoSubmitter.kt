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

    private val SUBMIT_LABELS = listOf(
        "submit", "check", "periksa", "kirim", "jawab", "konfirmasi",
        "confirm", "next", "lanjut", "selanjutnya", "selesai", "ok"
    )

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
        } ?: locateViaScreenshot(
            screenshotBase64,
            SUBMIT_LABELS.map { label ->
                { t: String ->
                    val low = t.lowercase()
                    low == label || (low.startsWith(label) && low.length <= label.length + 8)
                }
            }
        )?.also { submitVia = "OCR screenshot" }

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
            ?: locateViaScreenshot(
                screenshotBase64,
                SUBMIT_LABELS.map { label ->
                    { t: String ->
                        val low = t.lowercase()
                        low == label || (low.startsWith(label) && low.length <= label.length + 8)
                    }
                }
            )?.let { Pair(it, "OCR screenshot") }

        return if (submitPoint != null) {
            service.tapCoordinates(submitPoint.first.first, submitPoint.first.second)
            "Isi jawaban '$text' + tombol [${submitPoint.second}]"
        } else {
            "Jawaban '$text' sudah diisi; tombol submit tidak ketemu"
        }
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
        val exact = pool.filter { el ->
            val t = (el.text.ifBlank { el.contentDescription }).trim()
            t.equals(letter, ignoreCase = true) ||
                t.equals(answerText.trim(), ignoreCase = true) ||
                optionStart.containsMatchIn(t)
        }
        val candidates = (if (exact.isNotEmpty()) exact else pool)
        return candidates
            .sortedWith(
                compareByDescending<UiElementInfo> { it.isClickable }
                    .thenBy { it.bounds.width * it.bounds.height }
            )
            .firstOrNull()
            ?.let { Pair(it.bounds.centerX.toFloat(), it.bounds.centerY.toFloat()) }
    }

    private fun pickSubmitViaElements(service: JarvisAccessibilityService): Pair<Float, Float>? {
        for (label in SUBMIT_LABELS) {
            val candidates = service.findElementsByText(label).filter { el ->
                val t = (el.text.ifBlank { el.contentDescription }).trim().lowercase()
                (t == label || (t.startsWith(label) && t.length <= label.length + 8))
            }
            val best = candidates.sortedWith(
                compareByDescending<UiElementInfo> { it.isClickable }
                    .thenBy { it.bounds.width * it.bounds.height }
            ).firstOrNull()
            if (best != null) return Pair(best.bounds.centerX.toFloat(), best.bounds.centerY.toFloat())
        }
        return null
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
