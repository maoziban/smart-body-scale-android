package com.example.dianzicheng.data.ble

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import com.example.dianzicheng.domain.ScaleModel
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
     * 自动在所有支持的协议中依次尝试匹配。支持传入用户手动选择的型号 [preferredModel] 优先匹配。
     *
     * @param data           接收到的原始字节数组
     * @param charUuid       发出通知的特征 UUID（小写或大写字符串，用于辅助区分 SIG 标准特征）
     * @param preferredModel 用户手动偏好的型号（默认 AUTO，指定时优先尝试该协议）
     * @return 成功解析出有效数据时返回 [ParsedScaleData]，否则返回 null
     */
    fun parseNotification(
        data: ByteArray,
        charUuid: String? = null,
        preferredModel: ScaleModel = ScaleModel.AUTO
    ): ParsedScaleData? {
        if (data.isEmpty()) return null

        val uuidStr = charUuid?.uppercase() ?: ""

        // 优先分支 1：用户明确指定了偏好型号，优先执行该型号的专属解析
        when (preferredModel) {
            ScaleModel.AFU_PROTOCOL -> parseAfu(data)?.let { return it }
            ScaleModel.BOOHEE_YOLANDA -> parseYolandaIcomon(data)?.let { return it }
            ScaleModel.XIAOMI_SCALE_1, ScaleModel.XIAOMI_SCALE_2 -> parseXiaomiPayload(data)?.let { return it }
            ScaleModel.OKOK_CHIPSEA, ScaleModel.SENSSUN -> parseOkokChipsea(data)?.let { return it }
            ScaleModel.SIG_STANDARD -> {
                parseBluetoothSigWss(data)?.let { return it }
                parseBluetoothSigBcs(data)?.let { return it }
            }
            else -> {}
        }

        // 优先分支 2：特征 UUID 明确匹配
        // AFU 私有特征 (0xFFB2 通知 / 0xFFB0 服务)
        if (uuidStr.contains("FFB2") || uuidStr.contains("FFB0")) {
            parseAfu(data)?.let { return it }
        }
        // Bluetooth SIG 国际标准特征 (0x2A9C Body Composition / 0x2A9D Weight Measurement)
        if (uuidStr.contains("2A9C")) {
            parseBluetoothSigBcs(data)?.let { return it }
        }
        if (uuidStr.contains("2A9D")) {
            parseBluetoothSigWss(data)?.let { return it }
        }
        // 沃莱 / 薄荷健康特征 (0xFFA1 / 0xFFE1)
        if (uuidStr.contains("FFA1") || uuidStr.contains("FFE1")) {
            parseYolandaIcomon(data)?.let { return it }
        }

        // 默认/兜底全协议级联匹配
        // 1. 尝试 AFU 私有协议（含 0xAC 帧头，严格校验 w3 基准偏移量 0x68 与 status <= 0x05，防串扰）
        parseAfu(data)?.let { return it }

        // 2. 尝试沃莱 (Yolanda) / 薄荷健康 (Boohee) / 轻牛 / 合泰协议 (8 字节流 0xCE/0xCA/0xCB 或 20 字节复合帧)
        parseYolandaIcomon(data)?.let { return it }

        // 3. 尝试小米/米家格式（内部严格校验年月日时间合法性，杜绝未知特征误判）
        parseXiaomiPayload(data)?.let { return it }

        // 4. 尝试 OKOK / 芯海科技 / 香山等通用格式
        parseOkokChipsea(data)?.let { return it }

        // 5. 仅在 UUID 明确包含 181D 或 181B 时尝试 SIG 标准解析，避免对未知特征盲目误判
        if (uuidStr.contains("181D")) {
            parseBluetoothSigWss(data)?.let { return it }
        }
        if (uuidStr.contains("181B")) {
            parseBluetoothSigBcs(data)?.let { return it }
        }

        return null
    }

    /**
     * 解析 BLE 广播数据包（针对小米等无需连接直接广播体重的免配对/广播秤）。
     *
     * @param scanRecord 扫描返回的 [ScanRecord] 对象
     * @param preferredModel 用户手动偏好的型号（默认 AUTO）
     * @return 解析出有效测量数据则返回 [ParsedScaleData]，否则返回 null
     */
    fun parseAdvertisement(
        scanRecord: ScanRecord?,
        preferredModel: ScaleModel = ScaleModel.AUTO
    ): ParsedScaleData? {
        if (scanRecord == null) return null

        // 若广播中明确声明包含 AFU 服务 UUID (FFB0)，则必为双向 GATT 交互秤（需 FFB1 握手与 FFB2 订阅），绝不可作为广播秤处理
        if (scanRecord.serviceUuids?.any { it.uuid.toString().uppercase().contains("FFB0") } == true) {
            return null
        }

        // AFU 私有协议秤为双向 GATT 交互设备（需 FFB1 握手与 FFB2 订阅），绝非免配对广播秤，严禁在广播中截断连接
        if (preferredModel == ScaleModel.AFU_PROTOCOL) {
            return null
        }

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

        // 3. 尝试从厂商自定义数据 Manufacturer Data 中解析广播秤（如 Yolanda 免连接广播款、OKOK 广播款）
        val rawBytes = scanRecord.bytes
        if (rawBytes != null && rawBytes.size >= 6) {
            // 严格按 AD 结构遍历，检查厂商数据（0xFF）或服务数据（0x16）
            var i = 0
            while (i < rawBytes.size - 2) {
                val len = rawBytes[i].toInt() and 0xFF
                if (len == 0 || i + len >= rawBytes.size) break
                val type = rawBytes[i + 1].toInt() and 0xFF
                if ((type == 0xFF || type == 0x16) && len >= 5) {
                    val mfgPayload = rawBytes.copyOfRange(i + 2, i + 1 + len)
                    // 仅当非 AFU 协议报文（非 0xAC 或第 3 字节非 0x68..0x6E）时尝试 Yolanda 广播包
                    val isAfuHeader = (mfgPayload[0].toInt() and 0xFF) == 0xAC &&
                                     mfgPayload.size >= 4 &&
                                     ((mfgPayload[3].toInt() and 0xFF) in 0x68..0x6E)
                    if (!isAfuHeader) {
                        parseYolandaIcomon(mfgPayload)?.let { return it }
                    }

                    // 针对标准 0xFF 厂商数据（前 2 字节为蓝牙联盟 Company ID），跳过 2 字节后尝试解析
                    if (len >= 6) {
                        val payloadWithoutCompany = rawBytes.copyOfRange(i + 4, i + 1 + len)
                        val isAfuCompany = (payloadWithoutCompany[0].toInt() and 0xFF) == 0xAC &&
                                          payloadWithoutCompany.size >= 4 &&
                                          ((payloadWithoutCompany[3].toInt() and 0xFF) in 0x68..0x6E)
                        if (!isAfuCompany) {
                            parseYolandaIcomon(payloadWithoutCompany)?.let { return it }
                        }
                        parseOkokChipsea(payloadWithoutCompany)?.let { return it }
                    }
                    // 尝试无 Company ID 的 OKOK / 芯海透传格式
                    parseOkokChipsea(mfgPayload)?.let { return it }
                }
                i += len + 1
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // 协议 0：沃莱 (Yolanda) / 薄荷健康 (Boohee) / 深圳轻牛 / Fitdays / 合泰协议
    // -------------------------------------------------------------------------

    /**
     * 解析沃莱 (Yolanda) / 深圳轻牛 / 薄荷健康 (Boohee) / Fitdays / 合泰 常见体脂秤协议帧。
     *
     * 涵盖主流形态：
     * 1. 8 字节实时/锁定/阻抗流帧 (0xAC 0x02 / 0x03)：
     *    - 实时动态示数：AC 02 [W_HI] [W_LO] 00 00 CE [CHK] (体重 = raw / 100.0 kg, isStable = false)
     *    - 稳定锁定示数：AC 02 [W_HI] [W_LO] 00 00 CA [CHK] (体重 = raw / 100.0 kg, isStable = true)
     *    - 阻抗完成示数：AC 02 FD 01 [IMP_HI] [IMP_LO] CB [CHK] (阻抗 = raw Ω, weightKg = 0.0, isStable = true)
     *    - 部分变体使用 0xCC / 0xCD 状态位
     * 2. 20 字节复合帧 (0xA2 实时, 0xA3 稳定+阻抗, 或 0xAC 0x02/0x03 复合帧)：
     *    - 0xA2: 实时体重 (Byte 2~4: 24位无符号或 Byte 2~3: 16位无符号)
     *    - 0xA3: 稳定体重 + 阻抗
     *    - 0xAC 0x02 0xFF: 复合体脂包
     */
    fun parseYolandaIcomon(data: ByteArray): ParsedScaleData? {
        if (data.isEmpty()) return null

        val b0 = data[0].toInt() and 0xFF

        // ── 格式 1: 8 字节流式帧 (0xAC 开头) ──────────────────────────────────
        if (b0 == 0xAC && data.size >= 8) {
            val b1 = data[1].toInt() and 0xFF
            // 沃莱/薄荷健康 8 字节流通常以 0x02, 0x03, 0x05, 0x0A 为命令字
            if (b1 in listOf(0x02, 0x03, 0x05, 0x0A)) {
                val statusByte = data[6].toInt() and 0xFF

                // 1.1 阻抗专属数据包：AC 02 FD 01 [IMP_HI] [IMP_LO] CB [CHK]
                val b2 = data[2].toInt() and 0xFF
                val b3 = data[3].toInt() and 0xFF
                if (statusByte == 0xCB || (b2 == 0xFD && b3 == 0x01)) {
                    val rawImp = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                    if (rawImp in 100..1500) {
                        return ParsedScaleData(
                            weightKg = 0.0, // 标记为阻抗上报包，客户端保留已锁定的体重
                            isStable = true,
                            impedanceOhm = rawImp.toDouble(),
                            protocolName = "Yolanda/Boohee (0xCB)"
                        )
                    } else {
                        // 秤端指示未测得有效阻抗（例如 0x0000 或 0xFFFF 开路，表示穿袜或未踩电极）
                        return ParsedScaleData(
                            weightKg = 0.0,
                            isStable = true,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (0xCB No Imp)"
                        )
                    }
                }

                // 1.2 稳定锁定示数：AC 02 [W_HI] [W_LO] 00 00 CA [CHK]
                if (statusByte == 0xCA || statusByte == 0xCC) {
                    val rawWeight = (b2 shl 8) or b3
                    val weightKg = rawWeight / 100.0
                    if (weightKg in 0.0..350.0) {
                        val validStable = weightKg >= 3.0
                        return ParsedScaleData(
                            weightKg = weightKg,
                            isStable = validStable,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (0xCA)"
                        )
                    }
                }

                // 1.3 实时动态变动示数：AC 02 [W_HI] [W_LO] 00 00 CE [CHK]
                if (statusByte == 0xCE || statusByte == 0xCD) {
                    val rawWeight = (b2 shl 8) or b3
                    val weightKg = rawWeight / 100.0
                    if (weightKg in 0.0..350.0) {
                        return ParsedScaleData(
                            weightKg = weightKg,
                            isStable = false,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (0xCE)"
                        )
                    }
                }

                // 1.4 复合流式变体 (Byte 2~3 为体重, Byte 4~5 为保留/温度/状态, Byte 6 为稳定标志 0x01/0x02)
                if (statusByte in listOf(0x01, 0x02)) {
                    // 若 b3 在 0x68..0x6E 范围内，说明该帧为 AFU 协议报文（w3 基准偏移量 0x68），绝非 Yolanda，直接拒绝以防误判
                    if (b3 in 0x68..0x6E) return null

                    val rawWeight = (b2 shl 8) or b3
                    val weightKg = rawWeight / 100.0
                    if (weightKg in 0.0..350.0) {
                        // 注意：沃莱/薄荷健康 8 字节流式帧仅上报体重，Byte 4~5 常为环境温度、电池电压或 ADC 基准，绝非阻抗；
                        // 真实阻抗始终通过 1.1 专属的 0xCB / FD 01 报文送达。若在此处提取 Byte 4~5，会导致穿袜或未踩电极时误产生假阻抗！
                        return ParsedScaleData(
                            weightKg = weightKg,
                            isStable = (statusByte == 0x02) && weightKg >= 3.0,
                            impedanceOhm = null,
                            protocolName = "Yolanda/Boohee (Stream)"
                        )
                    }
                }
            }

            // 1.5 扩展复合包：AC 02 FF [W_HI] [W_LO] [IMP_HI] [IMP_LO] [STATUS] ...
            if (data.size >= 10 && (data[1].toInt() and 0xFF) in listOf(0x02, 0x03) && (data[2].toInt() and 0xFF) == 0xFF) {
                val rawWeight = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                val rawImp = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                val status = data[7].toInt() and 0xFF
                val weightKg = rawWeight / 100.0
                val isStable = (status == 0x02) && weightKg >= 3.0
                val imp = if (isStable && weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
                if (weightKg in 0.0..350.0) {
                    return ParsedScaleData(
                        weightKg = weightKg,
                        isStable = isStable,
                        impedanceOhm = imp,
                        protocolName = "Yolanda/Boohee (0xFF Composite)"
                    )
                }
            }
        }

        // ── 格式 2: 0xA2 / 0xA3 复合帧 (QNDoctor / 沃莱通用型) ──────────────────
        if ((b0 == 0xA2 || b0 == 0xA3) && data.size >= 7) {
            val isStable = (b0 == 0xA3)
            val w24 = ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
            val weightKg = if (w24 / 1000.0 in 3.0..350.0) {
                w24 / 1000.0
            } else {
                val w16 = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                w16 / 100.0
            }

            var imp: Double? = null
            if (isStable && data.size >= 9 && weightKg >= 3.0) {
                val rawImp = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
                if (rawImp in 100..1500) {
                    imp = rawImp.toDouble()
                }
            }

            if (weightKg in 0.0..350.0) {
                return ParsedScaleData(
                    weightKg = weightKg,
                    isStable = isStable && weightKg >= 3.0,
                    impedanceOhm = imp,
                    protocolName = if (isStable) "Yolanda (0xA3)" else "Yolanda (0xA2)"
                )
            }
        }

        // ── 格式 3: 0xCA 0x20 广播/透传复合帧 ──────────────────────────────────
        if (b0 == 0xCA && data.size >= 8 && (data[1].toInt() and 0xFF) == 0x20) {
            val flags = data[2].toInt() and 0xFF
            val isStable = (flags and 0x01 != 0) || (flags and 0x02 != 0)
            val rawWeight = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
            val weightKg = rawWeight / 100.0
            val rawImp = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
            val imp = if (isStable && weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
            if (weightKg in 0.0..350.0) {
                return ParsedScaleData(
                    weightKg = weightKg,
                    isStable = isStable && weightKg >= 3.0,
                    impedanceOhm = imp,
                    protocolName = "Yolanda (0xCA20)"
                )
            }
        }

        return null
    }

    // -------------------------------------------------------------------------
    // 协议 1：AFU 协议解析
    // -------------------------------------------------------------------------

    private fun parseAfu(data: ByteArray): ParsedScaleData? {
        val weightData = AFUPacketParser.parseWeight(data) ?: return null
        // 仅当示数已稳定锁定（weightData.isStable）且体重 >= 3.0kg 时，阻抗才具备生理意义且允许读取；
        // 动态测量中严禁读取阻抗；下秤发送 0.00kg 时正常上报体重，但阻抗必须置 null
        val impedance = if (weightData.isStable && weightData.weightKg >= 3.0) AFUPacketParser.parseImpedance(data) else null
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
        if (rawWeight == 0) {
            return ParsedScaleData(
                weightKg = 0.0,
                isStable = false,
                impedanceOhm = null,
                protocolName = "Bluetooth SIG WSS"
            )
        }
        if (rawWeight < 0) return null

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

        if (weightKg == 0.0 && rawFat == 0) {
            return ParsedScaleData(
                weightKg = 0.0,
                isStable = false,
                impedanceOhm = null,
                protocolName = "Bluetooth SIG BCS"
            )
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
        if (rawWeight == 0) {
            return ParsedScaleData(
                weightKg = 0.0,
                isStable = false,
                impedanceOhm = null,
                protocolName = "Xiaomi Mi Scale 2"
            )
        }
        if (rawWeight < 0) return null

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
        if (rawWeight == 0) {
            return ParsedScaleData(
                weightKg = 0.0,
                isStable = false,
                impedanceOhm = null,
                protocolName = "Xiaomi Mi Scale 1"
            )
        }
        if (rawWeight < 0) return null

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
            val year = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
            val month = data[4].toInt() and 0xFF
            val day = data[5].toInt() and 0xFF
            if ((year in 2015..2035 && month in 1..12 && day in 1..31) || (year == 0 && month == 0 && day == 0)) {
                return parseXiaomiBodyCompositionServiceData(data)
            }
        }
        if (data.size == 10) {
            val year = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
            val month = data[5].toInt() and 0xFF
            val day = data[6].toInt() and 0xFF
            if ((year in 2015..2035 && month in 1..12 && day in 1..31) || (year == 0 && month == 0 && day == 0)) {
                return parseXiaomiWeightScaleServiceData(data)
            }
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
                val imp = if (isStable && weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
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
                val imp = if (isStable && weightKg >= 3.0 && rawImp in 100..1500) rawImp.toDouble() else null
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
