package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.ServerLogItem
import com.example.service.TunnelManager
import com.example.ui.components.StatusPill
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

    val isHotwordEnabled by viewModel.isHotwordEnabled.collectAsState()
    val networkExposed by viewModel.networkExposed.collectAsState()
    val tunnelUrl by TunnelManager.tunnelUrl.collectAsState()
    val tunnelStatus by TunnelManager.tunnelStatus.collectAsState()
    val isTunnelStarting by TunnelManager.isTunnelStarting.collectAsState()
    val deviceIp by remember { mutableStateOf(TunnelManager.getDeviceIpAddress()) }
    val isHotwordListening by viewModel.isHotwordListeningActive.collectAsState()
    val isOverlayVisible by viewModel.isOverlayVisible.collectAsState()
    val aiConfig by viewModel.aiConfig.collectAsState()

    var showAiConfigDialog by remember { mutableStateOf(false) }
    var openAiConfigOnFreeTab by remember { mutableStateOf(false) }
    var openAiConfigPresetId by remember { mutableStateOf<String?>(null) }
    var showMcpHelpDialog by remember { mutableStateOf(false) }
    var selectedLog by remember { mutableStateOf<ServerLogItem?>(null) }

    // Status kuota model gratis aktif (ringan: refresh tiap 1 dtk hanya saat cooldown, 15 dtk saat normal)
    val quotaRevision by com.example.data.FreeQuotaManager.revision.collectAsState()
    val orHighTier by com.example.data.FreeQuotaManager.openRouterHighTier.collectAsState()
    val enforceLimit by com.example.data.FreeQuotaManager.enforceLocalLimit.collectAsState()
    var quotaTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val activeFreeQuota = remember(aiConfig.baseUrl, aiConfig.modelName, quotaRevision, orHighTier, enforceLimit, quotaTickMs) {
        com.example.data.FreeQuotaManager.getStatus(aiConfig.baseUrl, aiConfig.modelName, quotaTickMs)
    }
    LaunchedEffect(aiConfig.baseUrl, aiConfig.modelName, activeFreeQuota?.isBlocked) {
        if (activeFreeQuota != null) {
            val interval = if (activeFreeQuota.isBlocked) 1_000L else 15_000L
            while (true) {
                kotlinx.coroutines.delay(interval)
                quotaTickMs = System.currentTimeMillis()
            }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
                                        if (granted) {
                                            viewModel.setHotwordEnabled(context, true)
                                            Toast.makeText(context, "Asisten suara aktif di latar belakang!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Izin mikrofon dibutuhkan untuk asisten suara", Toast.LENGTH_LONG).show()
                                        }
    }

    val appSwitchColors = SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = JarvisCyan,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = JarvisTextSecondary,
        uncheckedTrackColor = JarvisSurfaceVariant,
        uncheckedBorderColor = JarvisBorderStrong
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 96.dp)
    ) {
        // Jendela Mengambang — toggle terpisah: HUD suara vs chat/task + status penyimpanan
        item {
            val voiceOverlayOn by viewModel.voiceOverlayEnabled.collectAsState()
            val taskOverlayOn by viewModel.taskOverlayEnabled.collectAsState()
            val storageOk = remember { com.example.data.PersistentStore.isExternalActive() }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("floating_window_card"),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DashboardIconBox(icon = Icons.Filled.WebAsset)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Jendela Mengambang", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)
                            Text("Atur visibilitas panel melayang di atas aplikasi lain", style = MaterialTheme.typography.bodySmall, color = JarvisTextSecondary)
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = JarvisBorder)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Indikator Suara", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = JarvisTextPrimary)
                            Text("Kapsul ringkas dan panel dengar yang dapat digeser", style = MaterialTheme.typography.bodySmall, color = JarvisTextSecondary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = voiceOverlayOn,
                            onCheckedChange = { viewModel.setVoiceOverlayEnabled(it) },
                            colors = appSwitchColors
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Panel Tugas & Balasan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = JarvisTextPrimary)
                            Text("Menampilkan progres eksekusi dan jawaban AI", style = MaterialTheme.typography.bodySmall, color = JarvisTextSecondary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = taskOverlayOn,
                            onCheckedChange = { viewModel.setTaskOverlayEnabled(it) },
                            colors = appSwitchColors
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = JarvisBorder)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (storageOk) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (storageOk) JarvisEmerald else JarvisAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (storageOk)
                                "Riwayat dan memori tersimpan permanen di ${com.example.data.PersistentStore.externalPath()}"
                            else
                                "Aktifkan akses penyimpanan agar riwayat chat tetap aman saat pembaruan aplikasi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        if (!storageOk) {
                            TextButton(onClick = {
                                com.example.service.AdbShizukuManager.requestAllFilesAccess(context)
                            }) { Text("Izinkan", color = JarvisCyan, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        // Informasi Versi Aplikasi
        item {
            val versionInfo = remember {
                runCatching {
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                               else @Suppress("DEPRECATION") pi.versionCode.toLong()
                    "v${pi.versionName} · build $code"
                }.getOrDefault("versi tidak terbaca")
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = JarvisSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Versi Aplikasi",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = versionInfo,
                        color = JarvisTextPrimary,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // LAPORAN CRASH — kalau ada fitur membuat app tertutup, salin & kirim ke developer
        item {
            var crashSummary by remember { mutableStateOf(com.example.data.CrashReporter.lastCrashSummary()) }
            if (crashSummary != null) {
                var copied by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("jarvis_crash", com.example.data.CrashReporter.fullLog()))
                            copied = true
                        },
                    colors = CardDefaults.cardColors(containerColor = JarvisRed.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = JarvisRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Ada laporan crash — TAP UNTUK SALIN",
                                style = MaterialTheme.typography.labelLarge,
                                color = JarvisRed,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "×",
                                color = JarvisTextSecondary,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        com.example.data.CrashReporter.clear()
                                        crashSummary = null
                                    }
                                    .padding(horizontal = 6.dp)
                            )
                        }
                        Text(
                            crashSummary ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (copied) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "✓ Log penuh tersalin — paste ke chat developer",
                                style = MaterialTheme.typography.bodySmall,
                                color = JarvisEmerald
                            )
                        }
                    }
                }
            }
        }

        // Quiz Analyzer — toggle overlay pemindai soal di layar
        item {
            val quizOn by viewModel.quizOverlayEnabled.collectAsState()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quiz_analyzer_card"),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DashboardIconBox(icon = Icons.Filled.Bolt)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Quiz Analyzer", style = MaterialTheme.typography.titleMedium, color = JarvisTextPrimary)
                        Text(
                            "Pindai dan analisis soal di layar dengan tangkapan layar serta OCR",
                            style = MaterialTheme.typography.bodySmall,
                            color = JarvisTextSecondary
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = quizOn,
                        colors = appSwitchColors,
                        onCheckedChange = {
                            val ok = viewModel.toggleQuizOverlay(context)
                            if (!ok) {
                                Toast.makeText(context, "Izin 'Tampil di atas aplikasi lain' dibutuhkan", Toast.LENGTH_LONG).show()
                                try {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: Exception) {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    if (quizOn) "Quiz Analyzer dinonaktifkan" else "Quiz Analyzer aktif",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }

        // Asisten Suara Latar Belakang
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("background_voice_assistant_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            DashboardIconBox(
                                icon = Icons.Default.RecordVoiceOver,
                                active = isHotwordEnabled
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Asisten Suara",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = JarvisTextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    StatusPill(
                                        text = if (isHotwordEnabled) "Aktif" else "Mati",
                                        active = isHotwordEnabled
                                    )
                                }
                                Text(
                                    text = "Deteksi kata aktivasi di latar belakang",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JarvisTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isHotwordEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    val hasAudio = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasAudio) {
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        viewModel.setHotwordEnabled(context, true)
                                        Toast.makeText(context, "Asisten suara aktif di latar belakang", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.setHotwordEnabled(context, false)
                                    Toast.makeText(context, "Asisten suara dinonaktifkan", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = appSwitchColors
                        )
                    }

                    HorizontalDivider(color = JarvisBorder)

                    // Description
                    Text(
                        text = if (isHotwordEnabled)
                            "Asisten mendengarkan kata aktivasi 'Jarvis [perintah]' di latar belakang tanpa perlu membuka aplikasi. Setiap respon dan tool otomatis ditampilkan melalui HUD melayang."
                        else
                            "Aktifkan sakelar di atas agar aplikasi mendeteksi kata aktivasi saat berjalan di latar belakang, seperti asisten bawaan perangkat.",
                        color = JarvisTextPrimary.copy(alpha = 0.85f),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )

                    // Overlay Status Check
                    val canDrawOverlay = JarvisOverlayManager.canDrawOverlay(context)
                    if (!canDrawOverlay) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = JarvisAmber.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAmber.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Izin Tampilkan di Atas Aplikasi Lain",
                                        color = JarvisAmber,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Diperlukan agar HUD asisten dapat muncul saat Anda membuka aplikasi lain.",
                                        color = JarvisTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = JarvisAmber, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Beri Izin", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (isHotwordEnabled) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = JarvisEmerald.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = JarvisEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "HUD melayang (overlay) siap & aktif di layar",
                                    color = JarvisEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Test Action & Battery Optimization Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!isHotwordEnabled) {
                                    viewModel.setHotwordEnabled(context, true)
                                }
                                viewModel.testTriggerHotword()
                                Toast.makeText(context, "Mendengarkan — ucapkan perintah Anda", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .weight(1.15f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorderStrong),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Uji Suara", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Buka Pengaturan Baterai untuk mengizinkan operasi latar belakang", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Optimasi Baterai", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }

        // Model & Endpoint AI
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_endpoint_config_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            DashboardIconBox(icon = Icons.Default.Tune)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Model & Endpoint",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JarvisTextPrimary
                                )
                                Text(
                                    text = aiConfig.providerLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JarvisTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                openAiConfigOnFreeTab = false
                                openAiConfigPresetId = null
                                showAiConfigDialog = true
                            },
                            modifier = Modifier.height(34.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorderStrong),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = JarvisTextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Atur API", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = JarvisSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Model", color = JarvisTextSecondary, fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = aiConfig.modelName,
                                    color = JarvisTextPrimary,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Endpoint", color = JarvisTextSecondary, fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = aiConfig.baseUrl.removePrefix("https://").removePrefix("http://"),
                                    color = JarvisTextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            activeFreeQuota?.let { fq ->
                                val qColor = when {
                                    fq.isBlocked -> JarvisRed
                                    fq.periodRemainingFraction <= 0.2f -> JarvisAmber
                                    else -> JarvisEmerald
                                }
                                HorizontalDivider(color = JarvisBorder)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (fq.isBlocked) {
                                            "Jeda kuota · reset ${com.example.data.FreeQuotaManager.formatCountdown(fq.activeWaitMs)}"
                                        } else {
                                            "Kuota ${fq.periodRemaining}/${fq.periodLimit} req"
                                        },
                                        color = qColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (fq.periodResetInMs > 0L) {
                                            "Reset ${com.example.data.FreeQuotaManager.formatCountdown(fq.periodResetInMs)}"
                                        } else {
                                            "${fq.minuteRemaining}/${fq.minuteLimit} /mnt"
                                        },
                                        color = JarvisTextSecondary,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(JarvisBackground)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fq.periodRemainingFraction)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(qColor)
                                    )
                                }
                            }
                        }
                    }

                    // Aksi Cepat: Preset Gratis & Portal Token Inception Labs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                openAiConfigOnFreeTab = true
                                openAiConfigPresetId = null
                                showAiConfigDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .testTag("dashboard_free_models_button"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorderStrong),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text("Model Gratis", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                openAiConfigOnFreeTab = true
                                openAiConfigPresetId = "inception_mercury_3"
                                showAiConfigDialog = true
                                openFreeApiPortalUrl(context, com.example.data.AiConfigManager.INCEPTION_LABS_LOGIN_URL)
                            },
                            modifier = Modifier
                                .weight(1.15f)
                                .height(36.dp)
                                .testTag("dashboard_inception_token_button"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisCyan,
                                contentColor = JarvisOnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Klaim Token AI", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
        // Layanan Sistem
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("status_overview_card"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Layanan Sistem",
                            style = MaterialTheme.typography.titleMedium,
                            color = JarvisTextPrimary
                        )
                        StatusPill(
                            text = if (isServerRunning) "Berjalan" else "Siaga",
                            active = isServerRunning
                        )
                    }

                    HorizontalDivider(color = JarvisBorder)

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
                        title = "Screen Share / Capture",
                        subtitle = if (isMediaProjActive) "Sesi screen share aktif • Siap untuk visi AI & tangkapan layar" else if (isAccessConnected) "Klik Aktifkan (atau otomatis fallback ke Aksesibilitas)" else "Izin tangkapan layar untuk inspeksi visual & AI",
                        isActive = isMediaProjActive,
                        actionLabel = if (isMediaProjActive) "Stop" else "Aktifkan",
                        onAction = {
                            if (isMediaProjActive) {
                                com.example.service.JarvisCompanionService.stopMediaProjection(context)
                                Toast.makeText(context, "Screen Share dinonaktifkan", Toast.LENGTH_SHORT).show()
                            } else {
                                onRequestMediaProjection()
                            }
                        }
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

                    // Shizuku & ADB Connector — status KONEKSI NYATA (binder + izin)
                    var shizukuStatus by remember { mutableStateOf(com.example.service.AdbShizukuManager.diagnose()) }
                    val isShizukuInstalled = com.example.service.AdbShizukuManager.isShizukuInstalled(context)
                    ServiceRow(
                        title = "Shizuku ADB Connector",
                        subtitle = if (isShizukuInstalled) shizukuStatus else "Shizuku belum terpasang (tap untuk buka/instal)",
                        isActive = shizukuStatus.startsWith("Terhubung"),
                        actionLabel = when {
                            !isShizukuInstalled -> "Install"
                            shizukuStatus.startsWith("Terhubung") -> "Cek ulang"
                            else -> "Sambungkan"
                        },
                        onAction = {
                            if (!isShizukuInstalled) {
                                com.example.service.AdbShizukuManager.openShizukuManager(context)
                            } else {
                                val ok = com.example.service.AdbShizukuManager.requestPermissionIfDenied()
                                shizukuStatus = if (ok) com.example.service.AdbShizukuManager.diagnose()
                                    else "Menunggu izin - pilih Izinkan di dialog Shizuku, lalu tap Cek ulang"
                            }
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
                            text = "KREDENSIAL LOKAL (X-Local-Token)",
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
                        text = "Komunikasi Termux ↔ Android diamankan dengan token ini. API key HANYA disimpan di Termux (config.py/env var), tidak pernah di APK.",
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

        // AI EKSTERNAL (MCP) CARD
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mcp_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            DashboardIconBox(icon = Icons.Default.Extension)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Integrasi MCP Eksternal",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = JarvisTextPrimary
                                )
                                Text(
                                    text = "Hubungkan Claude, ChatGPT, atau Cursor ke perangkat ini",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = JarvisTextSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusPill(
                            text = if (isServerRunning) "Siap" else "Mati",
                            active = isServerRunning
                        )
                        IconButton(
                            onClick = { showMcpHelpDialog = true },
                            modifier = Modifier.size(30.dp).testTag("mcp_help_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.HelpOutline,
                                contentDescription = "Panduan koneksi MCP",
                                tint = JarvisTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = JarvisBorder)

                    // Metode 1: Koneksi Langsung Lokal / LAN
                    Text(
                        text = "Koneksi Lokal & Jaringan WiFi",
                        color = JarvisTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Untuk AI/agent di HP ini atau perangkat lain di WiFi yang sama.",
                        color = JarvisTextSecondary,
                        fontSize = 10.sp
                    )

                    // Endpoint lokal (di HP ini)
                    EndpointRow(
                        label = "Endpoint (HP ini)",
                        url = "http://127.0.0.1:$port/mcp",
                        onCopy = {
                            copyToClipboard(context, "MCP Endpoint", "http://127.0.0.1:$port/mcp")
                            Toast.makeText(context, "Endpoint MCP dicopy!", Toast.LENGTH_SHORT).show()
                        }
                    )

                    // Endpoint LAN (perangkat lain)
                    if (deviceIp != null) {
                        EndpointRow(
                            label = "Endpoint (LAN / WiFi sama)",
                            url = "http://$deviceIp:$port/mcp",
                            onCopy = {
                                copyToClipboard(context, "MCP Endpoint LAN", "http://$deviceIp:$port/mcp")
                                Toast.makeText(context, "Endpoint LAN dicopy!", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    // Config JSON untuk klien MCP (Claude / Cursor / Cline, dll)
                    val mcpConfigJson = """
                        {
                          "mcpServers": {
                            "andra-control": {
                              "url": "http://127.0.0.1:$port/mcp",
                              "headers": { "X-Local-Token": "$token" }
                            }
                          }
                        }
                    """.trimIndent()

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                copyToClipboard(context, "MCP Config", mcpConfigJson)
                                Toast.makeText(context, "Config MCP dicopy — paste ke client AI kamu", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AuroraViolet.copy(alpha = 0.18f), contentColor = AuroraViolet),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AuroraViolet.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Salin Config MCP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(
                                    context,
                                    "Panduan MCP",
                                    "MODE A (Lokal): URL http://127.0.0.1:$port/mcp + header X-Local-Token: $token\n" +
                                        "MODE B (LAN): aktifkan Akses Jaringan, pakai URL http://$deviceIp:$port/mcp\n" +
                                        "MODE C (ChatGPT/Claude cloud): jalankan Tunnel (Metode 2) di kartu ini, daftarkan URL tunnel + /mcp sebagai connector di pengaturan AI, lalu tekan IZINKAN di halaman OAuth."
                                )
                                Toast.makeText(context, "Panduan koneksi dicopy!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Panduan", fontSize = 11.sp)
                        }
                    }

                    // Toggle ekspos jaringan (dibutuhkan Mode LAN)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Akses dari Jaringan (0.0.0.0)",
                                color = JarvisTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (networkExposed)
                                    "Server dapat dijangkau LAN / tunnel — MATIKAN bila tidak dipakai (aman = 127.0.0.1 saja)"
                                else
                                    "Aman: hanya aplikasi di HP ini (127.0.0.1) yang bisa mengakses",
                                color = JarvisTextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                        Switch(
                            checked = networkExposed,
                            onCheckedChange = { enable ->
                                viewModel.setNetworkExposed(enable)
                                Toast.makeText(
                                    context,
                                    if (enable) "Server dibuka ke jaringan (restart otomatis)" else "Server kembali lokal-only (aman)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JarvisBackground,
                                checkedTrackColor = JarvisAmber,
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisSurfaceHighlight
                            )
                        )
                    }

                    HorizontalDivider(color = JarvisBorder.copy(alpha = 0.4f))

                    // ================= METODE 2: TUNNEL HTTPS =================
                    Text(
                        text = "METODE 2 — TUNNEL HTTPS (CHATGPT / CLAUDE CLOUD)",
                        color = AuroraViolet,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Menjalankan tunnel (cloudflared) di Termux langsung dari sini. URL publik HTTPS muncul otomatis — tinggal daftarkan ke AI cloud.",
                        color = JarvisTextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )

                    if (tunnelUrl != null) {
                        EndpointRow(
                            label = "URL Tunnel (ChatGPT/Claude)",
                            url = TunnelManager.buildMcpTunnelUrl(tunnelUrl ?: ""),
                            urlColor = AuroraViolet,
                            onCopy = {
                                copyToClipboard(context, "MCP Tunnel URL", TunnelManager.buildMcpTunnelUrl(tunnelUrl ?: ""))
                                Toast.makeText(context, "URL tunnel dicopy — daftarkan di ChatGPT/Claude!", Toast.LENGTH_LONG).show()
                            }
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = JarvisSurfaceVariant.copy(alpha = 0.6f),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, JarvisBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isTunnelStarting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = AuroraViolet
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    tunnelStatus,
                                    color = JarvisTextSecondary,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tunnelUrl == null) {
                            Button(
                                onClick = {
                                    val res = TunnelManager.startTunnel(context, port)
                                    Toast.makeText(
                                        context,
                                        res.result ?: res.message ?: "Perintah tunnel terkirim",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isTunnelStarting,
                                colors = ButtonDefaults.buttonColors(containerColor = AuroraViolet, contentColor = Color(0xFF231433)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (isTunnelStarting) "Menjalankan…" else "Jalankan Tunnel",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(tunnelUrl ?: ""))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald.copy(alpha = 0.2f), contentColor = JarvisEmerald),
                                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Tunnel Aktif", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                TunnelManager.stopTunnel(context)
                                Toast.makeText(context, "Tunnel dihentikan", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(0.55f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Stop", fontSize = 11.sp)
                        }
                    }

                    Text(
                        text = "Butuh Termux (install otomatis di dalamnya saat pertama kali) + internet. Setiap start menghasilkan URL baru — daftarkan ulang bila URL berubah.",
                        color = JarvisTextSecondary.copy(alpha = 0.7f),
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp
                    )

                    Text(
                        text = "Protokol: MCP Streamable HTTP (JSON-RPC). Autentikasi: OAuth 2.0 / X-Local-Token / Authorization Bearer / ?token=",
                        color = JarvisTextSecondary.copy(alpha = 0.75f),
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp
                    )
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
                        text = "Log Permintaan",
                        style = MaterialTheme.typography.titleMedium,
                        color = JarvisTextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = JarvisSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${logs.size}",
                            color = JarvisTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
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
                        Text("Menunggu panggilan dari Termux Agent...", color = JarvisTextSecondary, fontSize = 13.sp)
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

    if (showMcpHelpDialog) {
        AlertDialog(
            onDismissRequest = { showMcpHelpDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = AuroraViolet, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Panduan Koneksi MCP", color = JarvisTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Hubungkan AI eksternal (ChatGPT, Claude, Cursor, dll) ke tools di HP ini via protokol MCP. Pilih mode sesuai letak AI-nya:",
                        color = JarvisTextPrimary.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    HelpStep("1", "Nyalakan Server", "Aktifkan sakelar di pojok kanan atas hingga status ONLINE / SIAP.")

                    HelpStep("2", "Mode A — AI di HP yang sama", "Pakai endpoint: http://127.0.0.1:8765/mcp dengan header X-Local-Token (tombol 'Salin Config MCP' di kartu ini). Cocok untuk agent Termux atau aplikasi di perangkat yang sama.")

                    HelpStep("3", "Mode B — Perangkat lain di WiFi yang sama", "Aktifkan 'Akses dari Jaringan' di kartu ini, lalu daftarkan URL http://<IP-HP>:8765/mcp di AI kamu. Cek IP HP di Pengaturan > Wi-Fi. Catatan: tambahkan http:// URL ini hanya untuk client yang mendukung HTTP lokal (bukan ChatGPT cloud).")

                    HelpStep("4", "Mode C — ChatGPT / Claude (CLOUD)", "Karena AI-nya di internet, HP perlu tunnel HTTPS. Cara termudah: tombol 'JALANKAN TUNNEL' di Metode 2 kartu ini — app menjalankan cloudflared via Termux dan URL publik muncul otomatis.\n\nManual (alternatif): di Termux jalankan 'curl -s http://127.0.0.1:8765/setup-mcp.sh | bash' — menginstall cloudflared + helper ~/mcp/tunnel.sh, lalu 'bash ~/mcp/tunnel.sh'.\n\nLalu di ChatGPT: Pengaturan > Connector > Tambah — tempel URL tunnel + '/mcp'. Saat menghubungkan akan muncul HALAMAN IZIN (OAuth) — tekan IZINKAN. Selesai!")

                    HelpStep("5", "Keamanan", "Token & OAuth adalah kunci masuk ke HP kamu. Matikan 'Akses dari Jaringan' saat tidak dipakai, dan segera regenerate token jika kecurigaan. Izin OAuth berlaku 24 jam lalu harus disetujui ulang.")

                    Text(
                        "Lisensi protokol: MCP Streamable HTTP + OAuth 2.0 (PKCE S256) + Dynamic Client Registration - kompatibel dengan spesifikasi resmi modelcontextprotocol.io",
                        color = JarvisTextSecondary.copy(alpha = 0.7f),
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMcpHelpDialog = false }) {
                    Text("Mengerti", color = JarvisCyan, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showAiConfigDialog) {
        AiConfigDialog(
            currentConfig = aiConfig,
            onSave = { apiKey, baseUrl, modelName, provider ->
                viewModel.updateAiConfig(apiKey, baseUrl, modelName, provider)
                openAiConfigPresetId = null
                showAiConfigDialog = false
            },
            onDismiss = {
                openAiConfigPresetId = null
                showAiConfigDialog = false
            },
            initialFilterFree = openAiConfigOnFreeTab,
            initialPresetId = openAiConfigPresetId
        )
    }
}

@Composable
private fun DashboardIconBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean = false
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(9.dp),
        color = if (active) JarvisCyan.copy(alpha = 0.14f) else JarvisSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) JarvisCyan.copy(alpha = 0.4f) else JarvisBorder
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) JarvisCyan else JarvisTextSecondary,
                modifier = Modifier.size(17.dp)
            )
        }
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
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isActive) JarvisEmerald else JarvisAmber)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(title, color = JarvisTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                color = JarvisTextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.5.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isActive) JarvisSurfaceVariant else JarvisCyan.copy(alpha = 0.14f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isActive) JarvisBorderStrong else JarvisCyan.copy(alpha = 0.4f)
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAction)
        ) {
            Text(
                text = actionLabel,
                color = if (isActive) JarvisTextPrimary else JarvisCyan,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder.copy(alpha = 0.85f)))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(JarvisCyan.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    title,
                    color = JarvisTextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                value,
                color = JarvisTextPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
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

@Composable
private fun EndpointRow(
    label: String,
    url: String,
    onCopy: () -> Unit,
    urlColor: Color = JarvisCyan
) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = JarvisTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(
                url,
                color = urlColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Button(
            onClick = onCopy,
            colors = ButtonDefaults.buttonColors(containerColor = urlColor.copy(alpha = 0.2f), contentColor = urlColor),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(6.dp)
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy", fontSize = 11.sp)
        }
    }
}

@Composable
private fun HelpStep(number: String, title: String, detail: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = JarvisSurfaceVariant.copy(alpha = 0.45f),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, JarvisBorder.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Surface(
                shape = CircleShape,
                color = AuroraViolet.copy(alpha = 0.18f),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number, color = AuroraViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    detail,
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}
