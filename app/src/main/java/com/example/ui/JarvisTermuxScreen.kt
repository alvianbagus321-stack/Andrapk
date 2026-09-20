package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.termux.TermuxScripts
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun JarvisTermuxScreen(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val port by viewModel.port.collectAsState()
    val token by viewModel.token.collectAsState()

    var selectedScriptTab by remember { mutableIntStateOf(0) }
    val scriptTitles = listOf(
        "INSTRUCTION.md",
        "tools/__init__.py",
        "cek_storage.py",
        "buka_youtube.py",
        "cek_ram.py",
        "agent.py",
        "android_tools.py",
        "termux_tools.py",
        "config.py",
        "setup.sh"
    )

    val currentScriptCode = when (selectedScriptTab) {
        0 -> TermuxScripts.instructionMd
        1 -> TermuxScripts.toolsInitPy
        2 -> TermuxScripts.customCekStoragePy
        3 -> TermuxScripts.customBukaYoutubePy
        4 -> TermuxScripts.customCekRamPy
        5 -> TermuxScripts.agentPy
        6 -> TermuxScripts.androidToolsPy
        7 -> TermuxScripts.termuxToolsPy
        8 -> TermuxScripts.getConfigPy(token, port)
        9 -> TermuxScripts.getSetupScript(token, port)
        else -> ""
    }

    val setupCommand = "curl -s http://127.0.0.1:$port/setup.sh | bash"
    val mcpSetupCommand = "curl -s http://127.0.0.1:$port/setup-mcp.sh | bash"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
    ) {
        // Quick 1-line setup banner
        item {
            var serviceOutput by remember { mutableStateOf<String?>(null) }
            var isRunningServiceOp by remember { mutableStateOf(false) }
            val coroutineScope = rememberCoroutineScope()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("termux_service_controller_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisTeal.copy(alpha = 0.5f)))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = JarvisTeal, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("TERMUX SERVICE & DAEMON CONTROL", color = JarvisTeal, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }

                        Surface(
                            color = if (com.example.service.AdbShizukuManager.isTermuxInstalled(context)) JarvisEmerald.copy(alpha = 0.2f) else JarvisAmber.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = if (com.example.service.AdbShizukuManager.isTermuxInstalled(context)) "Termux Terpasang" else "Termux Belum Terdeteksi",
                                color = if (com.example.service.AdbShizukuManager.isTermuxInstalled(context)) JarvisEmerald else JarvisAmber,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Asisten perangkat dapat mengontrol service background Termux, daemon Python, dan mengeksekusi utilitas Termux:API secara otonom.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )

                    // Action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isRunningServiceOp = true
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val res = com.example.service.AdbShizukuManager.manageTermuxService("status")
                                    serviceOutput = res.result ?: res.message
                                    isRunningServiceOp = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cek Status", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                isRunningServiceOp = true
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val res = com.example.service.AdbShizukuManager.manageTermuxService("start")
                                    serviceOutput = res.result ?: res.message
                                    isRunningServiceOp = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald, contentColor = JarvisBackground),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Start Daemon", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                com.example.service.AdbShizukuManager.openTermux(context)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTeal),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Buka Termux", fontSize = 11.sp)
                        }
                    }

                    // Service Output Box
                    if (serviceOutput != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp)),
                            color = JarvisBackground
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Termux Service Response:", color = JarvisCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    IconButton(
                                        onClick = { serviceOutput = null },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = JarvisTextSecondary, modifier = Modifier.size(12.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = serviceOutput ?: "",
                                    color = JarvisTextPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick 1-line setup banner
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("termux_quick_setup_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisCyan.copy(alpha = 0.5f)))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("1-LINE TERMUX SETUP & 3-LAYER TOOL REGISTRY", color = JarvisCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }

                    Text(
                        text = "Jalankan perintah ini di Termux untuk menginstal Agent lengkap beserta 3-Layer Tool Registry, auto-scanner tools/custom/, dan database memory:",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp)),
                        color = JarvisSurfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = setupCommand,
                                color = JarvisEmerald,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    copyToClipboard(context, "Setup Command", setupCommand)
                                    Toast.makeText(context, "Command copied!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // MCP Bridge Setup Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("termux_mcp_setup_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AuroraViolet.copy(alpha = 0.5f)))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = AuroraViolet, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("MCP SERVER & TUNNEL SETUP", color = AuroraViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }

                    Text(
                        text = "MCP server berjalan di dalam aplikasi ini (endpoint /mcp). Perintah berikut menyiapkan Termux sebagai jembatannya: izin otomasi, cloudflared (tunnel HTTPS untuk ChatGPT/Claude), helper scripts ~/mcp/, dan self-test koneksi:",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp)),
                        color = JarvisSurfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = mcpSetupCommand,
                                color = JarvisEmerald,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    copyToClipboard(context, "MCP Setup Command", mcpSetupCommand)
                                    Toast.makeText(context, "Command MCP setup copied!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AuroraViolet, contentColor = Color(0xFF231433)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = "Setelah selesai, tersedia di Termux:\n• bash ~/mcp/tunnel.sh → jalankan tunnel HTTPS\n• bash ~/mcp/stop.sh → hentikan tunnel\n• bash ~/mcp/status.sh → cek MCP server & URL tunnel",
                        color = JarvisTextSecondary.copy(alpha = 0.85f),
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // 3-Layer Architecture Overview Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = JarvisEmerald, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("3-LAYER TOOL REGISTRY ARCHITECTURE", color = JarvisEmerald, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "Aplikasi mengadopsi sistem modular sehingga kamu bisa memperluas kemampuan cukup dengan membuat file python di tools/custom/ tanpa mengedit agent.py.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )

                    // Visual 3 Layers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Layer 1", color = JarvisCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Android Tools", color = JarvisTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("tap, swipe, read_screen via Companion APK", color = JarvisTextSecondary, fontSize = 9.sp)
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Layer 2", color = JarvisEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Termux Tools", color = JarvisTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("shell, battery, clipboard via CLI & API", color = JarvisTextSecondary, fontSize = 9.sp)
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisTeal.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Layer 3", color = JarvisTeal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Custom Tools", color = JarvisTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("tools/custom/*.py ber-decorator @tool", color = JarvisTextSecondary, fontSize = 9.sp)
                            }
                        }
                    }

                    // How to add a new tool snippet
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisBackground)
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cara Membuat Tool Baru (@tool decorator):", color = JarvisCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "from tools import tool\n\n@tool(name=\"get_storage\", description=\"Mendapatkan kapasitas HP\")\ndef get_storage():\n    return shutil.disk_usage('/')",
                                color = JarvisEmerald,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                selectedScriptTab = 0 // jump to INSTRUCTION.md
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Buka INSTRUCTION.md", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                copyToClipboard(context, "Template Tool", TermuxScripts.customCekStoragePy)
                                Toast.makeText(context, "Template @tool disalin ke clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald, contentColor = JarvisBackground)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Salin Template", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Execution Steps Guide
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("PANDUAN EKSEKUSI AGENT", color = JarvisTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    StepItem(
                        number = "1",
                        title = "Jalankan 1-Line Setup di Termux",
                        code = setupCommand,
                        onCopy = { copyToClipboard(context, "Step 1", setupCommand) }
                    )

                    StepItem(
                        number = "2",
                        title = "Set API Key di Termux",
                        code = "export GEMINI_API_KEY=\"AIzaSy...\"",
                        onCopy = { copyToClipboard(context, "Step 2", "export GEMINI_API_KEY=\"\"") }
                    )

                    StepItem(
                        number = "3",
                        title = "Jalankan Agent (Mendeteksi Semua Tool Otomatis)",
                        code = "cd ~/jarvis-hp && python agent.py \"Cek kapasitas storage HP\"",
                        onCopy = { copyToClipboard(context, "Step 3", "cd ~/jarvis-hp && python agent.py \"Cek kapasitas storage HP\"") }
                    )

                    StepItem(
                        number = "4",
                        title = "Tambah Tool Custom Baru",
                        code = "nano ~/jarvis-hp/tools/custom/tool_baru.py",
                        onCopy = { copyToClipboard(context, "Step 4", "nano ~/jarvis-hp/tools/custom/tool_baru.py") }
                    )
                }
            }
        }

        // Code Viewer with Tabs
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("termux_code_viewer_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SOURCE CODE & SPEC VIEWER", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Button(
                            onClick = {
                                copyToClipboard(context, scriptTitles[selectedScriptTab], currentScriptCode)
                                Toast.makeText(context, "${scriptTitles[selectedScriptTab]} copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan.copy(alpha = 0.2f), contentColor = JarvisCyan),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy File", fontSize = 11.sp)
                        }
                    }

                    // Tab selector
                    ScrollableTabRow(
                        selectedTabIndex = selectedScriptTab,
                        containerColor = JarvisSurfaceVariant,
                        contentColor = JarvisCyan,
                        edgePadding = 8.dp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    ) {
                        scriptTitles.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedScriptTab == index,
                                onClick = { selectedScriptTab = index },
                                text = {
                                    Text(
                                        text = title,
                                        fontSize = 11.sp,
                                        fontWeight = if (selectedScriptTab == index) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    // Code content box
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp)),
                        color = JarvisBackground
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = currentScriptCode,
                                color = JarvisTextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StepItem(number: String, title: String, code: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = JarvisCyan.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "Langkah $number",
                    color = JarvisCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(title, color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Surface(
            color = JarvisSurfaceVariant,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(code, color = JarvisEmerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = JarvisTextSecondary, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}
