package com.example.dianzicheng.data.ble

/**
 * AFU 蓝牙体脂秤数据包解析器（单例对象）。
 *
 * 负责将蓝牙 GATT 通知回调中接收到的原始字节数组（ByteArray）按照
 * AFU 私有协议格式解析为可用的业务数据（体重、阻抗）。
 *
 * 协议帧结构（以 0xAC 作为帧头标志字节，常见 20 字节完整通知帧）：
 *   offset+0  : 0xAC（帧头标志）
 *   offset+3  : 体重高字节（需减去基准偏移 0x68）
 *   offset+4  : 体重中字节
 *   offset+5  : 体重低字节
 *   offset+6  : 稳定标志（0x02 表示稳定锁定，0x03~0x05 为测脂中/锁定）
 *   offset+8  : 固件特征/配置码（如恒为 0x05，非阻抗）
 *   offset+9  : 固件特征/配置码（如恒为 0x40，非阻抗）
 *   offset+17 : 真实生物电阻抗高字节（沃莱 FG2313UWB-D 等标准位置）
 *   offset+18 : 真实生物电阻抗低字节（大端序，若高位单位为 0.1Ω 则需除以 10）
 */
object AFUPacketParser {

    /**
     * 从原始字节数组中解析体重数据。
     *
     * 解析流程：
     * 1. 在字节数组中搜索 0xAC 帧头字节的位置（offset）。
     * 2. 从 offset 起读取体重三字节并还原为千克数值。
     * 3. 读取稳定标志位，判断是否已稳定锁定。
     *
     * @param data 蓝牙特征值通知回调中收到的原始字节数组。
     * @return 解析成功时返回 [WeightData]；数据不足或未找到帧头时返回 null。
     */
    fun parseWeight(data: ByteArray): WeightData? {
        // 空包直接返回
        if (data.isEmpty()) return null

        // 在字节数组中查找 AFU 协议帧头标志字节 0xAC 的起始偏移
        val offset = data.indexOfFirst { (it.toInt() and 0xFF) == 0xAC }

        // 只要数据包含 0xAC 且剩余至少 7 字节（包含体重最高、中、低及状态位），即可解析（兼顾 7/8 字节实时动态体重包）
        if (offset < 0 || data.size - offset < 7) return null

        // 读取体重三字节（分别为高、中、低字节），逐字节转为无符号整数
        val w3 = data[offset + 3].toInt() and 0xFF  // 体重最高字节（含偏移量 0x68）
        val w4 = data[offset + 4].toInt() and 0xFF  // 体重中字节
        val w5 = data[offset + 5].toInt() and 0xFF  // 体重低字节

        // 关键特征校验：AFU 协议的 w3 必定以 0x68 为基准（0~400kg 对应 0x68..0x6E）
        // 若超出此范围，绝非 AFU 协议帧（避免误解析 Yolanda、薄荷等其它 0xAC 报文）
        if (w3 !in 0x68..0x6E) return null

        val status = data[offset + 6].toInt() and 0xFF
        // AFU 状态位只可能是 0x00, 0x01（实时）或 0x02（稳定锁定）；若大于 0x05 则非 AFU 帧（如 Yolanda 的 0xCA..0xCE）
        if (status > 0x05) return null

        // offset+6 字节值为 0x02~0x05 时均表示秤面已稳定锁定（0x03~0x05 为体脂阻抗测量中及完成）
        val isStable = status in 0x02..0x05

        // 还原原始体重整数值：高字节需减去协议基准偏移 0x68，再组合为 24 位整数
        val rawWeight = (w3 - 0x68) * 65536 + w4 * 256 + w5

        // 将原始整数转换为千克（精度 0.001kg），负值或零统一返回 0.0；体重 <= 0 时稳定标志强制为 false
        val weight = if (rawWeight <= 0) 0.0 else rawWeight / 1000.0
        if (weight > 350.0) return null

        val effectiveStable = isStable && weight > 0.0

        return WeightData(weight, effectiveStable)
    }

