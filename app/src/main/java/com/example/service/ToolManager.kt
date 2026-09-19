package com.example.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages all built-in and user/AI-defined tools in JARVIS-HP.
 * Supports tool creation, editing, import/export, activation toggle, deletion, and execution.
 */
object ToolManager {

    private const val TAG = "ToolManager"
    private const val PREFS_NAME = "jarvis_custom_tools_prefs"
    private const val KEY_TOOLS_JSON = "custom_tools_list"

    private var sharedPreferences: SharedPreferences? = null

    // Built-in tools definition
    val builtInTools: List<CustomTool> = listOf(
        CustomTool(
            id = "open_app",
            name = "Buka Aplikasi",
            description = "Membuka aplikasi target berdasarkan package name atau nama umum (contoh: youtube, chrome, settings)",
            category = "Sistem & Navigasi",
            scriptType = ToolScriptType.ACCESSIBILITY,
            command = "open_app",
            parametersSchema = """{"package_name": "com.android.chrome"}""",
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
            description = "Menekan tombol navigasi sistem seperti BACK, HOME, atau RECENTS",
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
            description = "Membaca semua elemen UI yang tampil di layar beserta teks dan posisinya",
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
            description = "Memeriksa persentase baterai, voltase, status pengisian dan temperatur",
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
            description = "Mengeksekusi perintah shell Android via Termux/Shizuku/Runtime",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "shell",
            parametersSchema = """{"command": "pm list packages -3"}""",
            riskLevel = ToolRiskLevel.HIGH,
            isEnabled = true,
            isBuiltIn = true
        ),
        CustomTool(
            id = "termux_command",
            name = "Termux Terminal Command",
            description = "Mengeksekusi perintah terminal / shell langsung (contoh: ls, df -h, cat, top, dumpsys)",
            category = "Terminal & Shizuku",
            scriptType = ToolScriptType.SHELL,
            command = "termux_command",
            parametersSchema = """{"command": "df -h"}""",
            riskLevel = ToolRiskLevel.HIGH,
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
            description = "Mengecek penggunaan memori RAM perangkat (Total, Tersedia, dan Terpakai dalam MB)",
            category = "Sistem & Hardware",
            scriptType = ToolScriptType.CUSTOM_LOGIC,
            command = "cek_ram",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.SAFE,
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
            parametersSchema = """{"id": "id_tool", "name": "Nama Tool", "description": "Deskripsi", "script_type": "shell", "command": "perintah"}""",
            riskLevel = ToolRiskLevel.LOW,
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
            riskLevel = ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = true
        )
    )

    private val _tools = MutableStateFlow<List<CustomTool>>(builtInTools)
    val tools: StateFlow<List<CustomTool>> = _tools.asStateFlow()

    // Permission Mode State
    private val _permissionMode = MutableStateFlow(AiPermissionMode.LOW_RISK)
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
        val modeStr = prefs.getString("ai_permission_mode", AiPermissionMode.LOW_RISK.name)
        _permissionMode.value = try {
            AiPermissionMode.valueOf(modeStr ?: AiPermissionMode.LOW_RISK.name)
        } catch (_: Exception) {
            AiPermissionMode.LOW_RISK
        }

        _customPermissions.value = CustomPermissionSettings(
            allowReadScreen = prefs.getBoolean("perm_read_screen", true),
            allowTapSwipe = prefs.getBoolean("perm_tap_swipe", true),
            allowTypeText = prefs.getBoolean("perm_type_text", true),
            allowOpenApp = prefs.getBoolean("perm_open_app", true),
            allowShellCommands = prefs.getBoolean("perm_shell", false),
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
            // Can update custom tools, but built-in tools only update enabled state
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
            // Built-in tools cannot be deleted, only deactivated
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

    /**
     * Checks if executing this action is allowed under the current AI Permission Mode.
     */
    fun checkPermission(toolName: String, riskLevel: ToolRiskLevel = ToolRiskLevel.LOW): Pair<Boolean, String?> {
        val mode = _permissionMode.value
        val lower = toolName.lowercase()

        when (mode) {
            AiPermissionMode.SANDBOXED -> {
                // Read-only tools only
                val isReadOnly = lower == "read_screen" || lower == "battery" || lower == "get_telemetry" || lower == "list_tools"
                if (!isReadOnly) {
                    return Pair(false, "Aksi '$toolName' diblokir oleh AI Permission Mode: SANDBOXED (Hanya mode baca yang diizinkan).")
                }
                return Pair(true, null)
            }

            AiPermissionMode.LOW_RISK -> {
                // Shell execution or high risk blocked
                if (lower == "shell" || riskLevel == ToolRiskLevel.HIGH) {
                    return Pair(false, "Perintah shell/high-risk '$toolName' diblokir dalam mode LOW RISK ACCESS. Ubah ke mode FULL ACCESS jika Anda menyetujui.")
                }
                return Pair(true, null)
            }

            AiPermissionMode.FULL_ACCESS -> {
                return Pair(true, null)
            }

            AiPermissionMode.CUSTOM -> {
                val custom = _customPermissions.value
                when (lower) {
                    "read_screen", "screenshot" -> if (!custom.allowReadScreen) return Pair(false, "Izin Baca Layar dinonaktifkan di setelan kustom.")
                    "tap", "swipe" -> if (!custom.allowTapSwipe) return Pair(false, "Izin Tap & Swipe dinonaktifkan di setelan kustom.")
                    "type_text", "send_text" -> if (!custom.allowTypeText) return Pair(false, "Izin Ketik Teks dinonaktifkan di setelan kustom.")
                    "open_app" -> if (!custom.allowOpenApp) return Pair(false, "Izin Buka Aplikasi dinonaktifkan di setelan kustom.")
                    "shell" -> if (!custom.allowShellCommands) return Pair(false, "Izin Shell Command dinonaktifkan di setelan kustom.")
                    "create_tool" -> if (!custom.allowCreateTools) return Pair(false, "Izin AI Membuat Tool dinonaktifkan di setelan kustom.")
                    "press_key" -> if (!custom.allowSystemKeys) return Pair(false, "Izin Tombol Navigasi dinonaktifkan di setelan kustom.")
                }
                return Pair(true, null)
            }
        }
    }

    /**
     * AI Dynamic Tool Creation: registers a new tool directly generated by AI assistant.
     */
    fun registerAiGeneratedTool(
        name: String,
        description: String,
        scriptTypeStr: String,
        command: String,
        paramsSchema: String = "{}"
    ): CustomTool {
        val cleanId = "ai_" + name.lowercase().replace(Regex("[^a-z0-9_]"), "_") + "_" + System.currentTimeMillis() % 10000
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
            riskLevel = if (scriptType == ToolScriptType.SHELL) ToolRiskLevel.HIGH else ToolRiskLevel.LOW,
            isEnabled = true,
            isBuiltIn = false,
            createdByAi = true,
            createdAt = System.currentTimeMillis()
        )
        addOrUpdateTool(newTool)
        Log.i(TAG, "Registered new AI generated tool: ${newTool.id} ($name)")
        return newTool
    }

