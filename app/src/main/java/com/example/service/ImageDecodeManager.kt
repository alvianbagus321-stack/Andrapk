package com.example.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.model.ToolResult
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ImageDecodeManager — mendekode gambar (base64 / file / uri) dan mengubahnya
 * menjadi deskripsi TEKSTUAL yang kaya sehingga model AI NON-VISION pun bisa
 * "membaca" isi gambar: dimensi, format, warna dominan, kecerahan, tingkat
 * detail, teks hasil OCR, dan peta bentuk ASCII.
 */
object ImageDecodeManager {

    private const val TAG = "ImageDecodeManager"

    private const val MAX_DECODE_DIM = 512      // batas dekode untuk analisis warna/tekstur
    private const val ASCII_COLS = 36           // lebar peta ASCII
    private const val ASCII_ROWS = 18           // tinggi peta ASCII

    // Rampa karakter: indeks kecil = gelap, besar = terang
    private const val LUMINANCE_RAMP = " .:-=+*#%@"

    /** Screenshot terakhir yang diambil perangkat — bisa dianalisis via source="last_screenshot". */
    @Volatile
    private var lastScreenshotBase64: String? = null

    fun rememberScreenshot(base64: String) {
        lastScreenshotBase64 = base64
    }

    // ==========================================================
    // Entry points
    // ==========================================================

    suspend fun analyzeBase64(base64: String, withOcr: Boolean = true): ToolResult =
        withContext(Dispatchers.IO) {
            val clean = base64.substringAfter("base64,", base64).trim()
            if (clean.isBlank()) {
                return@withContext ToolResult("error", errorCode = com.example.model.ErrorCodes.INVALID_ARGUMENTS, message = "String base64 kosong.")
            }
            val bytes = try {
                Base64.decode(clean, Base64.DEFAULT)
            } catch (e: Exception) {
                return@withContext ToolResult("error", errorCode = com.example.model.ErrorCodes.INVALID_ARGUMENTS, message = "Gagal mendekode base64: ${e.message}")
            }
            analyzeBytes(bytes, withOcr)
        }

