package com.example.dianzicheng.data.ble

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import java.util.UUID

/**
 * 统一多品牌电子秤/体脂秤数据包解析引擎。
 *
 * 支持协议：
 * 1. [AFU]：原有 0xAC 协议帧（沃莱/私有体脂秤，UUID 0xFFB0）
 * 2. [SIG_STANDARD]：蓝牙 SIG 国际通用标准（Weight Scale Service 0x181D / Body Composition Service 0x181B）
 * 3. [XIAOMI]：小米/米家体脂秤与体重秤（广播 Service Data 0x181B / 0x181D 及 GATT 通知）
 * 4. [OKOK_CHIPSEA]：OKOK / 芯海科技 (Chipsea) / 香山等白牌体脂秤（UUID 0xFFF0 / 0xFFE0，0x10/0xCF/0xAA 帧）
 */
object MultiScalePacketParser {

    /** 蓝牙 SIG 标准 Weight Scale Service UUID (0x181D) */
    val UUID_SERVICE_SIG_WSS: UUID = UUID.fromString("0000181D-0000-1000-8000-00805F9B34FB")

    /** 蓝牙 SIG 标准 Body Composition Service UUID (0x181B) */
    val UUID_SERVICE_SIG_BCS: UUID = UUID.fromString("0000181B-0000-1000-8000-00805F9B34FB")

    /** 芯海科技/OKOK 常用体脂秤服务 UUID (0xFFF0) */
    val UUID_SERVICE_CHIPSEA_OKOK: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")

    /** 芯海/通用透传服务 UUID (0xFFE0) */
    val UUID_SERVICE_GENERIC_FFE0: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")

    /** AFU 私有服务 UUID (0xFFB0) */
    val UUID_SERVICE_AFU: UUID = UUID.fromString("0000FFB0-0000-1000-8000-00805F9B34FB")

    /** 微信运动/小米辅助服务 UUID (0xFEE7) */
    val UUID_SERVICE_XIAOMI_WECHAT: UUID = UUID.fromString("0000FEE7-0000-1000-8000-00805F9B34FB")

    /**
     * 解析完成的秤面数据。
     *
     * @property weightKg      体重数值（千克，kg）
     * @property isStable      是否已稳定锁定
     * @property impedanceOhm  体内阻抗数值（欧姆，Ω），为 null 表示未测出或不支持
     * @property protocolName  命中并解析的协议名称（用于日志标记与诊断）
     */
    data class ParsedScaleData(
        val weightKg: Double,
        val isStable: Boolean,
        val impedanceOhm: Double? = null,
        val protocolName: String
    )

    /**
     * 解析特征值通知或指示（GATT Notification / Indication）数据包。
     *
     * 自动在所有支持的协议中依次尝试匹配。
     *
     * @param data     接收到的原始字节数组
     * @param charUuid 发出通知的特征 UUID（小写或大写字符串，用于辅助区分 SIG 标准特征）
     * @return 成功解析出有效数据时返回 [ParsedScaleData]，否则返回 null
     */
    fun parseNotification(data: ByteArray, charUuid: String? = null): ParsedScaleData? {
        if (data.isEmpty()) return null

        val uuidStr = charUuid?.uppercase() ?: ""

        // 1. 优先尝试 AFU 协议（含 0xAC 帧头，原有协议兼容）
        parseAfu(data)?.let { return it }

        // 2. 尝试 Bluetooth SIG 国际标准特征 (0x2A9D Weight Measurement / 0x2A9C Body Composition)
        if (uuidStr.contains("2A9C")) {
            parseBluetoothSigBcs(data)?.let { return it }
        }
        if (uuidStr.contains("2A9D")) {
            parseBluetoothSigWss(data)?.let { return it }
        }

        // 3. 尝试小米/米家格式（如果设备以广播格式通过 GATT 通知发送）
        parseXiaomiPayload(data)?.let { return it }

        // 4. 尝试 OKOK / 芯海科技 / 香山等通用格式
        parseOkokChipsea(data)?.let { return it }

        // 5. 再次尝试 SIG 标准解析（仅在 UUID 未知或包含标准特征/服务时才尝试，避免盲目匹配无关特征）
        if (uuidStr.isBlank() || uuidStr.contains("181D") || uuidStr.contains("181B")) {
            parseBluetoothSigWss(data)?.let { return it }
            parseBluetoothSigBcs(data)?.let { return it }
        }

        return null
    }

