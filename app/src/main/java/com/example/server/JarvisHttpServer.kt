package com.example.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.model.ErrorCodes
import com.example.model.ServerLogItem
import com.example.model.ToolResult
import com.example.service.ImageDecodeManager
import com.example.service.JarvisAccessibilityService
import com.example.service.ScreenshotManager
import com.example.termux.TermuxScripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import kotlin.math.min

/**
 * Android-native HTTP Server implementing the REST interface for JARVIS-HP companion.
 * Binds strictly to 127.0.0.1 (localhost loopback) for device-local security.
 * Replaces JDK com.sun.net.httpserver to guarantee runtime compatibility on all Android devices.
 */
class JarvisHttpServer(
    private val context: Context,
    val port: Int = 8765,
    var token: String = "jarvis-token-8765",
    private val bindAllInterfaces: Boolean = false,
    private val onLog: (ServerLogItem) -> Unit
) {

    companion object {
        private const val TAG = "JarvisHttpServer"
        private const val NOTIFICATION_CHANNEL_ID = "jarvis_alerts"
    }

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    init {
        createNotificationChannel()
    }

    @Synchronized
    fun start(): Boolean {
        if (serverSocket != null && !serverSocket!!.isClosed) return true

        return try {
            // Default strict security: bind 127.0.0.1 (localhost loopback) only.
            // bindAllInterfaces = true HANYA jika pengguna mengaktifkan ekspos jaringan
            // (untuk tunnel HTTPS / akses LAN oleh AI eksternal via MCP).
            val bindAddr = InetAddress.getByName(if (bindAllInterfaces) "0.0.0.0" else "127.0.0.1")
            val s = ServerSocket(port, 50, bindAddr)
            serverSocket = s
            _isRunning.value = true
            Log.i(TAG, "Jarvis HTTP Server started at http://127.0.0.1:$port")

            acceptJob = scope.launch {
                while (_isRunning.value && !s.isClosed) {
                    try {
                        val clientSocket = s.accept()
                        launch {
                            handleConnection(clientSocket)
                        }
                    } catch (e: SocketException) {
                        // ServerSocket closed or reset
                        break
                    } catch (e: Exception) {
                        if (_isRunning.value) {
                            Log.e(TAG, "Exception accepting socket connection", e)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server on port $port", e)
            _isRunning.value = false
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            _isRunning.value = false
            acceptJob?.cancel()
            scope.coroutineContext[Job]?.cancelChildren()
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "Jarvis HTTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
        val clientIp: String
    )

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val output = BufferedOutputStream(socket.getOutputStream())

            val request = parseRequest(socket, input) ?: run {
                socket.close()
                return
            }

            handleRequest(request, output)
        } catch (e: Exception) {
            Log.w(TAG, "Error processing client connection: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun parseRequest(socket: Socket, input: InputStream): HttpRequest? {
        // Read header line by line
        val headerBytes = ByteArrayOutputStream()
        var lastFour = 0
        var byteRead: Int
        while (input.read().also { byteRead = it } != -1) {
            headerBytes.write(byteRead)
            lastFour = ((lastFour shl 8) or (byteRead and 0xFF))
            // Check for \r\n\r\n or \n\n
            if (lastFour == 0x0D0A0D0A || (lastFour and 0xFFFF) == 0x0A0A) {
                break
            }
        }

        val headerText = headerBytes.toString(Charsets.UTF_8.name())
        if (headerText.isBlank()) return null

        val lines = headerText.split("\r\n").ifEmpty { headerText.split("\n") }
        if (lines.isEmpty()) return null

        val requestLine = lines[0].trim()
        val reqTokens = requestLine.split(" ")
        if (reqTokens.size < 2) return null

        val method = reqTokens[0].uppercase(Locale.ROOT)
        val fullUri = reqTokens[1]

        val path: String
        val query: String?
        val qIndex = fullUri.indexOf('?')
        if (qIndex != -1) {
            path = fullUri.substring(0, qIndex)
            query = fullUri.substring(qIndex + 1)
        } else {
            path = fullUri
            query = null
        }

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val colonIndex = line.indexOf(':')
            if (colonIndex != -1) {
                val name = line.substring(0, colonIndex).trim().lowercase(Locale.ROOT)
                val value = line.substring(colonIndex + 1).trim()
                headers[name] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val read = input.read(bodyBytes, totalRead, contentLength - totalRead)
                if (read == -1) break
                totalRead += read
            }
            String(bodyBytes, 0, totalRead, Charsets.UTF_8)
        } else {
            ""
        }

        val clientIp = socket.inetAddress?.hostAddress ?: "127.0.0.1"

        return HttpRequest(
            method = method,
            path = path,
            query = query,
            headers = headers,
            body = body,
            clientIp = clientIp
        )
    }

    private suspend fun handleRequest(req: HttpRequest, output: OutputStream) {
        val path = req.path
        val method = req.method
        val clientIp = req.clientIp

        // CORS preflight: balas sebelum auth (preflight tidak membawa token)
        if (method == "OPTIONS") {
            sendResponse(output, 204, "", "text/plain", method, path, clientIp, "CORS preflight OK")
            return
        }

        // Public setup scripts and Termux scripts can be fetched directly from localhost
        if (path == "/setup.sh") {
            val script = TermuxScripts.getSetupScript(token, port)
            sendResponse(output, 200, script, "text/x-shellscript", method, path, clientIp, "Served setup.sh")
            return
        }
        if (path == "/setup-mcp.sh") {
            val script = TermuxScripts.getMcpSetupScript(token, port)
            sendResponse(output, 200, script, "text/x-shellscript", method, path, clientIp, "Served setup-mcp.sh")
            return
        }

        if (path.startsWith("/termux/")) {
            handleTermuxDownload(path, output, method, clientIp)
            return
        }

        val requestBody = req.body

        // ===== OAuth 2.0 Authorization Server (PUBLIK — dipakai klien MCP: ChatGPT, Claude, dll) =====
        if (path == "/.well-known/oauth-authorization-server" && method == "GET") {
            val host = req.headers["host"] ?: "127.0.0.1:$port"
            sendResponse(output, 200, OAuthManager.metadata(host).toString(), "application/json", method, path, clientIp, "OAuth metadata")
            return
        }
        if (path == "/oauth/register" && method == "POST") {
            val (status, respBody) = OAuthManager.registerClient(requestBody)
            sendResponse(output, status, respBody.toString(), "application/json", method, path, clientIp, "OAuth client registration")
            return
        }
        if (path == "/oauth/authorize" && method == "GET") {
            val (status, content) = OAuthManager.createAuthorizePage(req.query)
            val body = if (content is String) content else content.toString()
            sendResponse(output, status, body, "text/html; charset=utf-8", method, path, clientIp, "OAuth approval page")
            return
        }
        if (path == "/oauth/authorize/decision" && method == "GET") {
            val reqId = extractQueryParam(req.query, "req")
            val decision = extractQueryParam(req.query, "decision") ?: ""
            val (status, target, isRedirect) = OAuthManager.decideAuthorization(reqId, decision == "allow")
            if (isRedirect) {
                sendRedirect(output, status, target, method, path, clientIp, "OAuth decision: $decision")
            } else {
                sendResponse(output, status, target, "text/html; charset=utf-8", method, path, clientIp, "OAuth decision error", true)
            }
            return
        }
        if (path == "/oauth/token" && method == "POST") {
            val (status, respBody) = OAuthManager.exchangeToken(requestBody)
            sendResponse(output, status, respBody.toString(), "application/json", method, path, clientIp, "OAuth token exchange")
            return
        }

        // Check authentication token - MCP juga menerima standar Authorization: Bearer <token>
        val bearer = req.headers["authorization"]?.takeIf {
            it.startsWith("Bearer ", ignoreCase = true)
        }?.substring(7)?.trim()
        val authHeader = req.headers["x-local-token"] ?: bearer
        val queryToken = extractQueryParam(req.query, "token")
        val isAuthorized = authHeader == token || queryToken == token ||
            (bearer != null && OAuthManager.isValidAccessToken(bearer))

        if (!isAuthorized) {
            val errJson = JSONObject().apply {
                put("status", "error")
                put("error_code", ErrorCodes.UNAUTHORIZED)
                put("message", "Unauthorized. Provide correct X-Local-Token header or ?token= query parameter.")
                put("retryable", false)
            }.toString()
            sendResponse(output, 401, errJson, "application/json", method, path, clientIp, "401 Unauthorized", true)
            return
        }

        // ===== MCP (Model Context Protocol) endpoint: /mcp =====
        if (path == "/mcp") {
            when (method) {
                "POST" -> {
                    val (status, body) = McpServer.handleRequest(requestBody)
                    // Notifikasi JSON-RPC -> 202 Accepted (body kosong); lainnya respons penuh
                    sendResponse(output, status, body ?: "", "application/json", method, path, clientIp, "MCP request -> $status")
                }
                else -> {
                    // Streamable HTTP: GET (SSE stream) & DELETE (session) tidak ditawarkan server stateless ini
                    val err = errorJson(ErrorCodes.INVALID_ARGUMENTS, "MCP server ini stateless: hanya POST /mcp yang didukung (metode $method tidak tersedia)", false)
                    sendResponse(output, 405, err, "application/json", method, path, clientIp, "MCP 405 method not allowed", true)
                }
            }
            return
        }

        try {
            when {
                path == "/status" && method == "GET" -> {
                    val statusJson = JSONObject().apply {
                        put("status", "ok")
                        put("server", "JARVIS-HP Companion")
                        put("port", port)
                        put("accessibility_connected", JarvisAccessibilityService.instance != null)
                        put("media_projection_active", ScreenshotManager.isMediaProjectionActive.value)
                        put("current_app", JarvisAccessibilityService.currentApp.value)
                        put("timestamp", System.currentTimeMillis())
                    }.toString()
                    sendResponse(output, 200, statusJson, "application/json", method, path, clientIp, "Status checked")
                }

                path == "/screen/elements" && method == "GET" -> {
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        val err = errorJson(
                            ErrorCodes.SERVICE_NOT_CONNECTED,
                            "Accessibility Service is not enabled. Please enable JARVIS in Accessibility Settings.",
                            true
                        )
                        sendResponse(output, 503, err, "application/json", method, path, clientIp, "Accessibility not connected", true)
                        return
                    }

                    val elements = service.readScreenElements()
                    val rootObj = JSONObject().apply {
                        put("status", "ok")
                        put("count", elements.size)
                        put("current_app", JarvisAccessibilityService.currentApp.value)
                        val arr = JSONArray()
                        for (el in elements) {
                            val item = JSONObject().apply {
                                put("id", el.id)
                                put("text", el.text)
                                put("content_desc", el.contentDescription)
                                put("view_id", el.viewId)
                                put("class", el.className)
                                put("is_clickable", el.isClickable)
                                put("is_editable", el.isEditable)
                                put("is_scrollable", el.isScrollable)
                                val b = JSONObject().apply {
                                    put("left", el.bounds.left)
                                    put("top", el.bounds.top)
                                    put("right", el.bounds.right)
                                    put("bottom", el.bounds.bottom)
                                    put("center_x", el.bounds.centerX)
                                    put("center_y", el.bounds.centerY)
                                }
                                put("bounds", b)
                            }
                            arr.put(item)
                        }
                        put("elements", arr)
                    }.toString()
                    sendResponse(output, 200, rootObj, "application/json", method, path, clientIp, "Returned ${elements.size} elements")
                }

                path == "/screen/screenshot" && method == "GET" -> {
                    val (base64, errorMsg) = ScreenshotManager.captureBase64(context)
                    if (base64 != null) {
                        val resp = JSONObject().apply {
                            put("status", "ok")
                            put("format", "image/jpeg")
                            put("screenshot_base64", base64)
                        }.toString()
                        sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Captured screenshot")
                    } else {
                        val err = errorJson(ErrorCodes.PERMISSION_DENIED, errorMsg ?: "Failed to capture screenshot", false)
                        sendResponse(output, 500, err, "application/json", method, path, clientIp, "Screenshot error: $errorMsg", true)
                    }
                }

                // Image decode endpoint: ubah gambar (base64/file/screenshot terakhir) menjadi deskripsi tekstual + OCR
                path == "/image/decode" && method == "POST" -> {
                    val json = safeParseJson(requestBody)
                    val withOcr = json.optBoolean("with_ocr", true)
                    val source = json.optString("source", "").trim().lowercase()
                    val b64 = json.optString("base64", json.optString("image_base64", ""))
                    val filePath = json.optString("path", "")
                    val result = when {
                        b64.isNotBlank() -> ImageDecodeManager.analyzeBase64(b64, withOcr)
                        source == "last_screenshot" || source == "screenshot" -> ImageDecodeManager.analyzeLastScreenshot(withOcr)
                        filePath.isNotBlank() -> ImageDecodeManager.analyzePath(filePath, withOcr)
                        else -> ToolResult("error", errorCode = ErrorCodes.INVALID_ARGUMENTS, message = "Missing image source: provide 'base64', 'source':'last_screenshot', or 'path'")
                    }
                    val resp = JSONObject().apply {
                        put("status", result.status)
                        if (result.status == "ok") {
                            put("analysis", result.result ?: "")
                        } else {
                            put("error", result.message ?: "decode failed")
                        }
                    }.toString()
                    if (result.status == "ok") {
                        sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Decoded image to text")
                    } else {
                        sendResponse(output, 400, resp, "application/json", method, path, clientIp, "Image decode failed", true)
                    }
                }

                path == "/action/tap" && method == "POST" -> {
                    val service = getAccessibilityServiceOrError(output, method, path, clientIp) ?: return
                    val json = safeParseJson(requestBody)

                    if (json.has("x") && json.has("y")) {
                        val x = json.getDouble("x").toFloat()
                        val y = json.getDouble("y").toFloat()
                        val result = service.tapCoordinates(x, y)
                        sendToolResponse(output, result, method, path, clientIp, "Tap ($x, $y)")
                    } else if (json.has("element_id")) {
                        val elemId = json.getString("element_id")
                        val result = service.tapElement(elemId)
                        sendToolResponse(output, result, method, path, clientIp, "Tap element '$elemId'")
                    } else {
                        val err = errorJson(ErrorCodes.INVALID_ARGUMENTS, "Missing {x, y} or {element_id} in request body", false)
                        sendResponse(output, 400, err, "application/json", method, path, clientIp, "Invalid tap args", true)
                    }
                }

                path == "/action/swipe" && method == "POST" -> {
                    val service = getAccessibilityServiceOrError(output, method, path, clientIp) ?: return
                    val json = safeParseJson(requestBody)
                    val x1 = json.optDouble("x1", 0.0).toFloat()
                    val y1 = json.optDouble("y1", 0.0).toFloat()
                    val x2 = json.optDouble("x2", 0.0).toFloat()
                    val y2 = json.optDouble("y2", 0.0).toFloat()
                    val duration = json.optLong("duration_ms", 300L)

                    val result = service.swipeCoordinates(x1, y1, x2, y2, duration)
                    sendToolResponse(output, result, method, path, clientIp, "Swipe ($x1, $y1) -> ($x2, $y2)")
                }

                path == "/action/type" && method == "POST" -> {
                    val service = getAccessibilityServiceOrError(output, method, path, clientIp) ?: return
                    val json = safeParseJson(requestBody)
                    val text = when {
                        json.has("text") -> json.optString("text", "")
                        json.has("value") -> json.optString("value", "")
                        json.has("query") -> json.optString("query", "")
                        json.has("content") -> json.optString("content", "")
                        else -> ""
                    }
                    val elementId = when {
                        json.has("element_id") -> json.getString("element_id")
                        json.has("id") -> json.getString("id")
                        json.has("target_id") -> json.getString("target_id")
                        else -> null
                    }

                    val result = service.typeText(elementId, text)
                    sendToolResponse(output, result, method, path, clientIp, "Type text '$text'")
                }

                path == "/action/key" && method == "POST" -> {
                    val service = getAccessibilityServiceOrError(output, method, path, clientIp) ?: return
                    val json = safeParseJson(requestBody)
                    val keycode = json.optString("keycode", json.optString("action", "BACK"))

                    val result = service.pressKey(keycode)
                    sendToolResponse(output, result, method, path, clientIp, "Press key $keycode")
                }

                path == "/app/open" && method == "POST" -> {
                    val service = getAccessibilityServiceOrError(output, method, path, clientIp) ?: return
                    val json = safeParseJson(requestBody)
                    val pkg = json.optString("package_name", "")

                    if (pkg.isBlank()) {
                        val err = errorJson(ErrorCodes.INVALID_ARGUMENTS, "Missing 'package_name' parameter", false)
                        sendResponse(output, 400, err, "application/json", method, path, clientIp, "Missing package_name", true)
                        return
                    }

                    val result = service.openApp(pkg)
                    sendToolResponse(output, result, method, path, clientIp, "Open app $pkg")
                }

                path == "/app/close" && method == "POST" -> {
                    val service = getAccessibilityServiceOrError(output, method, path, clientIp) ?: return
                    val json = safeParseJson(requestBody)
                    val pkg = json.optString("package_name", "")

                    val result = service.closeApp(pkg)
                    sendToolResponse(output, result, method, path, clientIp, "Close app $pkg")
                }

                path == "/app/current" && method == "GET" -> {
                    val curPkg = JarvisAccessibilityService.currentApp.value
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("package_name", curPkg)
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Current app: $curPkg")
                }

                path == "/system/battery" && method == "GET" -> {
                    val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                        context.registerReceiver(null, ifilter)
                    }
                    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("level", pct)
                        put("is_charging", isCharging)
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Battery: $pct%")
                }

                path == "/system/wifi" && method == "GET" -> {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = cm.activeNetwork
                    val caps = cm.getNetworkCapabilities(network)
                    val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    val ssid = if (isWifi) wifiManager.connectionInfo?.ssid?.replace("\"", "") ?: "Connected" else "Disconnected"

                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("connected", isWifi)
                        put("ssid", ssid)
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "WiFi: $ssid")
                }

                path == "/system/volume" && method == "GET" -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("media_volume", current)
                        put("max_volume", max)
                        put("percentage", (current * 100) / max)
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Volume read: $current/$max")
                }

                path == "/system/volume" && method == "POST" -> {
                    val json = safeParseJson(requestBody)
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetLevel = if (json.has("percentage")) {
                        val pct = json.getInt("percentage").coerceIn(0, 100)
                        (pct * max) / 100
                    } else {
                        json.optInt("level", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).coerceIn(0, max)
                    }

                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetLevel, AudioManager.FLAG_SHOW_UI)
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("result", "Set media volume to $targetLevel / $max")
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Set volume $targetLevel")
                }

                path == "/clipboard" && method == "GET" -> {
                    var clipText = ""
                    mainHandler.post {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    }
                    Thread.sleep(80)
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("text", clipText)
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Read clipboard")
                }

                path == "/clipboard" && method == "POST" -> {
                    val json = safeParseJson(requestBody)
                    val text = json.optString("text", "")
                    mainHandler.post {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("JARVIS", text)
                        clipboard.setPrimaryClip(clip)
                    }
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("result", "Clipboard updated")
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Wrote clipboard")
                }

                path == "/notify" && method == "POST" -> {
                    val json = safeParseJson(requestBody)
                    val title = json.optString("title", "JARVIS-HP")
                    val message = json.optString("message", "Perintah berhasil dieksekusi")
                    showNotification(title, message)
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("result", "Notification posted")
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Notification: $title")
                }

                // Shizuku & ADB Shell Execution Endpoint
                path == "/adb/shell" && method == "POST" -> {
                    val json = safeParseJson(requestBody)
                    val cmd = json.optString("command", "").trim()
                    if (cmd.isBlank()) {
                        val err = errorJson(ErrorCodes.INVALID_ARGUMENTS, "Missing 'command' parameter in request body", false)
                        sendResponse(output, 400, err, "application/json", method, path, clientIp, "Empty command", true)
                        return
                    }

                    val shellResult = com.example.service.AdbShizukuManager.executeShell(cmd)
                    sendToolResponse(output, shellResult, method, path, clientIp, "ADB Shell: $cmd")
                }

                // System & Shizuku Status Endpoint
                path == "/adb/status" && method == "GET" -> {
                    val isShizukuInstalled = com.example.service.AdbShizukuManager.isShizukuInstalled(context)
                    val isStorageGranted = com.example.service.AdbShizukuManager.isManageStorageGranted()
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("shizuku_installed", isShizukuInstalled)
                        put("manage_storage_granted", isStorageGranted)
                        put("accessibility_connected", JarvisAccessibilityService.instance != null)
                        put("media_projection_active", ScreenshotManager.isMediaProjectionActive.value)
                    }.toString()
                    sendResponse(output, 200, resp, "application/json", method, path, clientIp, "Checked ADB / Shizuku status")
                }

                else -> {
                    val err = errorJson(ErrorCodes.UNKNOWN_ERROR, "Endpoint '$path' not found on JARVIS Companion Server", false)
                    sendResponse(output, 404, err, "application/json", method, path, clientIp, "404 Not Found", true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in endpoint $path", e)
            val err = errorJson(ErrorCodes.UNKNOWN_ERROR, "Internal server error: ${e.message}", false)
            sendResponse(output, 500, err, "application/json", method, path, clientIp, "500 Internal Error: ${e.message}", true)
        }
    }

    private fun handleTermuxDownload(path: String, output: OutputStream, method: String, clientIp: String) {
        when (path) {
            "/termux/agent.py" -> sendResponse(output, 200, TermuxScripts.agentPy, "text/plain", method, path, clientIp, "Downloaded agent.py")
            "/termux/config.py" -> sendResponse(output, 200, TermuxScripts.getConfigPy(token, port), "text/plain", method, path, clientIp, "Downloaded config.py")
            "/termux/memory.py" -> sendResponse(output, 200, TermuxScripts.memoryPy, "text/plain", method, path, clientIp, "Downloaded memory.py")
            "/termux/INSTRUCTION.md", "/termux/instruction.md" -> sendResponse(output, 200, TermuxScripts.instructionMd, "text/markdown", method, path, clientIp, "Downloaded INSTRUCTION.md")
            "/termux/requirements.txt" -> sendResponse(output, 200, TermuxScripts.requirementsTxt, "text/plain", method, path, clientIp, "Downloaded requirements.txt")
            "/termux/tools/__init__.py" -> sendResponse(output, 200, TermuxScripts.toolsInitPy, "text/plain", method, path, clientIp, "Downloaded tools/__init__.py")
            "/termux/tools/android_tools.py", "/termux/android_tools.py" -> sendResponse(output, 200, TermuxScripts.androidToolsPy, "text/plain", method, path, clientIp, "Downloaded android_tools.py")
            "/termux/tools/termux_tools.py", "/termux/termux_tools.py" -> sendResponse(output, 200, TermuxScripts.termuxToolsPy, "text/plain", method, path, clientIp, "Downloaded termux_tools.py")
            "/termux/tools/custom/__init__.py" -> sendResponse(output, 200, TermuxScripts.customInitPy, "text/plain", method, path, clientIp, "Downloaded custom/__init__.py")
            "/termux/tools/custom/cek_storage.py" -> sendResponse(output, 200, TermuxScripts.customCekStoragePy, "text/plain", method, path, clientIp, "Downloaded custom/cek_storage.py")
            "/termux/tools/custom/buka_youtube.py" -> sendResponse(output, 200, TermuxScripts.customBukaYoutubePy, "text/plain", method, path, clientIp, "Downloaded custom/buka_youtube.py")
            "/termux/tools/custom/cek_ram.py" -> sendResponse(output, 200, TermuxScripts.customCekRamPy, "text/plain", method, path, clientIp, "Downloaded custom/cek_ram.py")
            else -> {
                val err = errorJson(ErrorCodes.UNKNOWN_ERROR, "Termux asset '$path' not found", false)
                sendResponse(output, 404, err, "application/json", method, path, clientIp, "404 Not Found", true)
            }
        }
    }

    private fun sendToolResponse(
        output: OutputStream,
        result: ToolResult,
        method: String,
        path: String,
        clientIp: String,
        summary: String
    ) {
        val root = JSONObject().apply {
            put("status", result.status)
            if (result.result != null) put("result", result.result)
            if (result.errorCode != null) put("error_code", result.errorCode)
            if (result.message != null) put("message", result.message)
            put("retryable", result.retryable)
        }.toString()

        val isError = result.status != "ok"
        val statusHttp = if (isError) {
            if (result.errorCode == ErrorCodes.ELEMENT_NOT_FOUND) 404 else 400
        } else {
            200
        }
        sendResponse(output, statusHttp, root, "application/json", method, path, clientIp, summary, isError)
    }

    private fun getAccessibilityServiceOrError(
        output: OutputStream,
        method: String,
        path: String,
        clientIp: String
    ): JarvisAccessibilityService? {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            val err = errorJson(
                ErrorCodes.SERVICE_NOT_CONNECTED,
                "Accessibility Service is not connected. Enable JARVIS in Settings > Accessibility.",
                true
            )
            sendResponse(output, 503, err, "application/json", method, path, clientIp, "Accessibility not connected", true)
            return null
        }
        return service
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        body: String,
        contentType: String,
        method: String,
        path: String,
        clientIp: String,
        summary: String,
        isError: Boolean = false
    ) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val statusText = when (statusCode) {
                200 -> "OK"
                202 -> "Accepted"
                204 -> "No Content"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                500 -> "Internal Server Error"
                503 -> "Service Unavailable"
                else -> "Response"
            }

            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n")
            headerBuilder.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
            headerBuilder.append("Content-Length: ").append(bytes.size).append("\r\n")
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
            headerBuilder.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            headerBuilder.append("Access-Control-Allow-Headers: Content-Type, X-Local-Token, Authorization, MCP-Protocol-Version, Mcp-Session-Id\r\n")
            headerBuilder.append("Connection: close\r\n")
            headerBuilder.append("\r\n")

            output.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()

            onLog(
                ServerLogItem(
                    method = method,
                    path = path,
                    statusCode = statusCode,
                    clientIp = clientIp,
                    summary = summary,
                    isError = isError,
                    payloadPreview = if (body.length > 200) body.substring(0, 200) + "..." else body
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error writing HTTP response", e)
        }
    }

    private fun sendRedirect(
        output: OutputStream,
        statusCode: Int,
        location: String,
        method: String,
        path: String,
        clientIp: String,
        summary: String
    ) {
        try {
            val statusText = if (statusCode == 302) "Found" else "Redirect"
            val header = StringBuilder()
                .append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n")
                .append("Location: ").append(location).append("\r\n")
                .append("Content-Length: 0\r\n")
                .append("Connection: close\r\n")
                .append("\r\n")
            output.write(header.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            onLog(
                ServerLogItem(
                    method = method,
                    path = path,
                    statusCode = statusCode,
                    clientIp = clientIp,
                    summary = summary,
                    isError = false,
                    payloadPreview = location.take(200)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error writing redirect response", e)
        }
    }

    private fun errorJson(code: String, message: String, retryable: Boolean): String {
        return JSONObject().apply {
            put("status", "error")
            put("error_code", code)
            put("message", message)
            put("retryable", retryable)
        }.toString()
    }

    private fun safeParseJson(raw: String): JSONObject {
        return if (raw.isBlank()) JSONObject() else try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun extractQueryParam(query: String?, key: String): String? {
        if (query == null) return null
        val pairs = query.split("&")
        for (pair in pairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == key) {
                return parts[1]
            }
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "JARVIS Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Incoming notifications from JARVIS agent"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), notif)
    }
}
