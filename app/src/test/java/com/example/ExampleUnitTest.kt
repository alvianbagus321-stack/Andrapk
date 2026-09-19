package com.example

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

    assertTrue(com.example.data.AiConfigManager.isGeminiEndpoint(geminiUrl))
    assertFalse(com.example.data.AiConfigManager.isGeminiEndpoint(groqUrl))
    assertFalse(com.example.data.AiConfigManager.isGeminiEndpoint(localOllamaUrl))

    assertTrue(com.example.data.AiConfigManager.PRESETS.isNotEmpty())
    val defaultPreset = com.example.data.AiConfigManager.PRESETS.first { it.id == "gemini_flash" }
    assertEquals("gemini-3.5-flash", defaultPreset.defaultModel)
  }
}

