package com.example.dianzicheng.data.ble

/**
 * AFU 蓝牙体脂秤数据包解析器（单例对象）。
 *
 * 负责将蓝牙 GATT 通知回调中接收到的原始字节数组（ByteArray）按照
 * AFU 私有协议格式解析为可用的业务数据（体重、阻抗）。
 *
 * 协议帧结构（以 0xAC 作为帧头标志字节）：
 *   offset+0 : 0xAC（帧头标志）
 *   offset+3 : 体重高字节（需减去基准偏移 0x68）
 *   offset+4 : 体重中字节
 *   offset+5 : 体重低字节
 *   offset+6 : 稳定标志（0x02 表示稳定锁定）
 *   offset+7 : 阻抗高字节（标准位置，优先解析）
 *   offset+8 : 阻抗低字节（标准位置，优先解析；备用方案时作为高字节）
 *   offset+9 : 备用阻抗低字节
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

        // 未找到帧头，或帧头之后剩余字节不足 7 字节（无法完整解析体重+稳定位），则返回 null
        if (offset == -1 || data.size - offset < 7) return null

        // 读取体重三字节（分别为高、中、低字节），逐字节转为无符号整数
        val w3 = data[offset + 3].toInt() and 0xFF  // 体重最高字节（含偏移量 0x68）
        val w4 = data[offset + 4].toInt() and 0xFF  // 体重中字节
        val w5 = data[offset + 5].toInt() and 0xFF  // 体重低字节

        // offset+6 字节值为 0x02 时表示秤面已稳定锁定
        val isStable = (data[offset + 6].toInt() and 0xFF) == 0x02

        // 还原原始体重整数值：高字节需减去协议基准偏移 0x68，再组合为 24 位整数
        val rawWeight = (w3 - 0x68) * 65536 + w4 * 256 + w5

        // 将原始整数转换为千克（精度 0.001kg），负值或零统一返回 0.0
        val weight = if (rawWeight <= 0) 0.0 else rawWeight / 1000.0

        return WeightData(weight, isStable)
    }

    /**
     * 从原始字节数组中解析生物电阻抗（BIA）数值。
     *
     * 解析策略（双重容错）：
     * 1. 优先使用标准 AFU 协议位置（offset+7 高字节，offset+8 低字节）。
     * 2. 若标准位置阻抗值不在合理范围 [100, 1500] Ω，则尝试备用位置（offset+8, offset+9）。
     * 3. 两种位置均不满足有效范围则返回 null。
     *
     * 有效阻抗范围定义为 100～1500 Ω，低于或高于此区间视为无效数据。
     *
     * @param data 蓝牙特征值通知回调中收到的原始字节数组。
     * @return 解析成功时返回阻抗值（单位：Ω，Double 类型）；否则返回 null。
     */
    fun parseImpedance(data: ByteArray): Double? {
        // 空包直接返回
        if (data.isEmpty()) return null

        // 查找 AFU 协议帧头 0xAC 的位置
        val offset = data.indexOfFirst { (it.toInt() and 0xFF) == 0xAC }

        // 未找到帧头则无法解析
        if (offset == -1) return null

        // 优先尝试 7~8 字节（标准 AFU 协议：data[7]=高位, data[8]=低位）
        if (data.size - offset >= 9) {
            val imp7 = data[offset + 7].toInt() and 0xFF  // 阻抗高字节
            val imp8 = data[offset + 8].toInt() and 0xFF  // 阻抗低字节
            // 将两字节合并为 16 位无符号整数（大端序）
            val impA = (imp7 shl 8) or imp8
            // 校验阻抗值是否落在人体 BIA 有效范围内
            if (impA in 100..1500) {
                return impA.toDouble()
            }
        }

        // 备用：尝试 8~9 字节
        if (data.size - offset >= 10) {
            val imp8 = data[offset + 8].toInt() and 0xFF  // 备用阻抗高字节
            val imp9 = data[offset + 9].toInt() and 0xFF  // 备用阻抗低字节
            val impB = (imp8 shl 8) or imp9
            // 同样校验有效范围
            if (impB in 100..1500) {
                return impB.toDouble()
            }
        }

        // 两种解析位置均无有效数据，返回 null
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
