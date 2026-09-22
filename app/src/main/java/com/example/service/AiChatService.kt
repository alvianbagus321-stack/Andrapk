package com.example.service

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.BuildConfig
import com.example.model.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiChatResponse(
    val replyText: String,
    val thinkingProcess: String? = null,
    val actionToolName: String? = null,
    val actionResult: ToolResult? = null
)

/**
 * Direct REST AI Client supporting Google Gemini, OpenAI-compatible APIs, or custom OpenAI endpoints.
 * Users can configure custom API key, custom base URL, and model in the Chat Window settings.
 */
object AiChatService {

    private const val TAG = "AiChatService"

    @Volatile
    private var isCancelled = false

    val liveThoughtState = MutableStateFlow<String?>("")

    fun stopCurrentExecution() {
        isCancelled = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Default settings: Gemini 3.5 Flash per gemini-api skill standard
    const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"

    /**
     * Safety cap: maximum tool calls the agent may execute within ONE agent loop turn.
     * Guards against runaway multi-action batches in a single response.
     */
    private const val MAX_ACTIONS_PER_TURN = 8

    /**
     * Executes AI prompt and coordinates with Android automation tools if action commands are determined.
     */
    suspend fun sendMessage(
        userPrompt: String,
        apiKey: String,
        baseUrl: String,
        modelName: String,
        history: List<Pair<String, String>>, // (role, text)
        attachmentUri: String? = null,
        attachmentMimeType: String? = null,
        onStatusUpdate: (String) -> Unit
    ): AiChatResponse = withContext(Dispatchers.IO) {
        val savedConfig = com.example.data.AiConfigManager.config.value
        val cleanKey = apiKey.trim().ifEmpty {
            com.example.data.AiConfigManager.getEffectiveApiKey()
        }
        val cleanBaseUrl = baseUrl.trim().ifEmpty { savedConfig.baseUrl.ifEmpty { DEFAULT_GEMINI_BASE_URL } }.trimEnd('/')
        val cleanModel = modelName.trim().ifEmpty { savedConfig.modelName.ifEmpty { DEFAULT_GEMINI_MODEL } }

        val isGemini = com.example.data.AiConfigManager.isGeminiEndpoint(cleanBaseUrl)

        if (isGemini && (cleanKey.isEmpty() || cleanKey == "MY_GEMINI_API_KEY")) {
            return@withContext AiChatResponse(
                replyText = "⚠️ API Key belum disetel. Silakan masukkan API Key Anda pada pengaturan AI Endpoint di atas atau melalui AI Studio Secrets panel.",
                thinkingProcess = null,
                actionResult = null
            )
        }

        onStatusUpdate("Menghubungi AI ($cleanModel)...")

        // Dynamically get active tools from ToolManager
        val activeTools = ToolManager.tools.value.filter { it.isEnabled }
        val toolsDescription = activeTools.joinToString("\n") { tool ->
            "- ${tool.id}: ${tool.description} (params schema: ${tool.parametersSchema})"
        }
        val currentPermissionMode = ToolManager.permissionMode.value

        val context = com.example.JarvisApp.instance
        val metrics = com.example.service.ScreenshotManager.getScreenMetrics(context)
        val displayResolutionGuide = """
            📱 DIMENSI & RESOLUSI LAYAR PERANGKAT (ACCESSIBILITY RESOLUTION GUIDE):
            - Lebar Layar (Width): ${metrics.widthPixels} px
            - Tinggi Layar (Height): ${metrics.heightPixels} px
            - Kepadatan (Density): ${metrics.densityDpi} dpi (${metrics.density}x)
            - PENTING UNTUK TAP/GESTURE AGAR KETUKAN AKURAT & TIDAK MISS:
              • Selalu pastikan koordinat x ada di kisaran 0..${metrics.widthPixels} px dan y di kisaran 0..${metrics.heightPixels} px.
              • Kamu bisa gunakan persentase: {"x_percent": 50, "y_percent": 50} untuk posisi tengah layar.
              • Kamu bisa gunakan rasio desimal: {"x": 0.5, "y": 0.5}.
              • Atau sebutkan element_id / teks tombol: {"element_id": "com.whatsapp:id/send"} atau {"element_id": "Kirim"}.
        """.trimIndent()

        // Construct System Instructions for Autonomous Agent Loop & ReAct Step Execution
        val systemInstruction = """
            Kamu adalah JARVIS-HP, AI Agent otonom tingkat lanjut yang mengendalikan smartphone Android secara langsung melalui Shizuku, Termux, Android Intent, Shell, dan Accessibility Service.
            Mode Izin Keamanan saat ini: ${currentPermissionMode.name}.

            $displayResolutionGuide
            
            PENTING (AKSES SISTEM & TOOLS):
            1. Kamu memiliki AKSES PENUH ke sistem smartphone Android melalui Shizuku, Termux, ADB, dan Accessibility.
            2. SEMUA tools yang terdaftar di sistem ini (baik bawaan maupun custom tool buatan pengguna/AI) adalah TOOLS NYATA DAN AKTIF (BUKAN DUMMY).
            3. JANGAN PERNAH menolak perintah atau mengatakan tool adalah dummy atau tidak dapat dijalankan.
            4. Jika pengguna meminta menjalankan perintah shell, termux, membuka aplikasi, mengubah pengaturan, cek storage, cek ram, atau tool custom lainnya, SEGERA EKSEKUSI dengan mengeluarkan blok ```json:action```!
            
            PRINSIP AGENT LOOP:
            1. Kamu beroperasi dalam multi-step Agent Loop: Berpikir → Eksekusi satu ATAU beberapa tindakan (tool) → Amati hasil dari HP → Tentukan tindakan berikutnya → Ulangi sampai selesai.
            2. MULTI-AKSI PER GILIRAN: Dalam satu giliran kamu BOLEH mengeluarkan LEBIH DARI SATU blok ```json:action``` (usahakan maksimal 5 blok). Semua blok akan dieksekusi secara BERURUTAN sesuai urutan penulisan.
               - GUNAKAN multi-aksi jika beberapa tool saling melengkapi dan kamu SUDAH MENGETAHUI semua parameternya tanpa perlu membaca hasil aksi sebelumnya (contoh: cek_ram + get_storage + shell 'date', tap + type_text + press_key ENTER, termux_api + clipboard_set).
               - GUNAKAN SATU aksi saja per giliran jika aksi berikutnya BERGANTUNG pada hasil/output/perubahan layar dari aksi sebelumnya (contoh: open_app lalu read_screen, read_screen lalu tap ke elemen yang baru terdeteksi, screenshot untuk menentukan koordinat berikutnya).
            3. Setiap kali kamu butuh mengeksekusi aksi, letakkan blok JSON di bagian akhir jawabanmu dengan format (boleh lebih dari satu blok):
            ```json:action
            {
              "tool": "nama_tool",
              "params": { ... }
            }
            ```
            4. Setelah SEMUA blok aksi dieksekusi oleh sistem Android, hasilnya (stdout / output / status) akan langsung dikirimkan kembali kepadamu pada giliran berikutnya, diurutkan sesuai urutan eksekusi (Aksi 1, Aksi 2, dst).
            5. KETIKA SEMUA TUGAS SELESAI atau pengguna hanya bertanya tanpa perlu aksi di HP, berikan jawaban akhir yang ramah, informatif, dan solutif TANPA blok ```json:action```.

            STRATEGI AKURASI & VERIFIKASI (WAJIB — hasil audit pengujian perangkat nyata):
            1. read_screen adalah SUMBER KOORDINAT PRIMER. Jangan pernah menebak koordinat. Bila tahu teks tombolnya, SELALU utamakan tap_by_text (anti-miss, aman di landscape) di atas tap koordinat manual.
            2. Panggil screen_orientation di awal sesi dan saat mencurigai layar berputar (mis. setelah buka app video/remote desktop). Screenshot kini OTOMATIS mengikuti rotasi layar.
            3. VERIFIKASI setelah setiap aksi penting: wait_for_element (teks yang diharapkan muncul), get_current_app / dumpsys_window (app target di depan), atau diff_screen (bandingkan before/after: panggil sebelum aksi utk baseline lalu setelah aksi).
            4. Jika aksi tampak gagal (diff_screen bilang tidak berubah, tap meleset), RETRY MAKSIMAL 2x dengan pendekatan BERBEDA (koordinat → tap_by_text → accessibility_click), lalu laporkan ke user bila tetap gagal.
            5. Untuk list panjang pakai scroll_to_text (bukan swipe buta berulang); tunggu layar selesai loading dengan wait_stable sebelum screenshot/read_screen; baca layar padat dengan read_screen + {"filter_clickable": true} agar hanya tombol yang tampil.

            ARSITEKTUR TOOL (3-LAYER MODULAR REGISTRY):
            1. Android & Accessibility Layer:
               - open_app: Membuka aplikasi (params: {"package_name": "com.google.android.youtube"} atau alias "youtube", "chrome", "whatsapp", "settings")
               - read_screen: Membaca semua elemen UI, teks, tombol, viewId, dan koordinat posisi yang sedang tampil di layar HP
               - tap: Menekan elemen di layar (params: {"element_id": "id_atau_teks_tombol"} atau {"x": 540, "y": 1200})
               - type_text: Mengetik teks pada kolom input aktif (params: {"text": "teks yang ingin diketik", "element_id": "opsional"})
               - press_key: Menekan tombol sistem (params: {"keycode": "ENTER"|"BACK"|"HOME"|"RECENTS"|"VOLUME_UP"|"VOLUME_DOWN"})
               - swipe: Menggeser layar (params: {"x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration_ms": 300})
               - screenshot: Mengambil tangkapan layar perangkat
               - decode_image: Mendekode gambar (params: {"source": "last_screenshot"} atau {"base64": "..."} / {"path": "..."} / {"uri": "..."}) menjadi TEKS lengkap: dimensi, warna dominan, kecerahan, tingkat detail, peta bentuk ASCII, dan OCR teks. WAJIB dipakai untuk "melihat" isi gambar/screenshot jika kamu tidak mendukung input gambar (non-vision).
               - ocr_region: OCR hanya AREA tertentu dari screenshot (HEMAT TOKEN — pakai ini dulu sebelum decode_image jika hanya butuh teks): params {"x_percent":0,"y_percent":0,"w_percent":50,"h_percent":30} atau piksel {"left":0,"top":0,"right":400,"bottom":200}
               - record_screen: Rekam layar jadi video MP4 (params: {"action":"start"} lalu {"action":"stop"}; maks 3 menit) — pakai untuk debugging multi-step
               - adb_via_shizuku: Shell level ADB via Shizuku (params: {"command":"pm list packages -3"} atau {"action":"status"/"permission"}) — pakai INI saat shell biasa DITOLAK (pm grant, am force-stop, uiautomator dump); butuh app Shizuku aktif
               - send_notification: Mengirim notifikasi lokal ke status bar (params: {"title": "Judul", "message": "Pesan"})
               - flashlight_toggle: Menyalakan/mematikan senter (params: {"enable": true})
               - screen_orientation: Cek rotasi & dimensi layar saat ini (panggil sebelum tap bila orientasi berubah)
               - find_by_text / tap_by_text / wait_for_element / scroll_to_text: Cari, ketuk, tunggu, dan scroll berdasarkan TEKS elemen — lebih akurat daripada koordinat manual
               - input_swipe_bezier: Swipe kurva manusiawi (params: {"x1":500,"y1":800,"x2":500,"y2":300,"duration_ms":600,"bend":0.35}) untuk carousel/map yang mengabaikan swipe garis lurus
               - wait_stable / diff_screen: Pastikan layar stabil / bandingkan layar before-after aksi (verifikasi otomatis)
               - accessibility_click: Klik elemen langsung via AccessibilityNodeInfo (params: {"element_id": "id_atau_teks"})
               - dumpsys_window: Info window fokus + rotasi via dumpsys (diagnosa orientasi/app aktif)
            2. Termux Service, API & Shell Layer:
               - termux_service: Mengontrol service background Termux & daemon JARVIS (params: {"action": "status"|"start"|"stop"|"restart"|"run_agent"|"list", "service": "jarvis_agent"})
               - termux_api: Menjalankan utilitas Termux:API (params: {"command": "battery"|"wifi"|"tts"|"vibrate"|"torch"|"notification"|"toast"|"location"|"volume"|"sensor"|"sms"|"clipboard-get"|"clipboard-set", "args": "..."})
               - termux_pkg: Mengelola package Termux (params: {"action": "install"|"update"|"list"|"search", "package": "nama_paket"})
               - termux_python: Menjalankan kode atau script Python 3 langsung di runtime Termux (params: {"code": "print('Halo dari Termux!')"})
               - termux_file: Operasi file Termux/Android (params: {"action": "read"|"write"|"list"|"delete"|"mkdir", "path": "...", "content": "..."})
               - shell / termux_command: Menjalankan perintah bash/Linux/ADB apa pun langsung di HP (params: {"command": "df -h" atau "pm list packages -3"})
               - get_storage: Cek kapasitas memori internal HP (Total, Used, Free dalam GB)
               - cek_ram: Cek memori RAM perangkat (Total, Available, Used dalam MB/GB)
               - clipboard_read, clipboard_write: Membaca dan menulis teks ke clipboard sistem
               - http_request: Request API HTTP GET/POST (params: {"url": "https://api.ipify.org?format=json"})
            3. Custom Tools Layer:
               - create_tool: Membuat tool baru secara dinamis (params: {"name": "...", "description": "...", "script_type": "shell", "command": "...", "parameters_schema": "{}"})
               - create_python_tool: Membuat script tool Python lengkap untuk dieksekusi (params: {"filename": "tool.py", "tool_name": "...", "description": "...", "code": "..."})

            PROSES BERPIKIR (REASONING TRACE):
            Tuliskan analisa kamu di dalam tag:
            <thought>
            [Langkah analisa: apa yang sedang terjadi, evaluasi hasil tool sebelumnya, dan aksi apa yang harus dilakukan berikutnya]
            </thought>

            MEMORI TERMANFAATKAN (.md):
            ${com.example.data.JarvisMemoryManager.getMemoryMarkdown()}

            Daftar Tool yang AKTIF saat ini:
            $toolsDescription

            Jika pengguna meminta membuat website/komponen UI, berikan kode HTML lengkap di dalam markdown ```html ... ``` agar dapat langsung di-preview di aplikasi.
            Gunakan Bahasa Indonesia yang ramah, profesional, dan ringkas.
        """.trimIndent()

        isCancelled = false
        var currentPrompt = userPrompt

        // Process file / image attachment if provided
        var imageBase64: String? = null
        if (!attachmentUri.isNullOrEmpty()) {
            if (attachmentMimeType?.startsWith("image/") == true) {
                imageBase64 = readUriAsBase64(context, attachmentUri)
            } else {
                val fileText = readUriAsString(context, attachmentUri)
                if (!fileText.isNullOrEmpty()) {
                    currentPrompt = "[LAMPIRAN BERKAS TERHUBUNG]\n```\n$fileText\n```\n\n$userPrompt"
                }
            }
        }

        // AI NON-VISION (endpoint OpenAI-compatible tanpa dukungan gambar):
        // dekode lampiran gambar menjadi deskripsi tekstual agar tetap bisa "dibaca" model.
        if (!isGemini && imageBase64 != null) {
            onStatusUpdate("Menganalisa lampiran gambar menjadi teks (mode non-vision)...")
            val analysis = ImageDecodeManager.analyzeBase64(imageBase64)
            val analysisText = analysis.result ?: analysis.message
            if (!analysisText.isNullOrBlank()) {
                currentPrompt = "[LAMPIRAN GAMBAR — didekode otomatis menjadi deskripsi tekstual]\n$analysisText\n\n$userPrompt"
            }
            imageBase64 = null
        }

        liveThoughtState.value = ""
        var activeStepImageBase64: String? = imageBase64
        var activeStepImageMimeType: String? = attachmentMimeType ?: "image/jpeg"

        // Multi-Step Autonomous Agent Loop Controller
        val maxSteps = savedConfig.maxAgentLoops
        val isUnlimited = maxSteps == 0
        val effectiveMaxSteps = if (isUnlimited) 200 else maxSteps
        var currentStep = 0
        val loopHistory = history.toMutableList()
        val allThoughts = mutableListOf<String>()
        val executedTools = mutableListOf<Pair<String, ToolResult>>()
        val stepSummary = mutableListOf<String>()

        var finalReplyText = ""
        var lastToolResult: ToolResult? = null
        var lastActionName: String? = null

        while (isUnlimited || currentStep < maxSteps) {
            if (currentStep >= effectiveMaxSteps) {
                break
            }
            if (isCancelled) {
                onStatusUpdate("Dihentikan oleh pengguna.")
                return@withContext AiChatResponse(
                    replyText = if (finalReplyText.isNotBlank()) finalReplyText else "Proses AI dihentikan oleh pengguna. 🛑",
                    thinkingProcess = allThoughts.joinToString("\n\n"),
                    actionToolName = lastActionName,
                    actionResult = lastToolResult
                )
            }
            currentStep++
            val stepLabel = if (isUnlimited) "Langkah $currentStep (Mode Otomatis)" else "Langkah $currentStep/$maxSteps"
            onStatusUpdate("$stepLabel: Menganalisa & merencanakan aksi...")

            val (rawResponse, nativeThought) = if (isGemini) {
                callGeminiRest(
                    cleanBaseUrl, cleanModel, cleanKey, systemInstruction, loopHistory, currentPrompt,
                    imageBase64 = activeStepImageBase64,
                    imageMimeType = activeStepImageMimeType
                )
            } else {
                Pair(callOpenAiRest(cleanBaseUrl, cleanModel, cleanKey, systemInstruction, loopHistory, currentPrompt), null)
            }

            // Clear image after single-use step unless updated by screenshot tool result
            activeStepImageBase64 = null

            // Extract <thought> tags
            val (extractedThought, textWithoutThought) = extractThinking(rawResponse)
            val combinedThought = when {
                !nativeThought.isNullOrBlank() && !extractedThought.isNullOrBlank() -> "$nativeThought\n\n$extractedThought"
                !nativeThought.isNullOrBlank() -> nativeThought
                !extractedThought.isNullOrBlank() -> extractedThought
                else -> null
            }
            if (!combinedThought.isNullOrBlank()) {
                allThoughts.add("[$stepLabel]\n$combinedThought")
                liveThoughtState.value = "[$stepLabel]\n$combinedThought"
            }

            // Parse action JSON — supports one or multiple tool calls per turn
            val parsedActions = extractActionJsonList(textWithoutThought)

            if (parsedActions.isNotEmpty()) {
                loopHistory.add("assistant" to textWithoutThought)

                val turnResultBlocks = mutableListOf<String>()
                val turnExecutedCount = mutableListOf<Pair<String, ToolResult>>()
                var turnScreenshotB64: String? = null
                val wasTruncated = parsedActions.size >= MAX_ACTIONS_PER_TURN

                // Execute every action requested this turn, sequentially in order
                for ((actionIdx, parsedAction) in parsedActions.withIndex()) {
                    if (isCancelled) break

                    val toolName = parsedAction.optString("tool", parsedAction.optString("name", "")).trim()
                    val params = parsedAction.optJSONObject("params") ?: parsedAction.optJSONObject("arguments") ?: JSONObject()

                    if (toolName.isEmpty()) {
                        val skippedRes = ToolResult("error", message = "Blok aksi dilewati karena tidak memiliki field 'tool'.")
                        turnExecutedCount.add("(tidak_dikenal)" to skippedRes)
                        turnResultBlocks.add("Aksi ${actionIdx + 1}\nStatus: error\nOutput:\n${skippedRes.message}")
                        continue
                    }

                    val batchLabel = if (parsedActions.size > 1) "$stepLabel | Aksi ${actionIdx + 1}/${parsedActions.size}" else stepLabel
                    onStatusUpdate("$batchLabel: Mengeksekusi '$toolName'...")

                    // Execute tool locally
                    val executionResult = executeActionLocally(toolName, params)
                    lastToolResult = executionResult
                    lastActionName = toolName
                    turnExecutedCount.add(toolName to executionResult)
                    executedTools.add(toolName to executionResult)

                    // Check if tool produced a new screenshot Base64 (latest screenshot wins)
                    val toolScreenshotB64 = executionResult.extra["screenshot_b64"] as? String
                    var screenAnalysisText: String? = null
                    if (!toolScreenshotB64.isNullOrBlank()) {
                        ImageDecodeManager.rememberScreenshot(toolScreenshotB64)
                        if (isGemini) {
                            // Model vision: kirim gambar asli pada langkah berikutnya
                            turnScreenshotB64 = toolScreenshotB64
                        } else {
                            // Model non-vision: ubah screenshot menjadi deskripsi tekstual
                            onStatusUpdate("$batchLabel: Menganalisa screenshot menjadi teks (mode non-vision)...")
                            val analysis = ImageDecodeManager.analyzeBase64(toolScreenshotB64)
                            val analysisText = analysis.result ?: analysis.message
                            if (!analysisText.isNullOrBlank()) {
                                screenAnalysisText = "[Analisa Otomatis Screenshot]\n$analysisText"
                            }
                        }
                    }

                    val resultOutputStr = executionResult.result ?: executionResult.message ?: if (executionResult.status == "ok") "Berhasil (OK)" else "Gagal"
                    val briefResult = if (resultOutputStr.length > 300) resultOutputStr.take(300) + "..." else resultOutputStr
                    stepSummary.add("$stepLabel: $toolName → ${executionResult.status.uppercase()}: $briefResult")

                    turnResultBlocks.add("Aksi ${actionIdx + 1} — Tool: $toolName\nStatus: ${executionResult.status}\nOutput:\n$resultOutputStr")
                    screenAnalysisText?.let { turnResultBlocks.add(it) }

                    // Natural delay for UI transitions (e.g. app launching or layout animations)
                    if (toolName.equals("open_app", ignoreCase = true)) {
                        kotlinx.coroutines.delay(1500L)
                    } else if (toolName.lowercase() in listOf("tap", "type_text", "press_key", "swipe")) {
                        kotlinx.coroutines.delay(500L)
                    }
                }

                // Send the latest screenshot (if any) for Vision analysis on the next step
                if (!turnScreenshotB64.isNullOrBlank()) {
                    activeStepImageBase64 = turnScreenshotB64
                    activeStepImageMimeType = "image/jpeg"
                    onStatusUpdate("$stepLabel: Tangkapan layar berhasil dikirim ke Analisis Visi AI...")
                }

                // Formulate feedback prompt for next step in agent loop (contains ALL results, in execution order)
                currentPrompt = buildString {
                    appendLine("[Hasil Eksekusi Tool $stepLabel — ${turnExecutedCount.size} aksi dieksekusi berurutan]")
                    turnResultBlocks.forEachIndexed { idx, block ->
                        appendLine(block)
                        if (idx < turnResultBlocks.lastIndex) appendLine()
                    }
                    if (wasTruncated) {
                        appendLine()
                        appendLine("⚠️ Batas $MAX_ACTIONS_PER_TURN aksi per giliran tercapai; sebagian blok aksi tidak dieksekusi.")
                    }
                    appendLine()
                    appendLine("Instruksi Pengguna Awal: \"$userPrompt\"")
                    append("Silakan evaluasi hasil di atas dan tentukan langkah berikutnya (atau berikan respon akhir jika tugas telah selesai).")
                }

            } else {
                // AI decided no further tool action is needed -> Task Complete!
                val cleanedText = textWithoutThought.replace(Regex("```json:action[\\s\\S]*?```"), "").trim()
                finalReplyText = if (cleanedText.isNotBlank()) cleanedText else "Tugas telah selesai diproses oleh JARVIS."
                break
            }
        }

        if (finalReplyText.isBlank() && ((!isUnlimited && currentStep >= maxSteps) || (isUnlimited && currentStep >= effectiveMaxSteps))) {
            val limitLabel = if (isUnlimited) "Batas pengaman Mode Otomatis ($effectiveMaxSteps langkah)" else "Batas maksimum langkah agent loop ($maxSteps langkah)"
            finalReplyText = "$limitLabel telah tercapai.\n\nRingkasan eksekusi:\n" + stepSummary.joinToString("\n")
        }

        val aggregatedThoughts = if (allThoughts.isNotEmpty()) {
            allThoughts.joinToString("\n\n---\n\n")
        } else {
            "Menganalisa instruksi: \"$userPrompt\". Menjalankan Agent Loop ($currentStep langkah)."
        }

        val actionSummaryName = when {
            executedTools.isEmpty() -> null
            executedTools.size == 1 -> executedTools.first().first
            else -> "Agent Loop (${executedTools.size} Langkah: ${executedTools.joinToString(" → ") { it.first }})"
        }

        AiChatResponse(
            replyText = finalReplyText,
            thinkingProcess = aggregatedThoughts,
            actionToolName = actionSummaryName,
            actionResult = lastToolResult
        )
    }

    private fun extractThinking(rawText: String): Pair<String?, String> {
        val regex = Regex("<thought>([\\s\\S]*?)</thought>", RegexOption.IGNORE_CASE)
        val match = regex.find(rawText)
        return if (match != null) {
            val thoughtContent = match.groupValues[1].trim()
            val textWithout = rawText.replace(regex, "").trim()
            Pair(thoughtContent, textWithout)
        } else {
            Pair(null, rawText)
        }
    }

    private fun readUriAsBase64(context: Context, uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading URI as base64", e)
            null
        }
    }

