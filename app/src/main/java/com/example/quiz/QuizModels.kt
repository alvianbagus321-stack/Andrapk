package com.example.quiz

/** Status satu langkah di pipeline analisis kuis. */
enum class QuizStepStatus { OK, FAILED, SKIPPED }

/** Fase kerja analyzer — dipakai overlay untuk status dot & loading. */
enum class QuizPhase { IDLE, CAPTURING, OCR, AI, DONE, ERROR }

/** Hasil analisis AI yang sudah diparse — tampil di overlay. */
data class QuizAnswerResult(
    val question: String,
    val options: Map<String, String>,
    val answer: String,
    val answerText: String,
    val explanation: String,
    val confidence: Float
) {
    val isUncertain: Boolean get() = answer.isBlank() || answer == "?" || confidence < 0.3f
}

/** Soal hasil parse OCR (sebelum dikirim ke AI). */
data class ParsedQuestion(
    val questionText: String,
    val options: Map<String, String>,
    val rawOcrText: String
)

/** Baris-baris diagnostic untuk panel ▼ Diagnostic di overlay. */
data class QuizDiagnostic(
    val capture: QuizStepStatus = QuizStepStatus.SKIPPED,
    val ocr: QuizStepStatus = QuizStepStatus.SKIPPED,
    val aiApi: QuizStepStatus = QuizStepStatus.SKIPPED,
    val questionDetected: Boolean = false,
    val optionCount: Int = 0,
    val response: QuizStepStatus = QuizStepStatus.SKIPPED,
    val latencyMs: Long = 0,
    val error: String? = null,
    val autoSubmit: QuizStepStatus = QuizStepStatus.SKIPPED,
    val autoSubmitDetail: String? = null
) {
    fun toLines(): List<String> = listOf(
        "Screen Capture: ${label(capture)}",
        "OCR: ${label(ocr)}",
        "AI API: ${label(aiApi)}",
        "Question: ${if (questionDetected) "Detected" else "Not detected"}",
        "Options: $optionCount",
        "Response: ${label(response)}",
        "Latency: $latencyMs ms",
        "Auto Submit: " + (autoSubmitDetail ?: label(autoSubmit))
    ) + (error?.let { listOf("Error: $it") } ?: emptyList())

    private fun label(s: QuizStepStatus) = when (s) {
        QuizStepStatus.OK -> "OK"
        QuizStepStatus.FAILED -> "Failed"
        QuizStepStatus.SKIPPED -> "-"
    }
}
