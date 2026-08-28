package com.example.dianzicheng.data.local

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex

// ─────────────────────────────────────────────────────────────────────────────
// 成员实体与领域对象互转
// ─────────────────────────────────────────────────────────────────────────────

/** 将数据库实体 [MemberEntity] 转换为领域模型 [FamilyMember] */
fun MemberEntity.toDomain() = FamilyMember(
    id = id,
    name = name,
    sex = sex,
    heightCm = heightCm,
    birthDateEpochMs = birthDateEpochMs,
    referenceWeightKg = referenceWeightKg
)

/** 将领域模型 [FamilyMember] 转换为数据库实体 [MemberEntity]，用于持久化存储 */
fun FamilyMember.toEntity() = MemberEntity(
    id = id,
    name = name,
    sex = sex,
    heightCm = heightCm,
    birthDateEpochMs = birthDateEpochMs,
    referenceWeightKg = referenceWeightKg
)

// ─────────────────────────────────────────────────────────────────────────────
// 测量记录实体与领域对象互转
// ─────────────────────────────────────────────────────────────────────────────

/** 将数据库实体 [MeasurementEntity] 转换为领域模型 [BodyMeasurement] */
fun MeasurementEntity.toDomain() = BodyMeasurement(
    id = id,
    measuredAtEpochMs = measuredAtEpochMs,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPct = bodyFatPct,
    muscleKg = muscleKg,
    waterPct = waterPct,
    proteinPct = proteinPct,
    boneMassKg = boneMassKg,
    memberId = memberId,
    memberNameSnapshot = memberNameSnapshot
)

/** 将领域模型 [BodyMeasurement] 转换为数据库实体 [MeasurementEntity]，用于持久化存储 */
fun BodyMeasurement.toEntity() = MeasurementEntity(
    id = id,
    measuredAtEpochMs = measuredAtEpochMs,
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPct = bodyFatPct,
    muscleKg = muscleKg,
    waterPct = waterPct,
    proteinPct = proteinPct,
    boneMassKg = boneMassKg,
    memberId = memberId,
    memberNameSnapshot = memberNameSnapshot
)
