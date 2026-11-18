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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.JarvisCompanionService
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
                JarvisCompanionService.startMediaProjection(this, result.resultCode, result.data!!)
                Toast.makeText(this, "Tangkap layar aktif", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error starting media projection service", e)
                Toast.makeText(this, "Gagal mengaktifkan tangkap layar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Izin tangkap layar dibatalkan", Toast.LENGTH_SHORT).show()
        }
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val grantedCount = results.values.count { it }
        if (grantedCount > 0) {
            Toast.makeText(this, "$grantedCount izin diperbarui", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.quiz.QuizAnalyzer.loadPersisted()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#13161B"))
        )

        if (intent?.getBooleanExtra("auto_request_projection", false) == true) {
            requestMediaProjection()
        }

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

    val navItems = remember {
        listOf(
            Triple("nav_dashboard", Icons.Default.Dashboard to "Dashboard", 0),
            Triple("nav_sandbox", Icons.Default.TouchApp to "Sandbox", 1),
            Triple("nav_termux", Icons.Default.Terminal to "Termux", 2),
            Triple("nav_chat", Icons.AutoMirrored.Filled.Chat to "Asisten", 3),
            Triple("nav_tools", Icons.Default.Build to "Tools", 4)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = JarvisBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppLogoMark(size = 34.dp, iconSize = 18.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Andra Control",
                                    color = JarvisTextPrimary,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.2).sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Otomasi & Asisten Perangkat",
                                    color = JarvisTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            StatusPill(
                                text = if (isServerRunning) "Online" else "Offline",
                                active = isServerRunning
                            )
                            Switch(
                                checked = isServerRunning,
                                onCheckedChange = { viewModel.toggleServer() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = JarvisEmerald,
                                    checkedBorderColor = Color.Transparent,
                                    uncheckedThumbColor = JarvisTextSecondary,
                                    uncheckedTrackColor = JarvisSurfaceVariant,
                                    uncheckedBorderColor = JarvisBorderStrong
                                ),
                                modifier = Modifier
                                    .scale(0.84f)
                                    .testTag("server_toggle_switch")
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = JarvisBackground,
                        titleContentColor = JarvisTextPrimary
                    )
                )
                HorizontalDivider(thickness = 1.dp, color = JarvisBorder)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 1.dp, color = JarvisBorder)
                NavigationBar(
                    containerColor = JarvisSurface,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets.navigationBars
                ) {
                    navItems.forEach { (tag, iconAndLabel, index) ->
                        val (icon, label) = iconAndLabel
                        val isSelected = selectedTab == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = JarvisTextPrimary,
                                selectedTextColor = JarvisTextPrimary,
                                indicatorColor = JarvisSurfaceHighlight,
                                unselectedIconColor = JarvisTextSecondary,
                                unselectedTextColor = JarvisTextSecondary
                            ),
                            modifier = Modifier.testTag(tag)
                        )
                    }
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
