package com.example.dianzicheng.ui

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.tooling.preview.Preview
import com.example.dianzicheng.ui.theme.电子秤Theme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 趋势页面统计计算结果封装
 */
data class TrendStatistics(
    val points: List<Pair<BodyMeasurement, Double>>,
    val earliest: Pair<BodyMeasurement, Double>?,
    val latest: Pair<BodyMeasurement, Double>?,
    val minPoint: Pair<BodyMeasurement, Double>?,
    val maxPoint: Pair<BodyMeasurement, Double>?,
    val change: Double,
    val distinctDays: Int,
    val average: Double,
    val dateRangeText: String,
    val healthTip: String
)

/**
 * 计算选定数据集合与指标在当前周期下的趋势统计信息
 */
fun calculateTrendStatistics(
    measurements: List<BodyMeasurement>,
    metric: HistoryMetric,
    timeRange: HistoryTimeRange,
    memberName: String
): TrendStatistics {
    val inRange = timeRange.filter(measurements)
    val points = inRange
        .mapNotNull { m -> metric.getValue(m)?.let { v -> m to v } }
        .sortedBy { it.first.measuredAtEpochMs }

    if (points.isEmpty()) {
        return TrendStatistics(
            points = emptyList(),
            earliest = null,
            latest = null,
            minPoint = null,
            maxPoint = null,
            change = 0.0,
            distinctDays = 0,
            average = 0.0,
            dateRangeText = "--",
            healthTip = "当前时间段内暂无数据，请坚持上秤测量。"
        )
    }

    val earliest = points.first()
    val latest = points.last()
    val minPoint = points.minBy { it.second }
    val maxPoint = points.maxBy { it.second }
    val change = latest.second - earliest.second

    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val distinctDays = points.map { dayFormat.format(Date(it.first.measuredAtEpochMs)) }.distinct().size
    val average = points.map { it.second }.average()

    val mmDdFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
    val startStr = mmDdFormat.format(Date(earliest.first.measuredAtEpochMs))
    val endStr = mmDdFormat.format(Date(latest.first.measuredAtEpochMs))
    val dateRangeText = if (startStr == endStr) startStr else "$startStr-$endStr"

    val healthTip = when (metric) {
        HistoryMetric.WEIGHT -> {
            when {
                change < -0.3 -> "较期初减重 ${String.format(Locale.getDefault(), "%.2f", abs(change))} kg，减脂成效显著，继续保持健康饮食与规律运动！"
                change > 0.3 -> "较期初增加 ${String.format(Locale.getDefault(), "%.2f", change)} kg，建议关注热量摄入与日常代谢。"
                else -> "体重处于平稳波动区间（波动在 ±0.3 kg 内），代谢稳定，维持良好！"
            }
        }
        HistoryMetric.BMI -> {
            val bmiVal = latest.second
            when {
                bmiVal < 18.5 -> "当前 BMI ${String.format(Locale.getDefault(), "%.1f", bmiVal)}（偏瘦），建议适当增加优质蛋白与能量摄入。"
                bmiVal in 18.5..23.9 -> "当前 BMI ${String.format(Locale.getDefault(), "%.1f", bmiVal)}（标准），身体状态非常理想！"
                bmiVal in 24.0..27.9 -> "当前 BMI ${String.format(Locale.getDefault(), "%.1f", bmiVal)}（微胖/超重），建议结合有氧运动合理控制体重。"
                else -> "当前 BMI ${String.format(Locale.getDefault(), "%.1f", bmiVal)}（偏高），建议科学规划减脂计划并规律作息。"
            }
        }
        HistoryMetric.BODY_FAT -> {
            val fatVal = latest.second
            when {
                change < -0.5 -> "体脂率较期初下降 ${String.format(Locale.getDefault(), "%.1f", abs(change))}%，肌肉线条更加紧致，体态持续向好！"
                change > 0.5 -> "体脂率有所上升，建议适当提升力量训练与有氧代谢。"
                else -> "当前体脂率 ${String.format(Locale.getDefault(), "%.1f", fatVal)}%，体成分指标保持相对稳定。"
            }
        }
    }

    return TrendStatistics(
        points = points,
        earliest = earliest,
        latest = latest,
        minPoint = minPoint,
        maxPoint = maxPoint,
        change = change,
        distinctDays = distinctDays,
        average = average,
        dateRangeText = dateRangeText,
        healthTip = healthTip
    )
}

