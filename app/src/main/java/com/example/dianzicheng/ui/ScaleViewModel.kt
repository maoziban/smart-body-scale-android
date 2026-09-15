package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.PreferenceManager
import kotlinx.coroutines.flow.first

/** 日志标签，用于在 AppLogger 和 Logcat 中标识来源 */
private const val TAG = "ScaleVM"

/**
 * 称重主界面的 ViewModel，负责：
 * 1. 订阅 BleScaleClient 的体重、稳定状态、阻抗三路 Flow
 * 2. 在合适时机（稳定锁定 / 阻抗到达）触发测量数据保存
 * 3. 维护当前称量的会话状态，防止同一次称量生成重复记录
 * 4. 支持手动选择/绑定家庭成员，重新计算体脂率并更新数据库
 */
class ScaleViewModel(
    private val bleClient: BleScaleClient,
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager? = null,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    /** 对外暴露的 UI 状态，使用 StateFlow 保证只读性 */
    private val _uiState = MutableStateFlow(ScaleUiState())
    val uiState: StateFlow<ScaleUiState> = _uiState.asStateFlow()

    /**
     * 当前称量会话 ID（UUID 字符串）。
     * 在 isStable=true 时生成，在下秤（weight=0 或 isStable=false 后延迟300ms）后清空。
     * 同一会话 ID 用于对数据库进行幂等写入（REPLACE），避免重复记录。
     */
    private var activeSessionId: String? = null

    /**
     * 当前称量会话开始的时间戳（毫秒）。
     * 在 isStable=true 时固定记录，供整个会话期间复用，
     * 确保阻抗补充写入时不会改变测量时间。
     */
    private var activeSessionTimestamp: Long = 0L

    /** 获取有效的会话时间戳，若为 0L 则兜底初始化为当前系统时间 */
    private fun getSessionTimestamp(): Long {
        if (activeSessionTimestamp <= 0L) {
            activeSessionTimestamp = System.currentTimeMillis()
        }
        return activeSessionTimestamp
    }

    /** 当前会话内是否已弹出过"发现新成员"提示，防止重复弹窗打扰用户 */
    private var hasAlertedForCurrentSession = false

    /**
     * 上一次完成锁定的会话 ID。
     * 当用户离秤（activeSessionId 被清空）后，阻抗数据仍可能延迟到达，
     * 此时通过 lastLockedSessionId 找到对应记录进行补充更新。
     */
    private var lastLockedSessionId: String? = null

    /**
     * 当前锁定的体重值（公斤）。
     * 在 isStable=true 时写入，在 weight=0 时不重置（保留至下一次会话开始），
     * 供阻抗延迟到达时使用正确的体重参与计算。
     */
    private var currentLockedWeightKg: Double = 0.0

    /**
     * 会话是否因下秤（体重归零）而中断。
     * 中断后再次稳定一般是全新一次称重；仅在归零后极短时间内重新锁定才允许续接。
     */
    private var sessionInterrupted = false

    /**
     * 当前会话是否已写入数据库。
     * 阻抗到达（真人赤脚测量的确定信号）或下秤时校验通过才会写库；
     * 短暂踩秤/放物品等无效会话不写库，避免历史被无关数据污染。
     */
    private var sessionCommitted = false

    /** 本次上秤时刻（体重首次 ≥3.0kg），用于下秤时计算在秤时长 */
    private var stepOnTimeMs = 0L

    /** 最近一次体重归零的时刻，用于判断再次锁定是否为短时续接 */
    private var lastWeightZeroMs = 0L

    /** 体重不稳定时的延迟清空会话 Job，重新稳定时取消该任务以防误清空 */
    private var clearSessionJob: Job? = null

    companion object {
        /** 归零后该时间窗口内重新锁定且体重接近，仍视为同一次称重（覆盖瞬时抖动） */
        private const val CONTINUATION_AFTER_ZERO_GRACE_MS = 2500L

        /** 同一会话内再次锁定的体重允许波动范围（公斤） */
        private const val WEIGHT_CONTINUATION_TOLERANCE_KG = 2.0

        /** 实时体重超出锁定值超过该幅度时，判定该锁定为爬升过程中的过早误报，作废会话 */
        private const val SUPERSEDE_THRESHOLD_KG = 3.0
    }

    init {
        observeBle()      // 开始监听 BLE 数据流
        observeMembers()  // 开始监听家庭成员列表
    }

    /**
     * 监听家庭成员列表变化。
     *
     * 注意：只在用户已明确选择成员时，才同步更新该成员的最新信息（如修改了身高）；
     * 若未选择任何成员（selectedMember == null），保持 null 不自动绑定，
     * 以免干扰后续的智能自动匹配逻辑。
     */
    private fun observeMembers() {
        viewModelScope.launch {
            repository.getMembers().collect { members ->
                _uiState.update { state ->
                    val currentSelected = state.selectedMember
                    val updatedSelected = if (currentSelected != null) {
                        // 同步已选成员的最新信息（如身高、出生日期变更）
                        members.firstOrNull { it.id == currentSelected.id } ?: currentSelected
                    } else {
                        null // 保持 null，不自动绑定第一个成员
                    }
                    state.copy(
                        availableMembers = members,
                        selectedMember = updatedSelected
                    )
                }
            }
        }
    }

    /**
     * 用户手动选择成员（或切换为智能匹配模式）。
     *
     * 若当前已有测量结果且选择了具体成员，立即用新成员参数重新计算并保存。
     *
     * @param member 选择的成员；传 null 表示切换为智能自动匹配
     */
    fun selectMember(member: FamilyMember?) {
        _uiState.update { it.copy(selectedMember = member) }
        val currentMeas = _uiState.value.currentMeasurement
        if (currentMeas != null && member != null) {
            bindCurrentMeasurementToMember(member)
        }
    }

    /**
     * 将当前测量结果重新绑定到指定成员，重新计算 BIA 指标并更新数据库。
     * 常用于用户点击"重选/绑定成员"后的回调。
     *
     * @param member 要绑定的目标成员
     */
    fun bindCurrentMeasurementToMember(member: FamilyMember) {
        val currentMeas = _uiState.value.currentMeasurement ?: return
        viewModelScope.launch {
            try {
                val boundResult = repository.bindMeasurementToMember(currentMeas, member)
                // 手动绑定即用户明确要保留这条记录，视为已写库
                sessionCommitted = true
                _uiState.update {
                    it.copy(
                        currentMeasurement = boundResult,
                        selectedMember = member
                    )
                }
                scheduleHealthSync(boundResult)
            } catch (e: Exception) {
                android.util.Log.e("ScaleViewModel", "Error binding measurement to member", e)
            }
        }
    }

    /**
     * 启动所有 BLE 数据 Flow 的监听。
     *
     * 四路 Flow 各自在独立协程中运行：
     * - connectionState：连接状态变更
     * - weight：实时体重数值
     * - isStable：体重是否稳定锁定
     * - impedance：体内阻抗（BIA 测量值）
     * - discoveredDevice：扫描发现的设备名称和 MAC
     */
    private fun observeBle() {
        // ── 连接状态 ──────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.connectionState.collect { state ->
                AppLogger.i(TAG, "BLE 连接状态变更: $state")
                _uiState.update { it.copy(connection = state) }
            }
        }

        // ── 实时体重 ──────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.weight.collect { weight ->
                _uiState.update { it.copy(liveWeightKg = weight) }
                if (weight >= 3.0) {
                    // 记录上秤时刻（仅在未记录时），供下秤时计算在秤时长
                    if (stepOnTimeMs == 0L) stepOnTimeMs = System.currentTimeMillis()

                    // 过早锁定作废：秤可能在体重爬升过程中误报"稳定"（如爬到 18kg 时），
                    // 若实际体重随后显著超出锁定值，说明那次锁定是中间值，作废对应会话，
                    // 避免产生一条远低于真实体重的垃圾记录
                    val supersededId = lastLockedSessionId
                    if (supersededId != null && weight > currentLockedWeightKg + SUPERSEDE_THRESHOLD_KG) {
                        AppLogger.i(TAG, "检测到过早锁定 (锁定 ${String.format("%.2f", currentLockedWeightKg)}kg < 实际 ${String.format("%.2f", weight)}kg)，作废会话 ${supersededId.take(8)}")
                        if (sessionCommitted) {
                            // 垃圾记录可能已被阻抗抢先写入数据库：撤销它
                            viewModelScope.launch {
                                try {
                                    repository.deleteMeasurementById(supersededId)
                                } catch (e: Exception) {
                                    AppLogger.e(TAG, "撤销过早锁定记录失败: ${e.message}")
                                }
                            }
                        }
                        healthSyncJob?.cancel()
                        activeSessionId = null
                        lastLockedSessionId = null
                        sessionCommitted = false
                        _uiState.update { it.copy(currentMeasurement = null) }
                    }
                } else if (weight <= 0.0) {
                    // 体重归零，说明用户已离秤：若当前会话尚未写库则兜底提交
                    val sessionId = activeSessionId ?: lastLockedSessionId
                    if (sessionId != null && !sessionCommitted) {
                        AppLogger.i(TAG, "下秤，兜底提交本次测量: sessionId=${sessionId.take(8)}")
                        sessionCommitted = true
                        processMeasurement(sessionId, getSessionTimestamp(), commitToDb = true)
                    }
                    activeSessionId = null
                    hasAlertedForCurrentSession = false
                    sessionInterrupted = true
                    stepOnTimeMs = 0L
                    lastWeightZeroMs = System.currentTimeMillis()
                    // 阻抗可能延迟到达：保留 lastLockedSessionId 1 秒补写窗口，
                    // 之后清除，防止上一人的阻抗被误写入下一人的会话
                    val sessionIdToClear = lastLockedSessionId
                    if (sessionIdToClear != null) {
                        viewModelScope.launch {
                            delay(1000)
                            if (lastLockedSessionId == sessionIdToClear) {
                                lastLockedSessionId = null
                            }
                        }
                    }
                }
            }
        }

        // ── 稳定状态 ──────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.isStable.collect { stable ->
                _uiState.update { it.copy(isStable = stable) }
                if (stable) {
                    clearSessionJob?.cancel()
                    val w = bleClient.weight.value
                    if (w >= 3.0) {
                        // 归零后极短时间内重新锁定且体重接近：仍属同一次称重
                        // （覆盖秤的瞬时抖动、重心移动导致的短暂归零）
                        val interruptionWithinGrace = sessionInterrupted
                            && lastWeightZeroMs > 0
                            && System.currentTimeMillis() - lastWeightZeroMs <= CONTINUATION_AFTER_ZERO_GRACE_MS
                        val isContinuation = lastLockedSessionId != null
                            && (!sessionInterrupted || interruptionWithinGrace)
                            && abs(w - currentLockedWeightKg) <= WEIGHT_CONTINUATION_TOLERANCE_KG
                        if (isContinuation) {
                            val sessionId = lastLockedSessionId!!
                            activeSessionId = sessionId
                            currentLockedWeightKg = w
                            if (interruptionWithinGrace) sessionInterrupted = false
                            AppLogger.i(TAG, "体重再次稳定(同一会话): ${String.format("%.2f", w)} kg, sessionId=${sessionId.take(8)}")
                            // 同一会话体重微调，同步更新持久化记录
                            processMeasurement(
                                sessionId = sessionId,
                                timestamp = getSessionTimestamp(),
                                commitToDb = true
                            )
                        } else {
                            currentLockedWeightKg = w
                            AppLogger.i(TAG, "体重锁定稳定: ${String.format("%.2f", w)} kg")
                            val newSessionId = java.util.UUID.randomUUID().toString()
                            activeSessionId = newSessionId
                            lastLockedSessionId = newSessionId
                            activeSessionTimestamp = System.currentTimeMillis()
                            sessionInterrupted = false
                            sessionCommitted = false
                            // 锁定时立即写入数据库，确保穿袜、普通秤、老人小孩等无阻抗场景也能及时安全持久化
                            processMeasurement(
                                sessionId = newSessionId,
                                timestamp = activeSessionTimestamp,
                                commitToDb = true
                            )
                        }
                    }
                } else {
                    // 体重不稳定：延迟 300ms 后清空 activeSessionId，
                    // 给可能延迟到达的阻抗数据留出补充写入的窗口期。
                    // 使用受管协程句柄，避免新稳定事件被先前的延迟任务误销毁。
                    clearSessionJob?.cancel()
                    clearSessionJob = viewModelScope.launch {
                        delay(300)
                        if (!_uiState.value.isStable) {
                            activeSessionId = null
                            hasAlertedForCurrentSession = false
                        }
                    }
                }
            }
        }

        // ── 阻抗（BIA） ──────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.impedance.collect { imp ->
                _uiState.update { it.copy(impedanceOhm = imp) }
                // 阻抗到达说明是真人赤脚测量：复用已有 sessionId 和时间戳，
                // 确认会话有效并立即写库（阻抗可能晚于下秤到达，此处兜底提交）
                val sessionId = activeSessionId ?: lastLockedSessionId
                // 实时体重已远超锁定值时，该锁定是爬升中的过早误报，不可提交
                // （实时体重为 0 表示已下秤，属于延迟阻抗补写，放行）
                val liveWeight = bleClient.weight.value
                val lockStillValid = liveWeight <= 0.0 || liveWeight <= currentLockedWeightKg + SUPERSEDE_THRESHOLD_KG
                if (imp > 0.0 && sessionId != null && currentLockedWeightKg >= 3.0 && lockStillValid) {
                    AppLogger.i(TAG, "收到阻抗数据: ${imp.toInt()} Ω，更新记录 sessionId=${sessionId.take(8)}")
                    processMeasurement(
                        sessionId = sessionId,
                        timestamp = getSessionTimestamp(),
                        commitToDb = true
                    )
                }
            }
        }

        // ── 已发现设备 ────────────────────────────────────────────────────────
        viewModelScope.launch {
            bleClient.discoveredDevice.collect { pair ->
                _uiState.update {
                    it.copy(
                        discoveredDeviceName = pair?.first,
                        discoveredDeviceMac = pair?.second
                    )
                }
            }
        }
    }

    /** 直接连接指定 MAC 地址的设备（跳过扫描步骤，用于已知设备快速重连） */
    fun connectToMac(mac: String) {
        bleClient.connectMac(mac)
    }

    /**
     * 计算当前称量的测量结果，并按需写入数据库。
     *
     * 调用时机：
     * 1. [bleClient.isStable] 锁定（commitToDb=false，仅计算与展示）
     * 2. [bleClient.impedance] 收到有效阻抗（commitToDb=true，确认有效立即写库）
     * 3. 下秤时在秤时长校验通过（commitToDb=true，会话结束提交）
     *
     * 写入策略：
     * - 同一 sessionId 使用 REPLACE 策略，确保同次称量只保留一条最新记录
     * - 未通过有效性校验的会话（短暂踩秤等）不会触发数据库写入
     * - 无匹配成员时只保存体重，体脂等全部为 0，不使用虚假参数
     *
     * @param sessionId  本次称量的唯一会话 ID（UUID 字符串）
     * @param timestamp  本次称量开始时的时间戳（毫秒，固定值，不随多次保存改变）
     * @param commitToDb 是否写入数据库（及触发参考体重更新与 Health Connect 同步）
     */
    private fun processMeasurement(
        sessionId: String,
        timestamp: Long,
        commitToDb: Boolean
    ) {
        val weight = currentLockedWeightKg
        if (weight < 3.0) return // 体重过低，不处理（防止误触、放物品等情况）
        val impedance = bleClient.impedance.value

        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "处理测量: weight=${String.format("%.2f", weight)} kg, impedance=${impedance.toInt()} Ω, commit=$commitToDb, sessionId=${sessionId.take(8)}")

                // 严格使用用户手动选择的成员；若未选择则通过智能匹配查找
                val targetMember = _uiState.value.selectedMember
                    ?: repository.getBestMember(weight)

                val measurement = if (targetMember != null) {
                    // 有匹配成员：使用真实身体参数计算 BIA 指标
                    BodyAlgorithm.calculate(
                        weightKg = weight,
                        impedanceOhm = impedance,
                        sex = targetMember.sex,
                        heightCm = targetMember.heightCm,
                        birthDateEpochMs = targetMember.birthDateEpochMs
                    )
                } else {
                    // 没有匹配成员：只记录体重，体脂等全部为 0，不使用虚假参数误导用户
                    BodyMeasurement(
                        id = "",
                        measuredAtEpochMs = timestamp,
                        weightKg = weight,
                        impedanceOhm = impedance,
                        bmi = 0.0,
                        bodyFatPct = 0.0,
                        muscleKg = 0.0,
                        waterPct = 0.0,
                        proteinPct = 0.0,
                        boneMassKg = 0.0,
                        memberId = null,
                        memberNameSnapshot = null
                    )
                }

                // 强制使用本次会话的固定时间戳，覆盖 BodyAlgorithm 内部的 System.currentTimeMillis()，
                // 确保多次写入的时间戳完全一致
                val finalMeasurement = measurement.copy(
                    id = sessionId,
                    measuredAtEpochMs = timestamp
                )

                var displayMeasurement = finalMeasurement
                var matchedMember: FamilyMember? = targetMember

                if (commitToDb) {
                    AppLogger.d(TAG, "目标成员: ${targetMember?.name ?: "未匹配 (仅保存体重)"}")
                    try {
                        val r = repository.saveMeasurement(
                            finalMeasurement,
                            targetMember = targetMember,
                            existingId = sessionId
                        )
                        sessionCommitted = true
                        displayMeasurement = r.first
                        matchedMember = r.second
                        AppLogger.i(TAG, "保存成功: id=${sessionId.take(8)}, weight=${String.format("%.2f", weight)} kg, member=${r.second?.name ?: "无"}")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "保存失败: ${e.message}")
                        android.util.Log.e("ScaleViewModel", "Database save failed", e)
                    }
                }

                // 判断是否需要弹出"发现新成员"提示：
                // 匹配成员的参考体重与本次体重差距超过 7 kg，且用户未手动选择成员
                val diff = matchedMember?.let {
                    kotlin.math.abs(it.referenceWeightKg - weight)
                } ?: 100.0
                val userManuallySelected = _uiState.value.selectedMember != null
                val isOffScale = !bleClient.isStable.value
                val shouldShowAlert = isOffScale
                    && !hasAlertedForCurrentSession
                    && diff > 7.0
                    && !userManuallySelected
                if (shouldShowAlert) {
                    hasAlertedForCurrentSession = true // 本会话内只弹一次
                }

                // 仅已写库的记录才同步 Health Connect（防抖，同一次称重只写入一次）
                if (commitToDb) {
                    scheduleHealthSync(displayMeasurement)
                }

                // 更新 UI 状态：显示当前测量结果，并在需要时触发弹窗
                _uiState.update {
                    it.copy(
                        currentMeasurement = displayMeasurement,
                        showNewMemberAlert = if (shouldShowAlert) true else it.showNewMemberAlert
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("ScaleViewModel", "Error in processMeasurement", e)
            }
        }
    }

    /** 关闭"发现新成员"提示弹窗 */
    fun dismissAlert() {
        _uiState.update { it.copy(showNewMemberAlert = false) }
    }

    /** 待执行的 Health Connect 防抖同步任务 */
    private var healthSyncJob: Job? = null

    /**
     * 防抖写入 Health Connect。
     * 同一次称重的"稳定保存"与"阻抗补写"会先后触发本方法，
     * 取消前一个延迟任务，确保每次称重只向 Health Connect 写入一条最终记录。
     */
    private fun scheduleHealthSync(measurement: BodyMeasurement) {
        val prefs = preferenceManager ?: return
        val hc = healthConnectManager ?: return
        healthSyncJob?.cancel()
        healthSyncJob = viewModelScope.launch {
            delay(2000)
            try {
                val enabled = prefs.healthConnectEnabled.first()
                if (enabled && hc.hasAllPermissions()) {
                    hc.writeMeasurement(measurement)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Health Connect auto sync failed", e)
            }
        }
    }

    /**
     * 开始扫描 BLE 体脂秤设备。
     * 会先重置所有会话状态和 UI 数据，确保每次扫描都从干净状态开始。
     */
    fun startScanning() {
        AppLogger.i(TAG, "开始扫描 BLE 设备")
        // 重置会话状态
        activeSessionId = null
        lastLockedSessionId = null
        activeSessionTimestamp = 0L
        currentLockedWeightKg = 0.0
        sessionInterrupted = false
        sessionCommitted = false
        stepOnTimeMs = 0L
        lastWeightZeroMs = 0L
        // 重置 UI 显示
        _uiState.update {
            it.copy(
                currentMeasurement = null,
                liveWeightKg = 0.0,
                impedanceOhm = 0.0,
                isStable = false
            )
        }
        bleClient.startScan()
    }
}
