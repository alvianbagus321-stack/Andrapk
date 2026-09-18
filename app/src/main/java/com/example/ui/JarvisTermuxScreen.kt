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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.termux.TermuxScripts
import com.example.ui.theme.*

@Composable
fun JarvisTermuxScreen(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val port by viewModel.port.collectAsState()
    val token by viewModel.token.collectAsState()

    var selectedScriptTab by remember { mutableIntStateOf(0) }
    val scriptTitles = listOf("agent.py", "config.py", "android_tools.py", "termux_tools.py", "memory.py", "setup.sh")

    val currentScriptCode = when (selectedScriptTab) {
        0 -> TermuxScripts.agentPy
        1 -> TermuxScripts.getConfigPy(token, port)
        2 -> TermuxScripts.androidToolsPy
        3 -> TermuxScripts.termuxToolsPy
        4 -> TermuxScripts.memoryPy
        5 -> TermuxScripts.getSetupScript(token, port)
        else -> ""
    }

    val setupCommand = "curl -s http://127.0.0.1:$port/setup.sh | bash"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
    ) {
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
                        Text("1-LINE TERMUX SETUP", color = JarvisCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }

                    Text(
                        text = "Jalankan perintah ini di Termux untuk mengunduh seluruh skrip, dependency python, database memory, dan konfigurasi otomatis:",
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
                        title = "Buka Termux & Jalankan Setup",
                        code = setupCommand,
                        onCopy = { copyToClipboard(context, "Step 1", setupCommand) }
                    )

                    StepItem(
                        number = "2",
                        title = "Set API Key di Termux (Aman & Tidak di APK)",
                        code = "export GEMINI_API_KEY=\"AIzaSy...\"",
                        onCopy = { copyToClipboard(context, "Step 2", "export GEMINI_API_KEY=\"\"") }
                    )

                    StepItem(
                        number = "3",
                        title = "Jalankan Agent dengan Instruksi Anda",
                        code = "cd ~/jarvis-hp && python agent.py \"Buka YouTube dan cari Minecraft\"",
                        onCopy = { copyToClipboard(context, "Step 3", "cd ~/jarvis-hp && python agent.py \"Buka YouTube dan cari Minecraft\"") }
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
                        Text("SOURCE CODE VIEWER", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                            .heightIn(min = 180.dp, max = 320.dp)
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
