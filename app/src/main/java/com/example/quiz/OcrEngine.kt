package com.example.quiz

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR engine untuk Quiz Analyzer — ML Kit on-device (offline, cepat).
 * Dua mode: teks polos (pipeline analisis) dan bounding-box per baris
 * (dipakai Auto Submit untuk menemukan posisi opsi/tombol di screenshot).
 */
object OcrEngine {
    private const val TAG = "QuizOcrEngine"

    /** Satu baris teks OCR + posisinya di bitmap (piksel bitmap sumber). */
    data class OcrLineBox(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

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

    /** OCR dengan posisi: kembalikan tiap baris + bounding box-nya (koordinat bitmap). */
    suspend fun recognizeWithBoxes(bitmap: Bitmap): Result<List<OcrLineBox>> =
        withContext(Dispatchers.IO) {
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val visionText = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                recognizer.close()
                val boxes = mutableListOf<OcrLineBox>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val bb = line.boundingBox ?: continue
                        boxes.add(OcrLineBox(line.text, bb.left, bb.top, bb.right, bb.bottom))
                    }
                }
                Result.success(boxes)
            } catch (e: Exception) {
                Log.w(TAG, "OCR boxes gagal: ${e.message}")
                Result.failure(e)
            }
        }
}
