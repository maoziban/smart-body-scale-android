package com.example.dianzicheng.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.ScaleModel
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
    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** 当前活跃的 GATT 连接实例；未连接时为 null。 */
    private var bluetoothGatt: BluetoothGatt? = null

    /** 支持的体脂秤/体重秤服务 UUID 集合，用于广播过滤与服务匹配 */
    private val SUPPORTED_SERVICE_UUIDS = setOf(
        MultiScalePacketParser.UUID_SERVICE_AFU,              // 0xFFB0 (AFU 私有协议)
        MultiScalePacketParser.UUID_SERVICE_SIG_WSS,         // 0x181D (蓝牙 SIG 标准体重秤)
        MultiScalePacketParser.UUID_SERVICE_SIG_BCS,         // 0x181B (蓝牙 SIG 标准体脂秤/小米)
        MultiScalePacketParser.UUID_SERVICE_CHIPSEA_OKOK,    // 0xFFF0 (芯海科技 / OKOK 方案)
        MultiScalePacketParser.UUID_SERVICE_GENERIC_FFE0,    // 0xFFE0 (通用透传秤)
        MultiScalePacketParser.UUID_SERVICE_XIAOMI_WECHAT,   // 0xFEE7 (小米/微信运动秤)
        UUID.fromString("0000FFA0-0000-1000-8000-00805F9B34FB"), // Yolanda / Fitdays / 沃莱扩展
        UUID.fromString("0000FFE5-0000-1000-8000-00805F9B34FB"), // 芯海 / OKOK 扩展
        UUID.fromString("0000FEE0-0000-1000-8000-00805F9B34FB")  // 微信硬件 / 小米扩展
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

    /** 扫描发现的所有候选体脂秤列表（供用户手动选择配对）。 */
    private val _discoveredScales = MutableStateFlow<List<DiscoveredScaleDevice>>(emptyList())
    /** 扫描发现的所有候选体脂秤列表（外部只读）。 */
    val discoveredScales: StateFlow<List<DiscoveredScaleDevice>> = _discoveredScales

    // -------------------------------------------------------------------------
    // 连接状态枚举与设备模型
    // -------------------------------------------------------------------------

    /**
     * 扫描发现的候选体脂秤设备数据模型。
     */
    data class DiscoveredScaleDevice(
        val name: String,
        val address: String,
        val rssi: Int = 0,
        val isBroadcastScale: Boolean = false,
        val isWifiScale: Boolean = false,
        val isConfirmedScale: Boolean = true,
        val matchedModel: ScaleModel = ScaleModel.AUTO,
        val protocolName: String = "",
        val liveWeightKg: Double? = null
    )

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

    /** 用户手动指定的体脂秤型号偏好（默认为 AUTO 智能自动识别） */
    var preferredModel: ScaleModel = ScaleModel.AUTO

    /** 上次成功配对并记住的设备 MAC 地址。只有该值非空时，才允许自动连接。 */
    var lastPairedMac: String? = null

    /**
     * 是否处于显式手动配对模式。
     * 处于配对模式时，扫描器绝不自动连接任何设备，仅将符合特征的设备收集至 [discoveredScales] 供用户手动选择。
     */
    var isPairingMode: Boolean = false

    /** MAC 地址首次确认回调，用于将新配对的 MAC 持久化到外部存储。 */
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
    private fun parseNameFromBytes(bytes: ByteArray?): String? = MultiScalePacketParser.parseNameFromBytes(bytes)


    /**
     * 根据设备名称与广播包特征，推断其对应的体脂秤型号枚举。
     */
    fun detectMatchedModel(deviceName: String?, scanRecord: android.bluetooth.le.ScanRecord?): ScaleModel {
        val name = deviceName ?: ""
        // 优先检查 Service UUID 是否包含 AFU 专属服务 (0xFFB0)，或设备名明确包含 AFU/WL-TZ/TZ-A1
        if (scanRecord?.serviceUuids?.any { it.uuid.toString().uppercase().contains("FFB0") } == true ||
            name.contains("AFU", ignoreCase = true) ||
            name.contains("WL-TZ", ignoreCase = true) ||
            name.contains("TZ-A1", ignoreCase = true)) {
            return ScaleModel.AFU_PROTOCOL
        }

        val advData = MultiScalePacketParser.parseAdvertisement(scanRecord, preferredModel)
        if (advData?.protocolName?.contains("Xiaomi", ignoreCase = true) == true) {
            return if (advData.protocolName.contains("2")) ScaleModel.XIAOMI_SCALE_2 else ScaleModel.XIAOMI_SCALE_1
        }
        if (advData?.protocolName?.contains("Yolanda", ignoreCase = true) == true ||
            advData?.protocolName?.contains("Boohee", ignoreCase = true) == true) {
            return ScaleModel.BOOHEE_YOLANDA
        }
        if (name.contains("Yolanda", ignoreCase = true) || name.contains("Boohee", ignoreCase = true) ||
            name.contains("薄荷", ignoreCase = true) || name.contains("轻牛", ignoreCase = true) ||
            name.startsWith("BH_", ignoreCase = true) || name.startsWith("BH-", ignoreCase = true) ||
            name.contains("Fitdays", ignoreCase = true) || name.contains("合泰", ignoreCase = true)) {
            return ScaleModel.BOOHEE_YOLANDA
        }
        if (name.startsWith("MIBFS", ignoreCase = true) || name.contains("米家", ignoreCase = true) ||
            name.contains("小米", ignoreCase = true) || name.startsWith("MISCALE", ignoreCase = true) ||
            name.equals("MI_SCALE", ignoreCase = true)) {
            return ScaleModel.XIAOMI_SCALE_2
        }
        if (name.contains("OKOK", ignoreCase = true) || name.contains("Chipsea", ignoreCase = true) ||
            name.contains("芯海", ignoreCase = true) || name.startsWith("CS-", ignoreCase = true)) {
            return ScaleModel.OKOK_CHIPSEA
        }
        if (name.contains("Senssun", ignoreCase = true) || name.contains("香山", ignoreCase = true) ||
            name.contains("CAMRY", ignoreCase = true)) {
            return ScaleModel.SENSSUN
        }
        if (name.startsWith("S9", ignoreCase = true) || name.startsWith("zS7", ignoreCase = true)) {
            return ScaleModel.PHICOMM_S9
        }
        return ScaleModel.AUTO
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
        val deviceName = device.name ?: scanRecord?.deviceName ?: MultiScalePacketParser.parseNameFromBytes(rawBytes)

        // 1. 已配对过的 MAC 地址精确匹配
        val isMatchedMac = !lastPairedMac.isNullOrEmpty() && device.address.equals(lastPairedMac, ignoreCase = true)
        if (isMatchedMac) return true

        // 2. 检查黑名单厂商（Apple、Google、Microsoft、Samsung、Huawei 等设备绝对不是体脂秤）
        if (rawBytes != null && rawBytes.size >= 4) {
            var i = 0
            while (i < rawBytes.size - 3) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if (type == 0xFF && len >= 3) {
                    val companyId = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    if (companyId in MultiScalePacketParser.NON_SCALE_COMPANY_IDS) {
                        val hasExplicitScaleName = deviceName?.let { name ->
                            name.contains("Scale", ignoreCase = true) ||
                            name.contains("Weight", ignoreCase = true) ||
                            name.contains("体脂", ignoreCase = true) ||
                            name.contains("电子秤", ignoreCase = true) ||
                            name.contains("体重", ignoreCase = true)
                        } == true
                        if (!hasExplicitScaleName) {
                            return false
                        }
                    }
                }
                i += len + 1
            }
        }

        // 3. 支持的体脂秤/体重秤 Service UUID 匹配（含 AFU、SIG WSS/BCS、OKOK/芯海等）
        val hasService = serviceUuids?.any { it.uuid in SUPPORTED_SERVICE_UUIDS } == true
        if (hasService) return true

        // 4. 广播数据包直接可解析出有效体重（如小米秤 Service Data、免配对广播秤）
        if (MultiScalePacketParser.parseAdvertisement(scanRecord, preferredModel) != null) {
            return true
        }

        // 5. 称重设备常见品牌与关键词精确匹配（覆盖主流与小众品牌）
        val nameMatched = deviceName?.let { name ->
            name.contains("AFU", ignoreCase = true) ||
            name.contains("WL-TZ", ignoreCase = true) ||
            name.contains("TZ-A1", ignoreCase = true) ||
            name.contains("Scale", ignoreCase = true) ||
            name.contains("Weight", ignoreCase = true) ||
            name.contains("体脂", ignoreCase = true) ||
            name.contains("电子秤", ignoreCase = true) ||
            name.contains("体重", ignoreCase = true) ||
            name.contains("称重", ignoreCase = true) ||
            name.contains("健康秤", ignoreCase = true) ||
            name.startsWith("MIBFS", ignoreCase = true) ||
            name.startsWith("MISCALE", ignoreCase = true) ||
            name.contains("小米", ignoreCase = true) ||
            name.contains("米家", ignoreCase = true) ||
            name.equals("MI_SCALE", ignoreCase = true) ||
            name.contains("OKOK", ignoreCase = true) ||
            name.contains("Yolanda", ignoreCase = true) ||
            name.contains("Senssun", ignoreCase = true) ||
            name.contains("ICOMON", ignoreCase = true) ||
            name.contains("沃莱", ignoreCase = true) ||
            name.contains("BOOHEE", ignoreCase = true) ||
            name.contains("薄荷", ignoreCase = true) ||
            name.startsWith("BH_", ignoreCase = true) ||
            name.startsWith("BH-", ignoreCase = true) ||
            name.contains("香山", ignoreCase = true) ||
            name.contains("CAMRY", ignoreCase = true) ||
            name.contains("云麦", ignoreCase = true) ||
            name.contains("YUNMAI", ignoreCase = true) ||
            name.contains("Phicomm", ignoreCase = true) ||
            name.contains("斐讯", ignoreCase = true) ||
            name.startsWith("zS7", ignoreCase = true) ||
            name.startsWith("S7_", ignoreCase = true) ||
            name.startsWith("S9_", ignoreCase = true) ||
            name.equals("S7", ignoreCase = true) ||
            name.equals("S9", ignoreCase = true) ||
            name.contains("Picooc", ignoreCase = true) ||
            name.contains("有品", ignoreCase = true) ||
            name.contains("Keep", ignoreCase = true) ||
            name.contains("Fitdays", ignoreCase = true) ||
            name.contains("QNDoctor", ignoreCase = true) ||
            name.contains("QN-", ignoreCase = true) ||
            name.contains("轻牛", ignoreCase = true) ||
            name.contains("Sinocare", ignoreCase = true) ||
            name.contains("三诺", ignoreCase = true) ||
            name.contains("Omron", ignoreCase = true) ||
            name.contains("欧姆龙", ignoreCase = true) ||
            name.contains("Tanita", ignoreCase = true) ||
            name.contains("百利达", ignoreCase = true) ||
            name.contains("Lifesense", ignoreCase = true) ||
            name.contains("乐心", ignoreCase = true) ||
            name.contains("InBody", ignoreCase = true) ||
            name.contains("CHIPSEA", ignoreCase = true) ||
            name.contains("芯海", ignoreCase = true) ||
            name.startsWith("CS-", ignoreCase = true) ||
            name.contains("BT_SCALE", ignoreCase = true) ||
            name.contains("BLE_SCALE", ignoreCase = true) ||
            name.contains("SMART_SCALE", ignoreCase = true) ||
            name.contains("SWAN", ignoreCase = true) ||
            name.startsWith("SW-", ignoreCase = true) ||
            name.contains("iChoice", ignoreCase = true) ||
            name.startsWith("CF-", ignoreCase = true) ||
            name.startsWith("CF", ignoreCase = true) ||
            name.startsWith("WS-", ignoreCase = true) ||
            name.startsWith("YG", ignoreCase = true) ||
            name.startsWith("C08", ignoreCase = true) ||
            name.startsWith("C09", ignoreCase = true) ||
            name.startsWith("C10", ignoreCase = true) ||
            name.contains("合泰", ignoreCase = true)
        } == true
        if (nameMatched) return true

        // 6. 检查广播 AD 结构中是否声明了蓝牙 SIG 标准外观类型 (Appearance = 0x0400..0x043F 体重/体脂秤)
        if (rawBytes != null && rawBytes.size >= 4) {
            var i = 0
            while (i < rawBytes.size - 3) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if (type == 0x19 && len >= 3) { // 0x19 = Appearance
                    val appearance = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    if (appearance in 0x0400..0x043F) return true
                }
                i += len + 1
            }
        }

        // 7. 解析 BLE 广播数据中的厂商自定义数据包结构 (0xFF 或 0x16)，校验已知秤芯片 Company ID 与专属协议特征
        if (rawBytes != null && rawBytes.size >= 4) {
            var i = 0
            while (i < rawBytes.size - 2) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if (type == 0xFF && len >= 3) {
                    val companyId = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                    // 0x01A7 (Yolanda / Fitdays), 0x0157 (Huami / Xiaomi), 0x0590 (Chipsea), 0x0209 (Yolanda 变体), 0x00D2 (Dialog), 0x01DA (Telink)
                    if (companyId in listOf(0x01A7, 0x0157, 0x0590, 0x0209, 0x00D2, 0x01DA)) return true
                }
                if (type == 0x16 && len >= 2) {
                    val b0 = rawBytes[i + 2].toInt() and 0xFF
                    // 服务数据首字节为 0xAC / 0xA2 / 0xA3 / 0xCA 时判定为 Yolanda/薄荷
                    if (b0 == 0xAC || b0 == 0xA2 || b0 == 0xA3 || b0 == 0xCA) return true
                }
                i += len + 1
            }
        }

        return false
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

            // 模式 1：处于手动配对模式，或尚未记住任何设备
            if (isPairingMode || lastPairedMac.isNullOrEmpty()) {
                val isScale = isScaleAdvertisement(result)
                val rawBytes = result.scanRecord?.bytes
                val parsedName = device.name ?: result.scanRecord?.deviceName ?: MultiScalePacketParser.parseNameFromBytes(rawBytes)

                // 检查设备是否来自已知非秤厂商（Apple、Google、Microsoft、Samsung、Huawei 等）
                var isBlacklistedVendor = false
                if (rawBytes != null && rawBytes.size >= 4) {
                    var i = 0
                    while (i < rawBytes.size - 3) {
                        val len = rawBytes[i].toInt() and 0xFF
                        if (len == 0 || i + len >= rawBytes.size) break
                        val type = rawBytes[i + 1].toInt() and 0xFF
                        if (type == 0xFF && len >= 3) {
                            val companyId = (rawBytes[i + 2].toInt() and 0xFF) or ((rawBytes[i + 3].toInt() and 0xFF) shl 8)
                            if (companyId in MultiScalePacketParser.NON_SCALE_COMPANY_IDS) {
                                isBlacklistedVendor = true
                                break
                            }
                        }
                        i += len + 1
                    }
                }

                // 过滤规则：
                // 1. 确认为体脂秤的设备：加入列表；
                // 2. 属于已知非秤厂商：严禁加入任何列表，杜绝视觉污染与误导；
                // 3. 未确认为秤的设备：仅当有明确设备名（非匿名空串）且信号较强 (RSSI >= -75 dBm) 时，作为逃生兜底进入折叠列表。
                val shouldInclude = if (isScale) {
                    true
                } else if (!isBlacklistedVendor && !parsedName.isNullOrBlank() && result.rssi >= -75) {
                    true
                } else {
                    false
                }

                if (shouldInclude) {
                    val advScaleData = if (isScale) MultiScalePacketParser.parseAdvertisement(result.scanRecord, preferredModel) else null
                    val liveWeight = if (isScale && advScaleData != null && advScaleData.weightKg in 3.0..350.0) advScaleData.weightKg else null
                    val matchedModel = detectMatchedModel(parsedName, result.scanRecord)
                    val isXiaomi = matchedModel == ScaleModel.XIAOMI_SCALE_1 || matchedModel == ScaleModel.XIAOMI_SCALE_2 ||
                                   parsedName?.startsWith("MIBFS", ignoreCase = true) == true ||
                                   parsedName?.startsWith("MISCALE", ignoreCase = true) == true ||
                                   parsedName?.contains("小米", ignoreCase = true) == true ||
                                   parsedName?.contains("米家", ignoreCase = true) == true
                    val isBroadcast = advScaleData != null || isXiaomi

                    val defaultName = if (isScale) {
                        if (advScaleData != null && advScaleData.protocolName.isNotBlank()) {
                            "${advScaleData.protocolName} (${device.address.takeLast(5)})"
                        } else if (matchedModel != ScaleModel.AUTO) {
                            "${matchedModel.displayName} (${device.address.takeLast(5)})"
                        } else {
                            "体脂秤 (${device.address.takeLast(5)})"
                        }
                    } else {
                        parsedName ?: "蓝牙设备 (${device.address.takeLast(5)})"
                    }
                    val name = parsedName ?: defaultName

                    val currentList = _discoveredScales.value.toMutableList()
                    val existingIndex = currentList.indexOfFirst { it.address.equals(device.address, ignoreCase = true) }
                    val item = DiscoveredScaleDevice(
                        name = name,
                        address = device.address,
                        rssi = result.rssi,
                        isBroadcastScale = isBroadcast,
                        isWifiScale = false,
                        isConfirmedScale = isScale,
                        matchedModel = matchedModel,
                        protocolName = advScaleData?.protocolName ?: matchedModel.protocolName,
                        liveWeightKg = liveWeight
                    )
                    if (existingIndex >= 0) {
                        currentList[existingIndex] = item
                    } else {
                        currentList.add(item)
                    }
                    // 排序规则：
                    // 1. 正在踩秤称重中（含有实时体重数据 > 0）的设备排在最最顶端！
                    // 2. 若用户指定了型号偏好 (preferredModel != AUTO)，与该型号匹配的设备排在顶端
                    // 3. 确认为体脂秤或 Wi-Fi 秤的设备排在未确认的普通蓝牙设备前面
                    // 4. 最后按信号强度 (RSSI) 降序排列
                    currentList.sortWith(
                        compareByDescending<DiscoveredScaleDevice> {
                            (it.liveWeightKg != null && it.liveWeightKg > 0.0)
                        }.thenByDescending {
                            preferredModel != ScaleModel.AUTO && it.matchedModel == preferredModel
                        }.thenByDescending {
                            it.isConfirmedScale || it.isWifiScale
                        }.thenByDescending {
                            it.rssi
                        }
                    )
                    _discoveredScales.value = currentList
                    if (isScale) {
                        _discoveredDevice.value = Pair(name, device.address)
                    }
                }
                // 【核心保护】：手动配对阶段仅收集设备列表，绝不自动发起连接，等待用户手动点击！
                return
            }

            // 模式 2：已记住设备，严格执行自动连接（仅连接该设备，杜绝误连周围无关设备）
            val targetMac = lastPairedMac ?: return
            if (!device.address.equals(targetMac, ignoreCase = true)) {
                // 非已绑定的设备，严格忽略
                return
            }

            // 确认是已记住的目标设备后，按协议处理
            if (isScaleAdvertisement(result)) {
                val name = device.name ?: result.scanRecord?.deviceName ?: parseNameFromBytes(result.scanRecord?.bytes) ?: "体脂秤设备 (${device.address.takeLast(5)})"

                // 尝试直接从广播数据解析（如小米秤、免配对广播秤即踩即读）
                val advScaleData = MultiScalePacketParser.parseAdvertisement(result.scanRecord, preferredModel)
                if (advScaleData != null) {
                    // Bug #25 修复：只要 parseAdvertisement 返回非 null，就判定为广播秤，统一在此处理。
                    // 原条件 "advScaleData.weightKg > 0.0" 会导致离秤 0kg 包 fall-through 到下方 GATT 连接逻辑，
                    // 从而错误地对广播秤发起 GATT 连接。
                    if (advScaleData.weightKg > 0.0) {
                        _weight.value = advScaleData.weightKg
                        val validStable = advScaleData.isStable && (advScaleData.weightKg >= 3.0)
                        _isStable.value = validStable
                        if (validStable) {
                            _connectionState.value = ConnectionState.MEASURING
                            // 仅当示数锁定且有明确阻抗上报时接收阻抗
                            if (advScaleData.impedanceOhm != null && advScaleData.impedanceOhm > 0.0 && advScaleData.weightKg >= 3.0) {
                                _impedance.value = advScaleData.impedanceOhm
                            }
                        } else {
                            if (_connectionState.value != ConnectionState.MEASURING) {
                                _connectionState.value = ConnectionState.CONNECTED
                            }
                            // 实时动态示数阶段强制清零阻抗，杜绝上一轮或爬升中残留
                            _impedance.value = 0.0
                        }
                    } else {
                        // 广播秤下秤包（weightKg == 0.0）：立即归零体重与稳定状态，切回已连接状态
                        AppLogger.d(TAG, "广播秤下秤包 (0.0kg)：归零测量状态")
                        _weight.value = 0.0
                        _isStable.value = false
                        _impedance.value = 0.0
                        if (_connectionState.value == ConnectionState.MEASURING) {
                            _connectionState.value = ConnectionState.CONNECTED
                        }
                    }

                    _discoveredDevice.value = Pair(name, device.address)

                    // 重置 2.5s 无广播数据超时定时器（无论体重是否为 0 均重置，避免超时回调再次归零干扰）
                    inactivityRunnable?.let { handler.removeCallbacks(it) }
                    val watchdog = Runnable {
                        AppLogger.d(TAG, "无广播数据超时 (2.5s): 用户已下秤，重置测量状态")
                        _weight.value = 0.0
                        _isStable.value = false
                        _impedance.value = 0.0
                        if (_connectionState.value == ConnectionState.MEASURING) {
                            _connectionState.value = ConnectionState.CONNECTED
                        }
                    }
                    inactivityRunnable = watchdog
                    handler.postDelayed(watchdog, 2500)

                    // 广播秤保持扫描流以持续接收实时示数，直接返回，不走 GATT 连接逻辑
                    return
                }

                // 非广播秤（AFU / SIG / OKOK 等需 GATT 双向通信的设备）：建立 GATT 连接
                // 【核心保护】：若当前已在连接中或已建立连接/测量中，绝不重复发起 GATT 连接，防止多广播包碰撞
                if (_connectionState.value == ConnectionState.CONNECTING ||
                    _connectionState.value == ConnectionState.CONNECTED ||
                    _connectionState.value == ConnectionState.MEASURING) {
                    return
                }
                AppLogger.i(TAG, "已发现已记住的体脂秤，自动连接 GATT: $name [${device.address}]")
                _discoveredDevice.value = Pair(name, device.address)
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLogger.e(TAG, "BLE 扫描失败，错误码: $errorCode")
            isScanning = false
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                isScanning = true
                _connectionState.value = ConnectionState.SCANNING
                return
            }
            _connectionState.value = ConnectionState.IDLE
            if (scanRetryCount < 3) {
                // 指数退避重试 (2s, 4s, 8s)，避免高频重试导致被系统 BLE 栈永久拉黑
                val delay = 2000L * (1 shl scanRetryCount)
                scanRetryCount++
                AppLogger.w(TAG, "扫描失败，将于 ${delay}ms 后进行第 $scanRetryCount 次重试 (errorCode: $errorCode)")
                handler.postDelayed({
                    if (_connectionState.value == ConnectionState.IDLE) {
                        startScan()
                    }
                }, delay)
            } else {
                AppLogger.e(TAG, "扫描重试已达上限 (3次)，停止自动重试，请手动触发")
            }
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
    // 内部定时器工具与扫描状态
    // -------------------------------------------------------------------------

    /** 是否正在扫描中 */
    @Volatile
    var isScanning: Boolean = false
        private set

    /** 扫描失败连续重试计数 */
    private var scanRetryCount = 0

    /** GATT 连接超时看门狗 Runnable，8 秒内未完成连接则回退到扫描状态。 */
    private var connectTimeoutRunnable: Runnable? = null

    /** 待执行的 GATT 连接调度任务（延迟 250ms 防射频冲突） */
    private var pendingConnectRunnable: Runnable? = null

    /** 单个特征描述符写入超时看门狗 Runnable，2.5 秒内未收到 onDescriptorWrite 则跳过。 */
    private var descriptorTimeoutRunnable: Runnable? = null

    /** 主线程 Handler，用于延迟任务（看门狗定时器、扫描重试等）。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // 公开方法：蓝牙与定位状态查询
    // -------------------------------------------------------------------------

    /**
     * 检查系统蓝牙是否已开启。
     *
     * @return 蓝牙适配器存在且已启用时返回 true，否则返回 false。
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * 检查系统定位服务是否开启 (部分 Android 系统或国产 ROM 需开启定位服务才能扫描 BLE 设备)。
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    // -------------------------------------------------------------------------
    // 扫描控制
    // -------------------------------------------------------------------------

    /**
     * 启动低能耗蓝牙（BLE）主动扫描。
     *
     * 启动前会：
     * 1. 检查是否满足扫描条件：非配对模式下若未记住任何设备，直接跳过并设为 IDLE。
     * 2. 重置体重、稳定、阻抗状态为初始值。
     * 3. 清理旧 GATT 连接和看门狗定时器，防止资源泄漏。
     * 4. 以 [ScanSettings.SCAN_MODE_LOW_LATENCY] 低延迟模式扫描，报告延迟为 0（即时回调）。
     *
     * 若蓝牙适配器不可用或未启用，则直接将状态设为 [ConnectionState.IDLE] 并返回。
     */
    fun startScan() {
        // 重置所有测量数据，确保下一次称重得到干净的初始状态
        _weight.value = 0.0
        _isStable.value = false
        _impedance.value = 0.0

        // 若当前已在扫描中且连接状态也是 SCANNING，无需重复销毁与重建扫描器，避免触发系统频控
        if (isScanning && _connectionState.value == ConnectionState.SCANNING) {
            AppLogger.d(TAG, "BLE 扫描已处于运行状态，无需重复启动")
            return
        }

        // 取消任何待发起的 GATT 连接，确保扫描环境干净
        pendingConnectRunnable?.let { handler.removeCallbacks(it) }
        pendingConnectRunnable = null

        // 【关键保护】：非配对模式下，如果没有记住任何设备，绝不执行盲目扫描和自动连接
        if (!isPairingMode && lastPairedMac.isNullOrEmpty()) {
            AppLogger.i(TAG, "尚未记住任何体脂秤设备，跳过自动连接扫描（请先进行手动配对）")
            _connectionState.value = ConnectionState.IDLE
            isScanning = false
            return
        }

        val adapter = bluetoothAdapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            // 记录详细原因：无适配器 / BT 未开启 / Scanner 为 null
            val reason = if (adapter == null) "No BT Adapter" else if (!adapter.isEnabled) "BT Disabled" else "Scanner Null"
            AppLogger.e(TAG, "蓝牙扫描器不可用: $reason")
            _connectionState.value = ConnectionState.IDLE
            isScanning = false
            return
        }

        // 取消旧的连接超时与描述符超时看门狗，防止误触发
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
        descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
        descriptorTimeoutRunnable = null

        // 关闭旧的 GATT 连接，释放系统蓝牙资源
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {}
        bluetoothGatt = null

        AppLogger.i(TAG, "启动 BLE 扫描 (isPairingMode: $isPairingMode, pairedMac: $lastPairedMac)...")
        _connectionState.value = ConnectionState.SCANNING

        // 构建低延迟与高召回扫描参数配置（针对 MIUI/HyperOS/ColorOS/OriginOS 启用 Aggressive 模式）
        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // 最低延迟，最快发现设备
            .setReportDelay(0)                                 // 不批量缓存，立即回调

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            settingsBuilder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            settingsBuilder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            settingsBuilder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        }
        val settings = settingsBuilder.build()

        // 先尝试停止旧扫描（防止重复启动导致系统报错）
        try {
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {}

        // 以无过滤器方式启动扫描（由 isScaleAdvertisement 逻辑层过滤）
        try {
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            scanRetryCount = 0
        } catch (e: Exception) {
            isScanning = false
            AppLogger.e(TAG, "启动 BLE 扫描失败: ${e.message}")
            _connectionState.value = ConnectionState.IDLE
        }
    }

    /**
     * 启动设备手动配对扫描。
     * 处于配对模式时绝不自动连接任何设备，仅上报扫描到的候选秤列表供用户手动选择。
     */
    fun startPairingScan() {
        AppLogger.i(TAG, "启动设备手动配对扫描...")
        isPairingMode = true
        _discoveredScales.value = emptyList()
        _discoveredDevice.value = null
        startScan()
    }

    /**
     * 用户手动选择设备并发起连接与记住。
     *
     * @param device 用户在界面上点击的目标秤设备
     */
    fun manualConnect(device: DiscoveredScaleDevice) {
        isPairingMode = false
        stopScan()
        val bluetoothAdapter = bluetoothAdapter ?: return
        val remoteDevice = try {
            bluetoothAdapter.getRemoteDevice(device.address)
        } catch (e: Exception) {
            AppLogger.e(TAG, "无效的 MAC 地址: ${device.address}: ${e.message}")
            return
        }

        AppLogger.i(TAG, "用户手动连接设备: ${device.name} [${device.address}], 广播秤: ${device.isBroadcastScale}")
        _discoveredDevice.value = Pair(device.name, device.address)
        lastPairedMac = device.address
        onMacDiscovered?.invoke(device.address)

        if (device.isBroadcastScale) {
            // 广播秤（如小米体脂秤）：已记住该 MAC，直接设为已连接
            _connectionState.value = ConnectionState.CONNECTED
            // 延迟 500ms 再启动自动监听扫描，留足时间给上层 UI 捕获 CONNECTED 状态并完成配对保存与导航
            handler.postDelayed({
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    startScan()
                }
            }, 500L)
        } else {
            // GATT 秤：发起 GATT 连接
            connect(remoteDevice)
        }
    }

    /**
     * 停止正在进行的 BLE 扫描。
     *
     * 若当前状态为 [ConnectionState.SCANNING]，则将状态重置为 [ConnectionState.IDLE]。
     * 如果调用时扫描已停止，忽略异常静默处理。
     */
    fun stopScan() {
        isScanning = false
        pendingConnectRunnable?.let { handler.removeCallbacks(it) }
        pendingConnectRunnable = null
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
     * - 配对模式标记与候选设备列表
     * - 无数据超时定时器（inactivityRunnable）
     * - 连接超时看门狗（connectTimeoutRunnable）
     * - 待执行连接任务（pendingConnectRunnable）
     * - 描述符超时看门狗（descriptorTimeoutRunnable）
     * - GATT 连接实例
     * - 已配对 MAC 记录（lastPairedMac）
     * - 已发现设备信息（discoveredDevice）
     * - 所有测量数据（体重、稳定标志、阻抗）
     */
    fun disconnectAndReset() {
        AppLogger.i(TAG, "断开连接并重置蓝牙状态")
        isPairingMode = false
        isScanning = false
        scanRetryCount = 0
        _discoveredScales.value = emptyList()
        // 取消无数据超时定时器
        inactivityRunnable?.let { handler.removeCallbacks(it) }
        inactivityRunnable = null
        // 取消待执行的连接任务与看门狗
        pendingConnectRunnable?.let { handler.removeCallbacks(it) }
        pendingConnectRunnable = null
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
        // 取消描述符超时看门狗
        descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
        descriptorTimeoutRunnable = null

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
     * 1. 防重入保护：已处于连接中/已连接/测量中时直接忽略。
     * 2. 立即将状态更新为 CONNECTING，屏蔽后续重复广播包。
     * 3. 立即调用 stopScan 停止扫描，释放 BLE 射频资源。
     * 4. 延迟 250ms 后在主线程调度关闭旧 GATT 并调用 connectGatt，彻底杜绝 Status 133 / GATT_ERROR。
     * 5. 设置 8 秒连接超时看门狗：若 8 秒内 GATT 未建立则回退到扫描。
     * 6. 在 Android M+ 上强制使用 LE 传输通道。
     *
     * @param device 目标蓝牙设备对象。
     */
    private fun connect(device: BluetoothDevice) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.MEASURING) {
            AppLogger.d(TAG, "当前已处于 ${_connectionState.value} 状态，忽略重复连接请求: ${device.address}")
            return
        }

        // 1. 立即停止扫描，避免无线电资源冲突
        stopScan()
        // 2. 立即置为 CONNECTING，阻止后续广播包触发多次连接
        _connectionState.value = ConnectionState.CONNECTING

        pendingConnectRunnable?.let { handler.removeCallbacks(it) }
        val connectRunnable = Runnable {
            if (_connectionState.value != ConnectionState.CONNECTING) return@Runnable

            // 关闭旧的 GATT 连接实例
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: Exception) {
                AppLogger.w(TAG, "关闭旧 GATT 实例异常: ${e.message}")
            }
            bluetoothGatt = null

            AppLogger.i(TAG, "正在发起 GATT 连接 (延迟 250ms 防射频冲突): ${device.address}...")

            // Cancel previous connection watchdog
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }

            // 8-second watchdog: if GATT doesn't establish, close GATT & fall back to scan
            val timeoutRunnable = Runnable {
                if (_connectionState.value == ConnectionState.CONNECTING) {
                    AppLogger.w(TAG, "GATT 连接超时 (8s)，回退并重新启动 LE 扫描...")
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
            handler.postDelayed(timeoutRunnable, 8000L)  // 8000ms = 8秒超时

            // 发起 GATT 连接；Android M（API 23）及以上强制指定 LE 传输通道以提高稳定性
            bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        }
        pendingConnectRunnable = connectRunnable
        // 留出 250ms 给系统蓝牙芯片完全停止 LE 扫描状态，彻底规避 Status 133 冲突
        handler.postDelayed(connectRunnable, 250L)
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
            // 仅对 AFU 私有服务 (0xFFB0) 发送 AFU 私有协议握手包，
            // 严禁向 OKOK、SIG 标准或其它未知厂商特征发送，以防远端 MCU 异常或断连
            val isAfuService = sUuid.contains("FFB0") || sUuid.contains("0000FFB0")
            if (!isAfuService) continue

            // 仅当用户明确将偏好型号手动设置为“薄荷健康/沃莱(非AFU)”时才跳过；
            // 只要设备包含 0xFFB0 服务（AFU 专属服务），哪怕设备名称带有 QN- 或 Yolanda，也必须发送握手包激活 MCU！
            if (preferredModel == ScaleModel.BOOHEE_YOLANDA) {
                AppLogger.d(TAG, "当前偏好型号指定为薄荷健康/沃莱非AFU协议，跳过 AFU 握手包")
                continue
            }

            for (char in service.characteristics) {
                val props = char.properties
                val cUuid = char.uuid.toString().uppercase()
                val isAfuWrite = cUuid.contains("FFB1") || cUuid.contains("0000FFB1")
                // 只向支持 WRITE 或 WRITE_NO_RESPONSE 的 AFU 写入特征发送握手
                if (isAfuWrite && (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {

                    val handshakeData = byteArrayOf(0xFD.toByte(), 0x37, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x37)
                    val handshakeHex = handshakeData.joinToString(" ") { "%02X".format(it) }
                    AppLogger.i(TAG, "向 ${char.uuid} 发送握手数据包: $handshakeHex")
                    val writeType = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
                        props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    char.writeType = writeType
                    // 根据 Android API 版本选择写入方式
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(char, handshakeData, writeType)
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
        // 取消前一个特征的描述符超时看门狗
        descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
        descriptorTimeoutRunnable = null

        // 所有特征均已订阅完毕
        if (pendingSubscribeIndex >= pendingSubscribeList.size) {
            AppLogger.i(TAG, "所有特征通知已成功订阅，准备发送握手包")
            // 服务发现及全部特征订阅顺利完成，取消看门狗
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
            connectTimeoutRunnable = null
            // 稍作微小延迟 150ms 确保 BLE 描述符写入事务完全完成，再执行握手写入
            handler.postDelayed({
                sendHandshake(gatt)
            }, 150L)
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

            // 单个描述符写入设置 2.5s 超时看门狗，防止个别体脂秤丢弃回调导致整个订阅链路永久死锁
            val dTimeout = Runnable {
                AppLogger.w(TAG, "描述符写入超时 (2.5s) [${characteristic.uuid}], 跳过并尝试下一个特征")
                subscribeNextCharacteristic(gatt)
            }
            descriptorTimeoutRunnable = dTimeout
            handler.postDelayed(dTimeout, 2500L)

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
                // writeDescriptor 立即失败时（无需等待回调），取消定时器并直接处理下一个
                descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
                descriptorTimeoutRunnable = null
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
        val scaleResult = MultiScalePacketParser.parseNotification(data, charUuid, preferredModel)

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
            if (result.weightKg > 0.0) {
                _weight.value = result.weightKg
                // 只有当体重 >= 3.0kg 时才判定为有效稳定锁定（防止单脚踩秤或轻微压秤时的误锁定）
                val validStable = result.isStable && (result.weightKg >= 3.0)
                _isStable.value = validStable
                if (validStable) {
                    // 体重稳定锁定后切换到测量状态，通知 UI 开始体成分计算
                    _connectionState.value = ConnectionState.MEASURING
                } else {
                    // 体重处于实时动态变动中（未锁定）：
                    // 彻底清零阻抗，杜绝上一轮称重遗留或动态爬升中的杂质阻抗残留！
                    _impedance.value = 0.0
                }
            } else if (result.weightKg <= 0.0 && result.impedanceOhm == null) {
                // 仅当体重为 0 且无阻抗数据时（下秤离秤包），才归零体重与稳定标志
                _weight.value = 0.0
                _isStable.value = false
                _impedance.value = 0.0
                if (_connectionState.value == ConnectionState.MEASURING) {
                    _connectionState.value = ConnectionState.CONNECTED
                }
            }
            // 阻抗专属数据包（result.weightKg <= 0.0 && result.impedanceOhm != null）：绝不归零体重，保留已锁定的 _weight.value

            result.impedanceOhm?.let { imp ->
                // 人体生物电阻抗（BIA）必须基于真实人体踩秤（体重 >= 3.0kg）才能测得，空秤时禁止接收阻抗
                if (imp > 0.0 && (_weight.value >= 3.0 || result.weightKg >= 3.0)) {
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
         * 1. 取消正在等待的连接看门狗与调度任务。
         * 2. STATE_CONNECTED && GATT_SUCCESS：
         *    - 物理链路建立成功，重置重试计数器 gattRetryCount = 0。
         *    - 请求高优先级连接参数，延迟 300ms 发起 discoverServices()。
         * 3. STATE_DISCONNECTED：
         *    - 关闭当前 GATT 实例，释放底层 Binder 与 Client 句柄。
         *    - 重置测量数据（体重、稳定状态、阻抗）。
         *    - 区分两种断开场景：
         *      A. 正常完成称重后的秤端休眠断开（此前处于 CONNECTED 或 MEASURING）：
         *         - 重置 gattRetryCount = 0，非故障。
         *         - 延时 800ms 重新恢复扫描，以便用户下次踩秤时即踩即连。
         *      B. 连接建立阶段失败（此前处于 CONNECTING，如 Status 133 / 超时等）：
         *         - 执行指数退避重试（最长 8s）。
         *         - 达到最大重试后不永久停止，冷却 5s 后重置并恢复扫描，避免秤被重新唤醒时无法连接。
         * 4. 其他异常状态：关闭 GATT，并在 1s 后恢复扫描。
         *
         * @param gatt     GATT 客户端实例。
         * @param status   操作结果状态码（[BluetoothGatt.GATT_SUCCESS] 为成功）。
         * @param newState 新的连接状态（[BluetoothProfile.STATE_CONNECTED] 或 [BluetoothProfile.STATE_DISCONNECTED]）。
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            AppLogger.i(TAG, "GATT 连接状态变更: status=$status, newState=$newState, currentConnectionState=${_connectionState.value}")
            // 连接事件发生，取消超时看门狗与延时任务
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
            connectTimeoutRunnable = null
            pendingConnectRunnable?.let { handler.removeCallbacks(it) }
            pendingConnectRunnable = null

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // 物理连接建立成功，重置重试计数器
                gattRetryCount = 0
                _connectionState.value = ConnectionState.CONNECTING
                // 为服务发现和特征订阅设置超时看门狗（10秒），防止 discoverServices() 挂起无响应
                val discoveryTimeout = Runnable {
                    if (_connectionState.value == ConnectionState.CONNECTING) {
                        AppLogger.w(TAG, "GATT 服务发现或特征订阅超时(10s)，主动断开重连")
                        try {
                            gatt.disconnect()
                            gatt.close()
                        } catch (e: Exception) {}
                        if (bluetoothGatt == gatt) {
                            bluetoothGatt = null
                        }
                        _connectionState.value = ConnectionState.IDLE
                        startScan()
                    }
                }
                connectTimeoutRunnable = discoveryTimeout
                handler.postDelayed(discoveryTimeout, 10000L)

                // 请求高优先级连接参数
                handler.post {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }

                // 延迟 300ms 再发起服务发现，防止与连接参数协商在链路层产生报文碰撞
                handler.postDelayed({
                    if (_connectionState.value == ConnectionState.CONNECTING && bluetoothGatt == gatt) {
                        AppLogger.i(TAG, "发起 GATT discoverServices()")
                        gatt.discoverServices()
                    }
                }, 300L)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // 收到断开事件，必须先彻底关闭当前 GATT 客户端实例释放底层 Binder / Client 句柄
                try {
                    gatt.close()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "断开连接关闭 GATT 异常: ${e.message}")
                }
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                pendingSubscribeList.clear()
                pendingSubscribeIndex = 0

                // 区分是"正常测量完成后的设备休眠断开"还是"连接发起阶段的握手失败"
                val wasPreviouslyConnected = _connectionState.value == ConnectionState.CONNECTED ||
                                            _connectionState.value == ConnectionState.MEASURING

                _connectionState.value = ConnectionState.IDLE
                _weight.value = 0.0
                _isStable.value = false
                _impedance.value = 0.0

                if (wasPreviouslyConnected) {
                    // 场景 1：设备已完成称重，秤端自动休眠断开（常见 status: 19 远端用户终止、8 超时等）
                    // 此为体脂秤标准节能行为，绝对不是连接故障！立即重置重试计数，并恢复扫描等待下次上秤
                    gattRetryCount = 0
                    AppLogger.i(TAG, "体脂秤已正常休眠/断开 (status=$status)，重置测量状态，延时恢复扫描等待下次上秤...")
                    if (!isPairingMode && !lastPairedMac.isNullOrEmpty()) {
                        handler.postDelayed({
                            if (!isPairingMode && !lastPairedMac.isNullOrEmpty() && _connectionState.value == ConnectionState.IDLE) {
                                AppLogger.i(TAG, "秤休眠后延时恢复扫描成功，就绪等待下次上秤")
                                startScan()
                            }
                        }, 800L)
                    }
                } else {
                    // 场景 2：在 CONNECTING 阶段连接失败（如 Status 133、超时或秤刚唤醒就断开）
                    AppLogger.w(TAG, "GATT 连接建立失败 (status=$status)，当前重试次数: $gattRetryCount / $MAX_GATT_RETRY")
                    if (!isPairingMode && !lastPairedMac.isNullOrEmpty()) {
                        if (gattRetryCount < MAX_GATT_RETRY) {
                            val delayMs = (1000L * (1 shl gattRetryCount)).coerceAtMost(8000L)
                            gattRetryCount++
                            AppLogger.w(TAG, "将在 ${delayMs}ms 后恢复扫描以重连目标秤（第 $gattRetryCount 次）")
                            handler.postDelayed({
                                if (!isPairingMode && !lastPairedMac.isNullOrEmpty() && _connectionState.value == ConnectionState.IDLE) {
                                    startScan()
                                }
                            }, delayMs)
                        } else {
                            // 达到最大重试后不永久停止，冷却 5 秒后重置重试计数并继续恢复扫描，保证秤重新踩亮时能连上
                            gattRetryCount = 0
                            AppLogger.w(TAG, "GATT 连接连续失败已达 $MAX_GATT_RETRY 次，冷却 5s 后重置并恢复扫描...")
                            handler.postDelayed({
                                if (!isPairingMode && !lastPairedMac.isNullOrEmpty() && _connectionState.value == ConnectionState.IDLE) {
                                    startScan()
                                }
                            }, 5000L)
                        }
                    }
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                // 其他异常状态（如未处于 STATE_DISCONNECTED 却收到 status != 0）
                AppLogger.w(TAG, "GATT 出现异常状态 status=$status, newState=$newState，关闭连接")
                try {
                    gatt.close()
                } catch (e: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
                _connectionState.value = ConnectionState.IDLE
                if (!isPairingMode && !lastPairedMac.isNullOrEmpty()) {
                    handler.postDelayed({
                        if (!isPairingMode && !lastPairedMac.isNullOrEmpty() && _connectionState.value == ConnectionState.IDLE) {
                            startScan()
                        }
                    }, 1000L)
                }
            }
        }

        /**
         * GATT 服务发现完成回调。
         *
         * 成功后遍历所有非系统服务（过滤 GAP/GATT 通用服务及非秤标准服务），
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

                // 遍历所有 GATT 服务，收集可订阅的特征
                for (service in gatt.services) {
                    val sUuid = service.uuid.toString().uppercase()
                    // 跳过 GAP（0x1800）、GATT（0x1801）、设备信息（0x180A）、电池（0x180F）、时间（0x1805）等通用系统服务
                    val isSystemOrIgnored = sUuid.startsWith("00001800") ||
                                           sUuid.startsWith("00001801") ||
                                           sUuid.startsWith("0000180A") ||
                                           sUuid.startsWith("0000180F") ||
                                           sUuid.startsWith("00001805")
                    if (isSystemOrIgnored) continue

                    for (char in service.characteristics) {
                        val props = char.properties
                        // 收集支持 NOTIFY 或 INDICATE 的特征，这些特征会主动推送测量数据
                        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                            pendingSubscribeList.add(char)
                        }
                    }
                }

                AppLogger.i(TAG, "共找到 ${pendingSubscribeList.size} 个有效通知特征")
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
            // 收到写入回调，取消该特征的超时定时器
            descriptorTimeoutRunnable?.let { handler.removeCallbacks(it) }
            descriptorTimeoutRunnable = null
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
