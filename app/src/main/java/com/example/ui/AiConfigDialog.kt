package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.AiConfig
import com.example.data.AiConfigManager
import com.example.data.AiPreset
import com.example.data.ConnectionTestResult
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AiConfigDialog(
    currentConfig: AiConfig,
    onSave: (apiKey: String, baseUrl: String, modelName: String, provider: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var modelName by remember { mutableStateOf(currentConfig.modelName) }
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    var isKeyVisible by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .testTag("ai_config_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xF20B132B),
            border = BorderStroke(1.2.dp, Brush.horizontalGradient(listOf(JarvisCyan.copy(alpha = 0.8f), JarvisTeal.copy(alpha = 0.5f))))
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Glowing Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = JarvisCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, JarvisCyan)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Konfigurasi Model & Endpoint AI",
                                color = JarvisTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Gunakan Gemini, Groq, OpenRouter, DeepSeek, atau Lokal",
                                color = JarvisTextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = JarvisTextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                // Preset Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "PILIH PRESET CEPAT:",
                        color = JarvisCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AiConfigManager.PRESETS) { preset ->
                            val isSelected = (baseUrl == preset.baseUrl && modelName == preset.defaultModel) || (selectedPresetId == preset.id)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else JarvisSurfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) JarvisCyan else JarvisBorder
                                ),
                                modifier = Modifier
                                    .clickable {
                                        selectedPresetId = preset.id
                                        baseUrl = preset.baseUrl
                                        modelName = preset.defaultModel
                                        testResult = null
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = preset.displayName,
                                        color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        text = preset.providerName,
                                        color = JarvisTextSecondary,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // API Key Field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "API KEY:",
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            testResult = null
                        },
                        placeholder = { Text("Masukkan API key Anda", color = JarvisTextSecondary.copy(alpha = 0.5f), fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_api_key"),
                        singleLine = true,
                        visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                                Icon(
                                    imageVector = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Key",
                                    tint = JarvisTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary,
                            focusedContainerColor = Color(0x30080D1A),
                            unfocusedContainerColor = Color(0x30080D1A)
                        )
                    )
                    Text(
                        text = "💡 Jika dikosongkan untuk Gemini, akan menggunakan kunci bawaan dari AI Studio Secrets.",
                        color = JarvisTextSecondary,
                        fontSize = 9.5.sp
                    )
                }

                // Base URL / Endpoint Field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "BASE URL / API ENDPOINT:",
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            testResult = null
                        },
                        placeholder = { Text("https://generativelanguage.googleapis.com", color = JarvisTextSecondary.copy(alpha = 0.5f), fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_base_url"),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary,
                            focusedContainerColor = Color(0x30080D1A),
                            unfocusedContainerColor = Color(0x30080D1A)
                        )
                    )
                }

                // Model Name Field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "MODEL NAME:",
                        color = JarvisTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = {
                            modelName = it
                            testResult = null
                        },
                        placeholder = { Text("gemini-3.5-flash / deepseek-chat", color = JarvisTextSecondary.copy(alpha = 0.5f), fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_model_name"),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary,
                            focusedContainerColor = Color(0x30080D1A),
                            unfocusedContainerColor = Color(0x30080D1A)
                        )
                    )
                }

                // Connection Test Button & Status Banner
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isTestingConnection = true
                                testResult = null
                                val res = AiConfigManager.testEndpointConnection(
                                    apiKey = apiKey,
                                    baseUrl = baseUrl,
                                    modelName = modelName
                                )
                                testResult = res
                                isTestingConnection = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_test_connection"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (isTestingConnection) JarvisAmber else JarvisCyan.copy(alpha = 0.7f)),
                        enabled = !isTestingConnection
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = JarvisAmber, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Menguji Koneksi...", color = JarvisAmber, fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Uji Koneksi Endpoint (Ping Test)", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    AnimatedVisibility(visible = testResult != null) {
                        testResult?.let { res ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (res.success) JarvisEmerald.copy(alpha = 0.15f) else JarvisRed.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (res.success) JarvisEmerald else JarvisRed),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (res.success) JarvisEmerald else JarvisRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (res.success) "KONEKSI BERHASIL (${res.latencyMs} ms)" else "KONEKSI GAGAL",
                                            color = if (res.success) JarvisEmerald else JarvisRed,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = res.message,
                                            color = JarvisTextPrimary,
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Action Buttons: Reset Defaults & Save
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            apiKey = ""
                            baseUrl = AiConfigManager.DEFAULT_GEMINI_BASE_URL
                            modelName = AiConfigManager.DEFAULT_GEMINI_MODEL
                            selectedPresetId = "gemini_flash"
                            testResult = null
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, JarvisBorder)
                    ) {
                        Text("Reset Default", color = JarvisTextSecondary, fontSize = 11.sp)
                    }

                    Button(
                        onClick = {
                            onSave(apiKey, baseUrl, modelName, null)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("btn_save_ai_config"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color(0xFF040714))
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Simpan Konfigurasi", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
