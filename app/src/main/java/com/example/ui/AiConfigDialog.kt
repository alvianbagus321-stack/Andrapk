package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AiConfig
import com.example.data.AiConfigManager
import com.example.data.AiPreset
import com.example.data.ConnectionTestResult
import com.example.data.FreeQuotaManager
import com.example.data.FreeQuotaStatus
import com.example.data.OpenRouterFreeModelItem
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Buka tautan portal API di browser eksternal dengan fallback salin ke clipboard. */
fun openFreeApiPortalUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        copyUrlToClipboard(context, "Tautan Portal API", url)
        Toast.makeText(context, "Browser tidak ditemukan — tautan disalin ke clipboard", Toast.LENGTH_LONG).show()
    }
}

private fun copyUrlToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun AiConfigDialog(
    currentConfig: AiConfig,
    onSave: (apiKey: String, baseUrl: String, modelName: String, provider: String?) -> Unit,
    onDismiss: () -> Unit,
    initialFilterFree: Boolean = false,
    initialPresetId: String? = null
) {
    val context = LocalContext.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    val initialPreset = remember(initialPresetId) {
        initialPresetId?.let { id -> AiConfigManager.allModels().firstOrNull { it.id == id } }
    }
    var apiKey by remember {
        mutableStateOf(
            if (initialPreset != null) {
                if (AiConfigManager.isSameProviderVault(currentConfig.baseUrl, initialPreset.baseUrl)) {
                    currentConfig.apiKey
                } else {
                    AiConfigManager.getSavedApiKeyForEndpoint(initialPreset.baseUrl)
                }
            } else {
                currentConfig.apiKey
            }
        )
    }
    var baseUrl by remember { mutableStateOf(initialPreset?.baseUrl ?: currentConfig.baseUrl) }
    var modelName by remember { mutableStateOf(initialPreset?.defaultModel ?: currentConfig.modelName) }
    var maxAgentLoops by remember { mutableIntStateOf(currentConfig.maxAgentLoops) }
    var selectedPresetId by remember { mutableStateOf<String?>(initialPreset?.id) }
    var isKeyVisible by remember { mutableStateOf(false) }

    // 0 = Semua, 1 = Gratis, 2 = Pro & Lokal
    var presetFilter by remember { mutableIntStateOf(if (initialFilterFree) 1 else 0) }
    var showFreePortalsList by remember { mutableStateOf(false) }

    // Katalog model :free live OpenRouter
    var showOrLiveCatalog by remember { mutableStateOf(false) }
    var isLoadingOrModels by remember { mutableStateOf(false) }
    var orLiveModels by remember { mutableStateOf<List<OpenRouterFreeModelItem>>(emptyList()) }
    var orLiveError by remember { mutableStateOf<String?>(null) }
    var orSearchQuery by remember { mutableStateOf("") }

    // Status tier API key OpenRouter
    var isCheckingOrKey by remember { mutableStateOf(false) }
    var orKeyStatusMsg by remember { mutableStateOf<String?>(null) }
    var orKeyStatusOk by remember { mutableStateOf(false) }

    // Form impor model kustom
    var showImportForm by remember { mutableStateOf(false) }
    var importName by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var importModel by remember { mutableStateOf("") }

    // Draft key per endpoint agar perpindahan preset tidak menimpa ketikan user
    val draftKeys = remember { mutableStateMapOf<String, String>() }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val quotaRevision by FreeQuotaManager.revision.collectAsState()
    val openRouterHighTier by FreeQuotaManager.openRouterHighTier.collectAsState()
    val enforceLocalLimit by FreeQuotaManager.enforceLocalLimit.collectAsState()
    var nowTickMs by remember { mutableStateOf(System.currentTimeMillis()) }

    val activeQuotaStatus: FreeQuotaStatus? = remember(
        baseUrl, modelName, quotaRevision, openRouterHighTier, enforceLocalLimit, nowTickMs
    ) {
        FreeQuotaManager.getStatus(baseUrl, modelName, nowTickMs)
    }

    LaunchedEffect(baseUrl, modelName) {
        if (FreeQuotaManager.resolveBucketId(baseUrl, modelName) != null) {
            while (true) {
                delay(1000L)
                nowTickMs = System.currentTimeMillis()
            }
        }
    }

    fun selectPresetSafely(preset: AiPreset) {
        val previousUrl = baseUrl
        if (apiKey.isNotBlank()) {
            draftKeys[previousUrl.trim().lowercase()] = apiKey
        }
        val nextKey = if (AiConfigManager.isSameProviderVault(previousUrl, preset.baseUrl)) {
            apiKey
        } else {
            val fromDraft = draftKeys.entries
                .firstOrNull { AiConfigManager.isSameProviderVault(it.key, preset.baseUrl) }
                ?.value
            fromDraft ?: AiConfigManager.getSavedApiKeyForEndpoint(preset.baseUrl)
        }
        selectedPresetId = preset.id
        baseUrl = preset.baseUrl
        modelName = preset.defaultModel
        apiKey = nextKey
        testResult = null
        orKeyStatusMsg = null
    }

    val activePreset: AiPreset? = remember(baseUrl, modelName, selectedPresetId) {
        val all = AiConfigManager.allModels()
        all.firstOrNull { it.id == selectedPresetId && it.baseUrl == baseUrl && it.defaultModel == modelName }
            ?: AiConfigManager.findMatchingPreset(baseUrl, modelName)
    }
    val activePortal: Pair<String, String>? = remember(baseUrl, activePreset) {
        val presetUrl = activePreset?.apiKeyPortalUrl
        if (presetUrl != null) {
            presetUrl to (activePreset.apiKeyPortalLabel ?: "Ambil API Key")
        } else {
            AiConfigManager.getPortalUrlForEndpoint(baseUrl)
        }
    }
    val isOpenRouterSelected = remember(baseUrl) { AiConfigManager.isOpenRouterEndpoint(baseUrl) }
    val isKeyOptional = remember(baseUrl, modelName) { !AiConfigManager.requiresApiKey(baseUrl, modelName) }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisCyan,
        unfocusedBorderColor = JarvisBorder,
        focusedTextColor = JarvisTextPrimary,
        unfocusedTextColor = JarvisTextPrimary,
        focusedContainerColor = JarvisBackground,
        unfocusedContainerColor = JarvisBackground,
        cursorColor = JarvisCyan
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .heightIn(max = (screenHeightDp * 0.88f).dp)
                .testTag("ai_config_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = BorderStroke(1.dp, JarvisBorderStrong)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header tetap
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model & Endpoint AI",
                            color = JarvisTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Kelola provider, kuota gratis, dan kunci API",
                            color = JarvisTextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = JarvisBorder)

                // Konten utama yang dapat digulir
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Kartu Akses API & Token Gratis (Inception Labs + Portal Lain)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = JarvisSurfaceVariant,
                        border = BorderStroke(1.dp, JarvisBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("free_token_inception_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Inception Labs · Mercury 3",
                                    color = JarvisTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = JarvisEmerald.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = "100M token gratis",
                                        color = JarvisEmerald,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Masuk untuk membuat API key dengan alokasi 100 juta token gratis (konteks 1M, >1.000 tok/dtk), lalu tempel pada kolom API Key.",
                                color = JarvisTextSecondary,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        AiConfigManager.PRESETS.firstOrNull { it.id == "inception_mercury_3" }
                                            ?.let { selectPresetSafely(it) }
                                        openFreeApiPortalUrl(context, AiConfigManager.INCEPTION_LABS_LOGIN_URL)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .testTag("btn_open_inception_login"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = JarvisCyan,
                                        contentColor = JarvisOnPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Buka Inception Labs",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = JarvisSurface,
                                    border = BorderStroke(1.dp, JarvisBorderStrong),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            copyUrlToClipboard(
                                                context,
                                                "Inception Labs",
                                                AiConfigManager.INCEPTION_LABS_LOGIN_URL
                                            )
                                            Toast.makeText(context, "Tautan Inception Labs disalin", Toast.LENGTH_SHORT).show()
                                        }
                                        .testTag("btn_copy_inception_url")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Salin tautan",
                                            tint = JarvisTextSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(thickness = 1.dp, color = JarvisBorder)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showFreePortalsList = !showFreePortalsList }
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Penyedia API gratis lainnya (OpenRouter, Groq, AI Studio, Mistral)",
                                    color = JarvisTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (showFreePortalsList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = JarvisTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(visible = showFreePortalsList) {
                                Column(
                                    modifier = Modifier.padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AiConfigManager.FREE_API_PORTALS.forEach { portal ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = JarvisSurface,
                                            border = BorderStroke(1.dp, JarvisBorder),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    AiConfigManager.PRESETS.firstOrNull { it.id == portal.presetId }
                                                        ?.let { selectPresetSafely(it) }
                                                    openFreeApiPortalUrl(context, portal.url)
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = portal.providerName,
                                                            color = JarvisTextPrimary,
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = portal.bonusBadge,
                                                            color = JarvisEmerald,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(1.dp))
                                                    Text(
                                                        text = portal.resetSummary,
                                                        color = JarvisTextSecondary,
                                                        fontSize = 10.sp,
                                                        lineHeight = 14.sp
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = "Buka portal",
                                                    tint = JarvisTextSecondary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pemilih Preset Model dengan Tab Segmen
                    val customModels by AiConfigManager.customModels.collectAsState()
                    val allModels = remember(customModels) { AiConfigManager.allModels() }
                    val displayedPresets = remember(allModels, presetFilter) {
                        when (presetFilter) {
                            1 -> allModels.filter { it.isFreeTier }
                            2 -> allModels.filter { !it.isFreeTier }
                            else -> allModels
                        }
                    }
                    val freeCount = remember(allModels) { allModels.count { it.isFreeTier } }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Preset Model",
                            color = JarvisTextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Kontrol segmen bergaya studio
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = JarvisBackground,
                            border = BorderStroke(1.dp, JarvisBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                SegmentedTabItem(
                                    label = "Semua (${allModels.size})",
                                    selected = presetFilter == 0,
                                    onClick = { presetFilter = 0 },
                                    modifier = Modifier.weight(1f)
                                )
                                SegmentedTabItem(
                                    label = "Gratis ($freeCount)",
                                    selected = presetFilter == 1,
                                    onClick = { presetFilter = 1 },
                                    modifier = Modifier.weight(1f)
                                )
                                SegmentedTabItem(
                                    label = "Pro & Lokal",
                                    selected = presetFilter == 2,
                                    onClick = { presetFilter = 2 },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(displayedPresets) { preset ->
                                val isSelected = (baseUrl == preset.baseUrl && modelName == preset.defaultModel) ||
                                    (selectedPresetId == preset.id)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) JarvisSurfaceHighlight else JarvisSurfaceVariant,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) JarvisCyan else JarvisBorder
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { selectPresetSafely(preset) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = preset.displayName,
                                                color = JarvisTextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                            )
                                            if (preset.isFreeTier) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = JarvisEmerald.copy(alpha = 0.14f)
                                                ) {
                                                    Text(
                                                        text = if (preset.requiresApiKey) "Gratis" else "Tanpa Key",
                                                        color = JarvisEmerald,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = if (preset.limitBadge != null) {
                                                "${preset.providerName} · ${preset.limitBadge}"
                                            } else {
                                                preset.providerName
                                            },
                                            color = JarvisTextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        activePreset?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            Text(
                                text = desc,
                                color = JarvisTextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    // Panel Kuota & Jadwal Reset Berkala
                    AnimatedVisibility(visible = activeQuotaStatus != null) {
                        activeQuotaStatus?.let { qs ->
                            FreeQuotaStatusCard(
                                status = qs,
                                isOpenRouter = isOpenRouterSelected,
                                openRouterHighTier = openRouterHighTier,
                                isCheckingOrKey = isCheckingOrKey,
                                orKeyStatusMsg = orKeyStatusMsg,
                                orKeyStatusOk = orKeyStatusOk,
                                onToggleOrHighTier = { FreeQuotaManager.setOpenRouterHighTier(it) },
                                onToggleEnforceLocal = { FreeQuotaManager.setEnforceLocalLimit(it) },
                                onCheckOrKey = {
                                    coroutineScope.launch {
                                        isCheckingOrKey = true
                                        orKeyStatusMsg = null
                                        val res = AiConfigManager.checkOpenRouterKeyStatus(apiKey)
                                        orKeyStatusOk = res.success
                                        orKeyStatusMsg = res.message
                                        isCheckingOrKey = false
                                    }
                                },
                                onResetLocal = {
                                    FreeQuotaManager.resetLocalCounter(qs.policy.bucketId)
                                    Toast.makeText(context, "Penghitung kuota lokal direset", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }

                    // Katalog Live Model :free OpenRouter
                    AnimatedVisibility(visible = isOpenRouterSelected) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    showOrLiveCatalog = !showOrLiveCatalog
                                    if (showOrLiveCatalog && orLiveModels.isEmpty() && !isLoadingOrModels) {
                                        coroutineScope.launch {
                                            isLoadingOrModels = true
                                            orLiveError = null
                                            AiConfigManager.fetchOpenRouterFreeModels()
                                                .onSuccess { orLiveModels = it }
                                                .onFailure { orLiveError = it.message ?: "Gagal memuat katalog OpenRouter" }
                                            isLoadingOrModels = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                                border = BorderStroke(1.dp, JarvisBorderStrong),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.CloudSync, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (showOrLiveCatalog) "Tutup katalog :free OpenRouter" else "Telusuri model :free aktif di OpenRouter",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            AnimatedVisibility(visible = showOrLiveCatalog) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = JarvisSurfaceVariant,
                                    border = BorderStroke(1.dp, JarvisBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (orLiveModels.isNotEmpty()) {
                                                    "Katalog :free (${orLiveModels.size} model aktif)"
                                                } else {
                                                    "Katalog OpenRouter (:free)"
                                                },
                                                color = JarvisTextPrimary,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            IconButton(
                                                onClick = {
                                                    if (!isLoadingOrModels) {
                                                        coroutineScope.launch {
                                                            isLoadingOrModels = true
                                                            orLiveError = null
                                                            AiConfigManager.fetchOpenRouterFreeModels()
                                                                .onSuccess { orLiveModels = it }
                                                                .onFailure { orLiveError = it.message ?: "Gagal memuat" }
                                                            isLoadingOrModels = false
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Refresh,
                                                    contentDescription = "Muat ulang",
                                                    tint = JarvisTextSecondary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }

                                        val errText = orLiveError
                                        if (isLoadingOrModels) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 1.8.dp,
                                                    color = JarvisCyan
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    "Mengambil daftar model :free...",
                                                    color = JarvisTextSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        } else if (errText != null) {
                                            Text(
                                                text = errText,
                                                color = JarvisRed,
                                                fontSize = 11.sp
                                            )
                                        } else if (orLiveModels.isNotEmpty()) {
                                            OutlinedTextField(
                                                value = orSearchQuery,
                                                onValueChange = { orSearchQuery = it },
                                                placeholder = {
                                                    Text(
                                                        "Cari model (nemotron, llama, qwen, cohere)",
                                                        color = JarvisTextSecondary,
                                                        fontSize = 11.5.sp
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.Search,
                                                        contentDescription = null,
                                                        tint = JarvisTextSecondary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                shape = RoundedCornerShape(8.dp),
                                                colors = textFieldColors
                                            )

                                            val filtered = remember(orLiveModels, orSearchQuery) {
                                                val q = orSearchQuery.trim().lowercase()
                                                if (q.isEmpty()) orLiveModels.take(12)
                                                else orLiveModels.filter {
                                                    it.id.lowercase().contains(q) || it.name.lowercase().contains(q)
                                                }.take(15)
                                            }

                                            filtered.forEach { item ->
                                                val chosen = modelName.equals(item.id, ignoreCase = true)
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (chosen) JarvisSurfaceHighlight else JarvisSurface,
                                                    border = BorderStroke(1.dp, if (chosen) JarvisCyan else JarvisBorder),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            baseUrl = AiConfigManager.OPENROUTER_BASE_URL
                                                            modelName = item.id
                                                            selectedPresetId = null
                                                            testResult = null
                                                        }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = item.name,
                                                                color = JarvisTextPrimary,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            val ctxStr = if (item.contextLength >= 1000) " · ${item.contextLength / 1000}K" else ""
                                                            val visStr = if (item.supportsVision) " · Vision" else ""
                                                            Text(
                                                                text = "${item.id}$ctxStr$visStr",
                                                                color = JarvisTextSecondary,
                                                                fontSize = 9.5.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        if (chosen) {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = JarvisCyan,
                                                                modifier = Modifier.size(16.dp)
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
                    }

                    // Model Kustom Pengguna
                    if (customModels.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Model Kustom",
                                color = JarvisTextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            customModels.forEach { cm ->
                                val isSelected = selectedPresetId == cm.id
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) JarvisSurfaceHighlight else JarvisSurfaceVariant,
                                    border = BorderStroke(1.dp, if (isSelected) JarvisCyan else JarvisBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectPresetSafely(cm) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = cm.displayName,
                                                color = JarvisTextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "${cm.defaultModel} · ${cm.baseUrl}",
                                                color = JarvisTextSecondary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                AiConfigManager.removeCustomModel(cm.id)
                                                if (selectedPresetId == cm.id) selectedPresetId = null
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Hapus model",
                                                tint = JarvisTextSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tombol Impor Model Sendiri
                    OutlinedButton(
                        onClick = { showImportForm = !showImportForm },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                        border = BorderStroke(1.dp, JarvisBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            if (showImportForm) Icons.Default.ExpandLess else Icons.Default.Add,
                            contentDescription = null,
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tambah Endpoint / Model Kustom", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }

                    AnimatedVisibility(visible = showImportForm) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = JarvisSurfaceVariant,
                            border = BorderStroke(1.dp, JarvisBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = importName,
                                    onValueChange = { importName = it },
                                    placeholder = { Text("Nama tampilan (mis. Qwen3 4B Lokal)", color = JarvisTextSecondary, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = textFieldColors
                                )
                                OutlinedTextField(
                                    value = importUrl,
                                    onValueChange = { importUrl = it },
                                    placeholder = { Text("Base URL (mis. http://127.0.0.1:8080/v1)", color = JarvisTextSecondary, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = textFieldColors
                                )
                                OutlinedTextField(
                                    value = importModel,
                                    onValueChange = { importModel = it },
                                    placeholder = { Text("ID model (mis. qwen3-4b / model:free)", color = JarvisTextSecondary, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = textFieldColors
                                )
                                Button(
                                    onClick = {
                                        if (importUrl.isNotBlank() && importModel.isNotBlank()) {
                                            val saved = AiConfigManager.addCustomModel(
                                                displayName = importName,
                                                baseUrl = importUrl,
                                                modelId = importModel
                                            )
                                            selectPresetSafely(saved)
                                            importName = ""
                                            importUrl = ""
                                            importModel = ""
                                            showImportForm = false
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp),
                                    enabled = importUrl.isNotBlank() && importModel.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = JarvisCyan,
                                        contentColor = JarvisOnPrimary
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Tambahkan & Gunakan", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                }
                                Text(
                                    text = "Mendukung llama.cpp, vLLM, Ollama, LM Studio, OpenRouter, Inception Labs, dan endpoint OpenAI-compatible lainnya.",
                                    color = JarvisTextSecondary,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    // Kolom API Key
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isKeyOptional) "API Key (opsional)" else "API Key",
                                color = JarvisTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (activePortal != null) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { openFreeApiPortalUrl(context, activePortal.first) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Ambil API Key",
                                        color = JarvisCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = JarvisCyan,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                testResult = null
                            },
                            placeholder = {
                                Text(
                                    text = activePreset?.apiKeyPlaceholder ?: "Tempel API key provider",
                                    color = JarvisTextSecondary.copy(alpha = 0.65f),
                                    fontSize = 12.sp
                                )
                            },
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
                                        contentDescription = "Tampilkan atau sembunyikan key",
                                        tint = JarvisTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = textFieldColors
                        )
                        Text(
                            text = when {
                                AiConfigManager.isInceptionEndpoint(baseUrl) ->
                                    "Kunci API tiap provider disimpan terpisah. Gunakan tombol di atas untuk masuk dan mengambil key Inception Labs."
                                isKeyOptional ->
                                    "Endpoint ini dapat dipakai langsung tanpa API key."
                                else ->
                                    "Disimpan otomatis per provider sehingga tetap aman saat berpindah model."
                            },
                            color = JarvisTextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                    }

                    // Kolom Base URL
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "Base URL",
                            color = JarvisTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = {
                                baseUrl = it
                                testResult = null
                            },
                            placeholder = {
                                Text(
                                    "https://api.inceptionlabs.ai/v1",
                                    color = JarvisTextSecondary.copy(alpha = 0.65f),
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_base_url"),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = textFieldColors
                        )
                    }

                    // Kolom Nama Model
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "ID Model",
                            color = JarvisTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = modelName,
                            onValueChange = {
                                modelName = it
                                testResult = null
                            },
                            placeholder = {
                                Text(
                                    "mercury-3 / openrouter/free / gemini-3.5-flash-lite",
                                    color = JarvisTextSecondary.copy(alpha = 0.65f),
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_model_name"),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = textFieldColors
                        )
                    }

                    // Batas Langkah Agent
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Batas Iterasi Agen",
                                color = JarvisTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (maxAgentLoops == 0) "Otomatis" else "$maxAgentLoops langkah",
                                color = JarvisCyan,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterPillOption(
                                label = "Otomatis",
                                selected = maxAgentLoops == 0,
                                onClick = { maxAgentLoops = 0 }
                            )
                            FilterPillOption(
                                label = "20 langkah (standar)",
                                selected = maxAgentLoops == 20,
                                onClick = { maxAgentLoops = 20 }
                            )
                        }

                        Slider(
                            value = maxAgentLoops.toFloat(),
                            onValueChange = { maxAgentLoops = it.toInt() },
                            valueRange = 0f..50f,
                            steps = 49,
                            colors = SliderDefaults.colors(
                                thumbColor = JarvisCyan,
                                activeTrackColor = JarvisCyan,
                                inactiveTrackColor = JarvisSurfaceHighlight
                            )
                        )
                    }

                    // Uji Koneksi
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isTestingConnection = true
                                    testResult = null
                                    testResult = AiConfigManager.testEndpointConnection(
                                        apiKey = apiKey,
                                        baseUrl = baseUrl,
                                        modelName = modelName
                                    )
                                    isTestingConnection = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("btn_test_connection"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                            border = BorderStroke(1.dp, JarvisBorderStrong),
                            enabled = !isTestingConnection
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    color = JarvisCyan,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Menguji koneksi...", color = JarvisTextSecondary, fontSize = 12.sp)
                            } else {
                                Icon(
                                    Icons.Default.NetworkCheck,
                                    contentDescription = null,
                                    tint = JarvisTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Uji Koneksi Endpoint", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        AnimatedVisibility(visible = testResult != null) {
                            testResult?.let { res ->
                                val statusColor = if (res.success) JarvisEmerald else JarvisRed
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = statusColor.copy(alpha = 0.1f),
                                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = statusColor,
                                            modifier = Modifier
                                                .padding(top = 1.dp)
                                                .size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (res.success) "Terhubung (${res.latencyMs} ms)" else "Koneksi gagal",
                                                color = statusColor,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = res.message,
                                                color = JarvisTextPrimary,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Footer tetap
                HorizontalDivider(thickness = 1.dp, color = JarvisBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            apiKey = AiConfigManager.getSavedApiKeyForEndpoint(AiConfigManager.DEFAULT_GEMINI_BASE_URL)
                            baseUrl = AiConfigManager.DEFAULT_GEMINI_BASE_URL
                            modelName = AiConfigManager.DEFAULT_GEMINI_MODEL
                            maxAgentLoops = 20
                            selectedPresetId = "gemini_flash"
                            testResult = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, JarvisBorderStrong)
                    ) {
                        Text("Reset Default", color = JarvisTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }

                    Button(
                        onClick = {
                            AiConfigManager.saveMaxAgentLoops(maxAgentLoops)
                            onSave(apiKey, baseUrl, modelName, null)
                        },
                        modifier = Modifier
                            .weight(1.25f)
                            .height(40.dp)
                            .testTag("btn_save_ai_config"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisCyan,
                            contentColor = JarvisOnPrimary
                        )
                    ) {
                        Text("Simpan", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) JarvisSurfaceHighlight else Color.Transparent,
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (selected) JarvisTextPrimary else JarvisTextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun FilterPillOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) JarvisSurfaceHighlight else JarvisSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) JarvisCyan else JarvisBorder),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (selected) JarvisTextPrimary else JarvisTextSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun FreeQuotaStatusCard(
    status: FreeQuotaStatus,
    isOpenRouter: Boolean,
    openRouterHighTier: Boolean,
    isCheckingOrKey: Boolean,
    orKeyStatusMsg: String?,
    orKeyStatusOk: Boolean,
    onToggleOrHighTier: (Boolean) -> Unit,
    onToggleEnforceLocal: (Boolean) -> Unit,
    onCheckOrKey: () -> Unit,
    onResetLocal: () -> Unit
) {
    val accent = when {
        status.isBlocked -> JarvisRed
        status.periodRemainingFraction <= 0.2f -> JarvisAmber
        else -> JarvisEmerald
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = JarvisSurfaceVariant,
        border = BorderStroke(1.dp, JarvisBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("free_quota_status_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Kuota & Jadwal Reset",
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Reset hitungan",
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onResetLocal)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${status.periodRemaining}/${status.periodLimit} req tersisa (${status.policy.periodLabel})",
                        color = JarvisTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (status.periodResetInMs > 0L) {
                            "Reset ${FreeQuotaManager.formatCountdown(status.periodResetInMs)}"
                        } else {
                            "Penuh"
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
                            .fillMaxWidth(status.periodRemainingFraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batas per menit: ${status.minuteRemaining}/${status.minuteLimit} req",
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp
                )
                Text(
                    text = if (status.minuteResetInMs > 0L) {
                        "Reset ${FreeQuotaManager.formatCountdown(status.minuteResetInMs)}"
                    } else {
                        "Jendela 60 dtk"
                    },
                    color = if (status.isMinuteExhausted) JarvisAmber else JarvisTextSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            val grantLimit = status.policy.tokenGrantLimit
            if (grantLimit != null) {
                val remTokens = (grantLimit - status.totalTokensUsed).coerceAtLeast(0L)
                Text(
                    text = "Estimasi sisa token: ${FreeQuotaManager.formatTokenCount(remTokens)} dari ${FreeQuotaManager.formatTokenCount(grantLimit)} (terpakai ${FreeQuotaManager.formatTokenCount(status.totalTokensUsed)})",
                    color = JarvisEmerald,
                    fontSize = 10.5.sp
                )
            } else if (status.periodTokensUsed > 0L) {
                Text(
                    text = "Token terpakai periode ini: ${FreeQuotaManager.formatTokenCount(status.periodTokensUsed)}",
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp
                )
            }

            if (status.isServerCoolingDown) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = JarvisRed.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${status.serverCooldownReason ?: "Jeda server aktif"} — aktif kembali dalam ${FreeQuotaManager.formatCountdown(status.serverCooldownRemainingMs)}",
                        color = JarvisRed,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            if (isOpenRouter) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterPillOption(
                        label = "50/hari",
                        selected = !openRouterHighTier,
                        onClick = { onToggleOrHighTier(false) }
                    )
                    FilterPillOption(
                        label = "1.000/hari (≥\$10)",
                        selected = openRouterHighTier,
                        onClick = { onToggleOrHighTier(true) }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = JarvisSurface,
                        border = BorderStroke(1.dp, JarvisBorderStrong),
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(enabled = !isCheckingOrKey, onClick = onCheckOrKey)
                    ) {
                        Text(
                            text = if (isCheckingOrKey) "Memeriksa..." else "Cek Key",
                            color = JarvisTextPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                }
                if (!orKeyStatusMsg.isNullOrBlank()) {
                    Text(
                        text = orKeyStatusMsg,
                        color = if (orKeyStatusOk) JarvisEmerald else JarvisRed,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = status.policy.resetDescription,
                    color = JarvisTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 13.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (status.enforceLocalLimit) JarvisSurfaceHighlight else JarvisSurface,
                    border = BorderStroke(1.dp, if (status.enforceLocalLimit) JarvisBorderStrong else JarvisBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleEnforceLocal(!status.enforceLocalLimit) }
                ) {
                    Text(
                        text = if (status.enforceLocalLimit) "Proteksi Aktif" else "Proteksi Mati",
                        color = if (status.enforceLocalLimit) JarvisTextPrimary else JarvisTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
