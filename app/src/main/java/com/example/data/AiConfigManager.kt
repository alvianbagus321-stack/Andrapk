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
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AiPreset(
    val id: String,
    val providerName: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val isGeminiNative: Boolean = false,
    val apiKeyPlaceholder: String = "Masukkan API Key",
    val description: String = "",
    val isFreeTier: Boolean = false,
    val requiresApiKey: Boolean = true,
    val quotaBucketId: String? = null,
    val limitBadge: String? = null,
    val resetInfo: String? = null,
    val apiKeyPortalUrl: String? = null,
    val apiKeyPortalLabel: String? = null
)

data class FreeApiPortal(
    val id: String,
    val providerName: String,
    val bonusBadge: String,
    val resetSummary: String,
    val url: String,
    val presetId: String
)

data class OpenRouterFreeModelItem(
    val id: String,
    val name: String,
    val contextLength: Int,
    val supportsVision: Boolean,
    val description: String
)

data class OpenRouterKeyStatus(
    val success: Boolean,
    val message: String,
    val isFreeTier: Boolean = true,
    val dailyFreeLimit: Int = FreeQuotaManager.OPENROUTER_FREE_RPD_STANDARD
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
 * Persistent Manager for AI Model, Base URL / Endpoint, Free-Tier Presets, and API Key configuration.
 * Allows users to easily switch between Google Gemini, Inception Labs (Mercury free tokens),
 * OpenRouter (:free models with periodic reset), Groq, Mistral, Cerebras, GitHub Models,
 * Pollinations (keyless free), DeepSeek, Local Ollama / LM Studio / Termux, OpenAI,
 * or any custom OpenAI-compatible endpoint.
 */
object AiConfigManager {

    private const val TAG = "AiConfigManager"
    private const val PREFS_NAME = "jarvis_ai_config_prefs"

    private const val KEY_API_KEY = "key_api_key"
    private const val KEY_BASE_URL = "key_base_url"
    private const val KEY_MODEL_NAME = "key_model_name"
    private const val KEY_PROVIDER_LABEL = "key_provider_label"
    private const val KEY_MAX_AGENT_LOOPS = "key_max_agent_loops"
    private const val KEY_CUSTOM_MODELS = "key_custom_models"
    private const val KEY_PROVIDER_API_KEYS = "key_provider_api_keys_v1"

    const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"

    // Portal & Endpoint resmi model gratis + token gratis
    const val INCEPTION_LABS_LOGIN_URL = "https://platform.inceptionlabs.ai/auth/login?callbackUrl=%2F"
    const val INCEPTION_LABS_BASE_URL = "https://api.inceptionlabs.ai/v1"
    const val INCEPTION_LABS_DEFAULT_MODEL = "mercury-3"

    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
    const val OPENROUTER_KEYS_URL = "https://openrouter.ai/settings/keys"
    const val OPENROUTER_FREE_AUTO_MODEL = "openrouter/free"

    const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
    const val GROQ_KEYS_URL = "https://console.groq.com/keys"

    const val GEMINI_KEYS_URL = "https://aistudio.google.com/apikey"
    const val GEMINI_FREE_MODEL = "gemini-3.5-flash-lite"

    const val MISTRAL_BASE_URL = "https://api.mistral.ai/v1"
    const val MISTRAL_KEYS_URL = "https://console.mistral.ai/api-keys"

    const val CEREBRAS_BASE_URL = "https://api.cerebras.ai/v1"
    const val CEREBRAS_KEYS_URL = "https://cloud.cerebras.ai/"

    const val GITHUB_MODELS_BASE_URL = "https://models.github.ai/inference"
    const val GITHUB_MODELS_KEYS_URL = "https://github.com/settings/tokens"

    const val POLLINATIONS_BASE_URL = "https://text.pollinations.ai/openai"
    const val POLLINATIONS_AUTH_BASE_URL = "https://gen.pollinations.ai/v1"
    const val POLLINATIONS_DEFAULT_MODEL = "openai-fast"
    const val POLLINATIONS_PORTAL_URL = "https://enter.pollinations.ai/"

    /**
     * Daftar portal resmi untuk mendapatkan API Key / token AI gratis tanpa kartu kredit.
     */
    val FREE_API_PORTALS = listOf(
        FreeApiPortal(
            id = "portal_inception",
            providerName = "Inception Labs (Mercury 3)",
            bonusBadge = "100 Juta Token Gratis",
            resetSummary = "Daftar & login gratis → dapat API key + 100M token, kecepatan >1.000 tok/dtk & reset RPM tiap menit",
            url = INCEPTION_LABS_LOGIN_URL,
            presetId = "inception_mercury_3"
        ),
        FreeApiPortal(
            id = "portal_openrouter",
            providerName = "OpenRouter (:free)",
            bonusBadge = "20/mnt • 50–1K/hari",
            resetSummary = "Akses belasan model :free \$0 — limit reset otomatis tiap 1 menit & pukul 00:00 UTC (07:00 WIB)",
            url = OPENROUTER_KEYS_URL,
            presetId = "openrouter_free_auto"
        ),
        FreeApiPortal(
            id = "portal_groq",
            providerName = "Groq Cloud Free",
            bonusBadge = "30/mnt • 1.000/hari",
            resetSummary = "Inferensi LPU super cepat untuk Llama 3.3 70B & GPT-OSS 120B — reset tiap 1 menit & 24 jam",
            url = GROQ_KEYS_URL,
            presetId = "groq_llama"
        ),
        FreeApiPortal(
            id = "portal_gemini",
            providerName = "Google AI Studio",
            bonusBadge = "15/mnt • 500/hari",
            resetSummary = "Gemini 3.5 Flash-Lite gratis dengan dukungan Vision (screenshot) — reset tiap 1 menit & 24 jam",
            url = GEMINI_KEYS_URL,
            presetId = "gemini_2_5_flash"
        ),
        FreeApiPortal(
            id = "portal_mistral",
            providerName = "Mistral AI (Experiment)",
            bonusBadge = "~1 Miliar Token/bln",
            resetSummary = "Paket Experiment gratis untuk Mistral Small & Codestral — reset RPM tiap menit & kuota bulanan",
            url = MISTRAL_KEYS_URL,
            presetId = "mistral_small_free"
        ),
        FreeApiPortal(
            id = "portal_cerebras",
            providerName = "Cerebras Inference",
            bonusBadge = "30/mnt • 1M tok/hari",
            resetSummary = "Inferensi wafer-scale instan (GPT-OSS 120B) — limit reset tiap 1 menit & harian",
            url = CEREBRAS_KEYS_URL,
            presetId = "cerebras_free"
        ),
        FreeApiPortal(
            id = "portal_github",
            providerName = "GitHub Models",
            bonusBadge = "10/mnt • 50/hari",
            resetSummary = "Pakai akun GitHub (Personal Access Token) untuk akses GPT-4.1 gratis — reset tiap 24 jam",
            url = GITHUB_MODELS_KEYS_URL,
            presetId = "github_gpt41_free"
        )
    )

    val PRESETS = listOf(
        // ==================== MODEL DEFAULT & FREE TIER ====================
        AiPreset(
            id = "gemini_flash",
            providerName = "Google Gemini",
            displayName = "Gemini 3.5 Flash (Default)",
            baseUrl = DEFAULT_GEMINI_BASE_URL,
            defaultModel = DEFAULT_GEMINI_MODEL,
            isGeminiNative = true,
            apiKeyPlaceholder = "AIzaSy...",
            description = "Model resmi Google, kecepatan tinggi, dukungan Vision & penalaran tajam.",
            apiKeyPortalUrl = GEMINI_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key di Google AI Studio"
        ),
        AiPreset(
            id = "inception_mercury_3",
            providerName = "Inception Labs",
            displayName = "Mercury 3 (100M Token Gratis)",
            baseUrl = INCEPTION_LABS_BASE_URL,
            defaultModel = INCEPTION_LABS_DEFAULT_MODEL,
            isGeminiNative = false,
            apiKeyPlaceholder = "sk_... (API Key Inception Labs)",
            description = "Diffusion LLM super cepat (>1.000 token/detik, konteks 1M) dengan bonus 100 Juta token gratis untuk akun baru.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_INCEPTION_FREE,
            limitBadge = "100M Token • 60/mnt",
            resetInfo = "Limit reset tiap 1 menit & 24 jam + bonus 100 Juta token gratis",
            apiKeyPortalUrl = INCEPTION_LABS_LOGIN_URL,
            apiKeyPortalLabel = "Login Inception Labs & Klaim 100M Token Gratis"
        ),
        AiPreset(
            id = "openrouter_free_auto",
            providerName = "OpenRouter Free",
            displayName = "OpenRouter Auto (:free)",
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = OPENROUTER_FREE_AUTO_MODEL,
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-or-v1-...",
            description = "Router pintar gratis OpenRouter (\$0/token) — otomatis memilih model :free yang sedang aktif & tidak sibuk.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_OPENROUTER_FREE,
            limitBadge = "20/mnt • 50/hari",
            resetInfo = "Reset otomatis tiap 1 menit & harian pukul 00:00 UTC (07:00 WIB)",
            apiKeyPortalUrl = OPENROUTER_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis OpenRouter"
        ),
        AiPreset(
            id = "openrouter_nemotron_vl_free",
            providerName = "OpenRouter Free",
            displayName = "Nemotron 3.5 Super VL (:free)",
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = "nvidia/nemotron-3.5-super-vl:free",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-or-v1-...",
            description = "Model gratis NVIDIA 1M konteks di OpenRouter — respons cepat (~100 tok/s), stabil untuk agent & JSON.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_OPENROUTER_FREE,
            limitBadge = "20/mnt • 50/hari",
            resetInfo = "Reset otomatis tiap 1 menit & harian pukul 00:00 UTC (07:00 WIB)",
            apiKeyPortalUrl = OPENROUTER_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis OpenRouter"
        ),
        AiPreset(
            id = "openrouter_nemotron_ultra_free",
            providerName = "OpenRouter Free",
            displayName = "Nemotron 3 Ultra 550B (:free)",
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = "nvidia/nemotron-3-ultra-550b-a55b:free",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-or-v1-...",
            description = "Model penalaran besar 550B MoE gratis (konteks 1M) via OpenRouter untuk analisa soal & orkestrasi agent.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_OPENROUTER_FREE,
            limitBadge = "20/mnt • 50/hari",
            resetInfo = "Reset otomatis tiap 1 menit & harian pukul 00:00 UTC (07:00 WIB)",
            apiKeyPortalUrl = OPENROUTER_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis OpenRouter"
        ),
        AiPreset(
            id = "openrouter_cohere_code_free",
            providerName = "OpenRouter Free",
            displayName = "Cohere North Mini Code (:free)",
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = "cohere/north-mini-code:free",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-or-v1-...",
            description = "Model coding & agentic gratis (konteks 256K) via OpenRouter dengan otomatis fallback ke openrouter/free bila sibuk.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_OPENROUTER_FREE,
            limitBadge = "20/mnt • 50/hari",
            resetInfo = "Reset otomatis tiap 1 menit & harian pukul 00:00 UTC (07:00 WIB)",
            apiKeyPortalUrl = OPENROUTER_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis OpenRouter"
        ),
        AiPreset(
            id = "groq_llama",
            providerName = "Groq",
            displayName = "Groq Llama 3.3 70B (Gratis)",
            baseUrl = GROQ_BASE_URL,
            defaultModel = "llama-3.3-70b-versatile",
            isGeminiNative = false,
            apiKeyPlaceholder = "gsk_...",
            description = "Inferensi LPU super cepat (ratusan token/detik) di Free Tier Groq dengan kuota harian yang reset otomatis.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_GROQ_FREE,
            limitBadge = "30/mnt • 1K/hari",
            resetInfo = "Reset otomatis tiap 1 menit (30 RPM) & tiap 24 jam (1.000 req/hari)",
            apiKeyPortalUrl = GROQ_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis Groq"
        ),
        AiPreset(
            id = "groq_gpt_oss_free",
            providerName = "Groq",
            displayName = "Groq GPT-OSS 120B (Gratis)",
            baseUrl = GROQ_BASE_URL,
            defaultModel = "openai/gpt-oss-120b",
            isGeminiNative = false,
            apiKeyPlaceholder = "gsk_...",
            description = "Model penalaran 120B berkecepatan ~500 token/detik di Free Tier Groq (tanpa kartu kredit).",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_GROQ_FREE,
            limitBadge = "30/mnt • 1K/hari",
            resetInfo = "Reset otomatis tiap 1 menit (30 RPM) & tiap 24 jam (1.000 req/hari)",
            apiKeyPortalUrl = GROQ_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis Groq"
        ),
        AiPreset(
            id = "gemini_2_5_flash",
            providerName = "Google Gemini",
            displayName = "Gemini 3.5 Flash-Lite (Free Tier)",
            baseUrl = DEFAULT_GEMINI_BASE_URL,
            defaultModel = GEMINI_FREE_MODEL,
            isGeminiNative = true,
            apiKeyPlaceholder = "AIzaSy...",
            description = "Kuota gratis aktif Google AI Studio (15 RPM, 500 RPD), latensi rendah & mendukung Vision penuh.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_GEMINI_FREE,
            limitBadge = "15/mnt • 500/hari",
            resetInfo = "Reset otomatis tiap 1 menit & tiap 24 jam (mendukung input gambar)",
            apiKeyPortalUrl = GEMINI_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis AI Studio"
        ),
        AiPreset(
            id = "mistral_small_free",
            providerName = "Mistral AI",
            displayName = "Mistral Small (Gratis)",
            baseUrl = MISTRAL_BASE_URL,
            defaultModel = "mistral-small-latest",
            isGeminiNative = false,
            apiKeyPlaceholder = "API Key Mistral Console...",
            description = "Paket Experiment gratis Mistral La Plateforme (tanpa kartu kredit, kuota ~1 Miliar token/bulan).",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_MISTRAL_FREE,
            limitBadge = "30/mnt • 1B tok/bln",
            resetInfo = "Reset otomatis tiap 1 menit, harian (24 jam), & kuota bulanan",
            apiKeyPortalUrl = MISTRAL_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis Mistral"
        ),
        AiPreset(
            id = "cerebras_free",
            providerName = "Cerebras",
            displayName = "Cerebras GPT-OSS 120B (Gratis)",
            baseUrl = CEREBRAS_BASE_URL,
            defaultModel = "gpt-oss-120b",
            isGeminiNative = false,
            apiKeyPlaceholder = "csk-...",
            description = "Inferensi wafer-scale ultra cepat dari Cerebras Cloud dengan alokasi token gratis harian.",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_CEREBRAS_FREE,
            limitBadge = "30/mnt • 1K/hari",
            resetInfo = "Reset otomatis tiap 1 menit & tiap 24 jam (hingga 1M token/hari)",
            apiKeyPortalUrl = CEREBRAS_KEYS_URL,
            apiKeyPortalLabel = "Ambil API Key Gratis Cerebras"
        ),
        AiPreset(
            id = "github_gpt41_free",
            providerName = "GitHub Models",
            displayName = "GitHub GPT-4.1 (Gratis)",
            baseUrl = GITHUB_MODELS_BASE_URL,
            defaultModel = "openai/gpt-4.1",
            isGeminiNative = false,
            apiKeyPlaceholder = "ghp_... / github_pat_...",
            description = "Akses GPT-4.1 gratis menggunakan akun GitHub (Personal Access Token dengan akses Models).",
            isFreeTier = true,
            requiresApiKey = true,
            quotaBucketId = FreeQuotaManager.BUCKET_GITHUB_FREE,
            limitBadge = "10/mnt • 50/hari",
            resetInfo = "Reset otomatis tiap 1 menit (10 RPM) & tiap 24 jam (50 req/hari)",
            apiKeyPortalUrl = GITHUB_MODELS_KEYS_URL,
            apiKeyPortalLabel = "Buat Token Gratis di GitHub"
        ),
        AiPreset(
            id = "pollinations_free",
            providerName = "Pollinations AI",
            displayName = "Pollinations (Tanpa Key)",
            baseUrl = POLLINATIONS_BASE_URL,
            defaultModel = POLLINATIONS_DEFAULT_MODEL,
            isGeminiNative = false,
            apiKeyPlaceholder = "Kosongkan (opsional isi key gen.pollinations.ai)",
            description = "Langsung pakai tanpa API key (2 req/menit per IP, reset tiap menit & 6 jam). Jika key diisi, otomatis memakai endpoint gen.pollinations.ai.",
            isFreeTier = true,
            requiresApiKey = false,
            quotaBucketId = FreeQuotaManager.BUCKET_POLLINATIONS_FREE,
            limitBadge = "2/mnt • 100/6 jam",
            resetInfo = "Reset otomatis tiap 30–60 detik & tiap 6 jam (tanpa API key)",
            apiKeyPortalUrl = POLLINATIONS_PORTAL_URL,
            apiKeyPortalLabel = "Opsional: Daftar Pollinations utk Limit Lebih Tinggi"
        ),

        // ==================== MODEL BERBAYAR & LOKAL ====================
        AiPreset(
            id = "openrouter",
            providerName = "OpenRouter",
            displayName = "OpenRouter (Multi-Model)",
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = "google/gemini-2.5-flash",
            isGeminiNative = false,
            apiKeyPlaceholder = "sk-or-v1-...",
            description = "Akses ratusan model AI (Claude, DeepSeek, Llama, Gemini, Mercury) via 1 API Key.",
            apiKeyPortalUrl = OPENROUTER_KEYS_URL,
            apiKeyPortalLabel = "Buka Dashboard API Key OpenRouter"
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
            requiresApiKey = false,
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
            requiresApiKey = false,
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
            requiresApiKey = false,
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

    private var prefs: SharedPreferences? = null

    /** Penyimpanan API key per-provider agar tidak hilang saat berpindah antar preset gratis. */
    private val providerApiKeys = mutableMapOf<String, String>()

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
            loadProviderApiKeys()
            loadConfig()
            loadCustomModels()
        }
    }

    /** Semua pilihan model: preset bawaan + model impor milik pengguna. */
    fun allModels(): List<AiPreset> = PRESETS + _customModels.value

    /** Hanya daftar preset model gratis dengan kuota reset berkala / bonus token gratis. */
    fun freeModels(): List<AiPreset> = allModels().filter { it.isFreeTier }

    /** Cari preset yang cocok dengan pasangan baseUrl + modelName saat ini. */
    fun findMatchingPreset(baseUrl: String, modelName: String): AiPreset? {
        val cleanUrl = baseUrl.trim().trimEnd('/').lowercase()
        val cleanModel = modelName.trim().lowercase()
        return allModels().firstOrNull { preset ->
            preset.baseUrl.trim().trimEnd('/').lowercase() == cleanUrl &&
                preset.defaultModel.trim().lowercase() == cleanModel
        }
    }

    /**
     * Ambil URL portal API key gratis untuk endpoint tertentu (mis. Inception Labs, OpenRouter, Groq, dll).
     */
    fun getPortalUrlForEndpoint(baseUrl: String): Pair<String, String>? {
        val lower = baseUrl.trim().lowercase()
        return when {
            lower.contains("inceptionlabs.ai") ->
                INCEPTION_LABS_LOGIN_URL to "Login Inception Labs (Klaim 100M Token Gratis)"
            lower.contains("openrouter.ai") ->
                OPENROUTER_KEYS_URL to "Ambil API Key Gratis OpenRouter"
            lower.contains("api.groq.com") ->
                GROQ_KEYS_URL to "Ambil API Key Gratis Groq"
            lower.contains("generativelanguage.googleapis.com") ->
                GEMINI_KEYS_URL to "Ambil API Key Gratis Google AI Studio"
            lower.contains("api.mistral.ai") ->
                MISTRAL_KEYS_URL to "Ambil API Key Gratis Mistral"
            lower.contains("api.cerebras.ai") ->
                CEREBRAS_KEYS_URL to "Ambil API Key Gratis Cerebras"
            lower.contains("models.github.ai") || lower.contains("models.inference.ai.azure.com") ->
                GITHUB_MODELS_KEYS_URL to "Buat Token Gratis GitHub Models"
            lower.contains("pollinations.ai") ->
                POLLINATIONS_PORTAL_URL to "Portal Pollinations AI (Opsional)"
            else -> null
        }
    }

    /**
     * Impor model LLM sendiri (mis. Qwen3 4B via llama.cpp/vLLM/Ollama lokal,
     * model :free baru dari OpenRouter, atau endpoint OpenAI-compatible apa pun) dan simpan permanen.
     */
    fun addCustomModel(
        displayName: String,
        baseUrl: String,
        modelId: String,
        providerLabel: String = "Model Saya"
    ): AiPreset {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val cleanModel = modelId.trim()
        val bucket = FreeQuotaManager.resolveBucketId(cleanUrl, cleanModel)
        val policy = bucket?.let { FreeQuotaManager.getPolicyById(it) }
        val portal = getPortalUrlForEndpoint(cleanUrl)
        val preset = AiPreset(
            id = "user_" + System.currentTimeMillis(),
            providerName = providerLabel.trim().ifBlank { determineProviderName(cleanUrl, cleanModel) },
            displayName = displayName.trim().ifBlank { cleanModel },
            baseUrl = cleanUrl,
            defaultModel = cleanModel,
            isGeminiNative = isGeminiEndpoint(cleanUrl),
            requiresApiKey = requiresApiKey(cleanUrl, cleanModel),
            isFreeTier = bucket != null,
            quotaBucketId = bucket,
            limitBadge = policy?.let { "${it.rpmLimit}/mnt • ${it.periodLimit}/${it.periodLabel}" },
            resetInfo = policy?.resetDescription,
            apiKeyPortalUrl = portal?.first,
            apiKeyPortalLabel = portal?.second,
            apiKeyPlaceholder = "API key (opsional)",
            description = "Model impor kustom: $cleanModel di $cleanUrl"
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
                val bUrl = o.optString("baseUrl")
                val dModel = o.optString("defaultModel")
                val bucket = FreeQuotaManager.resolveBucketId(bUrl, dModel)
                val policy = bucket?.let { FreeQuotaManager.getPolicyById(it) }
                val portal = getPortalUrlForEndpoint(bUrl)
                list.add(
                    AiPreset(
                        id = o.optString("id"),
                        providerName = o.optString("providerName", "Model Saya"),
                        displayName = o.optString("displayName"),
                        baseUrl = bUrl,
                        defaultModel = dModel,
                        isGeminiNative = o.optBoolean("isGeminiNative", false),
                        requiresApiKey = requiresApiKey(bUrl, dModel),
                        isFreeTier = bucket != null,
                        quotaBucketId = bucket,
                        limitBadge = policy?.let { "${it.rpmLimit}/mnt • ${it.periodLimit}/${it.periodLabel}" },
                        resetInfo = policy?.resetDescription,
                        apiKeyPortalUrl = portal?.first,
                        apiKeyPortalLabel = portal?.second,
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

    private fun providerVaultKey(baseUrl: String): String {
        val lower = baseUrl.trim().trimEnd('/').lowercase()
        return when {
            lower.contains("generativelanguage.googleapis.com") || lower.contains("googleapis.com") -> "gemini"
            lower.contains("inceptionlabs.ai") -> "inception"
            lower.contains("openrouter.ai") -> "openrouter"
            lower.contains("api.groq.com") -> "groq"
            lower.contains("api.mistral.ai") -> "mistral"
            lower.contains("api.cerebras.ai") -> "cerebras"
            lower.contains("models.github.ai") || lower.contains("models.inference.ai.azure.com") -> "github_models"
            lower.contains("pollinations.ai") -> "pollinations"
            lower.contains("deepseek.com") -> "deepseek"
            lower.contains("api.openai.com") -> "openai"
            lower.contains("together.xyz") -> "together"
            else -> lower.removePrefix("https://").removePrefix("http://").substringBefore("/")
        }
    }

    /** Ambil API key yang pernah disimpan untuk provider dari `baseUrl` tertentu. */
    fun getSavedApiKeyForEndpoint(baseUrl: String): String {
        val slot = providerVaultKey(baseUrl)
        synchronized(providerApiKeys) {
            return providerApiKeys[slot].orEmpty()
        }
    }

    /** Cek apakah dua URL berasal dari provider API key yang sama. */
    fun isSameProviderVault(urlA: String, urlB: String): Boolean {
        return providerVaultKey(urlA) == providerVaultKey(urlB)
    }

    private fun saveProviderApiKey(baseUrl: String, apiKey: String) {
        val slot = providerVaultKey(baseUrl)
        if (slot.isBlank()) return
        val clean = apiKey.trim()
        synchronized(providerApiKeys) {
            if (clean.isEmpty()) {
                providerApiKeys.remove(slot)
            } else {
                providerApiKeys[slot] = clean
            }
            val sp = prefs ?: return
            val obj = JSONObject()
            for ((k, v) in providerApiKeys) {
                if (v.isNotBlank()) obj.put(k, v)
            }
            sp.edit().putString(KEY_PROVIDER_API_KEYS, obj.toString()).apply()
        }
    }

    private fun loadProviderApiKeys() {
        val raw = prefs?.getString(KEY_PROVIDER_API_KEYS, null) ?: return
        try {
            val obj = JSONObject(raw)
            synchronized(providerApiKeys) {
                providerApiKeys.clear()
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.optString(k, "").trim()
                    if (v.isNotEmpty()) providerApiKeys[k] = v
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memuat provider API keys: ${e.message}")
        }
    }

    private fun loadConfig() {
        val sp = prefs ?: return
        val savedKey = sp.getString(KEY_API_KEY, "") ?: ""
        val savedUrl = sp.getString(KEY_BASE_URL, DEFAULT_GEMINI_BASE_URL) ?: DEFAULT_GEMINI_BASE_URL
        val savedModel = sp.getString(KEY_MODEL_NAME, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
        val savedProvider = sp.getString(KEY_PROVIDER_LABEL, "Google Gemini") ?: "Google Gemini"
        val savedMaxLoops = sp.getInt(KEY_MAX_AGENT_LOOPS, 20).coerceIn(0, 100)

        // Migrasi key aktif ke vault per-provider bila belum tercatat
        if (savedKey.isNotBlank() && getSavedApiKeyForEndpoint(savedUrl).isBlank()) {
            saveProviderApiKey(savedUrl, savedKey)
        }

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

        saveProviderApiKey(cleanUrl, cleanKey)

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
        val current = _config.value
        val keyForPreset = if (isSameProviderVault(current.baseUrl, preset.baseUrl)) {
            current.apiKey
        } else {
            getSavedApiKeyForEndpoint(preset.baseUrl)
        }
        saveConfig(
            apiKey = keyForPreset,
            baseUrl = preset.baseUrl,
            modelName = preset.defaultModel,
            providerLabel = preset.providerName
        )
    }

    fun getEffectiveApiKey(targetBaseUrl: String = _config.value.baseUrl): String {
        val current = _config.value
        val configuredKey = if (isSameProviderVault(current.baseUrl, targetBaseUrl)) {
            current.apiKey.trim().ifEmpty { getSavedApiKeyForEndpoint(targetBaseUrl) }
        } else {
            getSavedApiKeyForEndpoint(targetBaseUrl)
        }
        if (configuredKey.isNotEmpty() && configuredKey != "MY_GEMINI_API_KEY") {
            return configuredKey
        }
        if (isGeminiEndpoint(targetBaseUrl)) {
            return try {
                val builtIn = BuildConfig.GEMINI_API_KEY.trim()
                if (builtIn == "MY_GEMINI_API_KEY") "" else builtIn
            } catch (_: Exception) {
                ""
            }
        }
        return ""
    }

    fun isGeminiEndpoint(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("generativelanguage.googleapis.com") || lower.contains("googleapis.com")
    }

    fun isInceptionEndpoint(url: String): Boolean {
        return url.lowercase().contains("inceptionlabs.ai")
    }

    fun isOpenRouterEndpoint(url: String): Boolean {
        return url.lowercase().contains("openrouter.ai")
    }

    fun isOpenRouterFreeModel(url: String, model: String): Boolean {
        val m = model.trim().lowercase()
        return isOpenRouterEndpoint(url) && (m == "openrouter/free" || m.endsWith(":free"))
    }

    fun isLocalOrKeylessEndpoint(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.contains("text.pollinations.ai") ||
            lower.contains("127.0.0.1") ||
            lower.contains("localhost") ||
            lower.contains("10.0.2.2") ||
            lower.contains(":11434") ||
            lower.contains(":1234") ||
            lower.startsWith("http://192.168.") ||
            lower.startsWith("http://10.") ||
            Regex("^http://172\\.(1[6-9]|2\\d|3[0-1])\\..*").matches(lower)
    }

    fun requiresApiKey(url: String, model: String = ""): Boolean {
        val preset = findMatchingPreset(url, model)
        if (preset != null && !preset.requiresApiKey) return false
        return !isLocalOrKeylessEndpoint(url)
    }

    /**
     * Pesan panduan saat API key belum diisi, dilengkapi tautan resmi pengambilan key/token gratis.
     */
    fun buildMissingApiKeyMessage(baseUrl: String, modelName: String): String {
        val provider = determineProviderName(baseUrl, modelName)
        val portal = getPortalUrlForEndpoint(baseUrl)
        return buildString {
            appendLine("API Key belum dikonfigurasi untuk $provider ($modelName).")
            if (portal != null) {
                appendLine("Dapatkan API Key / token gratis: ${portal.first}")
            }
            append("Buka Pengaturan AI untuk memasukkan API Key, atau pilih preset 'Pollinations (Tanpa Key)' untuk mencoba langsung.")
        }
    }

    /**
     * Menormalkan Base URL menjadi endpoint POST Chat Completions yang tepat untuk setiap provider,
     * termasuk penanganan khusus Pollinations anonim (`text.pollinations.ai/openai`) vs terautentikasi
     * (`gen.pollinations.ai/v1/chat/completions`).
     */
    fun resolveChatCompletionsEndpoint(baseUrl: String, hasApiKey: Boolean = false): String {
        val clean = baseUrl.trim().trimEnd('/')
        val lower = clean.lowercase()
        return when {
            lower.contains("text.pollinations.ai") && hasApiKey ->
                "$POLLINATIONS_AUTH_BASE_URL/chat/completions"
            lower.endsWith("text.pollinations.ai/openai") ->
                clean
            lower.endsWith("text.pollinations.ai") ->
                "$clean/openai"
            lower.endsWith("/chat/completions") ->
                clean
            lower.endsWith("gen.pollinations.ai") ->
                "$clean/v1/chat/completions"
            else ->
                "$clean/chat/completions"
        }
    }

    fun determineProviderName(url: String, model: String): String {
        val lower = url.lowercase()
        val lowerModel = model.lowercase()
        return when {
            lower.contains("generativelanguage.googleapis.com") -> "Google Gemini"
            lower.contains("inceptionlabs.ai") -> "Inception Labs"
            lower.contains("openrouter.ai") && (lowerModel == "openrouter/free" || lowerModel.endsWith(":free")) ->
                "OpenRouter Free"
            lower.contains("openrouter.ai") -> "OpenRouter"
            lower.contains("api.groq.com") -> "Groq"
            lower.contains("api.mistral.ai") -> "Mistral AI"
            lower.contains("api.cerebras.ai") -> "Cerebras"
            lower.contains("models.github.ai") || lower.contains("models.inference.ai.azure.com") -> "GitHub Models"
            lower.contains("pollinations.ai") -> "Pollinations AI"
            lower.contains("deepseek.com") -> "DeepSeek"
            lower.contains("11434") || lower.contains("ollama") -> "Ollama Local"
            lower.contains("1234") || lower.contains("lmstudio") -> "LM Studio"
            lower.contains("127.0.0.1") || lower.contains("localhost") -> "Lokal (HP)"
            lower.contains("api.openai.com") -> "OpenAI"
            lower.contains("together.xyz") -> "Together AI"
            else -> "Custom ($model)"
        }
    }

    /**
     * Ambil katalog terbaru model gratis (:free / $0) langsung dari API publik OpenRouter (tanpa perlu API key).
     * Berguna karena daftar model :free di OpenRouter diperbarui secara berkala.
     */
    suspend fun fetchOpenRouterFreeModels(): Result<List<OpenRouterFreeModelItem>> = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        runCatching {
            val req = Request.Builder()
                .url("$OPENROUTER_BASE_URL/models")
                .get()
                .build()
            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(120)}")
            }

            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: JSONArray()
            val items = mutableListOf<OpenRouterFreeModelItem>()

            // Selalu taruh router otomatis openrouter/free di urutan teratas
            items.add(
                OpenRouterFreeModelItem(
                    id = OPENROUTER_FREE_AUTO_MODEL,
                    name = "OpenRouter Auto Free Router (Direkomendasikan)",
                    contextLength = 256_000,
                    supportsVision = true,
                    description = "Otomatis merutekan ke model :free aktif terbaik (20/mnt • 50–1.000/hari)"
                )
            )

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                if (id.isEmpty() || id.equals(OPENROUTER_FREE_AUTO_MODEL, ignoreCase = true)) continue

                val pricing = obj.optJSONObject("pricing")
                val promptCost = pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0
                val completionCost = pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0
                val isFreeId = id.endsWith(":free", ignoreCase = true)
                val isZeroPrice = promptCost == 0.0 && completionCost == 0.0
                if (!isFreeId && !isZeroPrice) continue

                // Hanya ambil model chat teks (lewati model embedding/audio-only/rerank)
                val arch = obj.optJSONObject("architecture")
                val outMods = arch?.optJSONArray("output_modalities")
                if (outMods != null && outMods.length() > 0) {
                    var outputsText = false
                    for (m in 0 until outMods.length()) {
                        if (outMods.optString(m).equals("text", ignoreCase = true)) {
                            outputsText = true
                            break
                        }
                    }
                    if (!outputsText) continue
                }

                var hasVision = false
                val inMods = arch?.optJSONArray("input_modalities")
                if (inMods != null) {
                    for (m in 0 until inMods.length()) {
                        if (inMods.optString(m).equals("image", ignoreCase = true)) {
                            hasVision = true
                            break
                        }
                    }
                }

                val name = obj.optString("name", id).trim().ifBlank { id }
                val ctx = obj.optInt("context_length", 0)
                val desc = obj.optString("description", "").trim().take(120)

                items.add(
                    OpenRouterFreeModelItem(
                        id = id,
                        name = name,
                        contextLength = ctx,
                        supportsVision = hasVision,
                        description = desc
                    )
                )
            }

            // Urutkan: router otomatis pertama, lalu model :free dengan context terbesar
            val pinned = items.first()
            val rest = items.drop(1)
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<OpenRouterFreeModelItem> { it.id.endsWith(":free", ignoreCase = true) }
                        .thenByDescending { it.contextLength }
                        .thenBy { it.name }
                )
            listOf(pinned) + rest
        }
    }

    /**
     * Cek status & tier kuota harian API key OpenRouter via `GET /api/v1/key`,
     * lalu otomatis menyelaraskan batas 50/hari vs 1.000/hari di [FreeQuotaManager].
     */
    suspend fun checkOpenRouterKeyStatus(apiKey: String): OpenRouterKeyStatus = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim().ifEmpty { getEffectiveApiKey(OPENROUTER_BASE_URL) }
        if (cleanKey.isBlank()) {
            return@withContext OpenRouterKeyStatus(
                success = false,
                message = "Masukkan API key OpenRouter (sk-or-v1-...) terlebih dahulu."
            )
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        try {
            val req = Request.Builder()
                .url("$OPENROUTER_BASE_URL/key")
                .addHeader("Authorization", "Bearer $cleanKey")
                .get()
                .build()
            val response = client.newCall(req).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext OpenRouterKeyStatus(
                    success = false,
                    message = "Key tidak valid / gagal dicek (${response.code}): ${body.take(120)}"
                )
            }
            val data = JSONObject(body).optJSONObject("data") ?: JSONObject()
            val isFreeTier = data.optBoolean("is_free_tier", true)
            val usageDaily = data.optDouble("usage_daily", 0.0)
            val limitRem = if (data.has("limit_remaining") && !data.isNull("limit_remaining")) {
                data.optDouble("limit_remaining", 0.0)
            } else {
                null
            }
            val dailyCap = if (isFreeTier) {
                FreeQuotaManager.OPENROUTER_FREE_RPD_STANDARD
            } else {
                FreeQuotaManager.OPENROUTER_FREE_RPD_FUNDED
            }
            FreeQuotaManager.setOpenRouterHighTier(!isFreeTier)

            val tierLabel = if (isFreeTier) "Free Tier Standar" else "Akun Terverifikasi Kredit"
            val remInfo = limitRem?.let { " • Sisa kredit: \$${String.format(Locale.US, "%.3f", it)}" } ?: ""
            val usedInfo = String.format(Locale.US, "%.4f", usageDaily)
            OpenRouterKeyStatus(
                success = true,
                isFreeTier = isFreeTier,
                dailyFreeLimit = dailyCap,
                message = "$tierLabel — Kuota :free disetel ke $dailyCap req/hari & 20 req/mnt (reset 00:00 UTC / 07:00 WIB). Pemakaian kredit hari ini: \$$usedInfo$remInfo"
            )
        } catch (e: Exception) {
            OpenRouterKeyStatus(
                success = false,
                message = "Gagal mengecek status key: ${e.message}"
            )
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
        val cleanUrl = baseUrl.trim().ifEmpty { DEFAULT_GEMINI_BASE_URL }.trimEnd('/')
        val cleanModel = modelName.trim().ifEmpty { DEFAULT_GEMINI_MODEL }
        val cleanKey = apiKey.trim().ifEmpty { getEffectiveApiKey(cleanUrl) }

        if (requiresApiKey(cleanUrl, cleanModel) && cleanKey.isEmpty()) {
            val portalHint = getPortalUrlForEndpoint(cleanUrl)?.first?.let { "\nAmbil gratis di: $it" } ?: ""
            return@withContext ConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "API Key masih kosong untuk ${determineProviderName(cleanUrl, cleanModel)}.$portalHint",
                responsePreview = null
            )
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val isGemini = isGeminiEndpoint(cleanUrl)
        val isInception = isInceptionEndpoint(cleanUrl)

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
                val hasKey = cleanKey.isNotEmpty()
                val endpoint = resolveChatCompletionsEndpoint(cleanUrl, hasApiKey = hasKey)

                val bodyJson = JSONObject().apply {
                    put("model", cleanModel)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", "JARVIS Ping Test. Balas hanya kata 'PONG'.")
                        }
                    ))
                    if (isInception) {
                        // Dokumentasi resmi Inception Labs Mercury 3: temperature 0.5..1.0 & max_completion_tokens
                        put("temperature", 0.75)
                        put("max_completion_tokens", 256)
                    } else {
                        put("max_tokens", 32)
                    }
                }
                val reqBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val builder = Request.Builder()
                    .url(endpoint)
                    .post(reqBody)
                if (hasKey && !endpoint.lowercase().contains("text.pollinations.ai")) {
                    builder.addHeader("Authorization", "Bearer $cleanKey")
                }
                if (isOpenRouterEndpoint(cleanUrl)) {
                    builder.addHeader("HTTP-Referer", "https://ai.studio/build")
                    builder.addHeader("X-Title", "Andra Control")
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
            } else if (response.code == 429) {
                val resetMsg = FreeQuotaManager.recordRateLimitError(
                    baseUrl = cleanUrl,
                    modelName = cleanModel,
                    headers = response.headers,
                    errorBody = bodyString
                )
                ConnectionTestResult(
                    success = false,
                    latencyMs = latency,
                    message = "API aktif, namun limit sementara tercapai (429). ${resetMsg.lineSequence().firstOrNull() ?: ""}",
                    responsePreview = bodyString
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
