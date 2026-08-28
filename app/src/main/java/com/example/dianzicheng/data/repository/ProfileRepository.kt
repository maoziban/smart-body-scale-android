package com.example.dianzicheng.data.repository

import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.data.local.toDomain
import com.example.dianzicheng.data.local.toEntity
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 家庭成员数据仓库，封装 DAO 操作，提供领域层可用的接口。
 *
 * 职责单一：仅处理成员的增删查，不涉及测量记录逻辑。
 * 测量相关操作统一由 [com.example.dianzicheng.data.repository.ScaleRepository] 处理。
 */
class ProfileRepository(private val dao: ScaleDao) {

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
     * 删除指定家庭成员。
     * 注意：删除成员不会级联删除该成员的测量历史记录，历史记录中的 memberId 会保留。
     *
     * @param member 要删除的家庭成员领域对象
     */
    suspend fun deleteMember(member: FamilyMember) {
        dao.deleteMember(member.toEntity())
    }
}
