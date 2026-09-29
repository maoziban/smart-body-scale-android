package com.example.dianzicheng.data.phicomm

import android.content.Context
import android.net.wifi.WifiManager
import com.example.dianzicheng.data.local.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 斐讯 S7 / S7 PE (含 zS7 社区开源固件) 局域网 Wi-Fi 称重通信管理器。
 *
 * 背景说明：
 * 斐讯 S7 系列采用 2.4GHz Wi-Fi 联网通信，硬件无 BLE 称重模式。
 * 社区固件 (zS7) 及局域网通信机制通过 UDP 广播工作：
 * - S7 秤发送端口：UDP 10181 (目标地址 255.255.255.255 全网广播)
 * - S7 秤接收端口：UDP 10182
 * - 称重稳定后广播格式：{"mac":"1234567890ab", "weight":"70.5", "time":"1590281609"}
 * - 设备查询指令：发送 {"cmd":"device report"} 至 255.255.255.255:10182
 */
class PhicommS7Manager(private val context: Context) {

    companion object {
        private const val TAG = "PhicommS7Manager"
        const val PORT_LISTEN = 10181
        const val PORT_SEND = 10182

        /**
         * 纯函数解析接收到的 JSON 报文字符串。
         * 供实际网络收包与单元测试复用。
         */
        fun parsePacket(jsonStr: String): PhicommS7Packet? {
            if (jsonStr.isBlank() || !jsonStr.trim().startsWith("{")) return null
            return try {
                parseWithOrgJson(jsonStr)
            } catch (_: Throwable) {
                parseWithRegex(jsonStr)
            } ?: parseWithRegex(jsonStr)
        }

        private fun parseWithOrgJson(jsonStr: String): PhicommS7Packet? {
            return try {
                val json = JSONObject(jsonStr)

                // 1. 检查是否为称重数据报文 (包含 "weight")
                if (json.has("weight")) {
                    val weightRaw = json.opt("weight")
                    var weightKg = when (weightRaw) {
                        is Number -> weightRaw.toDouble()
                        is String -> weightRaw.toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    }
                    // 部分固件将体重数值放大 100 倍以整数存储 (如 7050 -> 70.5kg)
                    if (weightKg > 500.0) {
                        weightKg /= 100.0
                    }

                    if (weightKg > 0.0) {
                        val rawMac = json.optString("mac", "")
                        val formattedMac = formatMacAddress(rawMac)
                        val rawTime = json.optLong("time", 0L)
                        val timestampMs = if (rawTime > 0) {
                            if (rawTime < 10000000000L) rawTime * 1000L else rawTime
                        } else {
                            System.currentTimeMillis()
                        }
                        return PhicommS7Packet.WeightMeasurement(
                            weightKg = weightKg,
                            mac = formattedMac,
                            timestampEpochMs = timestampMs
                        )
                    }
                }

                // 2. 检查是否为历史数据报文 (包含 "history")
                if (json.has("history")) {
                    val historyObj = json.optJSONObject("history")
                    val weights = historyObj?.optJSONArray("weight")
                    val utcs = historyObj?.optJSONArray("utc")
                    val rawMac = json.optString("mac", "")
                    val formattedMac = formatMacAddress(rawMac)

                    if (weights != null && weights.length() > 0) {
                        // 取最新一组记录
                        val lastIdx = weights.length() - 1
                        var latestWeight = weights.optDouble(lastIdx, 0.0)
                        if (latestWeight > 500.0) {
                            latestWeight /= 100.0
                        }
                        val latestUtc = utcs?.optLong(lastIdx, 0L) ?: 0L
                        val timestampMs = if (latestUtc > 0) {
                            if (latestUtc < 10000000000L) latestUtc * 1000L else latestUtc
                        } else {
                            System.currentTimeMillis()
                        }
                        if (latestWeight > 0.0) {
                            return PhicommS7Packet.WeightMeasurement(
                                weightKg = latestWeight,
                                mac = formattedMac,
                                timestampEpochMs = timestampMs
                            )
                        }
                    }
                }

                // 3. 检查是否为设备探测回应 (包含 "type" 或 "type_name")
                if (json.has("type_name") || json.has("type")) {
                    val name = json.optString("name", "zS7")
                    val typeName = json.optString("type_name", "zS7")
                    val rawMac = json.optString("mac", "")
                    val formattedMac = formatMacAddress(rawMac)
                    return PhicommS7Packet.DeviceReport(
                        name = if (name.isNotBlank()) name else typeName,
                        mac = formattedMac
                    )
                }

                null
            } catch (_: Throwable) {
                null
            }
        }

        private fun parseWithRegex(jsonStr: String): PhicommS7Packet? {
            val macRegex = Regex("\"mac\"\\s*:\\s*\"([^\"]+)\"")
            val weightRegex = Regex("\"weight\"\\s*:\\s*\"?([0-9.]+)\"?")
            val timeRegex = Regex("\"time\"\\s*:\\s*\"?([0-9]+)\"?")
            val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            val typeNameRegex = Regex("\"type_name\"\\s*:\\s*\"([^\"]+)\"")
            val historyWeightRegex = Regex("\"weight\"\\s*:\\s*\\[([^\\]]+)\\]")
            val historyUtcRegex = Regex("\"utc\"\\s*:\\s*\\[([^\\]]+)\\]")

            val macMatch = macRegex.find(jsonStr)?.groupValues?.get(1) ?: ""
            val formattedMac = formatMacAddress(macMatch)

            // 1. 历史数据 (优先检测，避免与顶层 weight 混淆)
            val historyMatch = historyWeightRegex.find(jsonStr)
            if (historyMatch != null) {
                val weights = historyMatch.groupValues[1].split(",").mapNotNull { it.trim().toDoubleOrNull() }
                if (weights.isNotEmpty()) {
                    var latestWeight = weights.last()
                    if (latestWeight > 500.0) latestWeight /= 100.0
                    val utcs = historyUtcRegex.find(jsonStr)?.groupValues?.get(1)?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
                    val latestUtc = utcs?.lastOrNull() ?: 0L
                    val timestampMs = if (latestUtc > 0) {
                        if (latestUtc < 10000000000L) latestUtc * 1000L else latestUtc
                    } else {
                        System.currentTimeMillis()
                    }
                    if (latestWeight > 0.0) {
                        return PhicommS7Packet.WeightMeasurement(
                            weightKg = latestWeight,
                            mac = formattedMac,
                            timestampEpochMs = timestampMs
                        )
                    }
                }
            }

            // 2. 实时称重数据
            val weightMatch = weightRegex.find(jsonStr)
            if (weightMatch != null && !jsonStr.contains("\"history\"")) {
                var w = weightMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                if (w > 500.0) w /= 100.0
                if (w > 0.0) {
                    val rawTime = timeRegex.find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val timestampMs = if (rawTime > 0) {
                        if (rawTime < 10000000000L) rawTime * 1000L else rawTime
                    } else {
                        System.currentTimeMillis()
                    }
                    return PhicommS7Packet.WeightMeasurement(
                        weightKg = w,
                        mac = formattedMac,
                        timestampEpochMs = timestampMs
                    )
                }
            }

            // 3. 设备探测回应
            if (jsonStr.contains("\"type_name\"") || jsonStr.contains("\"type\"")) {
                val name = nameRegex.find(jsonStr)?.groupValues?.get(1)
                    ?: typeNameRegex.find(jsonStr)?.groupValues?.get(1)
                    ?: "zS7"
                return PhicommS7Packet.DeviceReport(
                    name = name,
                    mac = formattedMac
                )
            }

            return null
        }

        /** 将无冒号小写/大写 MAC 统一格式化为标准 XX:XX:XX:XX:XX:XX 格式 */
        fun formatMacAddress(raw: String): String {
            val clean = raw.trim().replace(":", "").replace("-", "").replace(" ", "").uppercase()
            return if (clean.length == 12 && clean.all { it in '0'..'9' || it in 'A'..'F' }) {
                clean.chunked(2).joinToString(":")
            } else {
                raw.trim().ifEmpty { "PHICOMM:S7:WIFI" }
            }
        }
    }

