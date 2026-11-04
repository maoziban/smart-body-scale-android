package com.example.dianzicheng.data.ble

import java.util.UUID

/**
 * 轻牛 (QN) / 薄荷健康 / 沃莱 (Yolanda) 双向握手协议。
 *
 * 此类秤连接后并不会直接推送体重，而是要求 App 对其下发的控制帧逐一回复 ACK：
 *   秤 → App 0x12 (配置请求)  ⇒ App → 秤 0x13 (单位/配置应答)
 *   秤 → App 0x14 (就绪)      ⇒ App → 秤 0x20 (时间同步)
 *   秤 → App 0x10 (体重帧)    ⇒ 稳定后 App → 秤 0x1F (测量完成确认)
 * 若没有回复 ACK，秤会停留在握手阶段，最终超时断开，表现为“连接上但没有数据”。
 *
 * 通用帧格式：[cmd][总长度][protocolType]...[checksum]，checksum 为前面所有字节之和的低 8 位。
 */
object QnProtocol {
    const val CMD_CONFIG_REQUEST = 0x12
    const val CMD_CONFIG_ACK = 0x13
    const val CMD_READY = 0x14
    const val CMD_WEIGHT = 0x10
    const val CMD_FINISH_ACK = 0x1F
    const val CMD_TIME_SYNC = 0x20

    /** 单位：0x01 = kg */
    const val UNIT_KG = 0x01

    /** QN 秤时间基准：2000-01-01 00:00:00 (UTC+8) 对应的 Unix 秒数 */
    private const val QN_EPOCH_OFFSET_SEC = 946702800L

    private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805F9B34FB")

    /** 通知特征 → 写入特征候选（按优先级），覆盖 FFE0 与 FFF0 两种固件方案。 */
    val NOTIFY_UUIDS: List<UUID> = listOf(uuid16("FFE1"), uuid16("FFF1"))
    val WRITE_UUID_CANDIDATES: List<UUID> = listOf(uuid16("FFE3"), uuid16("FFE2"), uuid16("FFF2"))
    val SERVICE_UUIDS: List<UUID> = listOf(uuid16("FFE0"), uuid16("FFF0"))

    fun isNotifyUuid(charUuid: String): Boolean {
        val up = charUuid.uppercase()
        return NOTIFY_UUIDS.any { up == it.toString().uppercase() }
    }

    /** 判断数据是否为 QN 控制帧（长度字段与实际长度一致，且命令字已知）。 */
    fun isQnFrame(data: ByteArray): Boolean {
        if (data.size < 3) return false
        val cmd = data[0].toInt() and 0xFF
        if (cmd !in setOf(CMD_CONFIG_REQUEST, CMD_READY, CMD_WEIGHT)) return false
        return (data[1].toInt() and 0xFF) == data.size
    }

    private fun withChecksum(body: ByteArray): ByteArray {
        val out = body.copyOf(body.size + 1)
        var sum = 0
        for (b in body) sum += (b.toInt() and 0xFF)
        out[body.size] = (sum and 0xFF).toByte()
        return out
    }

    /** 对 0x12 的应答：13 09 [proto] [unit] 10 00 00 00 [chk] */
    fun buildConfigAck(protocolType: Int, unit: Int = UNIT_KG): ByteArray =
        withChecksum(
            byteArrayOf(
                CMD_CONFIG_ACK.toByte(), 0x09, protocolType.toByte(), unit.toByte(),
                0x10, 0x00, 0x00, 0x00
            )
        )

    /** 对 0x14 的应答：20 08 [proto] [time LE 4B] [chk] */
    fun buildTimeSync(protocolType: Int, nowMillis: Long = System.currentTimeMillis()): ByteArray {
        val t = (nowMillis / 1000L - QN_EPOCH_OFFSET_SEC).coerceAtLeast(0L)
        return withChecksum(
            byteArrayOf(
                CMD_TIME_SYNC.toByte(), 0x08, protocolType.toByte(),
                (t and 0xFF).toByte(),
                ((t shr 8) and 0xFF).toByte(),
                ((t shr 16) and 0xFF).toByte(),
                ((t shr 24) and 0xFF).toByte()
            )
        )
    }

    /** 稳定数据收到后的确认：1F 05 [proto] 10 [chk] */
    fun buildFinishAck(protocolType: Int): ByteArray =
        withChecksum(byteArrayOf(CMD_FINISH_ACK.toByte(), 0x05, protocolType.toByte(), 0x10))

    /** 从 0x12 / 0x14 / 0x10 帧中取出 protocolType（第 3 字节）。 */
    fun protocolTypeOf(data: ByteArray): Int = if (data.size >= 3) data[2].toInt() and 0xFF else 0

    /**
     * 解析 0x10 体重帧：10 [len] [proto] [W_HI] [W_LO] [stable] [R1_HI] [R1_LO] [R2_HI] [R2_LO] ... [chk]
     * stable: 0 = 动态, 1 = 已锁定。
     */
    fun parseWeightFrame(data: ByteArray): MultiScalePacketParser.ParsedScaleData? {
        if (data.size < 6 || (data[0].toInt() and 0xFF) != CMD_WEIGHT) return null
        val raw = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        var weight = raw / 100.0
        if (weight > 300.0) weight = raw / 10.0
        if (weight < 0.0 || weight > 300.0) return null
        val stable = (data[5].toInt() and 0xFF) == 0x01
        var imp: Double? = null
        if (stable && data.size >= 9) {
            val r1 = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (r1 in 100..3000) imp = r1.toDouble()
        }
        return MultiScalePacketParser.ParsedScaleData(
            weightKg = weight,
            isStable = stable,
            impedanceOhm = imp,
            protocolName = "Yolanda/QN (0x10)"
        )
    }
}
