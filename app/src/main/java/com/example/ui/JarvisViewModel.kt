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
    // AI Chat Window State & Actions (Backed by AiConfigManager)
    // =========================================================================
    val aiConfig = com.example.data.AiConfigManager.config
    val chatApiKey = MutableStateFlow(com.example.data.AiConfigManager.config.value.apiKey)
    val chatBaseUrl = MutableStateFlow(com.example.data.AiConfigManager.config.value.baseUrl)
    val chatModelName = MutableStateFlow(com.example.data.AiConfigManager.config.value.modelName)

    val chatSessions = com.example.data.ChatSessionManager.sessions
    val currentSession = com.example.data.ChatSessionManager.currentSession

    private val _chatMessages = MutableStateFlow<List<com.example.model.ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<com.example.model.ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatAiThinking = MutableStateFlow(false)
    val isChatAiThinking: StateFlow<Boolean> = _isChatAiThinking.asStateFlow()

    private val _chatStatusText = MutableStateFlow("")
    val chatStatusText: StateFlow<String> = _chatStatusText.asStateFlow()

    val liveThought: StateFlow<String?> = com.example.service.AiChatService.liveThoughtState.asStateFlow()

    init {
        viewModelScope.launch {
            com.example.data.AiConfigManager.config.collect { config ->
                chatApiKey.value = config.apiKey
                chatBaseUrl.value = config.baseUrl
                chatModelName.value = config.modelName
            }
        }
        viewModelScope.launch {
            com.example.data.ChatSessionManager.currentSession.collect { session ->
                if (session != null) {
                    _chatMessages.value = session.messages
                }
            }
        }
    }

    fun updateAiConfig(apiKey: String, baseUrl: String, modelName: String, providerLabel: String? = null) {
        com.example.data.AiConfigManager.saveConfig(apiKey, baseUrl, modelName, providerLabel)
        chatApiKey.value = apiKey
        chatBaseUrl.value = baseUrl
        chatModelName.value = modelName
    }

    fun createNewSession(title: String? = null) {
        com.example.data.ChatSessionManager.createSession(title)
    }

    fun switchSession(sessionId: String) {
        com.example.data.ChatSessionManager.switchSession(sessionId)
    }

    fun deleteSession(sessionId: String) {
        com.example.data.ChatSessionManager.deleteSession(sessionId)
    }

    fun renameSession(sessionId: String, newTitle: String) {
        com.example.data.ChatSessionManager.renameSession(sessionId, newTitle)
    }

    fun sendChatMessage(
        userText: String,
        attachmentUri: String? = null,
        attachmentName: String? = null,
        attachmentMimeType: String? = null
    ) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() && attachmentUri == null) return

        val displayPrompt = trimmed.ifEmpty { "Mengirim lampiran: ${attachmentName ?: "Berkas"}" }

        val userMessage = com.example.model.ChatMessage(
            sender = com.example.model.ChatSender.USER,
            text = displayPrompt,
            attachmentUri = attachmentUri,
            attachmentName = attachmentName,
            attachmentMimeType = attachmentMimeType
        )
        val updatedUserList = _chatMessages.value + userMessage
        _chatMessages.value = updatedUserList
        com.example.data.ChatSessionManager.updateMessagesForCurrentSession(updatedUserList)

        viewModelScope.launch(Dispatchers.IO) {
            _isChatAiThinking.value = true
            _chatStatusText.value = "Memproses instruksi..."

            val history = _chatMessages.value
                .filter { it.sender == com.example.model.ChatSender.USER || it.sender == com.example.model.ChatSender.AI }
                .map { (if (it.sender == com.example.model.ChatSender.USER) "user" else "assistant") to it.text }

            val response = com.example.service.AiChatService.sendMessage(
                userPrompt = displayPrompt,
                apiKey = chatApiKey.value,
                baseUrl = chatBaseUrl.value,
                modelName = chatModelName.value,
                history = history,
                attachmentUri = attachmentUri,
                attachmentMimeType = attachmentMimeType,
                onStatusUpdate = { status ->
                    _chatStatusText.value = status
                    val context = com.example.JarvisApp.instance
                    if (com.example.ui.JarvisOverlayManager.canDrawOverlay(context)) {
                        com.example.ui.JarvisOverlayManager.show(context)
                        if (status.contains("eksekusi", ignoreCase = true) || status.contains("tool", ignoreCase = true)) {
                            com.example.ui.JarvisOverlayManager.onExecutingTool(status)
                        } else {
                            com.example.ui.JarvisOverlayManager.onThinking(status)
                        }
                    }
                }
            )

            val aiMessage = com.example.model.ChatMessage(
                sender = com.example.model.ChatSender.AI,
                text = response.replyText,
                isExecutingAction = response.actionResult != null,
                actionToolName = response.actionToolName ?: if (response.actionResult != null) "Otomasi Perangkat" else null,
                actionResult = response.actionResult?.let { if (it.status == "ok") (it.result ?: "Berhasil dieksekusi") else ("Gagal: " + (it.message ?: "Error")) },
                thinkingProcess = response.thinkingProcess
            )

            val finalMessages = _chatMessages.value + aiMessage
            _chatMessages.value = finalMessages
            com.example.data.ChatSessionManager.updateMessagesForCurrentSession(finalMessages)

            _isChatAiThinking.value = false
            _chatStatusText.value = ""

            val appContext = com.example.JarvisApp.instance
            if (com.example.ui.JarvisOverlayManager.canDrawOverlay(appContext)) {
                com.example.ui.JarvisOverlayManager.onResult(
                    command = displayPrompt,
                    tool = response.actionToolName,
                    resultStatus = response.actionResult?.status,
                    replyText = response.replyText
                )
            }

            // Trigger Voice Mode or Auto-Read TTS if active
            com.example.service.JarvisVoiceManager.onAiReplyReceived(response.replyText)
        }
    }

    fun stopAiExecution() {
        com.example.service.AiChatService.stopCurrentExecution()
        com.example.service.JarvisHotwordManager.cancelCurrentAction()
        com.example.ui.JarvisOverlayManager.cancelAction()
        _isChatAiThinking.value = false
        _chatStatusText.value = "Dihentikan oleh pengguna."
        val stoppedMsg = com.example.model.ChatMessage(
            sender = com.example.model.ChatSender.SYSTEM,
            text = "🛑 Proses AI telah dihentikan oleh pengguna."
        )
        val finalMessages = _chatMessages.value + stoppedMsg
        _chatMessages.value = finalMessages
        com.example.data.ChatSessionManager.updateMessagesForCurrentSession(finalMessages)
    }

    fun startVoiceCall() {
        com.example.service.JarvisVoiceManager.startVoiceCall { spokenInput ->
            sendChatMessage(spokenInput)
        }
    }

    fun endVoiceCall() {
        com.example.service.JarvisVoiceManager.endVoiceCall()
    }

    fun speakMessage(text: String) {
        com.example.service.JarvisVoiceManager.speak(text)
    }

    fun stopSpeech() {
        com.example.service.JarvisVoiceManager.stopSpeaking()
    }

    fun toggleAutoRead() {
        com.example.service.JarvisVoiceManager.toggleAutoRead()
    }

    // =========================================================================
    // Background "Jarvis" Hotword & Floating HUD States
    // =========================================================================
    val isHotwordEnabled: StateFlow<Boolean> = com.example.service.JarvisHotwordManager.isHotwordEnabled
    val isHotwordListeningActive: StateFlow<Boolean> = com.example.service.JarvisHotwordManager.isListeningActive
    val isOverlayVisible: StateFlow<Boolean> = com.example.ui.JarvisOverlayManager.isOverlayVisible

    fun toggleHotword(context: android.content.Context): Boolean {
        val result = com.example.service.JarvisHotwordManager.toggleHotword(context)
        com.example.service.JarvisCompanionService.updateNotification(context)
        return result
    }

    fun setHotwordEnabled(context: android.content.Context, enabled: Boolean) {
        com.example.service.JarvisHotwordManager.setHotwordEnabled(context, enabled)
        com.example.service.JarvisCompanionService.updateNotification(context)
    }

    fun testTriggerHotword() {
        com.example.service.JarvisHotwordManager.triggerImmediateListening()
    }

    fun clearChat() {
        com.example.data.ChatSessionManager.clearCurrentSessionMessages()
    }
}

