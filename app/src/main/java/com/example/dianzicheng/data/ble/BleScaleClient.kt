package com.example.dianzicheng.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.example.dianzicheng.data.local.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*

private const val TAG = "BleScaleClient"

/**
 * BLE 蓝牙体脂秤客户端，负责完整的蓝牙生命周期管理：
 * 扫描 → 发现设备 → GATT 连接 → 服务发现 → 订阅通知 → 握手 → 数据接收与解析。
 *
 * 内部通过 [AFUPacketParser] 对收到的特征值通知数据进行解析，
 * 并以 [StateFlow] 的形式向上层（ViewModel / UI）暴露实时状态：
 * - [connectionState]：当前连接状态枚举
 * - [weight]：实时体重（kg）
 * - [isStable]：体重是否稳定锁定
 * - [impedance]：生物电阻抗值（Ω）
 * - [discoveredDevice]：发现的设备（名称 + MAC 地址）
 *
 * @param context Android 上下文，用于获取蓝牙系统服务。
 */
@SuppressLint("MissingPermission")
class BleScaleClient(private val context: Context) {

    // -------------------------------------------------------------------------
    // 蓝牙适配器与 GATT 连接
    // -------------------------------------------------------------------------

    /** 系统蓝牙适配器，从 BluetoothManager 获取；若设备无 BT 则为 null。 */
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** 当前活跃的 GATT 连接实例；未连接时为 null。 */
    private var bluetoothGatt: BluetoothGatt? = null

    /** 支持的体脂秤/体重秤服务 UUID 集合，用于广播过滤与服务匹配 */
    private val SUPPORTED_SERVICE_UUIDS = setOf(
        MultiScalePacketParser.UUID_SERVICE_AFU,              // 0xFFB0 (AFU 私有协议)
        MultiScalePacketParser.UUID_SERVICE_SIG_WSS,         // 0x181D (蓝牙 SIG 标准体重秤)
        MultiScalePacketParser.UUID_SERVICE_SIG_BCS,         // 0x181B (蓝牙 SIG 标准体脂秤/小米)
        MultiScalePacketParser.UUID_SERVICE_CHIPSEA_OKOK,    // 0xFFF0 (芯海科技 / OKOK 方案)
        MultiScalePacketParser.UUID_SERVICE_GENERIC_FFE0,    // 0xFFE0 (通用透传秤)
        MultiScalePacketParser.UUID_SERVICE_XIAOMI_WECHAT    // 0xFEE7 (小米/微信运动秤)
    )

    // -------------------------------------------------------------------------
    // 对外暴露的 StateFlow 状态流
    // -------------------------------------------------------------------------

    /** 连接状态（内部可写）。 */
    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    /** 连接状态（外部只读）。 */
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** 实时体重，单位 kg（内部可写）。 */
    private val _weight = MutableStateFlow(0.0)
    /** 实时体重，单位 kg（外部只读）。 */
    val weight: StateFlow<Double> = _weight

    /** 体重是否稳定锁定（内部可写）。 */
    private val _isStable = MutableStateFlow(false)
    /** 体重是否稳定锁定（外部只读）。 */
    val isStable: StateFlow<Boolean> = _isStable

    /** 生物电阻抗值，单位 Ω（内部可写）。 */
    private val _impedance = MutableStateFlow(0.0)
    /** 生物电阻抗值，单位 Ω（外部只读）。 */
    val impedance: StateFlow<Double> = _impedance

    /**
     * 扫描到的体脂秤设备信息：Pair<设备显示名称, MAC 地址>（内部可写）。
     * null 表示尚未发现设备。
     */
    private val _discoveredDevice = MutableStateFlow<Pair<String, String>?>(null)
    /** 扫描到的体脂秤设备信息（外部只读）。 */
    val discoveredDevice: StateFlow<Pair<String, String>?> = _discoveredDevice

    // -------------------------------------------------------------------------
    // 连接状态枚举
    // -------------------------------------------------------------------------

