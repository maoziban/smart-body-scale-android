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
}