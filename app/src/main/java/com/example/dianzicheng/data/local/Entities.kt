package com.example.dianzicheng.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.dianzicheng.domain.Sex

/**
 * Room 数据库实体：家庭成员表（members）。
 *
 * @param id                成员唯一 ID（UUID 字符串），作为主键
 * @param name              成员姓名
 * @param sex               性别，通过 [Converters] 存储为字符串
 * @param heightCm          身高（厘米）
 * @param birthDateEpochMs  出生日期（Unix 毫秒时间戳）
 * @param referenceWeightKg 最近一次测量体重（公斤），用于多人智能匹配；首次为 0.0
 */
@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sex: Sex,
    val heightCm: Double,
    val birthDateEpochMs: Long,
    val referenceWeightKg: Double
)

/**
 * Room 数据库实体：体重测量记录表（measurements）。
 *
 * @param id                 测量记录唯一 ID（与 BLE 会话 sessionId 一致），作为主键；
 *                           同一次称重多次写入时以 REPLACE 策略覆盖，保证不重复
 * @param measuredAtEpochMs  称重时间（Unix 毫秒时间戳）
 * @param weightKg           体重（公斤）
 * @param impedanceOhm       体内阻抗（欧姆），0.0 表示本次未测出（需赤脚）
 * @param bmi                体质指数
 * @param bodyFatPct         体脂率（%），无阻抗时为 0.0
 * @param muscleKg           肌肉量（公斤），无阻抗时为 0.0
 * @param waterPct           体内水分率（%），无阻抗时为 0.0
 * @param proteinPct         蛋白质率（%），无阻抗时为 0.0
 * @param boneMassKg         骨量（公斤），无阻抗时为 0.0
 * @param memberId           关联的家庭成员 ID（外键），null 表示未绑定
 * @param memberNameSnapshot 保存时成员姓名快照，防止改名后历史混乱
 */
@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val measuredAtEpochMs: Long,
    val weightKg: Double,
    val impedanceOhm: Double,
    val bmi: Double,
    val bodyFatPct: Double,
    val muscleKg: Double,
    val waterPct: Double,
    val proteinPct: Double,
    val boneMassKg: Double,
    val memberId: String?,
    val memberNameSnapshot: String?
)
