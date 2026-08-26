# AGENTS.md — smart-body-scale-android

智能体脂秤 Android 客户端。Kotlin + Jetpack Compose + Room + BLE，通过 BIA 算法计算体重 / 体脂 / BMI / 水分 / 肌肉 / 蛋白质 / 骨量等指标，支持多家庭成员管理与首测智能绑定。

---

## Project

- **模块**: 单模块 `:app`（namespace `com.example.dianzicheng`，package `com.example.dianzicheng`）。
- **SDK**: `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`, Java 11。
- **构建**: AGP `9.3.1`, Kotlin `2.2.10`, Compose BOM `2026.02.01`, KSP `2.3.2`, Room `2.8.4`, Navigation Compose `2.8.5`, Health Connect `1.1.0-alpha10`, OkHttp `4.12.0`。
- **入口**: `app/src/main/AndroidManifest.xml` → `com.example.dianzicheng.MainActivity`，以 `setContent { 电子秤Theme { MainScreen(...) } }` 启动 Compose。
- **BLE 协议魔数**: `0xAC` 头 + 20 字节帧，订阅特征 `0000FFB2-...`，解析见 `AFUPacketParser`；阻抗 = `0x0000`（或袜子场景）时降级使用身高/年龄/性别估算公式。

## Commands

- `./gradlew assembleDebug` — 构建 debug APK。
- `./gradlew assembleRelease` — 构建 release（`isMinifyEnabled = false`，无签名配置）。
- `./gradlew test` — 跑 JVM 单测（`app/src/test/`，目前仅含样板 `ExampleUnitTest`）。
- `./gradlew connectedAndroidTest` — 跑 instrumentation 测试（需真机/模拟器）。
- `./gradlew lint` / `./gradlew lintDebug` — Android Lint。
- 在 Android Studio 中使用 **Run** 部署到带 BLE 的真机（清单已声明 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `ACCESS_FINE_LOCATION`，运行时动态申请）。
- 版本目录统一在 `gradle/libs.versions.toml`；新增 lib 必须先在这里加 `[versions]` / `[libraries]` / `[plugins]` 条目再用 `libs.xxx` 引用。

## Architecture

包结构 `com.example.dianzicheng.{data, domain, ui}`：

- **`data/ble/`** — BLE 通信层。
  - `BleScaleClient`：封装 `BluetoothAdapter` / `BluetoothGatt`，对外暴露 `connectionState / weight / isStable / impedance / discoveredDevice` 五个 `StateFlow`；含 `lastPairedMac` 重连、`onMacDiscovered` 回调。
  - `AFUPacketParser`：20 字节 GATT 通知 → `BodyMeasurement` 原始数据。
- **`data/local/`** — Room 持久化。
  - `Entities`：`MemberEntity`（`members` 表）+ `MeasurementEntity`（`measurements` 表）。
  - `ScaleDao`、`AppDatabase`（库名 `scale-db`）、`Converters`、`Mappers`、`PreferenceManager`（DataStore Preferences，保存配对 MAC、`isPairingComplete` 等）。
- **`data/repository/`** — `ScaleRepository`（测量记录 + 成员动态匹配 ±7kg）、`ProfileRepository`（成员 CRUD）。
- **`domain/`** — 纯 Kotlin 业务层：`Models.kt`（`Sex`, `BodyMeasurement`, ...），`BodyAlgorithm.calculate(...)`（双频阻抗 BIA + 估算降级）。
- **`ui/`** — Compose 屏幕 + ViewModel：`MainScreen`（导航宿主）、`DashboardScreen`（称重）、`HistoryScreen` + `HistoryViewModel`（历史卡片流 + 详情二级页）、`ProfileScreen` + `ProfileViewModel`（家庭成员管理）、`PairingScreen`（首次配对）、`ScaleViewModel`、`ScaleUiState`、主题包 `theme/{Color,Theme,Type}.kt`。

ViewModel 通过 `ViewModelProvider.Factory` 在 `MainActivity.onCreate` 内手工注入，不使用 Hilt。

## Conventions

- **资源/UI 字符串**: 文案写在 `app/src/main/res/values/strings.xml`，主题用 `@style/Theme.电子秤`，Composable 顶层 `电子秤Theme { ... }` 包裹。
- **Compose 圆角**: 全站统一 `8.dp`，不要用大圆角（README 已声明）。
- **状态层**: UI 层用 `StateFlow` / `SharedFlow` + `collectAsState`；Repository 返回 `Flow`，不要在 ViewModel 里手动 `setState`。
- **算法纯函数**: BIA / 估算逻辑放 `domain/BodyAlgorithm`，保持无 Android 依赖、可 JVM 单测。
- **BLE 解析魔数**: 修改 `AFUPacketParser` 必须保留 `0xAC` 头校验 + 稳定标志位 `0x02` 语义。
- **依赖**: 新依赖走 `libs.versions.toml`，不要在 `app/build.gradle.kts` 里写死版本号。
- **样式**: `kotlin.code.style=official`（在 `gradle.properties`）。
- **权限**: BLE / 定位权限运行时申请逻辑集中在 `MainActivity.checkPermissions()`，新增运行时权限在这里统一处理。
- **不要提交**: `local.properties`、`.idea/`、`/build`、`/captures`、`.cxx` 已在 `.gitignore`。

## Notes

- **⚠️ 构建断点**: `MainActivity.kt` 引入了 `com.example.dianzicheng.data.backup.WebDavManager` 与 `com.example.dianzicheng.data.health.HealthConnectManager`，但 `app/src/main/java/com/example/dianzicheng/data/` 下**不存在** `backup/` 和 `health/` 子包 —— 当前工程**无法编译**。补齐这两个文件是恢复构建的前置条件。
- `app/src/main/keepRules/rules.keep` 存在但当前 `release` 构建未启用 minify（`isMinifyEnabled = false`），keep 规则暂时无效。
- iOS 对照项目：https://github.com/maoziban/smart-body-scale-IOS（README 提到的姊妹仓库）。
- 没有 CI 配置；没有 `release` 签名配置；版本号硬编码为 `versionCode = 3` / `versionName = "1.2"`。