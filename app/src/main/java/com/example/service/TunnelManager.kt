package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.model.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * TunnelManager — Metode koneksi MCP #2: Tunnel HTTPS.
 *
 * Menjalankan `cloudflared tunnel` di Termux via RUN_COMMAND (tanpa perlu
 * membuka Termux secara manual), lalu memantau file log (di folder eksternal
 * milik app agar bisa dibaca kedua sisi) untuk menangkap URL publik
 * https://xxxx.trycloudflare.com yang kemudian ditampilkan & siap dipakai
 * sebagai endpoint MCP di ChatGPT / Claude.
 *
 * Metode #1 (URL langsung / LAN) tetap tersedia — lihat getDeviceIpAddress().
 */
object TunnelManager {

    private const val TAG = "TunnelManager"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.service_action.RUN_COMMAND"
    private const val RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tunnelUrl = MutableStateFlow<String?>(null)
    val tunnelUrl: StateFlow<String?> = _tunnelUrl.asStateFlow()

    private val _isTunnelStarting = MutableStateFlow(false)
    val isTunnelStarting = _isTunnelStarting.asStateFlow()

    private val _tunnelStatus = MutableStateFlow("Tidak aktif")
    val tunnelStatus: StateFlow<String> = _tunnelStatus.asStateFlow()

    private var pollJob: Job? = null
    private var logFile: File? = null

    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) != null
    } catch (_: Exception) {
        false
    }

    /** IP lokal HP (WiFi/LAN) untuk Metode 1 — tanpa izin lokasi (NetworkInterface API). */
    fun getDeviceIpAddress(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .toList()
        // Prioritaskan wlan0 (WiFi) daripada rmnet (seluler)
        candidates.firstOrNull { ifaceCandidate ->
            NetworkInterface.getByInetAddress(ifaceCandidate)?.name?.startsWith("wlan") == true
        }?.hostAddress ?: candidates.firstOrNull()?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "Gagal mendeteksi IP lokal: ${e.message}")
        null
    }

    /**
     * Menjalankan cloudflared tunnel di Termux (dipastikan terinstall otomatis).
     * Log ditulis ke folder eksternal app agar bisa dipantau dari sini.
     */
    fun startTunnel(context: Context, appPort: Int): ToolResult {
        if (!isTermuxInstalled(context)) {
            return ToolResult(
                "error",
                message = "Termux belum terinstall. Install Termux (F-Droid/GitHub) dulu, jalankan sekali, lalu coba lagi. Tombol Install di kartu Shizuku juga bisa memudahkan."
            )
        }

        val dir = context.getExternalFilesDir(null) ?: File(context.filesDir, "tunnel").apply { mkdirs() }
        dir.mkdirs()
        val log = File(dir, "tunnel.log")
        // Hapus log lama supaya URL yang terbaca pasti milik sesi baru
        try {
            log.delete()
            log.createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "Gagal reset log tunnel: ${e.message}")
        }
        logFile = log

        // Path yang bisa ditulis Termux: absolute path folder eksternal app.
        val logPath = log.absolutePath
        val script = buildString {
            append("command -v cloudflared >/dev/null 2>&1 || pkg install -y cloudflared; ")
            append("pkill -f 'cloudflared tunnel' 2>/dev/null; sleep 1; ")
            append("nohup cloudflared tunnel --url http://127.0.0.1:$appPort --loglevel info > '$logPath' 2>&1 & ")
            append("echo TUNNEL_LAUNCHED")
        }

        val ok = sendRunCommand(context, script)
        if (!ok) {
            return ToolResult(
                "error",
                message = "Gagal mengirim perintah ke Termux. Pastikan di Termux diizinkan: buka file ~/.termux/termux.properties dan isi 'allow-external-apps=true' (script setup resmi app juga bisa mengaturnya), lalu jalankan ulang setup dari tab Termux."
            )
        }

        _tunnelUrl.value = null
        _isTunnelStarting.value = true
        _tunnelStatus.value = "Menjalankan cloudflared di Termux… (install otomatis bila perlu, ±10-30 detik)"
        observeLogFile()
        return ToolResult("ok", result = "Perintah tunnel terkirim ke Termux. URL publik akan muncul otomatis di sini dalam ±10-30 detik.")
    }

    fun stopTunnel(context: Context) {
        sendRunCommand(
            context,
            "pkill -f 'cloudflared tunnel' 2>/dev/null; echo TUNNEL_STOPPED"
        )
        pollJob?.cancel()
        pollJob = null
        _isTunnelStarting.value = false
        _tunnelUrl.value = null
        _tunnelStatus.value = "Tidak aktif"
    }

    /** Kirim perintah ke Termux via RUN_COMMAND intent. */
    private fun sendRunCommand(context: Context, script: String): Boolean = try {
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            component = ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            putExtra(RUN_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
            putExtra(RUN_COMMAND_ARGUMENTS, arrayOf("-lc", script))
            putExtra(RUN_COMMAND_BACKGROUND, true)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "RUN_COMMAND gagal: ${e.message}")
        false
    }

    /** Pantau log tunnel sampai URL publik muncul. */
    private fun observeLogFile() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val urlRegex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")
            var waited = 0L
            while (isActive && waited < 90_000L) {
                delay(2000)
                waited += 2000
                val content = withContext(Dispatchers.IO) {
                    try {
                        logFile?.takeIf { it.exists() }?.readText() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                }
                val match = urlRegex.find(content)
                if (match != null) {
                    _tunnelUrl.value = match.value
                    _isTunnelStarting.value = false
                    _tunnelStatus.value = "Tunnel aktif 🎉"
                    Log.i(TAG, "Tunnel URL: ${match.value}")
                    return@launch
                }
                when {
                    content.contains("failed to connect", ignoreCase = true) ||
                        content.contains("connection refused", ignoreCase = true) -> {
                        _tunnelStatus.value = "Gagal tersambung ke internet/server. Cek koneksi lalu coba lagi."
                    }
                    content.contains("command not found", ignoreCase = true) -> {
                        _tunnelStatus.value = "cloudflared gagal diinstall di Termux. Jalankan 'pkg install cloudflared' manual lalu coba lagi."
                    }
                    content.isNotBlank() && waited % 10000 == 0L -> {
                        _tunnelStatus.value = "Menunggu URL tunnel… (${waited / 1000} detik)"
                    }
                }
            }
            if (_tunnelUrl.value == null) {
                _isTunnelStarting.value = false
                _tunnelStatus.value = "Timeout menunggu URL (90 detik). Cek log di Termux / koneksi internet, lalu coba lagi."
            }
        }
    }

    /** Simpan URL tunnel agar bisa dipakai ulang (mis. untuk reminder panduan). */
    fun buildMcpTunnelUrl(tunnelUrl: String): String = tunnelUrl.trimEnd('/') + "/mcp"

    fun openTermuxSetup(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}