    /**
     * 从原始字节数组中解析生物电阻抗（BIA）数值。
     *
     * 解析策略：
     * 1. 优先解析 20 字节标准复合帧（沃莱 FG2313UWB-D 等）：
     *    真实人体生物电阻抗存储在帧的第 17 与 18 字节（大端序），
     *    实测值如 0x29D6 = 10710，采用 0.1Ω 分辨率，需除以 10 得到 1071.0Ω；
     *    而第 8-9 字节（offset+8/offset+9）恒为 0x05 40，为固件特征码，严禁作为阻抗解析！
     *    若第 17-18 字节为 0x00 0x00 或 0xFF 0xFF，说明未测出阻抗（穿袜未踩电极、开路），直接返回 null。
     * 2. 备用兼容：针对非标准/精简短帧（小于 19 字节的变体），严格剔除 0x05 40 固件码干扰。
     *
     * @param data 蓝牙特征值通知回调中收到的原始字节数组。
     * @return 解析成功时返回阻抗值（单位：Ω，Double 类型）；否则返回 null。
     */
    fun parseImpedance(data: ByteArray): Double? {
        // 空包直接返回
        if (data.isEmpty()) return null

        // 查找 AFU 协议帧头 0xAC 的位置
        val offset = data.indexOfFirst { (it.toInt() and 0xFF) == 0xAC }
        if (offset < 0 || data.size - offset < 7) return null

        // 校验是否符合 AFU 协议特征 (w3 in 0x68..0x6E)
        val w3 = data[offset + 3].toInt() and 0xFF
        if (w3 !in 0x68..0x6E) return null

        val status = data[offset + 6].toInt() and 0xFF
        // AFU 协议中 status < 0x02 表示实时动态变动中，阻抗必须在示数稳定锁定或阻抗测量阶段（0x02..0x05）才能测得
        if (status < 0x02 || status > 0x05) return null

        // 1. 优先解析 20 字节标准/复合帧（沃莱 FG2313UWB-D 等）：
        // 真正的人体生物电阻抗存储在帧的第 17 与 18 字节（大端序），
        // 必须优先从此位置提取；帧第 8-9 字节（offset+8/offset+9）恒为 0x05 40，为固件特征码，绝不能作为阻抗！
        if (data.size - offset >= 19) {
            val imp17 = data[offset + 17].toInt() and 0xFF
            val imp18 = data[offset + 18].toInt() and 0xFF

            // 若明确为 0x00 0x00 或 0xFF 0xFF，说明秤端明确指示未测得阻抗（穿袜、未踩电极、开路）
            if ((imp17 == 0 && imp18 == 0) || (imp17 == 0xFF && imp18 == 0xFF)) {
                return null
            }

            val rawImp = (imp17 shl 8) or imp18
            // 阻抗单位换算：
            // FG2313UWB-D 等高精度型号实测大端序 0x29D6 = 10710，单位为 0.1Ω，需除以 10 得到 1071.0Ω；
            // 部分标准型号直接上报 1Ω 整数值（如 500 表示 500Ω）。
            val ohm = if (rawImp > 1500) rawImp / 10.0 else rawImp.toDouble()
            if (ohm in 100.0..1500.0) {
                return ohm
            }
            // 完整帧中若第 17-18 字节未解析出合法阻抗，直接返回 null，杜绝回退到前面字节造成伪阻抗
            return null
        }

        // 2. 备用兼容：针对非标准精简短帧（如 9~10 字节无 17-18 字节的变体）
        // 严禁将恒定的 0x05 40 (1344Ω) 误判为阻抗
        if (data.size - offset >= 10) {
            val imp8 = data[offset + 8].toInt() and 0xFF
            val imp9 = data[offset + 9].toInt() and 0xFF
            if (imp8 == 0x05 && imp9 == 0x40) {
                // 恒定固件标识，非阻抗，忽略
            } else if ((imp8 == 0 && imp9 == 0) || (imp8 == 0xFF && imp9 == 0xFF)) {
                return null
            } else if (imp8 > 0) {
                val rawImp = (imp8 shl 8) or imp9
                val ohm = if (rawImp > 1500) rawImp / 10.0 else rawImp.toDouble()
                if (ohm in 100.0..1500.0) {
                    return ohm
                }
            }
        }

        if (data.size - offset >= 9) {
            val imp7 = data[offset + 7].toInt() and 0xFF
            val imp8 = data[offset + 8].toInt() and 0xFF
            if (imp7 in 1..5) {
                val imp78 = (imp7 shl 8) or imp8
                if (imp78 in 100..1500) {
                    return imp78.toDouble()
                }
            }
        }

        // 均无有效阻抗数据，返回 null
        return null
    }

    /**
     * 体重数据数据类，封装单次体重解析结果。
     *
     * @property weightKg  解析得到的体重，单位：千克（kg），精度约 0.001kg。
     * @property isStable  true 表示秤面示数已稳定锁定（协议标志位 0x02），可用于触发后续 BIA 测量。
     */
    data class WeightData(val weightKg: Double, val isStable: Boolean)
}