    /** 封闭类描述解析后的 S7 报文类型 */
    sealed class PhicommS7Packet {
        data class WeightMeasurement(
            val weightKg: Double,
            val mac: String,
            val timestampEpochMs: Long
        ) : PhicommS7Packet()

        data class DeviceReport(
            val name: String,
            val mac: String
        ) : PhicommS7Packet()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** 称重事件数据流 (SharedFlow) */
    private val _weightFlow = MutableSharedFlow<PhicommS7Packet.WeightMeasurement>(extraBufferCapacity = 1)
    val weightFlow: SharedFlow<PhicommS7Packet.WeightMeasurement> = _weightFlow

    /** 局域网发现的斐讯设备信息 Pair<名称, MAC> */
    private val _discoveredDevice = MutableStateFlow<Pair<String, String>?>(null)
    val discoveredDevice: StateFlow<Pair<String, String>?> = _discoveredDevice

    /** 标记当前是否正在监听中 */
    private var isListening = false

    /**
     * 启动局域网 UDP 监听并广播设备探测报文。
     */
    fun startListening() {
        if (isListening) {
            // 已在监听，仅重新发送一次发现探测报文
            sendDiscovery()
            return
        }
        isListening = true

        // 尝试获取 MulticastLock 提升 Android Wi-Fi 睡眠策略下的收包可靠性
        acquireMulticastLock()

        listenJob = scope.launch {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT_LISTEN))
                    soTimeout = 2000 // 2 秒超时，以便周期性检查协程取消状态
                }
                socket = s
                AppLogger.i(TAG, "已启动斐讯 S7 UDP 局域网监听，端口: $PORT_LISTEN")

