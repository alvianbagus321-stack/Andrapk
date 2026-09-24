package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.model.ErrorCodes
import com.example.model.RectBounds
import com.example.model.ToolResult
import com.example.model.UiElementInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessService"

        private val _isConnected = MutableStateFlow(false)
        val isConnected = _isConnected.asStateFlow()

        private val _currentApp = MutableStateFlow("Unknown")
        val currentApp = _currentApp.asStateFlow()

        @Volatile
        var instance: JarvisAccessibilityService? = null

        /** Snapshot layar terakhir untuk diff_screen (verifikasi before/after aksi). */
        @Volatile
        private var lastDiffSnapshot: ScreenSnapshot? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "JarvisAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                _currentApp.value = pkg
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isConnected.value = false
        Log.i(TAG, "JarvisAccessibilityService destroyed")
    }

    /**
     * Swipe DUA JARI serentak: di app remote desktop (StarDesk/AnyDesk/dll) gerakan dua
     * jari diteruskan sebagai RODA MOUSE di PC — halaman PC ikut ter-gulung. Berbeda
     * dengan swipe 1 jari yang menjadi mouse-drag (menyeleksi teks, TIDAK menggulung).
     * Di app Android biasa tetap berfungsi sebagai scroll biasa.
     */
    suspend fun twoFingerSwipeCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): ToolResult =
        suspendCoroutine { cont ->
            val safeDuration = durationMs.coerceIn(50L, 2500L)
            val off = 48f
            val p1 = Path().apply { moveTo(x1 - off, y1); lineTo(x2 - off, y2) }
            val p2 = Path().apply { moveTo(x1 + off, y1); lineTo(x2 + off, y2) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0, safeDuration))
                .addStroke(GestureDescription.StrokeDescription(p2, 0, safeDuration))
                .build()
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    cont.resume(
                        ToolResult(
                            status = "ok",
                            result = "Two-finger swipe (${x1.toInt()}, ${y1.toInt()})->(${x2.toInt()}, ${y2.toInt()}) in ${safeDuration}ms"
                        )
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cont.resume(
                        ToolResult(
                            status = "error",
                            errorCode = ErrorCodes.GESTURE_FAILED,
                            message = "Two-finger swipe dibatalkan sistem",
                            retryable = true
                        )
                    )
                }
            }, null)
            if (!dispatched) {
                cont.resume(
                    ToolResult(
                        status = "error",
                        errorCode = ErrorCodes.GESTURE_FAILED,
                        message = "Gagal dispatch two-finger gesture",
                        retryable = true
                    )
                )
            }
        }

    /** Package name app yang sedang tampil di depan (untuk deteksi konteks, mis. remote desktop). */
    fun foregroundPackageName(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Exception) {
        null
    }

    /**
     * Inspects active window hierarchy and returns structured UI elements.
     * Uses windows fallback and rootInActiveWindow with interactive window support.
     */
    fun readScreenElements(): List<UiElementInfo> {
        val elements = mutableListOf<UiElementInfo>()
        val counter = AtomicInteger(1)

        // Primary: rootInActiveWindow
        val root = rootInActiveWindow
        if (root != null) {
            traverseNode(root, elements, counter, 0, 30)
            if (elements.isNotEmpty()) {
                return elements
            }
        }

        // Fallback 1: check all active interactive windows (flagRetrieveInteractiveWindows)
        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                for (w in windowList) {
                    val wRoot = w.root ?: continue
                    traverseNode(wRoot, elements, counter, 0, 30)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying windows list: ${e.message}")
        }

        return elements
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        outList: MutableList<UiElementInfo>,
        counter: AtomicInteger,
        depth: Int,
        maxDepth: Int
    ) {
        if (node == null || depth > maxDepth) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Include visible elements or valid interactive components
        val hasBounds = bounds.width() > 0 && bounds.height() > 0
        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.toString() ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable

        val hasMeaningfulContent = text.isNotEmpty() || contentDesc.isNotEmpty() || viewId.isNotEmpty()
        val isInteractive = isClickable || isEditable || isScrollable || node.isCheckable

        if (hasBounds && (hasMeaningfulContent || isInteractive)) {
            val elementId = if (viewId.isNotBlank()) {
                viewId
            } else {
                "elem_${counter.getAndIncrement()}"
            }

            val rectBounds = RectBounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                width = bounds.width(),
                height = bounds.height(),
                centerX = bounds.centerX(),
                centerY = bounds.centerY()
            )

            outList.add(
                UiElementInfo(
                    id = elementId,
                    text = text,
                    contentDescription = contentDesc,
                    viewId = viewId,
                    className = node.className?.toString() ?: "",
                    bounds = rectBounds,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isScrollable = isScrollable,
                    packageName = node.packageName?.toString() ?: ""
                )
            )
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            traverseNode(child, outList, counter, depth + 1, maxDepth)
        }
    }

    /**
     * Taps the screen at given coordinates using real touch injection.
     */
    suspend fun tapCoordinates(x: Float, y: Float): ToolResult = suspendCoroutine { cont ->
        com.example.ui.JarvisOverlayManager.showTapPointer(this, x, y)
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 45)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                cont.resume(ToolResult(status = "ok", result = "Tapped at (${x.toInt()}, ${y.toInt()})"))
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                cont.resume(
                    ToolResult(
                        status = "error",
                        errorCode = ErrorCodes.GESTURE_FAILED,
                        message = "Tap gesture was cancelled by system at (${x.toInt()}, ${y.toInt()})",
                        retryable = true
                    )
                )
            }
        }, null)

        if (!dispatched) {
            cont.resume(
                ToolResult(
                    status = "error",
                    errorCode = ErrorCodes.GESTURE_FAILED,
                    message = "System rejected gesture dispatch for coordinates (${x.toInt()}, ${y.toInt()})",
                    retryable = true
                )
            )
        }
    }

    /**
     * Swipes between coordinates with duration.
     */
    suspend fun swipeCoordinates(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): ToolResult =
        suspendCoroutine { cont ->
            com.example.ui.JarvisOverlayManager.showTapPointer(this, x1, y1)
            com.example.ui.JarvisOverlayManager.showTapPointer(this, x2, y2)
            val safeDuration = durationMs.coerceIn(50L, 2500L)
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    cont.resume(
                        ToolResult(
                            status = "ok",
                            result = "Swiped from (${x1.toInt()}, ${y1.toInt()}) to (${x2.toInt()}, ${y2.toInt()}) in ${safeDuration}ms"
                        )
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    cont.resume(
                        ToolResult(
                            status = "error",
                            errorCode = ErrorCodes.GESTURE_FAILED,
                            message = "Swipe gesture was cancelled by system",
                            retryable = true
                        )
                    )
                }
            }, null)

            if (!dispatched) {
                cont.resume(
                    ToolResult(
                        status = "error",
                        errorCode = ErrorCodes.GESTURE_FAILED,
                        message = "System rejected swipe gesture dispatch",
                        retryable = true
                    )
                )
            }
        }

    /**
     * Taps an element by ID, viewId, or label with multi-window lookup.
     */
    /**
     * Swipe mengikuti kurva Bezier kubik — gerakan menyerupai jari manusia
     * (bukan garis lurus robotik). Param bend: -1.0..1.0 = kelengkungan relatif
     * terhadap panjang swipe (positif melengkung ke kiri arah gerak, negatif ke kanan).
     */
    suspend fun bezierSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long, bend: Float = 0.35f
    ): ToolResult = suspendCoroutine { cont ->
        com.example.ui.JarvisOverlayManager.showTapPointer(this, x1, y1)
        com.example.ui.JarvisOverlayManager.showTapPointer(this, x2, y2)
        val safeDuration = durationMs.coerceIn(80L, 3000L)
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        // Vektor tegak lurus arah swipe
        val px = if (dist > 0f) -dy / dist else 0f
        val py = if (dist > 0f) dx / dist else 0f
        val bow = bend.coerceIn(-1f, 1f) * dist * 0.35f
        val c1x = x1 + dx * 0.30f + px * bow
        val c1y = y1 + dy * 0.30f + py * bow
        val c2x = x1 + dx * 0.70f + px * bow
        val c2y = y1 + dy * 0.70f + py * bow

        val path = Path().apply {
            moveTo(x1, y1)
            cubicTo(c1x, c1y, c2x, c2y, x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                cont.resume(
                    ToolResult(
                        status = "ok",
                        result = "Bezier swipe OK: (${x1.toInt()}, ${y1.toInt()}) → (${x2.toInt()}, ${y2.toInt()}) bend=$bend in ${safeDuration}ms (kurva manusiawi)"
                    )
                )
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                cont.resume(
                    ToolResult(
                        status = "error",
                        errorCode = ErrorCodes.GESTURE_FAILED,
                        message = "Bezier swipe dibatalkan sistem",
                        retryable = true
                    )
                )
            }
        }, null)

        if (!dispatched) {
            cont.resume(
                ToolResult(
                    status = "error",
                    errorCode = ErrorCodes.GESTURE_FAILED,
                    message = "Sistem menolak gesture bezier swipe",
                    retryable = true
                )
            )
        }
    }

    suspend fun tapElement(identifier: String): ToolResult {
        var node: AccessibilityNodeInfo? = null

        // 1. Search in rootInActiveWindow
        val root = rootInActiveWindow
        if (root != null) {
            node = findNodeByIdentifier(root, identifier)
        }

        // 2. Fallback: search across all active interactive windows
        if (node == null) {
            try {
                for (w in (windows ?: emptyList())) {
                    val wRoot = w.root ?: continue
                    val found = findNodeByIdentifier(wRoot, identifier)
                    if (found != null) {
                        node = found
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error looking up element in windows: ${e.message}")
            }
        }

        if (node == null) {
            return ToolResult(
                status = "error",
                errorCode = ErrorCodes.ELEMENT_NOT_FOUND,
                message = "Element '$identifier' was not found on active screen",
                retryable = true
            )
        }

        // Try direct accessibility click action first
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return ToolResult(status = "ok", result = "Clicked element '$identifier' via accessibility action")
        }

        // Fallback: tap at the center coordinates of the node bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            return tapCoordinates(cx, cy)
        }

        return ToolResult(
            status = "error",
            errorCode = ErrorCodes.ELEMENT_NOT_FOUND,
            message = "Element '$identifier' has zero bounds or is obscured",
            retryable = true
        )
    }

    /**
     * Sets text on an element using ACTION_SET_TEXT.
     * Ensures target node gains focus first, supports focused input resolution,
     * and correctly inputs text into the target element.
     */
    fun typeText(targetId: String?, text: String): ToolResult {
        var targetNode: AccessibilityNodeInfo? = null
        val root = rootInActiveWindow

        // 1. If targetId is provided, look for it in root and other windows
        if (!targetId.isNullOrBlank()) {
            if (root != null) {
                targetNode = findNodeByIdentifier(root, targetId)
            }
            if (targetNode == null) {
                for (w in (windows ?: emptyList())) {
                    val wRoot = w.root ?: continue
                    targetNode = findNodeByIdentifier(wRoot, targetId)
                    if (targetNode != null) break
                }
            }
        }

        // 2. If no targetId or targetId node is not editable, try active focused input
        if (targetNode == null || !targetNode.isEditable) {
            val focusedNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && focusedNode.isEditable) {
                targetNode = focusedNode
            }
        }

        // 3. Search active windows for input focus
        if (targetNode == null || !targetNode.isEditable) {
            for (w in (windows ?: emptyList())) {
                val wRoot = w.root ?: continue
                val focused = wRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && focused.isEditable) {
                    targetNode = focused
                    break
                }
            }
        }

        // 4. Fallback: find the first editable node on screen
        if (targetNode == null || !targetNode.isEditable) {
            if (root != null) {
                val editable = findFirstEditableNode(root)
                if (editable != null) targetNode = editable
            }
        }
        if (targetNode == null || !targetNode.isEditable) {
            for (w in (windows ?: emptyList())) {
                val wRoot = w.root ?: continue
                val editable = findFirstEditableNode(wRoot)
                if (editable != null) {
                    targetNode = editable
                    break
                }
            }
        }

        if (targetNode == null) {
            return ToolResult(
                status = "error",
                errorCode = ErrorCodes.ELEMENT_NOT_FOUND,
                message = "No editable input element found on screen. Tap an input field to focus it first.",
                retryable = true
            )
        }

        // Ensure the node has input focus
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // 1. Primary standard text insertion via ACTION_SET_TEXT
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        // 2. Fallback: if ACTION_SET_TEXT is rejected by the custom view, attempt ACTION_PASTE
        if (!success) {
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                if (clipboard != null) {
                    val clip = android.content.ClipData.newPlainText("JARVIS_INPUT", text)
                    clipboard.setPrimaryClip(clip)
                    success = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Paste fallback failed: ${e.message}")
            }
        }

        return if (success) {
            ToolResult(status = "ok", result = "Text '$text' successfully typed into input field")
        } else {
            ToolResult(
                status = "error",
                errorCode = ErrorCodes.GESTURE_FAILED,
                message = "Target input field rejected text input (node may be secure or read-only)",
                retryable = false
            )
        }
    }

    /**
     * Dispatches global key actions (HOME, BACK, RECENTS, etc.)
     */
    fun pressKey(keycodeStr: String): ToolResult {
        val key = keycodeStr.uppercase()
        val action = when {
            key.contains("HOME") -> GLOBAL_ACTION_HOME
            key.contains("BACK") -> GLOBAL_ACTION_BACK
            key.contains("RECENT") || key.contains("APP_SWITCH") -> GLOBAL_ACTION_RECENTS
            key.contains("NOTIFICATION") -> GLOBAL_ACTION_NOTIFICATIONS
            key.contains("QUICK_SETTINGS") -> GLOBAL_ACTION_QUICK_SETTINGS
            key.contains("POWER") -> GLOBAL_ACTION_POWER_DIALOG
            key.contains("LOCK") -> GLOBAL_ACTION_LOCK_SCREEN
            else -> null
        }

        if (action == null) {
            return ToolResult(
                status = "error",
                errorCode = ErrorCodes.INVALID_ARGUMENTS,
                message = "Unknown or unsupported keycode action: $keycodeStr",
                retryable = false
            )
        }

        val success = performGlobalAction(action)
        return if (success) {
            ToolResult(status = "ok", result = "Executed system action: $key")
        } else {
            ToolResult(
                status = "error",
                errorCode = ErrorCodes.GESTURE_FAILED,
                message = "Global action $key was not performed by system",
                retryable = true
            )
        }
    }

    /**
     * Launches an application by package name or friendly name.
     */
    fun openApp(packageNameOrQuery: String): ToolResult {
        val targetPkg = when (packageNameOrQuery.lowercase().trim()) {
            "youtube" -> "com.google.android.youtube"
            "chrome", "browser" -> "com.android.chrome"
            "settings" -> "com.android.settings"
            "termux" -> "com.termux"
            "camera" -> "com.google.android.GoogleCamera"
            "maps" -> "com.google.android.apps.maps"
            "gmail" -> "com.google.android.gm"
            "playstore", "play store" -> "com.android.vending"
            "whatsapp" -> "com.whatsapp"
            "calculator" -> "com.google.android.calculator"
            "shizuku" -> "moe.shizuku.privileged.api"
            else -> packageNameOrQuery.trim()
        }

        var intent = packageManager.getLaunchIntentForPackage(targetPkg)

        // Fallback: search installed applications if targetPkg was partial or name-based
        if (intent == null) {
            try {
                val installed = packageManager.getInstalledApplications(0)
                val matched = installed.firstOrNull { appInfo ->
                    appInfo.packageName.contains(targetPkg, ignoreCase = true) ||
                    packageManager.getApplicationLabel(appInfo).toString().contains(targetPkg, ignoreCase = true)
                }
                if (matched != null) {
                    intent = packageManager.getLaunchIntentForPackage(matched.packageName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed searching installed apps: ${e.message}")
            }
        }

        if (intent == null) {
            return ToolResult(
                status = "error",
                errorCode = ErrorCodes.APP_NOT_FOUND,
                message = "Application '$packageNameOrQuery' is not installed or has no launcher activity",
                retryable = false
            )
        }

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        return try {
            val resolvedPkg = intent.component?.packageName ?: targetPkg
            val before = currentApp.value
            if (before.equals(resolvedPkg, ignoreCase = true)) {
                return ToolResult(status = "ok", result = "✅ Aplikasi '$resolvedPkg' sudah berada di foreground.")
            }
            startActivity(intent)
            // Verifikasi foreground: tunggu accessibility event melaporkan app target (maks 3 dtk).
            // Dulu tool ini "tidak persist" karena sekadar startActivity tanpa konfirmasi.
            var now = currentApp.value
            var waitedMs = 0
            while (waitedMs < 3000 && !now.equals(resolvedPkg, ignoreCase = true)) {
                Thread.sleep(250)
                waitedMs += 250
                now = currentApp.value
            }
            if (now.equals(resolvedPkg, ignoreCase = true)) {
                ToolResult(status = "ok", result = "✅ Aplikasi '$resolvedPkg' terbuka & terverifikasi sebagai foreground (setelah ${waitedMs}ms).")
            } else {
                ToolResult(
                    status = "ok",
                    result = "⚠️ Perintah buka '$resolvedPkg' terkirim, tetapi foreground belum terkonfirmasi setelah ${waitedMs}ms (terdeteksi: '$now'). " +
                            "Verifikasi dengan 'get_current_app'/'dumpsys_window', lalu coba lagi bila perlu."
                )
            }
        } catch (e: Exception) {
            ToolResult(
                status = "error",
                errorCode = ErrorCodes.APP_NOT_FOUND,
                message = "Failed to launch '$targetPkg': ${e.message}",
                retryable = false
            )
        }
    }

    /**
     * Implements Level 1 app close (navigating to Home/Recents) as specified in §3.
     */
    fun closeApp(packageName: String): ToolResult {
        performGlobalAction(GLOBAL_ACTION_HOME)
        return ToolResult(
            status = "ok",
            result = "Minimized application '$packageName' to home screen (Level 1 UX close)"
        )
    }

    /**
     * Takes screenshot on Android 11+ via AccessibilityService fallback.
     */
    fun takeScreenshotCompat(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        val copy = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        onResult(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Accessibility screenshot failed with error code: $errorCode")
                        onResult(null)
                    }
                }
            )
        } else {
            onResult(null)
        }
    }

    /**
     * Traverses active window tree and collects interactive or text UI elements.
     */
    fun getScreenElements(): List<UiElementInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<UiElementInfo>()
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isClickable || !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
                val rectBounds = RectBounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    width = bounds.width(),
                    height = bounds.height(),
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY()
                )
                list.add(
                    UiElementInfo(
                        id = node.viewIdResourceName ?: "element_${list.size}",
                        text = node.text?.toString() ?: "",
                        contentDescription = node.contentDescription?.toString() ?: "",
                        viewId = node.viewIdResourceName ?: "",
                        className = node.className?.toString() ?: "",
                        bounds = rectBounds,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                        isScrollable = node.isScrollable,
                        packageName = node.packageName?.toString() ?: ""
                    )
                )
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        traverse(root)
        return list
    }

    /**
     * Mencari elemen UI yang cocok dengan teks (label tombol, judul, deskripsi, view id).
     * Pencarian case-insensitive & partial match — untuk agent loop & tap_by_text.
     */
    fun findElementsByText(query: String): List<UiElementInfo> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return getScreenElements().filter { el ->
            el.text.contains(q, ignoreCase = true) ||
                el.contentDescription.contains(q, ignoreCase = true) ||
                el.viewId.contains(q, ignoreCase = true)
        }
    }

    /**
     * Mengetuk elemen langsung berdasarkan teksnya — jauh lebih akurat daripada
     * tap koordinat manual (terutama setelah layar berputar/landscape).
     * Prioritas kecocokan: persis > diawali > mengandung; elemen clickable diutamakan.
     */
    suspend fun tapByText(query: String, timeoutMs: Long = 3000L): ToolResult {
        val q = query.trim()
        if (q.isBlank()) {
            return ToolResult("error", message = "Teks elemen tidak boleh kosong.")
        }
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, 20000L)
        var candidates = findElementsByText(q)
        while (candidates.isEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(250)
            candidates = findElementsByText(q)
        }
        if (candidates.isEmpty()) {
            return ToolResult(
                status = "error",
                message = "Tidak ada elemen berteks '$q' di layar${if (timeoutMs > 0) " (menunggu ${timeoutMs}ms)" else ""}. Coba 'find_by_text' dengan kata kunci lain, atau 'read_screen' untuk melihat isi layar."
            )
        }
        val target = candidates.sortedWith(
            compareByDescending<UiElementInfo> { it.isClickable }
                .thenBy { if (it.text.equals(q, true) || it.contentDescription.equals(q, true)) 0 else 1 }
                .thenBy { (it.bounds.width) * (it.bounds.height) }
        ).first()
        tapCoordinates(target.bounds.centerX.toFloat(), target.bounds.centerY.toFloat())
        return ToolResult(
            status = "ok",
            result = "👆 Tap '${(target.text.ifBlank { target.contentDescription }).ifBlank { target.viewId }}' di (${target.bounds.centerX}, ${target.bounds.centerY}) — cocok untuk '$q' (dari ${candidates.size} kandidat)."
        )
    }

    /** Snapshot ringan layar untuk diff_screen & wait_stable. */
    private data class ScreenSnapshot(val hash: Int, val summary: String, val count: Int)

    private fun snapshotScreen(): ScreenSnapshot {
        val els = getScreenElements()
        val sig = els.joinToString("|") { "${it.id}:${it.text}:${it.bounds.centerX},${it.bounds.centerY}" }
        val summary = els.take(20).joinToString("\n") { el ->
            "- '${el.text.ifBlank { el.contentDescription }}' @ (${el.bounds.centerX}, ${el.bounds.centerY})"
        }
        return ScreenSnapshot(sig.hashCode(), summary, els.size)
    }

    /**
     * Scroll otomatis sampai teks ditemukan (untuk list panjang) — menggantikan
     * swipe buta berulang. Scroll dari bawah (75% tinggi) ke atas (30%).
     */
    suspend fun scrollToText(query: String, maxSwipes: Int = 6): ToolResult {
        val q = query.trim()
        if (q.isBlank()) return ToolResult("error", message = "Parameter 'text' wajib diisi.")
        val metrics = ScreenshotManager.getScreenMetrics(this)
        val cx = metrics.widthPixels / 2f
        var swipes = 0
        var matched = findElementsByText(q)
        while (matched.isEmpty() && swipes < maxSwipes.coerceIn(1, 15)) {
            swipeCoordinates(cx, metrics.heightPixels * 0.75f, cx, metrics.heightPixels * 0.30f, 400)
            kotlinx.coroutines.delay(500)
            swipes++
            matched = findElementsByText(q)
        }
        return if (matched.isEmpty()) {
            ToolResult("error", message = "Teks '$q' tidak ditemukan setelah $swipes kali scroll.")
        } else {
            val el = matched.first()
            ToolResult(
                status = "ok",
                result = "📜 '$q' ditemukan setelah $swipes scroll @ (${el.bounds.centerX}, ${el.bounds.centerY}). Ketuk dengan tap_by_text."
            )
        }
    }

    /** Menunggu layar stabil (tidak berubah >= 2 interval 400ms) sebelum aksi berikutnya. */
    suspend fun waitForStableScreen(timeoutMs: Long = 3000L): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 15000L)
        var prev = snapshotScreen()
        var stableLoops = 0
        while (System.currentTimeMillis() < deadline && stableLoops < 2) {
            kotlinx.coroutines.delay(400)
            val cur = snapshotScreen()
            if (cur.hash == prev.hash) stableLoops++ else stableLoops = 0
            prev = cur
        }
        return ToolResult(
            status = "ok",
            result = if (stableLoops >= 2) {
                "✅ Layar stabil (${prev.count} elemen, tidak berubah >= 800ms)."
            } else {
                "⏳ Layar MASIH BERUBAH (terakhir ${prev.count} elemen). Tunggu sebentar sebelum aksi berikutnya."
            }
        )
    }

    /** Bandingkan layar saat ini vs snapshot pemanggilan sebelumnya (verifikasi before/after aksi). */
    suspend fun diffScreen(): ToolResult {
        val cur = snapshotScreen()
        val last = lastDiffSnapshot
        lastDiffSnapshot = cur
        return if (last == null) {
            ToolResult(
                status = "ok",
                result = "📸 Baseline layar disimpan (${cur.count} elemen). Lakukan aksi (tap/open_app), lalu panggil 'diff_screen' lagi untuk membandingkan before/after."
            )
        } else if (last.hash == cur.hash) {
            ToolResult(
                status = "ok",
                result = "⚠️ TIDAK ADA PERUBAHAN layar sebelum→sesudah (${cur.count} elemen sama persis). Kemungkinan aksi terakhir GAGAL/tidak berefek — coba pendekatan lain (mis. tap_by_text)."
            )
        } else {
            ToolResult(
                status = "ok",
                result = "✅ Layar BERUBAH sebelum→sesudah: ${last.count} elemen → ${cur.count} elemen.\nState terkini (20 teratas):\n${cur.summary}"
            )
        }
    }

    private fun findNodeByIdentifier(node: AccessibilityNodeInfo?, identifier: String): AccessibilityNodeInfo? {
        if (node == null) return null

        val viewId = node.viewIdResourceName?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if (viewId.equals(identifier, ignoreCase = true) ||
            viewId.endsWith("/$identifier", ignoreCase = true) ||
            viewId.contains(identifier, ignoreCase = true) ||
            text.equals(identifier, ignoreCase = true) ||
            text.contains(identifier, ignoreCase = true) ||
            desc.equals(identifier, ignoreCase = true) ||
            desc.contains(identifier, ignoreCase = true)
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findNodeByIdentifier(child, identifier)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }
}