/**
 * 趋势与历史统计主页面
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToRecords: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val history by viewModel.history.collectAsState()
    val members by viewModel.members.collectAsState()
    val selectedMemberId by viewModel.selectedMemberId.collectAsState()
    val selectedTimeRange by viewModel.selectedTimeRange.collectAsState()
    val selectedMetric by viewModel.selectedMetric.collectAsState()

    var showMemberSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val currentMember = remember(members, selectedMemberId) {
        members.firstOrNull { it.id == selectedMemberId }
    }

    val memberFilteredHistory = remember(history, selectedMemberId, members) {
        if (selectedMemberId == null || members.size <= 1) {
            history
        } else {
            history.filter { it.memberId == selectedMemberId || it.memberId == null }
        }
    }

    val currentMemberName = currentMember?.name ?: if (members.size <= 1 && members.isNotEmpty()) members.first().name else "全部成员"

    val stats = remember(memberFilteredHistory, selectedMetric, selectedTimeRange, currentMemberName) {
        calculateTrendStatistics(memberFilteredHistory, selectedMetric, selectedTimeRange, currentMemberName)
    }

    if (showMemberSheet) {
        MemberSelectionBottomSheet(
            members = members,
            selectedMemberId = selectedMemberId,
            onSelect = { memberId ->
                viewModel.selectMember(memberId)
                showMemberSheet = false
            },
            onDismiss = { showMemberSheet = false }
        )
    }

    HistoryContent(
        memberName = currentMemberName,
        isMale = currentMember?.sex == Sex.MALE,
        totalRecordCount = memberFilteredHistory.size,
        selectedTimeRange = selectedTimeRange,
        selectedMetric = selectedMetric,
        stats = stats,
        onMemberClick = { showMemberSheet = true },
        onShareClick = {
            shareTrendReport(
                context = context,
                memberName = currentMemberName,
                stats = stats,
                metric = selectedMetric,
                timeRange = selectedTimeRange
            )
        },
        onRecordsClick = onNavigateToRecords,
        onSelectTimeRange = { viewModel.selectTimeRange(it) },
        onSelectMetric = { viewModel.selectMetric(it) },
        modifier = modifier
    )
}

/**
 * 趋势与历史统计内容布局（解耦 ViewModel，支持 Preview 与独立测试）
 */
