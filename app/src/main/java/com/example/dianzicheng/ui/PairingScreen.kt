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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.ScaleModel

@Composable
fun PairingScreen(
    viewModel: ScaleViewModel,
    onPairingComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var connectingMac by remember { mutableStateOf<String?>(null) }
    var showModelDialog by remember { mutableStateOf(false) }

    // Permissions needed for BLE scanning
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
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

    // 退出配对界面时，若处于配对扫描中，自动停止配对扫描并重置配对模式
    DisposableEffect(Unit) {
        onDispose {
            if (uiState.connection == BleScaleClient.ConnectionState.SCANNING) {
                viewModel.stopPairingScan()
            }
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
            Spacer(modifier = Modifier.height(16.dp))
            Text("绑定体脂秤", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "请将体脂秤放置在平整地面上，站上或轻踩秤面唤醒蓝牙广播",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // ── 体脂秤型号选择卡片 ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "目标型号: ${uiState.selectedScaleModel.displayName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        TextButton(
                            onClick = { showModelDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("全部型号", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // 常用型号快速切换标签栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickModels = listOf(
                            ScaleModel.AUTO,
                            ScaleModel.BOOHEE_YOLANDA,
                            ScaleModel.XIAOMI_SCALE_2,
                            ScaleModel.OKOK_CHIPSEA,
                            ScaleModel.SENSSUN,
                            ScaleModel.PHICOMM_S7
                        )
                        quickModels.forEach { model ->
                            val isSelected = uiState.selectedScaleModel == model
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (!isSelected) {
                                        viewModel.selectScaleModel(model)
                                        if (hasBlePermissions()) {
                                            viewModel.startPairingScan()
                                        }
                                    }
                                },
                                label = {
                                    Text(
                                        text = when (model) {
                                            ScaleModel.AUTO -> "自动识别"
                                            ScaleModel.BOOHEE_YOLANDA -> "薄荷/沃莱"
                                            ScaleModel.XIAOMI_SCALE_2 -> "小米体脂秤2"
                                            ScaleModel.OKOK_CHIPSEA -> "芯海OKOK"
                                            ScaleModel.SENSSUN -> "香山"
                                            ScaleModel.PHICOMM_S7 -> "斐讯S7"
                                            else -> model.displayName
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            )
                        }
                    }

                    // 针对当前型号的唤醒和操作指引
                    if (uiState.selectedScaleModel.wakeGuidance.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = uiState.selectedScaleModel.wakeGuidance,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val scales = uiState.discoveredScales
            if (!uiState.isBluetoothEnabled) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "手机蓝牙已关闭",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "请开启手机蓝牙以搜索并连接附近的体脂秤设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("开启蓝牙")
                        }
                    }
                }
            } else if (!uiState.isLocationEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "系统定位服务未开启",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "该 Android 版本需开启定位服务才能扫描到附近的低功耗蓝牙设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                context.startActivity(intent)
                            }
                        ) {
                            Text("前往开启定位")
                        }
                    }
                }
            } else if (scales.isEmpty()) {
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
                        val isBestMatch = scale.matchedModel == uiState.selectedScaleModel && uiState.selectedScaleModel != ScaleModel.AUTO
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isThisConnecting -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    isBestMatch -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                }
                            ),
                            border = if (isBestMatch) {
                                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            } else null,
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
                                            imageVector = if (scale.isWifiScale) Icons.Default.Wifi else Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = scale.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isBestMatch) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = MaterialTheme.shapes.extraSmall
                                            ) {
                                                Text(
                                                    text = "匹配型号",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        } else if (scale.matchedModel != ScaleModel.AUTO) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = MaterialTheme.shapes.extraSmall
                                            ) {
                                                Text(
                                                    text = scale.matchedModel.displayName.split(" ").firstOrNull() ?: scale.matchedModel.displayName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        } else if (scale.isConfirmedScale || scale.isWifiScale) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = MaterialTheme.shapes.extraSmall
                                            ) {
                                                Text(
                                                    text = if (scale.isWifiScale) "Wi-Fi" else "体脂秤",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = scale.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = when {
                                            scale.isWifiScale -> "Wi-Fi 局域网设备 · 端口 10181"
                                            scale.matchedModel != ScaleModel.AUTO -> "协议: ${scale.matchedModel.protocolName} · 信号 ${scale.rssi} dBm"
                                            scale.protocolName.isNotBlank() -> "协议: ${scale.protocolName} · 信号 ${scale.rssi} dBm"
                                            scale.isBroadcastScale -> "免配对广播秤 · 信号 ${scale.rssi} dBm"
                                            scale.isConfirmedScale -> "蓝牙双向通信秤 · 信号 ${scale.rssi} dBm"
                                            else -> "附近蓝牙设备 · 信号 ${scale.rssi} dBm"
                                        },
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

    if (showModelDialog) {
        SelectScaleModelDialog(
            currentModel = uiState.selectedScaleModel,
            onModelSelected = { model ->
                viewModel.selectScaleModel(model)
                if (hasBlePermissions()) {
                    viewModel.startPairingScan()
                }
            },
            onDismiss = { showModelDialog = false }
        )
    }
}

