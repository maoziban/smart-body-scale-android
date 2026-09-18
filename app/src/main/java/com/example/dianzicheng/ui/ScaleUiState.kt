package com.example.dianzicheng.ui

import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember

/**
 * 称重主界面的 UI 状态数据类（不可变快照）。
 *
 * 由 [ScaleViewModel] 维护，通过 StateFlow 推送给 UI 层。
 * UI 只需订阅此单一状态对象，无需关心多个独立 LiveData/Flow 的同步问题。
 *
 * @param connection         当前蓝牙连接状态（IDLE / SCANNING / CONNECTING / CONNECTED / MEASURING）
 * @param liveWeightKg       设备实时上报的体重（公斤），0.0 表示离秤或未连接
 * @param impedanceOhm       本次称量测得的阻抗（欧姆），0.0 表示未测出（需赤脚）
 * @param isStable           当前体重是否已稳定锁定（true 时显示测量结果卡片）
 * @param currentMeasurement 当前称量的完整测量结果；用户离秤且新一次称量开始前保持可见
 * @param selectedMember     用户手动选择的家庭成员；null 表示使用智能自动匹配
 * @param availableMembers   全部家庭成员列表，用于成员选择弹窗
 * @param showNewMemberAlert 是否显示"发现新成员"提示弹窗（匹配偏差 > 7kg 且未手动选择时触发）
 * @param error              错误消息（暂未使用，预留扩展）
 * @param debugMessage       调试消息（暂未使用，预留扩展）
 * @param discoveredDeviceName 扫描发现的设备名称（用于配对引导页展示）
 * @param discoveredDeviceMac  扫描发现的设备 MAC 地址（用于后续直连）
 */
data class ScaleUiState(
    val connection: BleScaleClient.ConnectionState = BleScaleClient.ConnectionState.IDLE,
    val liveWeightKg: Double = 0.0,
    val impedanceOhm: Double = 0.0,
    val isStable: Boolean = false,
    val currentMeasurement: BodyMeasurement? = null,
    val selectedMember: FamilyMember? = null,
    val availableMembers: List<FamilyMember> = emptyList(),
    val showNewMemberAlert: Boolean = false,
    val error: String? = null,
    val discoveredDeviceName: String? = null,
    val discoveredDeviceMac: String? = null,
    val pairedDeviceMac: String? = null,
    val pairedDeviceName: String? = null,
    val isDeviceRemembered: Boolean = false,
    val discoveredScales: List<BleScaleClient.DiscoveredScaleDevice> = emptyList()
)
