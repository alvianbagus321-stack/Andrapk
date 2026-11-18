package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Cara penghitungan jadwal reset kuota periodik model gratis.
 */
enum class QuotaResetMode {
    /** Reset otomatis setiap [FreeQuotaPolicy.periodDurationMs] sejak pemakaian pertama di periode aktif. */
    ROLLING_INTERVAL,

    /** Reset otomatis setiap pergantian hari pukul 00:00 UTC (07:00 WIB / 08:00 WITA). */
    UTC_MIDNIGHT
}

/**
 * Aturan limit & jadwal reset untuk suatu kelompok (bucket) model AI gratis.
 */
data class FreeQuotaPolicy(
    val bucketId: String,
    val providerTitle: String,
    val rpmLimit: Int,
    val periodLimit: Int,
    val periodDurationMs: Long,
    val periodLabel: String,
    val resetMode: QuotaResetMode,
    val resetDescription: String,
    val tokenGrantLimit: Long? = null,
    val tokenGrantLabel: String? = null
)

/**
 * Snapshot real-time status kuota model gratis (dipakai UI Dashboard, Chat, dan AiConfigDialog).
 */
data class FreeQuotaStatus(
    val policy: FreeQuotaPolicy,
    val minuteUsed: Int,
    val minuteLimit: Int,
    val minuteRemaining: Int,
    val minuteResetInMs: Long,
    val periodUsed: Int,
    val periodLimit: Int,
    val periodRemaining: Int,
    val periodResetInMs: Long,
    val periodResetAtMs: Long,
    val serverCooldownRemainingMs: Long,
    val serverCooldownReason: String?,
    val periodTokensUsed: Long,
    val totalTokensUsed: Long,
    val enforceLocalLimit: Boolean
) {
    val isMinuteExhausted: Boolean
        get() = minuteRemaining <= 0 && minuteResetInMs > 0L

    val isPeriodExhausted: Boolean
        get() = periodRemaining <= 0 && periodResetInMs > 0L

    val isServerCoolingDown: Boolean
        get() = serverCooldownRemainingMs > 0L

    val isBlocked: Boolean
        get() = isServerCoolingDown || (enforceLocalLimit && (isPeriodExhausted || isMinuteExhausted))

    /** Waktu tunggu aktif paling relevan hingga request berikutnya bisa dikirim. */
    val activeWaitMs: Long
        get() = when {
            isServerCoolingDown -> serverCooldownRemainingMs
            isPeriodExhausted -> periodResetInMs
            isMinuteExhausted -> minuteResetInMs
            else -> 0L
        }

    /** Rasio sisa kuota periode (0f..1f) untuk progress bar UI. */
    val periodRemainingFraction: Float
        get() = if (periodLimit <= 0) 1f else (periodRemaining.toFloat() / periodLimit.toFloat()).coerceIn(0f, 1f)

    /** Rasio sisa kuota per menit (0f..1f). */
    val minuteRemainingFraction: Float
        get() = if (minuteLimit <= 0) 1f else (minuteRemaining.toFloat() / minuteLimit.toFloat()).coerceIn(0f, 1f)
}

/**
 * Hasil pemeriksaan pra-request sebelum memanggil endpoint model gratis.
 */
data class QuotaCheckResult(
    val allowed: Boolean,
    val waitMs: Long = 0L,
    val isShortMinuteWait: Boolean = false,
    val userMessage: String? = null,
    val status: FreeQuotaStatus? = null
)

/**
 * Manajer kuota & rate-limit otomatis untuk model-model AI gratis.
 *
 * Fitur utama:
 * - Melacak limit per menit (RPM — reset tiap 60 detik) dan limit periodik (6 jam / 24 jam / 00:00 UTC).
 * - Reset otomatis begitu jendela waktu berakhir (tanpa intervensi manual).
 * - Sinkron dengan header HTTP server (`Retry-After`, `X-RateLimit-Reset`, `X-RateLimit-Remaining`, dll.)
 *   saat terjadi respons 200 OK maupun 429 Too Many Requests.
 * - Melacak pemakaian token (berguna untuk memantau bonus 100 Juta token gratis Inception Labs, dll.).
 * - Persisten lintas restart aplikasi via SharedPreferences.
 */
object FreeQuotaManager {

