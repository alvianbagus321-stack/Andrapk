package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.JarvisApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

object JarvisVoiceManager : TextToSpeech.OnInitListener {
    private const val TAG = "JarvisVoiceManager"

    // Context & Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // TTS
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var currentUtteranceCallback: (() -> Unit)? = null

    // STT
    private var speechRecognizer: SpeechRecognizer? = null

    // States
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _audioRms = MutableStateFlow(0f)
    val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

    private val _voiceStatus = MutableStateFlow("Standby")
    val voiceStatus: StateFlow<String> = _voiceStatus.asStateFlow()

    // Auto-read setting (membacakan setiap respon AI di chat)
    private val _autoReadEnabled = MutableStateFlow(false)
    val autoReadEnabled: StateFlow<Boolean> = _autoReadEnabled.asStateFlow()

    // Voice Call Mode (Full Hands-Free Conversation Dialog)
    private val _isVoiceCallActive = MutableStateFlow(false)
    val isVoiceCallActive: StateFlow<Boolean> = _isVoiceCallActive.asStateFlow()

    private val _voiceCallState = MutableStateFlow(VoiceState.IDLE)
    val voiceCallState: StateFlow<VoiceState> = _voiceCallState.asStateFlow()

    private val _lastUserSpeech = MutableStateFlow("")
    val lastUserSpeech: StateFlow<String> = _lastUserSpeech.asStateFlow()

    private val _lastAiSpeech = MutableStateFlow("")
    val lastAiSpeech: StateFlow<String> = _lastAiSpeech.asStateFlow()

    // Callback when user speaks in voice call
    private var onVoiceCallInput: ((String) -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(JarvisApp.instance, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TTS", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            // Coba bahasa Indonesia
            val idLocale = Locale.Builder().setLanguage("id").setRegion("ID").build()
            val available = tts?.isLanguageAvailable(idLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = idLocale
            } else {
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    if (_isVoiceCallActive.value) {
                        _voiceCallState.value = VoiceState.SPEAKING
                        _voiceStatus.value = "JARVIS Berbicara..."
                    }
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    mainHandler.post {
                        val callback = currentUtteranceCallback
                        currentUtteranceCallback = null
                        callback?.invoke()

                        if (_isVoiceCallActive.value) {
                            _voiceCallState.value = VoiceState.IDLE
                            // Otomatis dengarkan user lagi setelah 400ms jeda
                            mainHandler.postDelayed({
                                if (_isVoiceCallActive.value && !_isSpeaking.value) {
                                    startListeningForCall()
                                }
                            }, 400)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    mainHandler.post {
                        currentUtteranceCallback?.invoke()
                        currentUtteranceCallback = null
                        if (_isVoiceCallActive.value) {
                            _voiceCallState.value = VoiceState.IDLE
                        }
                    }
                }
            })
        } else {
            Log.e(TAG, "TTS Initialization failed with status: $status")
        }
    }

    fun toggleAutoRead() {
        _autoReadEnabled.value = !_autoReadEnabled.value
    }

    fun setAutoRead(enabled: Boolean) {
        _autoReadEnabled.value = enabled
    }

