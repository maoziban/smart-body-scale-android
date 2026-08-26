# 智能体脂秤 Android 客户端 (Smart Body Scale Android)

这是一个基于 Android 平台开发的**智能体脂秤客户端应用**。项目采用现代化的 **Jetpack Compose** 声明式 UI 框架构建，通过 **低功耗蓝牙 (BLE)** 与体脂秤进行实时数据通信，解析专有硬件协议，利用 BIA（生物电阻抗分析）算法计算并记录用户的各项身体指标（体重、体脂率、BMI、水分、肌肉量、蛋白质、骨量等）。

ios版项目地址https://github.com/maoziban/smart-body-scale-IOS

---

## 🌟 功能特性

- ⚡ **极速蓝牙连接**：支持 BLE 低功耗蓝牙扫描与自动重连，连接后自动申请高优先级连接模式（`CONNECTION_PRIORITY_HIGH`），确保服务搜索和特征订阅零延迟。
- 📊 **多维身体指标计算 (BIA)**：
  - 测得阻抗时，使用双频阻抗算法高精度计算身体成分。
  - 穿袜子或阻抗测得为 0 时，自动无缝降级为基于身高、年龄、性别的智能估算算法，确保称重必定有数据反馈。
- 👥 **多成员家庭管理**：
  - 支持添加、删除家庭成员，**免去烦琐的体重估算输入**。
  - **首测智能绑定**：新成员首次称重时，系统会自动捕获并将实际体重绑定为该成员的参考体重。
  - **动态权重匹配**：后续测量时，系统会自动在 $\pm 7\text{kg}$ 的范围内动态匹配最接近的家庭成员。
- 📅 **历史测量档案与详情页**：
  - 提供清爽的测量历史卡片流，支持一键删除单次记录。
  - **二级测量详情页**：点击卡片可平滑切入二级详情界面，以专业的指标网格完整展示多达 8 项身体参数及其达标分析。
- 🎨 **极简科技风 UI 设计**：
  - 全站界面基于 Material Design 3 标准，配合动感渐变色与平滑动效。
  - 摒弃了过大的圆形弯角，全站统一收紧为直观干练的 `8.dp` 现代微圆角风格，视觉更加精致、高级。

---

## 🛠️ 技术栈

- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **异步与流式编程**：Kotlin Coroutines & Flow (StateFlow / SharedFlow)
- **本地数据库**：Room Database (支持自动建表与实体映射转换)
- **导航组件**：Jetpack Navigation Compose (支持带参数的二级页面深度跳转)
- **蓝牙通信**：Android BLE Core APIs (基于 GATT 协议开发，高度定制化 PacketParser)

---

## 📡 蓝牙硬件报文协议

本客户端通过订阅 `FFB2` 蓝牙特征通知的 20 字节原始十六进制报文（例：`AC 29 00 69 40 82 02 00 05 40 ...`）进行实时动态解析：

| 字节偏移 (Index) | 格式说明 | 协议解析规则 |
| :--- | :--- | :--- |
| **第 0 字节** | 报文头 | 必须为魔数 `0xAC` 才会进行数据解析 |
| **第 1-6 字节** | MAC 地址 | 体脂秤设备的物理 MAC 地址 |
| **第 6 字节** | 稳定标志位 | `0x02` 表示体重数值锁定且稳定，其它表示称重中 |
| **第 3-5 字节** | 体重数值（分段编码） | 见下方「体重数值解码」说明 |
| **第 8-9 字节** | 电阻抗 (BIA) | Big Endian 格式双字节阻抗值（例：`0x0540` = $1344\,\Omega$） |

### 体重数值解码

取 24 位大端原始值 $raw = (raw[3] \ll 16)\ |\ (raw[4] \ll 8)\ |\ raw[5]$，按档位分段解码（**两段步长均为 $1000$，仅零点不同**）：

- **段 1（低量程，`raw[3]` 为 `0x68`~`0x6C`，约 0–100 kg）**：
  $$
  \text{重量(kg)} = \frac{raw - 0x680000}{1000.0}
  $$
  等价于 README 原始公式 $\frac{(raw[3]-0x68)\times 65536\ +\ raw[4]\times 256\ +\ raw[5]}{1000.0}$（当 `raw[3]==0x68` 时即 $(raw[4]<<8 | raw[5]) / 1000$）。

- **段 2（高量程，`raw[3] == 0x6D`，约 ≥100 kg）**：
  $$
  \text{重量(kg)} = \frac{raw - 0x6C0000}{1000.0}
  $$

> 段 2 依据真实抓包校准：`0x6D8894`→100.5 kg、`0x6D8C18`→101.4 kg、`0x6D8F9C`→102.3 kg、`0x6D9578`→103.8 kg。若用旧公式（段 1 零点 `0x680000`）解码段 2 报文会得到 360 kg+ 的错误值。

---

## 📂 项目结构

```text
app/src/main/java/com/example/dianzicheng/
│
├── data/
│   ├── ble/              # 蓝牙连接中心 (BleScaleClient) 与报文解析器 (AFUPacketParser)
│   ├── local/            # Room 数据库配置 (ScaleDao, Entities, AppDatabase) 与实体转换器
│   └── repository/       # 统一数据仓库 (ScaleRepository) 负责数据匹配与持久化
│
├── domain/               # 业务实体 (Models) 及 BIA 身体指标核心算法 (BodyAlgorithm)
│
└── ui/                   # 主页 (DashboardScreen)、历史 (HistoryScreen)、我的 (ProfileScreen)、
                          # 配对 (PairingScreen) 及对应的 ViewModel 和 Theme 主题包
```

---

## 🚀 快速上手与运行

1. **准备环境**：
   - 确保安装了 Android Studio Jellyfish 或更高版本。
   - Android SDK 必须包含 API 31+ (Android 12+)，以支持全新的 `neverForLocation` 蓝牙无定位权限扫描特性。
2. **下载并构建**：
   - 在 Android Studio 中导入该项目。
   - 等待 Gradle 同步完成，点击 `Sync Project with Gradle Files`。
3. **部署运行**：
   - 准备一台拥有蓝牙功能的 Android 真机（开启手机蓝牙）。
   - 将真机连接至电脑，并在 Android Studio 中点击 **Run** 按钮。
   - 应用内置了 `LaunchedEffect` 自动寻秤扫描，开启应用后直接站上体脂秤即可开始体验极速称重与体脂分析！
