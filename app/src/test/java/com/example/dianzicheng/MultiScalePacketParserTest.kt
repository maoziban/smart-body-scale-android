package com.example.dianzicheng

import com.example.dianzicheng.data.ble.MultiScalePacketParser
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
}


