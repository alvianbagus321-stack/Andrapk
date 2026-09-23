package com.example.quiz

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.JarvisApp
import com.example.ui.OverlayLifecycleOwner
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/**
 * Jendela mengambang khusus AI Quiz Analyzer — TERPISAH dari overlay JARVIS utama.
 * Compact, bisa digeser (drag), bisa di-minimize jadi bulatan "Q", bisa di-ON/OFF-kan
 * dari Dashboard (toggle tersimpan).
 * Overlay ini HANYA untuk kontrol/status/hasil/diagnostic — tidak menyentuh app target.
 */
object QuizOverlayManager {
    private const val TAG = "QuizOverlayManager"
    private const val PREFS_NAME = "jarvis_quiz_overlay_prefs"
    private const val KEY_ENABLED = "quiz_overlay_enabled"
    private const val KEY_X = "quiz_overlay_x"
    private const val KEY_Y = "quiz_overlay_y"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    private val _isMinimized = MutableStateFlow(false)
    val isMinimized: kotlinx.coroutines.flow.StateFlow<Boolean> = _isMinimized.asStateFlow()

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> = _isEnabled.asStateFlow()

    fun canDrawOverlay(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true

    private fun ensurePrefs() {
        if (prefs == null) {
            prefs = JarvisApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _isEnabled.value = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        }
    }

    /** Toggle dari Dashboard. Return false bila izin overlay belum ada. */
    fun toggle(context: Context): Boolean {
        ensurePrefs()
        return if (_isEnabled.value) {
            setEnabled(false, save = true)
            true
        } else {
            if (!canDrawOverlay(context)) return false
            setEnabled(true, save = true)
            true
        }
    }

    fun setEnabled(enabled: Boolean, save: Boolean = true) {
        ensurePrefs()
        _isEnabled.value = enabled
        if (save) prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        mainHandler.post {
            if (enabled && overlayView == null) createWindow()
            if (!enabled && overlayView != null) removeWindow()
        }
    }

    /** Restore otomatis saat app jalan bila terakhir aktif (dipanggil dari MainActivity bila perlu). */
    fun restoreIfEnabled() {
        ensurePrefs()
        if (_isEnabled.value && canDrawOverlay(JarvisApp.instance)) {
            setEnabled(true, save = false)
        }
    }

    fun minimize() {
        _isMinimized.value = true
    }

    fun expand() {
        _isMinimized.value = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createWindow() {
        val context = JarvisApp.instance
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs?.getInt(KEY_X, 60) ?: 60
                y = prefs?.getInt(KEY_Y, 220) ?: 220
            }
            overlayParams = p

            val owner = OverlayLifecycleOwner()
            lifecycleOwner = owner

            val content = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    MyApplicationTheme {
                        QuizOverlayUI()
                    }
                }
            }

            val root = DraggableLayout(context).apply {
                configure(
                    getPos = { Pair(overlayParams?.x ?: 0, overlayParams?.y ?: 0) },
                    setPos = { x, y -> updatePosition(x, y) },
                    onDragEnd = { savePosition() }
                )
                addView(content)
            }

            overlayView = root
            wm.addView(root, p)
            Log.i(TAG, "Quiz overlay tampil")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menampilkan quiz overlay", e)
            overlayView = null
        }
    }

    private fun removeWindow() {
        try {
            overlayView?.let {
                val wm = JarvisApp.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeWindow: ${e.message}")
        } finally {
            overlayView = null
            overlayParams = null
            lifecycleOwner?.destroy()
            lifecycleOwner = null
        }
    }

    private fun updatePosition(x: Int, y: Int) {
        val params = overlayParams ?: return
        val view = overlayView ?: return
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        params.x = x.coerceIn(0, (metrics.widthPixels * 0.8f).toInt())
        params.y = y.coerceIn(0, (metrics.heightPixels * 0.9f).toInt())
        try {
            val wm = JarvisApp.instance.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun savePosition() {
        val params = overlayParams ?: return
        prefs?.edit()?.putInt(KEY_X, params.x)?.putInt(KEY_Y, params.y)?.apply()
    }

    /** Drag via onInterceptTouchEvent agar tetap jalan walau sentuhan di atas elemen Compose. */
    private class DraggableLayout(context: Context) : FrameLayout(context) {
        private var getPos: (() -> Pair<Int, Int>)? = null
        private var setPosFn: ((Int, Int) -> Unit)? = null
        private var onDragEndFn: (() -> Unit)? = null
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        fun configure(getPos: () -> Pair<Int, Int>, setPos: (Int, Int) -> Unit, onDragEnd: () -> Unit) {
            this.getPos = getPos
            this.setPosFn = setPos
            this.onDragEndFn = onDragEnd
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    val p = getPos?.invoke()
                    startX = p?.first ?: 0; startY = p?.second ?: 0
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(ev.rawX - downRawX, ev.rawY - downRawY) > touchSlop) dragging = true
                }
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    setPosFn?.invoke(startX + (ev.rawX - downRawX).toInt(), startY + (ev.rawY - downRawY).toInt())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    onDragEndFn?.invoke()
                }
            }
            return true
        }
    }
}
