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
    val captureActive by com.example.service.ScreenshotManager.isMediaProjectionActive.collectAsState()
    val captureDiedAt by com.example.service.ScreenshotManager.projectionDiedAt.collectAsState()
    val sweeping by QuizAnalyzer.sweeping.collectAsState()
    // Status Mode Advance PERSISTEN: dibuka kemarin? hari ini tetap terbuka.
    val showAdvanced by QuizAnalyzer.advancedShown.collectAsState()

    if (minimized) {
        // ---- Mode minimize: bulatan kecil ----
        Surface(
            modifier = Modifier
                .size(44.dp)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .clickable { QuizOverlayManager.expand() },
            shape = CircleShape,
            color = Color(0xF209101F),
            border = BorderStroke(1.4.dp, JarvisCyan)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("Q", color = JarvisCyan, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
        }
        return
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
        QuizPhase.IDLE -> "Ready"
        QuizPhase.CAPTURING -> "Capturing screen..."
        QuizPhase.OCR -> "Reading screen (OCR)..."
        QuizPhase.AI -> "Analyzing with AI..."
        QuizPhase.DONE -> "Done"
        QuizPhase.ERROR -> "Error"
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 250.dp, max = 300.dp)
            .shadow(16.dp, RoundedCornerShape(20.dp), ambientColor = JarvisCyan, spotColor = JarvisCyan)
            .clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xF209101F),
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(listOf(JarvisCyan.copy(alpha = 0.9f), JarvisTeal.copy(alpha = 0.5f), JarvisCyan.copy(alpha = 0.8f)))
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Header: judul + minimize + close
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "AI Quiz Analyzer",
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "−",
                    color = JarvisTextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { QuizOverlayManager.minimize() }
                        .padding(horizontal = 6.dp)
                )
                Text(
                    "×",
                    color = JarvisRed,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { QuizOverlayManager.setEnabled(false) }
                        .padding(horizontal = 6.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Status dot + teks
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = if (isAnalyzing) JarvisCyan else accent
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(statusText, color = JarvisTextSecondary, fontSize = 11.sp)
            }

            Spacer(Modifier.height(8.dp))

            if (!captureActive) {
                val diedStr = if (captureDiedAt > 0L)
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(captureDiedAt)) else ""
                Text(
                    "\u26a0 Sesi tangkap layar MATI" + (if (diedStr.isNotEmpty()) " ($diedStr)" else "") +
                        " - ketuk di sini untuk izin ulang",
                    color = JarvisRed,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22FF5252))
                        .clickable { QuizOverlayManager.requestProjectionFix() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            // Mode analyzer: PRO (lengkap) / DEFAULT (bersih)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "PRO",
                    color = if (proMode) Color.Black else JarvisTextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (proMode) JarvisCyan else Color(0x22FFFFFF))
                        .clickable { QuizAnalyzer.setProMode(true) }
                        .padding(vertical = 7.dp)
                )
                Text(
                    "DEFAULT",
                    color = if (!proMode) Color.Black else JarvisTextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (!proMode) JarvisEmerald else Color(0x22FFFFFF))
                        .clickable { QuizAnalyzer.setProMode(false) }
                        .padding(vertical = 7.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Auto Analyze
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Auto Analyze", color = JarvisTextPrimary, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoOn,
                    onCheckedChange = { QuizAnalyzer.setAuto(it) },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = JarvisCyan)
                )
            }

            // Auto Submit (opsional — otomatis ketuk opsi & tombol kirim)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Submit", color = JarvisTextPrimary, fontSize = 11.5.sp)
                    Text("Ketuk jawaban & tombol kirim otomatis", color = JarvisTextSecondary, fontSize = 9.sp)
                }
                Switch(
                    checked = autoSubmitOn,
                    onCheckedChange = { QuizAnalyzer.setAutoSubmit(it) },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = JarvisAmber)
                )
            }

            // Auto Jawab: loop analisis+jawab+submit sampai selesai / batas soal (atur di Mode advance)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto Jawab (sampai selesai)", color = JarvisTextPrimary, fontSize = 11.5.sp)
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

            if (proMode) {
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

            if (!proMode) {
                // MODE DEFAULT: panel bersih — hanya Auto Jawab (Auto Submit & auto-
                // minimize tetap berlaku dari setelannya); satu capture per Analyze.
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

            // Tombol Analyze / STOP (saat proses berjalan - tidak ada lagi kondisi stuck)
            Button(
                onClick = { if (isAnalyzing) QuizAnalyzer.cancelAnalysis() else QuizAnalyzer.analyzeOnce() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAnalyzing) JarvisRed else JarvisCyan,
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                if (isAnalyzing) {
                    Text("\u23f9 Stop (membatalkan analisis)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Analyze", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Fallback sweep: scroll terus dari atas ke bawah sampai mentok; semua frame dianalisis
            if (sweeping) {
                Text(
                    "\u23f9 Stop sweep (sedang memindai...)",
                    color = Color.Black,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisRed)
                        .clickable { QuizAnalyzer.stopSweep() }
                        .padding(vertical = 7.dp)
                )
            } else {
                Text(
                    "\u2913 Scan Penuh (scroll sampai mentok)",
                    color = JarvisTextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22FFFFFF))
                        .clickable { QuizAnalyzer.startSweepAnalyze() }
                        .padding(vertical = 7.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Tangkapan manual utk AI: tap 📷 sebanyak apa pun (scroll manual di antaranya),
            // lalu Kirim = analisis gabungan semua tangkapan
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { QuizAnalyzer.addManualCapture() },
                    enabled = !isAnalyzing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3348), contentColor = JarvisTextPrimary),
                    modifier = Modifier.weight(1f).height(30.dp)
                ) {
                    Text("\ud83d\udcf7 Tambah (" + manualCaptures + ")", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { QuizAnalyzer.sendManualCaptures() },
                    enabled = manualCaptures > 0 && !isAnalyzing,
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisEmerald, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).height(30.dp)
                ) {
                    Text("Kirim ke AI", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
                // Hapus buffer tangkapan manual (sebelumnya tidak ada tombolnya)
                Text(
                    "\u2715",
                    color = if (manualCaptures > 0) JarvisRed else JarvisTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (manualCaptures > 0) Color(0x33FF5555) else Color(0x11FFFFFF))
                        .clickable(enabled = manualCaptures > 0) { QuizAnalyzer.clearManualCaptures() }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
            // Kontrol gulir manual: posisikan halaman (PC via StarDesk = roda mouse 2 jari)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Gulir:", color = JarvisTextSecondary, fontSize = 10.5.sp)
                Text(
                    "\u25b2",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisCyan)
                        .clickable { QuizAnalyzer.remoteScroll(up = true) }
                        .padding(horizontal = 14.dp, vertical = 3.dp)
                )
                Text(
                    "\u25bc",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisCyan)
                        .clickable { QuizAnalyzer.remoteScroll(up = false) }
                        .padding(horizontal = 14.dp, vertical = 3.dp)
                )
                // Jalur PASTI: PageUp/PageDown dikirim via Shizuku -> StarDesk meneruskan
                // ke PC sbg tombol keyboard -> halaman PC tergulung walau gesture 2 jari tak mempan
                Text(
                    "\u21de",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisEmerald)
                        .clickable { QuizAnalyzer.scrollKey("pageup") }
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                )
                Text(
                    "\u21df",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(JarvisEmerald)
                        .clickable { QuizAnalyzer.scrollKey("pagedown") }
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                )
                Text(
                    "(hijau = PageUp/Dn via Shizuku)",
                    color = JarvisTextSecondary,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "\ud83d\udcf7 = simpan layar sekarang (scroll dulu bila perlu); Kirim = analisis gabungan",
                color = JarvisTextSecondary,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

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
