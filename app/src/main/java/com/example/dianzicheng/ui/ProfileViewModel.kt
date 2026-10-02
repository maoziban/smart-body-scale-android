package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.backup.WebDavManager
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ProfileRepository
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.Sex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * "我的"（设置/个人）页面的 ViewModel。
 *
 * 负责：
 * 1. 家庭成员的增删管理
 * 2. Health Connect 同步开关控制与批量历史数据同步
 * 3. WebDAV 云端备份的配置、测试连接、立即备份和数据恢复
 * 4. 应用运行日志的对外暴露（供 UI 实时显示）
 */
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val preferenceManager: PreferenceManager,
    private val webDavManager: WebDavManager? = null,
    private val healthConnectManager: HealthConnectManager? = null,
    private val scaleRepository: ScaleRepository? = null
) : ViewModel() {

    // ── 家庭成员 ──────────────────────────────────────────────────────────────

    /** 所有家庭成员列表（Flow，数据库变更时自动推送），初始值为空列表 */
    val members: StateFlow<List<FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── 设备配对设置 ─────────────────────────────────────────────────────────

    /** 当前已配对记住的设备 MAC 地址 */
    val pairedMac: StateFlow<String?> = preferenceManager.pairedMac
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** 当前已配对记住的设备名称 */
    val pairedDeviceName: StateFlow<String?> = preferenceManager.pairedDeviceName
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // ── 体脂秤型号设置 ────────────────────────────────────────────────────────

    /** 当前选中的体脂秤型号（来自 DataStore，默认自动识别） */
    val selectedScaleModel: StateFlow<ScaleModel> = preferenceManager.selectedScaleModel
        .map { ScaleModel.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, ScaleModel.AUTO)

    /**
     * 保存用户选择的体脂秤型号偏好
     */
    fun selectScaleModel(model: ScaleModel) {
        viewModelScope.launch {
            preferenceManager.saveSelectedScaleModel(model.id)
            AppLogger.i("ProfileVM", "已切换体脂秤型号偏好为: ${model.displayName} (${model.id})")
        }
    }

    // ── Health Connect 设置 ───────────────────────────────────────────────────

    /** Health Connect 自动同步开关状态（来自 DataStore） */
    val healthConnectEnabled: StateFlow<Boolean> = preferenceManager.healthConnectEnabled
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // ── WebDAV 配置 ───────────────────────────────────────────────────────────

    /** WebDAV 服务器地址（来自 DataStore，未配置时为空字符串） */
    val webdavUrl: StateFlow<String> = preferenceManager.webdavUrl
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    /** WebDAV 登录账号 */
    val webdavUsername: StateFlow<String> = preferenceManager.webdavUsername
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    /** WebDAV 登录密码（明文存储在 DataStore，仅限本地使用） */
    val webdavPassword: StateFlow<String> = preferenceManager.webdavPassword
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    /** 上次成功备份的时间戳（毫秒），0L 表示从未备份 */
    val lastBackupTime: StateFlow<Long> = preferenceManager.lastBackupTime
        .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    // ── 操作状态 ──────────────────────────────────────────────────────────────

    /**
     * 操作结果消息（一次性），用于在 UI 中弹出 Toast。
     * UI 消费后应调用 [clearStatusMessage] 置为 null，防止重复弹出。
     */
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /**
     * 是否正在执行耗时操作（WebDAV 测试/备份/恢复、Health Connect 同步等）。
     * UI 中相关按钮在此为 true 时显示 loading 状态并禁用点击。
     */
    private val _isOperating = MutableStateFlow(false)
    val isOperating: StateFlow<Boolean> = _isOperating.asStateFlow()

    /**
     * 批量同步进度描述文字（如 "正在同步 23 条记录..."）。
     * 同步完成或出错后置为 null，UI 据此显示/隐藏进度条。
     */
    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress: StateFlow<String?> = _syncProgress.asStateFlow()

    // ── 家庭成员管理 ─────────────────────────────────────────────────────────

    /**
     * 添加新家庭成员并保存到数据库。
     *
     * @param name            成员姓名
     * @param sex             性别
     * @param heightCm        身高（厘米）
     * @param birthDateEpochMs 出生日期（Unix 毫秒时间戳）
     * @param weightKg        初始参考体重（公斤），首次添加时通常为 0.0
     */
    fun addMember(name: String, sex: Sex, heightCm: Double, birthDateEpochMs: Long, weightKg: Double) {
        viewModelScope.launch {
            val member = FamilyMember(
                id = UUID.randomUUID().toString(), // 生成全局唯一 ID
                name = name,
                sex = sex,
                heightCm = heightCm,
                birthDateEpochMs = birthDateEpochMs,
                referenceWeightKg = weightKg
            )
            repository.saveMember(member)
        }
    }

    /** 从数据库删除指定家庭成员 */
    fun deleteMember(member: FamilyMember) {
        viewModelScope.launch { repository.deleteMember(member) }
    }

    fun upsertPrimaryMember(
        name: String,
        sex: Sex,
        heightCm: Double,
        birthDateEpochMs: Long
    ) {
        viewModelScope.launch {
            try {
                // 现查一次库，拿到当前成员列表（而不是复用 members.value，
                // 因为该 StateFlow 是 Lazily 启动的，本页未被打开时其值只是初始空列表）
                val existing = repository.getMembers().first()
                val untouchedDefault = existing.singleOrNull()
                    ?.takeIf { it.name == "自己" && it.referenceWeightKg <= 0.0 }

                val member = if (untouchedDefault != null) {
                    AppLogger.i("FirstRunProfile", "覆盖默认成员「自己」为: $name, ${heightCm}cm")
                    untouchedDefault.copy(
                        name = name,
                        sex = sex,
                        heightCm = heightCm,
                        birthDateEpochMs = birthDateEpochMs
                    )
                } else {
                    AppLogger.i("FirstRunProfile", "新增成员: $name, ${heightCm}cm")
                    FamilyMember(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        sex = sex,
                        heightCm = heightCm,
                        birthDateEpochMs = birthDateEpochMs,
                        referenceWeightKg = 0.0
                    )
                }
                repository.saveMember(member)
            } catch (e: Exception) {
                AppLogger.e("FirstRunProfile", "保存基础信息失败: ${e.message}")
            }
        }
    }

    /** 清除已配对的蓝牙设备 MAC 地址，触发重新配对流程 */
    fun resetPairing() {
        viewModelScope.launch { preferenceManager.clearPairedMac() }
    }

    // ── Health Connect ─────────────────────────────────────────────────────

    /** 更新 Health Connect 自动同步开关状态到 DataStore */
    fun setHealthConnectEnabled(enabled: Boolean) {
        viewModelScope.launch { preferenceManager.setHealthConnectEnabled(enabled) }
    }

    /** 检查当前设备是否支持并已安装 Health Connect */
    fun isHealthConnectAvailable(): Boolean = healthConnectManager?.isAvailable() == true

    /**
     * 将所有历史测量数据批量同步到 Health Connect。
     *
     * 前置条件：
     * 1. 设备支持 Health Connect
     * 2. 已获得 HealthPermission 读写权限
     * 3. 数据库中有历史记录
     *
     * 小米健康 App 通过 Health Connect 接口读取这些同步后的数据。
     */
    fun batchSyncToHealthConnect() {
        val hcManager = healthConnectManager ?: run {
            _statusMessage.value = "当前设备不支持 Health Connect"
            return
        }
        val scaleRepo = scaleRepository ?: run {
            _statusMessage.value = "无法访问数据库"
            return
        }
        viewModelScope.launch {
            if (!hcManager.isAvailable()) {
                _statusMessage.value = "当前设备不支持 Health Connect，请升级系统或安装 Health Connect 应用"
                return@launch
            }
            if (!hcManager.hasAllPermissions()) {
                _statusMessage.value = "请先在上方开启\"同步至系统健康\"并授予权限，再进行批量同步"
                return@launch
            }
            _isOperating.value = true
            _syncProgress.value = "正在读取历史记录..."
            try {
                val allMeasurements = scaleRepo.getAllMeasurements()
                if (allMeasurements.isEmpty()) {
                    _syncProgress.value = null
                    _isOperating.value = false
                    _statusMessage.value = "暂无历史测量数据可同步"
                    return@launch
                }
                _syncProgress.value = "正在同步 ${allMeasurements.size} 条记录..."
                val (success, failed) = hcManager.batchWriteMeasurements(allMeasurements)
                _syncProgress.value = null
                _isOperating.value = false
                _statusMessage.value = if (failed == 0) {
                    "✅ 成功同步 $success 条数据！小米健康将自动读取这些数据。"
                } else {
                    "同步完成：成功 $success 条，失败 $failed 条"
                }
            } catch (e: Exception) {
                _syncProgress.value = null
                _isOperating.value = false
                _statusMessage.value = "同步失败：${e.localizedMessage}"
            }
        }
    }

    /** 跳转到小米健康 App（已安装则直接打开，未安装则跳转应用市场） */
    fun openMiHealth() { healthConnectManager?.openMiHealth() }

    /** 跳转到系统 Health Connect 设置页面，用于管理权限 */
    fun openHealthConnectSettings() { healthConnectManager?.openHealthConnectSettings() }

    /** 检查小米健康 App 是否已安装 */
    fun isMiHealthInstalled(): Boolean = healthConnectManager?.isMiHealthInstalled() == true

    // ── WebDAV 备份 ───────────────────────────────────────────────────────────

    /** 保存 WebDAV 服务器配置（URL、账号、密码）到 DataStore */
    fun saveWebdavConfig(url: String, user: String, pass: String) {
        viewModelScope.launch { preferenceManager.saveWebdavConfig(url, user, pass) }
    }

    /**
     * 测试 WebDAV 连接是否可用（不保存配置，仅验证连通性）。
     * 测试期间 [isOperating] 为 true，结果通过 [statusMessage] 展示。
     */
    fun testWebdavConnection(url: String, user: String, pass: String) {
        val manager = webDavManager ?: return
        viewModelScope.launch {
            _isOperating.value = true
            val result = manager.testConnection(url, user, pass)
            _isOperating.value = false
            _statusMessage.value = result.getOrElse { it.localizedMessage }
        }
    }

    /**
     * 立即执行数据备份到 WebDAV 服务器。
     * 备份成功后更新 [lastBackupTime]，结果通过 [statusMessage] 展示。
     */
    fun backupData() {
        val manager = webDavManager ?: return
        viewModelScope.launch {
            _isOperating.value = true
            val url = webdavUrl.value
            val user = webdavUsername.value
            val pass = webdavPassword.value
            if (url.isBlank()) {
                _isOperating.value = false
                _statusMessage.value = "请先配置 WebDAV 服务器地址"
                return@launch
            }
            val result = manager.backupData(url, user, pass)
            _isOperating.value = false
            result.onSuccess { msg ->
                preferenceManager.saveLastBackupTime(System.currentTimeMillis())
                _statusMessage.value = msg
            }.onFailure { err ->
                _statusMessage.value = err.localizedMessage
            }
        }
    }

    /**
     * 从 WebDAV 服务器恢复数据。
     * 恢复过程中 [isOperating] 为 true，结果通过 [statusMessage] 展示。
     */
    fun restoreData() {
        val manager = webDavManager ?: return
        viewModelScope.launch {
            _isOperating.value = true
            val url = webdavUrl.value
            val user = webdavUsername.value
            val pass = webdavPassword.value
            if (url.isBlank()) {
                _isOperating.value = false
                _statusMessage.value = "请先配置 WebDAV 服务器地址"
                return@launch
            }
            val result = manager.restoreData(url, user, pass)
            _isOperating.value = false
            _statusMessage.value = result.getOrElse { it.localizedMessage }
        }
    }

    // ── 杂项 ──────────────────────────────────────────────────────────────────

    /** 清除状态消息（UI 消费 Toast 后调用，防止重复弹出） */
    fun clearStatusMessage() { _statusMessage.value = null }

    /** 清除同步进度文字（同步完成/出错后调用） */
    fun clearSyncProgress() { _syncProgress.value = null }

    // ── 日志 ──────────────────────────────────────────────────────────────────

    /**
     * 实时日志条目列表（来自 [AppLogger] 的 StateFlow）。
     * 日志查看器 UI 订阅此 Flow，每次有新日志写入时自动刷新。
     */
    val logEntries: StateFlow<List<AppLogger.LogEntry>> = AppLogger.entries
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * 清空所有日志记录（包括 UI 显示和内存缓冲区）。
     * 清空后会写入一条 INFO 日志标记清除操作。
     */
    fun clearLogs() {
        AppLogger.clear()
        AppLogger.i("ProfileVM", "日志已清除")
    }

    /**
     * 将所有日志导出为纯文本字符串，供外部分享（通过 Intent.ACTION_SEND）或复制。
     *
     * @return 格式为 "[HH:mm:ss.SSS] LEVEL tag: message" 的多行文本
     */
    fun exportLogsText(): String = AppLogger.exportText()
}
