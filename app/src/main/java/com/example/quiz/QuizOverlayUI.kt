package com.example.quiz

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    var delayText by remember(delayMs) { mutableStateOf(delayMs.toString()) }
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

            // Delay (ms)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Delay (ms)", color = JarvisTextPrimary, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = delayText,
                    onValueChange = { v ->
                        delayText = v.filter { it.isDigit() }.take(5)
                        delayText.toLongOrNull()?.let { QuizAnalyzer.setDelayMs(it) }
                    },
                    enabled = !autoOn,
                    textStyle = LocalTextStyle.current.copy(color = JarvisTextPrimary, fontSize = 11.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(92.dp).height(46.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // Tombol Analyze
            Button(
                onClick = { QuizAnalyzer.analyzeOnce() },
                enabled = !isAnalyzing,
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Analyzing...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Analyze", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Hasil jawaban
            val result = answer
            when {
                result != null && !result.isUncertain -> {
                    Text(
                        "Answer: ${result.answer.ifBlank { "?" }}",
                        color = JarvisEmerald,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    if (result.answerText.isNotBlank()) {
                        Text(result.answerText, color = JarvisTextPrimary, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Confidence: ${(result.confidence * 100).toInt()}%", color = JarvisCyan, fontSize = 10.5.sp)
                    if (submitInfo != null) {
                        Text("▸ $submitInfo", color = JarvisAmber, fontSize = 9.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    diag.toLines().forEach { line ->
                        Text(line, color = JarvisTextSecondary, fontSize = 9.5.sp, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}
