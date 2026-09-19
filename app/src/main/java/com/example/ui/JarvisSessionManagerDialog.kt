package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ChatSession
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JarvisSessionManagerDialog(
    sessions: List<ChatSession>,
    currentSessionId: String,
    onSelectSession: (String) -> Unit,
    onCreateNewSession: (String?) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onClearCurrentSession: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var sessionToRename by remember { mutableStateOf<ChatSession?>(null) }
    var sessionToDelete by remember { mutableStateOf<ChatSession?>(null) }
    var showNewSessionInput by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }

    val dateFormatter = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

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
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Kelola Sesi Chat",
                        color = JarvisTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = JarvisCyan.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${sessions.size} Sesi",
                        color = JarvisCyan,
                        fontSize = 11.sp,
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
                    .heightIn(max = 420.dp)
            ) {
                // Button to Create New Session
                Button(
                    onClick = {
                        onCreateNewSession(null)
                        Toast.makeText(context, "Sesi baru dibuat", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("session_create_new_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JarvisCyan,
                        contentColor = JarvisBackground
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("+ Buat Sesi Baru", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Daftar Percakapan:",
                    color = JarvisTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Belum ada riwayat sesi.",
                            color = JarvisTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            val isCurrent = session.id == currentSessionId
                            val updatedTimeStr = remember(session.updatedAt) {
                                dateFormatter.format(Date(session.updatedAt))
                            }
                            val messageCount = session.messages.count { it.sender != com.example.model.ChatSender.SYSTEM }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCurrent) JarvisSurfaceVariant else JarvisSurface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isCurrent) JarvisCyan else JarvisBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectSession(session.id)
                                        onDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Active indicator icon
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (isCurrent) JarvisCyan else Color.Transparent)
                                            .border(1.dp, if (isCurrent) JarvisCyan else JarvisTextSecondary.copy(alpha = 0.5f), CircleShape)
                                    )

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = session.title,
                                                color = if (isCurrent) JarvisCyan else JarvisTextPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (isCurrent) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = JarvisCyan.copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = "AKTIF",
                                                        color = JarvisCyan,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "$messageCount pesan",
                                                color = JarvisTextSecondary,
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                text = "•",
                                                color = JarvisTextSecondary,
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                text = updatedTimeStr,
                                                color = JarvisTextSecondary,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    // Rename session button
                                    IconButton(
                                        onClick = { sessionToRename = session },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Ubah Nama Sesi",
                                            tint = JarvisTextSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    // Delete session button
                                    IconButton(
                                        onClick = { sessionToDelete = session },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Hapus Sesi",
                                            tint = JarvisRed.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Clear current session messages button
                OutlinedButton(
                    onClick = {
                        onClearCurrentSession()
                        Toast.makeText(context, "Pesan pada sesi ini telah dibersihkan", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisAmber)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Bersihkan Chat Sesi Aktif", fontSize = 11.sp)
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

    // Rename Session Dialog
    if (sessionToRename != null) {
        val s = sessionToRename!!
        var editTitle by remember { mutableStateOf(s.title) }

        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            title = {
                Text(
                    "Ubah Nama Sesi",
                    color = JarvisTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "Masukkan nama baru untuk sesi ini:",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editTitle.isNotBlank()) {
                            onRenameSession(s.id, editTitle)
                            sessionToRename = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
                ) {
                    Text("Simpan", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text("Batal", color = JarvisTextSecondary)
                }
            },
            containerColor = JarvisSurface
        )
    }

    // Confirm Delete Session Dialog
    if (sessionToDelete != null) {
        val s = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = {
                Text(
                    "Hapus Sesi Chat?",
                    color = JarvisRed,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Apakah Anda yakin ingin menghapus sesi \"${s.title}\"? Semua riwayat percakapan dalam sesi ini akan dihapus permanen.",
                    color = JarvisTextPrimary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSession(s.id)
                        sessionToDelete = null
                        Toast.makeText(context, "Sesi berhasil dihapus", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisRed, contentColor = Color.White)
                ) {
                    Text("Hapus", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Batal", color = JarvisTextSecondary)
                }
            },
            containerColor = JarvisSurface
        )
    }
}
