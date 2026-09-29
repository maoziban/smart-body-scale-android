package com.example.dianzicheng.data.repository

import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.data.local.toDomain
import com.example.dianzicheng.data.local.toEntity
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*
import kotlin.math.abs

/**
 * 体重秤数据仓库，封装 Room DAO 操作，提供领域层可用的接口。
 *
 * 职责：
 * 1. 提供测量记录和家庭成员的增删查 Flow/挂起函数接口
 * 2. 保存测量时自动执行成员智能匹配与 BIA 指标重新计算
 * 3. 在保存成功后更新成员参考体重，提升下次匹配精准度
 */
class ScaleRepository(private val dao: ScaleDao) {

    /**
     * 获取所有测量历史记录（Flow，按时间倒序）。
     * 历史页订阅此 Flow，数据库更新后自动推送最新列表。
     */
    fun getHistory(): Flow<List<BodyMeasurement>> =
        dao.getAllMeasurements().map { entities -> entities.map { it.toDomain() } }

    /**
     * 一次性获取所有测量记录列表（挂起函数）。
     * 用于 Health Connect 批量同步、WebDAV 备份等非实时场景。
     */
    suspend fun getAllMeasurements(): List<BodyMeasurement> =
        dao.getAllMeasurementsList().map { it.toDomain() }

    /**
     * 获取所有家庭成员列表（Flow）。
     * ScaleViewModel 和 ProfileViewModel 均订阅此 Flow 以保持成员列表同步。
     */
    fun getMembers(): Flow<List<FamilyMember>> =
        dao.getAllMembers().map { entities -> entities.map { it.toDomain() } }

    /**
     * 保存或覆盖更新一条测量记录，并触发成员匹配逻辑。
     *
     * 优先级（成员绑定）：
     * 1. 传入的 [targetMember]（用户手动选择）
     * 2. 记录中已有的 memberId 所对应的成员
     * 3. 通过 [findBestMember] 智能匹配最近的成员
     * 4. 以上均失败：不绑定成员，只保存体重数据
     *
     * 若成功绑定成员，会用该成员的真实参数（性别、身高、年龄）重新计算体脂率等 BIA 指标，
     * 并将本次体重更新到成员的 [FamilyMember.referenceWeightKg]，供下次匹配使用。
     *
     * @param measurement 原始测量数据（体重、阻抗等）
     * @param targetMember 用户手动指定的目标成员，null 表示自动匹配
     * @param existingId 已有的会话 ID，传入时用于覆盖同一次称量的旧记录（防止重复条目）
     * @return Pair<最终保存的测量数据, 实际绑定的成员（null 表示未绑定）>
     */
    suspend fun saveMeasurement(
        measurement: BodyMeasurement,
        targetMember: FamilyMember? = null,
        existingId: String? = null
    ): Pair<BodyMeasurement, FamilyMember?> {
        val members = dao.getMembersList().map { it.toDomain() }

        // 按优先级确定实际绑定的成员
        val memberToBind = targetMember
            ?: members.firstOrNull { it.id == measurement.memberId }
            ?: findBestMember(measurement.weightKg, members)

        // 若有匹配成员，用其真实参数重新计算 BIA 指标；否则保留原始数据
        val recalculated = if (memberToBind != null) {
            com.example.dianzicheng.domain.BodyAlgorithm.calculate(
                weightKg = measurement.weightKg,
                impedanceOhm = measurement.impedanceOhm,
                sex = memberToBind.sex,
                heightCm = memberToBind.heightCm,
                birthDateEpochMs = memberToBind.birthDateEpochMs
            ).copy(
                // 使用传入的 existingId 保证同次称量幂等写入，否则生成新 UUID
                id = existingId ?: measurement.id.ifEmpty { UUID.randomUUID().toString() },
                // 保留原始称量时间戳，不被 BodyAlgorithm 内的 System.currentTimeMillis() 覆盖
                measuredAtEpochMs = measurement.measuredAtEpochMs,
                memberId = memberToBind.id,
                memberNameSnapshot = memberToBind.name
            )
        } else {
            // 未匹配到成员时：优先使用系统已有成员身高或默认 170cm 兜底计算有效 BMI，杜绝 BMI 为 0.0
            val fallbackHeightCm = members.firstOrNull()?.heightCm ?: 170.0
            val heightM = (fallbackHeightCm.coerceIn(50.0, 250.0)) / 100.0
            val fallbackBmi = if (measurement.bmi > 0.0) measurement.bmi
                else if (measurement.weightKg > 0.0) (measurement.weightKg / (heightM * heightM)).coerceIn(1.0, 100.0)
                else 0.0
            measurement.copy(
                id = existingId ?: measurement.id.ifEmpty { UUID.randomUUID().toString() },
                bmi = fallbackBmi
            )
        }

        // 写入数据库（REPLACE 策略：同 ID 覆盖旧记录）
        dao.insertMeasurement(recalculated.toEntity())

        // 用本次体重更新成员参考体重，供下次智能匹配参考
        memberToBind?.let { member ->
            dao.insertMember(member.copy(referenceWeightKg = measurement.weightKg).toEntity())
        }

        return Pair(recalculated, memberToBind)
    }

