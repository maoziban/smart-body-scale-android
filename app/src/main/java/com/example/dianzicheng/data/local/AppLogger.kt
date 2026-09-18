package com.example.dianzicheng.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque

/**
 * 应用内日志单例，提供轻量级的运行日志收集能力。
 *
 * 特性：
 * - 基于内存环形缓冲区，最多保留 [MAX_ENTRIES] 条记录，超出后自动丢弃最旧的
 * - 通过 [entries] StateFlow 实时推送最新日志列表，UI 可直接订阅
 * - 同时调用 Android 原生 `android.util.Log`，保证 ADB Logcat 可见
 * - 提供 [exportText] 将全部日志格式化为纯文本，供复制/分享
 * - 线程安全：使用 `@Synchronized` 保护缓冲区操作
 *
 * 使用示例：
 * ```kotlin
 * AppLogger.i("MyTag", "保存成功: id=abc123")
 * AppLogger.e("MyTag", "数据库写入失败: ${e.message}")
 * ```
 */
object AppLogger {

    /** 内存缓冲区最大保留条数，超出后滚动丢弃最旧记录 */
    private const val MAX_ENTRIES = 1000

    /**
     * 日志级别枚举，对应 Android Logcat 的标准级别。
     *
     * - [DEBUG]：调试信息，仅开发阶段关注
     * - [INFO]：常规运行信息（连接成功、保存成功等）
     * - [WARN]：警告信息（可恢复的异常情况）
     * - [ERROR]：错误信息（需要关注的失败事件）
     */
    enum class Level { DEBUG, INFO, WARN, ERROR }

    /**
     * 单条日志记录。
     *
     * @param timestamp 日志产生时间（Unix 毫秒时间戳）
     * @param level     日志级别
     * @param tag       来源标签（通常为类名或模块名缩写）
     * @param message   日志内容
     */
    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val tag: String,
        val message: String
    )

    /** 内部可变日志流（写端），日志 UI 监听此 Flow */
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** 对外只读日志流，供 UI 组件通过 collectAsState() 订阅 */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** 环形缓冲区，使用 ArrayDeque 支持高效头尾操作 */
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES + 1)

    /**
     * 追加一条日志记录到缓冲区，并同步写入 Logcat。
     * 线程安全，可从任意线程调用。
     *
     * @param entry 要写入的日志记录
     */
    @Synchronized
    private fun append(entry: LogEntry) {
        buffer.addLast(entry)
        // 超出上限时移除最旧的一条，保持环形滚动
        if (buffer.size > MAX_ENTRIES) buffer.pollFirst()
        // 触发 UI 更新（StateFlow 只在值真正变化时推送）
        _entries.value = buffer.toList()

        // 同步写入 Android logcat，方便 ADB 调试
        when (entry.level) {
            Level.DEBUG -> android.util.Log.d(entry.tag, entry.message)
            Level.INFO  -> android.util.Log.i(entry.tag, entry.message)
            Level.WARN  -> android.util.Log.w(entry.tag, entry.message)
            Level.ERROR -> android.util.Log.e(entry.tag, entry.message)
        }
    }

    /** 写入 DEBUG 级别日志（调试信息，生产环境可忽略） */
    fun d(tag: String, msg: String) = append(LogEntry(level = Level.DEBUG, tag = tag, message = msg))

    /** 写入 INFO 级别日志（常规运行信息） */
    fun i(tag: String, msg: String) = append(LogEntry(level = Level.INFO,  tag = tag, message = msg))

    /** 写入 WARN 级别日志（警告，可恢复的异常） */
    fun w(tag: String, msg: String) = append(LogEntry(level = Level.WARN,  tag = tag, message = msg))

    /** 写入 ERROR 级别日志（错误，需要关注的失败事件） */
    fun e(tag: String, msg: String) = append(LogEntry(level = Level.ERROR, tag = tag, message = msg))

    /**
     * 清空所有日志记录（包括缓冲区和 StateFlow）。
     * 线程安全。
     */
    fun clear() {
        synchronized(this) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /**
     * 将缓冲区中的所有日志格式化为纯文本字符串，用于复制到剪贴板或分享给开发者。
     *
     * 格式：`[HH:mm:ss.SSS] LEVEL tag: message`
     *
     * @return 格式化后的日志文本，每条记录占一行
     */
    fun exportText(): String = synchronized(this) {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        buffer.joinToString("\n") { entry ->
            val levelStr = entry.level.name.padEnd(5)
            "[${fmt.format(Date(entry.timestamp))}] $levelStr ${entry.tag}: ${entry.message}"
        }
    }
}
