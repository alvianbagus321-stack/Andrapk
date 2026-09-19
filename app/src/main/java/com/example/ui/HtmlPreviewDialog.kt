package com.example.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*

/**
 * Helper to extract HTML code blocks from chat text.
 */
object HtmlCodeExtractor {
    fun extractHtml(text: String): String? {
        // Look for ```html ... ```
        val markdownRegex = Regex("```(?:html|xml)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val matches = markdownRegex.findAll(text)
        for (match in matches) {
            val code = match.groupValues[1].trim()
            if (code.contains("<html", ignoreCase = true) ||
                code.contains("<!DOCTYPE", ignoreCase = true) ||
                code.contains("<div", ignoreCase = true) ||
                code.contains("<style", ignoreCase = true) ||
                code.contains("<body", ignoreCase = true) ||
                code.contains("<h1", ignoreCase = true) ||
                code.contains("<p>", ignoreCase = true)
            ) {
                return code
            }
        }

        // Look for raw <!DOCTYPE html> or <html>
        if (text.contains("<!DOCTYPE html>", ignoreCase = true) || text.contains("<html", ignoreCase = true)) {
            val startIdx = text.indexOf("<!DOCTYPE", ignoreCase = true).let { if (it >= 0) it else text.indexOf("<html", ignoreCase = true) }
            val endIdx = text.lastIndexOf("</html>", ignoreCase = true)
            if (startIdx >= 0) {
                return if (endIdx > startIdx) {
                    text.substring(startIdx, endIdx + 7).trim()
                } else {
                    text.substring(startIdx).trim()
                }
            }
        }

        return null
    }
}

/**
 * Preview banner card shown below a chat message that contains HTML.
 */
@Composable
fun HtmlPreviewBanner(
    htmlCode: String,
    onOpenPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        color = JarvisSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = JarvisCyan.copy(alpha = 0.15f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = "HTML Web",
                        tint = JarvisCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "HTML Web Code Terdeteksi",
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${htmlCode.length} karakter • Siap di-render secara visual",
                    color = JarvisTextSecondary,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Button(
                onClick = onOpenPreview,
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan,
                    contentColor = JarvisBackground
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Preview Live",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Fullscreen Interactive Dialog showing Live HTML/CSS/JS Preview with Tab switcher.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreviewDialog(
    htmlCode: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) } // 0: Live View, 1: Source Code
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = JarvisSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JarmonTopBarBg)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "HTML Live Visual Preview",
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    // Refresh Web View
                    IconButton(
                        onClick = {
                            webViewInstance?.loadDataWithBaseURL(null, htmlCode, "text/html", "UTF-8", null)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reload",
                            tint = JarvisCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Copy Code
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("HTML Code", htmlCode))
                            Toast.makeText(context, "Kode HTML disalin ke clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Close Dialog
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // View Tabs (Live Web Preview vs Raw Source)
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = JarvisSurfaceVariant,
                    contentColor = JarvisCyan
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Tampilan Web Live", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Source Code", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    )
                }

                // Content View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(if (selectedTab == 0) Color.White else JarvisBackground)
                ) {
                    if (selectedTab == 0) {
                        // Render HTML inside Android WebView
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    webViewClient = WebViewClient()
                                    webChromeClient = WebChromeClient()
                                    loadDataWithBaseURL(null, htmlCode, "text/html", "UTF-8", null)
                                    webViewInstance = this
                                }
                            },
                            update = { wv ->
                                wv.loadDataWithBaseURL(null, htmlCode, "text/html", "UTF-8", null)
                                webViewInstance = wv
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Raw source code viewer
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = htmlCode,
                                color = JarvisCyan,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private val JarmonTopBarBg = Color(0xFF131E35)
