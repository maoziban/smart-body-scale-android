package com.example.dianzicheng.data.local

import androidx.room.TypeConverter
import com.example.dianzicheng.domain.Sex

/**
 * Room 类型转换器，处理 [Sex] 枚举与 SQLite TEXT 类型之间的互转。
 *
 * Room 不能直接存储枚举类型，需通过 @TypeConverter 注解将枚举转换为基础类型后存储。
 * 存储格式为枚举的 name 字符串（"MALE" 或 "FEMALE"），避免使用 ordinal 导致顺序变化时的兼容问题。
 */
class Converters {

    /** 将 [Sex] 枚举转换为字符串（如 "MALE"），写入数据库时调用 */
    @TypeConverter
    fun fromSex(sex: Sex): String {
        return sex.name
    }

    /** 将数据库中的字符串（如 "MALE"）还原为 [Sex] 枚举，读取时调用。
     *  如遇未知值（数据库损坏、枚举将来扩展后旧库升级等情况），
     *  降级返回 [Sex.MALE]，避免直接抛 [IllegalArgumentException] 崩溃 App。*/
    @TypeConverter
    fun toSex(value: String): Sex {
        return try {
            Sex.valueOf(value)
        } catch (e: IllegalArgumentException) {
            android.util.Log.w("Converters", "Unknown Sex value in DB: '$value', defaulting to MALE")
            Sex.MALE
        }
    }
}
