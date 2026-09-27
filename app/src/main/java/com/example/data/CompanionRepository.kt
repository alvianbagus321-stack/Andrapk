package com.example.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.example.model.ServerLogItem
import com.example.model.SystemTelemetry
import com.example.model.UiElementInfo
import com.example.server.JarvisHttpServer
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class CompanionRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _serverLogs = MutableStateFlow<List<ServerLogItem>>(emptyList())
    val serverLogs = _serverLogs.asStateFlow()

    private val _token = MutableStateFlow("jarvis-token-8765")
    val token = _token.asStateFlow()

    private val _port = MutableStateFlow(8765)
    val port = _port.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning = _isServerRunning.asStateFlow()

    /** true = server bind 0.0.0.0 (bisa dijangkau LAN/tunnel AI eksternal via MCP). */
    private val _networkExposed = MutableStateFlow(false)
    val networkExposed = _networkExposed.asStateFlow()

    private val prefs = context.getSharedPreferences("andra_server_prefs", Context.MODE_PRIVATE)

    private val _telemetry = MutableStateFlow(SystemTelemetry())
    val telemetry = _telemetry.asStateFlow()

    private val _inspectedElements = MutableStateFlow<List<UiElementInfo>>(emptyList())
    val inspectedElements = _inspectedElements.asStateFlow()

    private var httpServer: JarvisHttpServer? = null

    init {
        _networkExposed.value = prefs.getBoolean("pref_network_exposed", false)
        startTelemetryLoop()
    }

    /**
     * Toggle ekspos jaringan untuk MCP/AI eksternal.
     * Server di-restart otomatis agar bind address baru langsung berlaku.
     */
    fun setNetworkExposed(enabled: Boolean) {
        _networkExposed.value = enabled
        prefs.edit().putBoolean("pref_network_exposed", enabled).apply()
        if (_isServerRunning.value) {
            stopServer()
            startServer()
        }
    }

    fun startServer() {
        if (httpServer != null && _isServerRunning.value) return

        val server = JarvisHttpServer(
            context = context,
            port = _port.value,
            token = _token.value,
            bindAllInterfaces = _networkExposed.value,
            onLog = { logItem ->
                val current = _serverLogs.value.toMutableList()
                current.add(0, logItem)
                if (current.size > 150) {
                    _serverLogs.value = current.take(150)
                } else {
                    _serverLogs.value = current
                }
            }
        )
        val started = server.start()
        httpServer = server
        _isServerRunning.value = started
    }

    fun stopServer() {
        httpServer?.stop()
        httpServer = null
        _isServerRunning.value = false
    }

    fun toggleServer() {
        if (_isServerRunning.value) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun regenerateToken(): String {
        val newToken = "jarvis-" + UUID.randomUUID().toString().substring(0, 8)
        _token.value = newToken
        httpServer?.token = newToken
        return newToken
    }

    fun clearLogs() {
        _serverLogs.value = emptyList()
    }

    fun refreshInspectedElements(): List<UiElementInfo> {
        val elements = JarvisAccessibilityService.instance?.readScreenElements() ?: emptyList()
        _inspectedElements.value = elements
        return elements
    }

    private fun startTelemetryLoop() {
        scope.launch {
            // Tunda poll pertama agar tidak bersaing dengan render startup.
            delay(2000)
            while (isActive) {
                updateTelemetry()
                delay(5000)
            }
        }
    }

    private fun updateTelemetry() {
        try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ssid = if (isWifi) wifiManager.connectionInfo?.ssid?.replace("\"", "") ?: "Connected" else "Disconnected"

            val curApp = JarvisAccessibilityService.currentApp.value

            val newTelemetry = SystemTelemetry(
                batteryLevel = pct,
                isCharging = isCharging,
                wifiConnected = isWifi,
                wifiSsid = ssid,
                currentAppPackage = curApp
            )
            // Hanya emit bila datanya benar-benar berubah, agar UI tidak
            // recompose tiap 5 detik tanpa alasan (hemat frame, layar mulus).
            if (newTelemetry != _telemetry.value) {
                _telemetry.value = newTelemetry
            }
        } catch (_: Exception) {}
    }
}