@Composable
fun HistoryContent(
    memberName: String,
    isMale: Boolean,
    totalRecordCount: Int,
    selectedTimeRange: HistoryTimeRange,
    selectedMetric: HistoryMetric,
    stats: TrendStatistics,
    onMemberClick: () -> Unit = {},
    onShareClick: () -> Unit = {},
    onRecordsClick: () -> Unit = {},
    onSelectTimeRange: (HistoryTimeRange) -> Unit = {},
    onSelectMetric: (HistoryMetric) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TrendTopBar(
                memberName = memberName,
                isMale = isMale,
                totalRecordCount = totalRecordCount,
                onMemberClick = onMemberClick,
                onShareClick = onShareClick,
                onRecordsClick = onRecordsClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (maxHeight < 520.dp) {
                // 小屏/横屏多任务模式：支持纵向滚动查看完整内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TimeRangeCapsuleSegment(
                        selectedRange = selectedTimeRange,
                        onSelectRange = onSelectTimeRange,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    MetricTabsRow(
                        selectedMetric = selectedMetric,
                        onSelectMetric = onSelectMetric,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (stats.points.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        RangeAndExtremesBar(
                            dateRangeText = stats.dateRangeText,
                            maxVal = stats.maxPoint?.second,
                            minVal = stats.minPoint?.second,
                            metric = selectedMetric,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    EnhancedTrendChart(
                        points = stats.points,
                        metric = selectedMetric,
                        totalHistoryCount = totalRecordCount,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SummaryMetricCard(
                        distinctDays = stats.distinctDays,
                        change = stats.change,
                        metric = selectedMetric,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    ExtraInsightsCard(
                        stats = stats,
                        metric = selectedMetric,
                        onNavigateToRecords = onRecordsClick,
                        totalCount = totalRecordCount,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            } else {
                // 标准竖屏模式：利用 weight(1f) 自适应铺满中间全部空间，数据在中间完整呈现，无任何突兀空洞
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. 时间周期切换胶囊栏（近一周、近一月、近90天、近半年）
                    TimeRangeCapsuleSegment(
                        selectedRange = selectedTimeRange,
                        onSelectRange = onSelectTimeRange,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. 指标切换栏（体重、BMI、体脂率）
                    MetricTabsRow(
                        selectedMetric = selectedMetric,
                        onSelectMetric = onSelectMetric,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 3. 时间区间 & 极值概览栏（仅在有数据时展示）
                    if (stats.points.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        RangeAndExtremesBar(
                            dateRangeText = stats.dateRangeText,
                            maxVal = stats.maxPoint?.second,
                            minVal = stats.minPoint?.second,
                            metric = selectedMetric,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 4. 核心平滑走势曲线图：weight(1f) 自适应撑满全部中间可用纵深，数据完整舒展居中展示
                    EnhancedTrendChart(
                        points = stats.points,
                        metric = selectedMetric,
                        totalHistoryCount = totalRecordCount,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 4.dp)
                    )

                    // 5. 下半部分：紧密贴底显示的统计指标卡与洞察建议卡
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SummaryMetricCard(
                            distinctDays = stats.distinctDays,
                            change = stats.change,
                            metric = selectedMetric,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        ExtraInsightsCard(
                            stats = stats,
                            metric = selectedMetric,
                            onNavigateToRecords = onRecordsClick,
                            totalCount = totalRecordCount,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "历史趋势页面", showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    val now = System.currentTimeMillis()
    val sampleHistory = listOf(
        BodyMeasurement(
            id = "1",
            measuredAtEpochMs = now - 3 * 86400000L,
            weightKg = 69.5,
            impedanceOhm = 500.0,
            bmi = 22.7,
            bodyFatPct = 18.5,
            muscleKg = 53.0,
            waterPct = 58.0,
            proteinPct = 17.0,
            boneMassKg = 3.1,
            memberId = "1",
            memberNameSnapshot = "张三"
        ),
        BodyMeasurement(
            id = "2",
            measuredAtEpochMs = now - 1 * 86400000L,
            weightKg = 68.8,
            impedanceOhm = 500.0,
            bmi = 22.5,
            bodyFatPct = 18.1,
            muscleKg = 53.2,
            waterPct = 58.5,
            proteinPct = 17.5,
            boneMassKg = 3.1,
            memberId = "1",
            memberNameSnapshot = "张三"
        ),
        BodyMeasurement(
            id = "3",
            measuredAtEpochMs = now,
            weightKg = 68.2,
            impedanceOhm = 500.0,
            bmi = 22.3,
            bodyFatPct = 17.9,
            muscleKg = 53.5,
            waterPct = 59.0,
            proteinPct = 17.8,
            boneMassKg = 3.1,
            memberId = "1",
            memberNameSnapshot = "张三"
        )
    )
    val stats = calculateTrendStatistics(sampleHistory, HistoryMetric.WEIGHT, HistoryTimeRange.ONE_WEEK, "张三")
    电子秤Theme(dynamicColor = false) {
        HistoryContent(
            memberName = "张三",
            isMale = true,
            totalRecordCount = sampleHistory.size,
            selectedTimeRange = HistoryTimeRange.ONE_WEEK,
            selectedMetric = HistoryMetric.WEIGHT,
            stats = stats
        )
    }
}

/**
 * 顶部导航栏：纯净设计，无笨重灰底，直接由头像 + 姓名 + 下拉箭头组成，右侧放置分享与历史记录图标
 */
@Composable
fun TrendTopBar(
    memberName: String,
    isMale: Boolean,
    totalRecordCount: Int,
    onMemberClick: () -> Unit,
    onShareClick: () -> Unit,
    onRecordsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 成员切换区域
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable { onMemberClick() }
                .padding(vertical = 4.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = memberName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "切换成员",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        // 右侧操作图标组
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onShareClick,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "分享趋势",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            IconButton(
                onClick = onRecordsClick,
                modifier = Modifier.size(38.dp)
            ) {
                BadgedBox(
                    badge = {
                        if (totalRecordCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text(if (totalRecordCount > 99) "99+" else "$totalRecordCount")
                            }
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ReceiptLong,
                        contentDescription = "数字记录明细",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 现代高质感时间切换胶囊控件（近一周、近一月、近90天、近半年）
 * 消除层叠黑灰背景，内嵌纯白圆角胶囊与自然柔光阴影，完全契合参考图质感
 */
@Composable
fun TimeRangeCapsuleSegment(
    selectedRange: HistoryTimeRange,
    onSelectRange: (HistoryTimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val ranges = HistoryTimeRange.entries

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ranges.forEach { range ->
                val isSelected = range == selectedRange
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (isSelected) {
                                Modifier
                                    .shadow(
                                        elevation = 1.5.dp,
                                        shape = RoundedCornerShape(9.dp),
                                        spotColor = Color(0x20000000),
                                        ambientColor = Color(0x10000000)
                                    )
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(9.dp)
                                    )
                            } else {
                                Modifier
                            }
                        )
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onSelectRange(range) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = range.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 指标切换栏（体重、BMI、体脂率）
 */
@Composable
fun MetricTabsRow(
    selectedMetric: HistoryMetric,
    onSelectMetric: (HistoryMetric) -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = HistoryMetric.entries

    Row(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        metrics.forEach { metric ->
            val isSelected = metric == selectedMetric
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelectMetric(metric) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = metric.label,
                    fontSize = 16.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(3.5.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                )
            }
        }
    }
}

/**
 * 时间区间及极值展示栏（03/02-08/29   🔴 最高 69.95kg   🟢 最低 68.95kg）
 * 仅在具有有效极值数据时展示，杜绝无数据时孤立展示 "--"
 */
@Composable
fun RangeAndExtremesBar(
    dateRangeText: String,
    maxVal: Double?,
    minVal: Double?,
    metric: HistoryMetric,
    modifier: Modifier = Modifier
) {
    if (maxVal == null || minVal == null || dateRangeText == "--") {
        return
    }

    Row(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateRangeText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "最高 ",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${metric.formatNumberOnly(maxVal)}${metric.unit}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "最低 ",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${metric.formatNumberOnly(minVal)}${metric.unit}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 现代平滑走势曲线图，Catmull-Rom 三次贝塞尔曲线、垂直渐变、5档水平虚线网格与交互 Tooltip
 */
@Composable
fun EnhancedTrendChart(
    points: List<Pair<BodyMeasurement, Double>>,
    metric: HistoryMetric,
    totalHistoryCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    if (points.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            // 背景绘制 5 条优雅微弱的水平虚线网格，维持图表结构感
            Canvas(modifier = Modifier.fillMaxSize()) {
                val paddingLeft = 48.dp.toPx()
                val paddingRight = 16.dp.toPx()
                val paddingTop = 16.dp.toPx()
                val paddingBottom = 32.dp.toPx()
                val chartW = size.width - paddingLeft - paddingRight
                val chartH = size.height - paddingTop - paddingBottom
                val tickCount = 5

                for (gi in 0 until tickCount) {
                    val gy = paddingTop + gi * (chartH / (tickCount - 1))
                    drawLine(
                        color = gridColor,
                        start = Offset(paddingLeft, gy),
                        end = Offset(paddingLeft + chartW, gy),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }
            }

            // 中心优雅温和的空状态提示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (totalHistoryCount > 0) "所选周期内暂无测量数据" else "暂无测量数据",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (totalHistoryCount > 0) "可切换上方时间周期或前往明细查看" else "站上体脂秤完成称重，即可自动生成走势曲线",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    val density = LocalDensity.current

    var selectedIndex by remember { mutableIntStateOf(-1) }

    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(points, metric) {
        selectedIndex = -1
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    val values = points.map { it.second }
    val minVal = values.minOrNull() ?: 0.0
    val maxVal = values.maxOrNull() ?: 1.0

    val span = if (maxVal > minVal) maxVal - minVal else 1.0
    val plotMin = minVal - span * 0.15
    val plotMax = maxVal + span * 0.15
    val plotRange = plotMax - plotMin

    val dateOnlyFormat = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    val yearFormat = remember { SimpleDateFormat("yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures(
                        onTap = { offset ->
                            val paddingLeft = 48.dp.toPx()
                            val paddingRight = 16.dp.toPx()
                            val chartW = size.width - paddingLeft - paddingRight
                            val n = points.size
                            if (n == 1) {
                                selectedIndex = 0
                            } else {
                                val touchX = offset.x
                                var closestIdx = 0
                                var minDistance = Float.MAX_VALUE
                                for (i in 0 until n) {
                                    val px = paddingLeft + (i.toFloat() / (n - 1)) * chartW
                                    val dist = abs(px - touchX)
                                    if (dist < minDistance) {
                                        minDistance = dist
                                        closestIdx = i
                                    }
                                }
                                selectedIndex = if (selectedIndex == closestIdx) -1 else closestIdx
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val paddingLeft = 48.dp.toPx()
            val paddingRight = 16.dp.toPx()
            val paddingTop = 16.dp.toPx()
            val paddingBottom = 32.dp.toPx()
            val chartW = w - paddingLeft - paddingRight
            val chartH = h - paddingTop - paddingBottom
            val n = points.size

            fun xOf(i: Int): Float {
                return if (n <= 1) paddingLeft + chartW / 2f
                else paddingLeft + (i.toFloat() / (n - 1)) * chartW
            }

            fun yOf(v: Double): Float {
                return (paddingTop + chartH * (1.0 - (v - plotMin) / plotRange)).toFloat()
            }

            // 1. 绘制 5 档 Y 轴刻度文本及水平虚线
            val tickCount = 5
            val tickPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#A0A0A5")
                isAntiAlias = true
                textSize = with(density) { 10.sp.toPx() }
                textAlign = android.graphics.Paint.Align.RIGHT
            }

            for (gi in 0 until tickCount) {
                val tickVal = plotMax - gi * (plotRange / (tickCount - 1))
                val gy = paddingTop + gi * (chartH / (tickCount - 1))

                drawLine(
                    color = Color(0x18000000),
                    start = Offset(paddingLeft, gy),
                    end = Offset(paddingLeft + chartW, gy),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )

                val text = if (metric == HistoryMetric.WEIGHT) {
                    String.format(Locale.getDefault(), "%.2f", tickVal)
                } else {
                    String.format(Locale.getDefault(), "%.1f", tickVal)
                }
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    paddingLeft - 6.dp.toPx(),
                    gy + 3.5.dp.toPx(),
                    tickPaint
                )
            }

            // 2. 绘制折线/曲线与渐变区域（带动画展开）
            clipRect(left = 0f, top = 0f, right = paddingLeft + chartW * animProgress.value, bottom = h) {
                if (n == 1) {
                    val px = xOf(0)
                    val py = yOf(values[0])
                    drawCircle(Color.White, radius = 5.5.dp.toPx(), center = Offset(px, py))
                    drawCircle(primaryColor, radius = 4.5.dp.toPx(), center = Offset(px, py), style = Stroke(width = 2.dp.toPx()))
                } else {
                    val linePath = Path()
                    val fillPath = Path()

                    val p0x = xOf(0)
                    val p0y = yOf(values[0])
                    linePath.moveTo(p0x, p0y)
                    fillPath.moveTo(p0x, p0y)

                    for (i in 0 until n - 1) {
                        val currX = xOf(i)
                        val currY = yOf(values[i])
                        val nextX = xOf(i + 1)
                        val nextY = yOf(values[i + 1])

                        val prevX = if (i > 0) xOf(i - 1) else currX
                        val prevY = if (i > 0) yOf(values[i - 1]) else currY
                        val nextNextX = if (i + 2 < n) xOf(i + 2) else nextX
                        val nextNextY = if (i + 2 < n) yOf(values[i + 2]) else nextY

                        val cp1X = currX + (nextX - prevX) / 6f
                        val cp1Y = currY + (nextY - prevY) / 6f
                        val cp2X = nextX - (nextNextX - currX) / 6f
                        val cp2Y = nextY - (nextNextY - currY) / 6f

                        linePath.cubicTo(cp1X, cp1Y, cp2X, cp2Y, nextX, nextY)
                        fillPath.cubicTo(cp1X, cp1Y, cp2X, cp2Y, nextX, nextY)
                    }

                    fillPath.lineTo(xOf(n - 1), paddingTop + chartH)
                    fillPath.lineTo(xOf(0), paddingTop + chartH)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.16f), primaryColor.copy(alpha = 0.01f)),
                            startY = paddingTop,
                            endY = paddingTop + chartH
                        )
                    )

                    drawPath(
                        path = linePath,
                        color = primaryColor,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    for (i in 0 until n) {
                        val px = xOf(i)
                        val py = yOf(values[i])
                        drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(px, py))
                        drawCircle(primaryColor, radius = 4.dp.toPx(), center = Offset(px, py), style = Stroke(width = 1.8.dp.toPx()))
                    }
                }
            }

            // 3. X 轴时间标签（双行：MM/dd 和 yyyy）
            val dateLabelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#A0A0A5")
                isAntiAlias = true
                textSize = with(density) { 9.sp.toPx() }
                textAlign = android.graphics.Paint.Align.CENTER
            }

            val labelIndices = if (n <= 3) {
                (0 until n).toList()
            } else {
                val step = (n - 1) / 3
                listOf(0, step, step * 2, n - 1).distinct()
            }

            for (idx in labelIndices) {
                val point = points[idx]
                val px = xOf(idx)
                val line1 = dateOnlyFormat.format(Date(point.first.measuredAtEpochMs))
                val line2 = yearFormat.format(Date(point.first.measuredAtEpochMs))

                val baseY = paddingTop + chartH + 13.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(line1, px, baseY, dateLabelPaint)
                drawContext.canvas.nativeCanvas.drawText(line2, px, baseY + 10.dp.toPx(), dateLabelPaint)
            }

            // 4. 交互选中点引导线与高亮光晕
            if (selectedIndex in 0 until n) {
                val activeX = xOf(selectedIndex)
                val activeY = yOf(values[selectedIndex])

                drawLine(
                    color = primaryColor.copy(alpha = 0.45f),
                    start = Offset(activeX, paddingTop),
                    end = Offset(activeX, paddingTop + chartH),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                )

                drawCircle(primaryColor.copy(alpha = 0.2f), radius = 9.dp.toPx(), center = Offset(activeX, activeY))
                drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(activeX, activeY))
                drawCircle(primaryColor, radius = 4.5.dp.toPx(), center = Offset(activeX, activeY), style = Stroke(width = 2.5.dp.toPx()))
            }
        }

        // 浮动交互提示框
        if (selectedIndex in points.indices) {
            val activePoint = points[selectedIndex]
            val previousPoint = if (selectedIndex > 0) points[selectedIndex - 1] else null
            val diff = previousPoint?.let { activePoint.second - it.second }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1F2937),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = timeFormat.format(Date(activePoint.first.measuredAtEpochMs)),
                            fontSize = 11.sp,
                            color = Color(0xFFD1D5DB)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = metric.format(activePoint.second),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (diff != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            val diffSign = if (diff > 0) "+" else ""
                            Text(
                                text = "(${diffSign}${String.format(Locale.getDefault(), "%.2f", diff)})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (diff <= 0) Color(0xFF34D399) else Color(0xFFF87171)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 核心指标卡片（记录天数 / 体重变化）- 严格参考用户上传设计图
 */
@Composable
fun SummaryMetricCard(
    distinctDays: Int,
    change: Double,
    metric: HistoryMetric,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 记录天数
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "记录天数",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$distinctDays",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = " 天",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // 竖向细分隔线
            Box(
                modifier = Modifier
                    .height(38.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            )

            // 体重变化 / BMI变化
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${metric.label}变化",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    val formattedChange = if (distinctDays == 0) "--" else {
                        val sign = if (change > 0) "+" else if (change < 0) "-" else ""
                        "$sign${String.format(Locale.getDefault(), "%.2f", abs(change))}"
                    }
                    Text(
                        text = formattedChange,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (distinctDays > 0 && metric.unit.isNotEmpty()) {
                        Text(
                            text = " ${metric.unit}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 整合型数据概览与洞察卡片：风格与 SummaryMetricCard 统一，消除碎片化堆叠卡片
 */
@Composable
fun ExtraInsightsCard(
    stats: TrendStatistics,
    metric: HistoryMetric,
    onNavigateToRecords: () -> Unit,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (stats.points.isNotEmpty()) {
                // 4项简明数据
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val earliestVal = stats.earliest?.second?.let { metric.format(it) } ?: "--"
                    val latestVal = stats.latest?.second?.let { metric.format(it) } ?: "--"
                    val avgVal = if (stats.points.isNotEmpty()) metric.format(stats.average) else "--"
                    val rangeVal = if (stats.maxPoint != null && stats.minPoint != null) {
                        String.format(Locale.getDefault(), "%.2f%s", stats.maxPoint.second - stats.minPoint.second, metric.unit)
                    } else "--"

                    StatGridColumn("期初", earliestVal, Modifier.weight(1f))
                    StatGridColumn("最新", latestVal, Modifier.weight(1f))
                    StatGridColumn("均值", avgVal, Modifier.weight(1f))
                    StatGridColumn("极差", rangeVal, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // 智能趋势提示
                Text(
                    text = stats.healthTip,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            } else {
                Text(
                    text = "💡 开启健康蜕变之旅",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "每日清晨空腹赤脚称重，能更精准地捕捉体重与体脂变化趋势。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            if (totalCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(6.dp))

                // 跳转明细快捷入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateToRecords() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "查看全部数字记录明细 (${totalCount}条)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatGridColumn(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(3.dp))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 家庭成员切换底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberSelectionBottomSheet(
    members: List<FamilyMember>,
    selectedMemberId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "选择要查看趋势的家庭成员",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (members.size > 1) {
                Surface(
                    onClick = { onSelect(null) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedMemberId == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.People, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("全部成员汇总", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (selectedMemberId == null) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            members.forEach { member ->
                val isSelected = selectedMemberId == member.id
                Surface(
                    onClick = { onSelect(member.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (member.sex == Sex.MALE) Color(0xFF3B82F6) else Color(0xFFEC4899)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(member.name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 触发系统分享身体趋势报告
 */
fun shareTrendReport(
    context: android.content.Context,
    memberName: String,
    stats: TrendStatistics,
    metric: HistoryMetric,
    timeRange: HistoryTimeRange
) {
    val shareContent = buildString {
        appendLine("【智能体脂秤 · 身体趋势简报】")
        appendLine("👤 成员：$memberName")
        appendLine("📅 周期：${timeRange.label} (${stats.dateRangeText})")
        appendLine("📊 指标：${metric.label}")
        appendLine("⏱ 累计记录：${stats.distinctDays} 天")
        if (stats.latest != null) {
            appendLine("📌 最新数值：${metric.format(stats.latest.second)}")
        }
        val sign = if (stats.change > 0) "+" else ""
        appendLine("📈 阶段变化：$sign${String.format(Locale.getDefault(), "%.2f", stats.change)} ${metric.unit}")
        if (stats.maxPoint != null && stats.minPoint != null) {
            appendLine("🔺 最高：${metric.format(stats.maxPoint.second)} | 🔻 最低：${metric.format(stats.minPoint.second)}")
        }
        appendLine("💡 趋势评价：${stats.healthTip}")
        appendLine("\n—— 来自智能体脂秤，关注每一天的蜕变！")
    }

    val intent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, shareContent)
        type = "text/plain"
    }
    context.startActivity(Intent.createChooser(intent, "分享健康趋势报告"))
}

// ─────────────────────────────────────────────────────────────────────────────
// 以下为完整的数字记录明细页面及卡片组件（由右上角图标进入，收纳数字记录）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 测量记录明细全屏列表（数字记录收起来更加简洁）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRecordsScreen(
    viewModel: HistoryViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val history by viewModel.history.collectAsState()
    val members by viewModel.members.collectAsState()
    val selectedMemberId by viewModel.selectedMemberId.collectAsState()

    val dateFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()) }
    var measurementToDelete by remember { mutableStateOf<BodyMeasurement?>(null) }
    var rebindTargetMeasurement by remember { mutableStateOf<BodyMeasurement?>(null) }

    val filteredHistory = remember(history, selectedMemberId, members) {
        if (selectedMemberId == null || members.size <= 1) {
            history
        } else {
            history.filter { it.memberId == selectedMemberId || it.memberId == null }
        }
    }

    if (rebindTargetMeasurement != null) {
        RebindMemberDialog(
            measurement = rebindTargetMeasurement!!,
            members = members,
            onSelectMember = { targetMember ->
                viewModel.rebindMeasurement(rebindTargetMeasurement!!, targetMember)
                rebindTargetMeasurement = null
            },
            onDismiss = { rebindTargetMeasurement = null }
        )
    }

    if (measurementToDelete != null) {
        val target = measurementToDelete!!
        AlertDialog(
            onDismissRequest = { measurementToDelete = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除 ${dateFormat.format(Date(target.measuredAtEpochMs))} 的 ${String.format(Locale.getDefault(), "%.2f", target.weightKg)} kg 测量记录吗？此操作无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMeasurement(target)
                        measurementToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { measurementToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数字记录明细 (${filteredHistory.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回趋势")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (filteredHistory.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "暂无测量记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (members.size > 1) {
                    item(key = "member_filter_chips") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedMemberId == null,
                                    onClick = { viewModel.selectMember(null) },
                                    label = { Text("全部 (${history.size})") }
                                )
                            }
                            items(members, key = { it.id }) { member ->
                                val count = history.count { it.memberId == member.id }
                                FilterChip(
                                    selected = selectedMemberId == member.id,
                                    onClick = { viewModel.selectMember(member.id) },
                                    label = { Text("${member.name} ($count)") }
                                )
                            }
                        }
                    }
                }

                items(filteredHistory, key = { it.id }) { measurement ->
                    ModernMeasurementCard(
                        measurement = measurement,
                        dateStr = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                        onDelete = { measurementToDelete = measurement },
                        onNavigateToDetail = onNavigateToDetail,
                        onRebindClick = { rebindTargetMeasurement = measurement }
                    )
                }
            }
        }
    }
}

@Composable
fun ModernMeasurementCard(
    measurement: BodyMeasurement,
    dateStr: String,
    onDelete: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onRebindClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDetail(measurement.id) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateStr,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.getDefault(), "%.2f", measurement.weightKg),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1F2937)
                        )
                        Text(
                            text = " kg",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(bottom = 4.dp),
                            color = Color(0xFF6B7280)
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = Color(0xFF9CA3AF)
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 成员匹配按钮标签（支持点击快速重新匹配成员）
            val memberName = measurement.memberNameSnapshot
            Surface(
                onClick = { onRebindClick?.invoke() },
                color = if (memberName != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color(0xFFFFF7ED),
                shape = RoundedCornerShape(6.dp),
                border = if (memberName == null) androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFFFDBA74)) else null
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (memberName != null) Icons.Default.Person else Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = if (memberName != null) MaterialTheme.colorScheme.primary else Color(0xFFEA580C),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (memberName != null) "成员: $memberName ▾" else "未匹配成员 (点击分配) ▾",
                        fontSize = 11.5.sp,
                        color = if (memberName != null) MaterialTheme.colorScheme.primary else Color(0xFFEA580C),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    HistoryMiniMetric("体脂", if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.bodyFatPct) else "--")
                    HistoryMiniMetric("BMI", if (measurement.bmi > 0.0) String.format(Locale.getDefault(), "%.1f", measurement.bmi) else "--")
                }
                Text(
                    text = "点击查看详情 ›",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun HistoryMiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF9CA3AF))
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 单次测量详情页面（点击明细项进入）
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementDetailScreen(
    measurementId: String,
    viewModel: HistoryViewModel,
    onNavigateBack: () -> Unit
) {
    val history by viewModel.history.collectAsState()
    val members by viewModel.members.collectAsState()
    val measurement = history.find { it.id == measurementId }

    var showRebindDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault()) }

    if (showRebindDialog && measurement != null) {
        RebindMemberDialog(
            measurement = measurement,
            members = members,
            onSelectMember = { targetMember ->
                viewModel.rebindMeasurement(measurement, targetMember)
                showRebindDialog = false
            },
            onDismiss = { showRebindDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测量详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (measurement == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("未找到该测量记录")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    onClick = { showRebindDialog = true },
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "成员: ${measurement.memberNameSnapshot ?: "未匹配"} (点击重新匹配) ▾",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = String.format(Locale.getDefault(), "%.2f", measurement.weightKg),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "kg",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(32.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "身体数据分析",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("BMI", if (measurement.bmi > 0.0) String.format(Locale.getDefault(), "%.1f", measurement.bmi) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("体脂率", if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.bodyFatPct) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("水分", if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.waterPct) else "--", modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("肌肉量", if (hasBia) String.format(Locale.getDefault(), "%.1fkg", measurement.muscleKg) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("蛋白质", if (hasBia) String.format(Locale.getDefault(), "%.1f%%", measurement.proteinPct) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("骨量", if (hasBia) String.format(Locale.getDefault(), "%.1fkg", measurement.boneMassKg) else "--", modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("电阻抗", if (measurement.impedanceOhm > 0.0) "${measurement.impedanceOhm.toInt()}Ω" else "未测出", modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(2f))
                        }
                        if (measurement.ageUsed > 0 || measurement.algorithmId.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                            val algoName = com.example.dianzicheng.domain.BodyAlgorithmType.entries
                                .firstOrNull { it.id == measurement.algorithmId }?.displayName ?: "未记录"
                            Text(
                                text = "计算依据：${measurement.ageUsed}岁 · ${measurement.heightCmUsed.toInt()}cm · $algoName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (measurement.recalcCount > 0) {
                            Text(
                                text = "已重算 ${measurement.recalcCount} 次，最近：${dateFormat.format(Date(measurement.lastRecalcAtEpochMs))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailGridItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * 重新匹配归属成员弹窗（支持误测后快速重新分配成员，自动按目标成员参数重算 BIA 与各项体成分指标）
 */
@Composable
fun RebindMemberDialog(
    measurement: BodyMeasurement,
    members: List<FamilyMember>,
    onSelectMember: (FamilyMember) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("重新匹配归属成员", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
        },
        text = {
            Column {
                Text(
                    text = "将本次测量记录 (${String.format(Locale.getDefault(), "%.2f", measurement.weightKg)} kg) 重新归属给指定家庭成员，系统将按该成员的身高、年龄自动重新计算体脂率、BMI等各项身体指标，无需删除记录：",
                    fontSize = 12.5.sp,
                    color = Color(0xFF4B5563),
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(14.dp))
                if (members.isEmpty()) {
                    Text("暂无可匹配的家庭成员", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                } else {
                    members.forEach { member ->
                        val isCurrent = measurement.memberId == member.id
                        Surface(
                            onClick = { onSelectMember(member) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(if (member.sex == Sex.MALE) Color(0xFF3B82F6) else Color(0xFFEC4899)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = member.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                                            fontSize = 11.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (isCurrent) {
                                    Text(
                                        text = "当前归属",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "点击匹配 ›",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
