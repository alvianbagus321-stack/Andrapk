package com.example.data

import android.content.Context
import android.util.Log
import com.example.JarvisApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Token-Efficient Persistent Markdown Memory Manager (.md).
 * Stores AI memories, user preferences, custom rules, and past task contexts
 * in a structured, concise Markdown format ('jarvis_memory.md') that is automatically
 * injected into AI system prompts across sessions.
 */
object JarvisMemoryManager {

    private const val TAG = "JarvisMemoryManager"
    // Disimpan lewat PersistentStore (/sdcard/JARVIS/memory/...) agar TAHAN UNINSTALL.
    private const val MEMORY_REL = "memory/jarvis_memory.md"
    private const val ARCHIVE_REL = "memory/jarvis_memory_archive.md"
    private const val LEGACY_MEMORY_FILE_NAME = "jarvis_memory.md"
    private const val MAX_MEMORY_CHARS_THRESHOLD = 3500

    private val _memoryState = MutableStateFlow("")
    val memoryState: StateFlow<String> = _memoryState.asStateFlow()

    init {
        // Migrasi data lama (file internal pra-PersistentStore) agar tidak hilang.
        try {
            com.example.data.PersistentStore.migrateLegacyFile(
                File(JarvisApp.instance.filesDir, LEGACY_MEMORY_FILE_NAME),
                MEMORY_REL
            )
        } catch (_: Exception) {}
        loadMemoryFromFile()
    }

