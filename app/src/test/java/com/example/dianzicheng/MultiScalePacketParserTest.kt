package com.example.dianzicheng

import com.example.dianzicheng.data.ble.MultiScalePacketParser
import com.example.dianzicheng.domain.ScaleModel
import org.junit.Assert.*
import org.junit.Test

class MultiScalePacketParserTest {

    @Test
    fun testAfuProtocolNotification() {
        // 经典的 AFU 协议数据包
        val rawData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )

        val result = MultiScalePacketParser.parseNotification(rawData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertTrue(result.isStable)
        assertNotNull(result.impedanceOhm)
        assertEquals(1344.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testBluetoothSigStandardWssKg() {
        // 蓝牙 SIG 标准 Weight Measurement (0x2A9D) 特征包 (SI 单位, kg)
        // Flags: 0x00 (SI 单位, 无额外字段)
        // Weight: 70.0 kg -> 70.0 / 0.005 = 14000 -> 0x36B0 (小端序: 0xB0, 0x36)
        val sigData = byteArrayOf(
            0x00,
            0xB0.toByte(), 0x36.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(sigData, "00002A9D-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertTrue(result!!.protocolName.contains("Bluetooth SIG"))
        assertEquals(70.0, result.weightKg, 0.05)
        assertTrue(result.isStable)
    }

    @Test
    fun testBluetoothSigStandardWssLbs() {
        // 蓝牙 SIG 标准 Weight Measurement (0x2A9D) 特征包 (Imperial 单位, lbs)
        // Flags: 0x01 (Imperial 单位, lbs)
        // Weight: 154.32 lbs (约 70.0 kg) -> 15432 -> 0x3C48 (小端序: 0x48, 0x3C)
        val sigData = byteArrayOf(
            0x01,
            0x48.toByte(), 0x3C.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(sigData, "00002A9D-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertTrue(result!!.protocolName.contains("Bluetooth SIG"))
        assertEquals(70.0, result.weightKg, 0.2)
        assertTrue(result.isStable)
    }

    @Test
    fun testXiaomiBodyCompositionScale2Notification() {
        // 小米体脂秤 2 (13 字节广播 / GATT 数据帧)
        // Flags (小端序): 0x00A2 (Bit 1: 稳定, Bit 5: 完成, Bit 7: 阻抗有效, 单位 kg)
        // Date: 2026-09-15 12:00:00 -> 0xEA, 0x07 (2026), 0x09, 0x0F, 0x0C, 0x00, 0x00
        // Impedance: 500 Ω -> 0x01F4 (小端序: 0xF4, 0x01)
        // Weight: 65.0 kg -> 65.0 * 200 = 13000 -> 0x32C8 (小端序: 0xC8, 0x32)
        val xiaomiData = byteArrayOf(
            0xA2.toByte(), 0x00.toByte(),                                           // Byte 0~1: Flags
            0xEA.toByte(), 0x07.toByte(), 0x09.toByte(), 0x0F.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x1E.toByte(), // Byte 2~8: 2026-09-15 12:00:30 (7B 时间戳)
            0xF4.toByte(), 0x01.toByte(),                                           // Byte 9~10: 阻抗 500 Ω (小端序)
            0xC8.toByte(), 0x32.toByte()                                            // Byte 11~12: 体重 65.0 kg (65 * 200 = 13000, 小端序)
        )

        val result = MultiScalePacketParser.parseNotification(xiaomiData, null)
        assertNotNull(result)
        assertTrue(result!!.protocolName.contains("Xiaomi"))
        assertEquals(65.0, result.weightKg, 0.01)
        assertTrue(result.isStable)
        assertEquals(500.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testXiaomiScale1CattyUnitConversion() {
        // 小米体重秤 1 代 (10 字节数据帧)
        // Flags: 0x30 (Bit 4: 斤/Catty, Bit 5: 稳定锁定)
        // Weight: 130.0 斤 (即 65.0 kg) -> 130.0 * 100 = 13000 -> 0x32C8 (小端序: 0xC8, 0x32)
        val xiaomiData = byteArrayOf(
            0x30.toByte(),
            0xC8.toByte(), 0x32.toByte(),
            0xEA.toByte(), 0x07.toByte(), 0x09.toByte(), 0x0F.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(xiaomiData, null)
        assertNotNull(result)
        assertTrue(result!!.protocolName.contains("Xiaomi"))
        assertEquals(65.0, result.weightKg, 0.01)
        assertTrue(result.isStable)
    }

    @Test
    fun testOkokChipseaProtocol() {
        // OKOK / 芯海科技 0x10 协议帧
        // 0x10, len 0x07, weight (大端序: 68.5kg -> 6850 -> 0x1AC2), status 0x01 (稳定), imp 480Ω (0x01E0)
        val okokData = byteArrayOf(
            0x10.toByte(),
            0x07.toByte(),
            0x1A.toByte(), 0xC2.toByte(),
            0x01.toByte(),
            0x01.toByte(), 0xE0.toByte(),
            0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(okokData, "0000FFF1-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertTrue(result!!.protocolName.contains("OKOK"))
        assertEquals(68.5, result.weightKg, 0.01)
        assertTrue(result.isStable)
        assertEquals(480.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testBluetoothSigStandardBcs() {
        // 蓝牙 SIG 标准 Body Composition Measurement (0x2A9C)
        // Flags: 0x0600 (Bit 9: 包含阻抗, Bit 10: 包含体重) -> 小端序 0x00, 0x06
        // Body Fat Pct: 18.5% -> 185 -> 0x00B9 -> 0xB9, 0x00
        // Impedance: 520.0 Ω -> 5200 (分辨率 0.1Ω) -> 0x1450 -> 0x50, 0x14
        // Weight: 72.5 kg -> 72.5 / 0.005 = 14500 -> 0x38A4 -> 0xA4, 0x38
        val bcsData = byteArrayOf(
            0x00.toByte(), 0x06.toByte(),
            0xB9.toByte(), 0x00.toByte(),
            0x50.toByte(), 0x14.toByte(),
            0xA4.toByte(), 0x38.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(bcsData, "00002A9C-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Bluetooth SIG BCS", result!!.protocolName)
        assertEquals(72.5, result.weightKg, 0.05)
        assertTrue(result.isStable)
        assertNotNull(result.impedanceOhm)
        assertEquals(520.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testAfuStepOffZeroWeightPacket() {
        // AFU 下秤 0.00kg 离秤数据包：必须能被成功解析为 0.0kg，且阻抗为 null，稳定标志为 false
        val zeroData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x68.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(), 0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(zeroData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull("下秤 0.00kg 数据包不应被丢弃", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(0.0, result.weightKg, 0.001)
        assertFalse(result.isStable)
        assertNull("空秤/离秤时严禁产生阻抗", result.impedanceOhm)
    }

    @Test
    fun testAfuUnder3KgSuppressesImpedanceAndStability() {
        // AFU 踩秤爬升阶段 (例如 1.5kg < 3.0kg)：允许上报实时动态体重，但严禁产生阻抗与稳定锁定
        val lightData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x68.toByte(), 0x05.toByte(),
            0xDC.toByte(), 0x02.toByte(), 0x05.toByte(), 0x40.toByte(), 0x00.toByte() // 0x05DC = 1500g = 1.5kg, 标志位设为 0x02 (试图锁定)
        )

        val result = MultiScalePacketParser.parseNotification(lightData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals(1.5, result!!.weightKg, 0.01)
        assertFalse("低于 3.0kg 绝对不允许稳定锁定", result.isStable)
        assertNull("低于 3.0kg 绝对不允许读取阻抗", result.impedanceOhm)
    }

    @Test
    fun testOkokStepOffZeroWeightPacket() {
        // OKOK 0x10 格式下秤 0.00kg 数据包
        val okokZero = byteArrayOf(
            0x10.toByte(), 0x07.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0xE0.toByte(), 0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(okokZero, "0000FFF1-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals(0.0, result!!.weightKg, 0.001)
        assertFalse(result.isStable)
        assertNull("0.0kg 严禁产生阻抗", result.impedanceOhm)
    }

    @Test
    fun testXiaomiBodyCompositionScale2StepOffZeroWeight() {
        // 小米体脂秤 2 下秤 0.00kg 数据帧
        val xiaomiZero = byteArrayOf(
            0x00.toByte(), 0x00.toByte(),
            0xEA.toByte(), 0x07.toByte(), 0x09.toByte(), 0x0F.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x1E.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte() // 0kg
        )

        val result = MultiScalePacketParser.parseNotification(xiaomiZero, null)
        assertNotNull("小米体脂秤 2 下秤 0.00kg 数据包不应被丢弃", result)
        assertEquals(0.0, result!!.weightKg, 0.001)
        assertFalse(result.isStable)
        assertNull("0.0kg 离秤时严禁产生阻抗", result.impedanceOhm)
    }

    @Test
    fun testXiaomiScale1StepOffZeroWeight() {
        // 小米体重秤 1 下秤 0.00kg 数据帧
        val xiaomi1Zero = byteArrayOf(
            0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), // 0kg
            0xEA.toByte(), 0x07.toByte(), 0x09.toByte(), 0x0F.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x00.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(xiaomi1Zero, null)
        assertNotNull("小米体重秤 1 下秤 0.00kg 数据包不应被丢弃", result)
        assertEquals(0.0, result!!.weightKg, 0.001)
        assertFalse(result.isStable)
    }

    @Test
    fun testBluetoothSigWssStepOffZeroWeight() {
        // 蓝牙 SIG WSS 0x2A9D 0.00kg 数据帧
        val sigZero = byteArrayOf(
            0x00,
            0x00, 0x00
        )

        val result = MultiScalePacketParser.parseNotification(sigZero, "00002A9D-0000-1000-8000-00805F9B34FB")
        assertNotNull("蓝牙 SIG WSS 下秤 0.00kg 数据包不应被丢弃", result)
        assertEquals(0.0, result!!.weightKg, 0.001)
        assertFalse(result.isStable)
    }

    @Test
    fun testYolandaBooheeLiveSettlingWeight() {
        // 沃莱/薄荷健康 8 字节动态示数：AC 02 1A C2 00 00 CE 4E (68.50kg)
        val data = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(),
            0x00, 0x00,
            0xCE.toByte(), 0x4E
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda/Boohee (0xCE)", result!!.protocolName)
        assertEquals(68.50, result.weightKg, 0.01)
        assertFalse("动态示数状态下严禁标记为稳定锁定", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testYolandaBooheeStableLockedWeight() {
        // 沃莱/薄荷健康 8 字节稳定锁定：AC 02 1A C2 00 00 CA 4A (68.50kg)
        val data = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(),
            0x00, 0x00,
            0xCA.toByte(), 0x4A
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda/Boohee (0xCA)", result!!.protocolName)
        assertEquals(68.50, result.weightKg, 0.01)
        assertTrue("0xCA 标志必须判定为稳定锁定", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testYolandaBooheeImpedancePacket() {
        // 沃莱/薄荷健康 8 字节阻抗专属报文：AC 02 FD 01 02 08 CB D5 (520Ω)
        val data = byteArrayOf(
            0xAC.toByte(), 0x02,
            0xFD.toByte(), 0x01,
            0x02, 0x08,
            0xCB.toByte(), 0xD5.toByte()
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda/Boohee (0xCB)", result!!.protocolName)
        assertEquals(0.0, result.weightKg, 0.001)
        assertTrue(result.isStable)
        assertNotNull(result.impedanceOhm)
        assertEquals(520.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testYolandaCompositeA3Packet() {
        // 沃莱 20 字节 0xA3 复合帧 (稳定体重 68.54kg + 阻抗 520Ω)
        // 68.54kg = 68540g -> 0x010BB8
        val data = byteArrayOf(
            0xA3.toByte(), 0x19,
            0x01, 0x0B, 0xB8.toByte(),
            0x02, 0x08,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = MultiScalePacketParser.parseNotification(data, "0000FFA1-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals("Yolanda (0xA3)", result!!.protocolName)
        assertEquals(68.54, result.weightKg, 0.01)
        assertTrue(result.isStable)
        assertEquals(520.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testAfuParserRejectsYolandaPacketsToPreventBogusNumbers() {
        // 核心防串扰校验：验证 AFUPacketParser 绝不会把 Yolanda 8 字节帧误解析为 5832kg
        val yolandaData = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(),
            0x00, 0x00,
            0xCA.toByte(), 0x4A
        )

        val afuWeight = com.example.dianzicheng.data.ble.AFUPacketParser.parseWeight(yolandaData)
        assertNull("AFUPacketParser 必须拒绝 Yolanda 8 字节帧，严禁产出数千公斤未知数值", afuWeight)

        // 验证 AFUPacketParser 也绝不会把 Yolanda 阻抗包误判为 AFU 0.0kg 体重
        val yolandaImp = byteArrayOf(
            0xAC.toByte(), 0x02,
            0xFD.toByte(), 0x01,
            0x02, 0x08,
            0xCB.toByte(), 0xD5.toByte()
        )
        val afuWeight2 = com.example.dianzicheng.data.ble.AFUPacketParser.parseWeight(yolandaImp)
        assertNull("AFUPacketParser 必须拒绝 Yolanda 阻抗帧", afuWeight2)
    }

    @Test
    fun testXiaomiPayloadRejectsRandomBytes() {
        // 任意 13 字节随机报文（年份超出 2015..2035），严禁被误判为小米体脂秤
        val random13 = byteArrayOf(
            0x01, 0x02,
            0x99.toByte(), 0x99.toByte(), // 年份 0x9999 = 39321
            0x25, 0x50, 0x10, 0x20, 0x30,
            0x01, 0x02,
            0x10, 0x20
        )
        val result = MultiScalePacketParser.parseNotification(random13, null)
        assertNull("非法日期的 13 字节数据严禁误判为小米体脂秤", result)
    }

    @Test
    fun testScaleModelFromId() {
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("auto"))
        assertEquals(ScaleModel.BOOHEE_YOLANDA, ScaleModel.fromId("boohee_yolanda"))
        assertEquals(ScaleModel.XIAOMI_SCALE_2, ScaleModel.fromId("xiaomi_scale_2"))
        assertEquals(ScaleModel.XIAOMI_SCALE_1, ScaleModel.fromId("xiaomi_scale_1"))
        assertEquals(ScaleModel.OKOK_CHIPSEA, ScaleModel.fromId("okok_chipsea"))
        assertEquals(ScaleModel.SENSSUN, ScaleModel.fromId("senssun"))
        assertEquals(ScaleModel.AFU_PROTOCOL, ScaleModel.fromId("afu_protocol"))
        assertEquals(ScaleModel.PHICOMM_S7, ScaleModel.fromId("phicomm_s7"))
        assertEquals(ScaleModel.PHICOMM_S9, ScaleModel.fromId("phicomm_s9"))
        assertEquals(ScaleModel.SIG_STANDARD, ScaleModel.fromId("sig_standard"))
        assertEquals(ScaleModel.OTHER_GENERIC, ScaleModel.fromId("other_generic"))
        // 未知型号默认回退到 AUTO
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId("some_unknown_model_xyz"))
        assertEquals(ScaleModel.AUTO, ScaleModel.fromId(null))
    }

    @Test
    fun testPreferredModelPrioritizationBoohee() {
        val yolandaData = byteArrayOf(
            0xA3.toByte(), 0x19,
            0x01, 0x0B, 0xB8.toByte(), // 68540g -> 68.54 kg
            0x02, 0x08,                // 520 -> 520 Ω
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        // 使用 ScaleModel.BOOHEE_YOLANDA 作为 preferredModel
        val result = MultiScalePacketParser.parseNotification(
            yolandaData,
            "0000FFA1-0000-1000-8000-00805F9B34FB",
            preferredModel = ScaleModel.BOOHEE_YOLANDA
        )
        assertNotNull(result)
        assertEquals("Yolanda (0xA3)", result!!.protocolName)
        assertEquals(68.54, result.weightKg, 0.01)
        assertTrue(result.isStable)
        assertEquals(520.0, result.impedanceOhm!!, 0.1)
    }

    @Test
    fun testPreferredModelPrioritizationXiaomi() {
        val xiaomiData = byteArrayOf(
            0xA2.toByte(), 0x00.toByte(),
            0xEA.toByte(), 0x07.toByte(), 0x09.toByte(), 0x0F.toByte(), 0x0C.toByte(), 0x00.toByte(), 0x1E.toByte(),
            0xF4.toByte(), 0x01.toByte(),
            0xC8.toByte(), 0x32.toByte()
        )
        val result = MultiScalePacketParser.parseNotification(
            xiaomiData,
            null,
            preferredModel = ScaleModel.XIAOMI_SCALE_2
        )
        assertNotNull(result)
        assertTrue(result!!.protocolName.contains("Xiaomi"))
        assertEquals(65.0, result.weightKg, 0.01)
        assertTrue(result.isStable)
    }

    @Test
    fun testPreferredModelPrioritizationSenssun() {
        // 香山 0xAA 帧: [0xAA, seq, weight_hi, weight_lo, status, checksum]
        // 650 -> 65.0 kg (0x028A)
        val senssunData = byteArrayOf(
            0xAA.toByte(), 0x01,
            0x02, 0x8A.toByte(),
            0x01,
            0x00
        )
        val result = MultiScalePacketParser.parseNotification(
            senssunData,
            "0000FFF1-0000-1000-8000-00805F9B34FB",
            preferredModel = ScaleModel.SENSSUN
        )
        assertNotNull(result)
        assertEquals("Senssun", result!!.protocolName)
        assertEquals(65.0, result.weightKg, 0.01)
        assertTrue(result.isStable)
    }

    @Test
    fun testAfu7ByteDynamicPacket() {
        // AFU 7 字节实时动态测量数据包：AC 07 00 68 12 34 00 (4.66kg, 动态测量未锁定)
        val dynamic7 = byteArrayOf(
            0xAC.toByte(), 0x07, 0x00, 0x68, 0x12, 0x34, 0x00
        )
        val result = MultiScalePacketParser.parseNotification(dynamic7, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull("AFU 7 字节实时动态包必须成功解析", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(4.66, result.weightKg, 0.001)
        assertFalse("状态为 0x00 时必须判定为未锁定（实时动态示数）", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testAfu8ByteDynamicPacket() {
        // AFU 8 字节实时动态示数包：AC 08 00 69 40 82 01 00 (82.05kg, 动态测量中 status=0x01)
        val dynamic8 = byteArrayOf(
            0xAC.toByte(), 0x08, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x01, 0x00
        )
        val result = MultiScalePacketParser.parseNotification(dynamic8, null, ScaleModel.AUTO)
        assertNotNull("AFU 8 字节动态包在 AUTO 模式下必须成功解析", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertFalse("status=0x01 时必须判定为未锁定（实时变动示数）", result.isStable)
        assertNull(result.impedanceOhm)
    }

    @Test
    fun testAfu8ByteLockedPacketWithoutImpedance() {
        // AFU 8 字节锁定示数包：AC 08 00 69 40 82 02 00 (82.05kg, 锁定 status=0x02, 数据不足9字节无阻抗)
        val locked8 = byteArrayOf(
            0xAC.toByte(), 0x08, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x02, 0x00
        )
        val result = MultiScalePacketParser.parseNotification(locked8, null, ScaleModel.AFU_PROTOCOL)
        assertNotNull("AFU 8 字节锁定包必须成功解析", result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertTrue("status=0x02 且体重>=3kg 时必须判定为稳定锁定", result.isStable)
        assertNull("8 字节数据包不包含阻抗字节，阻抗应为 null", result.impedanceOhm)
    }

    @Test
    fun testAfuPacketNotInterpretedAsYolandaStream() {
        // 验证 AFU 报文绝不会被误识别为 Yolanda Stream 流式协议（杜绝产生 ~1.05kg 伪造数值）
        val afuPacket = byteArrayOf(
            0xAC.toByte(), 0x02, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x02, 0x00
        )
        val yolandaResult = MultiScalePacketParser.parseYolandaIcomon(afuPacket)
        assertNull("Yolanda 解析器必须拒绝 AFU 报文 (b3 in 0x68..0x6E)", yolandaResult)
    }

    @Test
    fun testAfuWithPrefixBytes() {
        // 验证帧头 0xAC 前有前缀杂质字节时依然能准确定位并解析
        val prefixed = byteArrayOf(
            0xFF.toByte(), 0xAC.toByte(), 0x07, 0x00, 0x69.toByte(), 0x40.toByte(), 0x82.toByte(), 0x02
        )
        val result = MultiScalePacketParser.parseNotification(prefixed, null, ScaleModel.AUTO)
        assertNotNull(result)
        assertEquals("AFU", result!!.protocolName)
        assertEquals(82.05, result.weightKg, 0.001)
        assertTrue(result.isStable)
    }

    @Test
    fun testYolandaStreamStatus01IsDynamicNotStable() {
        // 沃莱流式包：statusByte 为 0x01 代表测量中（动态爬升），严禁判定为 isStable=true
        val streamDynamic = byteArrayOf(
            0xAC.toByte(), 0x02, 0x02, 0x00, 0x00, 0x00, 0x01, 0x00
        )
        val result = MultiScalePacketParser.parseYolandaIcomon(streamDynamic)
        assertNotNull(result)
        assertEquals(5.12, result!!.weightKg, 0.01)
        assertFalse("statusByte 为 0x01 时必须判定为实时动态变动，严禁过早锁定", result.isStable)
    }

    @Test
    fun testYolandaStreamStatus02IsStable() {
        // 沃莱流式包：statusByte 为 0x02 代表测量完成稳定锁定
        val streamLocked = byteArrayOf(
            0xAC.toByte(), 0x02, 0x02, 0x00, 0x00, 0x00, 0x02, 0x00
        )
        val result = MultiScalePacketParser.parseYolandaIcomon(streamLocked)
        assertNotNull(result)
        assertEquals(5.12, result!!.weightKg, 0.01)
        assertTrue("statusByte 为 0x02 时必须判定为稳定锁定", result.isStable)
    }

    @Test
    fun testAfuNoElectrodeContactReturnsNullImpedance() {
        // AFU 协议：用户穿袜或未踩电极时，秤端在 offset+7 和 offset+8 发送 0x00 0x00，尾部为校验和（如 0x96 = 150）
        // 必须解析出有效稳定体重，但阻抗绝对必须为 null，严禁将尾部校验和误当作阻抗！
        val afuSocksOnData = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0x96.toByte() // 0x96 = 150
        )

        val result = MultiScalePacketParser.parseNotification(afuSocksOnData, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertEquals(82.05, result!!.weightKg, 0.001)
        assertTrue(result.isStable)
        assertNull("穿袜或未踩电极（0x0000）时阻抗必须为 null，严禁将尾部校验和 0x96 误作为 150Ω 阻抗", result.impedanceOhm)

        // 开路情况（0xFF 0xFF）
        val afuOpenCircuit = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x96.toByte()
        )
        val resultOpen = MultiScalePacketParser.parseNotification(afuOpenCircuit, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(resultOpen)
        assertNull("开路 0xFFFF 时阻抗必须为 null", resultOpen!!.impedanceOhm)
    }

    @Test
    fun testAfuDynamicWeightSuppressesImpedance() {
        // AFU 动态测量中 (status = 0x00)，即使数据中有非零字节，也严禁读取阻抗
        val dynamicWithNoise = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x00.toByte(), 0x02.toByte(), 0x08.toByte(), 0x00.toByte() // 0x0208 = 520
        )
        val result = MultiScalePacketParser.parseNotification(dynamicWithNoise, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertFalse(result!!.isStable)
        assertNull("未稳定锁定时绝对不允许读取阻抗", result.impedanceOhm)
    }

    @Test
    fun testYolandaStreamDoesNotExtractImpedanceFromWeightFrame() {
        // 沃莱/薄荷 8 字节流式帧：稳定锁定 (status = 0x02)，但 byte 4~5 为室内温度 24.0°C (0x00F0 = 240)
        // 严禁将温度误作为 240Ω 阻抗！阻抗只能来自专属的 0xCB 报文
        val weightFrameWithTemp = byteArrayOf(
            0xAC.toByte(), 0x02,
            0x1A, 0xC2.toByte(), // 68.50 kg
            0x00, 0xF0.toByte(), // 室内温度 24.0°C (240)
            0x02, 0x4A
        )
        val result = MultiScalePacketParser.parseYolandaIcomon(weightFrameWithTemp)
        assertNotNull(result)
        assertEquals(68.50, result!!.weightKg, 0.01)
        assertTrue(result.isStable)
        assertNull("8 字节体重流式帧中绝不能提取阻抗，严禁将室内温度误解析为阻抗", result.impedanceOhm)
    }

    @Test
    fun testYolanda0CbNoElectrodeContactReturnsNullImpedance() {
        // 沃莱 0xCB 阻抗报文：秤端上报 0 表示未测出阻抗
        val cbZero = byteArrayOf(
            0xAC.toByte(), 0x02,
            0xFD.toByte(), 0x01,
            0x00, 0x00,
            0xCB.toByte(), 0xD5.toByte()
        )
        val result = MultiScalePacketParser.parseNotification(cbZero, "0000FFB2-0000-1000-8000-00805F9B34FB")
        assertNotNull(result)
        assertNull("阻抗上报 0 时阻抗应为 null", result!!.impedanceOhm)
    }
}