                // 启动时主动向全网广播探测命令
                sendDiscovery()

                val buffer = ByteArray(2048)
                while (isActive && isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                        AppLogger.d(TAG, "收到 UDP 局域网报文: $text (来自 ${packet.address.hostAddress})")

                        val parsed = parsePacket(text)
                        when (parsed) {
                            is PhicommS7Packet.WeightMeasurement -> {
                                AppLogger.i(TAG, "解析到斐讯 S7 体重: ${parsed.weightKg} kg, MAC: ${parsed.mac}")
                                _discoveredDevice.value = Pair("斐讯 S7 (${parsed.mac.takeLast(5)})", parsed.mac)
                                _weightFlow.tryEmit(parsed)
                            }
                            is PhicommS7Packet.DeviceReport -> {
                                AppLogger.i(TAG, "发现局域网斐讯设备: ${parsed.name}, MAC: ${parsed.mac}")
                                _discoveredDevice.value = Pair("斐讯 S7 (${parsed.name})", parsed.mac)
                            }
                            null -> {
                                // 非 S7 目标报文，忽略
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // 超时为正常行为，继续循环判断 isActive
                    } catch (e: Exception) {
                        if (isActive && isListening) {
                            AppLogger.w(TAG, "UDP 收包异常: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "绑定 UDP 端口 $PORT_LISTEN 失败: ${e.message}")
            } finally {
                cleanupSocket()
            }
        }
    }

    /**
     * 向局域网广播发送设备探测命令 {"cmd":"device report"}
     */
    fun sendDiscovery() {
        scope.launch {
            try {
                val cmd = "{\"cmd\":\"device report\"}".toByteArray(Charsets.UTF_8)
                val targetAddr = InetAddress.getByName("255.255.255.255")
                val sendPacket = DatagramPacket(cmd, cmd.size, targetAddr, PORT_SEND)
                DatagramSocket().use { sendSocket ->
                    sendSocket.broadcast = true
                    sendSocket.send(sendPacket)
                }
                AppLogger.d(TAG, "已广播发送斐讯 S7 探测报文: 255.255.255.255:$PORT_SEND")
            } catch (e: Exception) {
                AppLogger.w(TAG, "发送探测报文失败: ${e.message}")
            }
        }
    }

    /**
     * 停止监听并释放所有资源。
     */
    fun stopListening() {
        isListening = false
        listenJob?.cancel()
        listenJob = null
        cleanupSocket()
        releaseMulticastLock()
        AppLogger.i(TAG, "已停止斐讯 S7 局域网监听")
    }

    private fun cleanupSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("PhicommS7MulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "获取 MulticastLock 失败 (若无相关权限可忽略): ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }
}
