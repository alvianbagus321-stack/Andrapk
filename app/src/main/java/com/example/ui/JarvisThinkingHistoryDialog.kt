package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

data class ThinkingHistoryEntry(
    val userPrompt: String,
    val thinkingTrace: String,
    val actionTool: String?,
    val timestamp: Long
)

@Composable
fun JarvisThinkingHistoryDialog(
    messages: List<ChatMessage>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Aggregate thoughts paired with preceding user prompt
    val thinkingEntries = remember(messages) {
        val entries = mutableListOf<ThinkingHistoryEntry>()
        var lastUserPrompt = "Instruksi Awal"

        for (msg in messages) {
            if (msg.sender == ChatSender.USER) {
                lastUserPrompt = msg.text
            } else if (msg.sender == ChatSender.AI && !msg.thinkingProcess.isNullOrBlank()) {
                entries.add(
                    ThinkingHistoryEntry(
                        userPrompt = lastUserPrompt,
                        thinkingTrace = msg.thinkingProcess,
                        actionTool = msg.actionToolName,
                        timestamp = msg.timestamp
                    )
                )
            }
        }
        entries
    }

    fun copyToClipboard(text: String, label: String = "Proses Berpikir Asisten") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Proses berpikir disalin ke clipboard", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = JarvisAmber.copy(alpha = 0.2f),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = JarvisAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Riwayat Berpikir AI",
                            color = JarvisTextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Thinking Process & Reasoning Trace",
                            color = JarvisCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = JarvisAmber.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${thinkingEntries.size} Thought",
                        color = JarvisAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
            ) {
                Text(
                    text = "Daftar proses analisa penalaran internal yang dilakukan oleh asisten sebelum menjawab atau mengeksekusi tool di HP:",
                    color = JarvisTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (thinkingEntries.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = JarvisSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = JarvisTextSecondary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Belum Ada Riwayat Berpikir",
                                color = JarvisTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Kirim pertanyaan atau perintah otomasi HP ke AI untuk melihat proses berpikir langkah demi langkah secara transparan.",
                                color = JarvisTextSecondary,
                                fontSize = 11.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(thinkingEntries) { index, entry ->
                            val timeStr = remember(entry.timestamp) {
                                timeFormatter.format(Date(entry.timestamp))
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = JarvisSurface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAmber.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    // Header: Step Number & Time
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = JarvisAmber.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = "#${index + 1} REASONING",
                                                    color = JarvisAmber,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = timeStr,
                                                color = JarvisTextSecondary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        IconButton(
                                            onClick = { copyToClipboard(entry.thinkingTrace) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Salin Proses Berpikir",
                                                tint = JarvisCyan,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Context Prompt
                                    Text(
                                        text = "Instruksi: \"${entry.userPrompt.take(60)}${if (entry.userPrompt.length > 60) "..." else ""}\"",
                                        color = JarvisCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )

                                    if (entry.actionTool != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = JarvisEmerald.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = "⚡ Aksi Terpilih: ${entry.actionTool}",
                                                color = JarvisEmerald,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // The Thought Content in sci-fi terminal box
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = JarvisBackground,
                                        border = androidx.compose.foundation.BorderStroke(
                                            0.5.dp,
                                            JarvisBorder
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = entry.thinkingTrace,
                                            color = JarvisTextPrimary.copy(alpha = 0.9f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 16.sp,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Tutup", color = JarvisCyan)
            }
        },
        containerColor = JarvisSurface
    )
}
