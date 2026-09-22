package com.example.data

import android.content.Context
import android.util.Log
import com.example.JarvisApp
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.model.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChatSessionManager {
    private const val TAG = "ChatSessionManager"
    // Disimpan lewat PersistentStore (/sdcard/JARVIS/chats/...) agar TAHAN UNINSTALL.
    private const val SESSIONS_REL = "chats/jarvis_chat_sessions.json"
    private const val LEGACY_SESSIONS_FILE = "jarvis_chat_sessions.json"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    private val _currentSession = MutableStateFlow<ChatSession?>(null)
    val currentSession: StateFlow<ChatSession?> = _currentSession.asStateFlow()

    init {
        loadSessions()
    }

    private fun getStorageFile(): File {
        return File(JarvisApp.instance.filesDir, LEGACY_SESSIONS_FILE)
    }

    private fun createDefaultGreetingMessage(): ChatMessage {
        return ChatMessage(
            sender = ChatSender.SYSTEM,
            text = "Halo! Saya JARVIS-HP AI Assistant. Anda dapat langsung mengobrol dan memberikan instruksi otomasi HP di sini (misal: 'Buka YouTube', 'Ketik halo', 'Cek baterai', dll.)."
        )
    }

    fun loadSessions() {
        try {
            val jsonString = PersistentStore.read(SESSIONS_REL)
            if (!jsonString.isNullOrBlank()) {
                val jsonArray = JSONArray(jsonString)
                val list = mutableListOf<ChatSession>()

                for (i in 0 until jsonArray.length()) {
                    val sessionObj = jsonArray.getJSONObject(i)
                    val id = sessionObj.optString("id", java.util.UUID.randomUUID().toString())
                    val title = sessionObj.optString("title", "Sesi Percakapan")
                    val createdAt = sessionObj.optLong("createdAt", System.currentTimeMillis())
                    val updatedAt = sessionObj.optLong("updatedAt", System.currentTimeMillis())

                    val messagesArray = sessionObj.optJSONArray("messages") ?: JSONArray()
                    val msgList = mutableListOf<ChatMessage>()
                    for (j in 0 until messagesArray.length()) {
                        val mObj = messagesArray.getJSONObject(j)
                        val senderStr = mObj.optString("sender", "SYSTEM")
                        val sender = when (senderStr) {
                            "USER" -> ChatSender.USER
                            "AI" -> ChatSender.AI
                            else -> ChatSender.SYSTEM
                        }
                        msgList.add(
                            ChatMessage(
                                id = mObj.optString("id", java.util.UUID.randomUUID().toString()),
                                sender = sender,
                                text = mObj.optString("text", ""),
                                timestamp = mObj.optLong("timestamp", System.currentTimeMillis()),
                                isExecutingAction = mObj.optBoolean("isExecutingAction", false),
                                actionToolName = if (mObj.has("actionToolName") && !mObj.isNull("actionToolName")) mObj.getString("actionToolName") else null,
                                actionResult = if (mObj.has("actionResult") && !mObj.isNull("actionResult")) mObj.getString("actionResult") else null,
                                thinkingProcess = if (mObj.has("thinkingProcess") && !mObj.isNull("thinkingProcess")) mObj.getString("thinkingProcess") else null,
                                attachmentUri = if (mObj.has("attachmentUri") && !mObj.isNull("attachmentUri")) mObj.getString("attachmentUri") else null,
                                attachmentName = if (mObj.has("attachmentName") && !mObj.isNull("attachmentName")) mObj.getString("attachmentName") else null,
                                attachmentMimeType = if (mObj.has("attachmentMimeType") && !mObj.isNull("attachmentMimeType")) mObj.getString("attachmentMimeType") else null
                            )
                        )
                    }

                    list.add(
                        ChatSession(
                            id = id,
                            title = title,
                            createdAt = createdAt,
                            updatedAt = updatedAt,
                            messages = if (msgList.isEmpty()) listOf(createDefaultGreetingMessage()) else msgList
                        )
                    )
                }

                if (list.isNotEmpty()) {
                    _sessions.value = list
                    val first = list.first()
                    _currentSessionId.value = first.id
                    _currentSession.value = first
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chat sessions", e)
        }

        // Default initial session
        val defaultSession = ChatSession(
            title = "Sesi Percakapan Utama",
            messages = listOf(createDefaultGreetingMessage())
        )
        _sessions.value = listOf(defaultSession)
        _currentSessionId.value = defaultSession.id
        _currentSession.value = defaultSession
        saveSessionsToDisk()
    }

    private var saveJob: kotlinx.coroutines.Job? = null

    private fun saveSessionsToDisk() {
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(400) // Debounce writes
            try {
                val jsonArray = JSONArray()
                for (session in _sessions.value) {
                    val sObj = JSONObject().apply {
                        put("id", session.id)
                        put("title", session.title)
                        put("createdAt", session.createdAt)
                        put("updatedAt", session.updatedAt)

                        val msgArray = JSONArray()
                        // Cap messages to latest 60 to prevent unbounded bloat
                        val cappedMessages = if (session.messages.size > 60) session.messages.takeLast(60) else session.messages
                        for (m in cappedMessages) {
                            val mObj = JSONObject().apply {
                                put("id", m.id)
                                put("sender", m.sender.name)
                                // Cap individual message text in storage if it's an extreme log dump
                                val safeText = if (m.text.length > 8000) m.text.take(8000) + "... [truncated]" else m.text
                                put("text", safeText)
                                put("timestamp", m.timestamp)
                                put("isExecutingAction", m.isExecutingAction)
                                m.actionToolName?.let { put("actionToolName", it) }
                                m.actionResult?.let { put("actionResult", if (it.length > 2000) it.take(2000) + "..." else it) }
                                m.thinkingProcess?.let { put("thinkingProcess", if (it.length > 2000) it.take(2000) + "..." else it) }
                                m.attachmentUri?.let { put("attachmentUri", it) }
                                m.attachmentName?.let { put("attachmentName", it) }
                                m.attachmentMimeType?.let { put("attachmentMimeType", it) }
                            }
                            msgArray.put(mObj)
                        }
                        put("messages", msgArray)
                    }
                    jsonArray.put(sObj)
                }
                PersistentStore.write(SESSIONS_REL, jsonArray.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error saving sessions to disk", e)
            }
        }
    }

    fun createSession(title: String? = null): ChatSession {
        val sessionCount = _sessions.value.size + 1
        val finalTitle = title?.takeIf { it.isNotBlank() } ?: "Sesi #$sessionCount"
        val newSession = ChatSession(
            title = finalTitle,
            messages = listOf(createDefaultGreetingMessage())
        )
        val updatedList = listOf(newSession) + _sessions.value
        _sessions.value = updatedList
        _currentSessionId.value = newSession.id
        _currentSession.value = newSession
        saveSessionsToDisk()
        return newSession
    }

    fun switchSession(sessionId: String): ChatSession? {
        val target = _sessions.value.find { it.id == sessionId } ?: return null
        _currentSessionId.value = target.id
        _currentSession.value = target
        return target
    }

    fun deleteSession(sessionId: String) {
        val currentList = _sessions.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == sessionId }
        if (index == -1) return

        currentList.removeAt(index)

        if (currentList.isEmpty()) {
            val fresh = ChatSession(
                title = "Sesi #1",
                messages = listOf(createDefaultGreetingMessage())
            )
            currentList.add(fresh)
        }

        _sessions.value = currentList

        // If the deleted session was currently active, switch to the first available session
        if (_currentSessionId.value == sessionId) {
            val next = currentList.first()
            _currentSessionId.value = next.id
            _currentSession.value = next
        } else {
            _currentSession.value = currentList.find { it.id == _currentSessionId.value }
        }

        saveSessionsToDisk()
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return

        val updated = _sessions.value.map { session ->
            if (session.id == sessionId) {
                session.copy(title = trimmed, updatedAt = System.currentTimeMillis())
            } else {
                session
            }
        }
        _sessions.value = updated
        _currentSession.value = updated.find { it.id == _currentSessionId.value }
        saveSessionsToDisk()
    }

    fun updateMessagesForCurrentSession(messages: List<ChatMessage>) {
        val currentId = _currentSessionId.value
        val currentSessionObj = _sessions.value.find { it.id == currentId } ?: return

        // Auto-title if title is generic and there is a user message
        var title = currentSessionObj.title
        val firstUserMsg = messages.firstOrNull { it.sender == ChatSender.USER }
        if (firstUserMsg != null && (title.startsWith("Sesi #") || title == "Sesi Percakapan Utama" || title == "Sesi Baru")) {
            val snippet = firstUserMsg.text.replace("\n", " ").trim()
            title = if (snippet.length > 28) snippet.take(28) + "..." else snippet
        }

        val updatedSession = currentSessionObj.copy(
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = messages
        )

        val updatedList = _sessions.value.map {
            if (it.id == currentId) updatedSession else it
        }

        _sessions.value = updatedList
        _currentSession.value = updatedSession
        saveSessionsToDisk()
    }

    fun clearCurrentSessionMessages() {
        val currentId = _currentSessionId.value
        val resetMessages = listOf(createDefaultGreetingMessage())
        val updatedList = _sessions.value.map {
            if (it.id == currentId) {
                it.copy(
                    messages = resetMessages,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                it
            }
        }
        _sessions.value = updatedList
        _currentSession.value = updatedList.find { it.id == currentId }
        saveSessionsToDisk()
    }
}
