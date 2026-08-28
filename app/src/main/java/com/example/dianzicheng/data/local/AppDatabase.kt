package com.example.dianzicheng.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room 数据库主类，管理 members 和 measurements 两张表。
 *
 * - version = 1：数据库版本号，结构变更时需递增并提供迁移方案
 * - [Converters]：提供 [Sex] 枚举与字符串之间的类型转换，使枚举可直接存储在 SQLite 中
 *
 * 实例通过 Room.databaseBuilder() 在 Application 层创建，保证全局单例。
 */
@Database(entities = [MemberEntity::class, MeasurementEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /** 提供对 members 和 measurements 表的所有 DAO 操作 */
    abstract fun scaleDao(): ScaleDao
}
