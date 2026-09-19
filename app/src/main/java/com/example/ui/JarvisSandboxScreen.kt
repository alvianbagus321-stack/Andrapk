package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.UiElementInfo
import com.example.ui.theme.*

@Composable
fun JarvisSandboxScreen(viewModel: JarvisViewModel) {
    val inspectedElements by viewModel.inspectedElements.collectAsState()
    val lastResult by viewModel.lastActionResult.collectAsState()
    val isExecuting by viewModel.isActionExecuting.collectAsState()

    var tapX by remember { mutableStateOf("540") }
    var tapY by remember { mutableStateOf("1200") }
    var targetElementId by remember { mutableStateOf("") }

    var textInput by remember { mutableStateOf("Minecraft") }
    var swipeStartX by remember { mutableStateOf("500") }
    var swipeStartY by remember { mutableStateOf("1500") }
    var swipeEndX by remember { mutableStateOf("500") }
    var swipeEndY by remember { mutableStateOf("500") }

    var openAppPackage by remember { mutableStateOf("com.google.android.youtube") }
    var shellCommand by remember { mutableStateOf("uname -a") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
    ) {
        // Live action result banner
        if (lastResult != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = JarvisSurfaceVariant),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisCyan.copy(alpha = 0.5f)))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("ACTION RESULT", color = JarvisCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(lastResult ?: "", color = JarvisTextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Section 1: Screen Inspector (read_screen)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sandbox_inspector_card"),
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
                        Column {
                            Text("1. UI TREE INSPECTOR (read_screen)", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Inspects nodes via AccessibilityService", color = JarvisTextSecondary, fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.inspectScreen() },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isExecuting
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Inspect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (inspectedElements.isNotEmpty()) {
                        Text(
                            text = "Found ${inspectedElements.size} interactive nodes. Tap any item below to target it:",
                            color = JarvisTextSecondary,
                            fontSize = 11.sp
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(JarvisSurfaceVariant)
                                .padding(6.dp)
                        ) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(inspectedElements) { elem ->
                                    ElementItemRow(
                                        element = elem,
                                        isSelected = targetElementId == elem.id,
                                        onSelect = {
                                            targetElementId = elem.id
                                            tapX = elem.bounds.centerX.toString()
                                            tapY = elem.bounds.centerY.toString()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Tap & Gesture Testing
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sandbox_tap_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("2. TAP & GESTURE DISPATCHER (tap / swipe)", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    // Tap Coordinates
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = tapX,
                            onValueChange = { tapX = it },
                            label = { Text("X") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = tapY,
                            onValueChange = { tapY = it },
                            label = { Text("Y") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val x = tapX.toFloatOrNull() ?: 540f
                                val y = tapY.toFloatOrNull() ?: 1200f
                                viewModel.testTapCoordinates(x, y)
                            },
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Tap (X,Y)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Tap by Element ID
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = targetElementId,
                            onValueChange = { targetElementId = it },
                            label = { Text("Target Element ID / ViewID") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (targetElementId.isNotBlank()) {
                                    viewModel.testTapElement(targetElementId)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisTeal, contentColor = JarvisBackground),
                            shape = RoundedCornerShape(8.dp),
                            enabled = targetElementId.isNotBlank()
                        ) {
                            Text("Tap ID", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Swipe test
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Swipe: (500, 1500) -> (500, 500)", color = JarvisTextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Button(
                            onClick = {
                                viewModel.testSwipe(500f, 1500f, 500f, 500f, 300L)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisSurfaceVariant, contentColor = JarvisCyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.SwipeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Swipe Up", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section 3: Text Input via ACTION_SET_TEXT
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sandbox_type_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3. TEXT INPUT via ACTION_SET_TEXT (type_text)", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Per §3 spesifikasi: Tidak memakai simulasi keyboard melainkan AccessibilityNodeInfo.ACTION_SET_TEXT.", color = JarvisTextSecondary, fontSize = 11.sp)

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text("Text string to set") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            viewModel.testTypeText(targetElementId.ifBlank { null }, textInput)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald, contentColor = JarvisBackground),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Set Text via ACTION_SET_TEXT", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section 4: System Keys & Navigation
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sandbox_keys_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("4. SYSTEM KEYS & GLOBAL ACTIONS (press_key)", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.testPressKey("HOME") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisSurfaceVariant, contentColor = JarvisTextPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("HOME", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewModel.testPressKey("BACK") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisSurfaceVariant, contentColor = JarvisTextPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("BACK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { viewModel.testPressKey("RECENTS") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisSurfaceVariant, contentColor = JarvisTextPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("RECENTS", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Section 5: App Open Testing
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sandbox_app_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("5. APPLICATION LAUNCHER (open_app)", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = openAppPackage,
                        onValueChange = { openAppPackage = it },
                        label = { Text("Package Name") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PresetAppChip("YouTube", "com.google.android.youtube") { openAppPackage = it }
                        PresetAppChip("Chrome", "com.android.chrome") { openAppPackage = it }
                        PresetAppChip("Settings", "com.android.settings") { openAppPackage = it }
                    }

                    Button(
                        onClick = { viewModel.testOpenApp(openAppPackage) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisTeal, contentColor = JarvisBackground),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Application", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section 6: ADB & System Shell Execution
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sandbox_shell_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("6. ADB / SYSTEM SHELL EXECUTION (/adb/shell)", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Eksekusi perintah shell lokal / Shizuku connector secara langsung:", color = JarvisTextSecondary, fontSize = 11.sp)

                    OutlinedTextField(
                        value = shellCommand,
                        onValueChange = { shellCommand = it },
                        label = { Text("Shell command (e.g. uname -a, pm list packages, ls -la /sdcard)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PresetAppChip("Kernel info", "uname -a") { shellCommand = it }
                        PresetAppChip("Storage", "df -h /sdcard") { shellCommand = it }
                        PresetAppChip("List APKs", "pm list packages -3") { shellCommand = it }
                    }

                    Button(
                        onClick = { viewModel.testExecuteShell(shellCommand) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isExecuting && shellCommand.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Run Shell Command", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ElementItemRow(
    element: UiElementInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        color = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else JarvisSurface,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = element.id,
                    color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                val label = element.text.ifBlank { element.contentDescription }
                if (label.isNotBlank()) {
                    Text(text = "\"$label\"", color = JarvisTextSecondary, fontSize = 10.sp, maxLines = 1)
                }
            }
            Text(
                text = "(${element.bounds.centerX}, ${element.bounds.centerY})",
                color = JarvisTeal,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun PresetAppChip(label: String, packageName: String, onSelect: (String) -> Unit) {
    SuggestionChip(
        onClick = { onSelect(packageName) },
        label = { Text(label, fontSize = 11.sp) },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = JarvisSurfaceVariant,
            labelColor = JarvisTextPrimary
        ),
        border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = JarvisBorder)
    )
}
