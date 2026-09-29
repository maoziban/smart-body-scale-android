package com.example.dianzicheng.data.repository

import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.data.local.toDomain
import com.example.dianzicheng.data.local.toEntity
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 家庭成员数据仓库，封装 DAO 操作，提供领域层可用的接口。
 *
 * 职责单一：仅处理成员的增删查，不涉及测量记录逻辑。
 * 测量相关操作统一由 [com.example.dianzicheng.data.repository.ScaleRepository] 处理。
 */
class ProfileRepository(private val dao: ScaleDao) {

    /**
     * 检查并确保系统中至少存在一个默认家庭成员（"自己"）。
     * 解决新安装或未建成员时 BMI 与体成分指标无法计算归属的问题。
     */
    suspend fun ensureDefaultMemberExists() {
        val existing = dao.getMembersList()
        if (existing.isEmpty()) {
            val defaultMember = FamilyMember(
                id = UUID.randomUUID().toString(),
                name = "自己",
                sex = Sex.MALE,
                heightCm = 170.0,
                birthDateEpochMs = System.currentTimeMillis() - (1000L * 60 * 60 * 24 * 365 * 25), // 25岁
                referenceWeightKg = 0.0
            )
            dao.insertMember(defaultMember.toEntity())
            AppLogger.i("ProfileRepo", "已自动初始化默认家庭成员: 自己 (170cm, 男)")
        }
    }

    /**
     * 获取所有家庭成员列表（Flow，数据库变更时自动推送）。
     * 供 ProfileViewModel 和 ScaleViewModel 订阅，实时反映成员增删。
     */
    fun getMembers(): Flow<List<FamilyMember>> =
        dao.getAllMembers().map { entities -> entities.map { it.toDomain() } }

    /**
     * 保存或更新家庭成员（使用 REPLACE 冲突策略）。
     * 新增成员时插入；更新成员信息（如修改身高）时覆盖同 ID 旧记录。
     *
     * @param member 要保存的家庭成员领域对象
     */
    suspend fun saveMember(member: FamilyMember) {
        dao.insertMember(member.toEntity())
    }

    /**
     * 删除指定家庭成员，并级联删除该成员的所有历史测量记录。
     *
     * Bug #26 修复：原实现仅删除成员行，导致 measurements 表中该成员的记录
     * memberId 字段成为悬空孤立值（无对应成员），历史页显示归属混乱。
     * 现改为先删除成员测量记录，再删除成员本身，保持数据一致性。
     *
     * @param member 要删除的家庭成员领域对象
     */
    suspend fun deleteMember(member: FamilyMember) {
        dao.deleteMeasurementsByMemberId(member.id)
        dao.deleteMember(member.toEntity())
    }
}