    /**
     * BLE 连接状态枚举，描述客户端当前所处的生命周期阶段。
     *
     * - [IDLE]：空闲，未扫描也未连接。
     * - [SCANNING]：正在执行 BLE 广播扫描。
     * - [CONNECTING]：已发现目标设备，正在建立 GATT 连接或进行服务发现。
     * - [CONNECTED]：GATT 已连接并完成全部特征订阅，等待用户踩秤。
     * - [MEASURING]：检测到有效稳定体重，正在进行测量（含阻抗采集）。
     */
    enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, MEASURING }

    // -------------------------------------------------------------------------
    // 外部可读写的辅助属性
    // -------------------------------------------------------------------------

    /** 上次成功配对的设备 MAC 地址，优先用于精确匹配扫描结果。 */
    var lastPairedMac: String? = null

    /** MAC 地址首次确认回调，用于将新发现的 MAC 持久化到外部存储。 */
    var onMacDiscovered: ((String) -> Unit)? = null

    /** GATT 连接失败后当前的自动重连次数 */
    private var gattRetryCount = 0

    /** GATT 连接失败最大自动重连次数；超出后需用户手动触发，避免无限耗电 */
    private val MAX_GATT_RETRY = 5

    // -------------------------------------------------------------------------
    // 广播数据辅助解析工具方法
    // -------------------------------------------------------------------------


    /**
     * 从 BLE 广播原始字节数组中按标准 AD Structure 格式解析设备名称。
     *
     * BLE 广播数据由若干 AD 结构（length + type + value）组成。
     * 类型 0x08（缩短本地名）或 0x09（完整本地名）的值字段即为设备名称。
     *
     * @param bytes BLE ScanRecord 原始字节数组。
     * @return 解析成功返回设备名称字符串（已去除首尾空白），否则返回 null。
     */
    private fun parseNameFromBytes(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        var i = 0
        // 按 AD Structure 格式逐段遍历：每段格式为 [length(1B)] [type(1B)] [value(length-1 B)]
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            if (length == 0) break                        // length 为 0 表示广播数据结束
            if (i + length >= bytes.size) break           // 防止越界
            val type = bytes[i + 1].toInt() and 0xFF
            // 0x08 = 缩短本地名称，0x09 = 完整本地名称
            if ((type == 0x08 || type == 0x09) && length > 1) {
                return try {
                    // value 从 i+2 开始，长度为 length-1
                    String(bytes, i + 2, length - 1, Charsets.UTF_8).trim()
                } catch (e: Exception) {
                    null
                }
            }
            i += length + 1  // 跳到下一个 AD 结构
        }
        return null
    }

    /**
     * 综合多维度规则判断某个扫描结果是否来自目标体脂秤设备。
     *
     * 匹配优先级（由高到低）：
     * 1. 已配对 MAC 地址精确匹配（最高优先级，直接返回 true）。
     * 2. 广播包中包含厂商私有服务 UUID（0000FFB0）。
     * 3. 设备名称包含特定体脂秤关键词（AFU / WL-TZ / TZ-A1 / Scale / Weight / 体脂 / 电子秤）。
     * 4. 广播原始数据中厂商数据（0xFF）或服务数据（0x16）段的首字节为 0xAC 帧头。
     *
     * @param result BLE 扫描回调返回的单条扫描结果。
     * @return 判定为目标设备返回 true，否则返回 false。
     */
    private fun isScaleAdvertisement(result: ScanResult): Boolean {
        val device = result.device
        val scanRecord = result.scanRecord
        val serviceUuids = scanRecord?.serviceUuids
        val rawBytes = scanRecord?.bytes
        // 尝试从多个来源获取设备名称（优先系统缓存，其次广播字段，最后手动解析）
        val deviceName = device.name ?: scanRecord?.deviceName ?: parseNameFromBytes(rawBytes)

        // 1. 已配对过的 MAC 地址精确匹配
        val isMatchedMac = !lastPairedMac.isNullOrEmpty() && device.address.equals(lastPairedMac, ignoreCase = true)
        if (isMatchedMac) return true

        // 2. 支持的体脂秤/体重秤 Service UUID 匹配（含 AFU、SIG WSS/BCS、OKOK/芯海等）
        val hasService = serviceUuids?.any { it.uuid in SUPPORTED_SERVICE_UUIDS } == true
        if (hasService) return true

        // 3. 广播数据包直接可解析出有效体重（如小米秤 Service Data、免配对广播秤）
        if (MultiScalePacketParser.parseAdvertisement(scanRecord) != null) {
            return true
        }

        // 4. 称重设备常见品牌与关键词匹配
        val nameMatched = deviceName?.let { name ->
            name.contains("AFU", ignoreCase = true) ||
            name.contains("WL-TZ", ignoreCase = true) ||
            name.contains("TZ-A1", ignoreCase = true) ||
            name.contains("Scale", ignoreCase = true) ||
            name.contains("Weight", ignoreCase = true) ||
            name.contains("体脂", ignoreCase = true) ||
            name.contains("电子秤", ignoreCase = true) ||
            name.contains("体重", ignoreCase = true) ||
            name.contains("MI", ignoreCase = true) ||
            name.contains("MIBFS", ignoreCase = true) ||
            name.contains("OKOK", ignoreCase = true) ||
            name.contains("Yolanda", ignoreCase = true) ||
            name.contains("Senssun", ignoreCase = true) ||
            name.contains("ICOMON", ignoreCase = true) ||
            name.contains("沃莱", ignoreCase = true) ||
            name.contains("香山", ignoreCase = true) ||
            name.contains("云麦", ignoreCase = true) ||
            name.contains("小米", ignoreCase = true)
        } == true
        if (nameMatched) return true

        // 5. 解析 BLE 广播数据中的厂商自定义数据包结构 (0xFF 或 0x16)，校验 0xAC 帧头
        var hasValidAcHeader = false
        if (rawBytes != null && rawBytes.size >= 6) {
            var i = 0
            // 遍历广播原始数据的每一个 AD 结构
            while (i < rawBytes.size - 2) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                // 仅检查厂商数据（0xFF）或服务数据（0x16）类型的 AD 段
                if ((type == 0xFF || type == 0x16) && len > 1) {
                    val firstDataByte = rawBytes[i + 2].toInt() and 0xFF
                    // 数据段首字节为 0xAC 则认为是 AFU 协议帧
                    if (firstDataByte == 0xAC) {
                        hasValidAcHeader = true
                        break
                    }
                }
                i += len + 1
            }
        }

        return hasValidAcHeader
    }

    // -------------------------------------------------------------------------
    // BLE 扫描回调
    // -------------------------------------------------------------------------

    /**
     * BLE 扫描事件回调。
     * - [onScanResult]：每发现一个广播包就触发一次，过滤后决定是否连接。
     * - [onScanFailed]：扫描启动失败时触发，800ms 后自动重试。
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // 判断该广播结果是否匹配目标体脂秤
            if (isScaleAdvertisement(result)) {
                val name = device.name ?: result.scanRecord?.deviceName ?: parseNameFromBytes(result.scanRecord?.bytes) ?: "体脂秤设备 (${device.address.takeLast(5)})"

                // 尝试直接从广播数据解析（如小米秤、免配对广播秤即踩即读）
                val advScaleData = MultiScalePacketParser.parseAdvertisement(result.scanRecord)
                if (advScaleData != null && advScaleData.weightKg > 0.0) {
                    _weight.value = advScaleData.weightKg
                    val validStable = advScaleData.isStable && (advScaleData.weightKg >= 3.0)
                    _isStable.value = validStable
                    if (validStable) {
                        _connectionState.value = ConnectionState.MEASURING
                    } else if (_connectionState.value != ConnectionState.MEASURING) {
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    advScaleData.impedanceOhm?.let { _impedance.value = it }

                    // 更新已发现设备信息与持久化 MAC
                    _discoveredDevice.value = Pair(name, device.address)
                    onMacDiscovered?.invoke(device.address)

                    // 重置 2.5s 无广播数据超时定时器
                    inactivityRunnable?.let { handler.removeCallbacks(it) }
                    val watchdog = Runnable {
                        AppLogger.d(TAG, "无广播数据超时 (2.5s): 用户已下秤，重置测量状态")
                        _weight.value = 0.0
                        _isStable.value = false
                        _impedance.value = 0.0
                    }
                    inactivityRunnable = watchdog
                    handler.postDelayed(watchdog, 2500)

                    // 广播秤无需且不能停止扫描去连接 GATT（连接可能被秤拒绝并中断持续数据流），保持扫描流以持续接收实时示数
                    return
                }

                // 非广播秤（AFU / SIG / OKOK 等需 GATT 双向通信的设备）：建立 GATT 连接
                val rawAdvHex = result.scanRecord?.bytes?.joinToString(" ") { "%02X".format(it) } ?: "null"
                AppLogger.i(TAG, "匹配到 GATT 体脂秤! 设备: $name [${device.address}], 广播原始数据: $rawAdvHex")
                _discoveredDevice.value = Pair(name, device.address)
                stopScan()
                onMacDiscovered?.invoke(device.address)
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLogger.e(TAG, "BLE 扫描失败，错误码: $errorCode")
            _connectionState.value = ConnectionState.IDLE
            // 扫描失败后延迟 800ms 自动重试，避免立即重试导致系统限流
            handler.postDelayed({
                if (_connectionState.value == ConnectionState.IDLE) {
                    startScan()
                }
            }, 800)
        }
    }

    // -------------------------------------------------------------------------
    // 公开方法：直连、扫描、停止扫描、断开重置
    // -------------------------------------------------------------------------

    /**
     * 直接通过已知 MAC 地址跳过扫描发起 GATT 连接。
     *
     * 适用于已知配对 MAC（如从 SharedPreferences 恢复）的场景，
     * 可省去扫描耗时直接尝试重连。
     *
     * @param macAddress 目标设备的蓝牙 MAC 地址字符串（格式：XX:XX:XX:XX:XX:XX）。
     */
    fun connectMac(macAddress: String) {
        // 校验 MAC 地址格式合法性
        if (BluetoothAdapter.checkBluetoothAddress(macAddress)) {
            val device = bluetoothAdapter?.getRemoteDevice(macAddress)
            if (device != null) {
                // 通知上层 MAC 已确认（用于持久化）
                onMacDiscovered?.invoke(macAddress)
                connect(device)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 内部定时器工具
    // -------------------------------------------------------------------------

    /** GATT 连接超时看门狗 Runnable，5 秒内未完成连接则回退到扫描状态。 */
    private var connectTimeoutRunnable: Runnable? = null

    /** 主线程 Handler，用于延迟任务（看门狗定时器、扫描重试等）。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // 公开方法：蓝牙状态查询
    // -------------------------------------------------------------------------

    /**
     * 检查系统蓝牙是否已开启。
     *
     * @return 蓝牙适配器存在且已启用时返回 true，否则返回 false。
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    // -------------------------------------------------------------------------
    // 扫描控制
    // -------------------------------------------------------------------------

    /**
     * 启动低能耗蓝牙（BLE）主动扫描。
     *
     * 启动前会：
     * 1. 重置体重、稳定、阻抗状态为初始值。
     * 2. 清理旧 GATT 连接和看门狗定时器，防止资源泄漏。
     * 3. 以 [ScanSettings.SCAN_MODE_LOW_LATENCY] 低延迟模式扫描，报告延迟为 0（即时回调）。
     *
     * 若蓝牙适配器不可用或未启用，则直接将状态设为 [ConnectionState.IDLE] 并返回。
     */
    fun startScan() {
        // 重置所有测量数据，确保下一次称重得到干净的初始状态
        _weight.value = 0.0
        _isStable.value = false
        _impedance.value = 0.0

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            // 记录详细原因：无适配器 / BT 未开启 / Scanner 为 null
            val reason = if (bluetoothAdapter == null) "No BT Adapter" else if (!bluetoothAdapter.isEnabled) "BT Disabled" else "Scanner Null"
            AppLogger.e(TAG, "蓝牙扫描器不可用: $reason")
            _connectionState.value = ConnectionState.IDLE
            return
        }

        // Clean up previous connection and watchdog timer
        // 取消旧的连接超时看门狗，防止误触发
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        // 关闭旧的 GATT 连接，释放系统蓝牙资源
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null

        AppLogger.i(TAG, "启动 BLE 扫描 (pairedMac: $lastPairedMac)...")
        _connectionState.value = ConnectionState.SCANNING

        // 构建低延迟扫描参数配置
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // 最低延迟，最快发现设备
            .setReportDelay(0)                                 // 不批量缓存，立即回调
            .build()

        // 先尝试停止旧扫描（防止重复启动导致系统报错）
        try {
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {}

        // 以无过滤器方式启动扫描（由 isScaleAdvertisement 逻辑层过滤）
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动 BLE 扫描失败: ${e.message}")
            _connectionState.value = ConnectionState.IDLE
        }
    }

    /**
     * 停止正在进行的 BLE 扫描。
     *
     * 若当前状态为 [ConnectionState.SCANNING]，则将状态重置为 [ConnectionState.IDLE]。
     * 如果调用时扫描已停止，忽略异常静默处理。
     */
    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {
            AppLogger.w(TAG, "停止扫描异常: ${e.message}")
        }
        // 仅当处于扫描状态时才回退到 IDLE，避免覆盖 CONNECTING/CONNECTED 等状态
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.IDLE
        }
    }

    /**
     * 断开当前 GATT 连接并完整重置客户端至初始状态。
     *
     * 会清除：
     * - 无数据超时定时器（inactivityRunnable）
     * - 连接超时看门狗（connectTimeoutRunnable）
     * - GATT 连接实例
     * - 已配对 MAC 记录（lastPairedMac）
     * - 已发现设备信息（discoveredDevice）
     * - 所有测量数据（体重、稳定标志、阻抗）
     */
    fun disconnectAndReset() {
        AppLogger.i(TAG, "断开连接并重置蓝牙状态")
        // 取消无数据超时定时器
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        // 取消 GATT 连接超时看门狗
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null
        // 清除已配对设备记忆，下次需要重新扫描配对
        lastPairedMac = null
        _discoveredDevice.value = null
        // 重置所有测量数据为初始值
        _connectionState.value = ConnectionState.IDLE
        _weight.value = 0.0
        _isStable.value = false
        _impedance.value = 0.0
    }

    // -------------------------------------------------------------------------
    // 内部 GATT 连接管理
    // -------------------------------------------------------------------------

    /**
     * 向指定蓝牙设备发起 GATT 连接。
     *
     * 连接流程：
     * 1. 停止扫描，避免无线电资源冲突。
     * 2. 关闭旧 GATT 实例（如有）。
     * 3. 设置 5 秒连接超时看门狗：若 5 秒内 GATT 未建立则回退到扫描。
     * 4. 在 Android M+ 上强制使用 LE 传输通道。
     *
     * @param device 目标蓝牙设备对象。
     */
    private fun connect(device: BluetoothDevice) {
        // ALWAYS stop scanning before initiating GATT connection to prevent radio collision
        // 停止扫描以释放无线电资源，防止扫描与 GATT 连接同时竞争导致连接失败
        stopScan()

        // 关闭旧的 GATT 连接实例
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            AppLogger.w(TAG, "关闭旧 GATT 实例异常: ${e.message}")
        }
        bluetoothGatt = null

        _connectionState.value = ConnectionState.CONNECTING
        AppLogger.i(TAG, "正在连接 GATT: ${device.address}...")

        // Cancel previous connection watchdog
        // 取消上一次的连接超时看门狗（避免多个定时器同时运行）
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }

        // 5-second watchdog: if GATT doesn't establish, close GATT & fall back to scan
        // 设置 5 秒连接超时看门狗：连接超时后自动关闭 GATT 并重新发起扫描
        val timeoutRunnable = Runnable {
            if (_connectionState.value == ConnectionState.CONNECTING) {
                AppLogger.w(TAG, "GATT 连接超时 (5s)，回退并重新启动 LE 扫描...")
                try {
                    bluetoothGatt?.disconnect()
                    bluetoothGatt?.close()
                } catch (e: Exception) {}
                bluetoothGatt = null
                _connectionState.value = ConnectionState.IDLE
                startScan()  // 回退到扫描，重新发现设备
            }
        }
        connectTimeoutRunnable = timeoutRunnable
        handler.postDelayed(timeoutRunnable, 5000)  // 5000ms = 5秒超时

        // 发起 GATT 连接；Android M（API 23）及以上强制指定 LE 传输通道以提高稳定性
        bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    // -------------------------------------------------------------------------
    // 特征订阅队列管理
    // -------------------------------------------------------------------------

    /** 等待逐个订阅的通知/指示特征列表（服务发现后填充）。 */
    private var pendingSubscribeList = mutableListOf<BluetoothGattCharacteristic>()

    /** 当前正在处理的特征订阅索引（指向 [pendingSubscribeList] 中的下一个待订阅特征）。 */
    private var pendingSubscribeIndex = 0

    /**
     * 向所有具有可写属性的特征发送 AFU 协议握手包。
     *
     * 握手数据包格式（10 字节）：
     *   FD 37 00 00 00 00 00 00 00 37
     * 发送握手是 AFU 体脂秤开始推送体重 + 阻抗数据的必要前提。
     *
     * 兼容处理：
     * - Android 13+（TIRAMISU）：使用新 API [BluetoothGatt.writeCharacteristic]。
     * - 旧版本：使用已废弃的 [BluetoothGattCharacteristic.value] + [BluetoothGatt.writeCharacteristic]。
     *
     * @param gatt 当前 GATT 连接实例。
     */
    private fun sendHandshake(gatt: BluetoothGatt) {
        for (service in gatt.services) {
            val sUuid = service.uuid.toString().uppercase()
            // 跳过标准服务（GAP = 1800, GATT = 1801, WSS = 181D, BCS = 181B, DIS = 180A），只向私有业务服务发握手
            if (sUuid.startsWith("00001800") || sUuid.startsWith("00001801") ||
                sUuid.startsWith("0000181D") || sUuid.startsWith("0000181B") ||
                sUuid.startsWith("0000180A")) continue
            for (char in service.characteristics) {
                val props = char.properties
                // 只向支持 WRITE 或 WRITE_NO_RESPONSE 的特征发送握手
                if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {

                    val handshakeData = byteArrayOf(0xFD.toByte(), 0x37, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x37)
                    val handshakeHex = handshakeData.joinToString(" ") { "%02X".format(it) }
                    AppLogger.i(TAG, "向 ${char.uuid} 发送握手数据包: $handshakeHex")
                    // 根据 Android API 版本选择写入方式
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(char, handshakeData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = handshakeData
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                    }
                }
            }
        }
    }

    /**
     * 顺序订阅 [pendingSubscribeList] 中的下一个特征的通知或指示。
     *
     * BLE 协议要求：同一时刻只能有一个 [BluetoothGatt.writeDescriptor] 操作在途，
     * 因此必须串行订阅，等上一次 [onDescriptorWrite] 回调后再订阅下一个。
     *
     * 订阅步骤：
     * 1. 调用 [BluetoothGatt.setCharacteristicNotification] 在本地开启通知。
     * 2. 向 CCCD（Client Characteristic Configuration Descriptor，UUID 0x2902）
     *    写入 [BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE] 或
     *    [BluetoothGattDescriptor.ENABLE_INDICATION_VALUE]。
     * 3. 若特征无 CCCD 描述符，则直接跳过继续下一个。
     * 4. 全部订阅完成后调用 [sendHandshake] 并将连接状态更新为 [ConnectionState.CONNECTED]。
     *
     * @param gatt 当前 GATT 连接实例。
     */
    private fun subscribeNextCharacteristic(gatt: BluetoothGatt) {
        // 所有特征均已订阅完毕
        if (pendingSubscribeIndex >= pendingSubscribeList.size) {
            AppLogger.i(TAG, "所有特征通知已成功订阅，准备发送握手包")
            // 发送握手包激活体脂秤数据推送
            sendHandshake(gatt)
            _connectionState.value = ConnectionState.CONNECTED
            return
        }
        val characteristic = pendingSubscribeList[pendingSubscribeIndex]
        pendingSubscribeIndex++

        AppLogger.d(TAG, "正在订阅特征 (${pendingSubscribeIndex}/${pendingSubscribeList.size}): ${characteristic.uuid}")
        // 在本地（Android 系统层）开启特征通知路由
        gatt.setCharacteristicNotification(characteristic, true)

        // 获取 CCCD 描述符（标准 UUID 0x2902），向设备端写入以开启通知/指示
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor != null) {
            val props = characteristic.properties
            // 根据特征属性决定写入通知值还是指示值
            val cccdValue = if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE  // 指示：需设备确认
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE  // 通知：无需确认
            }
            // Android 13+（API 33）使用新版 writeDescriptor API，旧版本降级使用已废弃方式
            val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, cccdValue) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = cccdValue
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            AppLogger.d(TAG, "writeDescriptor 结果 [${characteristic.uuid}]: $success")
            if (!success) {
                // writeDescriptor 立即失败时（无需等待回调），直接处理下一个
                subscribeNextCharacteristic(gatt)
            }
            // 成功发起写入后等待 onDescriptorWrite 回调，再继续下一个
        } else {
            // 无 CCCD 描述符，跳过该特征继续下一个
            subscribeNextCharacteristic(gatt)
        }
    }

    // -------------------------------------------------------------------------
    // 无数据超时看门狗
    // -------------------------------------------------------------------------

    /**
     * 用户下秤后无数据包超时定时器。
     * 2.5 秒内若无新数据包到达，则将体重、稳定标志、阻抗全部重置为 0，
     * 确保下一次称重生成全新的记录，而不会叠加到上一次测量结果上。
     */
    private var inactivityRunnable: Runnable? = null

    /**
     * 处理从 GATT 特征通知中接收到的原始数据包。
     *
     * 处理流程：
     * 1. 打印原始十六进制数据日志，便于调试。
     * 2. 重置 2.5 秒无数据超时看门狗定时器。
     * 3. 调用 [AFUPacketParser.parseWeight] 解析体重；
     *    若体重 >= 3.0kg 且协议标志为稳定，则锁定稳定状态并切换到 [ConnectionState.MEASURING]。
     * 4. 调用 [AFUPacketParser.parseImpedance] 解析阻抗，有效时更新阻抗状态流。
     *
     * @param data     特征通知携带的原始字节数据。
     * @param charUuid 发出通知的特征 UUID 字符串（用于日志标记来源）。
     */
    private fun handleIncomingData(data: ByteArray, charUuid: String) {
        // 将原始数据格式化为十六进制字符串打印，记录原始蓝牙数据
        val hexStr = data.joinToString(" ") { "%02X".format(it) }
        val scaleResult = MultiScalePacketParser.parseNotification(data, charUuid)

        val parsedDetails = buildString {
            if (scaleResult != null) {
                append(" [${scaleResult.protocolName}] | 体重=${String.format(Locale.US, "%.2f", scaleResult.weightKg)}kg, 锁定=${scaleResult.isStable}")
                if (scaleResult.impedanceOhm != null) {
                    append(", 阻抗=${scaleResult.impedanceOhm.toInt()}Ω")
                }
            }
        }
        AppLogger.d(TAG, "收到原始蓝牙数据: $hexStr$parsedDetails")

        // 重置 2.5s 无数据包超时定时器（下秤停测后，自动重置状态，确保下一次称重生成全新记录）
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        val watchdog = Runnable {
            AppLogger.d(TAG, "无数据超时 (2.5s): 用户已下秤，重置测量状态")
            // 超时后将所有测量状态重置为 0，为下次称重做准备
            _weight.value = 0.0
            _isStable.value = false
            _impedance.value = 0.0
            if (_connectionState.value == ConnectionState.MEASURING) {
                _connectionState.value = ConnectionState.CONNECTED
            }
        }
        inactivityRunnable = watchdog
        handler.postDelayed(watchdog, 2500)  // 2500ms 无数据则认为用户已下秤

        // 更新测量结果
        scaleResult?.let { result ->
            _weight.value = result.weightKg
            if (result.weightKg > 0.0) {
                // 只有当体重 >= 3.0kg 时才判定为有效稳定锁定（防止单脚踩秤或轻微压秤时的误锁定）
                val validStable = result.isStable && (result.weightKg >= 3.0)
                _isStable.value = validStable
                if (validStable) {
                    // 体重稳定锁定后切换到测量状态，通知 UI 开始体成分计算
                    _connectionState.value = ConnectionState.MEASURING
                }
            } else if (result.weightKg <= 0.0 && result.impedanceOhm == null) {
                // 仅当体重为 0 且无阻抗数据时，才清除稳定标志和阻抗
                _isStable.value = false
                _impedance.value = 0.0
            }

            result.impedanceOhm?.let { imp ->
                if (imp > 0.0) {
                    _impedance.value = imp
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // GATT 事件回调
    // -------------------------------------------------------------------------

    /**
     * GATT 协议事件回调，处理连接状态变化、服务发现、描述符写入及特征值通知四类事件。
     */
    private val gattCallback = object : BluetoothGattCallback() {

        /**
         * GATT 连接状态变化回调。
         *
         * 处理逻辑：
         * - status != GATT_SUCCESS：连接失败，关闭 GATT 并回退到扫描。
         * - newState == STATE_CONNECTED：成功建立物理连接，
         *   立即请求高优先级连接参数并发起服务发现。
         * - newState == STATE_DISCONNECTED：设备断开，清理资源并重置状态。
         *
         * @param gatt     GATT 客户端实例。
         * @param status   操作结果状态码（[BluetoothGatt.GATT_SUCCESS] 为成功）。
         * @param newState 新的连接状态（[BluetoothProfile.STATE_CONNECTED] 或 [BluetoothProfile.STATE_DISCONNECTED]）。
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            AppLogger.i(TAG, "GATT 连接状态变更: status=$status, newState=$newState")
            // 连接事件发生，取消超时看门狗
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // GATT 操作失败（如被远端断开、连接超时等）
                AppLogger.e(TAG, "GATT 连接失败 (status=$status)，当前重试次数: $gattRetryCount / $MAX_GATT_RETRY")
                _connectionState.value = ConnectionState.IDLE
                try {
                    gatt.close()
                } catch (e: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                if (gattRetryCount < MAX_GATT_RETRY) {
                    // 指数退避：每次失败延迟加倍（1s, 2s, 4s, 8s, 16s），最长 30s
                    val delayMs = (1000L * (1 shl gattRetryCount)).coerceAtMost(30_000L)
                    gattRetryCount++
                    AppLogger.w(TAG, "将在 ${delayMs}ms 后自动重连（第 $gattRetryCount 次）")
                    handler.postDelayed({ startScan() }, delayMs)
                } else {
                    // 超出最大重试次数：停止自动重连，让用户手动操作
                    gattRetryCount = 0
                    AppLogger.e(TAG, "已达最大重试次数 ($MAX_GATT_RETRY)，停止自动重连，请手动重试")
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 物理连接建立成功，重置重试计数器
                gattRetryCount = 0
                _connectionState.value = ConnectionState.CONNECTING
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // 发起 GATT 服务发现，触发 onServicesDiscovered 回调
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // 设备断开连接，清理全部资源和状态
                AppLogger.i(TAG, "设备已断开连接")
                _connectionState.value = ConnectionState.IDLE
                _weight.value = 0.0
                _isStable.value = false
                _impedance.value = 0.0
                try {
                    gatt.close()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "断开连接关闭 GATT 异常: ${e.message}")
                }
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
            }
        }

        /**
         * GATT 服务发现完成回调。
         *
         * 成功后遍历所有非系统服务（过滤 GAP/GATT 通用服务），
         * 收集所有支持 Notify 或 Indicate 的特征加入待订阅队列，
         * 然后调用 [subscribeNextCharacteristic] 开始串行订阅流程。
         *
         * @param gatt   GATT 客户端实例。
         * @param status 发现结果状态码。
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            AppLogger.i(TAG, "GATT 服务发现完成: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 清空订阅队列，准备重新收集
                pendingSubscribeList.clear()
                pendingSubscribeIndex = 0

                // Search ALL services for Notify/Indicate characteristics
                // 遍历所有 GATT 服务，收集可订阅的特征
                for (service in gatt.services) {
                    val sUuid = service.uuid.toString().uppercase()
                    // 跳过 GAP（0x1800）和 GATT（0x1801）通用服务，只关注业务服务
                    if (sUuid.startsWith("00001800") || sUuid.startsWith("00001801")) continue

                    for (char in service.characteristics) {
                        val props = char.properties
                        // 收集支持 NOTIFY 或 INDICATE 的特征，这些特征会主动推送测量数据
                        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                            pendingSubscribeList.add(char)
                        }
                    }
                }

                AppLogger.i(TAG, "共找到 ${pendingSubscribeList.size} 个通知特征")
                // 开始串行订阅流程（第一个特征）
                subscribeNextCharacteristic(gatt)
            }
        }

        /**
         * GATT 描述符写入完成回调。
         *
         * 每次 [BluetoothGatt.writeDescriptor] 操作完成（成功或失败）后触发，
         * 继续串行订阅队列中的下一个特征。
         *
         * @param gatt       GATT 客户端实例。
         * @param descriptor 已完成写入的描述符。
         * @param status     写入操作结果状态码。
         */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            AppLogger.d(TAG, "描述符写入完成: status=$status, 特征: ${descriptor.characteristic?.uuid}")
            // 无论成功或失败，继续订阅队列中的下一个特征
            subscribeNextCharacteristic(gatt)
        }

        // Android 13+ (API 33+) overload
        /**
         * 特征值变化通知回调（Android 13+ / API 33+ 新签名）。
         *
         * Android 13 起引入此新版重载，直接将通知数据通过参数传入，
         * 不再需要从 [BluetoothGattCharacteristic.value] 读取（已废弃）。
         *
         * @param gatt           GATT 客户端实例。
         * @param characteristic 发出通知的特征。
         * @param value          通知携带的最新数据字节数组。
         */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingData(value, characteristic.uuid.toString())
        }

        // Older Android versions overload
        /**
         * 特征值变化通知回调（Android 12 及以下旧版签名，已废弃但仍需兼容）。
         *
         * 旧版 API 需从 [BluetoothGattCharacteristic.value] 属性读取数据，
         * 此方法在 Android 13+ 上不会被调用（系统优先调用上方新版重载）。
         *
         * @param gatt           GATT 客户端实例。
         * @param characteristic 发出通知的特征（数据从 [BluetoothGattCharacteristic.value] 读取）。
         */
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            handleIncomingData(data, characteristic.uuid.toString())
        }
    }
}
