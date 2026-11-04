package com.example.dianzicheng

import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyAlgorithmType
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class BodyAlgorithmRecalcTest {
    private fun ms(y: Int, m: Int, d: Int) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test
    fun ageIsComputedAtMeasurementTime() {
        val birth = ms(1990, 6, 15)
        assertEquals(29, BodyAlgorithm.ageAt(birth, ms(2020, 6, 14)))
        assertEquals(30, BodyAlgorithm.ageAt(birth, ms(2020, 6, 15)))
        assertEquals(25, BodyAlgorithm.ageAt(birth, ms(2016, 1, 1)))
    }

    @Test
    fun calculateRecordsBasisAndUsesMeasurementTime() {
        val birth = ms(1990, 6, 15)
        val at = ms(2020, 7, 1)
        val r = BodyAlgorithm.calculate(70.0, 500.0, Sex.MALE, 175.0, birth, BodyAlgorithmType.ACADEMIC, at)
        assertEquals(30, r.ageUsed)
        assertEquals(175.0, r.heightCmUsed, 0.001)
        assertEquals("ACADEMIC", r.algorithmId)
        assertEquals(at, r.measuredAtEpochMs)
    }

    @Test
    fun olderAgeChangesBodyFat() {
        val birth = ms(1990, 6, 15)
        val young = BodyAlgorithm.calculate(70.0, 500.0, Sex.MALE, 175.0, birth, BodyAlgorithmType.COMMERCIAL, ms(2012, 1, 1))
        val old = BodyAlgorithm.calculate(70.0, 500.0, Sex.MALE, 175.0, birth, BodyAlgorithmType.COMMERCIAL, ms(2030, 1, 1))
        assertNotEquals(young.bodyFatPct, old.bodyFatPct, 0.0001)
    }

    @Test
    fun birthDateFromAgeRoundTrips() {
        assertEquals(33, BodyAlgorithm.ageAt(BodyAlgorithm.birthDateFromAge(33)))
    }
}
