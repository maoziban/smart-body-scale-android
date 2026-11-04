package com.example.dianzicheng

import com.example.dianzicheng.data.ble.ImpedanceGate
import com.example.dianzicheng.data.ble.MultiScalePacketParser
import org.junit.Assert.*
import org.junit.Test

class ImpedanceGateTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val uuid = "0000FFB2-0000-1000-8000-00805F9B34FB"

    @Test
    fun lockedFrameImpedanceAccepted() {
        assertTrue(ImpedanceGate.accept(1344.0, true, 70.8, 70.8))
    }

    @Test
    fun unlockedOrEmptyScaleImpedanceRejected() {
        // 下秤后空载帧：体重 0、未稳定，阻抗跌到 384Ω，必须丢弃
        assertFalse(ImpedanceGate.accept(384.0, false, 0.0, 70.8))
    }

    @Test
    fun impedanceOnlyPacketAccepted() {
        // Yolanda 0xCB 阻抗专属包：体重 0 但带稳定标志
        assertTrue(ImpedanceGate.accept(500.0, true, 0.0, 70.8))
    }

    @Test
    fun noPersonRejected() {
        assertFalse(ImpedanceGate.accept(500.0, true, 0.0, 0.0))
        assertFalse(ImpedanceGate.accept(50.0, true, 70.0, 70.0))
    }

    @Test
    fun resetFramesAfterLockDoNotProduceStableResult() {
        val lock = b(0xAC,0x29,0x80,0x69,0x14,0x90,0x02,0x00,0x05,0x40,0,0,0,0,0,0,0,0,0,0)
        val reset = b(0xAC,0x29,0x02,0x00,0x01,0xE7,0x01,0xB2,0x01,0x80,0x69,0x14,0x90,0,0,0,0,0,0,0)
        val r = MultiScalePacketParser.parseNotification(lock, uuid)!!
        assertEquals(70.8, r.weightKg, 0.001)
        assertEquals(1344.0, r.impedanceOhm!!, 0.1)
        // 复位帧即使被某个协议解析出来，也不得携带可被接受的阻抗
        MultiScalePacketParser.parseNotification(reset, uuid)?.let {
            val imp = it.impedanceOhm
            assertTrue(imp == null || !ImpedanceGate.accept(imp, it.isStable, it.weightKg, 70.8))
        }
    }
}