    /**
     * 将已有测量记录重新绑定到指定成员，并以该成员参数重新计算 BIA 指标后写库。
     *
     * 常用于：用户在称重结果卡片中手动选择"重选/绑定成员"时调用。
     *
     * @param measurement 要重新绑定的原始测量记录
     * @param member 用户选择的目标成员
     * @return 重新计算后的测量记录
     */
    suspend fun bindMeasurementToMember(measurement: BodyMeasurement, member: FamilyMember): BodyMeasurement {
        val recalculated = com.example.dianzicheng.domain.BodyAlgorithm.calculate(
            weightKg = measurement.weightKg,
            impedanceOhm = measurement.impedanceOhm,
            sex = member.sex,
            heightCm = member.heightCm,
            birthDateEpochMs = member.birthDateEpochMs
        ).copy(
            id = measurement.id,
            measuredAtEpochMs = measurement.measuredAtEpochMs,
            memberId = member.id,
            memberNameSnapshot = member.name
        )
        dao.insertMeasurement(recalculated.toEntity())
        // 同步更新成员参考体重
        dao.insertMember(member.copy(referenceWeightKg = measurement.weightKg).toEntity())
        return recalculated
    }

    /**
     * 根据当前体重，从数据库中查找最匹配的家庭成员。
     * 是 [findBestMember] 的挂起函数包装，供外部（如 ScaleViewModel）调用。
     */
    suspend fun getBestMember(weightKg: Double): FamilyMember? {
        val members = dao.getMembersList().map { it.toDomain() }
        return findBestMember(weightKg, members)
    }

    /**
     * 成员智能匹配算法，从成员列表中找到体重最接近的成员。
     *
     * 匹配策略（按优先级）：
     * 1. 只有 1 个成员时，直接返回该成员（单用户家庭无歧义）
     * 2. 多成员中若全为新成员（referenceWeightKg <= 0.0），优先绑定第一个未测成员
     * 3. 在已测过体重的成员中，寻找体重最接近的成员（容差放宽至 10.0kg，适应日常体重正常浮动与衣服波动）
     * 4. 若与已测成员差值较大，但存在未测过体重的新增成员，优先归属于未测成员
     * 5. 兜底返回最接近的已有成员，避免数据彻底孤立
     *
     * @param weightKg 本次称量体重（公斤）
     * @param members 候选成员列表
     * @return 最匹配的成员，无法匹配时为 null
     */
    private fun findBestMember(weightKg: Double, members: List<FamilyMember>): FamilyMember? {
        if (members.isEmpty()) return null

        // 1. 单成员：直接自动绑定
        if (members.size == 1) {
            return members.first()
        }

        // 2. 多成员中若全为新成员（referenceWeightKg <= 0.0），优先绑定第一个未测成员
        val measuredMembers = members.filter { it.referenceWeightKg > 0.0 }
        if (measuredMembers.isEmpty()) {
            return members.first()
        }

        // 3. 在已测过体重的成员中，寻找体重最接近的成员（容差放宽至 10.0kg，适应日常体重正常浮动与衣服波动）
        val best = measuredMembers.minByOrNull { abs(it.referenceWeightKg - weightKg) }
        if (best != null && abs(best.referenceWeightKg - weightKg) <= 10.0) {
            return best
        }

        // 4. 若与已测成员差值较大，但存在未测过体重的新增成员，优先归属于未测成员
        val unmeasured = members.firstOrNull { it.referenceWeightKg <= 0.0 }
        if (unmeasured != null) {
            return unmeasured
        }

        // 5. 兜底返回最接近的已有成员，避免数据彻底孤立
        return best ?: members.first()
    }

    /** 删除一条测量记录 */
    suspend fun deleteMeasurement(measurement: BodyMeasurement) {
        dao.deleteMeasurement(measurement.toEntity())
    }

    /** 按 ID 删除测量记录，用于作废无效会话 */
    suspend fun deleteMeasurementById(id: String) {
        dao.deleteMeasurementById(id)
    }
}
