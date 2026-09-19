package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.JarvisApp
import com.example.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages all built-in and user/AI-defined tools in JARVIS-HP.
 * Executes all tools on the actual Android device (Shell, Termux, Shizuku, Intent, Accessibility, HTTP, System Logic).
 */
object ToolManager {

    private const val TAG = "ToolManager"
    private const val PREFS_NAME = "jarvis_custom_tools_prefs"
    private const val KEY_TOOLS_JSON = "custom_tools_list"

    private var sharedPreferences: SharedPreferences? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Built-in tools definition
    val builtInTools: List<CustomTool> = listOf(
        CustomTool(
            id = "open_app",
            name = "Buka Aplikasi",
            description = "Membuka aplikasi target berdasarkan package name atau nama umum (contoh: youtube, chrome, settings, whatsapp)",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "open_app",
            parametersSchema = """{"package_name": "com.google.android.youtube"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "type_text",
            name = "Ketik Teks",
            description = "Mengetikkan teks secara otomatis pada input field yang sedang aktif atau target element ID",
            category = "Input & Form",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "type_text",
            parametersSchema = """{"text": "Halo Dunia", "element_id": "opsional_id"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "tap",
            name = "Tap Layar",
            description = "Melakukan klik/tap pada koordinat spesifik (x, y) atau identifier teks/ID elemen",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "tap",
            parametersSchema = """{"x": 500, "y": 800, "element_id": "opsional_id"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "swipe",
            name = "Swipe / Scroll",
            description = "Mengusap layar dari koordinat awal (x1, y1) ke koordinat akhir (x2, y2)",
            category = "Gestur & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "swipe",
            parametersSchema = """{"x1": 500, "y1": 1500, "x2": 500, "y2": 500, "duration_ms": 300}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "press_key",
            name = "Tekan Tombol Sistem",
            description = "Menekan tombol navigasi sistem seperti BACK, HOME, RECENTS, ENTER, VOLUME_UP, VOLUME_DOWN",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "press_key",
            parametersSchema = """{"keycode": "BACK"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "read_screen",
            name = "Baca Layar (UI Hierarchy)",
            description = "Membaca semua elemen UI yang tampil di layar beserta teks, id, dan posisinya",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "read_screen",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "screenshot",
            name = "Screenshot Layar",
            description = "Menangkap tampilan layar perangkat (PNG / Base64)",
            category = "Inspeksi & Visi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "screenshot",
            parametersSchema = """{"format": "base64"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "battery",
            name = "Info Baterai",
            description = "Memeriksa persentase baterai, voltase, status pengisian, dan temperatur",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "battery",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "shell",
            name = "Shell Command",
            description = "Mengeksekusi perintah shell Android via Shizuku / Termux / Linux Runtime",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "{command}",
            parametersSchema = """{"command": "pm list packages -3"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_command",
            name = "Termux Terminal Command",
            description = "Mengeksekusi perintah terminal / shell langsung (contoh: ls, df -h, cat, top, dumpsys, python script)",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "{command}",
            parametersSchema = """{"command": "df -h"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_storage",
            name = "Info Storage HP",
            description = "Mendapatkan informasi kapasitas penyimpanan internal HP (Total, Digunakan, dan Sisa dalam GB)",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_storage",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "cek_ram",
            name = "Info RAM HP",
            description = "Mengecek penggunaan memori RAM perangkat (Total, Tersedia, dan Terpakai dalam MB & GB)",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "cek_ram",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "clipboard_read",
            name = "Baca Clipboard",
            description = "Membaca teks yang sedang disalin di clipboard sistem HP",
            category = "Sistem & Teks",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "clipboard_read",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "clipboard_write",
            name = "Tulis Clipboard",
            description = "Menyalin teks baru ke clipboard sistem HP",
            category = "Sistem & Teks",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "clipboard_write",
            parametersSchema = """{"text": "Teks yang ingin disalin"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "flashlight_toggle",
            name = "Senter / Flashlight",
            description = "Menyalakan atau mematikan lampu senter kamera HP",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "flashlight_toggle",
            parametersSchema = """{"enable": true}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "send_notification",
            name = "Kirim Notifikasi",
            description = "Mengirimkan banner notifikasi lokal ke status bar Android",
            category = "Sistem & Notifikasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "send_notification",
            parametersSchema = """{"title": "Pesan JARVIS", "message": "Isi notifikasi"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "http_request",
            name = "HTTP API Request",
            description = "Melakukan request HTTP GET / POST ke web API eksternal dan mengambil responnya",
            category = "Web & API",
            scriptType = ToolScriptType.HTTP,
            command = "GET https://api.ipify.org?format=json",
            parametersSchema = """{"url": "https://api.ipify.org?format=json", "method": "GET"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_service",
            name = "Termux Service Control",
            description = "Mengontrol background service di Termux & daemon JARVIS (action: status, start, stop, restart, run_agent, list; service: nama_service)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_service",
            parametersSchema = """{"action": "status", "service": "jarvis_agent"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_api",
            name = "Termux API Runner",
            description = "Menjalankan perintah Termux:API (command: battery, wifi, tts, vibrate, torch, notification, toast, location, volume, clipboard-get, clipboard-set, sensor, sms; args: opsi)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_api",
            parametersSchema = """{"command": "battery", "args": ""}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_pkg",
            name = "Termux Package Manager",
            description = "Menginstal, mengupdate, atau mencari package di Termux (action: install, update, list, search; package: nama_package)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_pkg",
            parametersSchema = """{"action": "list", "package": "python"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_python",
            name = "Termux Python Runner",
            description = "Mengeksekusi kode atau script Python 3 secara langsung dalam runtime Termux Linux",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_python",
            parametersSchema = """{"code": "import platform, sys; print('Python', sys.version)"}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_file",
            name = "Termux File Operations",
            description = "Membaca, menulis, mendaftar, atau menghapus file di direktori Termux / Storage (action: read, write, list, delete, mkdir; path: direktori/file; content: teks)",
            category = "Termux & Service",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "termux_file",
            parametersSchema = """{"action": "list", "path": "."}""",
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "create_tool",
            name = "Buat Tool Baru (AI Dynamic)",
            description = "Membuat dan mendaftarkan tool otomatis baru ke dalam sistem",
            category = "AI Meta",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "create_tool",
            parametersSchema = """{"name": "Nama Tool", "description": "Deskripsi", "script_type": "shell", "command": "perintah", "parameters_schema": "{}"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "create_python_tool",
            name = "Buat Tool Python Termux (@tool)",
            description = "Membuat modul custom tool Python baru dengan decorator @tool untuk disimpan di tools/custom/",
            category = "AI Meta",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "create_python_tool",
            parametersSchema = """{"filename": "nama_tool.py", "tool_name": "nama_tool", "description": "Deskripsi tool", "code": "kode python lengkap"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "save_memory",
            name = "Simpan Memori (.md)",
            description = "Menyimpan informasi penting, preferensi user, atau catatan permanen ke bank memori Markdown (.md)",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "save_memory",
            parametersSchema = """{"category": "Preferensi User", "content": "Rincian informasi yang perlu diingat"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "recall_memory",
            name = "Akses Memori (.md)",
            description = "Membaca dan mencari memori tersimpan dari bank memori Markdown (.md)",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "recall_memory",
            parametersSchema = """{"query": "kata kunci pencarian"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "summarize_memory",
            name = "Arsip & Padatkan Memori (.md)",
            description = "Memadatkan dan mengarsipkan catatan memori lama jika ukuran file memori melebihi batas untuk menjaga kecepatan & efisiensi token AI",
            category = "Memori & Context",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "summarize_memory",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "open_file",
            name = "Buka Berkas",
            description = "Membuka berkas atau dokumen di perangkat via Intent Android",
            category = "Sistem & File",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "open_file",
            parametersSchema = """{"path": "/sdcard/Download/dokumen.pdf", "mime_type": "*/*"}""",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "get_current_app",
            name = "Dapatkan Aplikasi Aktif",
            description = "Mengetahui aplikasi yang sedang terbuka di layar depan (Package Name & Activity)",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "get_current_app",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "list_apps",
            name = "Daftar Aplikasi Terinstal",
            description = "Mengambil daftar aplikasi terinstal pada perangkat",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "list_apps",
            parametersSchema = "{}",
            riskLevel = ToolRiskLevel.SAFE,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "kill_app",
            name = "Hentikan Paket Aplikasi",
            description = "Menghentikan paksa aplikasi latar belakang via am force-stop",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "kill_app",
            parametersSchema = """{"package_name": "com.example.app"}""",
            riskLevel = ToolRiskLevel.HIGH,
            isEnabled = true,
            isBuiltIn = true
        )
    )

    private val _tools = MutableStateFlow<List<CustomTool>>(builtInTools)
    val tools: StateFlow<List<CustomTool>> = _tools.asStateFlow()

    // Permission Mode State
    private val _permissionMode = MutableStateFlow(AiPermissionMode.FULL_ACCESS)
    val permissionMode: StateFlow<AiPermissionMode> = _permissionMode.asStateFlow()

    private val _customPermissions = MutableStateFlow(CustomPermissionSettings())
    val customPermissions: StateFlow<CustomPermissionSettings> = _customPermissions.asStateFlow()

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCustomTools()
        loadPermissionSettings()
    }

    fun setPermissionMode(mode: AiPermissionMode) {
        _permissionMode.value = mode
        sharedPreferences?.edit()?.putString("ai_permission_mode", mode.name)?.apply()
    }

    fun updateCustomPermissions(settings: CustomPermissionSettings) {
        _customPermissions.value = settings
        sharedPreferences?.edit()?.apply {
            putBoolean("perm_read_screen", settings.allowReadScreen)
            putBoolean("perm_tap_swipe", settings.allowTapSwipe)
            putBoolean("perm_type_text", settings.allowTypeText)
            putBoolean("perm_open_app", settings.allowOpenApp)
            putBoolean("perm_shell", settings.allowShellCommands)
            putBoolean("perm_create_tool", settings.allowCreateTools)
            putBoolean("perm_system_keys", settings.allowSystemKeys)
            apply()
        }
    }

    private fun loadPermissionSettings() {
        val prefs = sharedPreferences ?: return
        val modeStr = prefs.getString("ai_permission_mode", AiPermissionMode.FULL_ACCESS.name)
        _permissionMode.value = try {
            AiPermissionMode.valueOf(modeStr ?: AiPermissionMode.FULL_ACCESS.name)
        } catch (_: Exception) {
            AiPermissionMode.FULL_ACCESS
        }

        _customPermissions.value = CustomPermissionSettings(
            allowReadScreen = prefs.getBoolean("perm_read_screen", true),
            allowTapSwipe = prefs.getBoolean("perm_tap_swipe", true),
            allowTypeText = prefs.getBoolean("perm_type_text", true),
            allowOpenApp = prefs.getBoolean("perm_open_app", true),
            allowShellCommands = prefs.getBoolean("perm_shell", true),
            allowCreateTools = prefs.getBoolean("perm_create_tool", true),
            allowSystemKeys = prefs.getBoolean("perm_system_keys", true)
        )
    }

    private fun loadCustomTools() {
        val prefs = sharedPreferences ?: return
        val jsonStr = prefs.getString(KEY_TOOLS_JSON, null)
        val customList = mutableListOf<CustomTool>()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val jsonArr = JSONArray(jsonStr)
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    customList.add(
                        CustomTool(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            description = obj.optString("description"),
                            category = obj.optString("category", "Custom"),
                            scriptType = try {
                                ToolScriptType.valueOf(obj.optString("scriptType", "SHELL"))
                            } catch (_: Exception) {
                                ToolScriptType.SHELL
                            },
                            command = obj.optString("command"),
                            parametersSchema = obj.optString("parametersSchema", "{}"),
                            riskLevel = try {
                                ToolRiskLevel.valueOf(obj.optString("riskLevel", "LOW"))
                            } catch (_: Exception) {
                                ToolRiskLevel.LOW
                            },
                            isEnabled = obj.optBoolean("isEnabled", true),
                            isBuiltIn = false,
                            createdByAi = obj.optBoolean("createdByAi", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse saved custom tools", e)
            }
        }

        _tools.value = builtInTools + customList
    }

    private fun saveCustomTools() {
        val prefs = sharedPreferences ?: return
        val customList = _tools.value.filter { !it.isBuiltIn }
        val jsonArr = JSONArray()
        for (tool in customList) {
            val obj = JSONObject().apply {
                put("id", tool.id)
                put("name", tool.name)
                put("description", tool.description)
                put("category", tool.category)
                put("scriptType", tool.scriptType.name)
                put("command", tool.command)
                put("parametersSchema", tool.parametersSchema)
                put("riskLevel", tool.riskLevel.name)
                put("isEnabled", tool.isEnabled)
                put("createdByAi", tool.createdByAi)
                put("createdAt", tool.createdAt)
            }
            jsonArr.put(obj)
        }
        prefs.edit().putString(KEY_TOOLS_JSON, jsonArr.toString()).apply()
    }

    fun addOrUpdateTool(tool: CustomTool) {
        val current = _tools.value.toMutableList()
        val index = current.indexOfFirst { it.id == tool.id }
        if (index != -1) {
            val existing = current[index]
            if (existing.isBuiltIn) {
                current[index] = existing.copy(isEnabled = tool.isEnabled)
            } else {
                current[index] = tool
            }
        } else {
            current.add(tool)
        }
        _tools.value = current
        saveCustomTools()
    }

    fun deleteTool(toolId: String): Boolean {
        val current = _tools.value.toMutableList()
        val target = current.firstOrNull { it.id == toolId } ?: return false
        if (target.isBuiltIn) {
            return false
        }
        current.removeAll { it.id == toolId }
        _tools.value = current
        saveCustomTools()
        return true
    }

    fun toggleTool(toolId: String) {
        val current = _tools.value.toMutableList()
        val index = current.indexOfFirst { it.id == toolId }
        if (index != -1) {
            val item = current[index]
            current[index] = item.copy(isEnabled = !item.isEnabled)
            _tools.value = current
            saveCustomTools()
        }
    }

    fun getTool(toolId: String): CustomTool? {
        return _tools.value.firstOrNull { it.id.equals(toolId, ignoreCase = true) }
    }

    fun isToolEnabled(toolId: String): Boolean {
        val tool = getTool(toolId) ?: return false
        return tool.isEnabled
    }

    private val _pendingDeletionRequest = MutableStateFlow<DeletionRequest?>(null)
    val pendingDeletionRequest: StateFlow<DeletionRequest?> = _pendingDeletionRequest.asStateFlow()

    fun requestDeletionApproval(title: String, details: String, onConfirm: () -> Unit, onDeny: () -> Unit) {
        if (_permissionMode.value == AiPermissionMode.FULL_ACCESS) {
            onConfirm()
        } else {
            _pendingDeletionRequest.value = DeletionRequest(
                title = title,
                details = details,
                onConfirm = {
                    _pendingDeletionRequest.value = null
                    onConfirm()
                },
                onDeny = {
                    _pendingDeletionRequest.value = null
                    onDeny()
                }
            )
        }
    }

    fun confirmPendingDeletion() {
        val req = _pendingDeletionRequest.value ?: return
        _pendingDeletionRequest.value = null
        req.onConfirm()
    }

    fun denyPendingDeletion() {
        val req = _pendingDeletionRequest.value ?: return
        _pendingDeletionRequest.value = null
        req.onDeny()
    }

    fun isDeletionAction(toolName: String, command: String, params: JSONObject): Boolean {
        val nameLower = toolName.lowercase()
        val cmdLower = command.lowercase()
        val actParam = params.optString("action", params.optString("act", "")).lowercase()

        return nameLower.contains("delete") || nameLower.contains("remove") || nameLower.contains("hapus") ||
               cmdLower.contains("rm ") || cmdLower.contains("rm -rf") || cmdLower.contains("delete") ||
               cmdLower.contains("unlink") || actParam in listOf("delete", "remove", "hapus", "unlink", "clear")
    }

    /**
     * Checks if executing this action is allowed under the current AI Permission Mode.
     */
    fun checkPermission(toolName: String, riskLevel: ToolRiskLevel = ToolRiskLevel.LOW): Pair<Boolean, String?> {
        val mode = _permissionMode.value
        val lower = toolName.lowercase()

        when (mode) {
            AiPermissionMode.SANDBOXED -> {
                val isReadOnly = lower in listOf("read_screen", "battery", "get_telemetry", "list_tools", "get_storage", "cek_ram", "clipboard_read")
                if (!isReadOnly) {
                    return Pair(false, "Aksi '$toolName' diblokir oleh AI Permission Mode: SANDBOXED (Hanya baca yang diizinkan).")
                }
                return Pair(true, null)
            }

            AiPermissionMode.LOW_RISK, AiPermissionMode.FULL_ACCESS -> {
                return Pair(true, null)
            }

            AiPermissionMode.CUSTOM -> {
                val custom = _customPermissions.value
                when (lower) {
                    "read_screen", "screenshot" -> if (!custom.allowReadScreen) return Pair(false, "Izin Baca Layar dinonaktifkan di setelan kustom.")
                    "tap", "swipe" -> if (!custom.allowTapSwipe) return Pair(false, "Izin Tap & Swipe dinonaktifkan di setelan kustom.")
                    "type_text", "send_text" -> if (!custom.allowTypeText) return Pair(false, "Izin Ketik Teks dinonaktifkan di setelan kustom.")
                    "open_app" -> if (!custom.allowOpenApp) return Pair(false, "Izin Buka Aplikasi dinonaktifkan di setelan kustom.")
                    "shell", "termux_command" -> if (!custom.allowShellCommands) return Pair(false, "Izin Shell Command dinonaktifkan di setelan kustom.")
                    "create_tool", "create_python_tool" -> if (!custom.allowCreateTools) return Pair(false, "Izin AI Membuat Tool dinonaktifkan di setelan kustom.")
                    "press_key" -> if (!custom.allowSystemKeys) return Pair(false, "Izin Tombol Navigasi dinonaktifkan di setelan kustom.")
                }
                return Pair(true, null)
            }
        }
    }

    /**
     * AI Dynamic Tool Creation: registers a new tool directly generated by AI assistant or user.
     */
    fun registerAiGeneratedTool(
        name: String,
        description: String,
        scriptTypeStr: String,
        command: String,
        paramsSchema: String = "{}"
    ): CustomTool {
        val cleanId = "tool_" + name.lowercase().replace(Regex("[^a-z0-9_]"), "_") + "_" + System.currentTimeMillis() % 10000
        val scriptType = try {
            ToolScriptType.valueOf(scriptTypeStr.uppercase())
        } catch (_: Exception) {
            ToolScriptType.SHELL
        }

        val newTool = CustomTool(
            id = cleanId,
            name = name,
            description = description,
            category = "AI Generated",
            scriptType = scriptType,
            command = command,
            parametersSchema = paramsSchema.ifBlank { "{}" },
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = false,
            createdByAi = true,
            createdAt = System.currentTimeMillis()
        )
        addOrUpdateTool(newTool)
        Log.i(TAG, "Registered new tool: ${newTool.id} ($name)")
        return newTool
    }

    /**
     * Executes custom tools directly on Android device with parameter interpolation.
     */
    suspend fun executeCustomTool(tool: CustomTool, params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        if (!tool.isEnabled) {
            return@withContext ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = "Tool '${tool.name}' sedang dalam status non-aktif."
            )
        }

        val (allowed, reason) = checkPermission(tool.id, tool.riskLevel)
        if (!allowed) {
            return@withContext ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = reason ?: "Permission denied"
            )
        }

        val context = JarvisApp.instance

        return@withContext when (tool.scriptType) {
            ToolScriptType.SHELL -> {
                var finalCommand = tool.command.trim()
                val keys = params.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = params.optString(key, "")
                    finalCommand = finalCommand.replace("{$key}", value)
                }

                // If command is generic or wrapped in {command}, check params
                if (finalCommand == "{command}" || finalCommand.isBlank()) {
                    finalCommand = params.optString("command", params.optString("cmd", ""))
                }

                // Save multi-line scripts to filesDir/scripts for proper execution
                if (finalCommand.contains("\n") || finalCommand.startsWith("#!")) {
                    val scriptsDir = File(context.filesDir, "scripts").apply { if (!exists()) mkdirs() }
                    val scriptExt = if (finalCommand.contains("python") || finalCommand.startsWith("#!/usr/bin/env python")) "py" else "sh"
                    val scriptFile = File(scriptsDir, "${tool.id}.$scriptExt")
                    scriptFile.writeText(finalCommand)
                    scriptFile.setExecutable(true)

                    val runnerCmd = if (scriptExt == "py") "python3 \"${scriptFile.absolutePath}\"" else "sh \"${scriptFile.absolutePath}\""
                    AdbShizukuManager.executeShell(runnerCmd, scriptsDir)
                } else {
                    AdbShizukuManager.executeShell(finalCommand)
                }
            }

            ToolScriptType.HTTP -> {
                executeHttpTool(tool, params)
            }

            ToolScriptType.INTENT -> {
                executeIntentTool(context, tool, params)
            }

            ToolScriptType.ACCESSIBILITY -> {
                val service = JarvisAccessibilityService.instance
                    ?: return@withContext ToolResult("error", message = "Accessibility Service belum aktif di Pengaturan Android.")

                val cmdLower = tool.command.lowercase().trim()
                if (cmdLower in listOf("screenshot", "take_screenshot", "screencap")) {
                    val (base64, err) = ScreenshotManager.captureBase64(context)
                    if (base64 != null) {
                        ToolResult(
                            status = "ok",
                            result = "🖼️ Tangkapan layar (screenshot) berhasil diambil dan dikirim langsung ke analisis visi AI Anda.",
                            extra = mapOf("screenshot_b64" to base64)
                        )
                    } else {
                        ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                    }
                } else if (cmdLower in listOf("read_screen", "get_ui_tree", "dump_ui")) {
                    val elements = service.getScreenElements()
                    val summary = elements.joinToString("\n") { el ->
                        "- [${el.id}] '${el.text.ifBlank { el.contentDescription }}' (${el.className}) @ (${el.bounds.centerX}, ${el.bounds.centerY})"
                    }
                    ToolResult("ok", result = "📱 Elemen UI di Layar saat ini (${elements.size} elemen):\n$summary")
                } else if (params.has("x") && params.has("y")) {
                    service.tapCoordinates(params.getDouble("x").toFloat(), params.getDouble("y").toFloat())
                } else if (params.has("text") || params.has("value")) {
                    val text = params.optString("text", params.optString("value", ""))
                    val elId = if (params.has("element_id") && !params.isNull("element_id")) params.getString("element_id") else null
                    service.typeText(elId, text)
                } else if (params.has("x1") && params.has("y1")) {
                    service.swipeCoordinates(
                        params.optDouble("x1", 500.0).toFloat(),
                        params.optDouble("y1", 1500.0).toFloat(),
                        params.optDouble("x2", 500.0).toFloat(),
                        params.optDouble("y2", 500.0).toFloat(),
                        params.optLong("duration_ms", 300L)
                    )
                } else if (tool.command.equals("open_app", ignoreCase = true) || params.has("package_name") || params.has("app")) {
                    val pkg = params.optString("package_name", params.optString("app", tool.command))
                    service.openApp(pkg)
                } else {
                    val targetId = params.optString("element_id", tool.command)
                    service.tapElement(targetId)
                }
            }

            ToolScriptType.CUSTOM_LOGIC -> {
                executeCustomLogic(context, tool.command, params)
            }
        }
    }

    private fun executeHttpTool(tool: CustomTool, params: JSONObject): ToolResult {
        var rawUrl = tool.command.trim()
        var method = "GET"

        if (rawUrl.startsWith("GET ", ignoreCase = true)) {
            method = "GET"
            rawUrl = rawUrl.substring(4).trim()
        } else if (rawUrl.startsWith("POST ", ignoreCase = true)) {
            method = "POST"
            rawUrl = rawUrl.substring(5).trim()
        }

        // Interpolate {param} placeholders in URL
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = params.optString(key, "")
            rawUrl = rawUrl.replace("{$key}", java.net.URLEncoder.encode(value, "UTF-8"))
        }

        if (params.has("url")) {
            rawUrl = params.getString("url")
        }
        if (params.has("method")) {
            method = params.getString("method").uppercase()
        }

        if (rawUrl.isBlank() || (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://"))) {
            return ToolResult("error", message = "URL HTTP tidak valid: '$rawUrl'")
        }

        return try {
            val reqBuilder = Request.Builder().url(rawUrl)
            if (method == "POST") {
                val bodyStr = params.optString("body", params.optString("json", "{}"))
                reqBuilder.post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            } else {
                reqBuilder.get()
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ToolResult("ok", result = "HTTP $code OK:\n$body")
            } else {
                ToolResult("error", message = "HTTP Error $code:\n$body")
            }
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal melakukan HTTP request ke '$rawUrl': ${e.message}")
        }
    }

    private fun executeIntentTool(context: Context, tool: CustomTool, params: JSONObject): ToolResult {
        return try {
            var raw = tool.command.trim()
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = params.optString(key, "")
                raw = raw.replace("{$key}", value)
            }

            val intent = when {
                raw.startsWith("am start", ignoreCase = true) -> {
                    // Execute via shell for full ADB am intent capability
                    return AdbShizukuManager.executeShell(raw)
                }
                raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> {
                    Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                }
                raw.startsWith("tel:", ignoreCase = true) -> {
                    Intent(Intent.ACTION_DIAL, Uri.parse(raw))
                }
                raw.startsWith("package:", ignoreCase = true) -> {
                    val pkgName = raw.removePrefix("package:").trim()
                    context.packageManager.getLaunchIntentForPackage(pkgName) ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkgName"))
                }
                raw.startsWith("android.settings", ignoreCase = true) -> {
                    Intent(raw)
                }
                else -> {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(raw)
                    launchIntent ?: Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolResult("ok", result = "Berhasil menjalankan Intent / membuka aksi: $raw")
        } catch (e: Exception) {
            ToolResult("error", message = "Gagal menjalankan Intent '${tool.command}': ${e.message}")
        }
    }

    suspend fun executeCustomLogic(context: Context, command: String, params: JSONObject): ToolResult {
        return when (command.lowercase().trim()) {
            "save_memory" -> {
                val category = params.optString("category", params.optString("title", "Catatan"))
                val content = params.optString("content", params.optString("text", ""))
                if (content.isBlank()) {
                    ToolResult("error", message = "Parameter 'content' wajib diisi untuk menyimpan memori.")
                } else {
                    val result = com.example.data.JarvisMemoryManager.saveMemory(category, content)
                    ToolResult("ok", result = result)
                }
            }

            "recall_memory" -> {
                val query = params.optString("query", params.optString("keyword", ""))
                val result = com.example.data.JarvisMemoryManager.recallMemory(query)
                ToolResult("ok", result = result)
            }

            "summarize_memory" -> {
                val result = com.example.data.JarvisMemoryManager.checkAndAutoArchiveMemory()
                ToolResult("ok", result = result)
            }

            "screenshot", "take_screenshot", "screencap" -> {
                val (base64, err) = ScreenshotManager.captureBase64(context)
                if (base64 != null) {
                    ToolResult(
                        status = "ok",
                        result = "🖼️ Screenshot tangkapan layar berhasil diambil dan dikirim langsung ke analisis visi AI Anda.",
                        extra = mapOf("screenshot_b64" to base64)
                    )
                } else {
                    ToolResult("error", message = err ?: "Gagal mengambil screenshot.")
                }
            }

            "open_file" -> {
                try {
                    val pathOrUri = params.optString("path", params.optString("uri", ""))
                    val uri = if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                        Uri.parse(pathOrUri)
                    } else {
                        val file = File(pathOrUri)
                        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, params.optString("mime_type", "*/*"))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult("ok", result = "📂 Berhasil membuka berkas: $pathOrUri")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membuka berkas: ${e.message}")
                }
            }

            "get_current_app" -> {
                val currentPkg = JarvisAccessibilityService.currentApp.value.ifBlank { "com.example" }
                ToolResult("ok", result = "📱 Aplikasi aktif saat ini: $currentPkg")
            }

            "list_apps" -> {
                try {
                    val pm = context.packageManager
                    val packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    val appList = packages.take(50).joinToString("\n") { app ->
                        "- ${pm.getApplicationLabel(app)} (${app.packageName})"
                    }
                    ToolResult("ok", result = "📦 Daftar Aplikasi Terinstal (50 teratas):\n$appList")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengambil daftar aplikasi: ${e.message}")
                }
            }

            "kill_app" -> {
                val pkg = params.optString("package_name", params.optString("package", ""))
                if (pkg.isBlank()) {
                    ToolResult("error", message = "Parameter 'package_name' wajib diisi.")
                } else {
                    val res = AdbShizukuManager.executeShell("am force-stop $pkg")
                    ToolResult("ok", result = "Menghentikan paket '$pkg':\n${res.result ?: res.message}")
                }
            }

            "get_storage" -> {
                try {
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val totalBytes = stat.blockCountLong * stat.blockSizeLong
                    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                    val usedBytes = totalBytes - freeBytes
                    val totalGb = String.format(java.util.Locale.US, "%.2f", totalBytes / (1024.0 * 1024.0 * 1024.0))
                    val usedGb = String.format(java.util.Locale.US, "%.2f", usedBytes / (1024.0 * 1024.0 * 1024.0))
                    val freeGb = String.format(java.util.Locale.US, "%.2f", freeBytes / (1024.0 * 1024.0 * 1024.0))
                    ToolResult("ok", result = "📊 Informasi Penyimpanan HP:\n- Total: $totalGb GB\n- Digunakan: $usedGb GB\n- Sisa: $freeGb GB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca storage: ${e.message}")
                }
            }

            "cek_ram" -> {
                try {
                    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    val totalMb = memInfo.totalMem / (1024 * 1024)
                    val availMb = memInfo.availMem / (1024 * 1024)
                    val usedMb = totalMb - availMb
                    val totalGb = String.format(java.util.Locale.US, "%.1f", totalMb / 1024.0)
                    ToolResult("ok", result = "⚡ Informasi RAM HP:\n- Total: $totalMb MB ($totalGb GB)\n- Tersedia: $availMb MB\n- Terpakai: $usedMb MB")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca RAM: ${e.message}")
                }
            }

            "battery" -> {
                try {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    } else false
                    val chargingStr = if (isCharging) "Sedang di-charge ⚡" else "Menggunakan baterai"
                    ToolResult("ok", result = "🔋 Status Baterai: $level% ($chargingStr)")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca baterai: ${e.message}")
                }
            }

            "clipboard_read" -> {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "(Clipboard kosong)"
                    ToolResult("ok", result = "📋 Isi Clipboard:\n$text")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal membaca clipboard: ${e.message}")
                }
            }

            "clipboard_write" -> {
                try {
                    val text = params.optString("text", params.optString("content", ""))
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
                    ToolResult("ok", result = "📋 Berhasil menyalin teks ke clipboard: \"$text\"")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal menulis clipboard: ${e.message}")
                }
            }

            "flashlight_toggle" -> {
                try {
                    val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = camManager.cameraIdList.firstOrNull() ?: return ToolResult("error", message = "Kamera/Senter tidak ditemukan")
                    val enable = params.optBoolean("enable", true)
                    camManager.setTorchMode(cameraId, enable)
                    ToolResult("ok", result = if (enable) "🔦 Senter dinyalakan" else "🔦 Senter dimatikan")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengatur senter: ${e.message}")
                }
            }

            "send_notification" -> {
                try {
                    val title = params.optString("title", "JARVIS-HP")
                    val message = params.optString("message", params.optString("text", "Notifikasi dari JARVIS"))
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val channelId = "jarvis_tools_channel"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel(channelId, "JARVIS Notifications", NotificationManager.IMPORTANCE_DEFAULT)
                        nm.createNotificationChannel(channel)
                    }
                    val notification = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(System.currentTimeMillis().toInt(), notification)
                    ToolResult("ok", result = "🔔 Notifikasi terkirim: \"$title\" - \"$message\"")
                } catch (e: Exception) {
                    ToolResult("error", message = "Gagal mengirim notifikasi: ${e.message}")
                }
            }

            "termux_service" -> {
                val action = params.optString("action", params.optString("act", "status"))
                val service = params.optString("service", params.optString("name", "jarvis_agent"))
                AdbShizukuManager.manageTermuxService(action, service)
            }

            "termux_api" -> {
                val command = params.optString("command", params.optString("api", params.optString("cmd", "battery")))
                val args = params.optString("args", params.optString("arguments", params.optString("options", "")))
                AdbShizukuManager.executeTermuxApi(command, args)
            }

            "termux_pkg" -> {
                val action = params.optString("action", "list")
                val pkgName = params.optString("package", params.optString("pkg", params.optString("name", "")))
                AdbShizukuManager.executeTermuxPkg(action, pkgName)
            }

            "termux_python" -> {
                val code = params.optString("code", params.optString("script", params.optString("py", "")))
                val filename = params.optString("filename", "script.py")
                AdbShizukuManager.executeTermuxPython(code, filename)
            }

            "termux_file" -> {
                val action = params.optString("action", "list")
                val path = params.optString("path", params.optString("file", "."))
                val content = params.optString("content", params.optString("text", ""))
                AdbShizukuManager.executeTermuxFile(action, path, content)
            }

            else -> {
                ToolResult("ok", result = "Perintah custom logic '$command' selesai dijalankan.")
            }
        }
    }

