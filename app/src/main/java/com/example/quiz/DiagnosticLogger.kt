package com.example.quiz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mencatat status tiap langkah pipeline (capture/OCR/AI/response) + error terakhir.
 * Ditampilkan di panel ▼ Diagnostic overlay.
 */
object DiagnosticLogger {
    private val _diagnostic = MutableStateFlow(QuizDiagnostic())
    val diagnostic: StateFlow<QuizDiagnostic> = _diagnostic.asStateFlow()

    fun reset() {
        _diagnostic.value = QuizDiagnostic()
    }

    fun update(
        capture: QuizStepStatus? = null,
        ocr: QuizStepStatus? = null,
        aiApi: QuizStepStatus? = null,
        questionDetected: Boolean? = null,
        optionCount: Int? = null,
        response: QuizStepStatus? = null,
        latencyMs: Long? = null,
        error: String? = null,
        clearError: Boolean = false,
        autoSubmit: QuizStepStatus? = null,
        autoSubmitDetail: String? = null,
        clearAutoSubmitDetail: Boolean = false
    ) {
        val d = _diagnostic.value
        _diagnostic.value = d.copy(
            capture = capture ?: d.capture,
            ocr = ocr ?: d.ocr,
            aiApi = aiApi ?: d.aiApi,
            questionDetected = questionDetected ?: d.questionDetected,
            optionCount = optionCount ?: d.optionCount,
            response = response ?: d.response,
            latencyMs = latencyMs ?: d.latencyMs,
            error = if (clearError) null else (error ?: d.error),
            autoSubmit = autoSubmit ?: d.autoSubmit,
            autoSubmitDetail = if (clearAutoSubmitDetail) null else (autoSubmitDetail ?: d.autoSubmitDetail)
        )
    }
}
