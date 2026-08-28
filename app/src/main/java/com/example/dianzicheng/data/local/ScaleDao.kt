package com.example.dianzicheng.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room 数据访问对象（DAO），提供对 members 和 measurements 两张表的增删查操作。
 *
 * 成员表（members）使用 REPLACE 冲突策略：更新成员参考体重时直接覆盖同 ID 记录。
 * 测量表（measurements）使用 REPLACE 冲突策略：同一次称量（相同 sessionId）多次写入时
 * 只保留最新数据（先写体重，阻抗到达后再覆盖），确保记录不重复。
 */
@Dao
interface ScaleDao {

    // ── 成员表操作 ──────────────────────────────────────────────────────────

    /** 获取所有家庭成员列表（Flow，数据库变更时自动推送最新数据） */
    @Query("SELECT * FROM members")
    fun getAllMembers(): Flow<List<MemberEntity>>

    /** 一次性获取所有家庭成员列表（挂起函数，适用于非 UI 场景） */
    @Query("SELECT * FROM members")
    suspend fun getMembersList(): List<MemberEntity>

    /**
     * 插入或更新家庭成员。
     * 当成员 ID 已存在时（REPLACE 策略），用新数据覆盖旧记录，
     * 常用于更新成员的 referenceWeightKg（参考体重）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: MemberEntity)

    /** 删除指定家庭成员 */
    @Delete
    suspend fun deleteMember(member: MemberEntity)

    // ── 测量记录表操作 ──────────────────────────────────────────────────────

    /**
     * 获取所有测量记录（Flow，按时间倒序排列，数据库变更时自动推送）。
     * 历史页订阅此 Flow 实现实时更新。
     */
    @Query("SELECT * FROM measurements ORDER BY measuredAtEpochMs DESC")
    fun getAllMeasurements(): Flow<List<MeasurementEntity>>

    /** 一次性获取所有测量记录列表（挂起函数，按时间倒序），用于批量导出/同步 */
    @Query("SELECT * FROM measurements ORDER BY measuredAtEpochMs DESC")
    suspend fun getAllMeasurementsList(): List<MeasurementEntity>

    /**
     * 获取指定成员的所有测量记录（Flow，按时间倒序），
     * 用于按成员过滤历史数据。
     */
    @Query("SELECT * FROM measurements WHERE memberId = :memberId ORDER BY measuredAtEpochMs DESC")
    fun getMeasurementsForMember(memberId: String): Flow<List<MeasurementEntity>>

    /**
     * 插入或覆盖更新测量记录。
     * REPLACE 冲突策略确保同一 sessionId（主键）的记录只保存最新版本：
     * - 第一次写入：仅有体重数据，体脂等为 0
     * - 阻抗数据到达后第二次写入：完整数据覆盖前一条
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurement(measurement: MeasurementEntity)

    /** 删除指定测量记录 */
    @Delete
    suspend fun deleteMeasurement(measurement: MeasurementEntity)

    /** 按 ID 删除测量记录，用于作废无效会话（如秤过早锁定产生的垃圾记录） */
    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun deleteMeasurementById(id: String)
}
