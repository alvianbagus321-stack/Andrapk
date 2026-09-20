package com.example.server

import android.util.Log
import com.example.model.CustomTool
import com.example.model.ToolResult
import com.example.service.AiChatService
import com.example.service.ToolManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * McpServer — Model Context Protocol (MCP) endpoint over Streamable HTTP.
 *
 * Mengubah seluruh tools Andra Control menjadi MCP tools sehingga AI eksternal
 * (ChatGPT connectors, Claude, Cursor, Cline, agent MCP lainnya) dapat memanggil
 * tool di HP ini: buka aplikasi, tap/swipe, shell, termux, decode gambar, dll.
 *
 * Spesifikasi: JSON-RPC 2.0, methods: initialize, tools/list, tools/call, ping.
 * Transport: Streamable HTTP (POST /mcp, respons application/json single).
 * Stateless: tidak butuh session id — cocok untuk klien mana pun.
 */
object McpServer {

    private const val TAG = "McpServer"
    private const val SERVER_NAME = "andra-control"
    private const val SERVER_TITLE = "Andra Control — Android Automation"
    private const val SERVER_VERSION = "1.0.0"
    private const val LATEST_PROTOCOL_VERSION = "2025-06-18"

    private val SUPPORTED_PROTOCOL_VERSIONS = listOf(
        "2025-06-18",
        "2025-03-26",
        "2024-11-05"
    )

