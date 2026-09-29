package com.example.dianzicheng

import com.example.dianzicheng.data.phicomm.PhicommS7Manager
import com.example.dianzicheng.data.phicomm.PhicommS7Manager.PhicommS7Packet
import org.junit.Assert.*
import org.junit.Test

class PhicommS7ProtocolTest {

    @Test
    fun testPhicommS7RealtimeWeightString() {
        val json = """{"mac":"1234567890ab", "weight":"65.8", "time":"1590281609"}"""
        val packet = PhicommS7Manager.parsePacket(json)

        assertNotNull(packet)
        assertTrue(packet is PhicommS7Packet.WeightMeasurement)
        val meas = packet as PhicommS7Packet.WeightMeasurement
        assertEquals(65.8, meas.weightKg, 0.001)
        assertEquals("12:34:56:78:90:AB", meas.mac)
        assertEquals(1590281609000L, meas.timestampEpochMs)
    }

    @Test
    fun testPhicommS7RealtimeWeightScaledInt() {
        // 固件将体重乘以 100 整数存储 (7250 -> 72.5kg)
        val json = """{"mac":"aabbccddeeff", "weight":7250, "time":1590281609}"""
        val packet = PhicommS7Manager.parsePacket(json)

        assertNotNull(packet)
        assertTrue(packet is PhicommS7Packet.WeightMeasurement)
        val meas = packet as PhicommS7Packet.WeightMeasurement
        assertEquals(72.50, meas.weightKg, 0.001)
        assertEquals("AA:BB:CC:DD:EE:FF", meas.mac)
    }

    @Test
    fun testPhicommS7RealtimeWeightDouble() {
        val json = """{"mac":"12:34:56:78:90:AB", "weight":58.4}"""
        val packet = PhicommS7Manager.parsePacket(json)

        assertNotNull(packet)
        assertTrue(packet is PhicommS7Packet.WeightMeasurement)
        val meas = packet as PhicommS7Packet.WeightMeasurement
        assertEquals(58.4, meas.weightKg, 0.001)
        assertEquals("12:34:56:78:90:AB", meas.mac)
        assertTrue(meas.timestampEpochMs > 0)
    }

    @Test
    fun testPhicommS7HistoryPacket() {
        val json = """
            {
                "name":"zS7_90ab",
                "mac":"1234567890ab",
                "history": {
                    "weight": [6000, 6250],
                    "utc": [1590281600, 1590281609]
                }
            }
        """.trimIndent()
        val packet = PhicommS7Manager.parsePacket(json)

        assertNotNull(packet)
        assertTrue(packet is PhicommS7Packet.WeightMeasurement)
        val meas = packet as PhicommS7Packet.WeightMeasurement
        // 取最新一条记录 (6250 -> 62.5kg)
        assertEquals(62.5, meas.weightKg, 0.001)
        assertEquals("12:34:56:78:90:AB", meas.mac)
        assertEquals(1590281609000L, meas.timestampEpochMs)
    }

    @Test
    fun testPhicommS7DeviceReport() {
        val json = """{"name":"zS7_1234", "type":5, "type_name":"zS7", "mac":"1234567890ab"}"""
        val packet = PhicommS7Manager.parsePacket(json)

        assertNotNull(packet)
        assertTrue(packet is PhicommS7Packet.DeviceReport)
        val report = packet as PhicommS7Packet.DeviceReport
        assertEquals("zS7_1234", report.name)
        assertEquals("12:34:56:78:90:AB", report.mac)
    }

    @Test
    fun testInvalidJsonGracefulNull() {
        assertNull(PhicommS7Manager.parsePacket(""))
        assertNull(PhicommS7Manager.parsePacket("not a json"))
        assertNull(PhicommS7Manager.parsePacket("{}"))
        assertNull(PhicommS7Manager.parsePacket("""{"other_field": 123}"""))
    }

    @Test
    fun testMacAddressFormattingAndTrimming() {
        // 测试包含连字符与空格的 MAC 地址格式化
        val jsonHyphen = """{"mac":" 12-34-56-78-90-ab ", "weight":60.0}"""
        val packetHyphen = PhicommS7Manager.parsePacket(jsonHyphen)
        assertNotNull(packetHyphen)
        assertTrue(packetHyphen is PhicommS7Packet.WeightMeasurement)
        assertEquals("12:34:56:78:90:AB", (packetHyphen as PhicommS7Packet.WeightMeasurement).mac)

        // 测试包含多余空格的纯 12 位无分隔符 MAC 地址格式化
        val jsonHex = """{"mac":"  aabbccddeeff  ", "weight":60.0}"""
        val packetHex = PhicommS7Manager.parsePacket(jsonHex)
        assertNotNull(packetHex)
        assertTrue(packetHex is PhicommS7Packet.WeightMeasurement)
        assertEquals("AA:BB:CC:DD:EE:FF", (packetHex as PhicommS7Packet.WeightMeasurement).mac)
    }
}