    private fun readUriAsString(context: Context, uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
            val sb = StringBuilder()
            var line: String?
            var totalChars = 0
            while (reader.readLine().also { line = it } != null) {
                sb.appendLine(line)
                totalChars += line?.length ?: 0
                if (totalChars > 12000) {
                    sb.appendLine("... [Lampiran terpotong agar tidak melebihi batas token]")
                    break
                }
            }
            inputStream.close()
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading URI text", e)
            null
        }
    }

    /**
     * Panggilan AI mentah untuk fitur lain (mis. AI Quiz Analyzer) — MEMAKAI
     * client & konfigurasi yang sudah ada (tanpa API key baru).
     * @return Pair(teks jawaban, pesan error) — teks null bila gagal.
     */
    suspend fun rawCompletion(
        systemInstruction: String,
        prompt: String,
        imageBase64: String? = null
    ): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val cfg = com.example.data.AiConfigManager.config.value
        if (cfg.apiKey.isBlank()) {
            return@withContext null to "API key AI belum diisi. Buka pengaturan AI di app untuk mengisinya."
        }
        val (text, _) = if (cfg.isGeminiNative) {
            callGeminiRest(cfg.baseUrl, cfg.modelName, cfg.apiKey, systemInstruction, emptyList(), prompt, imageBase64)
        } else {
            // Provider OpenAI-compatible: teks saja (gambar tidak didukung jalur ini)
            callOpenAiRest(cfg.baseUrl, cfg.modelName, cfg.apiKey, systemInstruction, emptyList(), prompt) to null
        }
        val isErrorPrefix = text.startsWith("Gagal menghubungi") ||
                text.startsWith("Koneksi gagal") ||
                text.startsWith("Tidak ada teks balasan")
        if (isErrorPrefix) null to text else text to null
    }

    private fun callGeminiRest(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        prompt: String,
        imageBase64: String? = null,
        imageMimeType: String? = "image/jpeg"
    ): Pair<String, String?> {
        val endpoint = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"

        val contentsArray = JSONArray()

        // Sanitize and build strictly alternating user/model history
        val cleanHistory = mutableListOf<Pair<String, String>>()
        for ((rawRole, rawMsg) in history.takeLast(12)) {
            val text = rawMsg.trim()
            if (text.isBlank()) continue
            // Truncate long past messages to prevent token overflow & huge payload lag
            val truncated = if (text.length > 1500) text.take(1500) + "... [terpotong]" else text
            val role = if (rawRole.equals("user", ignoreCase = true)) "user" else "model"
            
            // Merge consecutive messages with the same role to prevent Gemini 400 Bad Request
            if (cleanHistory.isNotEmpty() && cleanHistory.last().first == role) {
                val previous = cleanHistory.removeAt(cleanHistory.size - 1)
                cleanHistory.add(role to "${previous.second}\n\n$truncated")
            } else {
                cleanHistory.add(role to truncated)
            }
        }

        // If history ends with "user", remove it or merge with prompt to avoid two consecutive "user" roles
        if (cleanHistory.isNotEmpty() && cleanHistory.last().first == "user") {
            cleanHistory.removeAt(cleanHistory.size - 1)
        }

        // If history starts with "model", remove the first model turn as Gemini requires first turn to be "user"
        if (cleanHistory.isNotEmpty() && cleanHistory.first().first == "model") {
            cleanHistory.removeAt(0)
        }

        // Add normalized past conversation turns
        for ((role, msg) in cleanHistory) {
            contentsArray.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg)))
            })
        }

        // Add current user prompt
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            val partsArr = JSONArray()
            if (!imageBase64.isNullOrBlank()) {
                partsArr.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", imageMimeType ?: "image/jpeg")
                        put("data", imageBase64)
                    })
                })
            }
            partsArr.put(JSONObject().put("text", prompt))
            put("parts", partsArr)
        })

        val requestJson = JSONObject().apply {
            put("contents", contentsArray)
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("topP", 0.95)
            })
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return Pair("Gagal menghubungi Gemini API (${response.code}): $bodyString", null)
            }
            val json = JSONObject(bodyString)
            val candidates = json.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            var mainText = ""
            var nativeThought = ""

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    val isThought = p.optBoolean("thought", false)
                    val pText = p.optString("text", "")
                    if (isThought) {
                        nativeThought = if (nativeThought.isEmpty()) pText else "$nativeThought\n$pText"
                    } else {
                        mainText += pText
                    }
                }
            }

            val finalMainText = if (mainText.isNotBlank()) mainText else "Tidak ada teks balasan dari Gemini."
            val finalThought = if (nativeThought.isNotBlank()) nativeThought else null
            Pair(finalMainText, finalThought)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini REST error: ${e.message}", e)
            Pair("Koneksi gagal: ${e.message}", null)
        }
    }

    private fun callOpenAiRest(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        prompt: String
    ): String {
        val endpoint = when {
            baseUrl.endsWith("/chat/completions") -> baseUrl
            baseUrl.endsWith("/v1") -> "$baseUrl/chat/completions"
            else -> "$baseUrl/chat/completions"
        }

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", systemInstruction)
        })
        for ((role, msg) in history.takeLast(10)) {
            val truncated = if (msg.length > 1500) msg.take(1500) + "... [terpotong]" else msg
            messagesArray.put(JSONObject().apply {
                put("role", if (role.equals("user", ignoreCase = true)) "user" else "assistant")
                put("content", truncated)
            })
        }
        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("temperature", 0.3)
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(requestBody)

        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        if (baseUrl.contains("openrouter.ai")) {
            reqBuilder.addHeader("HTTP-Referer", "https://ai.studio/build")
            reqBuilder.addHeader("X-Title", "Andra Control")
        }

        return try {
            val response = httpClient.newCall(reqBuilder.build()).execute()
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return "Gagal memanggil API (${response.code}): $bodyString"
            }
            val json = JSONObject(bodyString)
            val choices = json.optJSONArray("choices")
            val message = choices?.optJSONObject(0)?.optJSONObject("message")
            val reasoning = message?.optString("reasoning_content", "") ?: ""
            val content = message?.optString("content", "") ?: "Tidak ada balasan dari API."
            
            if (reasoning.isNotBlank()) {
                "<thought>\n$reasoning\n</thought>\n\n$content"
            } else {
                content
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI REST error: ${e.message}", e)
            "Koneksi gagal: ${e.message}"
        }
    }

    /**
     * Extracts one or more action objects from the AI response.
     * Supported formats:
     * 1. Multiple ```json:action { ... } ``` blocks in a single response (executed sequentially by the agent loop).
     * 2. A single block containing a batch object: { "actions": [ {...}, {...} ] } or { "tools": [...] }.
     * 3. A single block containing a raw JSON array: [ {...}, {...} ].
     * 4. Legacy single-action block (backward compatible).
     * Returns at most [MAX_ACTIONS_PER_TURN] actions.
     */
    private fun extractActionJsonList(text: String): List<JSONObject> {
        val actions = mutableListOf<JSONObject>()
        fun atCapacity() = actions.size >= MAX_ACTIONS_PER_TURN

        val regex = Regex("```json:action([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        for (match in regex.findAll(text)) {
            if (atCapacity()) break
            val rawJson = match.groupValues[1].trim()

            val obj: JSONObject? = try {
                JSONObject(rawJson)
            } catch (_: Exception) {
                null
            }

            if (obj != null) {
                // Batch format: {"actions": [...]} / {"tools": [...]}
                val batchArray = obj.optJSONArray("actions") ?: obj.optJSONArray("tools")
                if (batchArray != null) {
                    for (i in 0 until batchArray.length()) {
                        val item = batchArray.optJSONObject(i) ?: continue
                        actions.add(item)
                        if (atCapacity()) break
                    }
                } else {
                    actions.add(obj)
                }
                continue
            }

            // Fallback: block may contain a raw JSON array of actions: [{...}, {...}]
            try {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    actions.add(item)
                    if (atCapacity()) break
                }
            } catch (_: Exception) {
                // Skip malformed block
            }
        }
        return actions
    }

    /** Eksekusi tool oleh client eksternal/MCP (publik, melewati semua pemeriksaan keamanan yang sama). */
    suspend fun executeActionLocally(toolName: String, params: JSONObject): ToolResult {
        val lower = toolName.lowercase().trim()

        // 1. Check if tool is enabled
        if (lower != "create_tool" && !ToolManager.isToolEnabled(lower)) {
            val toolObj = ToolManager.getTool(lower)
            if (toolObj != null && !toolObj.isEnabled) {
                return ToolResult("error", message = "Aksi '$toolName' dibatalkan karena tool ini sedang dinonaktifkan di pengaturan Tools.")
            }
        }

        // 2. Check AI Permission Mode
        val customTool = ToolManager.getTool(lower)
        val risk = customTool?.riskLevel ?: if (lower == "shell") com.example.model.ToolRiskLevel.HIGH else com.example.model.ToolRiskLevel.LOW
        val (allowed, reason) = ToolManager.checkPermission(lower, risk)
        if (!allowed) {
            return ToolResult("error", message = reason ?: "Izin eksekusi ditolak oleh sistem keamanan AI.")
        }

        // Check deletion approval if permission mode is not FULL_ACCESS
        if (ToolManager.isDeletionAction(lower, customTool?.command ?: "", params) && ToolManager.permissionMode.value != com.example.model.AiPermissionMode.FULL_ACCESS) {
            var isApproved: Boolean? = null
            ToolManager.requestDeletionApproval(
                title = "Konfirmasi Aksi Penghapusan AI",
                details = "AI meminta untuk mengeksekusi aksi penghapusan '$toolName' (parameter: $params).\n\nApakah Anda mengizinkan aksi penghapusan ini?",
                onConfirm = { isApproved = true },
                onDeny = { isApproved = false }
            )

            var waitCounter = 0
            while (isApproved == null && waitCounter < 300) {
                kotlinx.coroutines.delay(200)
                waitCounter++
            }

            if (isApproved != true) {
                return ToolResult("error", message = "❌ Ditolak oleh pengguna: Aksi penghapusan '$toolName' dibatalkan.")
            }
        }

        // 3. Special AI meta-tools
        if (lower == "create_tool" || lower == "register_tool") {
            val name = params.optString("name", params.optString("tool_name", "Tool Baru"))
            val desc = params.optString("description", "Dibuat otomatis oleh AI Assistant")
            val scriptType = params.optString("script_type", "shell")
            val cmd = params.optString("command", "")
            val schema = params.optString("parameters_schema", params.optString("params_schema", "{}"))

            if (cmd.isBlank() && scriptType.equals("shell", ignoreCase = true)) {
                return ToolResult("error", message = "Perintah (command) untuk tool baru tidak boleh kosong.")
            }

            val created = ToolManager.registerAiGeneratedTool(
                name = name,
                description = desc,
                scriptTypeStr = scriptType,
                command = cmd,
                paramsSchema = schema
            )
            return ToolResult(
                status = "ok",
                result = "✨ Berhasil membuat Tool baru: '${created.name}' (${created.id}) dengan tipe ${created.scriptType}. Tool ini sekarang aktif dan siap digunakan!"
            )
        }

        if (lower == "create_python_tool") {
            val filename = params.optString("filename", "custom_tool.py").replace("..", "").replace("/", "")
            val toolNamePy = params.optString("tool_name", params.optString("name", "custom_tool"))
            val descPy = params.optString("description", "Custom Python Tool")
            val codePy = params.optString("code", "")

            val context = com.example.JarvisApp.instance
            val scriptsDir = java.io.File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }
            val scriptFile = java.io.File(scriptsDir, filename)
            if (codePy.isNotBlank()) {
                scriptFile.writeText(codePy)
                scriptFile.setExecutable(true)
            }

            val runCmd = if (codePy.isNotBlank()) {
                "python3 \"${scriptFile.absolutePath}\""
            } else {
                "python3 -c \"import sys; print('Menjalankan $toolNamePy')\""
            }

            val created = ToolManager.registerAiGeneratedTool(
                name = toolNamePy,
                description = descPy,
                scriptTypeStr = "shell",
                command = runCmd,
                paramsSchema = params.optString("parameters_schema", "{}")
            )
            return ToolResult(
                status = "ok",
                result = "✨ Berhasil membuat Custom Tool Python '${created.name}' (${created.id})!\n\nScript tersimpan di: ${scriptFile.name} dan terdaftar sebagai tool aktif yang langsung dapat dieksekusi oleh JARVIS.\nPanggil tool ini dengan nama: '${created.name}'.\n⚠️ Catatan: eksekusi Python membutuhkan python3 di perangkat (Termux: pkg install python). Bila belum ada, tool akan memberikan panduan instalasi saat dipanggil."
            )
        }

        // If tool is in custom tools or standard registry
        if (customTool != null && customTool.scriptType != com.example.model.ToolScriptType.ACCESSIBILITY) {
            return ToolManager.executeCustomTool(customTool, params)
        }

        // Media analysis: decode gambar menjadi teks kaya (untuk AI non-vision)
        if (lower == "decode_image" || lower == "analyze_image") {
            val withOcr = params.optBoolean("with_ocr", params.optBoolean("ocr", true))
            val source = params.optString("source", params.optString("src", "")).trim().lowercase()
            val b64 = params.optString("base64", params.optString("image_base64", params.optString("image", "")))
            val filePath = params.optString("path", params.optString("file", ""))
            val uri = params.optString("uri", params.optString("content_uri", ""))
            return when {
                b64.isNotBlank() -> ImageDecodeManager.analyzeBase64(b64, withOcr)
                source == "last_screenshot" || source == "screenshot" -> ImageDecodeManager.analyzeLastScreenshot(withOcr)
                filePath.isNotBlank() -> ImageDecodeManager.analyzePath(filePath, withOcr)
                uri.isNotBlank() -> ImageDecodeManager.analyzeUri(com.example.JarvisApp.instance, uri, withOcr)
                else -> ToolResult("error", message = "Parameter gambar tidak ditemukan. Gunakan {\"source\":\"last_screenshot\"}, {\"base64\":\"...\"}, {\"path\":\"...\"}, atau {\"uri\":\"...\"}.")
            }
        }

        val service = JarvisAccessibilityService.instance
        return when (lower) {
            "open_app" -> {
                var pkg = params.optString("package_name", params.optString("app", params.optString("package", ""))).trim()
                // Auto-resolve popular app aliases
                val commonApps = mapOf(
                    "youtube" to "com.google.android.youtube",
                    "chrome" to "com.android.chrome",
                    "browser" to "com.android.chrome",
                    "whatsapp" to "com.whatsapp",
                    "wa" to "com.whatsapp",
                    "settings" to "com.android.settings",
                    "pengaturan" to "com.android.settings",
                    "maps" to "com.google.android.apps.maps",
                    "google maps" to "com.google.android.apps.maps",
                    "playstore" to "com.android.vending",
                    "play store" to "com.android.vending",
                    "camera" to "com.google.android.GoogleCamera",
                    "kamera" to "com.google.android.GoogleCamera",
                    "spotify" to "com.spotify.music",
                    "tiktok" to "com.zhiliaoapp.musically",
                    "instagram" to "com.instagram.android",
                    "ig" to "com.instagram.android",
                    "telegram" to "org.telegram.messenger",
                    "gmail" to "com.google.android.gm",
                    "photos" to "com.google.android.apps.photos",
                    "galeri" to "com.google.android.apps.photos"
                )
                if (commonApps.containsKey(pkg.lowercase())) {
                    pkg = commonApps[pkg.lowercase()] ?: pkg
                }
                if (service != null) service.openApp(pkg) else ToolResult("error", message = "Accessibility Service belum aktif. Mohon aktifkan di Pengaturan Aksesibilitas Android.")
            }
            "type_text", "send_text" -> {
                val text = params.optString("text", params.optString("value", params.optString("query", "")))
                val elemId = params.optString("element_id", params.optString("id", "")).ifEmpty { null }
                if (service != null) service.typeText(elemId, text) else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "tap", "click" -> {
                if (service == null) return ToolResult("error", message = "Accessibility Service belum aktif")
                if (params.has("x") && params.has("y")) {
                    service.tapCoordinates(params.getDouble("x").toFloat(), params.getDouble("y").toFloat())
                } else {
                    val targetId = params.optString("element_id", params.optString("id", params.optString("text", params.optString("label", ""))))
                    service.tapElement(targetId)
                }
            }
            "swipe", "scroll" -> {
                if (service == null) return ToolResult("error", message = "Accessibility Service belum aktif")
                service.swipeCoordinates(
                    params.optDouble("x1", 500.0).toFloat(),
                    params.optDouble("y1", 1500.0).toFloat(),
                    params.optDouble("x2", 500.0).toFloat(),
                    params.optDouble("y2", 500.0).toFloat(),
                    params.optLong("duration_ms", 300L)
                )
            }
            "press_key", "press_button" -> {
                val key = params.optString("keycode", params.optString("action", params.optString("key", "BACK")))
                if (service != null) service.pressKey(key) else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "read_screen", "inspect_screen" -> {
                if (service != null) {
                    // Opsi filter: hanya elemen clickable (mengurangi noise utk agent) + limit jumlah output
                    val filterClickable = params.optBoolean("filter_clickable", false)
                    val limit = params.optInt("limit", 35).coerceIn(1, 100)
                    var elements = service.readScreenElements()
                    if (filterClickable) elements = elements.filter { it.isClickable }
                    if (elements.isEmpty()) {
                        ToolResult("ok", result = "Layar saat ini kosong atau tidak ada elemen UI${if (filterClickable) " interaktif (filter_clickable=true)" else ""} yang terdeteksi.")
                    } else {
                        val formatted = elements.take(limit).mapIndexed { idx, el ->
                            val idStr = if (el.viewId.isNotBlank()) el.viewId else el.id
                            val labels = listOf(el.text, el.contentDescription).filter { it.isNotBlank() }
                            val labelDesc = if (labels.isNotEmpty()) "Teks: \"${labels.joinToString(" / ")}\"" else "Tanpa label"
                            val type = el.className.substringAfterLast('.')
                            val flags = mutableListOf<String>()
                            if (el.isClickable) flags.add("Clickable")
                            if (el.isEditable) flags.add("Editable")
                            if (el.isScrollable) flags.add("Scrollable")
                            val flagInfo = if (flags.isNotEmpty()) "[${flags.joinToString(", ")}]" else ""
                            "• #$idx ID: '$idStr' | $labelDesc | Tipe: $type | Posisi: (${el.bounds.centerX}, ${el.bounds.centerY}) $flagInfo"
                        }.joinToString("\n")
                        val extraCount = if (elements.size > limit) "\n... (+${elements.size - limit} elemen lainnya)" else ""
                        ToolResult("ok", result = "📋 Tampilan Layar Saat Ini (${elements.size} elemen terdeteksi):\n$formatted$extraCount")
                    }
                } else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "screenshot" -> {
                val (base64, errorMsg) = ScreenshotManager.captureBase64(com.example.JarvisApp.instance)
                if (base64 != null) {
                    ImageDecodeManager.rememberScreenshot(base64)
                    ToolResult(
                        "ok",
                        result = "Tangkapan layar berhasil diambil (${base64.length / 1024} KB). Gunakan tool decode_image dengan {\"source\":\"last_screenshot\"} untuk membaca isinya sebagai teks.",
                        extra = mapOf("screenshot_b64" to base64)
                    )
                } else {
                    ToolResult("error", message = errorMsg ?: "Gagal mengambil tangkapan layar. Pastikan Screen Share atau Accessibility aktif.")
                }
            }
            "battery", "get_battery" -> {
                val t = com.example.JarvisApp.repository.telemetry.value
                val status = if (t.isCharging) "Sedang mengisi daya" else "Tidak mengisi daya"
                ToolResult("ok", result = "Status Baterai: ${t.batteryLevel}% ($status)")
            }
            "get_storage" -> {
                try {
                    val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                    val totalBytes = stat.blockCountLong * stat.blockSizeLong
                    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                    val usedBytes = totalBytes - freeBytes
                    val totalGb = String.format(java.util.Locale.US, "%.2f", totalBytes / (1024.0 * 1024.0 * 1024.0))
                    val usedGb = String.format(java.util.Locale.US, "%.2f", usedBytes / (1024.0 * 1024.0 * 1024.0))
                    val freeGb = String.format(java.util.Locale.US, "%.2f", freeBytes / (1024.0 * 1024.0 * 1024.0))
                    ToolResult("ok", result = "📊 Informasi Penyimpanan HP:\n- Total: $totalGb GB\n- Digunakan: $usedGb GB\n- Sisa (Free): $freeGb GB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca storage: ${e.message}")
                }
            }
            "cek_ram" -> {
                try {
                    val actManager = com.example.JarvisApp.instance.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    val totalMb = memInfo.totalMem / (1024 * 1024)
                    val availMb = memInfo.availMem / (1024 * 1024)
                    val usedMb = totalMb - availMb
                    val totalGb = String.format(java.util.Locale.US, "%.1f", totalMb / 1024.0)
                    ToolResult("ok", result = "⚡ Informasi RAM HP:\n- Total: $totalMb MB ($totalGb GB)\n- Tersedia: $availMb MB\n- Terpakai: $usedMb MB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca RAM: ${e.message}")
                }
            }
            "shell", "termux_command", "terminal" -> {
                val cmd = params.optString("command", "")
                executeLocalTerminalCommand(cmd)
            }
            "termux_service", "service" -> ToolManager.executeCustomLogic(com.example.JarvisApp.instance, "termux_service", params)
            "termux_api", "termux-api" -> ToolManager.executeCustomLogic(com.example.JarvisApp.instance, "termux_api", params)
            "termux_pkg", "pkg" -> ToolManager.executeCustomLogic(com.example.JarvisApp.instance, "termux_pkg", params)
            "termux_python", "python" -> ToolManager.executeCustomLogic(com.example.JarvisApp.instance, "termux_python", params)
            "termux_file", "file_op" -> ToolManager.executeCustomLogic(com.example.JarvisApp.instance, "termux_file", params)
            else -> {
                // Check if it matches any registered custom tool
                if (customTool != null) {
                    ToolManager.executeCustomTool(customTool, params)
                } else {
                    ToolResult("error", message = "Perintah $toolName tidak dikenal")
                }
            }
        }
    }

    private fun executeLocalTerminalCommand(cmd: String): ToolResult {
        if (cmd.isBlank()) {
            return ToolResult("error", message = "Perintah terminal tidak boleh kosong.")
        }

        // 1. Try via AdbShizukuManager
        val adbRes = AdbShizukuManager.executeShell(cmd)
        if (adbRes.status == "ok") return adbRes

        // 2. Direct local shell execution via process runner
        return try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            val reader = process.inputStream.bufferedReader()
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
                if (output.length > 8000) {
                    output.appendLine("... [Output terminal dipotong untuk performa]")
                    break
                }
            }
            val completed = process.waitFor(15, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                ToolResult("error", message = "Perintah terminal timeout setelah 15 detik")
            } else {
                val exitCode = process.exitValue()
                val text = output.toString().trim()
                val finalRes = if (text.isNotBlank()) text else "(Perintah terminal sukses dieksekusi, exit code: $exitCode)"
                ToolResult(if (exitCode == 0) "ok" else "error", result = finalRes)
            }
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal menjalankan terminal command: ${e.message}")
        }
    }
}
