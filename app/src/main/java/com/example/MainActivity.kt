package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.proxy.DpiProxyService
import com.example.proxy.LogEntry
import com.example.proxy.LogType
import com.example.proxy.ProxyStats
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                BegaDpiApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BegaDpiApp() {
    val context = LocalContext.current
    val serviceActive by DpiProxyService.statusFlow.collectAsState()
    val logs by DpiProxyService.logsFlow.collectAsState()
    val stats by DpiProxyService.statsFlow.collectAsState()

    var showConfigDialog by remember { mutableStateOf(false) }

    // State for temporary configuration inputs
    var inputHost by remember { mutableStateOf(DpiProxyService.targetHost) }
    var inputTargetPort by remember { mutableStateOf(DpiProxyService.targetPort.toString()) }
    var inputLocalPort by remember { mutableStateOf(DpiProxyService.localPort.toString()) }
    var inputBypassProbability by remember { mutableStateOf((DpiProxyService.bypassProbability * 100).toInt().toString()) }
    var inputModificationProbability by remember { mutableStateOf((DpiProxyService.byteModificationProbability * 100).toInt().toString()) }

    // Permission launcher for Notifications on Android 13+
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Notification permission is required to run the service in background.", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "BegaDPI",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // Update input states before showing dialog
                            inputHost = DpiProxyService.targetHost
                            inputTargetPort = DpiProxyService.targetPort.toString()
                            inputLocalPort = DpiProxyService.localPort.toString()
                            inputBypassProbability = (DpiProxyService.bypassProbability * 100).toInt().toString()
                            inputModificationProbability = (DpiProxyService.byteModificationProbability * 100).toInt().toString()
                            showConfigDialog = true
                        },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Service Controller Card
            ServiceControllerCard(
                isActive = serviceActive,
                onToggle = {
                    if (serviceActive) {
                        val intent = Intent(context, DpiProxyService::class.java).apply {
                            action = DpiProxyService.ACTION_STOP
                        }
                        context.startService(intent)
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val intent = Intent(context, DpiProxyService::class.java).apply {
                                action = DpiProxyService.ACTION_START
                            }
                            context.startService(intent)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Configuration Info Strip
            ConfigInfoStrip(
                host = DpiProxyService.targetHost,
                targetPort = DpiProxyService.targetPort,
                localPort = DpiProxyService.localPort,
                bypassProb = DpiProxyService.bypassProbability,
                modProb = DpiProxyService.byteModificationProbability
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row
            StatsDashboard(stats = stats)

            Spacer(modifier = Modifier.height(16.dp))

            // Logs Section
            LogsTerminal(
                logs = logs,
                onClear = {
                    DpiProxyService.clearLogsAndStats()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Settings Dialog
    if (showConfigDialog) {
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Proxy & DPI Config")
                }
            },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = inputHost,
                            onValueChange = { inputHost = it },
                            label = { Text("Target Host (IP)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("config_host_input")
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = inputTargetPort,
                            onValueChange = { inputTargetPort = it },
                            label = { Text("Target Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("config_target_port_input")
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = inputLocalPort,
                            onValueChange = { inputLocalPort = it },
                            label = { Text("Local Proxy Port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("config_local_port_input")
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = inputBypassProbability,
                            onValueChange = { inputBypassProbability = it },
                            label = { Text("Bypass Chance (%)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("config_bypass_prob_input")
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = inputModificationProbability,
                            onValueChange = { inputModificationProbability = it },
                            label = { Text("Byte Mod Chance (%)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("config_mod_prob_input")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Apply inputs with safe fallbacks
                        DpiProxyService.targetHost = inputHost.trim().ifEmpty { "45.131.211.76" }
                        DpiProxyService.targetPort = inputTargetPort.toIntOrNull() ?: 443
                        DpiProxyService.localPort = inputLocalPort.toIntOrNull() ?: 40446

                        val bp = inputBypassProbability.toFloatOrNull() ?: 10f
                        DpiProxyService.bypassProbability = (bp / 100f).coerceIn(0f, 1f)

                        val mp = inputModificationProbability.toFloatOrNull() ?: 20f
                        DpiProxyService.byteModificationProbability = (mp / 100f).coerceIn(0f, 1f)

                        DpiProxyService.addLog("⚙️ Configuration updated successfully", LogType.INFO)
                        showConfigDialog = false
                    },
                    modifier = Modifier.testTag("config_save_button")
                ) {
                    Text("Apply & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ServiceControllerCard(
    isActive: Boolean,
    onToggle: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("controller_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (isActive) {
                                listOf(Color(0xFF4CAF50), Color(0xFF2E7D32))
                            } else {
                                listOf(Color(0xFFE0E0E0), Color(0xFF9E9E9E))
                            }
                        )
                    )
                    .clickable(onClick = onToggle)
                    .testTag("power_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Toggle Service",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isActive) "ACTIVE & BYPASSING" else "SERVICE STOPPED",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (isActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isActive) "DPI bypass proxy is running in the background." else "Tap the power button to initiate the proxy.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ConfigInfoStrip(
    host: String,
    targetPort: Int,
    localPort: Int,
    bypassProb: Float,
    modProb: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TARGET: $host:$targetPort",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "LOCAL PROXY: 127.0.0.1:$localPort",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Bypass Prob: ${(bypassProb * 100).toInt()}%",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Byte Mod: ${(modProb * 100).toInt()}%",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
fun StatsDashboard(stats: ProxyStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatItem(
            title = "Sent",
            value = formatBytes(stats.bytesSent),
            icon = Icons.Default.ArrowUpward,
            modifier = Modifier.weight(1f)
        )
        StatItem(
            title = "Received",
            value = formatBytes(stats.bytesReceived),
            icon = Icons.Default.ArrowDownward,
            modifier = Modifier.weight(1f)
        )
        StatItem(
            title = "Connections",
            value = stats.activeConnections.toString(),
            icon = Icons.Default.Sync,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatItem(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun LogsTerminal(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)) // Terminal Dark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CONSOLE LOGS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp).testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear logs",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Divider(color = Color.DarkGray)

            Spacer(modifier = Modifier.height(8.dp))

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No logs yet. Activate the service to see live activity.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "[${entry.timestamp}] ",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = entry.message,
                                color = when (entry.type) {
                                    LogType.SUCCESS -> Color(0xFF4CAF50)
                                    LogType.WARNING -> Color(0xFFFFC107)
                                    LogType.ERROR -> Color(0xFFF44336)
                                    else -> Color.LightGray
                                },
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1] + ""
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}
