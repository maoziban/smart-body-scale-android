package com.example.dianzicheng.data.health

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.example.dianzicheng.domain.BodyMeasurement
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient: HealthConnectClient? by lazy {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    val permissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class)
    )

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    suspend fun writeMeasurement(measurement: BodyMeasurement): Boolean {
        val client = healthConnectClient ?: run {
            Log.w("HealthConnectManager", "Health Connect Client is unavailable")
            return false
        }

        return try {
            val instant = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
            val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)

            val weightMetadata = if (measurement.id.isNotBlank()) {
                Metadata(clientRecordId = "weight_${measurement.id}")
            } else {
                Metadata()
            }

            val weightRecord = WeightRecord(
                weight = Mass.kilograms(measurement.weightKg),
                time = instant,
                zoneOffset = zoneOffset,
                metadata = weightMetadata
            )

            val records = mutableListOf<androidx.health.connect.client.records.Record>(weightRecord)

            if (measurement.bodyFatPct > 0.0) {
                val fatMetadata = if (measurement.id.isNotBlank()) {
                    Metadata(clientRecordId = "fat_${measurement.id}")
                } else {
                    Metadata()
                }
                val fatRecord = BodyFatRecord(
                    percentage = Percentage(measurement.bodyFatPct),
                    time = instant,
                    zoneOffset = zoneOffset,
                    metadata = fatMetadata
                )
                records.add(fatRecord)
            }

            client.insertRecords(records)
            Log.d("HealthConnectManager", "Successfully written measurement to Health Connect")
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to write measurement to Health Connect", e)
            false
        }
    }

    /**
     * 从 Health Connect 删除与本地记录同一时刻写入的体重/体脂数据。
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
                try {
                    client.deleteRecords(
                        recordType = WeightRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = listOf("weight_${measurement.id}")
                    )
                } catch (e: Exception) {
                    Log.w("HealthConnectManager", "deleteRecords by clientRecordId weight failed", e)
                }
                try {
                    client.deleteRecords(
                        recordType = BodyFatRecord::class,
                        recordIdsList = emptyList(),
                        clientRecordIdsList = listOf("fat_${measurement.id}")
                    )
                } catch (e: Exception) {
                    Log.w("HealthConnectManager", "deleteRecords by clientRecordId fat failed", e)
                }
            }

            // 2. 兜底：若具备读权限，通过时间戳匹配清理可能无 clientRecordId 的历史记录
            try {
                val window = TimeRangeFilter.between(instant.minusMillis(1000), instant.plusMillis(1000))
                val weightResponse = client.readRecords(
                    ReadRecordsRequest(recordType = WeightRecord::class, timeRangeFilter = window)
                )
                val weightIds = weightResponse.records
                    .filter { it.time == instant && it.zoneOffset == zoneOffset }
                    .map { it.metadata.id }
                    .toList()
                if (weightIds.isNotEmpty()) {
                    client.deleteRecords(
                        recordType = WeightRecord::class,
                        recordIdsList = weightIds,
                        clientRecordIdsList = emptyList()
                    )
                }

                val fatResponse = client.readRecords(
                    ReadRecordsRequest(recordType = BodyFatRecord::class, timeRangeFilter = window)
                )
                val fatIds = fatResponse.records
                    .filter { it.time == instant && it.zoneOffset == zoneOffset }
                    .map { it.metadata.id }
                    .toList()
                if (fatIds.isNotEmpty()) {
                    client.deleteRecords(
                        recordType = BodyFatRecord::class,
                        recordIdsList = fatIds,
                        clientRecordIdsList = emptyList()
                    )
                }
            } catch (e: Exception) {
                // 无读权限或时间查询失败，跳过时间戳兜底
                Log.d("HealthConnectManager", "Timestamp-based cleanup skipped: ${e.message}")
            }

            Log.d("HealthConnectManager", "Deleted weight and fat records from Health Connect")
            true
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to delete measurement from Health Connect", e)
            false
        }
    }

    /**
     * 批量将历史数据同步到 Health Connect（供小米健康等 App 读取）
     * @return Pair(成功条数, 失败条数)
     */
    suspend fun batchWriteMeasurements(measurements: List<BodyMeasurement>): Pair<Int, Int> {
        val client = healthConnectClient ?: return Pair(0, measurements.size)
        if (!hasAllPermissions()) return Pair(0, measurements.size)

        var success = 0
        var failed = 0

        // 每批最多 20 条，避免单次请求过大
        measurements.chunked(20).forEach { batch ->
            try {
                val records = mutableListOf<androidx.health.connect.client.records.Record>()
                batch.forEach { measurement ->
                    val instant = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
                    val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)
                    val weightMetadata = if (measurement.id.isNotBlank()) {
                        Metadata(clientRecordId = "weight_${measurement.id}")
                    } else {
                        Metadata()
                    }
                    records.add(
                        WeightRecord(
                            weight = Mass.kilograms(measurement.weightKg),
                            time = instant,
                            zoneOffset = zoneOffset,
                            metadata = weightMetadata
                        )
                    )
                    if (measurement.bodyFatPct > 0.0) {
                        val fatMetadata = if (measurement.id.isNotBlank()) {
                            Metadata(clientRecordId = "fat_${measurement.id}")
                        } else {
                            Metadata()
                        }
                        records.add(
                            BodyFatRecord(
                                percentage = Percentage(measurement.bodyFatPct),
                                time = instant,
                                zoneOffset = zoneOffset,
                                metadata = fatMetadata
                            )
                        )
                    }
                }
                client.insertRecords(records)
                success += batch.size
                Log.d("HealthConnectManager", "Batch written ${batch.size} records")
            } catch (e: Exception) {
                Log.e("HealthConnectManager", "Batch write failed", e)
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

