package com.example.dianzicheng

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.example.dianzicheng.data.ble.BleScaleClient
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.dianzicheng.data.local.AppDatabase
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ProfileRepository
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.ui.HistoryViewModel
import com.example.dianzicheng.ui.MainScreen
import com.example.dianzicheng.ui.ProfileViewModel
import com.example.dianzicheng.ui.ScaleViewModel
import com.example.dianzicheng.ui.theme.电子秤Theme

import com.example.dianzicheng.data.backup.WebDavManager
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.phicomm.PhicommS7Manager
import com.example.dianzicheng.data.sync.ZeppLifeSyncManager

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var bleClient: BleScaleClient
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var webDavManager: WebDavManager
    private lateinit var phicommS7Manager: PhicommS7Manager
    private lateinit var zeppLifeSyncManager: ZeppLifeSyncManager

    private var scaleViewModelRef: ScaleViewModel? = null

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) {
                scaleViewModelRef?.checkBluetoothAndLocationState()
                val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.ERROR)
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    // 蓝牙打开后，若处于配对模式或已记住设备且当前处于 IDLE，自动恢复扫描
                    if (bleClient.isPairingMode && bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE) {
                        bleClient.startPairingScan()
                    } else if (!bleClient.lastPairedMac.isNullOrEmpty() && bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE) {
                        bleClient.startScan()
                    }
                } else if (state == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                    bleClient.stopScan()
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 若权限请求被系统取消或中断（返回空 Map），不弹出误报提示
        if (permissions.isEmpty()) return@registerForActivityResult

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!btGranted) {
                android.widget.Toast.makeText(
                    this,
                    "附近设备（蓝牙）权限被拒绝，请前往「设置 → 应用 → 权限」开启，否则无法搜索体脂秤",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
        } else {
            val locGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!locGranted) {
                android.widget.Toast.makeText(
                    this,
                    "定位权限未开启，请前往「设置 → 应用 → 权限」开启位置信息权限，否则系统将屏蔽体脂秤蓝牙广播",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }
        }

        // 权限已授予：若当前处于配对模式或尚未绑定设备，立即启动配对扫描；若已绑定设备则恢复常规扫描
        if (::bleClient.isInitialized) {
            if (bleClient.isPairingMode || bleClient.lastPairedMac.isNullOrEmpty()) {
                bleClient.startPairingScan()
            } else if (bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE) {
                bleClient.startScan()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "scale-db"
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()
        
        bleClient = BleScaleClient(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        profileRepository = ProfileRepository(database.scaleDao())
        preferenceManager = PreferenceManager(applicationContext)
        healthConnectManager = HealthConnectManager(applicationContext)
        webDavManager = WebDavManager(database.scaleDao())
        phicommS7Manager = PhicommS7Manager(applicationContext)
        zeppLifeSyncManager = ZeppLifeSyncManager(preferenceManager)

        checkPermissions()

        // 确保系统中存在默认用户（"自己"），保证首次称重时 BMI 与身体成分正常计算
        lifecycleScope.launch {
            profileRepository.ensureDefaultMemberExists()
        }

        bleClient.onMacDiscovered = { mac ->
            lifecycleScope.launch {
                preferenceManager.savePairedMac(mac)
            }
        }

        lifecycleScope.launch {
            preferenceManager.pairedMac.collect { mac ->
                bleClient.lastPairedMac = mac  // null clears memory, preventing stale reconnect
                if (mac.isNullOrEmpty()) {
                    // 若当前正处于手动配对扫描模式，绝不调用 disconnectAndReset() 中断配对扫描
                    if (!bleClient.isPairingMode) {
                        bleClient.disconnectAndReset()
                    }
                } else if (!bleClient.isPairingMode) {
                    if (bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE && bleClient.isBluetoothEnabled()) {
                        bleClient.startScan()
                        phicommS7Manager.startListening()
                    }
                }
            }
        }

        lifecycleScope.launch {
            preferenceManager.selectedBodyAlgorithm.collect { algoId ->
                com.example.dianzicheng.domain.BodyAlgorithm.currentAlgorithm =
                    com.example.dianzicheng.domain.BodyAlgorithmType.fromId(algoId)
            }
        }

        val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)

        enableEdgeToEdge()
        setContent {
            val isPairingComplete by preferenceManager.isPairingComplete.collectAsState(initial = null)
            
            电子秤Theme {
                val scaleViewModel: ScaleViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ScaleViewModel(
                                bleClient,
                                scaleRepository,
                                preferenceManager,
                                healthConnectManager,
                                phicommS7Manager,
                                zeppLifeSyncManager
                            ) as T
                        }
                    }
                )
                scaleViewModelRef = scaleViewModel
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return HistoryViewModel(scaleRepository, preferenceManager, healthConnectManager) as T
                        }
                    }
                )
                val profileViewModel: ProfileViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ProfileViewModel(
                                profileRepository,
                                preferenceManager,
                                webDavManager,
                                healthConnectManager,
                                scaleRepository,
                                zeppLifeSyncManager
                            ) as T
                        }
                    }
                )

                MainScreen(
                    scaleViewModel = scaleViewModel,
                    historyViewModel = historyViewModel,
                    profileViewModel = profileViewModel,
                    isPairingComplete = isPairingComplete,
                    onPairingComplete = {
                        lifecycleScope.launch {
                            preferenceManager.setPairingComplete(true)
                        }
                    }
                )
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val missing = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissionLauncher.launch(missing.toTypedArray())
            }
        } else {
            val locGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!locGranted) {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        scaleViewModelRef?.checkBluetoothAndLocationState()
        // 回到前台：若处于配对模式则恢复配对扫描；若已记住设备且当前为空闲状态，自动恢复扫描以保持踏秤即连
        if (bleClient.isPairingMode) {
            if (bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE && bleClient.isBluetoothEnabled()) {
                bleClient.startScan()
            }
            phicommS7Manager.startListening()
        } else if (!bleClient.lastPairedMac.isNullOrEmpty()) {
            if (bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE && bleClient.isBluetoothEnabled()) {
                bleClient.startScan()
            }
            phicommS7Manager.startListening()
        }
    }

    override fun onStop() {
        super.onStop()
        // 进入后台：暂停 BLE 低延迟扫描与 Wi-Fi UDP 监听，节约电量并符合 Android 后台规范
        bleClient.stopScan()
        phicommS7Manager.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
        bleClient.disconnectAndReset()
        phicommS7Manager.stopListening()
    }
}
