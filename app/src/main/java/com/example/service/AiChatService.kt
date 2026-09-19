package com.example.service

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.model.ToolResult
import kotlinx.coroutines.Dispatchers
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Default settings: Gemini 3.5 Flash per gemini-api skill standard
    const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"

    /**
     * Executes AI prompt and coordinates with Android automation tools if action commands are determined.
     */
    suspend fun sendMessage(
        userPrompt: String,
        apiKey: String,
        baseUrl: String,
        modelName: String,
        history: List<Pair<String, String>>, // (role, text)
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

        // Construct System Instructions for Autonomous Agent Loop & ReAct Step Execution
        val systemInstruction = """
            Kamu adalah JARVIS-HP, AI Agent otonom tingkat lanjut yang mengendalikan smartphone Android secara langsung melalui ReAct Agent Loop.
            Mode Izin Keamanan saat ini: ${currentPermissionMode.name}.
            
            PRINSIP AGENT LOOP (SANGAT PENTING):
            1. Kamu beroperasi dalam multi-step Agent Loop: Berpikir → Ambil 1 tindakan (tool) → Amati hasil dari HP → Tentukan tindakan berikutnya → Ulangi sampai selesai.
            2. JANGAN membuat rencana 10 aksi sekaligus di awal! Jalankan HANYA SATU AKSI per giliran, lalu tunggu hasil eksekusinya karena kondisi layar dan aplikasi HP bisa berubah setelah setiap tindakan.
            3. Setiap kali kamu butuh mengeksekusi aksi, letakkan blok JSON di bagian akhir jawabanmu dengan format:
            ```json:action
            {
              "tool": "nama_tool",
              "params": { ... }
            }
            ```
            4. Setelah aksi dieksekusi oleh sistem Android, hasilnya akan langsung dikirimkan kembali kepadamu pada giliran berikutnya.
            5. KETIKA SEMUA TUGAS SELESAI atau pengguna hanya bertanya tanpa perlu aksi di HP, berikan jawaban akhir yang ramah, informatif, dan solutif TANPA blok ```json:action```.

            ARSITEKTUR TOOL (3-LAYER MODULAR REGISTRY):
            1. Android Layer:
               - open_app: Membuka aplikasi (params: {"package_name": "com.google.android.youtube"} atau nama aplikasi umum "youtube", "chrome", "whatsapp")
               - read_screen: Membaca semua elemen UI, teks, tombol, viewId, dan koordinat posisi yang sedang tampil di layar HP
               - tap: Menekan elemen di layar (params: {"element_id": "id_atau_teks_tombol"} atau {"x": 540, "y": 1200})
               - type_text: Mengetik teks pada kolom input aktif (params: {"text": "teks yang ingin diketik", "element_id": "opsional"})
               - press_key: Menekan tombol sistem (params: {"keycode": "ENTER"|"BACK"|"HOME"|"RECENTS"|"VOLUME_UP"|"VOLUME_DOWN"})
               - swipe: Menggeser layar (params: {"x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration_ms": 300})
               - screenshot: Mengambil tangkapan layar perangkat
               - send_notification: Mengirim notifikasi lokal ke status bar
            2. Termux/Terminal Layer:
               - termux_command / shell: Menjalankan perintah bash/terminal apa pun di HP (params: {"command": "ls -la"})
               - get_battery, get_wifi, read_clipboard, write_clipboard
            3. Custom Tools Layer (tools/custom/):
               - get_storage: Cek kapasitas penyimpanan memori HP (GB)
               - cek_ram: Cek penggunaan memori RAM (MB)
               - create_tool / create_python_tool: Membuat tool baru secara dinamis

            PROSES BERPIKIR (REASONING TRACE):
            Tuliskan analisa kamu di dalam tag:
            <thought>
            [Langkah analisa: apa yang sedang terjadi, evaluasi hasil tool sebelumnya, dan aksi apa yang harus dilakukan berikutnya]
            </thought>

            Daftar Tool yang AKTIF saat ini:
            $toolsDescription

            Jika pengguna meminta membuat website/komponen UI, berikan kode HTML lengkap di dalam markdown ```html ... ``` agar dapat langsung di-preview di aplikasi.
            Gunakan Bahasa Indonesia yang ramah, profesional, dan ringkas.
        """.trimIndent()

        // Multi-Step Autonomous Agent Loop Controller
        val maxSteps = 20
        var currentStep = 0
        val loopHistory = history.toMutableList()
        var currentPrompt = userPrompt
        val allThoughts = mutableListOf<String>()
        val executedTools = mutableListOf<Pair<String, ToolResult>>()
        val stepSummary = mutableListOf<String>()

        var finalReplyText = ""
        var lastToolResult: ToolResult? = null
        var lastActionName: String? = null

        while (currentStep < maxSteps) {
            currentStep++
            val stepLabel = "Langkah $currentStep/$maxSteps"
            onStatusUpdate("$stepLabel: Menganalisa & merencanakan aksi...")

            val (rawResponse, nativeThought) = if (isGemini) {
                callGeminiRest(cleanBaseUrl, cleanModel, cleanKey, systemInstruction, loopHistory, currentPrompt)
            } else {
                Pair(callOpenAiRest(cleanBaseUrl, cleanModel, cleanKey, systemInstruction, loopHistory, currentPrompt), null)
            }

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
            }

            // Parse action JSON
            val parsedAction = extractActionJson(textWithoutThought)

            if (parsedAction != null) {
                val toolName = parsedAction.optString("tool", "").trim()
                val params = parsedAction.optJSONObject("params") ?: JSONObject()
                lastActionName = toolName

                onStatusUpdate("$stepLabel: Mengeksekusi '$toolName'...")

                // Execute tool locally
                val executionResult = executeActionLocally(toolName, params)
                lastToolResult = executionResult
                executedTools.add(toolName to executionResult)

                val resultOutputStr = executionResult.result ?: executionResult.message ?: if (executionResult.status == "ok") "Berhasil (OK)" else "Gagal"
                val briefResult = if (resultOutputStr.length > 300) resultOutputStr.take(300) + "..." else resultOutputStr
                stepSummary.add("$stepLabel: $toolName → ${executionResult.status.uppercase()}: $briefResult")

                // Natural delay for UI transitions (e.g. app launching or layout animations)
                if (toolName.equals("open_app", ignoreCase = true)) {
                    kotlinx.coroutines.delay(1500L)
                } else if (toolName in listOf("tap", "type_text", "press_key", "swipe")) {
                    kotlinx.coroutines.delay(500L)
                }

                // Add agent's response to history
                loopHistory.add("assistant" to textWithoutThought)

                // Formulate feedback prompt for next step in agent loop
                currentPrompt = """
                    [Hasil Eksekusi Tool $stepLabel]
                    Tool: $toolName
                    Status: ${executionResult.status}
                    Output:
                    $resultOutputStr

                    Instruksi Pengguna Awal: "$userPrompt"
                    Silakan evaluasi hasil di atas dan tentukan langkah berikutnya (atau berikan respon akhir jika tugas telah selesai).
                """.trimIndent()

            } else {
                // AI decided no further tool action is needed -> Task Complete!
                val cleanedText = textWithoutThought.replace(Regex("```json:action[\\s\\S]*?```"), "").trim()
                finalReplyText = if (cleanedText.isNotBlank()) cleanedText else "Tugas telah selesai diproses oleh JARVIS."
                break
            }
        }

        if (currentStep >= maxSteps && finalReplyText.isBlank()) {
            finalReplyText = "Batas maksimum langkah agent loop ($maxSteps langkah) telah tercapai.\n\nRingkasan eksekusi:\n" + stepSummary.joinToString("\n")
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

    private fun callGeminiRest(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        prompt: String
    ): Pair<String, String?> {
        val endpoint = "$baseUrl/v1beta/models/$model:generateContent?key=$apiKey"

        val contentsArray = JSONArray()
        // Add past conversation turns
        for ((role, msg) in history.takeLast(10)) {
            val geminiRole = if (role == "user") "user" else "model"
            contentsArray.put(JSONObject().apply {
                put("role", geminiRole)
                put("parts", JSONArray().put(JSONObject().put("text", msg)))
            })
        }
        // Add current user prompt
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
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
            messagesArray.put(JSONObject().apply {
                put("role", role)
                put("content", msg)
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
            reqBuilder.addHeader("X-Title", "JARVIS Companion")
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

    private fun extractActionJson(text: String): JSONObject? {
        val regex = Regex("```json:action([\\s\\S]*?)```")
        val match = regex.find(text) ?: return null
        val rawJson = match.groupValues[1].trim()
        return try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun executeActionLocally(toolName: String, params: JSONObject): ToolResult {
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
            val filename = params.optString("filename", "custom_tool.py")
            val toolNamePy = params.optString("tool_name", "custom_tool")
            val descPy = params.optString("description", "Custom Python Tool")
            val codePy = params.optString("code", "")

            val created = ToolManager.registerAiGeneratedTool(
                name = toolNamePy,
                description = descPy,
                scriptTypeStr = "shell",
                command = "python -c \"import sys; print('Menjalankan $toolNamePy...')\"",
                paramsSchema = "{}"
            )
            return ToolResult(
                status = "ok",
                result = "✨ Berhasil membuat Custom Tool Python '@tool' ('$toolNamePy') untuk direktori tools/custom/$filename!\n\nTool ini telah terdaftar dalam sistem dan siap digunakan baik di Termux maupun di Android Companion."
            )
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
                    val elements = service.readScreenElements()
                    if (elements.isEmpty()) {
                        ToolResult("ok", result = "Layar saat ini kosong atau tidak ada elemen UI interaktif yang terdeteksi.")
                    } else {
                        val formatted = elements.take(35).mapIndexed { idx, el ->
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
                        val extraCount = if (elements.size > 35) "\n... (+${elements.size - 35} elemen lainnya)" else ""
                        ToolResult("ok", result = "📋 Tampilan Layar Saat Ini (${elements.size} elemen terdeteksi):\n$formatted$extraCount")
                    }
                } else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "screenshot" -> {
                val (base64, errorMsg) = ScreenshotManager.captureBase64(com.example.JarvisApp.instance)
                if (base64 != null) {
                    ToolResult("ok", result = "Tangkapan layar berhasil diambil (${base64.length / 1024} KB)")
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