    private const val TAG = "FreeQuotaManager"
    private const val PREFS_NAME = "jarvis_free_quota_prefs"
    private const val KEY_BUCKETS_JSON = "key_quota_buckets_v1"
    private const val KEY_OPENROUTER_UNLOCKED = "key_openrouter_1000_rpd"
    private const val KEY_ENFORCE_LOCAL_LIMIT = "key_enforce_local_limit"

    const val MINUTE_WINDOW_MS = 60_000L
    const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L
    const val DAY_WINDOW_MS = 24L * 60L * 60L * 1000L

    const val BUCKET_INCEPTION_FREE = "inception_free"
    const val BUCKET_OPENROUTER_FREE = "openrouter_free"
    const val BUCKET_GROQ_FREE = "groq_free"
    const val BUCKET_GEMINI_FREE = "gemini_free"
    const val BUCKET_MISTRAL_FREE = "mistral_free"
    const val BUCKET_CEREBRAS_FREE = "cerebras_free"
    const val BUCKET_GITHUB_FREE = "github_free"
    const val BUCKET_POLLINATIONS_FREE = "pollinations_free"

    const val OPENROUTER_FREE_RPD_STANDARD = 50
    const val OPENROUTER_FREE_RPD_FUNDED = 1000
    const val INCEPTION_FREE_TOKEN_GRANT = 100_000_000L

    private data class BucketState(
        val minuteTimestamps: MutableList<Long> = mutableListOf(),
        var periodUsed: Int = 0,
        var periodResetAtMs: Long = 0L,
        var serverCooldownUntilMs: Long = 0L,
        var serverCooldownReason: String? = null,
        var serverRemainingOverride: Int? = null,
        var periodTokensUsed: Long = 0L,
        var totalTokensUsed: Long = 0L
    )

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val buckets = mutableMapOf<String, BucketState>()

    private val _openRouterHighTier = MutableStateFlow(false)
    val openRouterHighTier: StateFlow<Boolean> = _openRouterHighTier.asStateFlow()

    private val _enforceLocalLimit = MutableStateFlow(true)
    val enforceLocalLimit: StateFlow<Boolean> = _enforceLocalLimit.asStateFlow()

    /** Bertambah setiap kali state kuota berubah agar Compose UI langsung ter-update. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                loadFromPrefsLocked()
            }
        }
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1L
    }

    /**
     * Atur tier harian OpenRouter :free (false = 50 req/hari standar gratis, true = 1.000 req/hari akun ≥$10).
     */
    fun setOpenRouterHighTier(enabled: Boolean) {
        synchronized(lock) {
            _openRouterHighTier.value = enabled
            prefs?.edit()?.putBoolean(KEY_OPENROUTER_UNLOCKED, enabled)?.apply()
        }
        bumpRevision()
    }

    /**
     * Aktif/nonaktifkan penjaga limit lokal sebelum request dikirim (header 429 dari server tetap selalu dipatuhi).
     */
    fun setEnforceLocalLimit(enabled: Boolean) {
        synchronized(lock) {
            _enforceLocalLimit.value = enabled
            prefs?.edit()?.putBoolean(KEY_ENFORCE_LOCAL_LIMIT, enabled)?.apply()
        }
        bumpRevision()
    }

