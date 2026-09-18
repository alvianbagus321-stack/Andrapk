package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApp
import com.example.model.ServerLogItem
import com.example.model.SystemTelemetry
import com.example.model.UiElementInfo
import com.example.service.JarvisAccessibilityService
import com.example.service.ScreenshotManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class JarvisViewModel : ViewModel() {

    private val repository = JarvisApp.repository

    val isAccessibilityConnected: StateFlow<Boolean> = JarvisAccessibilityService.isConnected
    val isMediaProjectionActive: StateFlow<Boolean> = ScreenshotManager.isMediaProjectionActive
    val isServerRunning: StateFlow<Boolean> = repository.isServerRunning
    val port: StateFlow<Int> = repository.port
    val token: StateFlow<String> = repository.token
    val telemetry: StateFlow<SystemTelemetry> = repository.telemetry
    val serverLogs: StateFlow<List<ServerLogItem>> = repository.serverLogs
    val inspectedElements: StateFlow<List<UiElementInfo>> = repository.inspectedElements

    private val _lastActionResult = MutableStateFlow<String?>(null)
    val lastActionResult = _lastActionResult.asStateFlow()

    private val _isActionExecuting = MutableStateFlow(false)
    val isActionExecuting = _isActionExecuting.asStateFlow()

    fun toggleServer() {
        repository.toggleServer()
    }

    fun regenerateToken(): String {
        return repository.regenerateToken()
    }

    fun clearLogs() {
        repository.clearLogs()
    }

    fun inspectScreen() {
        viewModelScope.launch(Dispatchers.IO) {
            _isActionExecuting.value = true
            val elements = repository.refreshInspectedElements()
            _lastActionResult.value = "Inspected ${elements.size} UI elements on current active window."
            _isActionExecuting.value = false
        }
    }

    fun testTapCoordinates(x: Float, y: Float) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _lastActionResult.value = "Error: Accessibility Service is not connected."
            return
        }
        viewModelScope.launch {
            _isActionExecuting.value = true
            val res = service.tapCoordinates(x, y)
            _lastActionResult.value = res.result ?: res.message
            _isActionExecuting.value = false
        }
    }

    fun testTapElement(identifier: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _lastActionResult.value = "Error: Accessibility Service is not connected."
            return
        }
        viewModelScope.launch {
            _isActionExecuting.value = true
            val res = service.tapElement(identifier)
            _lastActionResult.value = res.result ?: res.message
            _isActionExecuting.value = false
        }
    }

    fun testSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _lastActionResult.value = "Error: Accessibility Service is not connected."
            return
        }
        viewModelScope.launch {
            _isActionExecuting.value = true
            val res = service.swipeCoordinates(x1, y1, x2, y2, duration)
            _lastActionResult.value = res.result ?: res.message
            _isActionExecuting.value = false
        }
    }

    fun testTypeText(elementId: String?, text: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _lastActionResult.value = "Error: Accessibility Service is not connected."
            return
        }
        viewModelScope.launch {
            _isActionExecuting.value = true
            val res = service.typeText(elementId, text)
            _lastActionResult.value = res.result ?: res.message
            _isActionExecuting.value = false
        }
    }

    fun testPressKey(key: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _lastActionResult.value = "Error: Accessibility Service is not connected."
            return
        }
        val res = service.pressKey(key)
        _lastActionResult.value = res.result ?: res.message
    }

    fun testOpenApp(packageName: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            _lastActionResult.value = "Error: Accessibility Service is not connected."
            return
        }
        val res = service.openApp(packageName)
        _lastActionResult.value = res.result ?: res.message
    }

    fun testExecuteShell(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isActionExecuting.value = true
            val res = com.example.service.AdbShizukuManager.executeShell(command)
            _lastActionResult.value = if (res.status == "ok") "Shell output:\n${res.result}" else "Shell error: ${res.message}"
            _isActionExecuting.value = false
        }
    }

    // =========================================================================
    // AI Chat Window State & Actions
    // =========================================================================
    val chatApiKey = MutableStateFlow("")
    val chatBaseUrl = MutableStateFlow(com.example.service.AiChatService.DEFAULT_GEMINI_BASE_URL)
    val chatModelName = MutableStateFlow(com.example.service.AiChatService.DEFAULT_GEMINI_MODEL)

    private val _chatMessages = MutableStateFlow<List<com.example.model.ChatMessage>>(
        listOf(
            com.example.model.ChatMessage(
                sender = com.example.model.ChatSender.SYSTEM,
                text = "Halo! Saya JARVIS-HP AI Assistant. Anda dapat langsung mengobrol dan memberikan instruksi otomasi HP di sini (misal: 'Buka YouTube', 'Ketik halo', 'Cek baterai', dll.)."
            )
        )
    )
    val chatMessages: StateFlow<List<com.example.model.ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatAiThinking = MutableStateFlow(false)
    val isChatAiThinking: StateFlow<Boolean> = _isChatAiThinking.asStateFlow()

    private val _chatStatusText = MutableStateFlow("")
    val chatStatusText: StateFlow<String> = _chatStatusText.asStateFlow()

    fun sendChatMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) return

        val userMessage = com.example.model.ChatMessage(
            sender = com.example.model.ChatSender.USER,
            text = trimmed
        )
        _chatMessages.value = _chatMessages.value + userMessage

        viewModelScope.launch(Dispatchers.IO) {
            _isChatAiThinking.value = true
            _chatStatusText.value = "Memproses instruksi..."

            val history = _chatMessages.value
                .filter { it.sender == com.example.model.ChatSender.USER || it.sender == com.example.model.ChatSender.AI }
                .map { (if (it.sender == com.example.model.ChatSender.USER) "user" else "assistant") to it.text }

            val (aiReply, actionResult) = com.example.service.AiChatService.sendMessage(
                userPrompt = trimmed,
                apiKey = chatApiKey.value,
                baseUrl = chatBaseUrl.value,
                modelName = chatModelName.value,
                history = history,
                onStatusUpdate = { status ->
                    _chatStatusText.value = status
                }
            )

            val aiMessage = com.example.model.ChatMessage(
                sender = com.example.model.ChatSender.AI,
                text = aiReply,
                isExecutingAction = actionResult != null,
                actionToolName = if (actionResult != null) "Otomasi Perangkat" else null,
                actionResult = actionResult?.let { if (it.status == "ok") (it.result ?: "Berhasil dieksekusi") else ("Gagal: " + (it.message ?: "Error")) }
            )

            _chatMessages.value = _chatMessages.value + aiMessage
            _isChatAiThinking.value = false
            _chatStatusText.value = ""
        }
    }

    fun clearChat() {
        _chatMessages.value = listOf(
            com.example.model.ChatMessage(
                sender = com.example.model.ChatSender.SYSTEM,
                text = "Riwayat chat telah dibersihkan."
            )
        )
    }
}

