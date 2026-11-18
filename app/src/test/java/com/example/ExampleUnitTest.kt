package com.example

import com.example.data.AiConfigManager
import com.example.data.FreeQuotaManager
import okhttp3.Headers
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testActionJsonParsing() {
    val sampleText = """
      <thought>
      Pengguna meminta membuka YouTube dan mencari Minecraft.
      Langkah 1: Buka aplikasi YouTube.
      </thought>
      Saya akan membuka YouTube sekarang.
      ```json:action
      {
        "tool": "open_app",
        "params": {
          "package_name": "com.google.android.youtube"
        }
      }
      ```
    """.trimIndent()

    val thoughtRegex = Regex("<thought>([\\s\\S]*?)</thought>", RegexOption.IGNORE_CASE)
    val thoughtMatch = thoughtRegex.find(sampleText)
    assertNotNull(thoughtMatch)
    assertTrue(thoughtMatch!!.groupValues[1].contains("Pengguna meminta membuka YouTube"))

    val actionRegex = Regex("```json:action([\\s\\S]*?)```")
    val actionMatch = actionRegex.find(sampleText)
    assertNotNull(actionMatch)
    val actionJson = JSONObject(actionMatch!!.groupValues[1].trim())
    assertEquals("open_app", actionJson.getString("tool"))
    assertEquals("com.google.android.youtube", actionJson.getJSONObject("params").getString("package_name"))
  }

  @Test
  fun testAiConfigPresetsAndEndpointDetection() {
    val geminiUrl = "https://generativelanguage.googleapis.com"
    val groqUrl = "https://api.groq.com/openai/v1"
    val localOllamaUrl = "http://10.0.2.2:11434/v1"

    assertTrue(AiConfigManager.isGeminiEndpoint(geminiUrl))
    assertFalse(AiConfigManager.isGeminiEndpoint(groqUrl))
    assertFalse(AiConfigManager.isGeminiEndpoint(localOllamaUrl))

    assertTrue(AiConfigManager.PRESETS.isNotEmpty())
    val defaultPreset = AiConfigManager.PRESETS.first { it.id == "gemini_flash" }
    assertEquals("gemini-3.5-flash", defaultPreset.defaultModel)
  }

  @Test
  fun testFreeModelsAndInceptionLabsPortal() {
    assertEquals(
      "https://platform.inceptionlabs.ai/auth/login?callbackUrl=%2F",
      AiConfigManager.INCEPTION_LABS_LOGIN_URL
    )

    val freePresets = AiConfigManager.freeModels()
    assertTrue("Should expose multiple free-tier presets", freePresets.size >= 8)

    val inception = freePresets.first { it.id == "inception_mercury_3" }
    assertTrue(AiConfigManager.isInceptionEndpoint(inception.baseUrl))
    assertEquals(AiConfigManager.INCEPTION_LABS_LOGIN_URL, inception.apiKeyPortalUrl)
    assertEquals("mercury-3", inception.defaultModel)
    assertTrue(AiConfigManager.requiresApiKey(inception.baseUrl, inception.defaultModel))

    val orAuto = freePresets.first { it.id == "openrouter_free_auto" }
    assertTrue(AiConfigManager.isOpenRouterFreeModel(orAuto.baseUrl, orAuto.defaultModel))
    assertTrue(AiConfigManager.isOpenRouterFreeModel(orAuto.baseUrl, "nvidia/nemotron-3.5-super-vl:free"))
    assertFalse(AiConfigManager.isOpenRouterFreeModel(orAuto.baseUrl, "anthropic/claude-sonnet-4"))

    val geminiFree = freePresets.first { it.id == "gemini_2_5_flash" }
    assertEquals("gemini-3.5-flash-lite", geminiFree.defaultModel)
    assertEquals(
      FreeQuotaManager.BUCKET_GEMINI_FREE,
      FreeQuotaManager.resolveBucketId(geminiFree.baseUrl, geminiFree.defaultModel)
    )

    val pollinations = freePresets.first { it.id == "pollinations_free" }
    assertEquals("openai-fast", pollinations.defaultModel)
    assertFalse(AiConfigManager.requiresApiKey(pollinations.baseUrl, pollinations.defaultModel))
    assertEquals(
      "https://text.pollinations.ai/openai",
      AiConfigManager.resolveChatCompletionsEndpoint(pollinations.baseUrl, hasApiKey = false)
    )
    assertEquals(
      "https://gen.pollinations.ai/v1/chat/completions",
      AiConfigManager.resolveChatCompletionsEndpoint(pollinations.baseUrl, hasApiKey = true)
    )
    assertEquals(
      "https://api.inceptionlabs.ai/v1/chat/completions",
      AiConfigManager.resolveChatCompletionsEndpoint(inception.baseUrl, hasApiKey = true)
    )
    assertEquals(
      "https://openrouter.ai/api/v1/chat/completions",
      AiConfigManager.resolveChatCompletionsEndpoint(orAuto.baseUrl, hasApiKey = true)
    )
  }

  @Test
  fun testFreeQuotaAutoResetWindowsAndRateLimitHeaders() {
    FreeQuotaManager.resetAllForTesting()
    val orUrl = AiConfigManager.OPENROUTER_BASE_URL
    val orModel = "openrouter/free"
    val baseMs = 1_763_460_000_000L // siang hari UTC

    // 1. Batas 20 req/menit OpenRouter :free & reset otomatis setelah 60 detik
    for (i in 0 until 20) {
      val check = FreeQuotaManager.checkBeforeRequest(orUrl, orModel, baseMs + i * 100L)
      assertTrue("Request #$i must be allowed", check.allowed)
      FreeQuotaManager.recordSuccess(orUrl, orModel, tokensUsed = 500L, nowMs = baseMs + i * 100L)
    }
    val blockedMin = FreeQuotaManager.checkBeforeRequest(orUrl, orModel, baseMs + 5_000L)
    assertFalse("21st request inside 60s should be rate-limited", blockedMin.allowed)
    assertTrue(blockedMin.waitMs > 0L)

    // Setelah 61 detik berlalu, jendela per-menit reset otomatis sementara hitungan harian tetap 20/50
    val afterMinMs = baseMs + 61_000L
    val afterMinCheck = FreeQuotaManager.checkBeforeRequest(orUrl, orModel, afterMinMs)
    assertTrue("Should auto-reset after 60s", afterMinCheck.allowed)
    assertEquals(30, afterMinCheck.status?.periodRemaining)

    // 2. Habiskan sisa 30 request harian (tersebar antar menit) -> terblokir sampai 00:00 UTC
    var cursorMs = afterMinMs
    for (i in 0 until 30) {
      cursorMs += 4_000L
      FreeQuotaManager.recordSuccess(orUrl, orModel, nowMs = cursorMs)
    }
    val blockedDay = FreeQuotaManager.checkBeforeRequest(orUrl, orModel, cursorMs + 65_000L)
    assertFalse("51st request on same UTC day should be blocked", blockedDay.allowed)
    assertFalse(blockedDay.isShortMinuteWait)

    // Tepat pada 00:00 UTC berikutnya -> kuota harian otomatis kembali penuh 50/50
    val nextMidnight = FreeQuotaManager.nextUtcMidnightMs(cursorMs)
    val resetDayCheck = FreeQuotaManager.checkBeforeRequest(orUrl, orModel, nextMidnight + 1_000L)
    assertTrue("Should auto-reset at UTC midnight", resetDayCheck.allowed)
    assertEquals(50, resetDayCheck.status?.periodRemaining)

    // 3. Inception Labs melacak pemakaian bonus 100 Juta token & header 429 Retry-After
    val incUrl = AiConfigManager.INCEPTION_LABS_BASE_URL
    val incModel = AiConfigManager.INCEPTION_LABS_DEFAULT_MODEL
    FreeQuotaManager.recordSuccess(incUrl, incModel, tokensUsed = 12_500L, nowMs = baseMs)
    val incStatus = FreeQuotaManager.getStatus(incUrl, incModel, baseMs + 1_000L)
    assertNotNull(incStatus)
    assertEquals(12_500L, incStatus!!.totalTokensUsed)
    assertEquals(FreeQuotaManager.INCEPTION_FREE_TOKEN_GRANT, incStatus.policy.tokenGrantLimit)

    val headers429 = Headers.headersOf("Retry-After", "15")
    FreeQuotaManager.recordRateLimitError(incUrl, incModel, headers429, "rate limit", baseMs + 2_000L)
    assertFalse(FreeQuotaManager.checkBeforeRequest(incUrl, incModel, baseMs + 5_000L).allowed)
    assertTrue(FreeQuotaManager.checkBeforeRequest(incUrl, incModel, baseMs + 18_000L).allowed)

    // 4. Parsing durasi gaya Go (Groq/Cerebras)
    val parsedGo = FreeQuotaManager.parseResetValueToEpochMs("1m30s", baseMs)
    assertEquals(baseMs + 90_000L, parsedGo)
  }
}
