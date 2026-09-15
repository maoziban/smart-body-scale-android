package com.example.dianzicheng.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    // Permission launcher: when permissions granted, start scanning immediately
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasBlePermissions()) {
            viewModel.startScanning()
        }
    }

    // Auto-start: check permissions then scan (or request permissions first)
    LaunchedEffect(Unit) {
        if (hasBlePermissions()) {
            viewModel.startScanning()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LaunchedEffect(uiState.connection) {
        if (uiState.connection == BleScaleClient.ConnectionState.CONNECTED ||
            uiState.connection == BleScaleClient.ConnectionState.MEASURING) {
            onPairingComplete()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("欢迎使用体脂秤", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("请确保体脂秤已开启，并站在秤上", style = MaterialTheme.typography.bodyLarge)

        Spacer(modifier = Modifier.height(64.dp))

        uiState.discoveredDeviceMac?.let { mac ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("发现设备: ${uiState.discoveredDeviceName}", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.connectToMac(mac) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("点击连接该设备")
                    }
                }
            }
        }

        if (uiState.connection == BleScaleClient.ConnectionState.SCANNING ||
            uiState.connection == BleScaleClient.ConnectionState.CONNECTING) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (uiState.connection == BleScaleClient.ConnectionState.CONNECTING) "正在连接体脂秤..." else "正在搜索体脂秤，请稍候...",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = { viewModel.startScanning() }) {
                Text("重新搜索")
            }
        } else {
            Button(
                onClick = {
                    if (hasBlePermissions()) {
                        viewModel.startScanning()
                    } else {
                        permissionLauncher.launch(requiredPermissions)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新搜索")
            }
        }

        TextButton(onClick = onPairingComplete) {
            Text("跳过配对")
        }
    }
}

