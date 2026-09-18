package com.example.dianzicheng.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.theme.电子秤Theme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(
    uiState: ScaleUiState,
    onStartScan: () -> Unit,
    onDismissAlert: () -> Unit,
    onSelectMember: (FamilyMember?) -> Unit,
    onBindMember: (FamilyMember) -> Unit,
    onNavigateToPairing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showMemberSelectDialog by remember { mutableStateOf(false) }
    var hasAutoPromptedForMeasId by remember { mutableStateOf<String?>(null) }

    var lastStableState by remember { mutableStateOf(false) }

    // 测量完全结束后（锁定完成并下秤），若当前测量未匹配到成员且有多个成员可选，自动弹出成员匹配弹窗
    LaunchedEffect(uiState.isStable, uiState.currentMeasurement?.id) {
        val currentMeas = uiState.currentMeasurement
        if (lastStableState && !uiState.isStable && currentMeas != null && currentMeas.id != hasAutoPromptedForMeasId) {
            hasAutoPromptedForMeasId = currentMeas.id
            if (uiState.selectedMember == null && currentMeas.memberId == null && uiState.availableMembers.size > 1) {
                showMemberSelectDialog = true
            }
        }
        if (uiState.isStable) {
            lastStableState = true
        } else if (currentMeas == null) {
            lastStableState = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("体重秤", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Member Selector Chip
            Surface(
                onClick = { showMemberSelectDialog = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.selectedMember?.let {
                            "绑定成员: ${it.name} (${if (it.sex == Sex.MALE) "男" else "女"} ${it.heightCm.toInt()}cm)"
                        } ?: "全员智能自动匹配",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connection Status Chip
            ConnectionStatusChip(
                state = uiState.connection,
                deviceName = uiState.pairedDeviceName ?: uiState.discoveredDeviceName,
                isDeviceRemembered = uiState.isDeviceRemembered
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Weight Display with modern circle
            WeightDisplay(
                weight = uiState.liveWeightKg,
                isStable = uiState.isStable,
                isCompact = uiState.currentMeasurement != null
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Metrics Card
            AnimatedVisibility(
                visible = uiState.currentMeasurement != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                uiState.currentMeasurement?.let {
                    MeasurementResultCard(
                        measurement = it,
                        availableMembers = uiState.availableMembers,
                        onBindMember = onBindMember
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Button / Binding Guidance Card
            if (!uiState.isDeviceRemembered) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "未绑定体脂秤",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "手动连接并记住体脂秤后，App 将仅针对该设备开启自动连接，防止误连周围其他设备。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToPairing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("搜索并绑定设备")
                        }
                    }
                }
            } else {
                if (uiState.connection == BleScaleClient.ConnectionState.IDLE) {
                    FilledTonalButton(
                        onClick = onStartScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text("开始称重", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (uiState.connection == BleScaleClient.ConnectionState.SCANNING) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                    )
                    Text(
                        "正在自动寻找已记住的设备 (${uiState.pairedDeviceName ?: uiState.pairedDeviceMac ?: "体脂秤"})...",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (uiState.showNewMemberAlert) {
        AlertDialog(
            onDismissRequest = onDismissAlert,
            title = { Text("发现新成员？") },
            text = { Text("测量体重与已有成员差距较大，您可以稍后在“我的”页面添加新成员。") },
            confirmButton = {
                TextButton(onClick = onDismissAlert) {
                    Text("知道了")
                }
            }
        )
    }

    if (showMemberSelectDialog) {
        SelectMemberDialog(
            members = uiState.availableMembers,
            currentMemberId = uiState.selectedMember?.id,
            onDismiss = { showMemberSelectDialog = false },
            onSelect = { member ->
                onSelectMember(member)
                showMemberSelectDialog = false
            }
        )
    }
}

@Composable
fun ConnectionStatusChip(
    state: BleScaleClient.ConnectionState,
    deviceName: String? = null,
    isDeviceRemembered: Boolean = true
) {
    if (!isDeviceRemembered) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "未绑定设备（自动连接已暂停）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        return
    }

    val color = when (state) {
        BleScaleClient.ConnectionState.IDLE       -> MaterialTheme.colorScheme.outline
        BleScaleClient.ConnectionState.SCANNING   -> MaterialTheme.colorScheme.primary
        BleScaleClient.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        BleScaleClient.ConnectionState.CONNECTED,
        BleScaleClient.ConnectionState.MEASURING  -> Color(0xFF4CAF50)
    }

    val label = when (state) {
        BleScaleClient.ConnectionState.IDLE -> if (deviceName != null) "就绪 · $deviceName" else "未连接"
        BleScaleClient.ConnectionState.SCANNING -> if (deviceName != null) "正在寻找 · $deviceName" else "搜索中"
        BleScaleClient.ConnectionState.CONNECTING -> if (deviceName != null) "正在连接 · $deviceName" else "连接中"
        BleScaleClient.ConnectionState.CONNECTED -> if (deviceName != null) "已连接 · $deviceName" else "已连接"
        BleScaleClient.ConnectionState.MEASURING -> if (deviceName != null) "测量中 · $deviceName" else "测量中"
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = color
            )
        }
    }
}

@Composable
fun WeightDisplay(weight: Double, isStable: Boolean, isCompact: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (!isStable && weight > 0) 0.5f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        )
    )

    val size = if (isCompact) 180.dp else 240.dp
    val fontSize = if (isCompact) 60.sp else 76.sp

    Box(
        modifier = Modifier
            .size(size)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.2f", weight),
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (weight > 0) alpha else 0.3f)
            )
            Text(
                text = "kg",
                fontSize = if (isCompact) 20.sp else 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun MeasurementResultCard(
    measurement: BodyMeasurement,
    availableMembers: List<FamilyMember>,
    onBindMember: (FamilyMember) -> Unit
) {
    var showBindDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Member Binding Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "数据所属: ${measurement.memberNameSnapshot ?: "未绑定成员"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (availableMembers.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showBindDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("重选/绑定成员", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
            // 从成员列表查找性别，用于体脂率健康区间判断（男女标准不同）
            val isMale = availableMembers.firstOrNull { it.id == measurement.memberId }
                ?.sex == com.example.dianzicheng.domain.Sex.MALE

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem(
                    label = "BMI",
                    value = if (measurement.bmi > 0.0) String.format("%.1f", measurement.bmi) else "--",
                    status = if (measurement.bmi > 0.0) getBmiStatus(measurement.bmi) else null,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "体脂率",
                    value = if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--",
                    status = if (hasBia) getFatStatus(measurement.bodyFatPct, isMale) else null,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "水分",
                    value = if (hasBia) String.format("%.1f%%", measurement.waterPct) else "--",
                    status = if (hasBia && measurement.waterPct in 50.0..65.0) "标准" else if (hasBia) "注意" else null,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem("肌肉量", if (hasBia) String.format("%.1fkg", measurement.muscleKg) else "--", modifier = Modifier.weight(1f))
                MetricItem("蛋白质", if (hasBia) String.format("%.1f%%", measurement.proteinPct) else "--", modifier = Modifier.weight(1f))
                MetricItem("骨量", if (hasBia) String.format("%.1fkg", measurement.boneMassKg) else "--", modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem(
                    label = "阻抗",
                    value = if (measurement.impedanceOhm > 0.0) "${measurement.impedanceOhm.toInt()}Ω" else "未测出 (请赤脚称重)",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showBindDialog) {
        SelectMemberDialog(
            members = availableMembers,
            currentMemberId = measurement.memberId,
            onDismiss = { showBindDialog = false },
            onSelect = { member ->
                if (member != null) {
                    onBindMember(member)
                }
                showBindDialog = false
            }
        )
    }
}

@Composable
fun SelectMemberDialog(
    members: List<FamilyMember>,
    currentMemberId: String?,
    onDismiss: () -> Unit,
    onSelect: (FamilyMember?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择绑定成员") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "选择成员后，系统将使用该成员的身高、年龄与性别重新计算准确的体脂率等指标并保存绑定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                // Auto Match Option
                Surface(
                    onClick = { onSelect(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (currentMemberId == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("全员智能自动匹配", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (members.isEmpty()) {
                    Text(
                        "暂无成员，请在“我的”页面添加家庭成员",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(members) { member ->
                            val isSelected = member.id == currentMemberId
                            Surface(
                                onClick = { onSelect(member) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = member.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun getBmiStatus(bmi: Double): String = when {
    bmi < 18.5 -> "偏瘦"
    bmi < 24.0 -> "标准"
    bmi < 28.0 -> "超重"
    else -> "肥胖"
}

private fun getFatStatus(fat: Double, isMale: Boolean): String = when {
    isMale -> when {
        fat < 8.0  -> "偏低"
        fat < 20.0 -> "标准"
        fat < 25.0 -> "偏高"
        else       -> "肥胖"
    }
    else -> when {
        fat < 17.0 -> "偏低"
        fat < 30.0 -> "标准"
        fat < 35.0 -> "偏高"
        else       -> "肥胖"
    }
}

@Composable
fun MetricItem(label: String, value: String, status: String? = null, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        if (status != null) {
            val color = if (status == "标准") Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            Surface(
                color = color.copy(alpha = 0.1f),
                shape = CircleShape,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: ScaleViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPairing: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // 仅当已有记住的设备时，才在进入主页时自动尝试连接该设备。
    // Bug #24 修复：同时监听 connection 状态，确保从后台切回时连接已断（IDLE）也能重新触发扫描，
    // 而不是仅在 isDeviceRemembered 首次从 false->true 时触发一次（冷启动后 key 不再变化导致漏触发）。
    LaunchedEffect(uiState.isDeviceRemembered, uiState.connection) {
        if (uiState.isDeviceRemembered && uiState.connection == BleScaleClient.ConnectionState.IDLE) {
            viewModel.startScanning()
        }
    }

    DashboardContent(
        uiState = uiState,
        onStartScan = { viewModel.startScanning() },
        onDismissAlert = { viewModel.dismissAlert() },
        onSelectMember = { viewModel.selectMember(it) },
        onBindMember = { viewModel.bindCurrentMeasurementToMember(it) },
        onNavigateToPairing = onNavigateToPairing,
        modifier = modifier
    )
}