    /**
     * Membersihkan teks markdown, kode, dan tag agar pengucapan TTS enak didengar
     */
    fun cleanTextForSpeech(text: String): String {
        return text
            .replace(Regex("```[\\s\\S]*?```"), "Blok kode terlampir.")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("[*#_~]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("\\n{2,}"), ". ")
            .replace("\n", ", ")
            .trim()
    }

    /**
     * Ucapkan teks via TextToSpeech
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val clean = cleanTextForSpeech(text)
        if (clean.isBlank()) {
            onDone?.invoke()
            return
        }

        currentUtteranceCallback = onDone
        _lastAiSpeech.value = clean

        mainHandler.post {
            if (tts == null || !isTtsInitialized) {
                initTts()
            }
            val utteranceId = UUID.randomUUID().toString()
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            tts?.stop()
            _isSpeaking.value = false
            currentUtteranceCallback = null
            if (_isVoiceCallActive.value && _voiceCallState.value == VoiceState.SPEAKING) {
                _voiceCallState.value = VoiceState.IDLE
            }
        }
    }

    /**
     * Single-shot Speech-to-Text untuk quick voice input pada text field
     */
    fun startListening(
        context: Context,
        onResult: (String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        mainHandler.post {
            stopSpeaking()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError?.invoke("Speech Recognizer tidak tersedia di perangkat ini.")
                return@post
            }

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                _isListening.value = true
                                _voiceStatus.value = "Mendengarkan..."
                            }

                            override fun onBeginningOfSpeech() {
                                _voiceStatus.value = "Mendengar suara..."
                            }

                            override fun onRmsChanged(rmsdB: Float) {
                                val normalized = ((rmsdB + 2) / 12f).coerceIn(0.05f, 1f)
                                _audioRms.value = normalized
                            }

                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {
                                _isListening.value = false
                                _audioRms.value = 0f
                                _voiceStatus.value = "Memproses suara..."
                            }

                            override fun onError(error: Int) {
                                _isListening.value = false
                                _audioRms.value = 0f
                                val errorMsg = when (error) {
                                    SpeechRecognizer.ERROR_AUDIO -> "Error audio"
                                    SpeechRecognizer.ERROR_CLIENT -> "Klien suara error"
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon diperlukan"
                                    SpeechRecognizer.ERROR_NETWORK -> "Koneksi jaringan error"
                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Waktu jaringan habis"
                                    SpeechRecognizer.ERROR_NO_MATCH -> "Suara tidak terdengar jelas"
                                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Layanan suara sibuk"
                                    SpeechRecognizer.ERROR_SERVER -> "Server suara error"
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak ada suara yang terdeteksi"
                                    else -> "Error suara ($error)"
                                }
                                _voiceStatus.value = errorMsg
                                onError?.invoke(errorMsg)
                            }

                            override fun onResults(results: Bundle?) {
                                _isListening.value = false
                                _audioRms.value = 0f
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val spoken = matches?.firstOrNull()?.trim().orEmpty()
                                if (spoken.isNotEmpty()) {
                                    _lastUserSpeech.value = spoken
                                    _voiceStatus.value = "Selesai"
                                    onResult(spoken)
                                } else {
                                    _voiceStatus.value = "Tidak ada teks"
                                }
                            }

                            override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                val partial = matches?.firstOrNull()?.trim().orEmpty()
                                if (partial.isNotEmpty()) {
                                    _lastUserSpeech.value = partial
                                }
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition", e)
                destroySpeechRecognizer()
                _isListening.value = false
                onError?.invoke(e.message ?: "Gagal memulai mikrofon")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition", e)
            }
            _isListening.value = false
            _audioRms.value = 0f
        }
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        }
        speechRecognizer = null
        _isListening.value = false
        _audioRms.value = 0f
    }

    // =========================================================================
    // Hands-Free Live Voice Call Mode
    // =========================================================================

    fun startVoiceCall(onInput: (String) -> Unit) {
        onVoiceCallInput = onInput
        _isVoiceCallActive.value = true
        _voiceCallState.value = VoiceState.IDLE
        _voiceStatus.value = "Menghubungkan Suara JARVIS..."

        speak("Sistem suara JARVIS aktif. Silakan bicara.") {
            // Setelah salam pembuka, mulai dengarkan
            if (_isVoiceCallActive.value) {
                startListeningForCall()
            }
        }
    }

    private fun startListeningForCall() {
        if (!_isVoiceCallActive.value) return

        _voiceCallState.value = VoiceState.LISTENING
        _voiceStatus.value = "Mendengarkan..."

        startListening(
            context = JarvisApp.instance,
            onResult = { spokenText ->
                if (_isVoiceCallActive.value) {
                    _voiceCallState.value = VoiceState.THINKING
                    _voiceStatus.value = "JARVIS sedang memproses..."
                    _lastUserSpeech.value = spokenText
                    onVoiceCallInput?.invoke(spokenText)
                }
            },
            onError = { err ->
                if (_isVoiceCallActive.value) {
                    // Jika timeout/no match, dengarkan lagi jika masih di mode call
                    if (err.contains("Tidak ada suara") || err.contains("tidak terdengar")) {
                        mainHandler.postDelayed({
                            if (_isVoiceCallActive.value && !_isSpeaking.value) {
                                startListeningForCall()
                            }
                        }, 800)
                    } else {
                        _voiceStatus.value = err
                    }
                }
            }
        )
    }

    fun onAiReplyReceived(replyText: String) {
        if (_isVoiceCallActive.value) {
            _voiceCallState.value = VoiceState.SPEAKING
            _voiceStatus.value = "JARVIS Berbicara..."
            speak(replyText)
        } else if (_autoReadEnabled.value) {
            speak(replyText)
        }
    }

    fun endVoiceCall() {
        _isVoiceCallActive.value = false
        _voiceCallState.value = VoiceState.IDLE
        _voiceStatus.value = "Sesi Suara Berakhir"
        stopListening()
        stopSpeaking()
        destroySpeechRecognizer()
        onVoiceCallInput = null
    }
}