    suspend fun analyzePath(path: String, withOcr: Boolean = true): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (!file.exists()) {
                return@withContext ToolResult("error", errorCode = com.example.model.ErrorCodes.ELEMENT_NOT_FOUND, message = "File tidak ditemukan: $path")
            }
            analyzeBytes(file.readBytes(), withOcr)
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal membaca file: ${e.message}")
        }
    }

    suspend fun analyzeUri(context: android.content.Context, uriString: String, withOcr: Boolean = true): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    ToolResult("error", message = "Tidak bisa membaca uri: $uriString")
                } else {
                    analyzeBytes(bytes, withOcr)
                }
            } catch (e: Exception) {
                ToolResult("error", message = "Gagal membaca uri: ${e.message}")
            }
        }

    suspend fun analyzeLastScreenshot(withOcr: Boolean = true): ToolResult {
        val b64 = lastScreenshotBase64
            ?: return ToolResult("error", message = "Belum ada screenshot tersimpan. Ambil screenshot dulu (tool 'screenshot'), lalu analisis dengan source='last_screenshot'.")
        return analyzeBase64(b64, withOcr)
    }

    /**
     * OCR hanya pada AREA tertentu dari gambar (crop dulu, baru OCR).
     * Jauh lebih hemat token & akurat dibanding decode_image satu layar penuh.
     * Region: piksel sumber (left/top/right/bottom) ATAU persen (x/y/w/h_percent).
     * Sumber gambar: base64 eksplisit → last_screenshot → path file.
     */
    suspend fun analyzeRegion(
        base64: String?,
        path: String?,
        left: Int?, top: Int?, right: Int?, bottom: Int?,
        xPct: Double?, yPct: Double?, wPct: Double?, hPct: Double?
    ): ToolResult = withContext(Dispatchers.IO) {
        val cleanB64 = base64?.trim()?.takeIf { it.isNotBlank() }?.substringAfter("base64,")
        val bytes = when {
            cleanB64 != null -> try {
                Base64.decode(cleanB64, Base64.DEFAULT)
            } catch (e: Exception) {
                return@withContext ToolResult("error", message = "Gagal mendekode base64: ${e.message}")
            }
            path?.isNotBlank() == true -> {
                val f = File(path.trim())
                if (!f.exists()) return@withContext ToolResult("error", message = "File tidak ditemukan: $path")
                f.readBytes()
            }
            lastScreenshotBase64 != null -> Base64.decode(lastScreenshotBase64!!, Base64.DEFAULT)
            else -> return@withContext ToolResult(
                "error",
                message = "Tidak ada sumber gambar. Ambil 'screenshot' dulu, atau kirim base64/path."
            )
        }

        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@withContext ToolResult("error", message = "Byte bukan gambar yang dikenali.")
        val iw = full.width
        val ih = full.height

        // Hitung region crop
        val l: Int; val t: Int; val r: Int; val b: Int
        if (left != null || top != null || right != null || bottom != null) {
            l = (left ?: 0).coerceIn(0, iw - 1)
            t = (top ?: 0).coerceIn(0, ih - 1)
            r = (right ?: iw).coerceIn(l + 1, iw)
            b = (bottom ?: ih).coerceIn(t + 1, ih)
        } else if (xPct != null || yPct != null || wPct != null || hPct != null) {
            l = ((xPct ?: 0.0).coerceIn(0.0, 100.0) / 100.0 * iw).toInt().coerceIn(0, iw - 1)
            t = ((yPct ?: 0.0).coerceIn(0.0, 100.0) / 100.0 * ih).toInt().coerceIn(0, ih - 1)
            r = (((xPct ?: 0.0) + (wPct ?: 100.0)).coerceIn(0.0, 100.0) / 100.0 * iw).toInt().coerceIn(l + 1, iw)
            b = (((yPct ?: 0.0) + (hPct ?: 100.0)).coerceIn(0.0, 100.0) / 100.0 * ih).toInt().coerceIn(t + 1, ih)
        } else {
            l = 0; t = 0; r = iw; b = ih
        }
        val cropW = r - l
        val cropH = b - t
        if (cropW < 4 || cropH < 4) {
            full.recycle()
            return@withContext ToolResult("error", message = "Region terlalu kecil (${cropW}x${cropH}px). Gambar sumber: ${iw}x${ih}px.")
        }

        val crop = Bitmap.createBitmap(full, l, t, cropW, cropH)
        val ocr = runOcr(crop)
        crop.recycle()
        full.recycle()

        val text = ocr.getOrElse { "" }
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val body = if (text.isNotBlank()) {
            "🔍 OCR region (left=$l, top=$t, $cropW x $cropH px dari gambar ${iw}x${ih}px) — $wordCount kata:\n$text"
        } else {
            val why = ocr.exceptionOrNull()?.message?.let { " (penyebab: $it)" } ?: ""
            "🔍 OCR region (left=$l, top=$t, $cropW x $cropH px) TIDAK menemukan teks$why. Coba perbesar region, atau ambil screenshot baru lalu ulangi."
        }
        ToolResult("ok", result = body)
    }

    // ==========================================================
    // Pipeline analisis utama
    // ==========================================================

    private fun analyzeBytes(bytes: ByteArray, withOcr: Boolean): ToolResult {
        // 1. Baca metadata tanpa dekode penuh
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ToolResult("error", message = "Byte bukan gambar yang dikenali (decode bounds gagal).")
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        val mime = bounds.outMimeType?.substringAfter("/")?.uppercase() ?: "UNKNOWN"

        // 2. Dekode versi terkecil yang cukup untuk analisis (hemat memori)
        val bitmap = decodeSampled(bytes, MAX_DECODE_DIM)
            ?: return ToolResult("error", message = "Gagal mendekode bitmap dari byte gambar.")

        // 3. Analisis visual
        val gridSize = ASCII_COLS * ASCII_ROWS
        val gridLum = luminanceGrid(bitmap)
        val avgBrightness = gridLum.average().toFloat()
        val darkPct = gridLum.count { it < 85 } * 100f / gridSize
        val midPct = gridLum.count { it in 85..170 } * 100f / gridSize
        val brightPct = gridLum.count { it > 170 } * 100f / gridSize
        val detailScore = edgeDensity(gridLum, ASCII_COLS, ASCII_ROWS)

        val colors = dominantColors(bitmap, topN = 5)
        val ascii = buildAsciiMap(gridLum, ASCII_COLS, ASCII_ROWS)

        val ratio = simplifyRatio(width, height)
        val sizeKb = bytes.size / 1024.0

        val sb = StringBuilder()
        sb.appendLine("🖼️ HASIL DEKODE GAMBAR")
        sb.appendLine("Format: $mime | Dimensi: ${width}x${height} px (rasio $ratio) | Ukuran: ${"%.1f".format(Locale.US, sizeKb)} KB")
        val brightnessLabel = when {
            avgBrightness < 60 -> "gelap"
            avgBrightness > 185 -> "terang"
            else -> "sedang"
        }
        sb.appendLine("Kecerahan rata-rata: ${avgBrightness.roundToInt()}/255 ($brightnessLabel) — Gelap: ${darkPct.roundToInt()}%, Sedang: ${midPct.roundToInt()}%, Terang: ${brightPct.roundToInt()}%")
        val detailLabel = when {
            detailScore < 12 -> "rendah (kemungkinan foto polos / gradasi / blur)"
            detailScore < 30 -> "menengah"
            else -> "tinggi (kemungkinan banyak teks, UI, atau detail tajam)"
        }
        sb.appendLine("Tingkat detail/tekstur: ${detailScore.roundToInt()}/100 — $detailLabel")

        sb.appendLine()
        sb.appendLine("🎨 Warna dominan:")
        colors.forEachIndexed { i, c ->
            sb.appendLine("  ${i + 1}. ${c.hex} (${String.format(Locale.US, "%.1f", c.percentage)}%)")
        }

        if (withOcr) {
            sb.appendLine()
            val ocr = runOcr(bitmap)
            if (ocr.isSuccess) {
                val text = ocr.getOrNull()?.trim()
                sb.appendLine("📝 Teks terdeteksi (OCR):")
                if (text.isNullOrBlank()) {
                    sb.appendLine("(tidak ada teks yang dapat dikenali dalam gambar)")
                } else {
                    val clipped = if (text.length > 2500) text.take(2500) + "... [terpotong]" else text
                    sb.appendLine("\"\"\"")
                    sb.appendLine(clipped)
                    sb.appendLine("\"\"\"")
                }
            } else {
                sb.appendLine("📝 OCR tidak tersedia: ${ocr.exceptionOrNull()?.message ?: "mesin pengenalan teks gagal"}")
            }
        }

        sb.appendLine()
        sb.append("🗺️ Peta bentuk (ASCII; '@' = terang, spasi = gelap, ${ASCII_COLS}x${ASCII_ROWS}):")
        sb.append('\n').append(ascii)

        bitmap.recycle()
        return ToolResult("ok", result = sb.toString().trimEnd())
    }

    private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxDim || opts.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    /** Grid luminance ASCII_COLS x ASCII_ROWS (0..255) dari bitmap (di-downscale dulu). */
    private fun luminanceGrid(bitmap: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, ASCII_COLS, ASCII_ROWS, true)
        val pixels = IntArray(ASCII_COLS * ASCII_ROWS)
        small.getPixels(pixels, 0, ASCII_COLS, 0, 0, ASCII_COLS, ASCII_ROWS)
        val lum = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
        }
        if (small !== bitmap) small.recycle()
        return lum
    }

    private fun buildAsciiMap(gridLum: IntArray, cols: Int, rows: Int): String {
        val ramp = LUMINANCE_RAMP
        val sb = StringBuilder()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val lum = gridLum[y * cols + x]
                val idx = (lum * (ramp.length - 1)) / 255
                sb.append(ramp[idx.coerceIn(0, ramp.length - 1)])
            }
            if (y < rows - 1) sb.append('\n')
        }
        return sb.toString()
    }

    /** Rata-rata selisih luminance tetangga (0..100) sebagai proksi kepadatan detail. */
    private fun edgeDensity(gridLum: IntArray, cols: Int, rows: Int): Float {
        var total = 0f
        var count = 0
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val cur = gridLum[y * cols + x].toFloat()
                if (x < cols - 1) {
                    total += abs(cur - gridLum[y * cols + x + 1]); count++
                }
                if (y < rows - 1) {
                    total += abs(cur - gridLum[(y + 1) * cols + x]); count++
                }
            }
        }
        if (count == 0) return 0f
        val mean = total / count
        return (mean / 255f * 100f).coerceIn(0f, 100f)
    }

    private data class DominantColor(val hex: String, val percentage: Float)

    /** Kuantisasi warna 4-bit/kanal lalu ambil N teratas (filter warna terlalu mirip). */
    private fun dominantColors(bitmap: Bitmap, topN: Int): List<DominantColor> {
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val pixels = IntArray(64 * 64)
        small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        if (small !== bitmap) small.recycle()

        val buckets = HashMap<Int, Int>()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            buckets[key] = (buckets[key] ?: 0) + 1
        }

        val total = pixels.size.toFloat()
        val sorted = buckets.entries.sortedByDescending { it.value }
        val result = mutableListOf<DominantColor>()
        for ((key, count) in sorted) {
            val r = ((key shr 8) and 0xF) * 17
            val g = ((key shr 4) and 0xF) * 17
            val b = (key and 0xF) * 17
            val pct = count * 100f / total
            // Abaikan bucket < 1.5%, dan jangan tambahkan warna yang terlalu mirip dengan yang sudah ada
            if (pct < 1.5f) break
            val tooSimilar = result.any {
                val or = Integer.parseInt(it.hex.substring(1, 3), 16)
                val og = Integer.parseInt(it.hex.substring(3, 5), 16)
                val ob = Integer.parseInt(it.hex.substring(5, 7), 16)
                abs(or - r) + abs(og - g) + abs(ob - b) < 60
            }
            if (tooSimilar) continue
            result.add(DominantColor(String.format(Locale.US, "#%02X%02X%02X", r, g, b), pct))
            if (result.size >= topN) break
        }
        if (result.isEmpty()) {
            result.add(DominantColor("#808080", 100f))
        }
        return result
    }

    /** OCR via ML Kit (blocking di background thread). */
    private fun runOcr(bitmap: Bitmap): Result<String> {
        return try {
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

    private fun simplifyRatio(w: Int, h: Int): String {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val g = gcd(w, h).coerceAtLeast(1)
        val rw = w / g
        val rh = h / g
        return if (rw <= 40 && rh <= 40) "$rw:$rh" else String.format(Locale.US, "%.2f", w.toDouble() / h)
    }
}
