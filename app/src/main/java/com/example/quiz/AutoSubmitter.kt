package com.example.quiz

import com.example.model.UiElementInfo
import com.example.service.JarvisAccessibilityService

/**
 * Auto Submit (opsional, default OFF):
 * setelah AI yakin dengan jawabannya, ketuk opsi yang dipilih di layar,
 * lalu cari & ketuk tombol kirim (Submit/Check/Kirim/dll).
 * Hanya mengetuk bila node yang cocok BENAR-BENAR ditemukan — tidak pernah menebak koordinat.
 */
object AutoSubmitter {

    private val SUBMIT_LABELS = listOf(
        "submit", "check", "periksa", "kirim", "jawab", "konfirmasi",
        "confirm", "next", "lanjut", "selanjutnya", "selesai", "ok"
    )

    /** @return ringkasan hasil aksi (untuk diagnostic overlay). */
    suspend fun submit(result: QuizAnswerResult): String {
        val service = JarvisAccessibilityService.instance
            ?: return "Accessibility Service belum aktif — tidak ada yang diketuk"

        val letter = result.answer.trim().uppercase().take(1)
        if (letter.isEmpty() || letter == "?") return "Jawaban tidak jelas — tidak ada yang diketuk"

        // 1) Ketuk opsi yang dipilih
        val optionNode = pickOptionNode(service, letter, result.answerText)
            ?: return "Opsi '$letter' tidak ditemukan di layar — tidak ada yang diketuk"
        service.tapCoordinates(
            optionNode.bounds.centerX.toFloat(),
            optionNode.bounds.centerY.toFloat()
        )
        kotlinx.coroutines.delay(600) // beri waktu UI bereaksi

        // 2) Cari & ketuk tombol submit
        val submitNode = pickSubmitNode(service)
        return if (submitNode != null) {
            service.tapCoordinates(
                submitNode.bounds.centerX.toFloat(),
                submitNode.bounds.centerY.toFloat()
            )
            "Ketuk opsi '$letter' + tombol '${labelOf(submitNode)}'"
        } else {
            "Ketuk opsi '$letter' (tombol submit tidak ditemukan di layar)"
        }
    }

    private fun pickOptionNode(
        service: JarvisAccessibilityService,
        letter: String,
        answerText: String
    ): UiElementInfo? {
        // Kandidat: cari berdasarkan teks jawaban (jika cukup panjang), lalu huruf opsi
        val pool = buildList {
            if (answerText.length >= 3) addAll(service.findElementsByText(answerText))
            addAll(service.findElementsByText(letter))
        }.distinctBy { "${it.viewId}|${it.text}|${it.bounds.centerX},${it.bounds.centerY}" }

        // Cocokan presisi: teks persis huruf, persis answerText, atau diawali "C." / "C)" / "C -"
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
                    .thenBy { it.bounds.width * it.bounds.height } // elemen terkecil = paling spesifik
            )
            .firstOrNull()
    }

    private fun pickSubmitNode(service: JarvisAccessibilityService): UiElementInfo? {
        for (label in SUBMIT_LABELS) {
            val candidates = service.findElementsByText(label).filter { el ->
                val t = (el.text.ifBlank { el.contentDescription }).trim().lowercase()
                // Harus label tombol pendek — hindari mengetuk paragraf yang mengandung kata tsb
                (t == label || (t.startsWith(label) && t.length <= label.length + 8))
            }
            val best = candidates.sortedWith(
                compareByDescending<UiElementInfo> { it.isClickable }
                    .thenBy { it.bounds.width * it.bounds.height }
            ).firstOrNull()
            if (best != null) return best
        }
        return null
    }

    private fun labelOf(el: UiElementInfo): String =
        (el.text.ifBlank { el.contentDescription }).trim().take(20)
}
