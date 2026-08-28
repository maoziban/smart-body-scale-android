package com.example.dianzicheng.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.ui.theme.电子秤Theme
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryContent(
    history: List<BodyMeasurement>,
    members: List<FamilyMember> = emptyList(),
    onDelete: (BodyMeasurement) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()) }
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var measurementToDelete by remember { mutableStateOf<BodyMeasurement?>(null) }

    val filteredHistory = remember(history, selectedMemberId) {
        if (selectedMemberId == null) {
            history
        } else {
            history.filter { it.memberId == selectedMemberId }
        }
    }

    if (measurementToDelete != null) {
        val target = measurementToDelete!!
        AlertDialog(
            onDismissRequest = { measurementToDelete = null },
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除 ${dateFormat.format(Date(target.measuredAtEpochMs))} 的 ${String.format("%.2f", target.weightKg)} kg 测量记录吗？此操作无法撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
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
            MediumTopAppBar(
                title = { Text("测量历史", fontWeight = FontWeight.Bold) }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无历史记录", 
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (members.size > 1) {
                    item(key = "member_filter") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedMemberId == null,
                                    onClick = { selectedMemberId = null },
                                    label = { Text("全部 (${history.size})") }
                                )
                            }
                            items(members, key = { it.id }) { member ->
                                val count = history.count { it.memberId == member.id }
                                FilterChip(
                                    selected = selectedMemberId == member.id,
                                    onClick = { selectedMemberId = member.id },
                                    label = { Text("${member.name} ($count)") }
                                )
                            }
                        }
                    }
                }

                item(key = "weight_chart_${selectedMemberId ?: "all"}") {
                    WeightTrendChart(
                        measurements = filteredHistory.take(30),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (filteredHistory.isEmpty()) {
                    item(key = "empty_member_history") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("该成员暂无历史记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                } else {
                    items(filteredHistory, key = { it.id }) { measurement ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            ModernMeasurementCard(
                                measurement = measurement,
                                dateStr = dateFormat.format(Date(measurement.measuredAtEpochMs)),
                                onDelete = { measurementToDelete = measurement },
                                onNavigateToDetail = onNavigateToDetail
                            )
                        }
                    }
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
    onNavigateToDetail: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDetail(measurement.id) },
        shape = RoundedCornerShape(8.dp), // Using a clean, slightly rounded card design instead of extraLarge
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dateStr, 
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format("%.2f", measurement.weightKg),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = " kg",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Member Name tag if present
            if (measurement.memberNameSnapshot != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = measurement.memberNameSnapshot,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    HistoryMiniMetric("体脂", if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--")
                    HistoryMiniMetric("BMI", if (measurement.bmi > 0.0) String.format("%.1f", measurement.bmi) else "--")
                }
                Text(
                    text = "点击查看详情 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun HistoryMiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WeightTrendChart(
    measurements: List<BodyMeasurement>,
    modifier: Modifier = Modifier
) {
    if (measurements.size < 2) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    // Animation progress
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(measurements) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 900))
    }

    val sorted = remember(measurements) { measurements.sortedBy { it.measuredAtEpochMs } }
    val weights = sorted.map { it.weightKg }
    val weightMinVal = weights.min()
    val weightMaxVal = weights.max()
    val hasDiff = weightMaxVal > weightMinVal
    val minWeight = if (hasDiff) weightMinVal else weightMinVal - 0.5
    val maxWeight = if (hasDiff) weightMaxVal else weightMaxVal + 0.5
    val weightRange = maxWeight - minWeight

    val labelDateFormat = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "体重走势",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "近${sorted.size}次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (hasDiff) {
                    Text(
                        text = String.format("↓ %.1f kg", weightMinVal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = String.format("↑ %.1f kg", weightMaxVal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        text = String.format("均值: %.1f kg", weightMinVal),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val w = size.width
                val h = size.height
                val paddingLeft = 8.dp.toPx()
                val paddingRight = 8.dp.toPx()
                val paddingTop = 12.dp.toPx()
                val paddingBottom = 24.dp.toPx()
                val chartW = w - paddingLeft - paddingRight
                val chartH = h - paddingTop - paddingBottom
                val n = sorted.size

                fun xOf(i: Int) = paddingLeft + i.toFloat() / (n - 1).toFloat() * chartW
                fun yOf(weight: Double) = paddingTop + chartH * (1.0 - (weight - minWeight) / weightRange).toFloat()

                // Grid lines (3 horizontal)
                val gridColor = outlineColor.copy(alpha = 0.12f)
                for (gi in 0..2) {
                    val gy = paddingTop + chartH * gi / 2f
                    drawLine(gridColor, Offset(paddingLeft, gy), Offset(paddingLeft + chartW, gy), strokeWidth = 1.dp.toPx())
                }

                // Progress clip for animation
                clipRect(left = 0f, top = 0f, right = paddingLeft + chartW * animProgress.value, bottom = h) {
                    // Gradient fill area
                    val fillPath = Path()
                    fillPath.moveTo(xOf(0), yOf(weights[0]))
                    for (i in 1 until n) {
                        val x0 = xOf(i - 1); val y0 = yOf(weights[i - 1])
                        val x1 = xOf(i);     val y1 = yOf(weights[i])
                        val cx = (x0 + x1) / 2f
                        fillPath.cubicTo(cx, y0, cx, y1, x1, y1)
                    }
                    fillPath.lineTo(xOf(n - 1), h - paddingBottom)
                    fillPath.lineTo(xOf(0), h - paddingBottom)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.25f), primaryColor.copy(alpha = 0f)),
                            startY = paddingTop,
                            endY = h - paddingBottom
                        )
                    )

                    // Line
                    val linePath = Path()
                    linePath.moveTo(xOf(0), yOf(weights[0]))
                    for (i in 1 until n) {
                        val x0 = xOf(i - 1); val y0 = yOf(weights[i - 1])
                        val x1 = xOf(i);     val y1 = yOf(weights[i])
                        val cx = (x0 + x1) / 2f
                        linePath.cubicTo(cx, y0, cx, y1, x1, y1)
                    }
                    drawPath(
                        path = linePath,
                        color = primaryColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Data points
                    val maxIdx = weights.indexOfFirst { it == weightMaxVal }
                    val minIdx = weights.indexOfFirst { it == weightMinVal }
                    for (i in 0 until n) {
                        val px = xOf(i); val py = yOf(weights[i])
                        val isHighlight = hasDiff && (i == maxIdx || i == minIdx || i == 0 || i == n - 1)
                        if (isHighlight) {
                            drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(px, py))
                            drawCircle(primaryColor, radius = 4.dp.toPx(), center = Offset(px, py), style = Stroke(width = 2.dp.toPx()))
                        } else {
                            drawCircle(primaryColor.copy(alpha = 0.4f), radius = 2.5.dp.toPx(), center = Offset(px, py))
                        }
                    }
                }

                // Date labels: first and last
                val labelPaint = android.graphics.Paint().apply {
                    color = outlineColor.copy(alpha = 0.7f).toArgb()
                    isAntiAlias = true
                    textSize = with(density) { 10.sp.toPx() }
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                val firstLabel = labelDateFormat.format(Date(sorted.first().measuredAtEpochMs))
                val lastLabel = labelDateFormat.format(Date(sorted.last().measuredAtEpochMs))
                val labelY = h - 4.dp.toPx()
                drawContext.canvas.nativeCanvas.apply {
                    drawText(firstLabel, paddingLeft, labelY, labelPaint)
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    drawText(lastLabel, paddingLeft + chartW, labelY, labelPaint)
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val history by viewModel.history.collectAsState()
    val members by viewModel.members.collectAsState()
    HistoryContent(
        history = history,
        members = members,
        onDelete = { viewModel.deleteMeasurement(it) },
        onNavigateToDetail = onNavigateToDetail,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementDetailScreen(
    measurementId: String,
    viewModel: HistoryViewModel,
    onNavigateBack: () -> Unit
) {
    val history by viewModel.history.collectAsState()
    val measurement = history.find { it.id == measurementId }

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("测量详情", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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

                if (measurement.memberNameSnapshot != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "成员: ${measurement.memberNameSnapshot}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = String.format("%.2f", measurement.weightKg),
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
                            DetailGridItem("BMI", if (measurement.bmi > 0.0) String.format("%.1f", measurement.bmi) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("体脂率", if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("水分", if (hasBia) String.format("%.1f%%", measurement.waterPct) else "--", modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("肌肉量", if (hasBia) String.format("%.1fkg", measurement.muscleKg) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("蛋白质", if (hasBia) String.format("%.1f%%", measurement.proteinPct) else "--", modifier = Modifier.weight(1f))
                            DetailGridItem("骨量", if (hasBia) String.format("%.1fkg", measurement.boneMassKg) else "--", modifier = Modifier.weight(1f))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            DetailGridItem("电阻抗", if (measurement.impedanceOhm > 0.0) "${measurement.impedanceOhm.toInt()}Ω" else "未测出", modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.weight(2f))
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

@Preview(showBackground = true)
@Composable
fun HistoryPreview() {
    电子秤Theme(dynamicColor = true) {
        HistoryContent(
            history = listOf(
                BodyMeasurement("1", System.currentTimeMillis(), 70.5, 500.0, 22.8, 18.5, 55.0, 60.0, 16.5, 3.2, "1", "我的名字"),
                BodyMeasurement("2", System.currentTimeMillis() - 86400000, 71.2, 510.0, 23.1, 19.0, 54.0, 59.0, 16.0, 3.1, "1", "我的名字")
            ),
            onDelete = {},
            onNavigateToDetail = {}
        )
    }
}
