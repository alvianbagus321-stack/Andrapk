package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiPreset(
    val id: String,
    val providerName: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val isGeminiNative: Boolean = false,
    val apiKeyPlaceholder: String = "Masukkan API Key",
    val description: String = ""
)

data class AiConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val providerLabel: String = "Google Gemini",
    val maxAgentLoops: Int = 20
)

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val responsePreview: String? = null
)

/**
 * Persistent Manager for AI Model, Base URL / Endpoint, and API Key configuration.
 * Allows users to easily switch between Google Gemini, Groq, OpenRouter, DeepSeek,
 * Local Ollama / LM Studio, OpenAI, or any custom OpenAI-compatible endpoint.
 */
object AiConfigManager {

    private const val TAG = "AiConfigManager"
    private const val PREFS_NAME = "jarvis_ai_config_prefs"

    private const val KEY_API_KEY = "key_api_key"
    private const val KEY_BASE_URL = "key_base_url"
    private const val KEY_MODEL_NAME = "key_model_name"
    private const val KEY_PROVIDER_LABEL = "key_provider_label"
    private const val KEY_MAX_AGENT_LOOPS = "key_max_agent_loops"

    const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"

    val PRESETS = listOf(
        AiPreset(
            id = "gemini_flash",
            providerName = "Google Gemini",
            displayName = "Gemini 3.5 Flash (Default)",
            baseUrl = "https://generativelanguage.googleapis.com",
            defaultModel = "gemini-3.5-flash",
            isGeminiNative = true,
            apiKeyPlaceholder = "AIzaSy...",
            description = "Model resmi Google, kecepatan tinggi & penalaran tajam."
        ),
        AiPreset(
            id = "gemini_2_5_flash",
            providerName = "Google Gemini",
            displayName = "Gemini 2.5 Flash",
            baseUrl = "https://generativelanguage.googleapis.com",
            defaultModel = "gemini-2.5-flash",
            isGeminiNative = true,
            apiKeyPlaceholder = "AIzaSy...",
            description = "Performa seimbang dan latensi sangat rendah."
        ),
        AiPreset(
            id = "groq_llama",
            providerName = "Groq",
            displayName = "Groq (Llama 3.3 70B)",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            isGeminiNative = false,
            apiKeyPlaceholder = "gsk_...",
            description = "Inferensi LPU super cepat (ratusan token per detik)."
        ),
        AiPreset(
            id = "openrouter",
            providerName = "OpenRouter",
            displayName = "OpenRouter (Multi-Model)",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "google/gemini-2.5-flash",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-or-v1-...",
            description = "Akses ratusan model AI (Claude, DeepSeek, Llama, Gemini) via 1 API Key."
        ),
        AiPreset(
            id = "deepseek",
            providerName = "DeepSeek",
            displayName = "DeepSeek (deepseek-chat)",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-chat",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-...",
            description = "Model DeepSeek V3 resmi berbiaya hemat & kemampuan coding/agentik tinggi."
        ),
        AiPreset(
            id = "deepseek_reasoner",
            providerName = "DeepSeek",
            displayName = "DeepSeek R1 (Reasoner)",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-reasoner",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-...",
            description = "Model penalaran mendalam (Chain of Thought) untuk tugas rumit."
        ),
        AiPreset(
            id = "ollama_local",
            providerName = "Ollama Local",
            displayName = "Ollama Local (PC / LAN)",
            baseUrl = "http://10.0.2.2:11434/v1",
            defaultModel = "llama3",
            isGeminiNative = false,
            apiKeyPlaceholder = "ollama (opsional)",
            description = "Model lokal berjalan di PC Anda (10.0.2.2 untuk emulator, atau IP LAN HP)."
        ),
        AiPreset(
            id = "lmstudio_local",
            providerName = "LM Studio",
            displayName = "LM Studio Local (PC / LAN)",
            baseUrl = "http://10.0.2.2:1234/v1",
            defaultModel = "local-model",
            isGeminiNative = false,
            apiKeyPlaceholder = "lm-studio (opsional)",
            description = "Server lokal LM Studio untuk menjalankan model GGUF offline."
        ),
        AiPreset(
            id = "openai_standard",
            providerName = "OpenAI",
            displayName = "OpenAI (GPT-4o Mini)",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-proj-...",
            description = "Model GPT-4o Mini standar OpenAI."
        ),
        AiPreset(
            id = "qwen3_4b_local",
            providerName = "Lokal (Termux)",
            displayName = "Qwen3 4B (Offline di HP)",
            baseUrl = "http://127.0.0.1:8080/v1",
            defaultModel = "qwen3-4b",
            isGeminiNative = false,
            apiKeyPlaceholder = "kosongkan (lokal)",
            description = "Qwen3 4B GGUF berjalan 100% offline di HP via llama-server (Termux). Tanpa API key, tanpa internet. Lihat panduan di tombol (?) kartu MCP."
        ),
        AiPreset(
            id = "custom_openai",
            providerName = "Custom / Self-Hosted",
            displayName = "Custom Endpoint Lainnya",
            baseUrl = "https://api.together.xyz/v1",
            defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            isGeminiNative = false,
            apiKeyPlaceholder = "API Key kustom",
            description = "Gunakan endpoint OpenAI-compatible apa pun (vLLM, Together, Mistral, dll)."
        )
    )

