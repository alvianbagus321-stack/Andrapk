package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisRed
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.JarvisApp
import com.example.MainActivity
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.hypot

enum class OverlayUiMode {
    MINI_PILL,
    LISTENING,
    THINKING,
    EXECUTING,
    RESULT
}

/**
 * Manager jendela mengambang (floating window) JARVIS.
 *
 * DUA jendela TERPISAH agar jelas bedanya:
 *  1. VOICE  (suara)  : MINI_PILL + LISTENING — kecil, bisa digeser (drag), posisi diingat.
 *  2. TASK   (chat)   : THINKING + EXECUTING + RESULT — MEMENUHI LAYAR (fullscreen overlay),
 *                       kartu besar di atas layar (dulu terlalu ke bawah).
 *
 * Masing-masing bisa di-ON/OFF-kan terpisah (toggle tersimpan di prefs).
 * Drag sekarang pakai onInterceptTouchEvent di layout pembungkus sehingga TETAP JALAN
 * meski jari di atas area Compose yang menelan sentuhan (dulu sering tak bisa digeser).
 */
object JarvisOverlayManager {
    private const val TAG = "JarvisOverlayManager"
    private const val PREFS_NAME = "jarvis_overlay_prefs"
    private const val KEY_VOICE_ENABLED = "overlay_voice_enabled"
    private const val KEY_TASK_ENABLED = "overlay_task_enabled"
    private const val KEY_VOICE_X = "overlay_voice_x"
    private const val KEY_VOICE_Y = "overlay_voice_y"

    private val VOICE_MODES = setOf(OverlayUiMode.MINI_PILL, OverlayUiMode.LISTENING)
    private val TASK_MODES = setOf(OverlayUiMode.THINKING, OverlayUiMode.EXECUTING, OverlayUiMode.RESULT)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null

    // ---------------- VOICE window ----------------
    private var voiceView: View? = null
    private var voiceParams: WindowManager.LayoutParams? = null
    private var voiceLifecycle: OverlayLifecycleOwner? = null

    // ---------------- TASK window ----------------
    private var taskView: View? = null
    private var taskParams: WindowManager.LayoutParams? = null
    private var taskLifecycle: OverlayLifecycleOwner? = null

    /** true selama user membiarkan overlay tampil (show()/hide()). */
    private var overlayShown = false

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

    // Toggle masing-masing jendela (persist di prefs; default ON)
    private val _voiceOverlayEnabled = MutableStateFlow(true)
    val voiceOverlayEnabled: StateFlow<Boolean> = _voiceOverlayEnabled.asStateFlow()

    private val _taskOverlayEnabled = MutableStateFlow(true)
    val taskOverlayEnabled: StateFlow<Boolean> = _taskOverlayEnabled.asStateFlow()

    private var autoDismissRunnable: Runnable? = null

    fun canDrawOverlay(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun ensurePrefs() {
        if (prefs == null) {
            prefs = JarvisApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _voiceOverlayEnabled.value = prefs?.getBoolean(KEY_VOICE_ENABLED, true) ?: true
            _taskOverlayEnabled.value = prefs?.getBoolean(KEY_TASK_ENABLED, true) ?: true
        }
    }

    /** ON/OFF kan jendela SUARA (pill + listening). */
    fun setVoiceOverlayEnabled(enabled: Boolean) {
        ensurePrefs()
        _voiceOverlayEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_VOICE_ENABLED, enabled)?.apply()
        mainHandler.post { syncWindows() }
    }

