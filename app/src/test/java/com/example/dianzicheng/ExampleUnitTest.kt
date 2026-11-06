package com.example.dianzicheng

import com.example.dianzicheng.data.ble.AFUPacketParser
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun testAFUPacketParser() {
        // 未测出阻抗（穿袜/锁定初始帧，第 8-9 字节为固件特征码 0x05 40，第 17-18 字节全为 0）
        val rawDataWithoutImp = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x00.toByte(), 0x69.toByte(), 0x40.toByte(),
            0x82.toByte(), 0x02.toByte(), 0x00.toByte(), 0x05.toByte(), 0x40.toByte(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        )
        
        val weightData = AFUPacketParser.parseWeight(rawDataWithoutImp)
        assertNotNull(weightData)
        assertEquals(82.05, weightData!!.weightKg, 0.0001)
        assertTrue(weightData.isStable)

        // 穿袜未踩电极时第 17-18 字节为 0，严禁将 0x05 40 误拼为 1344Ω，必须返回 null
        val impedanceNull = AFUPacketParser.parseImpedance(rawDataWithoutImp)
        assertNull(impedanceNull)

        // 实测沃莱 FG2313UWB-D 真实测得阻抗帧（第 17-18 字节为 0x29D6 = 10710 -> 0.1Ω 分辨率换算为 1071.0Ω）
        val rawDataWithImp = rawDataWithoutImp.copyOf()
        rawDataWithImp[17] = 0x29.toByte()
        rawDataWithImp[18] = 0xD6.toByte()

        val impedance = AFUPacketParser.parseImpedance(rawDataWithImp)
        assertNotNull(impedance)
        assertEquals(1071.0, impedance!!, 0.0001)
    }

    @Test
    fun testAFUPacketParserFinalCompletedFrame() {
        // 用户实测：转圈圈测脂完成后的最终数据帧 (Byte 2 == 0x02, 体重移至 offset+10..12, 阻抗在 offset+17..18)
        // AC 29 02 00 02 15 01 E7 01 80 69 2D 90 00 00 00 00 29 D6 07
        val finalFrame = byteArrayOf(
            0xAC.toByte(), 0x29.toByte(), 0x02.toByte(), 0x00.toByte(), 0x02.toByte(),
            0x15.toByte(), 0x01.toByte(), 0xE7.toByte(), 0x01.toByte(), 0x80.toByte(),
            0x69.toByte(), 0x2D.toByte(), 0x90.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x29.toByte(), 0xD6.toByte(), 0x07.toByte()
        )

        val weightData = AFUPacketParser.parseWeight(finalFrame)
        assertNotNull(weightData)
        assertEquals(77.20, weightData!!.weightKg, 0.0001)
        assertTrue(weightData.isStable)

        val impedance = AFUPacketParser.parseImpedance(finalFrame)
        assertNotNull(impedance)
        assertEquals(1071.0, impedance!!, 0.0001)
    }
}