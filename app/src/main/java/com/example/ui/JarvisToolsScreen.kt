package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.*
import com.example.service.ToolManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisToolsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val allTools by ToolManager.tools.collectAsStateWithLifecycle()
    val permissionMode by ToolManager.permissionMode.collectAsStateWithLifecycle()
    val customPerms by ToolManager.customPermissions.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("all") } // all, active, builtin, custom

    var showGuideDialog by remember { mutableStateOf(false) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var toolToEdit by remember { mutableStateOf<CustomTool?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showCustomPermsDialog by remember { mutableStateOf(false) }
    var testToolTarget by remember { mutableStateOf<CustomTool?>(null) }

    // Filter tools
    val filteredTools = remember(allTools, searchQuery, selectedFilter) {
        allTools.filter { tool ->
            val matchesQuery = tool.name.contains(searchQuery, ignoreCase = true) ||
                    tool.id.contains(searchQuery, ignoreCase = true) ||
                    tool.description.contains(searchQuery, ignoreCase = true) ||
                    tool.category.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "active" -> tool.isEnabled
                "builtin" -> tool.isBuiltIn
                "custom" -> !tool.isBuiltIn
                "termux" -> tool.category.contains("Termux", ignoreCase = true) || tool.id.startsWith("termux")
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = JarvisBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    toolToEdit = null
                    showAddEditDialog = true
                },
                containerColor = JarvisCyan,
                contentColor = JarvisBackground,
                shape = CircleShape,
                modifier = Modifier.testTag("fab_add_tool")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Tambah Tool Baru")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Top Bar: Title & Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kelola Tools & AI Actions",
                        color = JarvisTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${allTools.count { it.isEnabled }} aktif dari ${allTools.size} tools tersedia",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )
                }

                // Guide & Template (?) button
                FilledTonalIconButton(
                    onClick = { showGuideDialog = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = JarvisCyan.copy(alpha = 0.15f),
                        contentColor = JarvisCyan
                    ),
                    modifier = Modifier.testTag("btn_tool_guide")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "Panduan & Template Tools (?)"
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Import Button
                IconButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier.testTag("btn_import_tools")
                ) {
                    Icon(
                        Icons.Default.FileUpload,
                        contentDescription = "Import Tools",
                        tint = JarvisTeal
                    )
                }

                // Export Button
                IconButton(
                    onClick = {
                        val json = ToolManager.exportToolsToJson()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Custom Tools", json))
                        Toast.makeText(context, "Semua custom tools disalin ke clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("btn_export_tools")
                ) {
                    Icon(
                        Icons.Default.FileDownload,
                        contentDescription = "Export Tools",
                        tint = JarvisTeal
                    )
                }
            }

            // AI Permission Mode Card
            PermissionModeCard(
                currentMode = permissionMode,
                onModeSelected = { mode ->
                    ToolManager.setPermissionMode(mode)
                    if (mode == AiPermissionMode.CUSTOM) {
                        showCustomPermsDialog = true
                    }
                },
                onOpenCustomSettings = { showCustomPermsDialog = true }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Search & Filter row
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Cari tool berdasarkan nama, id, atau perintah...", fontSize = 12.sp, color = JarvisTextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = JarvisTextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_search_tools"),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = JarvisCyan,
                    unfocusedBorderColor = JarvisBorder,
                    focusedTextColor = JarvisTextPrimary,
                    unfocusedTextColor = JarvisTextPrimary,
                    focusedContainerColor = JarvisSurface,
                    unfocusedContainerColor = JarvisSurface
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == "all",
                    onClick = { selectedFilter = "all" },
                    label = { Text("Semua (${allTools.size})", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "active",
                    onClick = { selectedFilter = "active" },
                    label = { Text("Aktif (${allTools.count { it.isEnabled }})", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "builtin",
                    onClick = { selectedFilter = "builtin" },
                    label = { Text("Built-in (${allTools.count { it.isBuiltIn }})", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "custom",
                    onClick = { selectedFilter = "custom" },
                    label = { Text("Custom & AI (${allTools.count { !it.isBuiltIn }})", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = selectedFilter == "termux",
                    onClick = { selectedFilter = "termux" },
                    label = { Text("Termux & Service (${allTools.count { it.category.contains("Termux", ignoreCase = true) || it.id.startsWith("termux") }})", fontSize = 11.sp) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tools List
            if (filteredTools.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            tint = JarvisTextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tidak ada tool yang cocok",
                            color = JarvisTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 76.dp)
                ) {
                    items(filteredTools, key = { it.id }) { tool ->
                        ToolItemCard(
                            tool = tool,
                            onToggle = { ToolManager.toggleTool(tool.id) },
                            onEdit = {
                                toolToEdit = tool
                                showAddEditDialog = true
                            },
                            onDelete = {
                                val deleted = ToolManager.deleteTool(tool.id)
                                if (deleted) {
                                    Toast.makeText(context, "Tool '${tool.name}' berhasil dihapus", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onTestRun = {
                                testToolTarget = tool
                            }
                        )
                    }
                }
            }
        }
    }

    // Guide & Template Dialog (?)
    if (showGuideDialog) {
        ToolGuideAndTemplatesDialog(
            onDismiss = { showGuideDialog = false },
            onApplyTemplate = { template ->
                showGuideDialog = false
                toolToEdit = template.copy(
                    id = "custom_" + template.id.removePrefix("template_") + "_" + System.currentTimeMillis() % 1000
                )
                showAddEditDialog = true
            }
        )
    }

    // Add / Edit Tool Dialog
    if (showAddEditDialog) {
        AddEditToolDialog(
            initialTool = toolToEdit,
            onDismiss = { showAddEditDialog = false },
            onSave = { savedTool ->
                ToolManager.addOrUpdateTool(savedTool)
                Toast.makeText(context, "Tool '${savedTool.name}' berhasil disimpan!", Toast.LENGTH_SHORT).show()
                showAddEditDialog = false
            }
        )
    }

    // Import Dialog
    if (showImportDialog) {
        ImportToolsDialog(
            onDismiss = { showImportDialog = false },
            onImport = { jsonStr ->
                val result = ToolManager.importToolsFromJson(jsonStr)
                result.onSuccess { count ->
                    Toast.makeText(context, "Berhasil mengimpor $count tool!", Toast.LENGTH_SHORT).show()
                    showImportDialog = false
                }.onFailure { err ->
                    Toast.makeText(context, "Gagal mengimpor: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    // Custom Permissions Dialog
    if (showCustomPermsDialog) {
        CustomPermissionsDialog(
            settings = customPerms,
            onDismiss = { showCustomPermsDialog = false },
            onSave = { updated ->
                ToolManager.updateCustomPermissions(updated)
                Toast.makeText(context, "Setelan izin kustom disimpan!", Toast.LENGTH_SHORT).show()
                showCustomPermsDialog = false
            }
        )
    }

    // Test Run Tool Dialog
    testToolTarget?.let { tool ->
        TestRunToolDialog(
            tool = tool,
            onDismiss = { testToolTarget = null }
        )
    }
}

/**
 * Card displaying and switching AI Permission Modes
 */
@Composable
fun PermissionModeCard(
    currentMode: AiPermissionMode,
    onModeSelected: (AiPermissionMode) -> Unit,
    onOpenCustomSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JarvisSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = when (currentMode) {
                        AiPermissionMode.SANDBOXED -> JarvisTeal
                        AiPermissionMode.LOW_RISK -> JarvisAmber
                        AiPermissionMode.FULL_ACCESS -> JarvisRed
                        AiPermissionMode.CUSTOM -> JarvisCyan
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Mode Izin AI (Security Level):",
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (currentMode == AiPermissionMode.CUSTOM) {
                    TextButton(
                        onClick = onOpenCustomSettings,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp), tint = JarvisCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Atur Izin", color = JarvisCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PermissionModeOption(
                    title = "Sandboxed",
                    subtitle = "Read-Only",
                    isSelected = currentMode == AiPermissionMode.SANDBOXED,
                    activeColor = JarvisTeal,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.SANDBOXED) }
                )
                PermissionModeOption(
                    title = "Low Risk",
                    subtitle = "Aman",
                    isSelected = currentMode == AiPermissionMode.LOW_RISK,
                    activeColor = JarvisAmber,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.LOW_RISK) }
                )
                PermissionModeOption(
                    title = "Full Access",
                    subtitle = "Bebas",
                    isSelected = currentMode == AiPermissionMode.FULL_ACCESS,
                    activeColor = JarvisRed,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.FULL_ACCESS) }
                )
                PermissionModeOption(
                    title = "Custom",
                    subtitle = "Kustom",
                    isSelected = currentMode == AiPermissionMode.CUSTOM,
                    activeColor = JarvisCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { onModeSelected(AiPermissionMode.CUSTOM) }
                )
            }
        }
    }
}

@Composable
fun PermissionModeOption(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        color = if (isSelected) activeColor.copy(alpha = 0.15f) else JarvisSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) activeColor else JarvisBorder.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = if (isSelected) activeColor else JarvisTextPrimary,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = subtitle,
                color = JarvisTextSecondary,
                fontSize = 9.sp
            )
        }
    }
}

/**
 * Single Tool Item Card with details, switch, test button, edit & delete.
 */
@Composable
fun ToolItemCard(
    tool: CustomTool,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestRun: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = JarvisSurface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (tool.isEnabled) JarvisBorder else JarvisBorder.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Name, badges & Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tool.name,
                            color = if (tool.isEnabled) JarvisTextPrimary else JarvisTextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (tool.createdByAi) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = JarvisCyan.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "AI CREATED",
                                    color = JarvisCyan,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "ID: ${tool.id}",
                        color = JarvisTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Status Switch
                Switch(
                    checked = tool.isEnabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = JarvisCyan,
                        checkedTrackColor = JarvisCyanDark.copy(alpha = 0.5f),
                        uncheckedThumbColor = JarvisTextSecondary,
                        uncheckedTrackColor = JarvisSurfaceVariant
                    ),
                    modifier = Modifier.testTag("switch_${tool.id}")
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Description
            Text(
                text = tool.description,
                color = JarvisTextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Badges Row: Script Type, Category, Risk
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Script type badge
                Surface(
                    color = JarvisSurfaceVariant,
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
                ) {
                    Text(
                        text = tool.scriptType.name,
                        color = JarvisCyan,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Category
                Surface(
                    color = JarvisSurfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = tool.category,
                        color = JarvisTextSecondary,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Risk level
                Surface(
                    color = when (tool.riskLevel) {
                        ToolRiskLevel.SAFE -> JarvisEmerald.copy(alpha = 0.15f)
                        ToolRiskLevel.LOW -> JarvisAmber.copy(alpha = 0.15f)
                        ToolRiskLevel.HIGH -> JarvisRed.copy(alpha = 0.15f)
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "RISK: ${tool.riskLevel.name}",
                        color = when (tool.riskLevel) {
                            ToolRiskLevel.SAFE -> JarvisEmerald
                            ToolRiskLevel.LOW -> JarvisAmber
                            ToolRiskLevel.HIGH -> JarvisRed
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Actions: Test Run, Edit, Delete
                IconButton(
                    onClick = onTestRun,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Test Run",
                        tint = JarvisEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = JarvisCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (!tool.isBuiltIn) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Hapus",
                            tint = JarvisRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Help & Templates Dialog accessible via the (?) button
 */
@Composable
fun ToolGuideAndTemplatesDialog(
    onDismiss: () -> Unit,
    onApplyTemplate: (CustomTool) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JarvisSurfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.HelpOutline, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Panduan & Template Pembuatan Tools",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = JarvisTextSecondary)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    // Section 1: Konsep Tool
                    Text(
                        "1. Bagaimana Tool Bekerja?",
                        color = JarvisCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tool adalah fungsi otomasi yang dapat dipanggil oleh pengguna atau AI. Tool mengeksekusi perintah pada Android secara lokal melalui Aksesibilitas, Shell Termux/Shizuku, atau Intent.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Section 2: AI Bisa Buat Tool Sendiri
                    Surface(
                        color = JarvisCyan.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Fitur Spesial: AI Membuat Tool Sendiri",
                                    color = JarvisCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Kamu cukup meminta AI di Chat Window: contohnya 'Tolong buatkan tool untuk mengecek suhu baterai hp' atau 'Buatkan tool kunci layar'. AI akan secara otonom menyusun perintah, parameter, dan mendaftarkannya ke daftar tools ini!",
                                color = JarvisTextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Section 3: Placeholder Parameter
                    Text(
                        "2. Format Placeholder Parameter",
                        color = JarvisCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Gunakan kurung kurawal {nama_parameter} di dalam perintah. Nilai parameter yang dikirim oleh pemanggil akan disisipkan otomatis:\nContoh: 'am start -a android.intent.action.VIEW -d {url}'",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Section 4: Galeri Template Siap Pakai
                    Text(
                        "3. Template Siap Pakai (Klik untuk Menggunakan):",
                        color = JarvisEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ToolManager.toolTemplates.forEach { template ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(template.name, color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(template.description, color = JarvisTextSecondary, fontSize = 10.sp)
                                    }

                                    Button(
                                        onClick = { onApplyTemplate(template) },
                                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("Pakai Template", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = JarvisBackground,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = template.command,
                                        color = JarvisCyan,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Add or Edit Tool Dialog
 */
@Composable
fun AddEditToolDialog(
    initialTool: CustomTool?,
    onDismiss: () -> Unit,
    onSave: (CustomTool) -> Unit
) {
    val isEditing = initialTool != null && !initialTool.id.startsWith("template_")

    var id by remember { mutableStateOf(initialTool?.id ?: "tool_${System.currentTimeMillis() % 10000}") }
    var name by remember { mutableStateOf(initialTool?.name ?: "") }
    var description by remember { mutableStateOf(initialTool?.description ?: "") }
    var category by remember { mutableStateOf(initialTool?.category ?: "Custom") }
    var scriptType by remember { mutableStateOf(initialTool?.scriptType ?: ToolScriptType.SHELL) }
    var command by remember { mutableStateOf(initialTool?.command ?: "") }
    var parametersSchema by remember { mutableStateOf(initialTool?.parametersSchema ?: "{}") }
    var riskLevel by remember { mutableStateOf(initialTool?.riskLevel ?: ToolRiskLevel.LOW) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JarvisSurfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (isEditing) Icons.Default.Edit else Icons.Default.Add, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isEditing) "Edit Tool: ${initialTool?.name}" else "Tambah Tool Baru",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Batal", tint = JarvisTextSecondary)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ID
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text("Tool ID (unik)", fontSize = 11.sp) },
                        enabled = !isEditing || !(initialTool?.isBuiltIn ?: false),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Nama Tool
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nama Tool", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Deskripsi
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Deskripsi Fungsi", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Tipe Script Selector
                    Text("Tipe Script:", color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(ToolScriptType.SHELL, ToolScriptType.ACCESSIBILITY, ToolScriptType.INTENT).forEach { type ->
                            FilterChip(
                                selected = scriptType == type,
                                onClick = { scriptType = type },
                                label = { Text(type.name, fontSize = 10.sp) }
                            )
                        }
                    }

                    // Command / Perintah
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Perintah / Command Script", fontSize = 11.sp) },
                        placeholder = { Text("Contoh: dumpsys battery | grep level", color = JarvisTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Parameter Schema (JSON)
                    OutlinedTextField(
                        value = parametersSchema,
                        onValueChange = { parametersSchema = it },
                        label = { Text("Parameter Schema (JSON)", fontSize = 11.sp) },
                        placeholder = { Text("""{"param": "contoh"}""", color = JarvisTextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        )
                    )

                    // Tingkat Risiko
                    Text("Tingkat Risiko (Security Level):", color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(ToolRiskLevel.SAFE, ToolRiskLevel.LOW, ToolRiskLevel.HIGH).forEach { risk ->
                            FilterChip(
                                selected = riskLevel == risk,
                                onClick = { riskLevel = risk },
                                label = { Text(risk.name, fontSize = 10.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tombol Simpan
                    Button(
                        onClick = {
                            if (name.isBlank() || command.isBlank()) return@Button
                            onSave(
                                CustomTool(
                                    id = id.trim(),
                                    name = name.trim(),
                                    description = description.trim(),
                                    category = category.trim(),
                                    scriptType = scriptType,
                                    command = command.trim(),
                                    parametersSchema = parametersSchema.trim().ifBlank { "{}" },
                                    riskLevel = riskLevel,
                                    isEnabled = initialTool?.isEnabled ?: true,
                                    isBuiltIn = initialTool?.isBuiltIn ?: false,
                                    createdByAi = initialTool?.createdByAi ?: false,
                                    createdAt = initialTool?.createdAt ?: System.currentTimeMillis()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Simpan Tool", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Custom AI Permissions Dialog
 */
@Composable
fun CustomPermissionsDialog(
    settings: CustomPermissionSettings,
    onDismiss: () -> Unit,
    onSave: (CustomPermissionSettings) -> Unit
) {
    var readScreen by remember { mutableStateOf(settings.allowReadScreen) }
    var tapSwipe by remember { mutableStateOf(settings.allowTapSwipe) }
    var typeText by remember { mutableStateOf(settings.allowTypeText) }
    var openApp by remember { mutableStateOf(settings.allowOpenApp) }
    var shell by remember { mutableStateOf(settings.allowShellCommands) }
    var createTools by remember { mutableStateOf(settings.allowCreateTools) }
    var systemKeys by remember { mutableStateOf(settings.allowSystemKeys) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Atur Izin Kustom AI",
                        color = JarvisTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                PermissionToggleRow("Baca Layar & Visi", "Membaca teks dan elemen UI di layar", readScreen) { readScreen = it }
                PermissionToggleRow("Gestur Tap & Swipe", "Mengetuk dan mengusap layar otomatis", tapSwipe) { tapSwipe = it }
                PermissionToggleRow("Ketik Teks Otomatis", "Mengetikkan teks ke input field", typeText) { typeText = it }
                PermissionToggleRow("Buka Aplikasi", "Membuka aplikasi lain dari sistem", openApp) { openApp = it }
                PermissionToggleRow("Perintah Shell / Shizuku", "Mengeksekusi perintah terminal shell", shell) { shell = it }
                PermissionToggleRow("AI Membuat Tool Sendiri", "Mengizinkan AI mendaftarkan tool baru", createTools) { createTools = it }
                PermissionToggleRow("Tombol Sistem (Back/Home)", "Menekan tombol navigasi sistem", systemKeys) { systemKeys = it }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal", color = JarvisTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(
                                CustomPermissionSettings(
                                    allowReadScreen = readScreen,
                                    allowTapSwipe = tapSwipe,
                                    allowTypeText = typeText,
                                    allowOpenApp = openApp,
                                    allowShellCommands = shell,
                                    allowCreateTools = createTools,
                                    allowSystemKeys = systemKeys
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
                    ) {
                        Text("Terapkan", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = JarvisTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = JarvisTextSecondary, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = JarvisCyan,
                checkedTrackColor = JarvisCyanDark.copy(alpha = 0.5f),
                uncheckedThumbColor = JarvisTextSecondary,
                uncheckedTrackColor = JarvisSurfaceVariant
            )
        )
    }
}

/**
 * Import Tools Dialog
 */
@Composable
fun ImportToolsDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var jsonInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Import Custom Tools (JSON)",
                    color = JarvisTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tempelkan JSON array konfigurasi tool yang ingin diimpor ke dalam aplikasi:",
                    color = JarvisTextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    placeholder = { Text("[{\"id\": \"tool_1\", \"name\": \"...\"}]", color = JarvisTextSecondary, fontSize = 11.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal", color = JarvisTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onImport(jsonInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
                    ) {
                        Text("Import", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Quick Test Run Tool Dialog
 */
@Composable
fun TestRunToolDialog(
    tool: CustomTool,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var paramsInput by remember { mutableStateOf(tool.parametersSchema) }
    var executionOutput by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = JarvisEmerald)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Test Eksekusi: ${tool.name}",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Command: ${tool.command}", color = JarvisCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = paramsInput,
                    onValueChange = { paramsInput = it },
                    label = { Text("Parameter (JSON)", fontSize = 10.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isRunning = true
                            executionOutput = "Mengeksekusi ${tool.name}..."
                            val jsonObj = try { JSONObject(paramsInput) } catch (_: Exception) { JSONObject() }
                            val res = ToolManager.executeCustomTool(tool, jsonObj)
                            executionOutput = if (res.status == "ok") {
                                "✅ STATUS OK:\n${res.result ?: "Berhasil dijalankan."}"
                            } else {
                                "❌ ERROR (${res.errorCode ?: "FAIL"}):\n${res.message ?: "Gagal mengeksekusi tool"}"
                            }
                            isRunning = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald, contentColor = Color.White),
                    enabled = !isRunning
                ) {
                    Text(if (isRunning) "Menjalankan..." else "Jalankan Sekarang", fontWeight = FontWeight.Bold)
                }

                executionOutput?.let { out ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = JarvisBackground,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = out,
                            color = JarvisTextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Tutup", color = JarvisTextSecondary)
                }
            }
        }
    }
}
