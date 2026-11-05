package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 趋势与历史统计支持的时间周期范围
 */
enum class HistoryTimeRange(val label: String, val days: Int) {
    ONE_WEEK("近一周", 7),
    ONE_MONTH("近一月", 30),
    NINETY_DAYS("近90天", 90),
    HALF_YEAR("近半年", 180);

    fun filter(measurements: List<BodyMeasurement>): List<BodyMeasurement> {
        val now = System.currentTimeMillis()
        val cutoff = now - days * 86400000L
        return measurements.filter { it.measuredAtEpochMs >= cutoff }
    }
}

/**
 * 趋势与历史统计支持的重要指标类型
 */
enum class HistoryMetric(val label: String, val unit: String) {
    WEIGHT("体重", "kg"),
    BMI("BMI", ""),
    BODY_FAT("体脂率", "%");

    fun getValue(measurement: BodyMeasurement): Double? {
        return when (this) {
            WEIGHT -> measurement.weightKg
            BMI -> if (measurement.bmi > 0.0) measurement.bmi else null
            BODY_FAT -> if (measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0) measurement.bodyFatPct else null
        }
    }

    fun format(value: Double): String {
        return when (this) {
            WEIGHT -> String.format(Locale.getDefault(), "%.2f kg", value)
            BMI -> String.format(Locale.getDefault(), "%.1f", value)
            BODY_FAT -> String.format(Locale.getDefault(), "%.1f%%", value)
        }
    }

    fun formatNumberOnly(value: Double): String {
        return when (this) {
            WEIGHT -> String.format(Locale.getDefault(), "%.2f", value)
            BMI -> String.format(Locale.getDefault(), "%.1f", value)
            BODY_FAT -> String.format(Locale.getDefault(), "%.1f", value)
        }
    }
}

/**
 * 测量历史与趋势页面的 ViewModel。
 *
 * 职责：
 * 1. 提供测量历史记录列表（按时间倒序），历史页 UI 直接订阅
 * 2. 提供家庭成员列表，用于历史记录与趋势中的成员筛选与切换
 * 3. 管理当前选中的成员、时间跨度（近一周/近一月/近90天/近半年）与展示指标（体重/BMI/体脂率）
 * 4. 提供删除单条测量记录的操作入口（本地与 Health Connect 同步删除）
 */
