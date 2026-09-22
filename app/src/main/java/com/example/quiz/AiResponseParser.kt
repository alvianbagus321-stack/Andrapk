package com.example.quiz

import org.json.JSONObject

/**
 * Parser respons AI menjadi QuizAnswerResult.
 * AI diminta membalas JSON murni; parser ini toleran terhadap
 * code-fence (```json ... ```) dan teks lain di sekitar JSON.
 */
object AiResponseParser {

    fun extractJsonBlock(text: String): JSONObject? {
        if (text.isBlank()) return null
        // 1) coba langsung
        try {
            return JSONObject(text.trim())
        } catch (_: Exception) {}
        // 2) ambil dari fence ``` ... ```
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        if (fenced != null) {
            try {
                return JSONObject(fenced.groupValues[1].trim())
            } catch (_: Exception) {}
        }
        // 3) ambil dari { pertama sampai } terakhir
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end > start) {
            try {
                return JSONObject(text.substring(start, end + 1))
            } catch (_: Exception) {}
        }
        return null
    }

    fun toAnswerResult(json: JSONObject?): QuizAnswerResult? {
        if (json == null) return null
        val optionsJson = json.optJSONObject("options")
        val options = LinkedHashMap<String, String>()
        if (optionsJson != null) {
            val keys = optionsJson.keys()
            while (keys.hasNext()) {
                val k = keys.next().uppercase().take(1)
                options[k] = optionsJson.optString(k, "").trim()
            }
        }
        val confidence = when (val c = json.opt("confidence")) {
            is Number -> c.toFloat().coerceIn(0f, 1f)
            else -> (json.optString("confidence").toDoubleOrNull() ?: 0f).toFloat().coerceIn(0f, 1f)
        }
        return QuizAnswerResult(
            question = json.optString("question", "").trim(),
            options = options,
            answer = json.optString("answer", "").trim().uppercase().take(4),
            answerText = json.optString("answerText", "").trim(),
            explanation = json.optString("explanation", "").trim(),
            confidence = confidence
        )
    }
}
