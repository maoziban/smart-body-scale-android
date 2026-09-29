package com.example.dianzicheng.domain

import java.util.Calendar
import kotlin.math.max
import com.example.dianzicheng.data.local.AppLogger

/**
 * 身体成分计算算法（BIA - 生物电阻抗分析）。
 *
 * 根据体重、阻抗、性别、身高、年龄计算体脂率、肌肉量、水分、蛋白质、骨量等指标。
 *
 * 注意：
 * - 当 [impedanceOhm] 为 0.0 时，表示本次未测得有效阻抗（用户未赤脚称重），
 *   所有 BIA 相关指标均返回 0.0，不使用估算回退，避免误导用户
 * - BMI 始终计算，无论是否有阻抗数据
 */
object BodyAlgorithm {

    /**
     * 计算单次称重的完整身体成分数据。
     *
     * @param weightKg        体重（公斤）
     * @param impedanceOhm    体内阻抗（欧姆），0.0 表示无阻抗数据
     * @param sex             性别（MALE / FEMALE），影响体脂率计算系数
     * @param heightCm        身高（厘米），用于计算 BMI 和作为 BIA 辅助参数
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳），用于推算年龄
     * @return 完整的 [BodyMeasurement] 对象；id 为空字符串，由 repository 层赋值
     */
    fun calculate(
        weightKg: Double,
        impedanceOhm: Double,
        sex: Sex,
        heightCm: Double,
        birthDateEpochMs: Long
    ): BodyMeasurement {
        // 限制身高在生理合理范围（50~250cm），彻底杜绝 heightCm <= 0 时的除以零或 NaN 异常
        val safeHeightCm = heightCm.coerceIn(50.0, 250.0)
        val heightM = safeHeightCm / 100.0
        val bmi = if (weightKg > 0.0) (weightKg / (heightM * heightM)).coerceIn(1.0, 100.0) else 0.0
        val age = calculateAge(birthDateEpochMs)

        // 仅当测得有效阻抗（> 0.0Ω 且体重有效）时，严格使用 BIA 生物电阻抗计算体脂及身体成分；
        // 否则所有 BIA 指标保持 0.0（不使用估算回退）
        val hasImpedance = impedanceOhm > 0.0 && weightKg >= 3.0

        // ── 体脂率（%）─────────────────────────────────────────────────────
        // 使用 BIA 公式：综合 BMI、年龄、阻抗三个维度加权计算
        val fat = if (hasImpedance) {
            val biaFat = if (sex == Sex.MALE) {
                0.18 * bmi + 0.012 * age + 0.018 * impedanceOhm - 3.2
            } else {
                0.26 * bmi + 0.011 * age + 0.020 * impedanceOhm - 2.5
            }
            biaFat.coerceIn(5.0, 55.0) // 限制在合理生理范围内
        } else {
            0.0
        }

        // ── 体内水分率（%）──────────────────────────────────────────────────
        // 与体脂率负相关：体脂越高，水分占比越低
        val water = if (hasImpedance && fat > 0) (69.7 - fat * 0.55).coerceIn(35.0, 75.0) else 0.0

        // ── 骨量（公斤）──────────────────────────────────────────────────────
        // 按性别不同比例估算，男性骨密度略高于女性
        val bone = if (hasImpedance && fat > 0)
            (weightKg * (if (sex == Sex.MALE) 0.047 else 0.040)).coerceIn(1.5, 5.5)
        else 0.0

        // ── 蛋白质率（%）──────────────────────────────────────────────────────
        // 与水分正相关，水分越高通常蛋白质比例也越高
        val protein = if (hasImpedance && fat > 0)
            (16.0 + (water - 50.0) * 0.12).coerceIn(10.0, 24.0)
        else 0.0

        // ── 肌肉量（公斤）──────────────────────────────────────────────────
        // 去脂体重扣除骨量即为肌肉量，保证不低于 0
        val muscle = if (hasImpedance && fat > 0)
            max(weightKg * (1 - fat / 100.0) - bone, 0.0)
        else 0.0

        return BodyMeasurement(
            id = "", // id 由 ScaleRepository 根据 sessionId 赋值，此处留空
            measuredAtEpochMs = System.currentTimeMillis(),
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            bmi = bmi,
            bodyFatPct = fat,
            muscleKg = muscle,
            waterPct = water,
            proteinPct = protein,
            boneMassKg = bone,
            memberId = null,           // 成员绑定由 ScaleRepository 处理
            memberNameSnapshot = null
        )
    }

    /**
     * 根据出生日期时间戳计算当前年龄（整数岁）。
     *
     * 以当年元旦为分界：若当年的第几天 < 出生日期的第几天，说明今年生日未过，年龄减 1。
     *
     * Bug #27 修复：当 birthDateEpochMs <= 0L 时（成员未填写出生日期），
     * 原实现会将 0L 当作 1970-01-01 计算，得到约 56 岁，导致体脂率偏高。
     * 现改为返回中性默认年龄 30 岁，减少未填生日时的计算偏差，并记录警告日志。
     *
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳），0 或负数表示未设置
     * @return 年龄（1~120 范围内，防止异常数据导致崩溃）
     */
    private fun calculateAge(birthDateEpochMs: Long): Int {
        // 出生日期未设置（0L 或负数）：返回中性默认年龄，避免 1970 年导致偏差
        if (birthDateEpochMs <= 0L) {
            AppLogger.w("BodyAlgorithm", "出生日期未设置（birthDateEpochMs=$birthDateEpochMs），使用默认年龄 30 岁参与体成分计算")
            return 30
        }
        val today = Calendar.getInstance()
        val birthDate = Calendar.getInstance().apply {
            timeInMillis = birthDateEpochMs
        }
        if (birthDate.after(today)) {
            AppLogger.w("BodyAlgorithm", "出生日期在未来（birthDateEpochMs=$birthDateEpochMs），使用默认年龄 30 岁参与体成分计算")
            return 30
        }
        var age = today.get(Calendar.YEAR) - birthDate.get(Calendar.YEAR)
        // 今年生日未过：年龄再减 1（使用月/日比较，避免闰年 DAY_OF_YEAR 偏差问题）
        val todayMonth = today.get(Calendar.MONTH)
        val todayDay   = today.get(Calendar.DAY_OF_MONTH)
        val birthMonth = birthDate.get(Calendar.MONTH)
        val birthDay   = birthDate.get(Calendar.DAY_OF_MONTH)
        if (todayMonth < birthMonth || (todayMonth == birthMonth && todayDay < birthDay)) {
            age--
        }
        return age.coerceIn(1, 120)
    }
}
