package com.example.dianzicheng.data.backup

import android.util.Log
import com.example.dianzicheng.data.local.MemberEntity
import com.example.dianzicheng.data.local.MeasurementEntity
import com.example.dianzicheng.data.local.ScaleDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import com.example.dianzicheng.domain.Sex

class WebDavManager(private val dao: ScaleDao) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (!trimmed.endsWith(".json")) {
            if (trimmed.endsWith("/")) "${trimmed}scale_backup.json" else "$trimmed/scale_backup.json"
        } else {
            trimmed
        }
    }

    suspend fun testConnection(url: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = normalizeUrl(url)
            val credential = Credentials.basic(user, pass)

            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", credential)
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 404) {
                    Result.success("连接成功！服务器可用")
                } else {
                    Result.failure(Exception("连接失败，HTTP 状态码: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e("WebDavManager", "Test connection error", e)
            Result.failure(Exception("连接错误: ${e.localizedMessage ?: "未知错误"}"))
        }
    }

    suspend fun backupData(url: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = normalizeUrl(url)
            val credential = Credentials.basic(user, pass)

            val membersList = dao.getMembersList()
            val measurementsList = dao.getAllMeasurementsList()

            val jsonRoot = JSONObject()
            jsonRoot.put("version", 1)
            jsonRoot.put("exportedAt", System.currentTimeMillis())

            val membersArray = JSONArray()
            membersList.forEach { m ->
                val obj = JSONObject()
                obj.put("id", m.id)
                obj.put("name", m.name)
                obj.put("sex", m.sex.name)
                obj.put("heightCm", m.heightCm)
                obj.put("birthDateEpochMs", m.birthDateEpochMs)
                obj.put("referenceWeightKg", m.referenceWeightKg)
                membersArray.put(obj)
            }
            jsonRoot.put("members", membersArray)

            val measurementsArray = JSONArray()
            measurementsList.forEach { m ->
                val obj = JSONObject()
                obj.put("id", m.id)
                obj.put("measuredAtEpochMs", m.measuredAtEpochMs)
                obj.put("weightKg", m.weightKg)
                obj.put("impedanceOhm", m.impedanceOhm)
                obj.put("bmi", m.bmi)
                obj.put("bodyFatPct", m.bodyFatPct)
                obj.put("muscleKg", m.muscleKg)
                obj.put("waterPct", m.waterPct)
                obj.put("proteinPct", m.proteinPct)
                obj.put("boneMassKg", m.boneMassKg)
                obj.put("memberId", m.memberId ?: JSONObject.NULL)
                obj.put("memberNameSnapshot", m.memberNameSnapshot ?: JSONObject.NULL)
                measurementsArray.put(obj)
            }
            jsonRoot.put("measurements", measurementsArray)

            val jsonString = jsonRoot.toString(2)
            val requestBody = jsonString.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", credential)
                .put(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    Result.success("备份成功！已备份 ${membersList.size} 位成员及 ${measurementsList.size} 条测量记录")
                } else {
                    Result.failure(Exception("备份上传失败，HTTP 状态码: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e("WebDavManager", "Backup error", e)
            Result.failure(Exception("备份错误: ${e.localizedMessage ?: "未知错误"}"))
        }
    }

    suspend fun restoreData(url: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = normalizeUrl(url)
            val credential = Credentials.basic(user, pass)

            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", credential)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("恢复失败，找不到备份文件或 HTTP 状态码: ${response.code}"))
                }

                val jsonString = response.body?.string() ?: return@withContext Result.failure(Exception("恢复失败，服务器返回空数据"))
                val jsonRoot = JSONObject(jsonString)

                val membersArray = jsonRoot.optJSONArray("members") ?: JSONArray()
                val importedMembers = mutableListOf<MemberEntity>()
                for (i in 0 until membersArray.length()) {
                    val obj = membersArray.getJSONObject(i)
                    val sexStr = obj.optString("sex", "MALE")
                    val sexEnum = try { Sex.valueOf(sexStr) } catch (e: Exception) { Sex.MALE }
                    importedMembers.add(
                        MemberEntity(
                            id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            name = obj.optString("name").ifBlank { "未命名" },
                            sex = sexEnum,
                            heightCm = obj.optDouble("heightCm", 170.0),
                            birthDateEpochMs = obj.optLong("birthDateEpochMs", 946656000000L),
                            referenceWeightKg = obj.optDouble("referenceWeightKg", 0.0)
                        )
                    )
                }

                val measurementsArray = jsonRoot.optJSONArray("measurements") ?: JSONArray()
                val importedMeasurements = mutableListOf<MeasurementEntity>()
                for (i in 0 until measurementsArray.length()) {
                    val obj = measurementsArray.getJSONObject(i)
                    importedMeasurements.add(
                        MeasurementEntity(
                            id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            measuredAtEpochMs = obj.optLong("measuredAtEpochMs", System.currentTimeMillis()),
                            weightKg = obj.optDouble("weightKg", 0.0),
                            impedanceOhm = obj.optDouble("impedanceOhm", 0.0),
                            bmi = obj.optDouble("bmi", 0.0),
                            bodyFatPct = obj.optDouble("bodyFatPct", 0.0),
                            muscleKg = obj.optDouble("muscleKg", 0.0),
                            waterPct = obj.optDouble("waterPct", 0.0),
                            proteinPct = obj.optDouble("proteinPct", 0.0),
                            boneMassKg = obj.optDouble("boneMassKg", 0.0),
                            memberId = if (obj.isNull("memberId")) null else obj.optString("memberId").takeIf { it.isNotBlank() },
                            memberNameSnapshot = if (obj.isNull("memberNameSnapshot")) null else obj.optString("memberNameSnapshot").takeIf { it.isNotBlank() }
                        )
                    )
                }

                // 使用事务方法批量写入，确保成员与测量记录同时成功或同时回滚
                dao.restoreAll(importedMembers, importedMeasurements)

                Result.success("恢复成功！已导入 ${importedMembers.size} 位成员及 ${importedMeasurements.size} 条测量记录")
            }
        } catch (e: Exception) {
            Log.e("WebDavManager", "Restore error", e)
            Result.failure(Exception("恢复错误: ${e.localizedMessage ?: "未知错误"}"))
        }
    }
}