    /**
     * 解析 BLE 广播数据包（针对小米等无需连接直接广播体重的免配对/广播秤）。
     *
     * @param scanRecord 扫描返回的 [ScanRecord] 对象
     * @return 解析出有效测量数据则返回 [ParsedScaleData]，否则返回 null
     */
    fun parseAdvertisement(scanRecord: ScanRecord?): ParsedScaleData? {
        if (scanRecord == null) return null

        // 1. 尝试从小服务数据 Service Data 中解析小米体脂秤 2 (0x181B)
        val bcsParcel = ParcelUuid(UUID_SERVICE_SIG_BCS)
        val bcsData = scanRecord.getServiceData(bcsParcel)
        if (bcsData != null && bcsData.size >= 10) {
            parseXiaomiBodyCompositionServiceData(bcsData)?.let { return it }
        }

        // 2. 尝试从小服务数据 Service Data 中解析小米体重秤 1 (0x181D)
        val wssParcel = ParcelUuid(UUID_SERVICE_SIG_WSS)
        val wssData = scanRecord.getServiceData(wssParcel)
        if (wssData != null && wssData.size >= 3) {
            parseXiaomiWeightScaleServiceData(wssData)?.let { return it }
        }

        // 3. 尝试从厂商自定义数据 Manufacturer Data 中解析
        val rawBytes = scanRecord.bytes
        if (rawBytes != null && rawBytes.size >= 6) {
            // 严格按 AD 结构遍历，检查厂商数据（0xFF）或服务数据（0x16），禁止全报文盲搜 0xAC
            var i = 0
            while (i < rawBytes.size - 2) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if ((type == 0xFF || type == 0x16) && len >= 5) {
                    val mfgPayload = rawBytes.copyOfRange(i + 2, i + 1 + len)
                    // 若数据段首字节为 0xAC 则按 AFU 协议解析
                    if ((mfgPayload[0].toInt() and 0xFF) == 0xAC) {
                        parseAfu(mfgPayload)?.let { return it }
                    }
                    if (len >= 6) {
                        // 跳过 2 字节 Company ID 尝试 OKOK/芯海解析
                        val payloadWithoutCompany = rawBytes.copyOfRange(i + 4, i + 1 + len)
                        parseOkokChipsea(payloadWithoutCompany)?.let { return it }
                    }
                }
                i += len + 1
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // 协议 1：AFU 协议解析
    // -------------------------------------------------------------------------

    private fun parseAfu(data: ByteArray): ParsedScaleData? {
        val weightData = AFUPacketParser.parseWeight(data) ?: return null
        // 仅当体重 >= 3.0kg 时，阻抗才具备生理意义且允许稳定锁定；
        // 当用户下秤发送 0.00kg 时正常上报，以便 UI 立即清除测量状态（避免 2.5 秒延迟），但阻抗必须置 null
        val impedance = if (weightData.weightKg >= 3.0) AFUPacketParser.parseImpedance(data) else null
        return ParsedScaleData(
            weightKg = weightData.weightKg,
            isStable = weightData.isStable && weightData.weightKg >= 3.0,
            impedanceOhm = impedance,
            protocolName = "AFU"
        )
    }

    // -------------------------------------------------------------------------
    // 协议 2：Bluetooth SIG 国际通用标准（0x2A9D Weight / 0x2A9C Body Composition）
    // -------------------------------------------------------------------------

    /**
     * 解析蓝牙 SIG 标准 Weight Measurement (0x2A9D) 特征数据。
     *
     * 0x2A9D Weight Measurement 帧格式：
     * - Byte 0: Flags (8-bit)
     *   - Bit 0: 单位（0 = SI / kg，1 = Imperial / lbs）
     *   - Bit 1: 是否包含时间戳（7 字节）
     *   - Bit 2: 是否包含 User ID（1 字节）
     *   - Bit 3: 是否包含 BMI 和 Height（4 字节）
     * - Byte 1~2: 体重 Raw UInt16（小端序，Little-Endian）
     *   - SI 单位下分辨率标准为 0.005 kg
     *   - Imperial 单位下分辨率为 0.01 lb
     */
    fun parseBluetoothSigWss(data: ByteArray): ParsedScaleData? {
        if (data.size < 3) return null

        val flags = data[0].toInt() and 0xFF
        val isImperial = (flags and 0x01) != 0

        val rawWeight = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        if (rawWeight <= 0) return null

        val weightKg: Double = if (isImperial) {
            // 磅转换为千克：1 lb ≈ 0.45359237 kg，分辨率 0.01 lb
            val lbs = rawWeight * 0.01
            lbs * 0.45359237
        } else {
            // 蓝牙 SIG 标准规范 (GATT Weight Measurement 0x2A9D): SI 单位标准分辨率为 0.005 kg
            rawWeight * 0.005
        }

        if (weightKg < 1.0 || weightKg > 350.0) return null

        // SIG 标准中 Weight Measurement 是 Indication，通常在读数稳定时上报
        return ParsedScaleData(
            weightKg = weightKg,
            isStable = true,
            impedanceOhm = null,
            protocolName = "Bluetooth SIG WSS"
        )
    }

    /**
     * 解析蓝牙 SIG 标准 Body Composition Measurement (0x2A9C) 特征数据。
     *
     * 0x2A9C 帧结构：
     * - Byte 0~1: Flags (16位 UInt16 小端序)
     *   - Bit 0: 单位 (0 = SI / kg, 1 = Imperial / lbs)
     *   - Bit 1: 是否包含时间戳 (7 字节)
     *   - Bit 2: 是否包含 User ID (1 字节)
     *   - Bit 3: 是否包含基础代谢率 (2 字节, 1 kJ)
     *   - Bit 4: 是否包含肌肉百分比 (2 字节, 0.1%)
     *   - Bit 5: 是否包含肌肉质量 (2 字节)
     *   - Bit 6: 是否包含去脂体重 (2 字节)
     *   - Bit 7: 是否包含软瘦体重 (2 字节)
     *   - Bit 8: 是否包含身体水分 (2 字节)
     *   - Bit 9: 是否包含阻抗 (2 字节, 0.1 Ω)
     *   - Bit 10: 是否包含体重 (2 字节)
     *   - Bit 11: 是否包含身高 (2 字节)
     * - Byte 2~3: 体脂率 (UInt16 小端序，分辨率 0.1%)，必选字段
     */
    fun parseBluetoothSigBcs(data: ByteArray): ParsedScaleData? {
        if (data.size < 4) return null

        val flags = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val isImperial = (flags and 0x01) != 0

        // 必选字段：体脂率 (分辨率 0.1%)
        val rawFat = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val bodyFatPct = rawFat * 0.1

        var offset = 4
        // Bit 1: Timestamp (7 字节)
        if ((flags and (1 shl 1)) != 0) offset += 7
        // Bit 2: User ID (1 字节)
        if ((flags and (1 shl 2)) != 0) offset += 1
        // Bit 3: Basal Metabolism (2 字节)
        if ((flags and (1 shl 3)) != 0) offset += 2
        // Bit 4: Muscle Percentage (2 字节)
        if ((flags and (1 shl 4)) != 0) offset += 2
        // Bit 5: Muscle Mass (2 字节)
        if ((flags and (1 shl 5)) != 0) offset += 2
        // Bit 6: Fat Free Mass (2 字节)
        if ((flags and (1 shl 6)) != 0) offset += 2
        // Bit 7: Soft Lean Mass (2 字节)
        if ((flags and (1 shl 7)) != 0) offset += 2
        // Bit 8: Body Water Mass (2 字节)
        if ((flags and (1 shl 8)) != 0) offset += 2

        // Bit 9: Impedance (2 字节，SIG 标准分辨率 0.1 Ω)
        var impedance: Double? = null
        if ((flags and (1 shl 9)) != 0 && offset + 2 <= data.size) {
            val rawImp = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            val impOhm = rawImp * 0.1
            if (impOhm in 50.0..2000.0) {
                impedance = impOhm
            }
            offset += 2
        }

        // Bit 10: Weight (2 字节)
        var weightKg = 0.0
        if ((flags and (1 shl 10)) != 0 && offset + 2 <= data.size) {
            val rawWeight = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            val w = if (isImperial) (rawWeight * 0.01) * 0.45359237 else rawWeight * 0.005
            if (w in 2.0..350.0) {
                weightKg = w
            }
            offset += 2
        }

        if (weightKg < 3.0 && bodyFatPct <= 0.0) {
            return null
        }

        // 仅当已有有效体重（>= 3.0kg）时，阻抗才具备生理意义
        val effectiveImpedance = if (weightKg >= 3.0) impedance else null

        return ParsedScaleData(
            weightKg = weightKg,
            isStable = weightKg >= 3.0,
            impedanceOhm = effectiveImpedance,
            protocolName = "Bluetooth SIG BCS"
        )
    }

    // -------------------------------------------------------------------------
    // 协议 3：小米 / 米家系列体脂秤（Xiaomi Mi Scale 1 & 2）
    // -------------------------------------------------------------------------

    /**
     * 解析小米体脂秤 2 (Mi Body Composition Scale, 0x181B) 广播 Service Data。
     * 13 字节结构：
     * - Byte 0~1: Flags (小端序)
     *   - Bit 0: 1 = lbs
     *   - Bit 1: 1 = 读数锁定稳定 (stabilized)
     *   - Bit 4: 1 = 斤 (catty)
     *   - Bit 5: 1 = 测量完成 (stabilized)
     *   - Bit 7: 1 = 阻抗已测出 (impedance measured)
     * - Byte 2~7: 年月日时分秒
     * - Byte 8~9: 阻抗 (UINT16 小端序，Ω)
     * - Byte 10~11 或 11~12: 体重 (UINT16 小端序)
     */
    private fun parseXiaomiBodyCompositionServiceData(data: ByteArray): ParsedScaleData? {
        if (data.size < 12) return null

        val flags = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val isLbs = (flags and 0x01) != 0
        val isCatty = (flags and 0x10) != 0
        val isStable = (flags and (1 shl 5)) != 0 || (flags and (1 shl 1)) != 0
        val hasImpedance = (flags and (1 shl 7)) != 0

        // 阻抗位于 Byte 9~10 (13 字节包含秒数时) 或 Byte 8~9 (12 字节变体)
        val impOffset = if (data.size >= 13) 9 else 8
        val rawImp = (data[impOffset].toInt() and 0xFF) or ((data[impOffset + 1].toInt() and 0xFF) shl 8)
        val impedance = if (hasImpedance && rawImp in 100..1500) rawImp.toDouble() else null

        // 体重位于尾部最后 2 字节（data.size - 2）
        val wOffset = data.size - 2
        val rawWeight = (data[wOffset].toInt() and 0xFF) or ((data[wOffset + 1].toInt() and 0xFF) shl 8)
        if (rawWeight <= 0) return null

        val weightKg = when {
            isCatty -> (rawWeight / 100.0) * 0.5   // 1 斤 = 0.5 kg
            isLbs   -> (rawWeight / 100.0) * 0.45359237
            else    -> rawWeight / 200.0           // 小米 kg 格式精度 1/200 = 0.005kg
        }

        if (weightKg < 2.0 || weightKg > 350.0) return null

        return ParsedScaleData(
            weightKg = weightKg,
            isStable = isStable && weightKg >= 3.0,
            impedanceOhm = impedance,
            protocolName = "Xiaomi Mi Scale 2"
        )
    }

    /**
     * 解析小米体重秤 1 (Mi Scale 1, 0x181D) 广播 Service Data。
     * 10 字节结构：
     * - Byte 0: Flags (Bit 0: lbs, Bit 4: catty/斤, Bit 5: stable)
     * - Byte 1~2: 体重 (UINT16 小端序)
     */
    private fun parseXiaomiWeightScaleServiceData(data: ByteArray): ParsedScaleData? {
        if (data.size < 3) return null

        val flags = data[0].toInt() and 0xFF
        val isLbs = (flags and 0x01) != 0
        val isCatty = (flags and 0x10) != 0
        val isStable = (flags and 0x20) != 0

        val rawWeight = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        if (rawWeight <= 0) return null

        val weightKg = when {
            isCatty -> (rawWeight / 100.0) * 0.5
            isLbs   -> (rawWeight / 100.0) * 0.45359237
            else    -> rawWeight / 200.0
        }

        if (weightKg < 2.0 || weightKg > 350.0) return null

        return ParsedScaleData(
            weightKg = weightKg,
            isStable = isStable && weightKg >= 3.0,
            impedanceOhm = null,
            protocolName = "Xiaomi Mi Scale 1"
        )
    }

    private fun parseXiaomiPayload(data: ByteArray): ParsedScaleData? {
        if (data.size == 13) {
            return parseXiaomiBodyCompositionServiceData(data)
        }
        if (data.size == 10) {
            return parseXiaomiWeightScaleServiceData(data)
        }
        return null
    }

    // -------------------------------------------------------------------------
    // 协议 4：OKOK / 芯海科技 (Chipsea) / 香山 (Senssun) 通用格式
    // -------------------------------------------------------------------------

    /**
     * 解析 OKOK / 芯海科技方案及香山电子秤数据帧。
     *
     * 常见格式 A（0x10 开头）：
     * - Byte 0: 0x10
     * - Byte 1: 长度 / 命令
     * - Byte 2~3: 体重 UInt16 大端序或小端序
     * - Byte 4: 状态（Bit 0: 锁定）
     * - Byte 5~6: 阻抗 UInt16 (100~1500)
     *
     * 常见格式 B（0xCF 开头）：
     * - Byte 0: 0xCF
     * - Byte 1~2: 体重
     * - Byte 3: 锁定状态位
     *
     * 常见格式 C（香山 0xAA / 0x55）：
     * - Byte 0: 0xAA 或 0x55
     */
    private fun parseOkokChipsea(data: ByteArray): ParsedScaleData? {
        if (data.size < 6) return null

        val header = data[0].toInt() and 0xFF

        // 格式 A：0x10
        if (header == 0x10 && data.size >= 7) {
            // 大端序尝试
            val rawWeightBe = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            // 小端序尝试
            val rawWeightLe = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
            val wBe = rawWeightBe / 100.0
            val wLe = rawWeightLe / 100.0

            val weightKg = if (wBe in 3.0..300.0) wBe else if (wLe in 3.0..300.0) wLe else if (rawWeightBe == 0 || rawWeightLe == 0) 0.0 else null
            if (weightKg != null) {
                val isStable = (data[4].toInt() and 0x01) != 0 || (data[4].toInt() and 0x02) != 0
                val rawImp = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                val imp = if (weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
                return ParsedScaleData(
                    weightKg = weightKg,
                    isStable = isStable && weightKg >= 3.0,
                    impedanceOhm = imp,
                    protocolName = "OKOK/Chipsea (0x10)"
                )
            }
        }

        // 格式 B：0xCF
        if (header == 0xCF && data.size >= 6) {
            val rawWeight = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            val weightKg = rawWeight / 100.0
            if (weightKg in 3.0..300.0 || rawWeight == 0) {
                val isStable = (data[3].toInt() and 0x01) != 0
                val rawImp = if (data.size >= 6) ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF) else 0
                val imp = if (weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
                return ParsedScaleData(
                    weightKg = weightKg,
                    isStable = isStable && weightKg >= 3.0,
                    impedanceOhm = imp,
                    protocolName = "OKOK (0xCF)"
                )
            }
        }

        // 格式 C：香山 Senssun (0xAA / 0x55)
        if ((header == 0xAA || header == 0x55) && data.size >= 6) {
            val rawWeight = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val weightKg = rawWeight / 10.0 // 香山常以 0.1kg 为单位
            if (weightKg in 3.0..300.0) {
                val isStable = (data[4].toInt() and 0x01) != 0 || (data[4].toInt() and 0x80) != 0
                return ParsedScaleData(
                    weightKg = weightKg,
                    isStable = isStable && weightKg >= 3.0,
                    impedanceOhm = null,
                    protocolName = "Senssun"
                )
            }
        }

        return null
    }
}