    /**
     * Menangani satu request MCP.
     * @return HTTP status code + body (null = 202 tanpa body, mis. notifikasi)
     */
    suspend fun handleRequest(rawBody: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        // 1. Parse JSON-RPC
        val request: JSONObject = try {
            JSONObject(rawBody)
        } catch (e: Exception) {
            Log.w(TAG, "MCP parse error: ${e.message}")
            return@withContext 400 to jsonRpcError(null, -32700, "Parse error: JSON tidak valid").toString()
        }

        val id = request.opt("id")
        val method = request.optString("method", "")

        // 2. Notifikasi (tanpa field id) → 202 Accepted, tanpa respons
        if (method.startsWith("notifications/", ignoreCase = true) || !request.has("id")) {
            return@withContext 202 to null
        }

        // 3. Dispatch method
        val resultJson: Any = try {
            when (method) {
                "initialize" -> handleInitialize(request.optJSONObject("params"))
                "ping" -> JSONObject()
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(request.optJSONObject("params"))
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> {
                    Log.i(TAG, "MCP method not found: $method")
                    return@withContext 200 to jsonRpcError(id, -32601, "Method tidak dikenal: $method").toString()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MCP internal error on $method", e)
            return@withContext 200 to jsonRpcError(id, -32603, "Internal error: ${e.message}").toString()
        }

        val response = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", resultJson)
        }
        200 to response.toString()
    }

    // ==========================================================
    // Method handlers
    // ==========================================================

    private fun handleInitialize(params: JSONObject?): JSONObject {
        val requested = params?.optString("protocolVersion", LATEST_PROTOCOL_VERSION) ?: LATEST_PROTOCOL_VERSION
        val negotiated = if (SUPPORTED_PROTOCOL_VERSIONS.contains(requested)) requested else LATEST_PROTOCOL_VERSION
        val clientName = params?.optJSONObject("clientInfo")?.optString("name", "unknown") ?: "unknown"
        Log.i(TAG, "MCP initialize dari '$clientName' — protocol $negotiated")

        return JSONObject().apply {
            put("protocolVersion", negotiated)
            put("capabilities", JSONObject().put("tools", JSONObject()))
            put("serverInfo", JSONObject().apply {
                put("name", SERVER_NAME)
                put("title", SERVER_TITLE)
                put("version", SERVER_VERSION)
            })
            put(
                "instructions",
                "Ini adalah MCP server Andra Control yang terhubung langsung ke smartphone Android pengguna. " +
                    "Gunakan tools/list untuk melihat semua tool otomasi Android yang tersedia (buka aplikasi, " +
                    "tap/swipe layar, ketik teks, shell, Termux, decode gambar, dsb). Eksekusi tool berlaku pada " +
                    "perangkat fisik pengguna — selalu konfirmasikan maksud pengguna sebelum aksi berisiko. " +
                    "Unggah 1 aksi per panggilan, amati hasilnya, lalu putuskan langkah berikutnya."
            )
        }
    }

    private fun handleToolsList(): JSONObject {
        val toolsJson = JSONArray()
        val registered = ToolManager.tools.value.associateBy { it.id.lowercase() }

        // Semua tool terdaftar (built-in + custom buatan pengguna/AI)
        for (tool in ToolManager.tools.value) {
            toolsJson.put(toolToMcp(tool))
        }
        // Meta-tools yang dieksekusi khusus oleh AiChatService (tambahkan bila belum terdaftar)
        for (meta in syntheticMetaTools()) {
            if (!registered.containsKey(meta.id.lowercase())) {
                toolsJson.put(toolToMcp(meta))
            }
        }
        return JSONObject().put("tools", toolsJson)
    }

    private suspend fun handleToolsCall(params: JSONObject?): JSONObject {
        if (params == null) throw IllegalArgumentException("params wajib ada untuk tools/call")
        val toolName = params.optString("name", "").trim()
        if (toolName.isEmpty()) throw IllegalArgumentException("Field 'name' wajib ada di params")

        val args = params.optJSONObject("arguments") ?: JSONObject()

        Log.i(TAG, "MCP tools/call: $toolName($args)")
        val result = AiChatService.executeActionLocally(toolName, args)

        val text = result.result ?: result.message
            ?: if (result.status == "ok") "Berhasil (OK)" else "Gagal"

        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", text.take(12000))
            }))
            put("isError", result.status != "ok")
        }
    }

    // ==========================================================
    // Mapping tool registry → MCP tool descriptor
    // ==========================================================

    private fun toolToMcp(tool: CustomTool): JSONObject {
        return JSONObject().apply {
            put("name", tool.id)
            put("description", buildString {
                append(tool.description)
                append(" [kategori: ${tool.category}, risiko: ${tool.riskLevel.name}")
                if (!tool.isEnabled) append(", NONAKTIF")
                append("]")
            })
            put("inputSchema", schemaToMcpInput(tool.parametersSchema))
        }
    }

    /**
     * Mengubah parametersSchema contoh aplikasi menjadi JSON Schema MCP yang valid.
     * Contoh: {"package_name": "com.android.chrome"} →
     *   {type:"object", properties:{package_name:{type:"string", description:"contoh: com.android.chrome"}}}
     */
    private fun schemaToMcpInput(schemaStr: String): JSONObject {
        val properties = JSONObject()
        try {
            val raw = JSONObject(schemaStr)
            val keys = raw.names() ?: return emptyObjectSchema()
            for (i in 0 until keys.length()) {
                val key = keys.optString(i)
                val value = raw.opt(key) ?: continue
                val prop = JSONObject()
                when (value) {
                    is Boolean -> {
                        prop.put("type", "boolean")
                        prop.put("description", "contoh: $value")
                    }
                    is Int, is Long, is Double -> {
                        prop.put("type", "number")
                        prop.put("description", "contoh: $value")
                    }
                    else -> {
                        prop.put("type", "string")
                        prop.put("description", "contoh: $value")
                    }
                }
                properties.put(key, prop)
            }
        } catch (_: Exception) {
            return emptyObjectSchema()
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("additionalProperties", true)
        }
    }

    private fun emptyObjectSchema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    /** Meta-tools khusus yang dieksekusi langsung oleh AiChatService. */
    private fun syntheticMetaTools(): List<CustomTool> = listOf(
        CustomTool(
            id = "create_tool",
            name = "Buat Tool Baru",
            description = "Membuat tool baru secara dinamis dan langsung aktif di registry perangkat",
            category = "Meta",
            scriptType = com.example.model.ToolScriptType.CUSTOM_LOGIC,
            command = "create_tool",
            parametersSchema = """{"name": "nama_tool", "description": "deskripsi", "script_type": "shell", "command": "perintah_shell", "parameters_schema": "{}"}""",
            riskLevel = com.example.model.ToolRiskLevel.LOW,
            isBuiltIn = true
        ),
        CustomTool(
            id = "create_python_tool",
            name = "Buat Tool Python",
            description = "Menyimpan script Python sebagai tool baru yang dapat dieksekusi di Termux",
            category = "Meta",
            scriptType = com.example.model.ToolScriptType.CUSTOM_LOGIC,
            command = "create_python_tool",
            parametersSchema = """{"filename": "tool.py", "tool_name": "nama", "description": "deskripsi", "code": "print('halo')"}""",
            riskLevel = com.example.model.ToolRiskLevel.LOW,
            isBuiltIn = true
        )
    )

    // ==========================================================
    // JSON-RPC helpers
    // ==========================================================

    private fun jsonRpcError(id: Any?, code: Int, message: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
    }
}
