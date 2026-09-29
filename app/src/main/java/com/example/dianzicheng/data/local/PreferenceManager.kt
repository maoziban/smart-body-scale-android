package com.example.dianzicheng.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

import androidx.datastore.preferences.core.stringPreferencesKey

// 通过 Kotlin 委托属性为 Context 扩展 DataStore 实例，文件名 "settings"
private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 应用偏好设置管理器，基于 Jetpack DataStore（Preferences）实现持久化存储。
 *
 * 存储以下配置项：
 * - 蓝牙设备配对状态与已配对 MAC 地址
 * - Health Connect 同步开关
 * - WebDAV 云备份的服务器地址、账号、密码
 * - 上次备份时间
 *
 * 所有读取操作返回 [Flow]，可响应式订阅；写入操作为挂起函数，需在协程中调用。
 */
class PreferenceManager(private val context: Context) {

    // ── DataStore Key 定义 ──────────────────────────────────────────────────

    /** 是否已完成设备配对流程 */
    private val PAIRING_COMPLETE = booleanPreferencesKey("pairing_complete")

    /** 已配对的蓝牙设备 MAC 地址（格式如 "AA:BB:CC:DD:EE:FF"） */
    private val PAIRED_MAC = stringPreferencesKey("paired_mac")

    /** 已配对的蓝牙设备名称（如 "小米体脂秤 2"） */
    private val PAIRED_DEVICE_NAME = stringPreferencesKey("paired_device_name")

    /** Health Connect 自动同步开关状态 */
    private val HEALTH_CONNECT_ENABLED = booleanPreferencesKey("health_connect_enabled")

    /** WebDAV 服务器地址（如 https://dav.jianguoyun.com/dav/） */
    private val WEBDAV_URL = stringPreferencesKey("webdav_url")

    /** WebDAV 登录账号 */
    private val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")

    /** WebDAV 登录密码或应用授权码 */
    private val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")

    /** 上次成功备份到 WebDAV 的时间（Unix 毫秒时间戳） */
    private val LAST_BACKUP_TIME =
        androidx.datastore.preferences.core.longPreferencesKey("last_backup_time")

    /** 用户手动选择的体脂秤型号 ID（如 "BOOHEE_YOLANDA", "XIAOMI_SCALE_2", 默认为 "AUTO"） */
    private val SELECTED_SCALE_MODEL = stringPreferencesKey("selected_scale_model")

    // ── 读取操作（Flow） ─────────────────────────────────────────────────────

    /** 是否已完成设备配对，默认 false */
    val isPairingComplete: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PAIRING_COMPLETE] ?: false }

    /** 用户手动选择的体脂秤型号 ID，默认为 "AUTO" */
    val selectedScaleModel: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[SELECTED_SCALE_MODEL] ?: "AUTO" }

    /** 已配对的蓝牙 MAC 地址，未配对时为 null */
    val pairedMac: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[PAIRED_MAC] }

    /** 已配对的蓝牙设备名称，未配对时为 null */
    val pairedDeviceName: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[PAIRED_DEVICE_NAME] }

    /** Health Connect 自动同步是否已开启，默认 false */
    val healthConnectEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HEALTH_CONNECT_ENABLED] ?: false }

    /** WebDAV 服务器地址，未配置时为空字符串 */
    val webdavUrl: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[WEBDAV_URL] ?: "" }

    /** WebDAV 账号，未配置时为空字符串 */
    val webdavUsername: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[WEBDAV_USERNAME] ?: "" }

    /** WebDAV 密码，未配置时为空字符串（存储明文，建议仅限内部使用） */
    val webdavPassword: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[WEBDAV_PASSWORD] ?: "" }

    /** 上次成功备份时间（Unix 毫秒），从未备份时为 0L */
    val lastBackupTime: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_BACKUP_TIME] ?: 0L }

    // ── 写入操作（挂起函数） ─────────────────────────────────────────────────

    /** 更新配对完成状态 */
    suspend fun setPairingComplete(complete: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PAIRING_COMPLETE] = complete
        }
    }

    /** 保存已配对的蓝牙设备 MAC 地址 */
    suspend fun savePairedMac(mac: String) {
        context.dataStore.edit { preferences ->
            preferences[PAIRED_MAC] = mac
        }
    }

    /** 保存已配对的蓝牙设备信息（MAC 地址与设备名称）并标记配对已完成 */
    suspend fun savePairedDevice(mac: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[PAIRED_MAC] = mac
            preferences[PAIRED_DEVICE_NAME] = name
            preferences[PAIRING_COMPLETE] = true
        }
    }

    /** 清除已配对设备信息并将配对状态重置为 false（重新配对时调用） */
    suspend fun clearPairedMac() {
        context.dataStore.edit { preferences ->
            preferences.remove(PAIRED_MAC)
            preferences.remove(PAIRED_DEVICE_NAME)
            preferences[PAIRING_COMPLETE] = false
        }
    }

    /** 保存用户手动选择的体脂秤型号 ID */
    suspend fun saveSelectedScaleModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_SCALE_MODEL] = modelId
        }
    }

    /** 更新 Health Connect 同步开关状态 */
    suspend fun setHealthConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HEALTH_CONNECT_ENABLED] = enabled
        }
    }

    /** 保存 WebDAV 服务器配置（地址、账号、密码） */
    suspend fun saveWebdavConfig(url: String, user: String, pass: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_URL] = url
            preferences[WEBDAV_USERNAME] = user
            preferences[WEBDAV_PASSWORD] = pass
        }
    }

    /** 更新上次成功备份的时间戳 */
    suspend fun saveLastBackupTime(timeMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_BACKUP_TIME] = timeMs
        }
    }
}
