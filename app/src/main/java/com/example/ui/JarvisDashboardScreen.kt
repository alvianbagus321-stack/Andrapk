package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ServerLogItem
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JarvisDashboardScreen(
    viewModel: JarvisViewModel,
    onRequestMediaProjection: () -> Unit
) {
    val context = LocalContext.current
    val isAccessConnected by viewModel.isAccessibilityConnected.collectAsState()
    val isMediaProjActive by viewModel.isMediaProjectionActive.collectAsState()
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val port by viewModel.port.collectAsState()
    val token by viewModel.token.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val logs by viewModel.serverLogs.collectAsState()

    var selectedLog by remember { mutableStateOf<ServerLogItem?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
    ) {
        // Status overview cards
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("status_overview_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SYSTEM ENGINE STATUS",
                            color = JarvisCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isServerRunning) JarvisEmerald else JarvisAmber)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isServerRunning) "RUNNING" else "STANDBY",
                                color = if (isServerRunning) JarvisEmerald else JarvisAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider(color = JarvisBorder.copy(alpha = 0.5f))

                    // Accessibility Service Status
                    ServiceRow(
                        title = "Accessibility Service",
                        subtitle = if (isAccessConnected) "Active • Window hierarchy & gesture engine" else "Disconnected • Tap to enable in Settings",
                        isActive = isAccessConnected,
                        actionLabel = if (isAccessConnected) "Ready" else "Enable",
                        onAction = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )

                    // Local HTTP Server
                    ServiceRow(
                        title = "Local HTTP Loopback",
                        subtitle = "127.0.0.1:$port (Strictly localhost loopback)",
                        isActive = isServerRunning,
                        actionLabel = if (isServerRunning) "Stop" else "Start",
                        onAction = { viewModel.toggleServer() }
                    )

                    // Screen Capture MediaProjection
                    ServiceRow(
                        title = "MediaProjection Screenshot",
                        subtitle = if (isMediaProjActive) "Screen capture session active" else "Optional (Fallback to API 30+ screenshot)",
                        isActive = isMediaProjActive,
                        actionLabel = if (isMediaProjActive) "Active" else "Grant",
                        onAction = onRequestMediaProjection
                    )

                    // Storage & Files Access
                    val isStorageGranted = com.example.service.AdbShizukuManager.isManageStorageGranted()
                    ServiceRow(
                        title = "Storage & File Access",
                        subtitle = if (isStorageGranted) "All files access granted" else "Full filesystem access required for Termux & scripts",
                        isActive = isStorageGranted,
                        actionLabel = if (isStorageGranted) "Granted" else "Grant",
                        onAction = {
                            com.example.service.AdbShizukuManager.requestAllFilesAccess(context)
                        }
                    )

                    // Shizuku & ADB Connector
                    val isShizukuInstalled = com.example.service.AdbShizukuManager.isShizukuInstalled(context)
                    ServiceRow(
                        title = "Shizuku ADB Connector",
                        subtitle = if (isShizukuInstalled) "Shizuku manager detected • Elevated shell ready" else "Shizuku not installed (Tap to open/install)",
                        isActive = isShizukuInstalled,
                        actionLabel = if (isShizukuInstalled) "Installed" else "Install",
                        onAction = {
                            com.example.service.AdbShizukuManager.openShizukuManager(context)
                        }
                    )
                }
            }
        }

        // Security & Local Token Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("token_credentials_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SECURITY CREDENTIALS (X-Local-Token)",
                            color = JarvisTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        IconButton(
                            onClick = {
                                val newToken = viewModel.regenerateToken()
                                Toast.makeText(context, "New token generated: $newToken", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate Token", tint = JarvisCyan)
                        }
                    }

                    Text(
                        text = "Komunikasi Termux ↔ Android diamankan dengan token ini. API key AI HANYA disimpan di Termux (config.py/env var), tidak pernah di APK.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    // Token display & copy row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisSurfaceVariant)
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("ACTIVE TOKEN", color = JarvisTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Text(token, color = JarvisCyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                copyToClipboard(context, "X-Local-Token", token)
                                Toast.makeText(context, "Token copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan.copy(alpha = 0.2f), contentColor = JarvisCyan),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Token", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp)
                        }
                    }

                    // Endpoint display
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisSurfaceVariant)
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("LOCAL ENDPOINT", color = JarvisTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Text("http://127.0.0.1:$port", color = JarvisTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                        Button(
                            onClick = {
                                copyToClipboard(context, "Endpoint", "http://127.0.0.1:$port")
                                Toast.makeText(context, "Endpoint copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisBorder, contentColor = JarvisTextPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Copy", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Telemetry Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryChip(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Battery",
                    value = if (telemetry.batteryLevel >= 0) "${telemetry.batteryLevel}%" else "N/A",
                    detail = if (telemetry.isCharging) "Charging" else "Discharging",
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    icon = Icons.Default.Wifi,
                    title = "Network",
                    value = if (telemetry.wifiConnected) "WiFi" else "Cellular",
                    detail = telemetry.wifiSsid.take(12),
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    icon = Icons.Default.Apps,
                    title = "Active App",
                    value = telemetry.currentAppPackage.substringAfterLast(".").take(10),
                    detail = telemetry.currentAppPackage.take(14),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Live Request Logs Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LIVE AGENT ACTIVITY STREAM",
                        color = JarvisTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = JarvisCyan.copy(alpha = 0.2f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${logs.size}",
                            color = JarvisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                if (logs.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearLogs() }) {
                        Text("Clear", color = JarvisTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        // Live Log Items
        if (logs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = JarvisSurfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = JarvisCyan.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Waiting for Termux Agent calls...", color = JarvisTextSecondary, fontSize = 13.sp)
                        Text("Requests to http://127.0.0.1:$port will appear here in real time.", color = JarvisTextSecondary.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        } else {
            items(logs, key = { it.id }) { logItem ->
                LogItemRow(logItem = logItem, onClick = { selectedLog = logItem })
            }
        }
    }

    // Details Dialog for Selected Log
    selectedLog?.let { log ->
        AlertDialog(
            onDismissRequest = { selectedLog = null },
            title = { Text("${log.method} ${log.path}", color = JarvisCyan, fontSize = 15.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status: ${log.statusCode}", color = if (log.isError) JarvisRed else JarvisEmerald, fontWeight = FontWeight.Bold)
                    Text("Client: ${log.clientIp}", color = JarvisTextSecondary, fontSize = 12.sp)
                    Text("Summary: ${log.summary}", color = JarvisTextPrimary, fontSize = 13.sp)
                    if (log.payloadPreview.isNotEmpty()) {
                        Text("Payload / Response:", color = JarvisTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Surface(
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = log.payloadPreview,
                                color = JarvisTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLog = null }) {
                    Text("Close", color = JarvisCyan)
                }
            },
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun ServiceRow(
    title: String,
    subtitle: String,
    isActive: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) JarvisEmerald else JarvisAmber)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(title, color = JarvisTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, color = JarvisTextSecondary, fontSize = 11.sp)
        }
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) JarvisEmerald.copy(alpha = 0.15f) else JarvisCyan.copy(alpha = 0.2f),
                contentColor = if (isActive) JarvisEmerald else JarvisCyan
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 36.dp)
        ) {
            Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TelemetryChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(title, color = JarvisTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = JarvisTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = JarvisTextSecondary, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
fun LogItemRow(logItem: ServerLogItem, onClick: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(logItem.timestamp) { timeFormat.format(Date(logItem.timestamp)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder.copy(alpha = 0.6f)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    color = if (logItem.isError) JarvisRed.copy(alpha = 0.2f) else JarvisCyan.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = logItem.method,
                        color = if (logItem.isError) JarvisRed else JarvisCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(logItem.path, color = JarvisTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(logItem.summary, color = JarvisTextSecondary, fontSize = 11.sp, maxLines = 1)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = if (logItem.statusCode == 200) JarvisEmerald.copy(alpha = 0.2f) else JarvisAmber.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${logItem.statusCode}",
                        color = if (logItem.statusCode == 200) JarvisEmerald else JarvisAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(timeStr, color = JarvisTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}
