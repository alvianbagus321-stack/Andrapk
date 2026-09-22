package com.example.quiz

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.withContext

/**
 * OCR engine untuk Quiz Analyzer — ML Kit on-device (offline, cepat).
 * Sengaja terpisah dari ImageDecodeManager agar modul quiz mandiri dan
 * menerima Bitmap hasil screenshot langsung.
 */
object OcrEngine {
    private const val TAG = "QuizOcrEngine"

    suspend fun recognize(bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = Tasks.await(recognizer.process(inputImage))
            recognizer.close()
            Result.success(visionText.text)
        } catch (e: Exception) {
            Log.w(TAG, "OCR gagal: ${e.message}")
            Result.failure(e)
        }
    }
}