    /**
     * Executes custom tools by interpolating arguments.
     */
    suspend fun executeCustomTool(tool: CustomTool, params: JSONObject): ToolResult {
        if (!tool.isEnabled) {
            return ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = "Tool '${tool.name}' sedang dalam status non-aktif."
            )
        }

        val (allowed, reason) = checkPermission(tool.id, tool.riskLevel)
        if (!allowed) {
            return ToolResult(
                status = "error",
                errorCode = ErrorCodes.PERMISSION_DENIED,
                message = reason ?: "Permission denied"
            )
        }

        return when (tool.scriptType) {
            ToolScriptType.SHELL -> {
                var finalCommand = tool.command
                val keys = params.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = params.optString(key, "")
                    finalCommand = finalCommand.replace("{$key}", value)
                }
                AdbShizukuManager.executeShell(finalCommand)
            }
            ToolScriptType.ACCESSIBILITY -> {
                val service = JarvisAccessibilityService.instance
                    ?: return ToolResult("error", message = "Accessibility Service belum aktif")
                if (params.has("x") && params.has("y")) {
                    service.tapCoordinates(params.getDouble("x").toFloat(), params.getDouble("y").toFloat())
                } else if (params.has("text")) {
                    val elId = if (params.has("element_id") && !params.isNull("element_id")) params.getString("element_id") else null
                    service.typeText(elId, params.getString("text"))
                } else {
                    service.tapElement(params.optString("element_id", tool.command))
                }
            }
            ToolScriptType.INTENT -> {
                // Execute intent / deep link
                try {
                    val urlOrPkg = tool.command
                    val service = JarvisAccessibilityService.instance
                    if (service != null && urlOrPkg.isNotBlank()) {
                        service.openApp(urlOrPkg)
                    } else {
                        ToolResult("error", message = "Accessibility service required")
                    }
                } catch (e: Exception) {
                    ToolResult("error", message = "Intent execution failed: ${e.message}")
                }
            }
            ToolScriptType.CUSTOM_LOGIC, ToolScriptType.HTTP -> {
                ToolResult("ok", result = "Tool '${tool.name}' selesai dijalankan dengan parameter: $params")
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

    // Ready-made Templates for users & guide
    val toolTemplates = listOf(
        CustomTool(
            id = "template_shell_battery_temp",
            name = "Suhu Baterai (Shell)",
            description = "Membaca temperatur baterai perangkat secara akurat via dumpsys",
            category = "Hardware & Status",
            scriptType = ToolScriptType.SHELL,
            command = "dumpsys battery | grep -i temperature",
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
            name = "Atur Volume Suara",
            description = "Menaikkan atau menurunkan level volume media Android",
            category = "Multimedia",
            scriptType = ToolScriptType.SHELL,
            command = "media volume --show --set {level}",
            parametersSchema = """{"level": "10"}""",
            riskLevel = ToolRiskLevel.LOW
        ),
        CustomTool(
            id = "template_open_url_browser",
            name = "Buka URL di Browser",
            description = "Membuka link alamat website langsung di browser default",
            category = "Web & Internet",
            scriptType = ToolScriptType.SHELL,
            command = "am start -a android.intent.action.VIEW -d '{url}'",
            parametersSchema = """{"url": "https://google.com"}""",
            riskLevel = ToolRiskLevel.SAFE
        ),
        CustomTool(
            id = "template_list_installed_apps",
            name = "Daftar Aplikasi Pengguna",
            description = "Melihat daftar package aplikasi pihak ketiga yang terpasang di HP",
            category = "Aplikasi",
            scriptType = ToolScriptType.SHELL,
            command = "pm list packages -3",
            parametersSchema = """{}""",
            riskLevel = ToolRiskLevel.LOW
        )
    )
}
