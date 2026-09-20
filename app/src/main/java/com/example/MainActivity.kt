package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.JarvisCompanionService
import com.example.service.ScreenshotManager
import com.example.ui.JarvisChatScreen
import com.example.ui.JarvisDashboardScreen
import com.example.ui.JarvisSandboxScreen
import com.example.ui.JarvisTermuxScreen
import com.example.ui.JarvisToolsScreen
import com.example.ui.JarvisViewModel
import com.example.ui.components.AppLogoMark
import com.example.ui.components.AuroraBackdrop
import com.example.ui.components.StatusPill
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                // In Android 14+ (API 34+), getMediaProjection must be called while a Foreground Service
                // of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is actively running.
                JarvisCompanionService.startMediaProjection(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Screen Share berhasil diaktifkan!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting media projection service", e)
                Toast.makeText(this, "Gagal mengaktifkan Screen Share: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Izin Screen Share dibatalkan atau ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val grantedCount = results.values.count { it }
        if (grantedCount > 0) {
            Toast.makeText(this, "Permissions updated ($grantedCount granted)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request runtime permissions (Storage, Notifications)
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            multiPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setContent {
            MyApplicationTheme {
                JarvisMainApp(
                    viewModel = viewModel,
                    onRequestMediaProjection = { requestMediaProjection() }
                )
            }
        }
    }

    private fun requestMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                multiPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error launching screen capture intent", e)
            Toast.makeText(this, "Gagal membuka dialog izin: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisMainApp(
    viewModel: JarvisViewModel,
    onRequestMediaProjection: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val isServerRunning by viewModel.isServerRunning.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = JarvisBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppLogoMark(size = 38.dp, iconSize = 20.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Andra Control",
                                color = JarvisTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                            Text(
                                text = "Device Automation Suite",
                                color = JarvisCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        StatusPill(
                            text = if (isServerRunning) "ONLINE" else "OFFLINE",
                            active = isServerRunning
                        )
                        Switch(
                            checked = isServerRunning,
                            onCheckedChange = { viewModel.toggleServer() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JarvisBackground,
                                checkedTrackColor = JarvisEmerald,
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisSurfaceVariant
                            ),
                            modifier = Modifier.testTag("server_toggle_switch")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = JarvisBackground.copy(alpha = 0.6f),
                    titleContentColor = JarvisTextPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = JarvisSurface.copy(alpha = 0.96f),
                tonalElevation = 0.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                listOf(
                    Triple(0, Icons.Default.Dashboard, "Dashboard"),
                    Triple(1, Icons.Default.TouchApp, "Sandbox"),
                    Triple(2, Icons.Default.Terminal, "Termux"),
                    Triple(3, Icons.AutoMirrored.Filled.Chat, "Asisten"),
                    Triple(4, Icons.Default.Build, "Tools")
                ).forEach { (index, icon, label) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = {
                            Text(
                                label,
                                fontSize = 10.5.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                letterSpacing = 0.2.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = JarvisCyan,
                            selectedTextColor = JarvisCyan,
                            indicatorColor = JarvisCyan.copy(alpha = 0.14f),
                            unselectedIconColor = JarvisTextSecondary,
                            unselectedTextColor = JarvisTextSecondary
                        ),
                        modifier = Modifier.testTag(
                            when (index) {
                                0 -> "nav_dashboard"
                                1 -> "nav_sandbox"
                                2 -> "nav_termux"
                                3 -> "nav_chat"
                                else -> "nav_tools"
                            }
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AuroraBackdrop {
                Crossfade(targetState = selectedTab, label = "tab_transition") { tab ->
                    when (tab) {
                        0 -> JarvisDashboardScreen(
                            viewModel = viewModel,
                            onRequestMediaProjection = onRequestMediaProjection
                        )
                        1 -> JarvisSandboxScreen(viewModel = viewModel)
                        2 -> JarvisTermuxScreen(viewModel = viewModel)
                        3 -> JarvisChatScreen(viewModel = viewModel)
                        4 -> JarvisToolsScreen()
                    }
                }
            }
        }
    }
}