    /**
     * Ambil definisi kebijakan kuota gratis berdasarkan `bucketId`.
     */
    fun getPolicyById(bucketId: String): FreeQuotaPolicy? {
        val orDaily = if (_openRouterHighTier.value) OPENROUTER_FREE_RPD_FUNDED else OPENROUTER_FREE_RPD_STANDARD
        return when (bucketId) {
            BUCKET_INCEPTION_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_INCEPTION_FREE,
                providerTitle = "Inception Labs (Mercury Free)",
                rpmLimit = 60,
                periodLimit = 500,
                periodDurationMs = DAY_WINDOW_MS,
                periodLabel = "hari (24 jam)",
                resetMode = QuotaResetMode.ROLLING_INTERVAL,
                resetDescription = "Reset otomatis tiap 1 menit & tiap 24 jam + bonus 100 Juta token gratis akun baru",
                tokenGrantLimit = INCEPTION_FREE_TOKEN_GRANT,
                tokenGrantLabel = "100 Juta Token Gratis"
            )
            BUCKET_OPENROUTER_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_OPENROUTER_FREE,
                providerTitle = "OpenRouter (:free)",
                rpmLimit = 20,
                periodLimit = orDaily,
                periodDurationMs = DAY_WINDOW_MS,
                periodLabel = "hari (UTC)",
                resetMode = QuotaResetMode.UTC_MIDNIGHT,
                resetDescription = "Reset otomatis tiap 1 menit (20 RPM) & harian pukul 00:00 UTC / 07:00 WIB ($orDaily req/hari)"
            )
            BUCKET_GROQ_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_GROQ_FREE,
                providerTitle = "Groq Cloud (Free Tier)",
                rpmLimit = 30,
                periodLimit = 1000,
                periodDurationMs = DAY_WINDOW_MS,
                periodLabel = "hari (24 jam)",
                resetMode = QuotaResetMode.ROLLING_INTERVAL,
                resetDescription = "Reset otomatis tiap 1 menit (30 RPM) & tiap 24 jam (1.000 req/hari)"
            )
            BUCKET_GEMINI_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_GEMINI_FREE,
                providerTitle = "Google AI Studio (Free Tier)",
                rpmLimit = 15,
                periodLimit = 500,
                periodDurationMs = DAY_WINDOW_MS,
                periodLabel = "hari (24 jam)",
                resetMode = QuotaResetMode.ROLLING_INTERVAL,
                resetDescription = "Reset otomatis tiap 1 menit (15 RPM) & tiap 24 jam (500 req/hari)"
            )
            BUCKET_MISTRAL_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_MISTRAL_FREE,
                providerTitle = "Mistral AI (Experiment Free)",
                rpmLimit = 30,
                periodLimit = 1000,
                periodDurationMs = DAY_WINDOW_MS,
                periodLabel = "hari (24 jam)",
                resetMode = QuotaResetMode.ROLLING_INTERVAL,
                resetDescription = "Reset otomatis tiap 1 menit & tiap 24 jam (paket Experiment gratis ~1 Miliar token/bulan)"
            )
            BUCKET_CEREBRAS_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_CEREBRAS_FREE,
                providerTitle = "Cerebras Inference (Free)",
                rpmLimit = 30,
                periodLimit = 1000,
                periodDurationMs = DAY_WINDOW_MS,
                periodLabel = "hari (24 jam)",
                resetMode = QuotaResetMode.ROLLING_INTERVAL,
                resetDescription = "Reset otomatis tiap 1 menit (30 RPM) & tiap 24 jam (hingga 1 Juta token/hari)"
            )
            BUCKET_GITHUB_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_GITHUB_FREE,
                providerTitle = "GitHub Models (Free Tier)",
                rpmLimit = 10,
                periodLimit = 50,
                periodDurationMs = DAY_WINDOW_MS,
                periodLabel = "hari (24 jam)",
                resetMode = QuotaResetMode.ROLLING_INTERVAL,
                resetDescription = "Reset otomatis tiap 1 menit (10 RPM) & tiap 24 jam (50 req/hari)"
            )
            BUCKET_POLLINATIONS_FREE -> FreeQuotaPolicy(
                bucketId = BUCKET_POLLINATIONS_FREE,
                providerTitle = "Pollinations AI (Free Tanpa Key)",
                rpmLimit = 2,
                periodLimit = 100,
                periodDurationMs = SIX_HOURS_MS,
                periodLabel = "6 jam",
                resetMode = QuotaResetMode.ROLLING_INTERVAL,
                resetDescription = "Reset otomatis tiap 30–60 detik (2 RPM) & tiap 6 jam (100 req/6 jam, tanpa API key)"
            )
            else -> null
        }
    }

    /**
     * Tentukan bucket kuota gratis dari kombinasi `baseUrl` dan `modelName`.
     * Mengembalikan `null` untuk endpoint berbayar murni atau model lokal offline (Ollama/LM Studio/Termux).
     */
    fun resolveBucketId(baseUrl: String, modelName: String): String? {
        val url = baseUrl.trim().lowercase()
        val model = modelName.trim().lowercase()
        return when {
            url.contains("inceptionlabs.ai") -> BUCKET_INCEPTION_FREE
            url.contains("openrouter.ai") && (model == "openrouter/free" || model.endsWith(":free")) -> BUCKET_OPENROUTER_FREE
            url.contains("api.groq.com") -> BUCKET_GROQ_FREE
            url.contains("api.mistral.ai") -> BUCKET_MISTRAL_FREE
            url.contains("api.cerebras.ai") -> BUCKET_CEREBRAS_FREE
            url.contains("models.github.ai") || url.contains("models.inference.ai.azure.com") -> BUCKET_GITHUB_FREE
            url.contains("pollinations.ai") -> BUCKET_POLLINATIONS_FREE
            url.contains("generativelanguage.googleapis.com") &&
                (model == "gemini-3.5-flash-lite" || model == "gemini-flash-lite-latest" || model == "gemini-2.5-flash") ->
                BUCKET_GEMINI_FREE
            else -> null
        }
    }

    fun resolvePolicy(baseUrl: String, modelName: String): FreeQuotaPolicy? {
        val bucketId = resolveBucketId(baseUrl, modelName) ?: return null
        return getPolicyById(bucketId)
    }

    /**
     * Ambil status kuota terkini (sekaligus menjalankan auto-reset bila jendela waktu sudah lewat).
     */
    fun getStatus(
        baseUrl: String,
        modelName: String,
        nowMs: Long = System.currentTimeMillis()
    ): FreeQuotaStatus? {
        val bucketId = resolveBucketId(baseUrl, modelName) ?: return null
        return getStatusByBucket(bucketId, nowMs)
    }

    fun getStatusByBucket(
        bucketId: String,
        nowMs: Long = System.currentTimeMillis()
    ): FreeQuotaStatus? {
        val policy = getPolicyById(bucketId) ?: return null
        synchronized(lock) {
            val state = buckets.getOrPut(bucketId) { BucketState() }
            val mutated = refreshWindowsLocked(state, policy, nowMs)
            if (mutated) {
                persistLocked()
            }
            return buildStatusLocked(state, policy, nowMs)
        }
    }

    /**
     * Periksa apakah request baru diizinkan saat ini.
     * Jika limit per menit tercapai (tunggu singkat), `isShortMinuteWait = true` sehingga caller
     * dapat memilih menunggu otomatis beberapa detik atau langsung memberi info ke user.
     */
    fun checkBeforeRequest(
        baseUrl: String,
        modelName: String,
        nowMs: Long = System.currentTimeMillis()
    ): QuotaCheckResult {
        val bucketId = resolveBucketId(baseUrl, modelName) ?: return QuotaCheckResult(allowed = true)
        val policy = getPolicyById(bucketId) ?: return QuotaCheckResult(allowed = true)

        synchronized(lock) {
            val state = buckets.getOrPut(bucketId) { BucketState() }
            if (refreshWindowsLocked(state, policy, nowMs)) {
                persistLocked()
            }
            val status = buildStatusLocked(state, policy, nowMs)

            // 1. Cooldown dari respons 429 server selalu dipatuhi
            if (status.isServerCoolingDown) {
                val wait = status.serverCooldownRemainingMs
                val shortWait = wait in 1L..20_000L
                val reason = status.serverCooldownReason ?: "Batas rate-limit server tercapai."
                return QuotaCheckResult(
                    allowed = false,
                    waitMs = wait,
                    isShortMinuteWait = shortWait,
                    userMessage = buildLimitReachedMessage(policy, status, reason, wait),
                    status = status
                )
            }

            if (!_enforceLocalLimit.value) {
                return QuotaCheckResult(allowed = true, status = status)
            }

            // 2. Limit periode (harian / 6 jam)
            if (status.isPeriodExhausted) {
                val wait = status.periodResetInMs
                return QuotaCheckResult(
                    allowed = false,
                    waitMs = wait,
                    isShortMinuteWait = false,
                    userMessage = buildLimitReachedMessage(
                        policy = policy,
                        status = status,
                        reason = "Kuota gratis ${policy.periodLimit} request/${policy.periodLabel} sudah terpakai penuh.",
                        waitMs = wait
                    ),
                    status = status
                )
            }

            // 3. Limit per menit (RPM)
            if (status.isMinuteExhausted) {
                val wait = status.minuteResetInMs
                return QuotaCheckResult(
                    allowed = false,
                    waitMs = wait,
                    isShortMinuteWait = wait in 1L..20_000L,
                    userMessage = buildLimitReachedMessage(
                        policy = policy,
                        status = status,
                        reason = "Batas kecepatan ${policy.rpmLimit} request/menit tercapai.",
                        waitMs = wait
                    ),
                    status = status
                )
            }

            return QuotaCheckResult(allowed = true, status = status)
        }
    }

    /**
     * Catat request berhasil beserta pemakaian token & header rate-limit server (bila ada).
     */
    fun recordSuccess(
        baseUrl: String,
        modelName: String,
        headers: Headers? = null,
        tokensUsed: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val bucketId = resolveBucketId(baseUrl, modelName) ?: return
        val policy = getPolicyById(bucketId) ?: return

        synchronized(lock) {
            val state = buckets.getOrPut(bucketId) { BucketState() }
            refreshWindowsLocked(state, policy, nowMs)

            state.minuteTimestamps.add(nowMs)
            if (state.periodResetAtMs <= nowMs) {
                state.periodResetAtMs = computeNextPeriodResetMs(policy, nowMs)
            }
            state.periodUsed += 1
            state.serverRemainingOverride = state.serverRemainingOverride?.let { (it - 1).coerceAtLeast(0) }

            if (tokensUsed > 0L) {
                state.periodTokensUsed += tokensUsed
                state.totalTokensUsed += tokensUsed
            }

            // Bersihkan cooldown lama jika request sudah berhasil
            state.serverCooldownUntilMs = 0L
            state.serverCooldownReason = null

            // Sinkronisasi sisa kuota dari header server bila disediakan
            if (headers != null) {
                syncFromHeadersLocked(state, policy, headers, nowMs, isError429 = false)
            }

            persistLocked()
        }
        bumpRevision()
    }

    /**
     * Catat respons HTTP 429 (Too Many Requests) dan hitung waktu reset otomatis dari header/body.
     * Mengembalikan pesan ramah berbahasa Indonesia lengkap dengan hitung mundur reset.
     */
    fun recordRateLimitError(
        baseUrl: String,
        modelName: String,
        headers: Headers? = null,
        errorBody: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val bucketId = resolveBucketId(baseUrl, modelName)
        val policy = bucketId?.let { getPolicyById(it) }

        val lowerBody = errorBody.lowercase()
        val isDailyQuotaExhausted = lowerBody.contains("free-models-per-day") ||
            lowerBody.contains("per day") ||
            lowerBody.contains("daily") ||
            lowerBody.contains("requests per day") ||
            lowerBody.contains("rpd")

        val headerResetMs = headers?.let { extractResetEpochFromHeaders(it, nowMs) }
        val fallbackResetMs = when {
            isDailyQuotaExhausted && policy != null -> computeNextPeriodResetMs(policy, nowMs)
            isDailyQuotaExhausted -> nextUtcMidnightMs(nowMs)
            else -> nowMs + MINUTE_WINDOW_MS
        }
        val maxCapMs = nowMs + (2L * DAY_WINDOW_MS)
        val effectiveResetAt = (headerResetMs ?: fallbackResetMs).coerceIn(nowMs + 5_000L, maxCapMs)
        val waitMs = (effectiveResetAt - nowMs).coerceAtLeast(1_000L)

        val reason = when {
            isDailyQuotaExhausted -> "Kuota harian gratis di server telah habis."
            lowerBody.contains("upstream") || lowerBody.contains("temporarily rate-limited") ->
                "Server model gratis sedang padat (upstream rate-limit)."
            else -> "Batas request per waktu (HTTP 429) tercapai."
        }

        if (bucketId != null && policy != null) {
            var snapshot: FreeQuotaStatus
            synchronized(lock) {
                val state = buckets.getOrPut(bucketId) { BucketState() }
                refreshWindowsLocked(state, policy, nowMs)
                state.serverCooldownUntilMs = effectiveResetAt
                state.serverCooldownReason = reason
                if (isDailyQuotaExhausted) {
                    state.periodUsed = maxOf(state.periodUsed, policy.periodLimit)
                    if (state.periodResetAtMs <= nowMs) {
                        state.periodResetAtMs = effectiveResetAt
                    }
                }
                if (headers != null) {
                    syncFromHeadersLocked(state, policy, headers, nowMs, isError429 = true)
                }
                persistLocked()
                snapshot = buildStatusLocked(state, policy, nowMs)
            }
            bumpRevision()
            return buildLimitReachedMessage(policy, snapshot, reason, waitMs)
        }

        return "Batas pemakaian API tercapai (HTTP 429): $reason\n" +
            "Estimasi reset otomatis dalam ${formatCountdown(waitMs)}.\n" +
            "Saran: Tunggu hingga limit reset, atau beralih ke preset model gratis lain (Inception Labs Mercury 3, OpenRouter :free, Groq, atau Gemini Flash-Lite) di Pengaturan AI."
    }

    /**
     * Reset penghitung lokal untuk satu bucket (misalnya saat pengguna mengganti API key ke akun baru).
     * Tidak menghapus total token bila `resetTotalTokens = false`.
     */
    fun resetLocalCounter(bucketId: String, resetTotalTokens: Boolean = false) {
        synchronized(lock) {
            val existing = buckets[bucketId]
            buckets[bucketId] = BucketState(
                totalTokensUsed = if (resetTotalTokens) 0L else (existing?.totalTokensUsed ?: 0L)
            )
            persistLocked()
        }
        bumpRevision()
    }

    /** Reset semua state (terutama untuk pengujian unit). */
    fun resetAllForTesting() {
        synchronized(lock) {
            buckets.clear()
            _openRouterHighTier.value = false
            _enforceLocalLimit.value = true
            prefs?.edit()?.clear()?.apply()
        }
        bumpRevision()
    }

    private fun refreshWindowsLocked(
        state: BucketState,
        policy: FreeQuotaPolicy,
        nowMs: Long
    ): Boolean {
        var changed = false

        // 1. Bersihkan request yang sudah lewat dari jendela 60 detik
        val beforeMin = state.minuteTimestamps.size
        state.minuteTimestamps.removeAll { ts -> (nowMs - ts) >= MINUTE_WINDOW_MS || ts > nowMs + MINUTE_WINDOW_MS }
        if (state.minuteTimestamps.size != beforeMin) {
            changed = true
        }

        // 2. Auto-reset jendela periodik (harian / 6 jam / 00:00 UTC)
        if (state.periodResetAtMs > 0L && nowMs >= state.periodResetAtMs) {
            state.periodUsed = 0
            state.periodTokensUsed = 0L
            state.periodResetAtMs = 0L
            state.serverRemainingOverride = null
            changed = true
        }

        // 3. Auto-clear server cooldown setelah waktunya lewat
        if (state.serverCooldownUntilMs > 0L && nowMs >= state.serverCooldownUntilMs) {
            state.serverCooldownUntilMs = 0L
            state.serverCooldownReason = null
            changed = true
        }

        // Pastikan konsistensi bila ada periodUsed tanpa jadwal reset
        if (state.periodUsed > 0 && state.periodResetAtMs <= 0L) {
            state.periodResetAtMs = computeNextPeriodResetMs(policy, nowMs)
            changed = true
        }

        return changed
    }

    private fun buildStatusLocked(
        state: BucketState,
        policy: FreeQuotaPolicy,
        nowMs: Long
    ): FreeQuotaStatus {
        val minUsed = state.minuteTimestamps.size
        val minRem = (policy.rpmLimit - minUsed).coerceAtLeast(0)
        val oldestMin = state.minuteTimestamps.minOrNull()
        val minResetIn = if (oldestMin != null) {
            ((oldestMin + MINUTE_WINDOW_MS) - nowMs).coerceAtLeast(0L)
        } else {
            0L
        }

        val effectivePeriodResetAt = if (state.periodResetAtMs > nowMs) {
            state.periodResetAtMs
        } else if (policy.resetMode == QuotaResetMode.UTC_MIDNIGHT) {
            nextUtcMidnightMs(nowMs)
        } else {
            0L
        }
        val periodResetIn = if (effectivePeriodResetAt > nowMs) {
            (effectivePeriodResetAt - nowMs).coerceAtLeast(0L)
        } else {
            0L
        }

        val computedRemaining = (policy.periodLimit - state.periodUsed).coerceAtLeast(0)
        val finalPeriodRemaining = state.serverRemainingOverride
            ?.coerceIn(0, policy.periodLimit)
            ?.let { minOf(it, computedRemaining) }
            ?: computedRemaining
        val finalPeriodUsed = maxOf(state.periodUsed, policy.periodLimit - finalPeriodRemaining)
            .coerceIn(0, policy.periodLimit)

        val cooldownRem = if (state.serverCooldownUntilMs > nowMs) {
            state.serverCooldownUntilMs - nowMs
        } else {
            0L
        }

        return FreeQuotaStatus(
            policy = policy,
            minuteUsed = minUsed,
            minuteLimit = policy.rpmLimit,
            minuteRemaining = minRem,
            minuteResetInMs = minResetIn,
            periodUsed = finalPeriodUsed,
            periodLimit = policy.periodLimit,
            periodRemaining = finalPeriodRemaining,
            periodResetInMs = periodResetIn,
            periodResetAtMs = effectivePeriodResetAt,
            serverCooldownRemainingMs = cooldownRem,
            serverCooldownReason = if (cooldownRem > 0L) state.serverCooldownReason else null,
            periodTokensUsed = state.periodTokensUsed,
            totalTokensUsed = state.totalTokensUsed,
            enforceLocalLimit = _enforceLocalLimit.value
        )
    }

    private fun syncFromHeadersLocked(
        state: BucketState,
        policy: FreeQuotaPolicy,
        headers: Headers,
        nowMs: Long,
        isError429: Boolean
    ) {
        // Baca header sisa request harian bila tersedia (Groq, Cerebras, OpenRouter)
        val remainingDayStr = headers["x-ratelimit-remaining-requests-day"]
            ?: if (policy.bucketId == BUCKET_OPENROUTER_FREE && isError429) headers["X-RateLimit-Remaining"] else null
        val remainingDay = remainingDayStr?.trim()?.toIntOrNull()
        if (remainingDay != null && remainingDay >= 0) {
            state.serverRemainingOverride = remainingDay
        }

        val resetDayStr = headers["x-ratelimit-reset-requests-day"]
        val resetDayEpoch = parseResetValueToEpochMs(resetDayStr, nowMs)
        if (resetDayEpoch != null && resetDayEpoch > nowMs) {
            state.periodResetAtMs = resetDayEpoch
        }
    }

    private fun extractResetEpochFromHeaders(headers: Headers, nowMs: Long): Long? {
        val candidates = listOf(
            headers["Retry-After"],
            headers["X-RateLimit-Reset"],
            headers["x-ratelimit-reset-requests"],
            headers["x-ratelimit-reset-tokens"],
            headers["x-ratelimit-reset"]
        )
        for (raw in candidates) {
            val parsed = parseResetValueToEpochMs(raw, nowMs)
            if (parsed != null && parsed > nowMs) {
                return parsed
            }
        }
        return null
    }

    /**
     * Parsing fleksibel nilai header reset menjadi epoch milidetik:
     * - Epoch ms (mis. `1763510400000` dari OpenRouter `X-RateLimit-Reset`)
     * - Epoch detik (mis. `1763510400`)
     * - Detik relatif (mis. `45` dari `Retry-After`)
     * - Durasi gaya Go (mis. `"2m30s"`, `"14.5s"`, `"1h5m"`, `"850ms"` dari Groq/Cerebras)
     */
    fun parseResetValueToEpochMs(raw: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Coba angka murni lebih dulu
        trimmed.toDoubleOrNull()?.let { num ->
            if (num <= 0.0) return null
            return when {
                num >= 1_000_000_000_000.0 -> num.toLong()                    // Epoch ms
                num >= 1_000_000_000.0 -> (num * 1000.0).toLong()             // Epoch detik
                num <= 172_800.0 -> nowMs + (num * 1000.0).toLong()           // Delta detik (s.d. 48 jam)
                else -> null
            }
        }

        // Coba format durasi "1h2m3.5s" / "450ms"
        val unitRegex = Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s)", RegexOption.IGNORE_CASE)
        val matches = unitRegex.findAll(trimmed).toList()
        if (matches.isNotEmpty()) {
            var totalMs = 0.0
            for (m in matches) {
                val amount = m.groupValues[1].toDoubleOrNull() ?: continue
                when (m.groupValues[2].lowercase()) {
                    "h" -> totalMs += amount * 3_600_000.0
                    "m" -> totalMs += amount * 60_000.0
                    "s" -> totalMs += amount * 1_000.0
                    "ms" -> totalMs += amount
                }
            }
            if (totalMs > 0.0) {
                return nowMs + totalMs.toLong().coerceAtLeast(1_000L)
            }
        }
        return null
    }

    fun computeNextPeriodResetMs(policy: FreeQuotaPolicy, nowMs: Long): Long {
        return when (policy.resetMode) {
            QuotaResetMode.UTC_MIDNIGHT -> nextUtcMidnightMs(nowMs)
            QuotaResetMode.ROLLING_INTERVAL -> nowMs + policy.periodDurationMs
        }
    }

    fun nextUtcMidnightMs(nowMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun buildLimitReachedMessage(
        policy: FreeQuotaPolicy,
        status: FreeQuotaStatus,
        reason: String,
        waitMs: Long
    ): String {
        val countdown = formatCountdown(waitMs)
        return buildString {
            appendLine("Batas kuota tercapai — ${policy.providerTitle}")
            appendLine("Status: $reason")
            appendLine("Pemakaian: ${status.minuteUsed}/${status.minuteLimit} req/menit • ${status.periodUsed}/${status.periodLimit} req/${policy.periodLabel}")
            appendLine("Reset otomatis dalam: $countdown")
            append("Tunggu hingga kuota direset otomatis, atau beralih ke model gratis lain (Inception Labs Mercury 3, OpenRouter :free, Groq, Gemini Flash-Lite, atau Pollinations) melalui Pengaturan AI.")
        }
    }

    /**
     * Format sisa waktu milidetik menjadi teks ringkas Bahasa Indonesia (mis. "42 dtk", "4 mnt 12 dtk", "5 jam 20 mnt").
     */
    fun formatCountdown(remainingMs: Long): String {
        if (remainingMs <= 0L) return "siap sekarang"
        val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L && minutes > 0L -> "$hours jam $minutes mnt"
            hours > 0L -> "$hours jam"
            minutes > 0L && seconds > 0L -> "$minutes mnt $seconds dtk"
            minutes > 0L -> "$minutes mnt"
            else -> "$seconds dtk"
        }
    }

    /**
     * Format jumlah token besar agar ringkas & mudah dibaca (mis. "12.4K", "1.25M", "100M").
     */
    fun formatTokenCount(tokens: Long): String {
        val safe = tokens.coerceAtLeast(0L)
        return when {
            safe >= 1_000_000L -> {
                val m = safe / 1_000_000.0
                if (safe % 1_000_000L == 0L) "${safe / 1_000_000L}M"
                else String.format(Locale.US, "%.2fM", m)
            }
            safe >= 1_000L -> String.format(Locale.US, "%.1fK", safe / 1_000.0)
            else -> safe.toString()
        }
    }

    private fun persistLocked() {
        val sp = prefs ?: return
        try {
            val root = JSONObject()
            for ((id, st) in buckets) {
                val minArr = JSONArray()
                st.minuteTimestamps.forEach { minArr.put(it) }
                root.put(id, JSONObject().apply {
                    put("minuteTimestamps", minArr)
                    put("periodUsed", st.periodUsed)
                    put("periodResetAtMs", st.periodResetAtMs)
                    put("serverCooldownUntilMs", st.serverCooldownUntilMs)
                    if (st.serverCooldownReason != null) put("serverCooldownReason", st.serverCooldownReason)
                    if (st.serverRemainingOverride != null) put("serverRemainingOverride", st.serverRemainingOverride)
                    put("periodTokensUsed", st.periodTokensUsed)
                    put("totalTokensUsed", st.totalTokensUsed)
                })
            }
            sp.edit().putString(KEY_BUCKETS_JSON, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menyimpan state kuota: ${e.message}")
        }
    }

    private fun loadFromPrefsLocked() {
        val sp = prefs ?: return
        _openRouterHighTier.value = sp.getBoolean(KEY_OPENROUTER_UNLOCKED, false)
        _enforceLocalLimit.value = sp.getBoolean(KEY_ENFORCE_LOCAL_LIMIT, true)

        val raw = sp.getString(KEY_BUCKETS_JSON, null) ?: return
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val obj = root.optJSONObject(id) ?: continue
                val minList = mutableListOf<Long>()
                val arr = obj.optJSONArray("minuteTimestamps")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val ts = arr.optLong(i, 0L)
                        if (ts > 0L) minList.add(ts)
                    }
                }
                val remOverride = if (obj.has("serverRemainingOverride") && !obj.isNull("serverRemainingOverride")) {
                    obj.optInt("serverRemainingOverride")
                } else {
                    null
                }
                val reason = if (obj.has("serverCooldownReason") && !obj.isNull("serverCooldownReason")) {
                    obj.optString("serverCooldownReason").takeIf { it.isNotBlank() }
                } else {
                    null
                }
                buckets[id] = BucketState(
                    minuteTimestamps = minList,
                    periodUsed = obj.optInt("periodUsed", 0).coerceAtLeast(0),
                    periodResetAtMs = obj.optLong("periodResetAtMs", 0L).coerceAtLeast(0L),
                    serverCooldownUntilMs = obj.optLong("serverCooldownUntilMs", 0L).coerceAtLeast(0L),
                    serverCooldownReason = reason,
                    serverRemainingOverride = remOverride,
                    periodTokensUsed = obj.optLong("periodTokensUsed", 0L).coerceAtLeast(0L),
                    totalTokensUsed = obj.optLong("totalTokensUsed", 0L).coerceAtLeast(0L)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memuat state kuota: ${e.message}")
        }
    }
}