    private const val KEY_CUSTOM_MODELS = "key_custom_models"

    private var prefs: SharedPreferences? = null

    // ===== Model impor milik pengguna (Qwen, Llama GGUF, vLLM, apapun) =====
    private val _customModels = MutableStateFlow<List<AiPreset>>(emptyList())
    val customModels: StateFlow<List<AiPreset>> = _customModels.asStateFlow()

    private val _config = MutableStateFlow(
        AiConfig(
            apiKey = "",
            baseUrl = DEFAULT_GEMINI_BASE_URL,
            modelName = DEFAULT_GEMINI_MODEL,
            providerLabel = "Google Gemini"
        )
    )
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadConfig()
            loadCustomModels()
        }
    }

    /** Semua pilihan model: preset bawaan + model impor milik pengguna. */
    fun allModels(): List<AiPreset> = PRESETS + _customModels.value

    /**
     * Impor model LLM sendiri (mis. Qwen3 4B via llama.cpp/vLLM/Ollama lokal,
     * atau endpoint OpenAI-compatible apa pun) dan simpan permanen.
     */
    fun addCustomModel(
        displayName: String,
        baseUrl: String,
        modelId: String,
        providerLabel: String = "Model Saya"
    ): AiPreset {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val preset = AiPreset(
            id = "user_" + System.currentTimeMillis(),
            providerName = providerLabel.trim().ifBlank { "Model Saya" },
            displayName = displayName.trim().ifBlank { modelId.trim() },
            baseUrl = cleanUrl,
            defaultModel = modelId.trim(),
            isGeminiNative = isGeminiEndpoint(cleanUrl),
            apiKeyPlaceholder = "API key (opsional)",
            description = "Model impor kustom: $modelId di $cleanUrl"
        )
        val updated = _customModels.value + preset
        _customModels.value = updated
        persistCustomModels(updated)
        Log.i(TAG, "Custom model ditambahkan: ${preset.displayName} (${preset.id})")
        return preset
    }

    fun removeCustomModel(id: String) {
        val updated = _customModels.value.filterNot { it.id == id }
        _customModels.value = updated
        persistCustomModels(updated)
    }

    private fun persistCustomModels(list: List<AiPreset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("providerName", p.providerName)
                put("displayName", p.displayName)
                put("baseUrl", p.baseUrl)
                put("defaultModel", p.defaultModel)
                put("isGeminiNative", p.isGeminiNative)
                put("apiKeyPlaceholder", p.apiKeyPlaceholder)
                put("description", p.description)
            })
        }
        prefs?.edit()?.putString(KEY_CUSTOM_MODELS, arr.toString())?.apply()
    }

    private fun loadCustomModels() {
        val str = prefs?.getString(KEY_CUSTOM_MODELS, null) ?: return
        try {
            val arr = JSONArray(str)
            val list = mutableListOf<AiPreset>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    AiPreset(
                        id = o.optString("id"),
                        providerName = o.optString("providerName", "Model Saya"),
                        displayName = o.optString("displayName"),
                        baseUrl = o.optString("baseUrl"),
                        defaultModel = o.optString("defaultModel"),
                        isGeminiNative = o.optBoolean("isGeminiNative", false),
                        apiKeyPlaceholder = o.optString("apiKeyPlaceholder", "API key (opsional)"),
                        description = o.optString("description")
                    )
                )
            }
            _customModels.value = list
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memuat custom models: ${e.message}")
        }
    }

    private fun loadConfig() {
        val sp = prefs ?: return
        val savedKey = sp.getString(KEY_API_KEY, "") ?: ""
        val savedUrl = sp.getString(KEY_BASE_URL, DEFAULT_GEMINI_BASE_URL) ?: DEFAULT_GEMINI_BASE_URL
        val savedModel = sp.getString(KEY_MODEL_NAME, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        val savedProvider = sp.getString(KEY_PROVIDER_LABEL, "Google Gemini") ?: "Google Gemini"
        val savedMaxLoops = sp.getInt(KEY_MAX_AGENT_LOOPS, 20).coerceIn(0, 100)

        _config.value = AiConfig(
            apiKey = savedKey,
            baseUrl = savedUrl,
            modelName = savedModel,
            providerLabel = savedProvider,
            maxAgentLoops = savedMaxLoops
        )
    }

    fun saveConfig(
        apiKey: String,
        baseUrl: String,
        modelName: String,
        providerLabel: String? = null,
        maxAgentLoops: Int = _config.value.maxAgentLoops
    ) {
        val cleanKey = apiKey.trim()
        val cleanUrl = baseUrl.trim().ifEmpty { DEFAULT_GEMINI_BASE_URL }
        val cleanModel = modelName.trim().ifEmpty { DEFAULT_GEMINI_MODEL }
        val determinedProvider = providerLabel ?: determineProviderName(cleanUrl, cleanModel)
        val validMaxLoops = maxAgentLoops.coerceIn(0, 100)

        _config.value = AiConfig(
            apiKey = cleanKey,
            baseUrl = cleanUrl,
            modelName = cleanModel,
            providerLabel = determinedProvider,
            maxAgentLoops = validMaxLoops
        )

        prefs?.edit()?.apply {
            putString(KEY_API_KEY, cleanKey)
            putString(KEY_BASE_URL, cleanUrl)
            putString(KEY_MODEL_NAME, cleanModel)
            putString(KEY_PROVIDER_LABEL, determinedProvider)
            putInt(KEY_MAX_AGENT_LOOPS, validMaxLoops)
            apply()
        }
        Log.i(TAG, "AI Config updated: provider=$determinedProvider, model=$cleanModel, url=$cleanUrl")
    }

    fun saveMaxAgentLoops(maxLoops: Int) {
        val valid = maxLoops.coerceIn(0, 100)
        _config.value = _config.value.copy(maxAgentLoops = valid)
        prefs?.edit()?.putInt(KEY_MAX_AGENT_LOOPS, valid)?.apply()
    }

    fun applyPreset(preset: AiPreset) {
        val currentKey = _config.value.apiKey
        saveConfig(
            apiKey = currentKey,
            baseUrl = preset.baseUrl,
            modelName = preset.defaultModel,
            providerLabel = preset.providerName
        )
    }

    fun getEffectiveApiKey(): String {
        val configuredKey = _config.value.apiKey.trim()
        if (configuredKey.isNotEmpty() && configuredKey != "MY_GEMINI_API_KEY") {
            return configuredKey
        }
        return try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun isGeminiEndpoint(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("generativelanguage.googleapis.com") || lower.contains("googleapis.com")
    }

    private fun determineProviderName(url: String, model: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("generativelanguage.googleapis.com") -> "Google Gemini"
            lower.contains("api.groq.com") -> "Groq"
            lower.contains("openrouter.ai") -> "OpenRouter"
            lower.contains("deepseek.com") -> "DeepSeek"
            lower.contains("11434") || lower.contains("ollama") -> "Ollama Local"
            lower.contains("1234") || lower.contains("lmstudio") -> "LM Studio"
            lower.contains("api.openai.com") -> "OpenAI"
            lower.contains("together.xyz") -> "Together AI"
            else -> "Custom ($model)"
        }
    }

    /**
     * Sends a lightweight ping test to verify endpoint reachability, API key validity, and latency.
     */
    suspend fun testEndpointConnection(
        apiKey: String,
        baseUrl: String,
        modelName: String
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cleanKey = apiKey.trim().ifEmpty { getEffectiveApiKey() }
        val cleanUrl = baseUrl.trim().ifEmpty { DEFAULT_GEMINI_BASE_URL }.trimEnd('/')
        val cleanModel = modelName.trim().ifEmpty { DEFAULT_GEMINI_MODEL }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val isGemini = isGeminiEndpoint(cleanUrl)

        try {
            val request = if (isGemini) {
                val endpoint = "$cleanUrl/v1beta/models/$cleanModel:generateContent?key=$cleanKey"
                val bodyJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", "JARVIS Ping Test. Balas hanya kata 'PONG'.")))
                        }
                    ))
                    put("generationConfig", JSONObject().apply {
                        put("maxOutputTokens", 20)
                    })
                }
                val reqBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                Request.Builder().url(endpoint).post(reqBody).build()
            } else {
                val endpoint = if (cleanUrl.endsWith("/chat/completions")) {
                    cleanUrl
                } else if (cleanUrl.endsWith("/v1")) {
                    "$cleanUrl/chat/completions"
                } else {
                    "$cleanUrl/chat/completions"
                }

                val bodyJson = JSONObject().apply {
                    put("model", cleanModel)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", "JARVIS Ping Test. Balas hanya kata 'PONG'.")
                        }
                    ))
                    put("max_tokens", 20)
                }
                val reqBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val builder = Request.Builder()
                    .url(endpoint)
                    .post(reqBody)
                if (cleanKey.isNotEmpty()) {
                    builder.addHeader("Authorization", "Bearer $cleanKey")
                }
                builder.build()
            }

            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            val bodyString = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ConnectionTestResult(
                    success = true,
                    latencyMs = latency,
                    message = "Koneksi berhasil! (Status: ${response.code} OK, $latency ms)",
                    responsePreview = if (bodyString.length > 200) bodyString.take(200) + "..." else bodyString
                )
            } else {
                ConnectionTestResult(
                    success = false,
                    latencyMs = latency,
                    message = "Gagal (${response.code}): ${bodyString.take(180)}",
                    responsePreview = bodyString
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ConnectionTestResult(
                success = false,
                latencyMs = latency,
                message = "Gagal terhubung: ${e.message}",
                responsePreview = null
            )
        }
    }
}
