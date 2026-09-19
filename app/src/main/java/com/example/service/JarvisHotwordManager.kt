package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.JarvisApp
import com.example.data.ChatSessionManager
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.ui.JarvisOverlayManager
import com.example.ui.OverlayUiMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object JarvisHotwordManager {
    private const val TAG = "JarvisHotwordManager"
    private const val PREFS_NAME = "jarvis_hotword_prefs"
    private const val KEY_HOTWORD_ENABLED = "pref_hotword_enabled"
    private const val KEY_OVERLAY_ENABLED = "pref_overlay_enabled"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Observable toggle states
    private val _isHotwordEnabled = MutableStateFlow(false)
    val isHotwordEnabled: StateFlow<Boolean> = _isHotwordEnabled.asStateFlow()

    private val _isListeningActive = MutableStateFlow(false)
    val isListeningActive: StateFlow<Boolean> = _isListeningActive.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    // Android Speech Recognizer & WakeLock
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isInitialized = false

    // Pending state when user only says "jarvis" without immediate command
    private var isAwaitingFollowupCommand = false

    private var currentExecutionJob: kotlinx.coroutines.Job? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val prefs = getPrefs(context)
        val savedEnabled = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        _isHotwordEnabled.value = savedEnabled

        if (savedEnabled) {
            start(context)
        }
    }

    /**
     * Toggles the background "Jarvis" voice assistant on/off
     */
    fun toggleHotword(context: Context): Boolean {
        val newState = !_isHotwordEnabled.value
        setHotwordEnabled(context, newState)
        return newState
    }

    fun setHotwordEnabled(context: Context, enabled: Boolean) {
        _isHotwordEnabled.value = enabled
        getPrefs(context).edit().putBoolean(KEY_HOTWORD_ENABLED, enabled).apply()

        if (enabled) {
            start(context)
        } else {
            stop(context)
        }
    }

    fun start(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start Jarvis hotword: RECORD_AUDIO not granted")
            return
        }

        acquireWakeLock(context)

        // Show floating HUD if overlay permission is granted
        if (JarvisOverlayManager.canDrawOverlay(context)) {
            JarvisOverlayManager.show(context)
        }

        startContinuousListening(context)
    }

    fun stop(context: Context? = null) {
        releaseWakeLock()
        stopContinuousListening()
        JarvisOverlayManager.hide()
        _isListeningActive.value = false
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null || !wakeLock!!.isHeld) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::VoiceHotwordLock").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24 hours max
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock!!.isHeld) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }

    private fun startContinuousListening(context: Context) {
        mainHandler.post {
            if (!_isHotwordEnabled.value) return@post

            // If Jarvis is currently speaking through TTS or executing an AI task, do not listen
            if (JarvisVoiceManager.isSpeaking.value || _isExecuting.value) {
                mainHandler.postDelayed({
                    if (_isHotwordEnabled.value && !JarvisVoiceManager.isSpeaking.value && !_isExecuting.value) {
                        startContinuousListening(context)
                    }
                }, 800)
                return@post
            }

            destroySpeechRecognizer()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "SpeechRecognizer is not available on this device")
                return@post
            }

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListeningActive.value = true
                        }

                        override fun onBeginningOfSpeech() {}

                        override fun onRmsChanged(rmsdB: Float) {
                            val normalized = ((rmsdB + 2) / 12f).coerceIn(0.05f, 1f)
                            JarvisOverlayManager.updateAudioRms(normalized)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            JarvisOverlayManager.updateAudioRms(0f)
                        }

                        override fun onError(error: Int) {
                            _isListeningActive.value = false
                            JarvisOverlayManager.updateAudioRms(0f)

                            // Normal silence timeouts: schedule smooth restart
                            val delayMs = when (error) {
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                                SpeechRecognizer.ERROR_CLIENT -> 800L
                                else -> 400L
                            }

                            mainHandler.postDelayed({
                                if (_isHotwordEnabled.value && !JarvisVoiceManager.isSpeaking.value && !_isExecuting.value) {
                                    startContinuousListening(context)
                                }
                            }, delayMs)
                        }

                        override fun onResults(results: Bundle?) {
                            _isListeningActive.value = false
                            JarvisOverlayManager.updateAudioRms(0f)

                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim().orEmpty()

                            if (text.isNotEmpty()) {
                                handleRecognizedSpeech(context, text)
                            } else {
                                scheduleRestart(context, 350)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val partial = matches?.firstOrNull()?.trim().orEmpty()

                            if (partial.isNotEmpty()) {
                                val lower = partial.lowercase()
                                if (containsHotword(lower) || isAwaitingFollowupCommand) {
                                    JarvisOverlayManager.onSpeechPartial(partial)
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                speechRecognizer?.startListening(intent)
                _isListeningActive.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error in startContinuousListening", e)
                scheduleRestart(context, 1000)
            }
        }
    }

    private fun scheduleRestart(context: Context, delayMs: Long) {
        mainHandler.postDelayed({
            if (_isHotwordEnabled.value && !JarvisVoiceManager.isSpeaking.value && !_isExecuting.value) {
                startContinuousListening(context)
            }
        }, delayMs)
    }

    private fun stopContinuousListening() {
        mainHandler.post {
            destroySpeechRecognizer()
            _isListeningActive.value = false
        }
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun containsHotword(text: String): Boolean {
        return text.contains("jarvis")
    }

    /**
     * Processes recognized speech text, looks for "jarvis [perintah]",
     * and triggers autonomous AI execution hands-free!
     */
    private fun handleRecognizedSpeech(context: Context, rawSpokenText: String) {
        val lower = rawSpokenText.lowercase().trim()

        if (isAwaitingFollowupCommand) {
            // User already triggered "jarvis" earlier, this utterance is their command!
            isAwaitingFollowupCommand = false
            vibrate(context, 60)
            executeAutonomousAiCommand(context, rawSpokenText)
            return
        }

        if (!containsHotword(lower)) {
            // Not addressed to Jarvis, silently keep listening
            scheduleRestart(context, 300)
            return
        }

        // Extract command after hotword (e.g. "jarvis buka youtube" -> "buka youtube")
        val jarvisIdx = lower.indexOf("jarvis")
        val command = if (jarvisIdx >= 0) {
            rawSpokenText.substring(jarvisIdx + "jarvis".length)
                .trim()
                .removePrefix(",")
                .removePrefix(":")
                .removePrefix("-")
                .trim()
        } else {
            ""
        }

        vibrate(context, 100)

        // 1. Ensure Floating HUD overlay is displayed whenever "jarvis" is detected
        if (JarvisOverlayManager.canDrawOverlay(context)) {
            JarvisOverlayManager.show(context)
        }

        if (command.isNotBlank()) {
            // User gave direct full command: "jarvis <perintah>"
            JarvisOverlayManager.onHotwordDetected(command)
            executeAutonomousAiCommand(context, command)
        } else {
            // User called "jarvis" -> HUD opens and JARVIS responds "Yes?" while listening for the command!
            isAwaitingFollowupCommand = true
            JarvisOverlayManager.onHotwordTriggeredYes()
            JarvisVoiceManager.speak("Yes?") {
                scheduleRestart(context, 150)
            }
        }
    }

    /**
     * Autonomous AI & Tool Execution Pipeline
     */
    private fun executeAutonomousAiCommand(context: Context, command: String) {
        _isExecuting.value = true
        JarvisOverlayManager.onThinking(command)

        currentExecutionJob?.cancel()
        currentExecutionJob = scope.launch {
            try {
                // 1. Record user command in active ChatSession
                val userMsg = ChatMessage(
                    sender = ChatSender.USER,
                    text = command
                )
                val currentSession = ChatSessionManager.currentSession.value
                val existingMessages = currentSession?.messages ?: emptyList()
                val updatedWithUser = existingMessages + userMsg
                ChatSessionManager.updateMessagesForCurrentSession(updatedWithUser)

                // 2. Prepare conversation history
                val history = updatedWithUser
                    .filter { it.sender == ChatSender.USER || it.sender == ChatSender.AI }
                    .takeLast(8)
                    .map { (if (it.sender == ChatSender.USER) "user" else "assistant") to it.text }

                // 3. Call AI Chat Service with live HUD status callbacks using configured Model & Endpoint
                val currentAiConfig = com.example.data.AiConfigManager.config.value
                val response = AiChatService.sendMessage(
                    userPrompt = command,
                    apiKey = currentAiConfig.apiKey,
                    baseUrl = currentAiConfig.baseUrl,
                    modelName = currentAiConfig.modelName,
                    history = history,
                    onStatusUpdate = { status ->
                        JarvisOverlayManager.onThinking(command)
                        if (status.contains("tool", ignoreCase = true) || status.contains("eksekusi", ignoreCase = true)) {
                            JarvisOverlayManager.onExecutingTool(status)
                        }
                    }
                )

                // 4. Record AI response in ChatSession
                val aiMsg = ChatMessage(
                    sender = ChatSender.AI,
                    text = response.replyText,
                    isExecutingAction = response.actionResult != null,
                    actionToolName = response.actionToolName ?: if (response.actionResult != null) "Otomasi Perangkat" else null,
                    actionResult = response.actionResult?.let { if (it.status == "ok") (it.result ?: "Berhasil dieksekusi") else ("Gagal: " + (it.message ?: "Error")) },
                    thinkingProcess = response.thinkingProcess
                )
                val updatedWithAi = (ChatSessionManager.currentSession.value?.messages ?: emptyList()) + aiMsg
                ChatSessionManager.updateMessagesForCurrentSession(updatedWithAi)

                // 5. Update Floating HUD with result and tool badge
                JarvisOverlayManager.onResult(
                    command = command,
                    tool = response.actionToolName,
                    resultStatus = response.actionResult?.status,
                    replyText = response.replyText
                )

                // 6. Speak response via TTS hands-free
                JarvisVoiceManager.speak(response.replyText) {
                    _isExecuting.value = false
                    scheduleRestart(context, 500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing autonomous AI command", e)
                _isExecuting.value = false
                JarvisOverlayManager.onResult(
                    command = command,
                    tool = null,
                    resultStatus = "error",
                    replyText = "Maaf, terjadi kendala saat memproses perintah Anda: ${e.localizedMessage}"
                )
                JarvisVoiceManager.speak("Maaf, terjadi kendala saat memproses perintah.") {
                    scheduleRestart(context, 500)
                }
            }
        }
    }

    fun cancelCurrentAction() {
        isAwaitingFollowupCommand = false
        currentExecutionJob?.cancel()
        currentExecutionJob = null
        _isExecuting.value = false
        JarvisVoiceManager.stopSpeaking()
        destroySpeechRecognizer()
        scheduleRestart(JarvisApp.instance, 300)
    }

    fun triggerImmediateListening() {
        isAwaitingFollowupCommand = true
        JarvisOverlayManager.expandToListening()
        destroySpeechRecognizer()
        scheduleRestart(JarvisApp.instance, 100)
    }

    private fun vibrate(context: Context, durationMs: Long) {
        try {
            @Suppress("DEPRECATION")
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vm?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }
}
