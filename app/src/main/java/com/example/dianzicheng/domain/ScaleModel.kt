package com.example.dianzicheng.domain

/**
 * 体脂秤/体重秤支持的型号与协议分类枚举。
 *
 * 允许用户在界面上显式手动选择自己的秤型号，同时支持默认的“自动识别 (全品牌兼容)”。
 *
 * @property id             唯一标识符，用于持久化存储到 DataStore
 * @property displayName    在界面上呈现的友好型号全称
 * @property brand          品牌厂商名称
 * @property category       分类标签（如 "推荐", "热门品牌", "主流方案", "专有协议/Wi-Fi", "通用标准"）
 * @property description    型号与协议特性简述
 * @property wakeGuidance   针对该型号的具体唤醒与称重指引
 * @property protocolName   对应的协议名称
 */
enum class ScaleModel(
    val id: String,
    val displayName: String,
    val brand: String,
    val category: String,
    val description: String,
    val wakeGuidance: String,
    val protocolName: String
) {
    AUTO(
        id = "AUTO",
        displayName = "自动识别 (全品牌兼容)",
        brand = "智能多协议匹配",
        category = "推荐",
        description = "自动探测蓝牙广播与特征通知报文，智能匹配协议，兼容市面上 99% 的体脂秤",
        wakeGuidance = "请将秤放置于平整地面上，轻踩秤面唤醒屏幕即可",
        protocolName = "Auto-Detect"
    ),
    BOOHEE_YOLANDA(
        id = "BOOHEE_YOLANDA",
        displayName = "薄荷健康 / 沃莱 (Yolanda / 轻牛)",
        brand = "薄荷健康 / 沃莱",
        category = "热门品牌",
        description = "采用 0xAC 8 字节流式与 20 字节复合帧协议，支持 BIA 生物电阻抗与体脂率计算",
        wakeGuidance = "请踩秤亮屏，若测体脂需光脚站立在金属电极片上，稳定后自动回传阻抗",
        protocolName = "Yolanda/Boohee"
    ),
    XIAOMI_SCALE_2(
        id = "XIAOMI_SCALE_2",
        displayName = "小米体脂秤 2 / 米家体脂秤",
        brand = "小米 / 米家",
        category = "热门品牌",
        description = "蓝牙广播 0x181B / 0x2A9C 13 字节协议，即踩即读无需配对，支持 10+ 项体成分",
        wakeGuidance = "赤脚踩上秤面唤醒，保持站立直至显示屏体脂测试进度灯全部亮起",
        protocolName = "Xiaomi Mi Scale 2"
    ),
    XIAOMI_SCALE_1(
        id = "XIAOMI_SCALE_1",
        displayName = "小米体重秤 1 代 (经典款)",
        brand = "小米 / 米家",
        category = "热门品牌",
        description = "蓝牙广播 0x181D 10 字节标准体重协议，支持公斤与斤单位自动换算",
        wakeGuidance = "轻踩秤面唤醒，站在秤上等待数字闪烁锁定",
        protocolName = "Xiaomi Mi Scale 1"
    ),
    OKOK_CHIPSEA(
        id = "OKOK_CHIPSEA",
        displayName = "OKOK / 芯海科技方案体脂秤",
        brand = "OKOK / 芯海科技",
        category = "主流方案",
        description = "芯海芯片常见 0x10 / 0xCF 协议帧与 0xFFF0 / 0xFFE0 透传服务",
        wakeGuidance = "请踩秤点亮屏幕，蓝牙广播名称通常包含 OKOK、Chipsea 或 CS- 开头",
        protocolName = "OKOK/Chipsea"
    ),
    SENSSUN(
        id = "SENSSUN",
        displayName = "香山电子秤 (Senssun / CAMRY)",
        brand = "香山 / 康美",
        category = "主流品牌",
        description = "香山 0xAA / 0x55 专用透传协议帧，0.1kg 精度分辨率",
        wakeGuidance = "轻踩秤面唤醒，香山常见蓝牙广播名为 Senssun 或 CAMRY",
        protocolName = "Senssun"
    ),
    AFU_PROTOCOL(
        id = "AFU_PROTOCOL",
        displayName = "沃莱 AFU 私有协议体脂秤",
        brand = "AFU / 沃莱",
        category = "专有协议",
        description = "AFU 0xAC 协议帧（含 0x68 基准偏移），0xFFB0 服务，需发送 0xFD 握手指令激活",
        wakeGuidance = "站上秤面唤醒，设备广播名称通常包含 AFU、WL-TZ 或 TZ-A1",
        protocolName = "AFU"
    ),
    PHICOMM_S7(
        id = "PHICOMM_S7",
        displayName = "斐讯 S7 / S7 PE (Wi-Fi 局域网版)",
        brand = "斐讯 Phicomm",
        category = "Wi-Fi 秤",
        description = "UDP 端口 10181 局域网广播协议，无需蓝牙连接，测量数据实时推送",
        wakeGuidance = "确保手机与秤连接在同一局域网 Wi-Fi 下，站在秤上稳定后自动推送到 App",
        protocolName = "Phicomm S7 Wi-Fi"
    ),
    PHICOMM_S9(
        id = "PHICOMM_S9",
        displayName = "斐讯 S9 (蓝牙体脂秤)",
        brand = "斐讯 Phicomm",
        category = "蓝牙秤",
        description = "斐讯 S9 / zS7 专用蓝牙广播与通知协议",
        wakeGuidance = "踩秤唤醒，蓝牙广播名为 S9 或 zS7",
        protocolName = "Phicomm S9"
    ),
    SIG_STANDARD(
        id = "SIG_STANDARD",
        displayName = "蓝牙国际通用标准秤 (SIG WSS/BCS)",
        brand = "国际标准",
        category = "通用标准",
        description = "蓝牙技术联盟 SIG 标准 Weight Scale (0x181D) / Body Composition (0x181B) 服务",
        wakeGuidance = "符合蓝牙 SIG 标准的医疗/健康秤，踩秤唤醒后自动协商标准 GATT",
        protocolName = "Bluetooth SIG Standard"
    ),
    OTHER_GENERIC(
        id = "OTHER_GENERIC",
        displayName = "其它白牌 / 定制蓝牙秤",
        brand = "其它品牌",
        category = "通用标准",
        description = "针对未在已知列表中的各类第三方与定制电子秤，自适应解析广播与透传报文",
        wakeGuidance = "轻踩秤面使蓝牙广播启动，确保手机蓝牙与定位权限已开启",
        protocolName = "Generic Scale"
    );

    companion object {
        /**
         * 根据持久化存储的 ID 还原对应的枚举项；若为 null 或未匹配则回退到 [AUTO]。
         */
        fun fromId(id: String?): ScaleModel {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }

        /**
         * 获取所有可用的分类列表（用于 UI 分类选项卡或分组展示）。
         */
        fun categories(): List<String> {
            return listOf("推荐", "热门品牌", "主流方案", "专有协议", "Wi-Fi 秤", "蓝牙秤", "通用标准")
        }

        /**
         * 按分类分组的所有型号映射表（保持预定义分类顺序）。
         */
        fun grouped(): Map<String, List<ScaleModel>> {
            val order = categories()
            val groups = entries.groupBy { it.category }
            val sortedMap = LinkedHashMap<String, List<ScaleModel>>()
            for (cat in order) {
                groups[cat]?.let { sortedMap[cat] = it }
            }
            groups.forEach { (k, v) ->
                if (!sortedMap.containsKey(k)) sortedMap[k] = v
            }
            return sortedMap
        }
    }
}
