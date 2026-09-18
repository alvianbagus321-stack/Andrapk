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
    ): Pair<String, ToolResult?> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim().ifEmpty {
            try {
                BuildConfig.GEMINI_API_KEY.trim()
            } catch (_: Exception) {
                ""
            }
        }
        val cleanBaseUrl = baseUrl.trim().ifEmpty { DEFAULT_GEMINI_BASE_URL }.trimEnd('/')
        val cleanModel = modelName.trim().ifEmpty { DEFAULT_GEMINI_MODEL }

        if (cleanKey.isEmpty() || cleanKey == "MY_GEMINI_API_KEY") {
            return@withContext Pair(
                "⚠️ API Key belum disetel. Silakan masukkan Gemini API Key Anda pada pengaturan di bagian atas jendela chat atau melalui AI Studio Secrets panel.",
                null
            )
        }

        onStatusUpdate("Menghubungi AI ($cleanModel)...")

        // Construct System Instructions for Autonomous Device Control
        val systemInstruction = """
            Kamu adalah JARVIS-HP, AI Assistant cerdas yang dapat mengontrol smartphone Android pengguna secara langsung melalui Companion Engine.
            Pengguna berbicara denganmu melalui Chat Window di dalam aplikasi.
            
            Jika pengguna meminta tindakan otomasi pada perangkat (misalnya: buka aplikasi, ketik teks, tap tombol, swipe, cek baterai, screenshot), sertakan blok tindakan JSON di bagian akhir jawabanmu dengan format berikut:
            ```json:action
            {
              "tool": "open_app|type_text|tap|swipe|press_key|read_screen|battery|shell",
              "params": { ... }
            }
            ```
            
            Parameter tool:
            - open_app: {"package_name": "youtube" / "chrome" / "settings" / "com.example.app"}
            - type_text: {"text": "isi teks", "element_id": "opsional_id"}
            - tap: {"x": 500, "y": 800} atau {"element_id": "nama_atau_id_tombol"}
            - swipe: {"x1": 500, "y1": 1500, "x2": 500, "y2": 500}
            - press_key: {"keycode": "BACK" / "HOME" / "RECENTS"}
            - read_screen: {}
            - battery: {}
            - shell: {"command": "perintah shell"}

            Jawab ramah, ringkas, solutif, dan informatif dalam Bahasa Indonesia.
        """.trimIndent()

        // Distinguish Gemini REST API vs OpenAI format
        val isGemini = cleanBaseUrl.contains("generativelanguage.googleapis.com") || cleanModel.contains("gemini")

        val rawResponse = if (isGemini) {
            callGeminiRest(cleanBaseUrl, cleanModel, cleanKey, systemInstruction, history, userPrompt)
        } else {
            callOpenAiRest(cleanBaseUrl, cleanModel, cleanKey, systemInstruction, history, userPrompt)
        }

        // Parse possible action json
        val parsedAction = extractActionJson(rawResponse)
        if (parsedAction != null) {
            val toolName = parsedAction.optString("tool", "")
            val params = parsedAction.optJSONObject("params") ?: JSONObject()
            onStatusUpdate("Mengeksekusi aksi $toolName pada Android...")

            val executionResult = executeActionLocally(toolName, params)
            val cleanedText = rawResponse.replace(Regex("```json:action[\\s\\S]*?```"), "").trim()
            Pair(cleanedText, executionResult)
        } else {
            Pair(rawResponse, null)
        }
    }

    private fun callGeminiRest(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemInstruction: String,
        history: List<Pair<String, String>>,
        prompt: String
    ): String {
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
                return "Gagal menghubungi Gemini API (${response.code}): $bodyString"
            }
            val json = JSONObject(bodyString)
            val candidates = json.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text", "")
            if (!text.isNullOrBlank()) text else "Tidak ada teks balasan dari Gemini."
        } catch (e: Exception) {
            Log.e(TAG, "Gemini REST error: ${e.message}", e)
            "Koneksi gagal: ${e.message}"
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
        val endpoint = "$baseUrl/chat/completions"

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
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return "Gagal memanggil API (${response.code}): $bodyString"
            }
            val json = JSONObject(bodyString)
            val choices = json.optJSONArray("choices")
            val message = choices?.optJSONObject(0)?.optJSONObject("message")
            message?.optString("content", "") ?: "Tidak ada balasan dari API."
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
        val service = JarvisAccessibilityService.instance
        return when (toolName.lowercase()) {
            "open_app" -> {
                val pkg = params.optString("package_name", params.optString("app", ""))
                if (service != null) service.openApp(pkg) else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "type_text", "send_text" -> {
                val text = params.optString("text", params.optString("value", ""))
                val elemId = params.optString("element_id", "").ifEmpty { null }
                if (service != null) service.typeText(elemId, text) else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "tap" -> {
                if (service == null) return ToolResult("error", message = "Accessibility Service belum aktif")
                if (params.has("x") && params.has("y")) {
                    service.tapCoordinates(params.getDouble("x").toFloat(), params.getDouble("y").toFloat())
                } else {
                    service.tapElement(params.optString("element_id", params.optString("id", "")))
                }
            }
            "swipe" -> {
                if (service == null) return ToolResult("error", message = "Accessibility Service belum aktif")
                service.swipeCoordinates(
                    params.optDouble("x1", 500.0).toFloat(),
                    params.optDouble("y1", 1500.0).toFloat(),
                    params.optDouble("x2", 500.0).toFloat(),
                    params.optDouble("y2", 500.0).toFloat(),
                    params.optLong("duration_ms", 300L)
                )
            }
            "press_key" -> {
                val key = params.optString("keycode", params.optString("action", "BACK"))
                if (service != null) service.pressKey(key) else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "read_screen" -> {
                if (service != null) {
                    val elements = service.readScreenElements()
                    ToolResult("ok", result = "Berhasil membaca ${elements.size} elemen UI dari layar")
                } else ToolResult("error", message = "Accessibility Service belum aktif")
            }
            "shell" -> {
                val cmd = params.optString("command", "")
                AdbShizukuManager.executeShell(cmd)
            }
            else -> ToolResult("error", message = "Perintah $toolName tidak dikenal")
        }
    }
}
