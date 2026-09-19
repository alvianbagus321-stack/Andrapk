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
    private const val MEMORY_FILE_NAME = "jarvis_memory.md"
    private const val ARCHIVE_FILE_NAME = "jarvis_memory_archive.md"
    private const val MAX_MEMORY_CHARS_THRESHOLD = 3500

    private val _memoryState = MutableStateFlow("")
    val memoryState: StateFlow<String> = _memoryState.asStateFlow()

    init {
        loadMemoryFromFile()
    }

    private fun getMemoryFile(): File {
        val context = JarvisApp.instance
        return File(context.filesDir, MEMORY_FILE_NAME)
    }

    private fun getArchiveFile(): File {
        val context = JarvisApp.instance
        return File(context.filesDir, ARCHIVE_FILE_NAME)
    }

    fun loadMemoryFromFile(): String {
        return try {
            val file = getMemoryFile()
            if (!file.exists()) {
                val initialContent = """
                    # 🧠 JARVIS Permanent Memory Bank (.md)
                    
                    ## 👤 User Preferences
                    - Bahasa utama: Indonesia
                    
                    ## 📱 Device Context & Tools
                    - Sistem Otomasi: Accessibility Service + Shizuku/ADB
                    
                    ## 📝 Saved Notes & Preferences
                    - Memori tersimpan secara otomatis dalam format Markdown yang hemat token.
                """.trimIndent()
                file.writeText(initialContent)
                _memoryState.value = initialContent
                initialContent
            } else {
                val content = file.readText()
                _memoryState.value = content
                content
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
            val file = getMemoryFile()
            var currentContent = if (file.exists()) file.readText() else ""
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
                file.writeText(newContent)
                _memoryState.value = newContent
            } else {
                // Add new category section
                val newSection = "\n\n$headerTitle\n$formattedLine"
                val newContent = currentContent + newSection
                file.writeText(newContent)
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
            val file = getMemoryFile()
            if (!file.exists()) return "File memori belum ada."
            val content = file.readText()

            if (content.length > MAX_MEMORY_CHARS_THRESHOLD) {
                val archiveFile = getArchiveFile()
                val timestamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                archiveFile.appendText("\n\n--- 📦 AUTO ARCHIVE ENTRY ($timestamp) ---\n$content")

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

                file.writeText(condensedContent)
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

    fun recallMemory(query: String?): String {
        val fullMemory = getMemoryMarkdown()
        if (query.isNullOrBlank()) return fullMemory

        val matches = fullMemory.lines().filter { line ->
            line.contains(query, ignoreCase = true) || line.startsWith("#")
        }

        return if (matches.isNotEmpty()) {
            matches.joinToString("\n")
        } else {
            "Tidak ditemukan memori spesifik untuk kata kunci '$query'. Berikut memori lengkap:\n\n$fullMemory"
        }
    }

    fun clearMemory(): String {
        return try {
            val file = getMemoryFile()
            if (file.exists()) {
                file.delete()
            }
            loadMemoryFromFile()
            "Seluruh memori (.md) telah dibersihkan."
        } catch (e: Exception) {
            "Gagal membersihkan memori: ${e.localizedMessage}"
        }
    }
}
