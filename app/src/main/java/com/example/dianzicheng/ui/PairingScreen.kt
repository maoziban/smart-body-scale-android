package com.example.dianzicheng.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dianzicheng.data.ble.BleScaleClient

@Composable
fun PairingScreen(
    viewModel: ScaleViewModel,
    onPairingComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var connectingMac by remember { mutableStateOf<String?>(null) }

    // Permissions needed for BLE scanning
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Permission launcher: when permissions granted, start pairing scan immediately
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasBlePermissions()) {
            viewModel.startPairingScan()
        }
    }

    // Auto-start: check permissions then scan
    LaunchedEffect(Unit) {
        if (hasBlePermissions()) {
            viewModel.startPairingScan()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Bug #23 修复：监听连接状态回退（IDLE/SCANNING），自动清空 connectingMac，
    // 防止 GATT 连接失败或超时后按钮永久处于 Loading 状态造成界面死锁
    LaunchedEffect(uiState.connection) {
        if (connectingMac != null &&
            uiState.connection == BleScaleClient.ConnectionState.IDLE ||
            uiState.connection == BleScaleClient.ConnectionState.SCANNING) {
            if (!uiState.isDeviceRemembered) {
                // 连接失败回退（非成功配对），清空 connecting 状态让用户可以重试
                connectingMac = null
            }
        }
    }

    // 当且仅当用户手动点击连接并成功记住该设备（isDeviceRemembered 为 true 且已连接），才自动跳转完成配对
    LaunchedEffect(uiState.connection, uiState.isDeviceRemembered) {
        if (uiState.isDeviceRemembered &&
            (uiState.connection == BleScaleClient.ConnectionState.CONNECTED ||
             uiState.connection == BleScaleClient.ConnectionState.MEASURING)) {
            connectingMac = null
            onPairingComplete()
        }
    }

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedButton(
                    onClick = {
                        if (hasBlePermissions()) {
                            viewModel.startPairingScan()
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重新搜索设备")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onPairingComplete) {
                    Text("暂不绑定，先去主页", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text("绑定体脂秤", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "请将体脂秤放置在平整地面上，站上或轻踩秤面唤醒蓝牙广播",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "支持小米/米家、沃莱、香山、芯海OKOK、斐讯 (S7 Wi-Fi / S9 蓝牙) 等品牌",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            val scales = uiState.discoveredScales
            if (scales.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "正在搜索附近的体脂秤...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "搜到设备后请在列表手动点击「连接并记住」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                Text(
                    "发现以下附近设备（点击手动连接）：",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(scales, key = { it.address }) { scale ->
                        val isThisConnecting = connectingMac == scale.address
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isThisConnecting) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                }
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = scale.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = scale.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (scale.isBroadcastScale) "免配对广播秤 · 信号 ${scale.rssi} dBm" else "蓝牙双向通信秤 · 信号 ${scale.rssi} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        connectingMac = scale.address
                                        viewModel.pairAndConnectDevice(scale)
                                    },
                                    enabled = connectingMac == null || isThisConnecting
                                ) {
                                    if (isThisConnecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("连接中")
                                    } else {
                                        Text("连接并记住")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

