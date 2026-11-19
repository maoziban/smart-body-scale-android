package com.example.dianzicheng

import com.example.dianzicheng.data.ble.MultiScalePacketParser
import com.example.dianzicheng.domain.ScaleModel
import org.junit.Assert.*
import org.junit.Test

/** 帧均取自 2026-11-10 02:53 Xiaomi 15 / AFU-BH-TZ-B1 实测日志。 */
class AfuB1Af45ParserTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private val RECORD = "AF 45 00 B3 C4 F0 6A 3C F0 08 80 00 01 33 02 00 00 00 54 0F"

    /** 测量中帧 (hex, kg)，byte5 = 00 未锁定 */
    private val measuring = listOf(
        "AF 45 66 D0 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1D" to 53.35,
        "AF 45 60 D1 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 18" to 53.60,
        "AF 45 D6 E7 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 04" to 59.35,
        "AF 45 16 EE 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 0B" to 60.95,
        "AF 45 54 EC 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 07" to 60.50,
        "AF 45 D0 E8 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 1F" to 59.60,
        "AF 45 F2 F3 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 0C" to 62.45,
        "AF 45 B4 F5 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 10" to 62.90,
        "AF 45 AC EE 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 01" to 61.10,
        "AF 45 3C F0 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 13" to 61.50,
        "AF 45 0A F0 08 00 02 80 01 88 38 4B 00 00 00 00 00 00 51 01" to 61.45,
    )

    @Test
    fun measuringFramesAreUnstable() {
        for ((h, kg) in measuring) {
            val r = MultiScalePacketParser.parseNotification(hex(h), "0000FFB2-0000-1000-8000-00805F9B34FB")
            assertNotNull(h, r)
            assertEquals(MultiScalePacketParser.PROTOCOL_AFU_B1_AF45, r!!.protocolName)
            assertEquals(h, kg, r.weightKg, 0.001)
            assertFalse(h, r.isStable)
            assertNull(r.impedanceOhm)
        }
    }

    @Test
    fun byte5Bit7IsLock() {
        val r = MultiScalePacketParser.parseNotification(hex("AF 45 3C F0 08 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 13"))
        assertEquals(61.50, r!!.weightKg, 0.001)
        assertTrue(r.isStable)
    }

    @Test
    fun recordFrameIsIgnoredButDetected() {
        assertNull(MultiScalePacketParser.parseNotification(hex(RECORD)))
        assertNull(MultiScalePacketParser.parseNotification(hex(RECORD), null, ScaleModel.AFU_PROTOCOL))
        assertTrue(MultiScalePacketParser.isAfuB1Af45RecordFrame(hex(RECORD)))
        assertFalse(MultiScalePacketParser.isAfuB1Af45RecordFrame(hex(measuring[0].first)))
    }

    @Test
    fun badChecksumRejected() {
        val bad = hex(measuring[0].first).also { it[19] = 0x1C }
        assertNull(MultiScalePacketParser.parseAfuB1Af45(bad))
        assertFalse(MultiScalePacketParser.isAfuB1Af45RecordFrame(hex(RECORD).also { it[19] = 0x0E }))
    }

    @Test
    fun byte4Bit0IsWeightBit16() {
        // 67.096 kg = 0x10618 -> [2..3] = 18 06，byte4 = 09；校验 = sum([2..18]) & 0x1F
        val f = hex("AF 45 18 06 09 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 00")
        f[19] = ((2..18).sumOf { f[it].toInt() and 0xFF } and 0x1F).toByte()
        val r = MultiScalePacketParser.parseAfuB1Af45(f)
        assertEquals(67.096, r!!.weightKg, 0.001)
        assertTrue(r.isStable)
    }

    @Test
    fun lightFramesReportZeroAndNeverLock() {
        // 0.66 kg, byte4 = 08, 已锁定 -> 仍按空载 0 kg、不锁定
        val f = hex("AF 45 94 02 08 80 02 80 01 88 38 4B 00 00 00 00 00 00 51 00")
        f[19] = ((2..18).sumOf { f[it].toInt() and 0xFF } and 0x1F).toByte()
        val r = MultiScalePacketParser.parseAfuB1Af45(f)
        assertEquals(0.0, r!!.weightKg, 0.0)
        assertFalse(r.isStable)
    }

    @Test
    fun recordFrameParsesWeightAndImpedance() {
        val recWithImp = MultiScalePacketParser.parseAfuB1Af45Record(hex(RECORD))
        assertNotNull(recWithImp)
        assertEquals(61.50, recWithImp!!.weightKg, 0.001)
        assertTrue(recWithImp.isLocked)
        assertEquals(563.0, recWithImp.impedanceOhm!!, 0.001)

        // 80.10 kg（byte9 bit0 = 1 进位），无阻抗（[13..14] = 00 00）
        val recNoImp = MultiScalePacketParser.parseAfuB1Af45Record(
            hex("AF 45 00 ED 04 F7 6A E4 38 09 80 00 01 00 00 00 00 00 54 0C")
        )
        assertNotNull(recNoImp)
        assertEquals(80.10, recNoImp!!.weightKg, 0.001)
        assertTrue(recNoImp.isLocked)
        assertNull(recNoImp.impedanceOhm)
    }
}
