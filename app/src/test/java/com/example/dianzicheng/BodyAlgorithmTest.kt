package com.example.dianzicheng

import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class BodyAlgorithmTest {

    @Test
    fun testZeroWeightProducesZeroBmiAndMetrics() {
        val result = BodyAlgorithm.calculate(
            weightKg = 0.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = 946656000000L
        )

        assertEquals("0.0kg 体重时 BMI 必须为 0.0，不能被限制为 5.0", 0.0, result.bmi, 0.0001)
        assertEquals(0.0, result.bodyFatPct, 0.0001)
        assertEquals(0.0, result.muscleKg, 0.0001)
        assertEquals(0.0, result.waterPct, 0.0001)
        assertEquals(0.0, result.boneMassKg, 0.0001)
    }

    @Test
    fun testLightWeightBelow3KgSuppressesImpedanceMetrics() {
        val result = BodyAlgorithm.calculate(
            weightKg = 2.5,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = 946656000000L
        )

        // 体重低于 3.0kg（非人体正常体重）：不应计算体脂与肌肉
        assertEquals(0.0, result.bodyFatPct, 0.0001)
        assertEquals(0.0, result.muscleKg, 0.0001)
        assertEquals(0.0, result.waterPct, 0.0001)
        assertTrue(result.bmi > 0.0)
    }

    @Test
    fun testFutureBirthDateHandledSafely() {
        val futureCal = Calendar.getInstance().apply {
            add(Calendar.YEAR, 5)
        }
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = futureCal.timeInMillis
        )

        assertNotNull(result)
        assertTrue("体脂率应处于合理范围", result.bodyFatPct in 5.0..55.0)
        assertTrue("BMI 应处于正常范围", result.bmi in 15.0..30.0)
    }

    @Test
    fun testUnsetBirthDateHandledSafely() {
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.FEMALE,
            heightCm = 165.0,
            birthDateEpochMs = 0L
        )

        assertNotNull(result)
        assertTrue("体脂率应处于合理范围", result.bodyFatPct in 5.0..55.0)
    }

    @Test
    fun testSafeHeightClampingPreventsDivisionByZero() {
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 0.0, // 异常身高
            birthDateEpochMs = 946656000000L
        )

        assertFalse("BMI 不能为 NaN", result.bmi.isNaN())
        assertFalse("BMI 不能为无穷大", result.bmi.isInfinite())
        assertTrue("BMI 应该限制在合理最大值", result.bmi <= 100.0)
    }

    @Test
    fun testNormalAdultMeasurementCalculation() {
        val resultMale = BodyAlgorithm.calculate(
            weightKg = 72.0,
            impedanceOhm = 520.0,
            sex = Sex.MALE,
            heightCm = 178.0,
            birthDateEpochMs = 788918400000L // 1995年
        )

        assertTrue(resultMale.bmi in 20.0..25.0)
        assertTrue(resultMale.bodyFatPct in 10.0..25.0)
        assertTrue(resultMale.muscleKg > 40.0)
        assertTrue(resultMale.waterPct in 50.0..65.0)
        assertTrue(resultMale.boneMassKg in 2.5..4.0)

        val resultFemale = BodyAlgorithm.calculate(
            weightKg = 52.0,
            impedanceOhm = 550.0,
            sex = Sex.FEMALE,
            heightCm = 162.0,
            birthDateEpochMs = 788918400000L
        )

        assertTrue(resultFemale.bmi in 18.0..22.0)
        assertTrue(resultFemale.bodyFatPct in 10.0..30.0)
        assertTrue(resultFemale.waterPct in 45.0..65.0)
    }

    @Test
    fun testSmallWeightBmiCalculation() {
        // 测试小体重（如 5.10kg 测试场景）BMI 正确计算，不被错误 clamp 到 5.0 或 0.0
        val result = BodyAlgorithm.calculate(
            weightKg = 5.10,
            impedanceOhm = 0.0,
            sex = Sex.MALE,
            heightCm = 170.0,
            birthDateEpochMs = 946656000000L
        )

        // 5.10 / (1.7 * 1.7) = 5.10 / 2.89 ≈ 1.76
        assertTrue("BMI 必须大于 0", result.bmi > 0.0)
        assertEquals("BMI 应准确计算为 5.10 / 1.7^2 ≈ 1.76", 5.10 / (1.7 * 1.7), result.bmi, 0.01)
    }
}
