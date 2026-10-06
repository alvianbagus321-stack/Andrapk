package com.example.quiz

/**
 * Parser teks OCR menjadi soal + opsi jawaban (heuristik umum soal kuis:
 * "A. teks" / "A) teks" / "(A) teks" / "A - teks", juga angka "1.").
 */
object QuestionParser {

    private val optionRegex = Regex(
        """^\(?([A-Da-d])[).\]:\-]?\s*[\.\)\:\-]?\s+(.{1,160})$"""
    )

    fun parse(ocrText: String): ParsedQuestion? {
        if (ocrText.isBlank()) return null
        val lines = ocrText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val options = LinkedHashMap<String, String>()
        val questionLines = mutableListOf<String>()
        var inOptions = false
        var currentKey: String? = null

        for (line in lines) {
            val match = optionRegex.find(line)
            if (match != null) {
                val key = match.groupValues[1].uppercase()
                val text = match.groupValues[2].trim()
                // Mulai blok opsi hanya jika key urut masuk akal (A/B/C/D)
                if (options.isEmpty() && key != "A" && key != "B") {
                    questionLines.add(line); continue
                }
                inOptions = true
                currentKey = key
                options[key] = text
            } else if (inOptions && currentKey != null) {
                // Lanjutan teks opsi yang wrap ke baris baru
                options[currentKey] = (options[currentKey] ?: "") + " " + line
            } else {
                questionLines.add(line)
            }
        }

        val questionText = questionLines
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)

        if (options.size < 2 || questionText.isBlank()) return null
        return ParsedQuestion(questionText, options, ocrText)
    }
}
