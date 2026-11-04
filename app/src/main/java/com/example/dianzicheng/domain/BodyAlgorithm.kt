package com.example.dianzicheng.domain

import java.util.Calendar
import kotlin.math.max
import com.example.dianzicheng.data.local.AppLogger

/**
 * 身体成分计算算法（BIA - 生物电阻抗分析）。
 *
 * 支持三套算法模型自由切换（详见 [BodyAlgorithmType]）：
 * 1. COMMERCIAL：商业对标模式（默认推荐），对标小米 / OKOK / 芯海官方 App，贴近足底硬件标定
 * 2. ACADEMIC：日内瓦物理模型，基于 Kyle & Kushner 阻抗指数 (H²/Z) 严谨物理推导
 * 3. LEGACY：经典线性加权模型，兼容旧版本简易累加公式
 *
 * 核心保障：
 * - 质量守恒保证：脂肪质量 + 肌肉质量 + 骨量质量恒等于总重量（100% 物理守恒，杜绝指标加和不等于体重）
 * - 赤脚检测保障：当 [impedanceOhm] 为 0.0 时，表示本次未测得有效阻抗（用户未赤脚称重），
 *   所有 BIA 相关指标均返回 0.0，不使用估算回退，避免误导用户
 * - BMI 始终计算，无论是否有阻抗数据
 */
object BodyAlgorithm {

    /**
     * 当前全局生效的算法类型，默认 COMMERCIAL 商业对标模式。
     * 可在应用启动时或用户在设置中选择后动态修改。
     */
    @Volatile
    var currentAlgorithm: BodyAlgorithmType = BodyAlgorithmType.COMMERCIAL

    /**
     * 计算单次称重的完整身体成分数据。
     *
     * @param weightKg        体重（公斤）
     * @param impedanceOhm    体内阻抗（欧姆），0.0 表示无阻抗数据
     * @param sex             性别（MALE / FEMALE），影响体脂率计算系数
     * @param heightCm        身高（厘米），用于计算 BMI 和作为 BIA 辅助参数
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳），用于推算年龄
     * @param algorithmType   指定使用的计算算法，若不传则使用当前全局配置 [currentAlgorithm]
     * @param measuredAtEpochMs 称重时刻（毫秒）；年龄按该时刻推算，历史重算时必须传入原称重时间
     * @return 完整的 [BodyMeasurement] 对象；id 为空字符串，由 repository 层赋值
     */
    fun calculate(
        weightKg: Double,
        impedanceOhm: Double,
        sex: Sex,
        heightCm: Double,
        birthDateEpochMs: Long,
        algorithmType: BodyAlgorithmType = currentAlgorithm,
        measuredAtEpochMs: Long = System.currentTimeMillis()
    ): BodyMeasurement {
        // 限制身高在生理合理范围（50~250cm），彻底杜绝 heightCm <= 0 时的除以零或 NaN 异常
        val safeHeightCm = heightCm.coerceIn(50.0, 250.0)
        val heightM = safeHeightCm / 100.0
        val bmi = if (weightKg > 0.0) (weightKg / (heightM * heightM)).coerceIn(1.0, 100.0) else 0.0
        val age = ageAt(birthDateEpochMs, measuredAtEpochMs)

        // 仅当测得有效阻抗（> 0.0Ω 且体重有效）时，严格使用 BIA 生物电阻抗计算体脂及身体成分；
        // 否则所有 BIA 指标保持 0.0（不使用估算回退）
        val hasImpedance = impedanceOhm > 0.0 && weightKg >= 3.0

        var fat = 0.0
        var water = 0.0
        var bone = 0.0
        var protein = 0.0
        var muscle = 0.0

        if (hasImpedance) {
            when (algorithmType) {
                BodyAlgorithmType.COMMERCIAL -> {
                    // ── 方案一：商业对标模式（小米 / 芯海 / openScale 硬件标定） ──────
                    val h = heightM
                    val baseLbm = (h * 9.058) * h + weightKg * 0.32 + 12.226 - impedanceOhm * 0.0068 - age * 0.0542
                    val rawLbm = if (sex == Sex.MALE) baseLbm else baseLbm * 0.842
                    val boundedLbm = rawLbm.coerceIn(weightKg * 0.35, weightKg * 0.95)

                    val rawFat = (weightKg - boundedLbm) / weightKg * 100.0
                    fat = rawFat.coerceIn(5.0, 55.0)

                    // 质量守恒修正：确保生效的瘦体重与截断后的体脂率严格一致（脂肪质量 + 瘦体重 = 总体重）
                    val effectiveLbm = weightKg * (1.0 - fat / 100.0)

                    // 去脂组织中约 72.5% 为水分（Pace & Rathbun 恒量）
                    val waterKg = effectiveLbm * 0.725
                    water = (waterKg / weightKg * 100.0).coerceIn(35.0, 75.0)

                    // 骨矿物质含量与去脂体重呈比例
                    val boneRatio = if (sex == Sex.MALE) 0.052 else 0.046
                    bone = (effectiveLbm * boneRatio).coerceIn(1.2, 5.5)

                    // 肌肉量 = 去脂体重 - 骨量（各成分精确相加等于总体重）
                    muscle = max(0.0, effectiveLbm - bone)

                    // 蛋白质占去脂体重的约 19.5%
                    val proteinKg = effectiveLbm * 0.195
                    protein = (proteinKg / weightKg * 100.0).coerceIn(10.0, 24.0)
                }

                BodyAlgorithmType.ACADEMIC -> {
                    // ── 方案二：学术物理日内瓦模型（Kyle & Kushner H²/Z 阻抗指数） ───
                    val index = (safeHeightCm * safeHeightCm) / impedanceOhm
                    // 足底双极秤电极无相角容抗测量，基于 +5.85Ω 容抗补偿修正女性常数至 +1.75，避免身材苗条女性体脂严重虚高
                    val rawFfm = if (sex == Sex.MALE) {
                        0.439 * index + 0.354 * weightKg + 0.052 * age + 6.47
                    } else {
                        0.412 * index + 0.336 * weightKg + 0.048 * age + 1.75
                    }
                    val boundedFfm = rawFfm.coerceIn(weightKg * 0.35, weightKg * 0.95)

                    val rawFat = (weightKg - boundedFfm) / weightKg * 100.0
                    fat = rawFat.coerceIn(5.0, 55.0)

                    // 质量守恒修正：去脂组织（FFM）与体脂率完全自洽
                    val effectiveFfm = weightKg * (1.0 - fat / 100.0)

                    // 经典生物化学：去脂组织（FFM）恒定含水量约为 73.2%
                    val waterKg = effectiveFfm * 0.732
                    water = (waterKg / weightKg * 100.0).coerceIn(35.0, 75.0)

                    val boneRatio = if (sex == Sex.MALE) 0.052 else 0.046
                    bone = (effectiveFfm * boneRatio).coerceIn(1.2, 5.5)
                    muscle = max(0.0, effectiveFfm - bone)

                    val proteinKg = effectiveFfm * 0.195
                    protein = (proteinKg / weightKg * 100.0).coerceIn(10.0, 24.0)
                }

                BodyAlgorithmType.LEGACY -> {
                    // ── 方案三：旧版简易线性加权回归模型 ────────────────────────────
                    val biaFat = if (sex == Sex.MALE) {
                        0.18 * bmi + 0.012 * age + 0.018 * impedanceOhm - 3.2
                    } else {
                        0.26 * bmi + 0.011 * age + 0.020 * impedanceOhm - 2.5
                    }
                    fat = biaFat.coerceIn(5.0, 55.0)
                    water = (69.7 - fat * 0.55).coerceIn(35.0, 75.0)
                    bone = (weightKg * (if (sex == Sex.MALE) 0.047 else 0.040)).coerceIn(1.2, 5.5)
                    protein = (16.0 + (water - 50.0) * 0.12).coerceIn(10.0, 24.0)
                    muscle = max(weightKg * (1 - fat / 100.0) - bone, 0.0)
                }
            }
        }

        return BodyMeasurement(
            id = "", // id 由 ScaleRepository 根据 sessionId 赋值，此处留空
            measuredAtEpochMs = measuredAtEpochMs,
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            bmi = bmi,
            bodyFatPct = fat,
            muscleKg = muscle,
            waterPct = water,
            proteinPct = protein,
            boneMassKg = bone,
            memberId = null,           // 成员绑定由 ScaleRepository 处理
            memberNameSnapshot = null,
            ageUsed = age,
            heightCmUsed = safeHeightCm,
            algorithmId = algorithmType.id
        )
    }

