package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.JarvisApp
import com.example.MainActivity
import com.example.service.JarvisVoiceManager
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class OverlayUiMode {
    MINI_PILL,
    LISTENING,
    THINKING,
    EXECUTING,
    RESULT
}

object JarvisOverlayManager {
    private const val TAG = "JarvisOverlayManager"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Touch drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    // Observable states for UI
    private val _uiMode = MutableStateFlow(OverlayUiMode.MINI_PILL)
    val uiMode: StateFlow<OverlayUiMode> = _uiMode.asStateFlow()

    private val _userCommand = MutableStateFlow("")
    val userCommand: StateFlow<String> = _userCommand.asStateFlow()

    private val _statusText = MutableStateFlow("Standby")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _toolName = MutableStateFlow<String?>(null)
    val toolName: StateFlow<String?> = _toolName.asStateFlow()

    private val _toolResult = MutableStateFlow<String?>(null)
    val toolResult: StateFlow<String?> = _toolResult.asStateFlow()

    private val _aiReply = MutableStateFlow("")
    val aiReply: StateFlow<String> = _aiReply.asStateFlow()

    private val _audioRms = MutableStateFlow(0f)
    val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private var autoDismissRunnable: Runnable? = null

    fun canDrawOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context): Boolean {
        if (!canDrawOverlay(context)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
            return false
        }

        mainHandler.post {
            if (overlayView != null) {
                _isOverlayVisible.value = true
                return@post
            }

            try {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 40
                    y = 160
                }

                val owner = OverlayLifecycleOwner()
                lifecycleOwner = owner

                val composeView = ComposeView(context).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeViewModelStoreOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)

                    setContent {
                        MyApplicationTheme {
                            JarvisFloatingOverlayUI(
                                onDismiss = { collapseToPill() },
                                onCloseOverlay = { hide() },
                                onCancel = { cancelAction() },
                                onOpenApp = { openMainActivity(context) },
                                onMicTap = { triggerVoiceListening() }
                            )
                        }
                    }

                    setOnTouchListener { _, event ->
                        handleTouchEvent(event)
                    }
                }

                overlayView = composeView
                windowManager?.addView(composeView, layoutParams)
                _isOverlayVisible.value = true
                Log.i(TAG, "Jarvis Overlay Window successfully displayed")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding overlay view to WindowManager", e)
            }
        }
        return true
    }

    private fun handleTouchEvent(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        val wm = windowManager ?: return false
        val view = overlayView ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > 10 || abs(dy) > 10) {
                    isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    try {
                        wm.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    return true
                }
            }
        }
        return false
    }

    fun hide() {
        mainHandler.post {
            cancelAutoDismiss()
            try {
                if (overlayView != null && windowManager != null) {
                    windowManager?.removeView(overlayView)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            } finally {
                overlayView = null
                lifecycleOwner?.destroy()
                lifecycleOwner = null
                _isOverlayVisible.value = false
            }
        }
    }

    fun updateAudioRms(rms: Float) {
        _audioRms.value = rms
    }

    fun onHotwordDetected(command: String = "") {
        mainHandler.post {
            cancelAutoDismiss()
            _userCommand.value = command
            _toolName.value = null
            _toolResult.value = null
            _aiReply.value = ""
            if (command.isBlank()) {
                _uiMode.value = OverlayUiMode.LISTENING
                _statusText.value = "Mendengarkan..."
            } else {
                _uiMode.value = OverlayUiMode.THINKING
                _statusText.value = "Menganalisis perintah..."
            }
        }
    }

    /**
     * Triggered when user says "Jarvis" -> opens HUD, responds "Yes?", and starts active listening
     */
    fun onHotwordTriggeredYes() {
        mainHandler.post {
            cancelAutoDismiss()
            _userCommand.value = ""
            _toolName.value = null
            _toolResult.value = null
            _aiReply.value = "Yes?"
            _statusText.value = "JARVIS: Yes? (Mendengarkan...)"
            _uiMode.value = OverlayUiMode.LISTENING
        }
    }

    fun cancelAction() {
        mainHandler.post {
            cancelAutoDismiss()
            com.example.service.JarvisHotwordManager.cancelCurrentAction()
            collapseToPill()
        }
    }

    fun onSpeechPartial(text: String) {
        mainHandler.post {
            _userCommand.value = text
            if (_uiMode.value == OverlayUiMode.MINI_PILL) {
                _uiMode.value = OverlayUiMode.LISTENING
            }
        }
    }

    fun onThinking(command: String) {
        mainHandler.post {
            cancelAutoDismiss()
            _userCommand.value = command
            _uiMode.value = OverlayUiMode.THINKING
            _statusText.value = "Memproses instruksi..."
        }
    }

    fun onExecutingTool(tool: String) {
        mainHandler.post {
            _toolName.value = tool
            _uiMode.value = OverlayUiMode.EXECUTING
            _statusText.value = "Menjalankan otomasi: $tool"
        }
    }

    fun onResult(
        command: String,
        tool: String?,
        resultStatus: String?,
        replyText: String
    ) {
        mainHandler.post {
            _userCommand.value = command
            _toolName.value = tool
            _toolResult.value = resultStatus
            _aiReply.value = replyText
            _uiMode.value = OverlayUiMode.RESULT
            _statusText.value = if (tool != null) "Selesai Dieksekusi" else "JARVIS Menjawab"
            scheduleAutoDismiss(9000)
        }
    }

    fun collapseToPill() {
        mainHandler.post {
            cancelAutoDismiss()
            _uiMode.value = OverlayUiMode.MINI_PILL
            _statusText.value = "JARVIS • Standby"
        }
    }

    fun expandToListening() {
        mainHandler.post {
            cancelAutoDismiss()
            _uiMode.value = OverlayUiMode.LISTENING
            _statusText.value = "Katakan 'Jarvis...' atau perintah Anda"
        }
    }

    private fun triggerVoiceListening() {
        if (_uiMode.value == OverlayUiMode.MINI_PILL) {
            expandToListening()
            com.example.service.JarvisHotwordManager.triggerImmediateListening()
        } else {
            collapseToPill()
        }
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        cancelAutoDismiss()
        autoDismissRunnable = Runnable {
            collapseToPill()
        }.also {
            mainHandler.postDelayed(it, delayMs)
        }
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
    }

    private fun openMainActivity(context: Context) {
        collapseToPill()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }
}
