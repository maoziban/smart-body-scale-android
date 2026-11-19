package com.example.dianzicheng.data.health

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.BodyMeasurement
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    companion object {
        /** 身体测量 5 大项写入权限：体重、体脂率、去脂体重、骨量、身体水分量 */
        val WRITE_PERMISSIONS = setOf(
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getWritePermission(BodyFatRecord::class),
            HealthPermission.getWritePermission(LeanBodyMassRecord::class),
            HealthPermission.getWritePermission(BoneMassRecord::class),
            HealthPermission.getWritePermission(BodyWaterMassRecord::class)
        )

        /** 身体测量 5 大项完整读写权限集合 */
        val ALL_PERMISSIONS = WRITE_PERMISSIONS + setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class)
        )
    }

    private val healthConnectClient: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    val permissions = ALL_PERMISSIONS

    /** 写入所需的全部 5 大项权限集合 */
    private val writePermissions = WRITE_PERMISSIONS

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    /** 返回不可用原因（用于提示用户），可用时返回 null。 */
    fun unavailableReason(): String? = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> null
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
            "Health Connect 版本过旧，请先在应用商店更新 Health Connect"
        else -> "当前设备未安装或不支持 Health Connect"
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    /** 是否已具备全部 5 大项身体测量指标的写入权限 */
    suspend fun hasAllWritePermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            client.permissionController.getGrantedPermissions().containsAll(writePermissions)
        } catch (e: Exception) {
            AppLogger.e("HealthConnect", "查询权限失败: ${e.message}")
            false
        }
    }

    /** 是否已具备基础写入权限（至少授予了体重写入权限即可写入已授权的指标）。 */
    suspend fun hasWritePermissions(): Boolean {
        val client = healthConnectClient ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            HealthPermission.getWritePermission(WeightRecord::class) in granted
        } catch (e: Exception) {
            AppLogger.e("HealthConnect", "查询权限失败: ${e.message}")
            false
        }
    }

    /**
     * 构造带稳定 clientRecordId 的元数据，并标记数据来自体重秤设备。
     *
     * clientRecordVersion 取当前时间：相同 clientRecordId 的记录只有在新版本号更大时才会覆盖旧记录，
     * 默认版本号 0 会导致重算后再次同步被 Health Connect 静默忽略。
     */
    private fun buildMetadata(prefix: String, id: String): Metadata {
        val device = Device(type = Device.TYPE_SCALE, manufacturer = "BodyScale", model = "BLE")
        return if (id.isNotBlank()) {
            Metadata(
                clientRecordId = "${prefix}_$id",
                clientRecordVersion = System.currentTimeMillis(),
                device = device,
                recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED
            )
        } else {
            Metadata(device = device, recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED)
        }
    }

    /**
     * 根据单条测量记录与当前已授予的权限，构造可写入的身体测量 5 大项记录：
     * 1. 体重 (WeightRecord)
     * 2. 体脂率 (BodyFatRecord)
     * 3. 去脂体重 / 瘦体重 (LeanBodyMassRecord)
     * 4. 骨量 (BoneMassRecord)
     * 5. 身体水分量 (BodyWaterMassRecord)
     */
    private fun buildRecordsForMeasurement(
        measurement: BodyMeasurement,
        grantedPermissions: Set<String>
    ): List<Record> {
        val instant = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
        val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)
        val records = mutableListOf<Record>()

        // 1. 体重 (WeightRecord)
        if (measurement.weightKg > 0.0 &&
            HealthPermission.getWritePermission(WeightRecord::class) in grantedPermissions
        ) {
            records.add(
                WeightRecord(
                    time = instant,
                    zoneOffset = zoneOffset,
                    weight = Mass.kilograms(measurement.weightKg),
                    metadata = buildMetadata("weight", measurement.id)
                )
            )
        }

        if (measurement.bodyFatPct > 0.0) {
            // 2. 体脂率 (BodyFatRecord)
            if (HealthPermission.getWritePermission(BodyFatRecord::class) in grantedPermissions) {
                records.add(
                    BodyFatRecord(
                        time = instant,
                        zoneOffset = zoneOffset,
                        percentage = Percentage(measurement.bodyFatPct.coerceIn(0.0, 100.0)),
                        metadata = buildMetadata("fat", measurement.id)
                    )
                )
            }

            // 3. 去脂体重 / 瘦体重 (LeanBodyMassRecord)
            val leanBodyMassKg = if (measurement.muscleKg > 0.0 && measurement.boneMassKg > 0.0) {
                measurement.muscleKg + measurement.boneMassKg
            } else {
                (measurement.weightKg * (1.0 - measurement.bodyFatPct / 100.0)).coerceAtLeast(0.0)
            }
            if (leanBodyMassKg > 0.0 &&
                HealthPermission.getWritePermission(LeanBodyMassRecord::class) in grantedPermissions
            ) {
                records.add(
                    LeanBodyMassRecord(
                        time = instant,
                        zoneOffset = zoneOffset,
                        mass = Mass.kilograms(leanBodyMassKg),
                        metadata = buildMetadata("lean", measurement.id)
                    )
                )
            }

            // 4. 骨量 (BoneMassRecord)
            if (measurement.boneMassKg > 0.0 &&
                HealthPermission.getWritePermission(BoneMassRecord::class) in grantedPermissions
            ) {
                records.add(
                    BoneMassRecord(
                        time = instant,
                        zoneOffset = zoneOffset,
                        mass = Mass.kilograms(measurement.boneMassKg),
                        metadata = buildMetadata("bone", measurement.id)
                    )
                )
            }

            // 5. 身体水分量 (BodyWaterMassRecord)：水分质量(kg) = 体重(kg) * 水分率(%)
            val waterMassKg = measurement.weightKg * (measurement.waterPct / 100.0)
            if (measurement.waterPct > 0.0 && waterMassKg > 0.0 &&
                HealthPermission.getWritePermission(BodyWaterMassRecord::class) in grantedPermissions
            ) {
                records.add(
                    BodyWaterMassRecord(
                        time = instant,
                        zoneOffset = zoneOffset,
                        mass = Mass.kilograms(waterMassKg),
                        metadata = buildMetadata("water", measurement.id)
                    )
                )
            }
        }

        return records
    }

    suspend fun writeMeasurement(measurement: BodyMeasurement): Boolean {
        val client = healthConnectClient ?: run {
            Log.w("HealthConnectManager", "Health Connect Client is unavailable")
            return false
        }

        return try {
            val granted = client.permissionController.getGrantedPermissions()
            val records = buildRecordsForMeasurement(measurement, granted)
            if (records.isEmpty()) {
                AppLogger.w("HealthConnect", "未授予任何可写入的 Health Connect 权限")
                return false
            }

            client.insertRecords(records)
            Log.d("HealthConnectManager", "Successfully written ${records.size} records to Health Connect")
            AppLogger.i(
                "HealthConnect",
                "已写入 Health Connect (${records.size}项): 体重 ${"%.2f".format(measurement.weightKg)}kg, 体脂 ${"%.1f".format(measurement.bodyFatPct)}%"
            )
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to write measurement to Health Connect", e)
            AppLogger.e("HealthConnect", "写入 Health Connect 失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 从 Health Connect 删除与本地记录同一时刻写入的 5 大项身体测量数据。
     * 历史页删除记录时调用，保持两端数据一致。优先使用 clientRecordId 精确删除，
     * 同时保留时间戳匹配以兼容未分配 clientRecordId 的历史记录。
     */
    suspend fun deleteMeasurement(measurement: BodyMeasurement): Boolean {
        val client = healthConnectClient ?: run {
            Log.w("HealthConnectManager", "Health Connect Client is unavailable")
            return false
        }

        return try {
            val instant = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
            val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)

            // 1. 如果有本地 ID，优先直接使用 clientRecordId 删除（无需 READ 权限，快速且可靠）
            if (measurement.id.isNotBlank()) {
                val idMappings = listOf(
                    WeightRecord::class to "weight_${measurement.id}",
                    BodyFatRecord::class to "fat_${measurement.id}",
                    LeanBodyMassRecord::class to "lean_${measurement.id}",
                    BoneMassRecord::class to "bone_${measurement.id}",
                    BodyWaterMassRecord::class to "water_${measurement.id}"
                )
                for ((recordClass, clientId) in idMappings) {
                    try {
                        client.deleteRecords(
                            recordType = recordClass,
                            recordIdsList = emptyList(),
                            clientRecordIdsList = listOf(clientId)
                        )
                    } catch (e: Exception) {
                        Log.w("HealthConnectManager", "deleteRecords by clientRecordId $clientId failed", e)
                    }
                }
            }

            // 2. 兜底：若具备读权限，通过时间戳匹配清理可能无 clientRecordId 的历史记录
            try {
                val window = TimeRangeFilter.between(instant.minusMillis(1000), instant.plusMillis(1000))

                val weightIds = client.readRecords(
                    ReadRecordsRequest(recordType = WeightRecord::class, timeRangeFilter = window)
                ).records.filter { it.time == instant && it.zoneOffset == zoneOffset }.map { it.metadata.id }
                if (weightIds.isNotEmpty()) {
                    client.deleteRecords(WeightRecord::class, weightIds, emptyList())
                }

                val fatIds = client.readRecords(
                    ReadRecordsRequest(recordType = BodyFatRecord::class, timeRangeFilter = window)
                ).records.filter { it.time == instant && it.zoneOffset == zoneOffset }.map { it.metadata.id }
                if (fatIds.isNotEmpty()) {
                    client.deleteRecords(BodyFatRecord::class, fatIds, emptyList())
                }

                val leanIds = client.readRecords(
                    ReadRecordsRequest(recordType = LeanBodyMassRecord::class, timeRangeFilter = window)
                ).records.filter { it.time == instant && it.zoneOffset == zoneOffset }.map { it.metadata.id }
                if (leanIds.isNotEmpty()) {
                    client.deleteRecords(LeanBodyMassRecord::class, leanIds, emptyList())
                }

                val boneIds = client.readRecords(
                    ReadRecordsRequest(recordType = BoneMassRecord::class, timeRangeFilter = window)
                ).records.filter { it.time == instant && it.zoneOffset == zoneOffset }.map { it.metadata.id }
                if (boneIds.isNotEmpty()) {
                    client.deleteRecords(BoneMassRecord::class, boneIds, emptyList())
                }

                val waterIds = client.readRecords(
                    ReadRecordsRequest(recordType = BodyWaterMassRecord::class, timeRangeFilter = window)
                ).records.filter { it.time == instant && it.zoneOffset == zoneOffset }.map { it.metadata.id }
                if (waterIds.isNotEmpty()) {
                    client.deleteRecords(BodyWaterMassRecord::class, waterIds, emptyList())
                }
            } catch (e: Exception) {
                // 无读权限或时间查询失败，跳过时间戳兜底
                Log.d("HealthConnectManager", "Timestamp-based cleanup skipped: ${e.message}")
            }

            Log.d("HealthConnectManager", "Deleted body measurement records from Health Connect")
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to delete measurement from Health Connect", e)
            false
        }
    }

    /**
     * 批量将历史数据同步到 Health Connect（供外部健康 App 读取）
     * @return Pair(成功条数, 失败条数)
     */
    suspend fun batchWriteMeasurements(measurements: List<BodyMeasurement>): Pair<Int, Int> {
        val client = healthConnectClient ?: return Pair(0, measurements.size)
        if (!hasWritePermissions()) return Pair(0, measurements.size)

        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            return Pair(0, measurements.size)
        }

        var success = 0
        var failed = 0

        // 每批最多 20 条，避免单次请求过大
        measurements.chunked(20).forEach { batch ->
            try {
                val records = batch.flatMap { measurement ->
                    buildRecordsForMeasurement(measurement, granted)
                }
                if (records.isNotEmpty()) {
                    client.insertRecords(records)
                }
                success += batch.size
                Log.d("HealthConnectManager", "Batch written ${batch.size} measurements (${records.size} records)")
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Batch write failed", e)
                AppLogger.e("HealthConnect", "批量写入失败: ${e.javaClass.simpleName}: ${e.message}")
                failed += batch.size
            }
        }

        return Pair(success, failed)
    }

    /**
     * 检测小米健康是否已安装
     */
    fun isMiHealthInstalled(): Boolean {
        val miHealthPackages = listOf(
            "com.mi.health",
            "com.xiaomi.hm.health"
        )
        return miHealthPackages.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * 打开小米健康应用（引导用户在其中开启 Health Connect 数据源）
     */
    fun openMiHealth() {
        val miHealthPackages = listOf("com.mi.health", "com.xiaomi.hm.health")
        for (pkg in miHealthPackages) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                Log.w("HealthConnectManager", "Cannot open $pkg", e)
            }
        }
        // 如果找不到小米健康，打开应用市场搜索页
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.mi.health"))
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(marketIntent)
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Cannot open market", e)
        }
    }

    /**
     * 打开 Health Connect 设置页（让用户管理已授权 App）
     */
    fun openHealthConnectSettings() {
        try {
            val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("HealthConnectManager", "Cannot open ACTION_HEALTH_CONNECT_SETTINGS, trying fallback", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                try {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(webIntent)
                } catch (e3: Exception) {
                    Log.e("HealthConnectManager", "Cannot open Health Connect settings or play store", e3)
                }
            }
        }
    }
}