    /**
     * Export all custom tools to JSON string
     */
    fun exportToolsToJson(): String {
        val customList = _tools.value.filter { !it.isBuiltIn }
        val jsonArr = JSONArray()
        for (tool in customList) {
            val obj = JSONObject().apply {
                put("id", tool.id)
                put("name", tool.name)
                put("description", tool.description)
                put("category", tool.category)
                put("scriptType", tool.scriptType.name)
                put("command", tool.command)
                put("parametersSchema", tool.parametersSchema)
                put("riskLevel", tool.riskLevel.name)
                put("isEnabled", tool.isEnabled)
                put("createdByAi", tool.createdByAi)
            }
            jsonArr.put(obj)
        }
        return jsonArr.toString(2)
    }

    /**
     * Import tools from JSON string
     */
    fun importToolsFromJson(jsonString: String): Result<Int> {
        return try {
            val jsonArr = JSONArray(jsonString.trim())
            var count = 0
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val rawId = obj.optString("id").ifBlank { "custom_" + System.currentTimeMillis() + "_$i" }
                val tool = CustomTool(
                    id = rawId,
                    name = obj.optString("name", "Imported Tool"),
                    description = obj.optString("description", ""),
                    category = obj.optString("category", "Imported"),
                    scriptType = try {
                        ToolScriptType.valueOf(obj.optString("scriptType", "SHELL"))
                    } catch (_: Exception) {
                        ToolScriptType.SHELL
                    },
                    command = obj.optString("command", ""),
                    parametersSchema = obj.optString("parametersSchema", "{}"),
                    riskLevel = try {
                        ToolRiskLevel.valueOf(obj.optString("riskLevel", "LOW"))
                    } catch (_: Exception) {
                        ToolRiskLevel.LOW
                    },
                    isEnabled = obj.optBoolean("isEnabled", true),
                    isBuiltIn = false,
                    createdByAi = obj.optBoolean("createdByAi", false),
                    createdAt = System.currentTimeMillis()
                )
                addOrUpdateTool(tool)
                count++
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Ready-made Real Functional Templates for users & guide
    val toolTemplates = listOf(
        CustomTool(
            id = "template_shell_battery_temp",
            name = "Suhu Baterai (Dumpsys)",
            description = "Membaca temperatur baterai perangkat secara akurat via Android dumpsys",
            category = "Hardware & Status",
            scriptType = ToolScriptType.SHELL,
            command = "dumpsys battery | grep -i temperature",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_disk_free",
            name = "Cek Disk & Partisi (df -h)",
            description = "Mengecek penggunaan ruang penyimpanan semua partisi internal & SD card",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "df -h",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_screen_lock",
            name = "Kunci Layar (Power Key)",
            description = "Menekan tombol power virtual untuk mematikan/mengunci layar",
            category = "Sistem & Power",
            scriptType = ToolScriptType.SHELL,
            command = "input keyevent 26",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_shell_volume_control",
            name = "Atur Volume Media",
            description = "Menaikkan atau menurunkan level volume suara Android",
            category = "Multimedia",
            scriptType = ToolScriptType.SHELL,
            command = "media volume --show --set {level}",
            parametersSchema = """{"level": "10"}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_open_url_browser",
            name = "Buka Link Website",
            description = "Membuka link alamat website langsung di browser default",
            category = "Web & Internet",
            scriptType = ToolScriptType.INTENT,
            command = "https://google.com",
            parametersSchema = """{"url": "https://google.com"}""",
            riskLevel = ToolRiskLevel.SAFE
        ),
        CustomTool(
            id = "template_list_installed_apps",
            name = "Daftar Aplikasi Pihak Ketiga",
            description = "Melihat daftar package aplikasi pengguna yang terpasang di HP",
            category = "Aplikasi",
            scriptType = ToolScriptType.SHELL,
            command = "pm list packages -3",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_open_settings",
            name = "Buka Pengaturan Android",
            description = "Membuka menu utama Pengaturan (Settings) perangkat",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.INTENT,
            command = "android.settings.SETTINGS",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE
        ),
        CustomTool(
            id = "template_ping_test",
            name = "Tes Koneksi Ping Internet",
            description = "Mengirim 3 paket ping ke Google DNS untuk menguji latency internet",
            category = "Jaringan",
            scriptType = ToolScriptType.SHELL,
            command = "ping -c 3 8.8.8.8",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_http_public_ip",
            name = "Cek Alamat IP Publik",
            description = "Memeriksa IP publik perangkat menggunakan API ipify",
            category = "Web & API",
            scriptType = ToolScriptType.HTTP,
            command = "GET https://api.ipify.org?format=json",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE
        )
    )
}
