package com.example.model

/**
 * Data model representing an interactive or visible UI element inspected from the screen.
 */
data class UiElementInfo(
    val id: String,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val className: String,
    val bounds: RectBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val packageName: String
)

data class RectBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val width: Int,
    val height: Int,
    val centerX: Int,
    val centerY: Int
)

/**
 * Standardized error codes conforming to §7 of JARVIS-HP specification.
 */
object ErrorCodes {
    const val ELEMENT_NOT_FOUND = "ELEMENT_NOT_FOUND"
    const val SERVICE_NOT_CONNECTED = "SERVICE_NOT_CONNECTED"
    const val TIMEOUT = "TIMEOUT"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val APP_NOT_FOUND = "APP_NOT_FOUND"
    const val INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val GESTURE_FAILED = "GESTURE_FAILED"
    const val UNKNOWN_ERROR = "UNKNOWN_ERROR"
}

/**
 * Standard tool result representation.
 */
data class ToolResult(
    val status: String, // "ok" or "error"
    val result: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
    val extra: Map<String, Any?> = emptyMap()
)

/**
 * Represents an entry in the live HTTP server activity log.
 */
data class ServerLogItem(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val path: String,
    val statusCode: Int,
    val clientIp: String,
    val summary: String,
    val isError: Boolean = false,
    val payloadPreview: String = ""
)

/**
 * System telemetry state.
 */
data class SystemTelemetry(
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val wifiConnected: Boolean = false,
    val wifiSsid: String = "Disconnected",
    val currentAppPackage: String = "Unknown",
    val currentAppTitle: String = ""
)

enum class ChatSender {
    USER, AI, SYSTEM
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: ChatSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isExecutingAction: Boolean = false,
    val actionToolName: String? = null,
    val actionResult: String? = null,
    val thinkingProcess: String? = null
)

data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "Sesi Baru",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
)

enum class ToolScriptType {
    SHELL,
    ACCESSIBILITY,
    INTENT,
    HTTP,
    CUSTOM_LOGIC
}

enum class ToolRiskLevel {
    SAFE,
    LOW,
    HIGH
}

data class CustomTool(
    val id: String,
    val name: String,
    val description: String,
    val category: String = "Custom",
    val scriptType: ToolScriptType = ToolScriptType.SHELL,
    val command: String = "",
    val parametersSchema: String = "{}",
    val riskLevel: ToolRiskLevel = ToolRiskLevel.LOW,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val createdByAi: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

enum class AiPermissionMode {
    SANDBOXED,
    LOW_RISK,
    FULL_ACCESS,
    CUSTOM
}

data class CustomPermissionSettings(
    val allowReadScreen: Boolean = true,
    val allowTapSwipe: Boolean = true,
    val allowTypeText: Boolean = true,
    val allowOpenApp: Boolean = true,
    val allowShellCommands: Boolean = false,
    val allowCreateTools: Boolean = true,
    val allowSystemKeys: Boolean = true
)
