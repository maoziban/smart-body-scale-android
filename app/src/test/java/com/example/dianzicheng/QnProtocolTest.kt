package com.example.dianzicheng

import com.example.dianzicheng.data.ble.QnProtocol
import org.junit.Assert.*
import org.junit.Test

class QnProtocolTest {
    private fun checksumOk(f: ByteArray): Boolean {
        var s = 0
        for (i in 0 until f.size - 1) s += f[i].toInt() and 0xFF
        return (s and 0xFF) == (f.last().toInt() and 0xFF)
    }

    @Test
    fun configAckFormat() {
        val f = QnProtocol.buildConfigAck(0x01)
        assertEquals(9, f.size)
        assertEquals(0x13, f[0].toInt() and 0xFF)
        assertEquals(f.size, f[1].toInt() and 0xFF)
        assertTrue(checksumOk(f))
    }

    @Test
    fun timeSyncFormat() {
        val f = QnProtocol.buildTimeSync(0x01, 946702800000L + 0x01020304L * 1000)
        assertEquals(8, f.size)
        assertEquals(f.size, f[1].toInt() and 0xFF)
        assertEquals(0x04, f[3].toInt() and 0xFF)
        assertEquals(0x01, f[6].toInt() and 0xFF)
        assertTrue(checksumOk(f))
    }

    @Test
    fun finishAckFormat() {
        val f = QnProtocol.buildFinishAck(0x01)
        assertEquals(5, f.size)
        assertTrue(checksumOk(f))
    }

    @Test
    fun parseStableWeight() {
        // 70.00kg stable, R1 = 500
        val frame = byteArrayOf(0x10, 0x0C, 0x01, 0x1B, 0x58, 0x01, 0x01, 0xF4.toByte(), 0x00, 0x00, 0x00, 0x00)
        assertTrue(QnProtocol.isQnFrame(frame))
        val r = QnProtocol.parseWeightFrame(frame)!!
        assertEquals(70.0, r.weightKg, 0.001)
        assertTrue(r.isStable)
        assertEquals(500.0, r.impedanceOhm!!, 0.001)
    }
}
