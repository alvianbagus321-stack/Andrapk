package com.example.quiz

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * UI overlay AI Quiz Analyzer (compact, sesuai spec):
 * header (judul − ×) | status dot | Auto Analyze switch | Delay | [Analyze] |
 * jawaban besar + confidence | ▼ Diagnostic.
 * Minimize → bulatan "Q" kecil yang bisa ditap untuk memperluas lagi.
 */
@Composable
fun QuizOverlayUI() {
    val phase by QuizAnalyzer.phase.collectAsState()
    val answer by QuizAnalyzer.answer.collectAsState()
    val isAnalyzing by QuizAnalyzer.isAnalyzing.collectAsState()
    val autoOn by QuizAnalyzer.autoAnalyze.collectAsState()
    val delayMs by QuizAnalyzer.delayMs.collectAsState()
    val lastError by QuizAnalyzer.lastError.collectAsState()
    val minimized by QuizOverlayManager.isMinimized.collectAsState()
    val autoSubmitOn by QuizAnalyzer.autoSubmit.collectAsState()
    val submitInfo by QuizAnalyzer.submitInfo.collectAsState()
    val diagState by DiagnosticLogger.diagnostic.collectAsState()
    val capturePreview by QuizAnalyzer.capturePreview.collectAsState()
    val captureModeAuto by QuizAnalyzer.captureModeAuto.collectAsState()
    val manualExtraCount by QuizAnalyzer.manualExtraCount.collectAsState()
    val manualCaptures by QuizAnalyzer.manualCaptures.collectAsState()
    val scrollOverlap by QuizAnalyzer.scrollOverlapPercent.collectAsState()
    val scrollFingers by QuizAnalyzer.scrollFingers.collectAsState()
    val autoAnswerOn by QuizAnalyzer.autoAnswerLoop.collectAsState()
    val autoAnswerProgress by QuizAnalyzer.autoAnswerProgress.collectAsState()
    val answerLimitAuto by QuizAnalyzer.answerLimitAuto.collectAsState()
    val answerLimitN by QuizAnalyzer.answerLimitN.collectAsState()
    val scrollFromTop by QuizAnalyzer.scrollToTopOnAnalyze.collectAsState()
    val autoSweepOn by QuizAnalyzer.autoSweep.collectAsState()
    val proMode by QuizAnalyzer.proMode.collectAsState()
    val wheelSide by QuizAnalyzer.wheelSide.collectAsState()
    val manualMode by QuizAnalyzer.manualMode.collectAsState()
    val scrollAmount by QuizAnalyzer.scrollAmount.collectAsState()
    val captureMethod by QuizAnalyzer.captureMethod.collectAsState()
    val manualThumbs by QuizAnalyzer.manualThumbs.collectAsState()
    var viewAttachment by remember { mutableStateOf<Int?>(null) }
    val captureActive by com.example.service.ScreenshotManager.isMediaProjectionActive.collectAsState()
    val captureDiedAt by com.example.service.ScreenshotManager.projectionDiedAt.collectAsState()
    val sweeping by QuizAnalyzer.sweeping.collectAsState()
    // Status Mode Advance PERSISTEN: dibuka kemarin? hari ini tetap terbuka.
    val showAdvanced by QuizAnalyzer.advancedShown.collectAsState()

    if (minimized) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .shadow(10.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable { QuizOverlayManager.expand() },
            shape = RoundedCornerShape(12.dp),
            color = Color(0xF513161B),
            border = BorderStroke(1.dp, JarvisBorderStrong)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("Q", color = JarvisTextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        return
    }

    // VIEWER lampiran manual: gambar penuh + tombol tutup
    viewAttachment?.let { vIdx ->
        val b64v = manualThumbs.getOrNull(vIdx)
        if (b64v == null) {
            viewAttachment = null
        } else {
            val fullImg: ImageBitmap? = androidx.compose.runtime.remember(vIdx, b64v) {
                val bmp: android.graphics.Bitmap? = runCatching {
                    val bytes = android.util.Base64.decode(b64v, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
                bmp?.asImageBitmap()
            }
            androidx.compose.ui.window.Dialog(onDismissRequest = { viewAttachment = null }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xF209101F),
                    border = BorderStroke(1.2.dp, JarvisCyan)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Tangkapan " + (vIdx + 1) + "/" + manualThumbs.size,
                            color = JarvisTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        if (fullImg != null) {
                            Image(
                                bitmap = fullImg,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                            )
                        } else {
                            Text("(gambar tidak bisa dibuka)", color = JarvisTextSecondary, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "\u2190 Sebelumnya",
                                color = if (vIdx > 0) JarvisCyan else JarvisTextSecondary,
                                fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22FFFFFF))
                                    .clickable(enabled = vIdx > 0) { viewAttachment = vIdx - 1 }
                                    .padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text(
                                "Tutup",
                                color = Color.Black, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(JarvisCyan)
                                    .clickable { viewAttachment = null }
                                    .padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text(
                                "Berikutnya \u2192",
                                color = if (vIdx < manualThumbs.lastIndex) JarvisCyan else JarvisTextSecondary,
                                fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x22FFFFFF))
                                    .clickable(enabled = vIdx < manualThumbs.lastIndex) { viewAttachment = vIdx + 1 }
                                    .padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Mode penuh (compact card) ----
    var showDiagnostic by remember { mutableStateOf(false) }

    val accent = when (phase) {
        QuizPhase.ERROR -> JarvisRed
        QuizPhase.DONE -> JarvisEmerald
        QuizPhase.IDLE -> JarvisTextSecondary
        else -> JarvisCyan
    }
    val statusText = when (phase) {
        QuizPhase.IDLE -> "Siap memindai"
        QuizPhase.CAPTURING -> "Menangkap layar..."
        QuizPhase.OCR -> "Membaca teks (OCR)..."
        QuizPhase.AI -> "Menganalisis dengan AI..."
        QuizPhase.DONE -> "Selesai"
        QuizPhase.ERROR -> "Terjadi kendala"
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 264.dp, max = 312.dp)
            .shadow(14.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF513161B),
        border = BorderStroke(1.dp, JarvisBorderStrong)
    ) {
        val hudScrHdp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        val hudScroll = androidx.compose.foundation.rememberScrollState()
        Column(
            modifier = Modifier
                .padding(horizontal = 13.dp, vertical = 11.dp)
                .heightIn(max = (hudScrHdp * 0.88f).dp)
                .verticalScroll(hudScroll)
        ) {
            // Header: judul + status + minimize + close
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = if (isAnalyzing) JarvisCyan else accent
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Quiz Analyzer",
                        color = JarvisTextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        statusText,
                        color = JarvisTextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "−",
                    color = JarvisTextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { QuizOverlayManager.minimize() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Tutup",
                    tint = JarvisTextSecondary,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { QuizOverlayManager.setEnabled(false) }
                        .padding(5.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            if (!captureActive) {
                val diedStr = if (captureDiedAt > 0L)
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(captureDiedAt)) else ""
                Text(
                    "Izin tangkap layar nonaktif" + (if (diedStr.isNotEmpty()) " ($diedStr)" else "") +
                        " — ketuk untuk mengaktifkan ulang",
                    color = JarvisRed,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisRed.copy(alpha = 0.12f))
                        .border(1.dp, JarvisRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .clickable { QuizOverlayManager.requestProjectionFix() }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            // Unified segmented mode control (Standar / Lanjutan / Manual)
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = JarvisSurfaceVariant,
                border = BorderStroke(1.dp, JarvisBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val modes = listOf(
                        Triple("Standar", !proMode && !manualMode) { QuizAnalyzer.setProMode(false) },
                        Triple("Pro", proMode && !manualMode) { QuizAnalyzer.setProMode(true) },
                        Triple("Manual", manualMode) { QuizAnalyzer.setManualMode(true) }
                    )
                    modes.forEach { (label, selected, onClick) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (selected) JarvisSurfaceElevated else Color.Transparent)
                                .then(
                                    if (selected) Modifier.border(1.dp, JarvisBorderStrong, RoundedCornerShape(7.dp))
                                    else Modifier
                                )
                                .clickable(onClick = onClick)
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) JarvisTextPrimary else JarvisTextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Auto Analyze
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Analisis otomatis", color = JarvisTextPrimary, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoOn,
                    onCheckedChange = { QuizAnalyzer.setAuto(it) },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = JarvisCyan,
                        uncheckedThumbColor = JarvisTextSecondary,
                        uncheckedTrackColor = JarvisSurfaceVariant
                    )
                )
            }

            Spacer(Modifier.height(4.dp))

            // Auto Submit
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Kirim jawaban otomatis", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Text("Pilih opsi dan tekan tombol lanjut", color = JarvisTextSecondary, fontSize = 9.5.sp)
                }
                Switch(
                    checked = autoSubmitOn,
                    onCheckedChange = { QuizAnalyzer.setAutoSubmit(it) },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = JarvisCyan,
                        uncheckedThumbColor = JarvisTextSecondary,
                        uncheckedTrackColor = JarvisSurfaceVariant
                    )
                )
            }

            if (proMode && !manualMode) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Jawab beruntun hingga selesai", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    if (autoAnswerOn) {
                        Text(
                            "Aktif · $autoAnswerProgress soal diproses",
                            color = JarvisCyan,
                            fontSize = 9.5.sp
                        )
                    }
                }
                Switch(
                    checked = autoAnswerOn,
                    onCheckedChange = { QuizAnalyzer.setAutoAnswerLoop(it) },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = JarvisCyan,
                        uncheckedThumbColor = JarvisTextSecondary,
                        uncheckedTrackColor = JarvisSurfaceVariant
                    )
                )
            }
            } // akhir Auto Jawab PRO

            // METODE TANGKAP (PRO): pilih salah satu / serahkan ke AI (Adaptif)
            if (proMode && !manualMode) {
                Text("Metode tangkap", color = JarvisTextPrimary, fontSize = 11.5.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        0 to "Adaptif",
                        1 to "Screenshot",
                        2 to "ScreenRec"
                    ).forEach { (v, label) ->
                        val selected = captureMethod == v
                        Text(
                            label,
                            color = if (selected) Color.Black else JarvisTextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                .clickable { QuizAnalyzer.setCaptureMethod(v) }
                                .padding(vertical = 7.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                Text(
                    "Otomatis adaptif: AI yang mengontrol - halaman 1 via video; soal terpotong WAJIB digulir ke bawah, tiap halaman jadi frame utk AI (Android 10+). Screenshot = jalur klasik.",
                    color = JarvisTextSecondary,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
            }

            if (proMode && !manualMode) {
                // MODE ADVANCE: setelan lanjutan disembunyikan agar HUD tetap ringkas
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { QuizAnalyzer.setAdvancedShown(!showAdvanced) }
                        .padding(vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Mode advance", color = JarvisTextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (showAdvanced) " ▲" else " ▼", color = JarvisTextSecondary, fontSize = 10.sp)
                }
                if (showAdvanced) {
                // Delay auto analyze: chip preset — TAP SAJA. Field ketik mustahil dipakai di
                // jendela overlay (window FLAG_NOT_FOCUSABLE -> keyboard tidak bisa muncul).
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Delay auto analyze", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(500L, 1000L, 2000L, 5000L).forEach { ms ->
                            val selected = delayMs == ms
                            Text(
                                text = if (ms >= 1000L) (ms / 1000).toString() + "s" else ms.toString() + "ms",
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setDelayMs(ms) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    // Atur bebas: -/+ 100ms. Batas aman ditegakkan di setDelayMs (300ms-10s):
                    // di bawah 300ms loop cuma bikin panas/boros baterai tanpa manfaat.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "-",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(JarvisCyan)
                                .clickable { QuizAnalyzer.setDelayMs(delayMs - 100L) }
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                        Text(delayMs.toString() + " ms", color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "+",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(JarvisCyan)
                                .clickable { QuizAnalyzer.setDelayMs(delayMs + 100L) }
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                        Text("(bebas 300ms-10s)", color = JarvisTextSecondary, fontSize = 9.sp)
                    }
                }

                // Multi-capture soal panjang: OTOMATIS (AI atur) atau PILIHAN (user tentukan)
                Spacer(Modifier.height(6.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Multi-capture soal panjang", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            true to "Otomatis (AI)",
                            false to "Pilihan"
                        ).forEach { (v, label) ->
                            val selected = captureModeAuto == v
                            Text(
                                text = label,
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setCaptureModeAuto(v) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (!captureModeAuto) {
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "-",
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(JarvisCyan)
                                    .clickable { QuizAnalyzer.setManualExtraCount(manualExtraCount - 1) }
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                            Text("x" + manualExtraCount + " capture tambahan", color = JarvisCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "+",
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(JarvisCyan)
                                    .clickable { QuizAnalyzer.setManualExtraCount(manualExtraCount + 1) }
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                            )
                            Text("(1-100)", color = JarvisTextSecondary, fontSize = 9.sp)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "-",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(JarvisCyan)
                                .clickable { QuizAnalyzer.setScrollOverlap(scrollOverlap - 5) }
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                        Text("Overlap " + scrollOverlap + "%", color = JarvisCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "+",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(JarvisCyan)
                                .clickable { QuizAnalyzer.setScrollOverlap(scrollOverlap + 5) }
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                        Text("geser " + (100 - scrollOverlap) + "% layar/langkah (40-90%)", color = JarvisTextSecondary, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("Gulir saat multi-capture", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            0 to "Otomatis",
                            1 to "1 jari",
                            2 to "2 jari",
                            3 to "Roda StarDesk"
                        ).forEach { (v, label) ->
                            val selected = scrollFingers == v
                            Text(
                                text = label,
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setScrollFingers(v) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Text(
                        "Roda StarDesk = drag pelan di widget roda (kiri/kanan, dideteksi otomatis) - PALING andal utk StarDesk. Otomatis = Roda StarDesk saat remote terdeteksi",
                        color = JarvisTextSecondary,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Posisi widget roda StarDesk: deteksi otomatis / manual kiri-kanan
                    Spacer(Modifier.height(6.dp))
                    Text("Posisi roda StarDesk", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            0 to "Otomatis (deteksi)",
                            1 to "Kiri",
                            2 to "Kanan"
                        ).forEach { (v, label) ->
                            val selected = wheelSide == v
                            Text(
                                text = label,
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setWheelSide(v) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Text(
                        "AI mencari roda via UI hierarchy StarDesk (tanpa screenshot) + pindai screenshot sbg cadangan, lalu drag tepat di sana; bila roda tak terlihat: kiri saat landscape, kanan saat portrait.",
                        color = JarvisTextSecondary,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Intensitas gulir otomatis (0 = MATI) — dipakai semua jalur gulir
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(0 to "Off", 30 to "30", 50 to "50", 70 to "70").forEach { (v, label) ->
                            val selected = scrollAmount == v
                            Text(
                                label,
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisAmber else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setScrollAmount(v) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        Text(
                            "Gulir otomatis (0 = mati)",
                            color = JarvisTextSecondary,
                            fontSize = 9.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // SOP langkah 1: mulai dari atas saat Analyze
                    Spacer(Modifier.height(6.dp))
                    Text("Scroll otomatis saat Analyze", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            true to "Mulai dari atas (lambat, aman)",
                            false to "Tanpa scroll (cepat)"
                        ).forEach { (v, label) ->
                            val selected = scrollFromTop == v
                            Text(
                                text = label,
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setScrollToTopOnAnalyze(v) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            true to "Auto Scan Penuh bila tak yakin (lambat)",
                            false to "Tanpa auto Scan Penuh (cepat)"
                        ).forEach { (v, label) ->
                            val selected = autoSweepOn == v
                            Text(
                                text = label,
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setAutoSweep(v) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Batas soal utk Auto Jawab
                    Spacer(Modifier.height(6.dp))
                    Text("Batas soal (Auto Jawab)", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            true to "Otomatis (sampai selesai)",
                            false to "Isi sendiri"
                        ).forEach { (v, label) ->
                            val selected = answerLimitAuto == v
                            Text(
                                text = label,
                                color = if (selected) Color.Black else JarvisTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) JarvisCyan else Color(0x22FFFFFF))
                                    .clickable { QuizAnalyzer.setAnswerLimitAuto(v) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (!answerLimitAuto) {
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "-",
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(JarvisCyan)
                                    .clickable { QuizAnalyzer.setAnswerLimitN(answerLimitN - 1) }
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                            Text(answerLimitN.toString() + " soal", color = JarvisCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "+",
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(JarvisCyan)
                                    .clickable { QuizAnalyzer.setAnswerLimitN(answerLimitN + 1) }
                                    .padding(horizontal = 10.dp, vertical = 2.dp)
                            )
                            Text("(1-100)", color = JarvisTextSecondary, fontSize = 9.sp)
                        }
                    }
                }
                } // akhir mode advance

                Spacer(Modifier.height(6.dp))

            } // akhir seksi PRO

            if (!proMode && !manualMode) {
                // MODE DEFAULT: panel bersih — hanya Auto Jawab (Auto Submit & auto-
                // minimize tetap berlaku dari setelannya); satu capture per Analyze.
                // Gulir otomatis: 0 = MATI (tak pernah scroll), berlaku juga di PRO
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(0 to "Off", 30 to "30", 50 to "50", 70 to "70").forEach { (v, label) ->
                        val selected = scrollAmount == v
                        Text(
                            label,
                            color = if (selected) Color.Black else JarvisTextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) JarvisAmber else Color(0x22FFFFFF))
                                .clickable { QuizAnalyzer.setScrollAmount(v) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    Text(
                        "Gulir otomatis (0 = mati)",
                        color = JarvisTextSecondary,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto Jawab semua soal", color = JarvisTextPrimary, fontSize = 11.5.sp)
                        if (autoAnswerOn) {
                            Text(
                                "\ud83e\udd16 berjalan - " + autoAnswerProgress + " soal dikerjakan",
                                color = JarvisAmber,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                    Switch(
                        checked = autoAnswerOn,
                        onCheckedChange = { QuizAnalyzer.setAutoAnswerLoop(it) },
                        modifier = Modifier.height(24.dp),
                        colors = SwitchDefaults.colors(checkedTrackColor = JarvisCyan)
                    )
                }
                Text(
                    "Mode default: satu scan + Auto Submit jawaban (bila Auto Submit ON) - " +
                        "tanpa gulir/sentuhan otomatis. Untuk multi-capture, Scan Penuh, gulir " +
                        "& setelan lanjut: pilih mode PRO.",
                    color = JarvisTextSecondary,
                    fontSize = 9.5.sp
                )
            }

            if (manualMode) {
                // MODE MANUAL: tangkap layar + lampiran + analisis
                Button(
                    onClick = { QuizAnalyzer.addManualCapture() },
                    enabled = !isAnalyzing,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisSurfaceElevated, contentColor = JarvisTextPrimary),
                    border = BorderStroke(1.dp, JarvisBorderStrong),
                    modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    Text("Tangkap Layar", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "$manualCaptures tangkapan terlampir",
                        color = JarvisTextPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Hapus akhir",
                        color = if (manualCaptures > 0) JarvisTextPrimary else JarvisTextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(JarvisSurfaceVariant)
                            .clickable(enabled = manualCaptures > 0) { QuizAnalyzer.removeManualCapture(manualCaptures - 1) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Reset",
                        color = if (manualCaptures > 0) JarvisRed else JarvisTextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(JarvisSurfaceVariant)
                            .clickable(enabled = manualCaptures > 0) { QuizAnalyzer.clearManualCaptures() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
                if (manualThumbs.isNotEmpty()) {
                    // PRATINJAU lampiran: tap thumbnail utk melihat gambar penuh
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        manualThumbs.forEachIndexed { idx, b64 ->
                            val img: ImageBitmap? = androidx.compose.runtime.remember(idx, b64) {
                                val bmp: android.graphics.Bitmap? = runCatching {
                                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }.getOrNull()
                                bmp?.asImageBitmap()
                            }
                            if (img != null) {
                                Image(
                                    bitmap = img,
                                    contentDescription = "Tangkapan " + (idx + 1),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, if (idx == manualThumbs.lastIndex) JarvisCyan else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                                        .clickable { viewAttachment = idx }
                                )
                            }
                        }
                    }
                    Text(
                        "Tap gambar utk melihat penuh; gambar terbaru di kanan.",
                        color = JarvisTextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "Screenshot = HUD mengecil otomatis lalu gambar terlampir di sini. Lampirkan beberapa halaman, lalu tap Analyze.",
                    color = JarvisTextSecondary,
                    fontSize = 9.5.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto Jawab (klik jawaban)", color = JarvisTextPrimary, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoAnswerOn,
                        onCheckedChange = { QuizAnalyzer.setAutoAnswerLoop(it) },
                        modifier = Modifier.height(24.dp),
                        colors = SwitchDefaults.colors(checkedTrackColor = JarvisCyan)
                    )
                }
                Text(
                    "Mode manual: hanya Screenshot, lampiran, Analyze & Auto Jawab.",
                    color = JarvisTextSecondary,
                    fontSize = 9.5.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            // Primary Action: Analisis / Hentikan
            Button(
                onClick = {
                    if (isAnalyzing) QuizAnalyzer.cancelAnalysis()
                    else if (manualMode) QuizAnalyzer.sendManualCaptures()
                    else QuizAnalyzer.analyzeOnce()
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAnalyzing) JarvisRed else JarvisCyan,
                    contentColor = if (isAnalyzing) Color.White else JarvisOnPrimary
                ),
                modifier = Modifier.fillMaxWidth().height(38.dp)
            ) {
                Text(
                    if (isAnalyzing) "Hentikan Analisis" else "Analisis Layar",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (sweeping) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Hentikan Pindai Penuh",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisRed)
                        .clickable { QuizAnalyzer.stopSweep() }
                        .padding(vertical = 8.dp)
                )
            } else if (proMode && !manualMode) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pindai Penuh (Gulir Otomatis)",
                    color = JarvisTextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisSurfaceVariant)
                        .border(1.dp, JarvisBorderStrong, RoundedCornerShape(8.dp))
                        .clickable { QuizAnalyzer.startSweepAnalyze() }
                        .padding(vertical = 8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            if (!manualMode) {
                // Multi-page manual capture controls — fixed padding & sizing so text never clips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = { QuizAnalyzer.addManualCapture() },
                        enabled = !isAnalyzing,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                        border = BorderStroke(1.dp, JarvisBorderStrong),
                        modifier = Modifier.weight(1.1f).height(34.dp)
                    ) {
                        Text(
                            text = "+ Tambah ($manualCaptures)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { QuizAnalyzer.sendManualCaptures() },
                        enabled = manualCaptures > 0 && !isAnalyzing,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (manualCaptures > 0) JarvisCyan else JarvisTextTertiary
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (manualCaptures > 0) JarvisCyan.copy(alpha = 0.5f) else JarvisBorder
                        ),
                        modifier = Modifier.weight(1f).height(34.dp)
                    ) {
                        Text(
                            text = "Kirim Gabungan",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    if (manualCaptures > 0) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Hapus lampiran",
                            tint = JarvisTextSecondary,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(JarvisSurfaceVariant)
                                .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp))
                                .clickable { QuizAnalyzer.clearManualCaptures() }
                                .padding(8.dp)
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Clean scroll & PageUp/PageDown controls — zero truncated "(hijau = PageUp..." text
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Gulir", color = JarvisTextSecondary, fontSize = 10.5.sp)
                    Spacer(Modifier.width(2.dp))
                    listOf(
                        "↑" to { QuizAnalyzer.remoteScroll(up = true) },
                        "↓" to { QuizAnalyzer.remoteScroll(up = false) },
                        "PgUp" to { QuizAnalyzer.scrollKey("pageup") },
                        "PgDn" to { QuizAnalyzer.scrollKey("pagedown") }
                    ).forEach { (lbl, act) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(JarvisSurfaceVariant)
                                .border(1.dp, JarvisBorder, RoundedCornerShape(7.dp))
                                .clickable(onClick = act)
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = lbl,
                                color = JarvisTextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Hasil jawaban
            val result = answer
            when {
                result != null && !result.isUncertain -> {
                    if (result.isFillIn) {
                        // Soal isian: tampilkan jawaban teksnya langsung
                        Text(
                            result.answerText,
                            color = JarvisEmerald,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("Jawaban isian", color = JarvisTextSecondary, fontSize = 9.5.sp)
                    } else {
                        Text(
                            "Answer: ${result.answer.ifBlank { "?" }}",
                            color = JarvisEmerald,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        if (result.answerText.isNotBlank()) {
                            Text(result.answerText, color = JarvisTextPrimary, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Confidence: ${(result.confidence * 100).toInt()}%", color = JarvisCyan, fontSize = 10.5.sp)
                    if (submitInfo != null) {
                        Text("▸ $submitInfo", color = JarvisAmber, fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    // Menu PENJELASAN: tap untuk membuka; teks panjang bisa digulir
                    if (result.explanation.isNotBlank()) {
                        var showExpl by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showExpl = !showExpl }
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(
                                if (showExpl) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = JarvisCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Penjelasan", color = JarvisCyan, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (showExpl) {
                            Text(
                                result.explanation,
                                color = JarvisTextSecondary,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
                result != null && result.isUncertain -> {
                    Text("Unable to determine answer", color = JarvisAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (result.explanation.isNotBlank()) {
                        Text(result.explanation, color = JarvisTextSecondary, fontSize = 10.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                lastError != null -> {
                    Text(lastError ?: "", color = JarvisRed, fontSize = 10.5.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                else -> {
                    Text("Tekan Analyze untuk memindai soal di layar.", color = JarvisTextSecondary, fontSize = 10.5.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            // ▼ Diagnostic
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showDiagnostic = !showDiagnostic }
                    .padding(vertical = 2.dp)
            ) {
                Icon(
                    if (showDiagnostic) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Diagnostic", color = JarvisTextSecondary, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
            }
            if (showDiagnostic) {
                val diag = diagState
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x14000000), RoundedCornerShape(6.dp))
                        .padding(6.dp)
                ) {
                    capturePreview?.let { pv ->
                        Image(
                            bitmap = pv,
                            contentDescription = "Pratinjau tangkapan layar",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 110.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, JarvisCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(3.dp))
                        Text("Pratinjau = apa yang dilihat analyzer", color = JarvisTextSecondary, fontSize = 9.sp)
                        Spacer(Modifier.height(3.dp))
                    }
                    diag.toLines().forEach { line ->
                        Text(line, color = JarvisTextSecondary, fontSize = 9.5.sp, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}
