package com.example.dianzicheng.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.BodyAlgorithmType
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.theme.电子秤Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    members: List<FamilyMember>,
    onAddMember: (String, Sex, Double, Long, Double) -> Unit,
    onDeleteMember: (FamilyMember) -> Unit,
    onUpdateMember: (FamilyMember) -> Unit = {},
    onRecalculateHistory: () -> Unit = {},
    onResetPairing: () -> Unit,
    onNavigateToPairing: () -> Unit = onResetPairing,
    pairedMac: String? = null,
    pairedDeviceName: String? = null,
    selectedScaleModel: ScaleModel = ScaleModel.AUTO,
    onSelectScaleModel: (ScaleModel) -> Unit = {},
    selectedBodyAlgorithm: BodyAlgorithmType = BodyAlgorithmType.COMMERCIAL,
    onSelectBodyAlgorithm: (BodyAlgorithmType) -> Unit = {},
    healthConnectEnabled: Boolean = false,
    onToggleHealthConnect: (Boolean) -> Unit = {},
    onRequestHealthConnectPermissions: () -> Unit = {},
    onBatchSyncToHealthConnect: () -> Unit = {},
    onOpenMiHealth: () -> Unit = {},
    onOpenHealthConnectSettings: () -> Unit = {},
    syncProgress: String? = null,
    isMiHealthInstalled: Boolean = false,
    zeppLifeSyncEnabled: Boolean = false,
    onToggleZeppLifeSync: (Boolean) -> Unit = {},
    zeppLifeAccount: String = "",
    zeppLifePassword: String = "",
    zeppLifeAppToken: String = "",
    zeppLifeUserId: String = "",
    onSaveZeppLifeConfig: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onTestZeppLifeConnection: (String, String, String, String, (Boolean, String) -> Unit) -> Unit = { _, _, _, _, _ -> },
    onSyncLatestToZeppLife: () -> Unit = {},
    webdavUrl: String = "",
    webdavUsername: String = "",
    webdavPassword: String = "",
    lastBackupTime: Long = 0L,
    onSaveWebdavConfig: (String, String, String) -> Unit = { _, _, _ -> },
    onTestWebdavConnection: (String, String, String) -> Unit = { _, _, _ -> },
    onBackupData: () -> Unit = {},
    onRestoreData: () -> Unit = {},
    isOperating: Boolean = false,
    logEntries: List<AppLogger.LogEntry> = emptyList(),
    onClearLogs: () -> Unit = {},
    onShareLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showWebdavDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showUnpairConfirmDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showAlgorithmDialog by remember { mutableStateOf(false) }
    var memberToDelete by remember { mutableStateOf<FamilyMember?>(null) }
    var memberToEdit by remember { mutableStateOf<FamilyMember?>(null) }
    var showRecalcConfirm by remember { mutableStateOf(false) }
    var showZeppDialog by remember { mutableStateOf(false) }
    var showHealthConnectDialog by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "我的",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加成员")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Member Section
            item {
                Text(
                    text = "成员管理",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (members.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "暂无成员，点击右上角图标添加",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(members) { member ->
                    MemberCard(
                        member = member,
                        onEdit = { memberToEdit = member },
                        onDelete = { memberToDelete = member }
                    )
                }
            }

            // Data Synchronization Section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "数据同步",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    SettingsItem(
                        title = "系统健康 (Health Connect)",
                        subtitle = if (healthConnectEnabled) "自动同步已开启" else "系统健康已关闭",
                        icon = Icons.Default.Favorite,
                        onClick = { showHealthConnectDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "Zepp Life 云同步",
                        subtitle = if (zeppLifeSyncEnabled) "云端同步已开启" else if (zeppLifeAppToken.isNotBlank() || zeppLifeAccount.isNotBlank()) "已配置 (未开启)" else "未配置",
                        icon = Icons.Default.CloudSync,
                        onClick = { showZeppDialog = true }
                    )
                }
            }

            // WebDAV Backup Section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "WebDAV 云端备份",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    SettingsItem(
                        title = "WebDAV 服务器配置",
                        subtitle = if (webdavUrl.isBlank()) "未配置" else "已配置",
                        icon = Icons.Default.Cloud,
                        onClick = { showWebdavDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "立即备份到 WebDAV",
                        subtitle = if (lastBackupTime > 0) "上次: ${dateFormat.format(Date(lastBackupTime))}" else "从未备份",
                        icon = Icons.Default.CloudUpload,
                        onClick = onBackupData,
                        isLoading = isOperating
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "从 WebDAV 恢复数据",
                        icon = Icons.Default.CloudDownload,
                        onClick = onRestoreData,
                        isLoading = isOperating
                    )
                }
            }

            // Settings Section
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "应用设置",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    SettingsItem(
                        title = if (pairedMac.isNullOrEmpty()) "绑定体脂秤" else "已记住体脂秤",
                        subtitle = if (pairedMac.isNullOrEmpty()) "未绑定设备" else "${pairedDeviceName ?: "体脂秤"} · 点击解绑",
                        icon = Icons.Default.Bluetooth,
                        onClick = {
                            if (pairedMac.isNullOrEmpty()) {
                                onNavigateToPairing()
                            } else {
                                showUnpairConfirmDialog = true
                            }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "体脂秤型号与品牌",
                        subtitle = selectedScaleModel.displayName,
                        icon = Icons.Default.Tune,
                        onClick = { showModelDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "体成分计算算法",
                        subtitle = selectedBodyAlgorithm.displayName,
                        icon = Icons.Default.Calculate,
                        onClick = { showAlgorithmDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "重算历史记录",
                        subtitle = "按最新资料与算法重算历史指标",
                        icon = Icons.Default.Refresh,
                        onClick = { showRecalcConfirm = true },
                        isLoading = isOperating
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "查看运行日志",
                        subtitle = if (logEntries.isEmpty()) "暂无日志" else "共 ${logEntries.size} 条记录",
                        icon = Icons.AutoMirrored.Filled.Article,
                        onClick = { showLogDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "关于软件",
                        subtitle = "版本 1.4.1",
                        icon = Icons.Default.Info,
                        onClick = {
                            try {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/maoziban/smart-body-scale-android")
                                    )
                                )
                            } catch (_: Exception) {
                                Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    if (showLogDialog) {
        LogViewerDialog(
            entries = logEntries,
            onDismiss = { showLogDialog = false },
            onClear = onClearLogs,
            onShare = onShareLogs
        )
    }

    if (showAddDialog) {
        AddMemberDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, sex, height, birth, weight ->
                onAddMember(name, sex, height, birth, weight)
                showAddDialog = false
            }
        )
    }

    memberToEdit?.let { target ->
        AddMemberDialog(
            initial = target,
            onDismiss = { memberToEdit = null },
            onConfirm = { name, sex, height, birth, _ ->
                onUpdateMember(target.copy(name = name, sex = sex, heightCm = height, birthDateEpochMs = birth))
                memberToEdit = null
            }
        )
    }

    if (showRecalcConfirm) {
        AlertDialog(
            onDismissRequest = { showRecalcConfirm = false },
            title = { Text("重算历史记录", fontWeight = FontWeight.Bold) },
            text = {
                Text("将按「${selectedBodyAlgorithm.displayName}」和各成员当前的性别、身高、年龄重算全部历史体成分指标。原始体重、阻抗与称重时间不会改变，重算次数会记录在每条记录上。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onRecalculateHistory()
                    showRecalcConfirm = false
                }) { Text("开始重算", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRecalcConfirm = false }) { Text("取消") }
            }
        )
    }

    if (memberToDelete != null) {
        val target = memberToDelete!!
        AlertDialog(
            onDismissRequest = { memberToDelete = null },
            title = { Text("确认删除成员", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除成员「${target.name}」吗？\n删除后该成员的历史测量记录将保留，但不再关联到此成员。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMember(target)
                        memberToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showUnpairConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirmDialog = false },
            title = { Text("解除设备绑定？", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要解除与体脂秤「${pairedDeviceName ?: pairedMac}」的绑定吗？\n\n解除后 App 将暂停自动连接，直到您重新手动搜索并绑定设备。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnpairConfirmDialog = false
                        onResetPairing()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("解除绑定", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showWebdavDialog) {
        WebDavConfigDialog(
            initialUrl = webdavUrl,
            initialUsername = webdavUsername,
            initialPassword = webdavPassword,
            onDismiss = { showWebdavDialog = false },
            onSave = { url, user, pass ->
                onSaveWebdavConfig(url, user, pass)
                showWebdavDialog = false
            },
            onTest = { url, user, pass ->
                onTestWebdavConnection(url, user, pass)
            },
            isOperating = isOperating
        )
    }

    if (showHealthConnectDialog) {
        HealthConnectSyncDialog(
            enabled = healthConnectEnabled,
            onToggleEnabled = onToggleHealthConnect,
            onRequestPermissions = onRequestHealthConnectPermissions,
            onBatchSync = onBatchSyncToHealthConnect,
            syncProgress = syncProgress,
            onDismiss = { showHealthConnectDialog = false }
        )
    }

    if (showZeppDialog) {
        ZeppLifeConfigDialog(
            syncEnabled = zeppLifeSyncEnabled,
            onToggleSync = onToggleZeppLifeSync,
            onSyncLatest = onSyncLatestToZeppLife,
            initialAccount = zeppLifeAccount,
            initialPassword = zeppLifePassword,
            initialToken = zeppLifeAppToken,
            initialUserId = zeppLifeUserId,
            onDismiss = { showZeppDialog = false },
            onSave = { acc, pass, token, uid ->
                onSaveZeppLifeConfig(acc, pass, token, uid)
                showZeppDialog = false
            },
            onTest = { acc, pass, token, uid, onResult ->
                onTestZeppLifeConnection(acc, pass, token, uid, onResult)
            },
            isOperating = isOperating
        )
    }

    if (showModelDialog) {
        SelectScaleModelDialog(
            currentModel = selectedScaleModel,
            onModelSelected = onSelectScaleModel,
            onDismiss = { showModelDialog = false }
        )
    }

    if (showAlgorithmDialog) {
        SelectBodyAlgorithmDialog(
            currentAlgorithm = selectedBodyAlgorithm,
            onAlgorithmSelected = onSelectBodyAlgorithm,
            onDismiss = { showAlgorithmDialog = false }
        )
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent,
        enabled = !isLoading
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(
    entries: List<AppLogger.LogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    // 每次有新日志时自动滚动到最底部
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            try {
                listState.scrollToItem(entries.size - 1)
            } catch (_: Exception) {
                // 列表在滚动期间变化时忽略，避免崩溃
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("运行日志", fontWeight = FontWeight.Bold)
                            Text(
                                "${entries.size} 条记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val text = AppLogger.exportText()
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                        }
                        IconButton(onClick = {
                            val text = AppLogger.exportText()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_SUBJECT, "AppLog")
                            }
                            context.startActivity(Intent.createChooser(intent, "分享日志"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Delete, contentDescription = "清空")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        LogEntryRow(entry = entry, timeFmt = timeFmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(
    entry: AppLogger.LogEntry,
    timeFmt: SimpleDateFormat
) {
    val (levelColor, levelBg) = when (entry.level) {
        AppLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent
        AppLogger.Level.INFO  -> Color(0xFF2196F3) to Color(0xFF2196F3).copy(alpha = 0.07f)
        AppLogger.Level.WARN  -> Color(0xFFFF9800) to Color(0xFFFF9800).copy(alpha = 0.07f)
        AppLogger.Level.ERROR -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(levelBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeFmt.format(Date(entry.timestamp)),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.level.name.take(1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "[${entry.tag}] ${entry.message}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (entry.level == AppLogger.Level.DEBUG)
                MaterialTheme.colorScheme.onSurfaceVariant
            else
                MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun WebDavConfigDialog(
    initialUrl: String,
    initialUsername: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit,
    isOperating: Boolean
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 WebDAV 云同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "支持坚果云、Nextcloud、OwnCloud、NAS 等任意标准 WebDAV 服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器 URL (如 https://dav.jianguoyun.com/dav/)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号 / 用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 / 应用授权码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url, username, password) },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest(url, username, password) },
                    enabled = !isOperating,
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (isOperating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun HealthConnectSyncDialog(
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onBatchSync: () -> Unit,
    syncProgress: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("系统健康 (Health Connect)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Health Connect 是 Android 官方健康数据共享中心。开启后可将测量数据写入，供 Google Fit、三星健康等外部健康 App 读取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动同步至系统健康", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("称重保存后自动写入", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = enabled, onCheckedChange = onToggleEnabled)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                OutlinedButton(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("申请健康中心读写权限")
                }

                Button(
                    onClick = onBatchSync,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = syncProgress == null
                ) {
                    if (syncProgress != null) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(syncProgress)
                    } else {
                        Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("批量同步全部历史记录")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun ZeppLifeConfigDialog(
    syncEnabled: Boolean = false,
    onToggleSync: (Boolean) -> Unit = {},
    onSyncLatest: () -> Unit = {},
    initialAccount: String,
    initialPassword: String,
    initialToken: String,
    initialUserId: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onTest: (String, String, String, String, (Boolean, String) -> Unit) -> Unit,
    isOperating: Boolean
) {
    var account by remember { mutableStateOf(initialAccount) }
    var password by remember { mutableStateOf(initialPassword) }
    var token by remember { mutableStateOf(initialToken) }
    var userId by remember { mutableStateOf(initialUserId) }
    var testResult by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Zepp Life 云端同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动上传到 Zepp 云端", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("称重保存后自动推送", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = syncEnabled, onCheckedChange = onToggleSync)
                    }
                }

                if (syncEnabled) {
                    OutlinedButton(
                        onClick = onSyncLatest,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isOperating
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("立即上传最新测量数据")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    "支持华米账号密码登录提取 Token，或直接填入免密 AppToken 直连。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("Zepp/华米 登录邮箱/账号") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("登录密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "如账号登录遇到验证码，可直接填入已有的 AppToken 与 UserId：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("AppToken (可选)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text("User ID (可选)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(account, password, token, userId) },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        testResult = "正在测试连接并获取Token..."
                        onTest(account, password, token, userId) { success, msg ->
                            testResult = if (success) "✅ $msg" else "❌ $msg"
                        }
                    },
                    enabled = !isOperating,
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (isOperating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试/提取")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun MemberCard(member: FamilyMember, onEdit: () -> Unit = {}, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm · ${com.example.dianzicheng.domain.BodyAlgorithm.ageAt(member.birthDateEpochMs)}岁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun AddMemberDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Sex, Double, Long, Double) -> Unit,
    initial: FamilyMember? = null
) {
    val initialAge = remember(initial) {
        initial?.let { com.example.dianzicheng.domain.BodyAlgorithm.ageAt(it.birthDateEpochMs) } ?: 25
    }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var sex by remember { mutableStateOf(initial?.sex ?: Sex.MALE) }
    var height by remember { mutableStateOf(initial?.heightCm?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "170") }
    var age by remember { mutableStateOf(initialAge.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加新成员" else "编辑成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                Text("性别", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilterChip(
                        selected = sex == Sex.MALE,
                        onClick = { sex = Sex.MALE },
                        label = { Text("男生") },
                        leadingIcon = if (sex == Sex.MALE) { { Icon(Icons.Default.Check, null) } } else null
                    )
                    FilterChip(
                        selected = sex == Sex.FEMALE,
                        onClick = { sex = Sex.FEMALE },
                        label = { Text("女生") },
                        leadingIcon = if (sex == Sex.FEMALE) { { Icon(Icons.Default.Check, null) } } else null
                    )
                }

                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it },
                    label = { Text("身高 (cm)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                OutlinedTextField(
                    value = age,
                    onValueChange = { v -> age = v.filter { it.isDigit() }.take(3) },
                    label = { Text("年龄 (岁)") },
                    supportingText = if (initial != null) { { Text("修改后将重算该成员的历史记录") } } else null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (name.isBlank()) "新成员" else name.trim()
                    val ageInt = (age.toIntOrNull() ?: initialAge).coerceIn(1, 120)
                    // 年龄未改动时保留原出生日期（避免丢失月/日精度）；否则由年龄反推
                    val birth = if (initial != null && ageInt == initialAge) initial.birthDateEpochMs
                        else com.example.dianzicheng.domain.BodyAlgorithm.birthDateFromAge(ageInt)
                    onConfirm(
                        finalName,
                        sex,
                        (height.toDoubleOrNull() ?: 170.0).coerceIn(50.0, 250.0),
                        birth,
                        initial?.referenceWeightKg ?: 0.0
                    )
                    onDismiss()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}


@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val members by viewModel.members.collectAsState()
    val healthConnectEnabled by viewModel.healthConnectEnabled.collectAsState()
    val webdavUrl by viewModel.webdavUrl.collectAsState()
    val webdavUsername by viewModel.webdavUsername.collectAsState()
    val webdavPassword by viewModel.webdavPassword.collectAsState()
    val lastBackupTime by viewModel.lastBackupTime.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val pairedMac by viewModel.pairedMac.collectAsState()
    val pairedDeviceName by viewModel.pairedDeviceName.collectAsState()
    val selectedScaleModel by viewModel.selectedScaleModel.collectAsState()
    val selectedBodyAlgorithm by viewModel.selectedBodyAlgorithm.collectAsState()

    val zeppLifeSyncEnabled by viewModel.zeppLifeSyncEnabled.collectAsState()
    val zeppLifeAccount by viewModel.zeppLifeAccount.collectAsState()
    val zeppLifePassword by viewModel.zeppLifePassword.collectAsState()
    val zeppLifeAppToken by viewModel.zeppLifeAppToken.collectAsState()
    val zeppLifeUserId by viewModel.zeppLifeUserId.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.isNotEmpty()) {
            Toast.makeText(context, "已获得系统健康权限！", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未获得健康读写权限", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    ProfileContent(
        members = members,
        onAddMember = { name, sex, height, birth, weight -> viewModel.addMember(name, sex, height, birth, weight) },
        onDeleteMember = { viewModel.deleteMember(it) },
        onUpdateMember = { viewModel.updateMember(it) },
        onRecalculateHistory = { viewModel.recalculateAllHistory() },
        onResetPairing = { viewModel.resetPairing() },
        onNavigateToPairing = onNavigateToPairing,
        pairedMac = pairedMac,
        pairedDeviceName = pairedDeviceName,
        selectedScaleModel = selectedScaleModel,
        onSelectScaleModel = { viewModel.selectScaleModel(it) },
        selectedBodyAlgorithm = selectedBodyAlgorithm,
        onSelectBodyAlgorithm = { viewModel.selectBodyAlgorithm(it) },
        healthConnectEnabled = healthConnectEnabled,
        onToggleHealthConnect = { enabled ->
            viewModel.setHealthConnectEnabled(enabled)
            if (enabled && viewModel.isHealthConnectAvailable()) {
                permissionLauncher.launch(
                    setOf(
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.BodyFatRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class)
                    )
                )
            }
        },
        onRequestHealthConnectPermissions = {
            if (viewModel.isHealthConnectAvailable()) {
                permissionLauncher.launch(
                    setOf(
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.BodyFatRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class)
                    )
                )
            } else {
                Toast.makeText(context, "当前设备不支持或未安装 Health Connect", Toast.LENGTH_SHORT).show()
            }
        },
        onBatchSyncToHealthConnect = { viewModel.batchSyncToHealthConnect() },
        onOpenMiHealth = { viewModel.openMiHealth() },
        onOpenHealthConnectSettings = { viewModel.openHealthConnectSettings() },
        syncProgress = syncProgress,
        isMiHealthInstalled = viewModel.isMiHealthInstalled(),
        zeppLifeSyncEnabled = zeppLifeSyncEnabled,
        onToggleZeppLifeSync = { viewModel.toggleZeppLifeSync(it) },
        zeppLifeAccount = zeppLifeAccount,
        zeppLifePassword = zeppLifePassword,
        zeppLifeAppToken = zeppLifeAppToken,
        zeppLifeUserId = zeppLifeUserId,
        onSaveZeppLifeConfig = { acc, pass, token, uid -> viewModel.saveZeppLifeConfig(acc, pass, token, uid) },
        onTestZeppLifeConnection = { acc, pass, token, uid, onResult -> viewModel.testZeppLifeConnection(acc, pass, token, uid, onResult) },
        onSyncLatestToZeppLife = { viewModel.syncLatestToZeppLife() },
        webdavUrl = webdavUrl,
        webdavUsername = webdavUsername,
        webdavPassword = webdavPassword,
        lastBackupTime = lastBackupTime,
        onSaveWebdavConfig = { url, user, pass -> viewModel.saveWebdavConfig(url, user, pass) },
        onTestWebdavConnection = { url, user, pass -> viewModel.testWebdavConnection(url, user, pass) },
        onBackupData = { viewModel.backupData() },
        onRestoreData = { viewModel.restoreData() },
        isOperating = isOperating,
        logEntries = logEntries,
        onClearLogs = { viewModel.clearLogs() },
        onShareLogs = {
            val text = viewModel.exportLogsText()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "AppLog")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "分享日志"))
        },
        modifier = modifier
    )
}

@Preview(name = "个人中心设置", showBackground = true)
@Composable
private fun ProfileScreenPreview() {
    val sampleMembers = listOf(
        FamilyMember(
            id = "1",
            name = "张三",
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = 946684800000L,
            referenceWeightKg = 68.5
        ),
        FamilyMember(
            id = "2",
            name = "李四",
            sex = Sex.FEMALE,
            heightCm = 162.0,
            birthDateEpochMs = 978307200000L,
            referenceWeightKg = 52.0
        )
    )
    电子秤Theme(dynamicColor = false) {
        ProfileContent(
            members = sampleMembers,
            onAddMember = { _, _, _, _, _ -> },
            onDeleteMember = {},
            onResetPairing = {},
            pairedMac = "AA:BB:CC:DD:EE:FF",
            pairedDeviceName = "Phicomm S7 体脂秤",
            healthConnectEnabled = true,
            onToggleHealthConnect = {},
            onRequestHealthConnectPermissions = {},
            onBatchSyncToHealthConnect = {},
            onOpenMiHealth = {},
            onOpenHealthConnectSettings = {},
            syncProgress = null,
            isMiHealthInstalled = true,
            webdavUrl = "https://dav.jianguoyun.com/dav/",
            webdavUsername = "user@example.com",
            webdavPassword = "password",
            lastBackupTime = System.currentTimeMillis() - 3600000L,
            onSaveWebdavConfig = { _, _, _ -> },
            onTestWebdavConnection = { _, _, _ -> },
            onBackupData = {},
            onRestoreData = {},
            isOperating = false
        )
    }
}