    /** ON/OFF kan jendela CHAT/TASK (thinking/executing/result). */
    fun setTaskOverlayEnabled(enabled: Boolean) {
        ensurePrefs()
        _taskOverlayEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_TASK_ENABLED, enabled)?.apply()
        mainHandler.post { syncWindows() }
    }

    /** Tampilkan overlay (dipanggil hotword service / dashboard). */
    fun show(context: Context): Boolean {
        if (!canDrawOverlay(context)) {
            Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
            return false
        }
        ensurePrefs()
        overlayShown = true
        mainHandler.post { syncWindows() }
        return true
    }

    /** Tutup semua jendela overlay. */
    fun hide() {
        overlayShown = false
        mainHandler.post { syncWindows() }
    }

    // =====================================================================
    // Window lifecycle
    // =====================================================================

    private fun syncWindows() {
        val mode = _uiMode.value
        val wantVoice = overlayShown && _voiceOverlayEnabled.value && mode in VOICE_MODES
        val wantTask = overlayShown && _taskOverlayEnabled.value && mode in TASK_MODES

        if (wantVoice && voiceView == null) createVoiceWindow()
        if (!wantVoice && voiceView != null) removeVoiceWindow()
        if (wantTask && taskView == null) createTaskWindow()
        if (!wantTask && taskView != null) removeTaskWindow()

        _isOverlayVisible.value = voiceView != null || taskView != null
    }

    private fun windowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    @SuppressLint("ClickableViewAccessibility")
    private fun createVoiceWindow() {
        val context = JarvisApp.instance
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Default di ATAS layar (dulu y=160 terasa terlalu ke bawah)
                x = prefs?.getInt(KEY_VOICE_X, 40) ?: 40
                y = prefs?.getInt(KEY_VOICE_Y, 100) ?: 100
            }
            voiceParams = p

            val owner = OverlayLifecycleOwner()
            voiceLifecycle = owner

            val content = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    MyApplicationTheme {
                        JarvisFloatingOverlayUI(
                            allowedModes = VOICE_MODES,
                            onDismiss = { collapseToPill() },
                            onCloseOverlay = { hide() },
                            onCancel = { cancelAction() },
                            onOpenApp = { openMainActivity(context) },
                            onMicTap = { triggerVoiceListening() }
                        )
                    }
                }
            }

            val root = DraggableOverlayLayout(context).apply {
                configure(
                    getPos = { Pair(voiceParams?.x ?: 0, voiceParams?.y ?: 0) },
                    setPos = { x, y -> updateVoicePosition(x, y) },
                    onDragEnd = { saveVoicePosition() }
                )
                addView(content)
            }
            // PENTING: Compose mencari ViewTreeLifecycleOwner di ROOT view jendela
            // (bukan di ComposeView anaknya). Tanpa ini -> IllegalStateException saat frame
            // dirender ("ViewTreeLifecycleOwner not found from DraggableOverlayLayout").
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeViewModelStoreOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)

            voiceView = root
            wm.addView(root, p)
            Log.i(TAG, "Jendela SUARA (pill/listening) tampil")
        } catch (t: Throwable) {
            com.example.data.CrashReporter.log("Overlay voiceWindow", t)
            Log.e(TAG, "Gagal menampilkan jendela suara", t)
            try { removeVoiceWindow() } catch (_: Throwable) {}
            overlayShown = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTaskWindow() {
        val context = JarvisApp.instance
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            taskParams = p

            val owner = OverlayLifecycleOwner()
            taskLifecycle = owner

            val content = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    MyApplicationTheme {
                        JarvisFloatingOverlayUI(
                            allowedModes = TASK_MODES,
                            onDismiss = { collapseToPill() },
                            onCloseOverlay = { hide() },
                            onCancel = { cancelAction() },
                            onOpenApp = { openMainActivity(context) },
                            onMicTap = { triggerVoiceListening() }
                        )
                    }
                }
            }

            taskView = content
            wm.addView(content, p)
            Log.i(TAG, "Jendela CHAT/TASK (fullscreen) tampil")
        } catch (t: Throwable) {
            com.example.data.CrashReporter.log("Overlay taskWindow", t)
            Log.e(TAG, "Gagal menampilkan jendela chat/task", t)
            try { removeTaskWindow() } catch (_: Throwable) {}
        }
    }

    private fun removeVoiceWindow() {
        try {
            if (voiceView != null) {
                val wm = JarvisApp.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(voiceView)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeVoiceWindow: ${e.message}")
        } finally {
            voiceView = null
            voiceParams = null
            voiceLifecycle?.destroy()
            voiceLifecycle = null
        }
    }

    private fun removeTaskWindow() {
        try {
            if (taskView != null) {
                val wm = JarvisApp.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(taskView)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeTaskWindow: ${e.message}")
        } finally {
            taskView = null
            taskParams = null
            taskLifecycle?.destroy()
            taskLifecycle = null
        }
    }

    private fun updateVoicePosition(x: Int, y: Int) {
        val params = voiceParams ?: return
        val view = voiceView ?: return
        val metrics = Resources.getSystem().displayMetrics
        val maxX = (metrics.widthPixels * 0.9f).toInt()
        val maxY = (metrics.heightPixels * 0.85f).toInt()
        params.x = x.coerceIn(0, maxX)
        params.y = y.coerceIn(0, maxY)
        try {
            val wm = JarvisApp.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun saveVoicePosition() {
        val params = voiceParams ?: return
        prefs?.edit()
            ?.putInt(KEY_VOICE_X, params.x)
            ?.putInt(KEY_VOICE_Y, params.y)
            ?.apply()
    }

    /**
     * Layout pembungkus yang MENYALURKAN drag ke window.
     * Kunci: onInterceptTouchEvent — event turun dari root dulu, jadi drag tetap
     * terdeteksi walau jari berada di atas elemen Compose yang clickable
     * (dulu setOnTouchListener di ComposeView sering kalah oleh anak Compose).
     */
    private class DraggableOverlayLayout(context: Context) : FrameLayout(context) {
        private var getPos: (() -> Pair<Int, Int>)? = null
        private var setPosFn: ((Int, Int) -> Unit)? = null
        private var onDragEnd: (() -> Unit)? = null

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        fun configure(
            getPos: () -> Pair<Int, Int>,
            setPos: (Int, Int) -> Unit,
            onDragEnd: () -> Unit
        ) {
            this.getPos = getPos
            this.setPosFn = setPos
            this.onDragEnd = onDragEnd
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    val p = getPos?.invoke()
                    startX = p?.first ?: 0
                    startY = p?.second ?: 0
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) {
                        dragging = true
                    }
                }
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        setPosFn?.invoke(
                            startX + (ev.rawX - downRawX).toInt(),
                            startY + (ev.rawY - downRawY).toInt()
                        )
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        onDragEnd?.invoke()
                    }
                }
            }
            return true
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
            syncWindows()
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
            _statusText.value = "Asisten: Ada yang bisa dibantu? (Mendengarkan...)"
            _uiMode.value = OverlayUiMode.LISTENING
            syncWindows()
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
                syncWindows()
            }
        }
    }

    fun onThinking(command: String) {
        mainHandler.post {
            cancelAutoDismiss()
            _userCommand.value = command
            _uiMode.value = OverlayUiMode.THINKING
            _statusText.value = "Memproses instruksi..."
            syncWindows()
        }
    }

    fun onExecutingTool(tool: String) {
        mainHandler.post {
            _toolName.value = tool
            _uiMode.value = OverlayUiMode.EXECUTING
            _statusText.value = "Menjalankan otomasi: $tool"
            syncWindows()
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
            _statusText.value = if (tool != null) "Selesai Dieksekusi" else "Asisten Menjawab"
            scheduleAutoDismiss(9000)
            syncWindows()
        }
    }

    fun collapseToPill() {
        mainHandler.post {
            cancelAutoDismiss()
            _uiMode.value = OverlayUiMode.MINI_PILL
            _statusText.value = "Asisten • Standby"
            syncWindows()
        }
    }

    fun expandToListening() {
        mainHandler.post {
            cancelAutoDismiss()
            _uiMode.value = OverlayUiMode.LISTENING
            _statusText.value = "Katakan 'Jarvis...' atau perintah Anda"
            syncWindows()
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

    /**
     * Renders a temporary glowing visual touch pointer / target circle at (x, y)
     * whenever AI executes a gesture (tap/swipe) so the user sees where the AI clicks or moves.
     */
    fun showTapPointer(context: Context, x: Float, y: Float) {
        if (!canDrawOverlay(context)) return
        mainHandler.post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val pointerView = ComposeView(context).apply {
                    val owner = taskLifecycle ?: voiceLifecycle ?: OverlayLifecycleOwner()
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                    setContent {
                        MyApplicationTheme {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopStart
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .graphicsLayer {
                                            translationX = x - 24
                                            translationY = y - 24
                                        }
                                        .size(48.dp),
                                    shape = CircleShape,
                                    color = JarvisCyan.copy(alpha = 0.4f),
                                    border = BorderStroke(2.dp, JarvisRed)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Surface(
                                            modifier = Modifier.size(12.dp),
                                            shape = CircleShape,
                                            color = JarvisRed
                                        ) {}
                                    }
                                }
                            }
                        }
                    }
                }

                val pointerParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )

                wm.addView(pointerView, pointerParams)

                mainHandler.postDelayed({
                    try {
                        wm.removeView(pointerView)
                    } catch (_: Exception) {}
                }, 1200L)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show tap pointer overlay", e)
            }
        }
    }
}