class HistoryViewModel(
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager? = null,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    /**
     * 所有测量历史记录列表（Flow，按时间倒序，数据库变更时自动推送）。
     * 使用 [SharingStarted.Eagerly] 确保页面切换回来时数据已就绪，无白屏等待。
     */
    val history: StateFlow<List<BodyMeasurement>> = repository.getHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 所有家庭成员列表（Flow），用于历史记录与趋势页的成员切换。
     */
    val members: StateFlow<List<FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前选中的家庭成员 ID，null 表示未过滤（或全部成员） */
    private val _selectedMemberId = MutableStateFlow<String?>(null)
    val selectedMemberId: StateFlow<String?> = _selectedMemberId.asStateFlow()

    /** 当前选中的时间范围，默认近半年（匹配设计图） */
    private val _selectedTimeRange = MutableStateFlow(HistoryTimeRange.HALF_YEAR)
    val selectedTimeRange: StateFlow<HistoryTimeRange> = _selectedTimeRange.asStateFlow()

    /** 当前选中的指标类型，默认体重 */
    private val _selectedMetric = MutableStateFlow(HistoryMetric.WEIGHT)
    val selectedMetric: StateFlow<HistoryMetric> = _selectedMetric.asStateFlow()

    private var hasAutoSelectedMember = false

    init {
        // 首次加载成员列表时，如果尚未选择成员且成员列表不为空，自动选中第一位成员
        viewModelScope.launch {
            members.collect { memberList ->
                if (!hasAutoSelectedMember && memberList.isNotEmpty() && _selectedMemberId.value == null) {
                    _selectedMemberId.value = memberList.first().id
                    hasAutoSelectedMember = true
                }
            }
        }
    }

    fun selectMember(memberId: String?) {
        hasAutoSelectedMember = true
        _selectedMemberId.value = memberId
    }

    fun selectTimeRange(range: HistoryTimeRange) {
        _selectedTimeRange.value = range
    }

    fun selectMetric(metric: HistoryMetric) {
        _selectedMetric.value = metric
    }

    /**
     * 生成符合设计图走势的体验演示数据（3条覆盖近90天/近半年的高质感数据），方便直观预览走势图与极值变化。
     */
    fun insertDemoData() {
        viewModelScope.launch {
            val member = members.value.firstOrNull()
            val now = System.currentTimeMillis()
            val dayMs = 86400000L

            val demoMeasurements = listOf(
                BodyMeasurement(
                    id = java.util.UUID.randomUUID().toString(),
                    measuredAtEpochMs = now - 94 * dayMs,
                    weightKg = 69.80,
                    impedanceOhm = 500.0,
                    bmi = 22.8,
                    bodyFatPct = 19.5,
                    muscleKg = 53.0,
                    waterPct = 58.0,
                    proteinPct = 16.0,
                    boneMassKg = 3.2,
                    memberId = member?.id,
                    memberNameSnapshot = member?.name ?: "自己"
                ),
                BodyMeasurement(
                    id = java.util.UUID.randomUUID().toString(),
                    measuredAtEpochMs = now - 84 * dayMs,
                    weightKg = 69.95,
                    impedanceOhm = 505.0,
                    bmi = 22.9,
                    bodyFatPct = 19.6,
                    muscleKg = 53.1,
                    waterPct = 58.0,
                    proteinPct = 16.0,
                    boneMassKg = 3.2,
                    memberId = member?.id,
                    memberNameSnapshot = member?.name ?: "自己"
                ),
                BodyMeasurement(
                    id = java.util.UUID.randomUUID().toString(),
                    measuredAtEpochMs = now - 10 * dayMs,
                    weightKg = 68.95,
                    impedanceOhm = 490.0,
                    bmi = 22.5,
                    bodyFatPct = 18.5,
                    muscleKg = 53.3,
                    waterPct = 59.2,
                    proteinPct = 16.3,
                    boneMassKg = 3.2,
                    memberId = member?.id,
                    memberNameSnapshot = member?.name ?: "自己"
                )
            )

            demoMeasurements.forEach { repository.saveMeasurement(it, member) }
        }
    }

    /**
     * 将已有历史测量记录重新绑定到指定成员，并按该成员资料重新计算全部 BIA 指标。
     * 支持误测后重新归属，无需删除记录。
     *
     * @param measurement 要重新绑定的原始测量记录
     * @param targetMember 用户选择的目标归属成员
     */
    fun rebindMeasurement(measurement: BodyMeasurement, targetMember: FamilyMember) {
        viewModelScope.launch {
            val updated = repository.bindMeasurementToMember(measurement, targetMember)

            val prefs = preferenceManager ?: return@launch
            val hc = healthConnectManager ?: return@launch
            try {
                val enabled = prefs.healthConnectEnabled.first()
                if (enabled && hc.hasWritePermissions()) {
                    hc.writeMeasurement(updated)
                }
            } catch (e: Exception) {
                android.util.Log.e("HistoryViewModel", "Health Connect rebind sync failed", e)
            }
        }
    }

    /**
     * 删除单条测量记录。
     * 先删除本地数据库记录（[history] Flow 自动推送更新，UI 无需手动刷新），
     * 若已开启 Health Connect 同步，再删除远端对应记录，保持两端数据一致。
     *
     * @param measurement 要删除的测量记录
     */
    fun deleteMeasurement(measurement: BodyMeasurement) {
        viewModelScope.launch {
            repository.deleteMeasurement(measurement)

            val prefs = preferenceManager ?: return@launch
            val hc = healthConnectManager ?: return@launch
            try {
                val enabled = prefs.healthConnectEnabled.first()
                if (enabled && hc.hasWritePermissions()) {
                    hc.deleteMeasurement(measurement)
                }
            } catch (e: Exception) {
                android.util.Log.e("HistoryViewModel", "Health Connect delete sync failed", e)
            }
        }
    }
}