    fun loadMemoryFromFile(): String {
        return try {
            val existing = PersistentStore.read(MEMORY_REL)
            if (existing == null) {
                val initialContent = """
                    # 🧠 JARVIS Permanent Memory Bank (.md)
                    
                    ## 👤 User Preferences
                    - Bahasa utama: Indonesia
                    
                    ## 📱 Device Context & Tools
                    - Sistem Otomasi: Accessibility Service + Shizuku/ADB
                    
                    ## 📝 Saved Notes & Preferences
                    - Memori tersimpan secara otomatis dalam format Markdown yang hemat token.
                """.trimIndent()
                PersistentStore.write(MEMORY_REL, initialContent)
                _memoryState.value = initialContent
                initialContent
            } else {
                _memoryState.value = existing
                existing
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading memory file", e)
            _memoryState.value
        }
    }

    fun getMemoryMarkdown(): String {
        val current = _memoryState.value
        return if (current.isBlank()) loadMemoryFromFile() else current
    }

    fun saveMemory(category: String, content: String): String {
        return try {
            var currentContent = PersistentStore.read(MEMORY_REL) ?: ""
            if (currentContent.isBlank()) {
                currentContent = "# 🧠 JARVIS Permanent Memory Bank (.md)\n"
            }

            val cleanCategory = category.trim().ifEmpty { "Catatan Umum" }
            val headerTitle = "## 📌 $cleanCategory"

            val timestamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val formattedLine = "- [$timestamp] ${content.trim()}"

            if (currentContent.contains(headerTitle)) {
                // Append under existing category header
                val parts = currentContent.split(headerTitle)
                val beforeHeader = parts[0]
                val afterHeader = parts[1]
                val newContent = "$beforeHeader$headerTitle\n$formattedLine\n$afterHeader"
                PersistentStore.write(MEMORY_REL, newContent)
                _memoryState.value = newContent
            } else {
                // Add new category section
                val newSection = "\n\n$headerTitle\n$formattedLine"
                val newContent = currentContent + newSection
                PersistentStore.write(MEMORY_REL, newContent)
                _memoryState.value = newContent
            }

            Log.d(TAG, "Successfully saved memory under category '$cleanCategory'")
            checkAndAutoArchiveMemory()
            "Memori berhasil disimpan dalam format Markdown (.md) di kategori '$cleanCategory'."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memory", e)
            "Gagal menyimpan memori: ${e.localizedMessage}"
        }
    }

    /**
     * Checks if memory file size exceeds the threshold and compresses old entries
     * into 'jarvis_memory_archive.md' while keeping active memory token-lean.
     */
    fun checkAndAutoArchiveMemory(): String {
        return try {
            val content = PersistentStore.read(MEMORY_REL)
                ?: return "File memori belum ada."

            if (content.length > MAX_MEMORY_CHARS_THRESHOLD) {
                val timestamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val existingArchive = PersistentStore.read(ARCHIVE_REL) ?: ""
                PersistentStore.write(ARCHIVE_REL, "$existingArchive\n\n--- 📦 AUTO ARCHIVE ENTRY ($timestamp) ---\n$content")

                val lines = content.lines()
                val headerLines = lines.filter { it.startsWith("#") || it.contains("User Preferences") || it.contains("Device Context") }
                val bulletLines = lines.filter { it.trim().startsWith("- ") }

                val recentBullets = bulletLines.takeLast(12)
                val archivedCount = bulletLines.size - recentBullets.size

                val condensedContent = buildString {
                    appendLine("# 🧠 JARVIS Permanent Memory Bank (.md)")
                    appendLine()
                    appendLine("## 📦 Ringkasan Arsip Memori (Auto-Summarized Archive)")
                    appendLine("- [$timestamp] $archivedCount catatan lama telah dipadatkan ke 'jarvis_memory_archive.md' untuk menjaga respon AI tetap cepat dan hemat token.")
                    appendLine()
                    appendLine("## 📌 Catatan & Preferensi Terbaru")
                    recentBullets.forEach { bullet ->
                        appendLine(bullet)
                    }
                }

                PersistentStore.write(MEMORY_REL, condensedContent)
                _memoryState.value = condensedContent
                Log.i(TAG, "Auto-archived $archivedCount memory entries due to storage threshold.")
                "📦 Memori lama ($archivedCount entri) telah diarsipkan otomatis ke 'jarvis_memory_archive.md' agar AI tetap cepat & efisien token."
            } else {
                "Ukuran memori (${content.length} karakter) masih dalam batas efisien (< $MAX_MEMORY_CHARS_THRESHOLD karakter)."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during memory auto-archive", e)
            "Gagal melakukan pengarsipan memori: ${e.localizedMessage}"
        }
    }

    /**
     * Recall memori dengan pencarian per-kata (OR, case-insensitive) + ranking relevansi.
     * Dulu query utuh 4+ kata ("environment uid termux python") gagal karena dicocokkan
     * sebagai SATU substring per baris — fakta yang tersimpan di baris berbeda tak pernah match.
     * Sekarang: tiap kata = keyword tersendiri; baris dinilai dari jumlah keyword yang cocok
     * (makin banyak makin relevan), keyword di-highlight bold, header seksi tetap ditampilkan.
     */
    fun recallMemory(query: String?): String {
        val fullMemory = getMemoryMarkdown()
        if (query.isNullOrBlank()) return fullMemory

        // Tokenisasi query per-kata: buang tanda baca, minimal 2 karakter, unik
        val keywords = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }
            .distinct()
        if (keywords.isEmpty()) return fullMemory

        // Skor tiap baris = jumlah keyword yang muncul di baris itu (case-insensitive)
        val scored = fullMemory.lines().map { line ->
            val lower = line.lowercase()
            val hits = keywords.filter { lower.contains(it) }
            line to hits
        }

        val matched = scored.filter { it.second.isNotEmpty() }
        if (matched.isEmpty()) {
            return "Tidak ditemukan memori yang cocok untuk kata kunci: ${keywords.joinToString(", ")}. Berikut memori lengkap:\n\n$fullMemory"
        }

        // Ranking relevansi: baris dengan keyword terbanyak di atas (stable sort —
        // baris ber-score sama tetap urut asli). Header seksi (0 hit) tetap ikut sebagai konteks.
        val ranked = (scored.filter { it.second.isNotEmpty() } + scored.filter { it.second.isEmpty() && it.first.startsWith("#") })
            .sortedByDescending { it.second.size }

        // Highlight keyword (bold markdown) — huruf asli dipertahankan
        val highlighted = ranked.map { (line, hits) ->
            if (hits.isEmpty()) line
            else {
                var out = line
                for (kw in hits) {
                    out = out.replace(Regex(Regex.escape(kw), setOf(RegexOption.IGNORE_CASE))) { m -> "**${m.value}**" }
                }
                out
            }
        }

        val totalHits = matched.sumOf { it.second.size }
        return highlighted.joinToString("\n") +
                "\n\n🔎 (${matched.size} baris cocok, $totalHits kecocokan keyword dari ${keywords.size} kata; diurutkan berdasarkan relevansi)"
    }

    fun clearMemory(): String {
        return try {
            PersistentStore.delete(MEMORY_REL)
            PersistentStore.delete(ARCHIVE_REL)
            loadMemoryFromFile()
            "Seluruh memori (.md) telah dibersihkan."
        } catch (e: Exception) {
            "Gagal membersihkan memori: ${e.localizedMessage}"
        }
    }
}
