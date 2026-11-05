package com.example.dianzicheng

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.ui.HistoryMetric
import com.example.dianzicheng.ui.HistoryTimeRange
import com.example.dianzicheng.ui.calculateTrendStatistics
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class TrendStatisticsTest {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    @Test
    fun testEmptyMeasurementsReturnsGracefulEmptyStats() {
        val stats = calculateTrendStatistics(
            measurements = emptyList(),
            metric = HistoryMetric.WEIGHT,
            timeRange = HistoryTimeRange.HALF_YEAR,
            memberName = "石头"
        )

        assertTrue(stats.points.isEmpty())
        assertNull(stats.earliest)
        assertNull(stats.latest)
        assertNull(stats.minPoint)
        assertNull(stats.maxPoint)
        assertEquals(0.0, stats.change, 0.001)
        assertEquals(0, stats.distinctDays)
        assertEquals("--", stats.dateRangeText)
    }

    @Test
    fun testWeightChangeAndExtremesCalculation() {
        val now = System.currentTimeMillis()
        val dayMs = 86400000L

        // 创建近半年内的3条记录（模拟用户上传的参考图）
        val m1 = BodyMeasurement(
            id = "1",
            measuredAtEpochMs = now - 60 * dayMs,
            weightKg = 69.80,
            impedanceOhm = 500.0,
            bmi = 22.8,
            bodyFatPct = 19.5,
            muscleKg = 53.0,
            waterPct = 58.0,
            proteinPct = 16.0,
            boneMassKg = 3.2,
            memberId = "stone",
            memberNameSnapshot = "石头"
        )
        val m2 = BodyMeasurement(
            id = "2",
            measuredAtEpochMs = now - 30 * dayMs,
            weightKg = 69.95, // 最高
            impedanceOhm = 500.0,
            bmi = 22.9,
            bodyFatPct = 19.6,
            muscleKg = 53.0,
            waterPct = 58.0,
            proteinPct = 16.0,
            boneMassKg = 3.2,
            memberId = "stone",
            memberNameSnapshot = "石头"
        )
        val m3 = BodyMeasurement(
            id = "3",
            measuredAtEpochMs = now - 5 * dayMs,
            weightKg = 68.95, // 最新且最低
            impedanceOhm = 500.0,
            bmi = 22.5,
            bodyFatPct = 18.5,
            muscleKg = 53.2,
            waterPct = 59.0,
            proteinPct = 16.2,
            boneMassKg = 3.2,
            memberId = "stone",
            memberNameSnapshot = "石头"
        )

        val stats = calculateTrendStatistics(
            measurements = listOf(m3, m2, m1), // 倒序输入
            metric = HistoryMetric.WEIGHT,
            timeRange = HistoryTimeRange.HALF_YEAR,
            memberName = "石头"
        )

        // 验证点数和排序
        assertEquals(3, stats.points.size)
        assertEquals(m1.id, stats.earliest?.first?.id)
        assertEquals(m3.id, stats.latest?.first?.id)

        // 验证变化量：最新 68.95 - 期初 69.80 = -0.85 kg
        assertEquals(-0.85, stats.change, 0.001)

        // 验证天数
        assertEquals(3, stats.distinctDays)

        // 验证极值：最高 69.95kg，最低 68.95kg
        assertEquals(69.95, stats.maxPoint?.second ?: 0.0, 0.001)
        assertEquals(68.95, stats.minPoint?.second ?: 0.0, 0.001)

        // 验证平均值：(69.80 + 69.95 + 68.95) / 3 = 69.5666...
        assertEquals(69.567, stats.average, 0.01)

        // 验证日期区间文本非空且包含 '-'
        assertTrue(stats.dateRangeText.contains("-"))
    }

    @Test
    fun testBiaFilteringForBodyFatMetric() {
        val now = System.currentTimeMillis()
        val dayMs = 86400000L

        // 未赤脚称重（阻抗为0，体脂为0）的记录
        val mWithoutBia = BodyMeasurement(
            id = "1",
            measuredAtEpochMs = now - 10 * dayMs,
            weightKg = 70.0,
            impedanceOhm = 0.0,
            bmi = 23.0,
            bodyFatPct = 0.0,
            muscleKg = 0.0,
            waterPct = 0.0,
            proteinPct = 0.0,
            boneMassKg = 0.0,
            memberId = "stone",
            memberNameSnapshot = "石头"
        )

        // 赤脚有效测得体脂的记录
        val mWithBia = BodyMeasurement(
            id = "2",
            measuredAtEpochMs = now - 5 * dayMs,
            weightKg = 69.5,
            impedanceOhm = 510.0,
            bmi = 22.7,
            bodyFatPct = 18.2,
            muscleKg = 53.5,
            waterPct = 59.5,
            proteinPct = 16.5,
            boneMassKg = 3.2,
            memberId = "stone",
            memberNameSnapshot = "石头"
        )

        val stats = calculateTrendStatistics(
            measurements = listOf(mWithoutBia, mWithBia),
            metric = HistoryMetric.BODY_FAT,
            timeRange = HistoryTimeRange.ONE_MONTH,
            memberName = "石头"
        )

        // 应当自动过滤无效体脂记录，避免走势图跌到 0%
        assertEquals(1, stats.points.size)
        assertEquals(18.2, stats.points.first().second, 0.001)
    }

    @Test
    fun testTimeRangeFilterCutoff() {
        val now = System.currentTimeMillis()
        val dayMs = 86400000L

        val recent = BodyMeasurement("1", now - 3 * dayMs, 70.0, 0.0, 23.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null)
        val old = BodyMeasurement("2", now - 15 * dayMs, 71.0, 0.0, 23.5, 0.0, 0.0, 0.0, 0.0, 0.0, null, null)

        val weekFiltered = HistoryTimeRange.ONE_WEEK.filter(listOf(recent, old))
        assertEquals(1, weekFiltered.size)
        assertEquals("1", weekFiltered.first().id)

        val monthFiltered = HistoryTimeRange.ONE_MONTH.filter(listOf(recent, old))
        assertEquals(2, monthFiltered.size)
    }

    @Test
    fun testRebindRecalculationLogic() {
        // 模拟误测场景：原本归属于女性（160cm），重新匹配给男性（175cm）
        val rawMeasurement = BodyMeasurement(
            id = "test-rebind",
            measuredAtEpochMs = System.currentTimeMillis(),
            weightKg = 70.0,
            impedanceOhm = 500.0,
            bmi = 27.34, // 70 / (1.6 * 1.6)
            bodyFatPct = 26.5,
            muscleKg = 48.0,
            waterPct = 52.0,
            proteinPct = 15.0,
            boneMassKg = 2.8,
            memberId = "female-id",
            memberNameSnapshot = "妈妈"
        )

        val targetMember = com.example.dianzicheng.domain.FamilyMember(
            id = "male-id",
            name = "爸爸",
            sex = com.example.dianzicheng.domain.Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = System.currentTimeMillis() - 35L * 365 * 86400000L,
            referenceWeightKg = 70.0
        )

        val recalculated = com.example.dianzicheng.domain.BodyAlgorithm.calculate(
            weightKg = rawMeasurement.weightKg,
            impedanceOhm = rawMeasurement.impedanceOhm,
            sex = targetMember.sex,
            heightCm = targetMember.heightCm,
            birthDateEpochMs = targetMember.birthDateEpochMs,
            measuredAtEpochMs = rawMeasurement.measuredAtEpochMs
        ).copy(
            id = rawMeasurement.id,
            memberId = targetMember.id,
            memberNameSnapshot = targetMember.name,
            recalcCount = rawMeasurement.recalcCount + 1
        )

        assertEquals("male-id", recalculated.memberId)
        assertEquals("爸爸", recalculated.memberNameSnapshot)
        // 重新计算后 BMI 应该更新为 70 / (1.75 * 1.75) = 22.86
        assertEquals(22.86, recalculated.bmi, 0.1)
        assertEquals(1, recalculated.recalcCount)
    }
}
