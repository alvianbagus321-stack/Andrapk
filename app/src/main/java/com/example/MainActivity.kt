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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.ScreenshotManager
import com.example.ui.JarvisChatScreen
import com.example.ui.JarvisDashboardScreen
import com.example.ui.JarvisSandboxScreen
import com.example.ui.JarvisTermuxScreen
import com.example.ui.JarvisViewModel
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
            if (projection != null) {
                ScreenshotManager.setMediaProjection(this, projection)
                Toast.makeText(this, "MediaProjection screenshot active!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not acquire MediaProjection", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
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
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
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
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = JarvisCyan.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "JARVIS-HP",
                                color = JarvisTextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Agent Engine & Companion",
                                color = JarvisCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
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
                    containerColor = JarvisSurface,
                    titleContentColor = JarvisTextPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = JarvisSurface,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard", fontSize = 11.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = JarvisBackground,
                        selectedTextColor = JarvisCyan,
                        indicatorColor = JarvisCyan,
                        unselectedIconColor = JarvisTextSecondary,
                        unselectedTextColor = JarvisTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_dashboard")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = "Sandbox") },
                    label = { Text("Sandbox", fontSize = 11.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = JarvisBackground,
                        selectedTextColor = JarvisCyan,
                        indicatorColor = JarvisCyan,
                        unselectedIconColor = JarvisTextSecondary,
                        unselectedTextColor = JarvisTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_sandbox")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Termux Agent") },
                    label = { Text("Termux", fontSize = 11.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = JarvisBackground,
                        selectedTextColor = JarvisCyan,
                        indicatorColor = JarvisCyan,
                        unselectedIconColor = JarvisTextSecondary,
                        unselectedTextColor = JarvisTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_termux")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Forum, contentDescription = "AI Chat") },
                    label = { Text("AI Chat", fontSize = 11.sp, fontWeight = if (selectedTab == 3) FontWeight.Bold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = JarvisBackground,
                        selectedTextColor = JarvisCyan,
                        indicatorColor = JarvisCyan,
                        unselectedIconColor = JarvisTextSecondary,
                        unselectedTextColor = JarvisTextSecondary
                    ),
                    modifier = Modifier.testTag("nav_chat")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = selectedTab, label = "tab_transition") { tab ->
                when (tab) {
                    0 -> JarvisDashboardScreen(
                        viewModel = viewModel,
                        onRequestMediaProjection = onRequestMediaProjection
                    )
                    1 -> JarvisSandboxScreen(viewModel = viewModel)
                    2 -> JarvisTermuxScreen(viewModel = viewModel)
                    3 -> JarvisChatScreen(viewModel = viewModel)
                }
            }
        }
    }
}