    /**
     * 根据出生日期与参考时刻计算整数岁年龄。
     *
     * 历史记录重算时以“称重时刻”为参考，使 10 个月前的记录使用当时的年龄，而不是今天的年龄。
     * 出生日期未设置（<= 0）、晚于参考时刻时，返回中性默认年龄 30 岁并记录警告。
     *
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳）
     * @param atEpochMs        参考时刻（Unix 毫秒时间戳），默认当前时间
     * @return 年龄（1~120）
     */
    fun ageAt(birthDateEpochMs: Long, atEpochMs: Long = System.currentTimeMillis()): Int {
        if (birthDateEpochMs <= 0L) {
            AppLogger.w("BodyAlgorithm", "出生日期未设置（birthDateEpochMs=$birthDateEpochMs），使用默认年龄 30 岁参与体成分计算")
            return 30
        }
        val ref = Calendar.getInstance().apply { timeInMillis = atEpochMs }
        val birthDate = Calendar.getInstance().apply { timeInMillis = birthDateEpochMs }
        if (birthDate.after(ref)) {
            AppLogger.w("BodyAlgorithm", "出生日期晚于称重时刻（birthDateEpochMs=$birthDateEpochMs, at=$atEpochMs），使用默认年龄 30 岁参与体成分计算")
            return 30
        }
        var age = ref.get(Calendar.YEAR) - birthDate.get(Calendar.YEAR)
        val rm = ref.get(Calendar.MONTH)
        val rd = ref.get(Calendar.DAY_OF_MONTH)
        val bm = birthDate.get(Calendar.MONTH)
        val bd = birthDate.get(Calendar.DAY_OF_MONTH)
        if (rm < bm || (rm == bm && rd < bd)) age--
        return age.coerceIn(1, 120)
    }

    /** 由整数年龄反推出生日期（取今天往前推 [age] 年），用于仅输入年龄的成员编辑场景。 */
    fun birthDateFromAge(age: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.YEAR, -age.coerceIn(1, 120))
        return c.timeInMillis
    }
}
