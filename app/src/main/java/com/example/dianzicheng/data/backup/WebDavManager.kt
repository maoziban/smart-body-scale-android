package com.example.dianzicheng.data.backup

import android.util.Log
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.MemberEntity
import com.example.dianzicheng.data.local.MeasurementEntity
import com.example.dianzicheng.data.local.ScaleDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import com.example.dianzicheng.domain.Sex

class WebDavManager(private val dao: ScaleDao) {

    /**
     * 禁用 OkHttp 默认重定向：
     * 默认重定向在遇到 301/302（例如 http -> https 或 /dav -> /dav/）时，
     * 会将 PUT / MKCOL / PROPFIND 篡改为 GET 并丢弃请求体，且跨协议跳转时会剥离 Authorization 头。
     * 因此由 [executeWebDav] 手动跟踪重定向并完整保留 WebDAV 方法、请求体与认证头。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun executeWebDav(initialRequest: Request, maxRedirects: Int = 5): Response {
        var currentRequest = initialRequest
        var redirects = 0
        while (true) {
            val response = client.newCall(currentRequest).execute()
            if (response.code !in listOf(301, 302, 303, 307, 308) || redirects >= maxRedirects) {
                return response
            }
            val location = response.header("Location")
            if (location.isNullOrBlank()) {
                return response
            }
            val nextUrl = currentRequest.url.resolve(location) ?: return response
            response.close()
            currentRequest = currentRequest.newBuilder()
                .url(nextUrl)
                .method(currentRequest.method, currentRequest.body)
                .build()
            redirects++
        }
    }

    /**
     * 规范化用户输入的 WebDAV URL：
     * - 自动补齐 https:// 协议头
     * - 针对坚果云 (dav.jianguoyun.com)：
     *   1. 若未包含 /dav/ 前缀，自动补齐 /dav/
     *   2. 坚果云禁止直接在 /dav/ 根目录下存放文件，若用户仅填写了 https://dav.jianguoyun.com/dav/，
     *      自动补充默认子目录 BodyScale/scale_backup.json
     * - 若末尾不是 .json 文件名，自动拼接 /scale_backup.json
     */
    private fun normalizeUrl(url: String): String {
        var trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        val parsed = trimmed.toHttpUrlOrNull()
        if (parsed != null && parsed.host.equals("dav.jianguoyun.com", ignoreCase = true)) {
            val segments = parsed.pathSegments.filter { it.isNotEmpty() }
            val newBuilder = parsed.newBuilder().encodedPath("/")
            when {
                // 用户只填了 https://dav.jianguoyun.com 或 https://dav.jianguoyun.com/dav/
                segments.isEmpty() ||
                    (segments.size == 1 && segments[0].equals("dav", ignoreCase = true)) ||
                    (segments.size == 2 && segments[0].equals("dav", ignoreCase = true) && segments[1].endsWith(".json", ignoreCase = true)) -> {
                    val fileName = if (segments.size == 2 && segments[1].endsWith(".json", ignoreCase = true)) {
                        segments[1]
                    } else {
                        "scale_backup.json"
                    }
                    newBuilder.addPathSegment("dav")
                    newBuilder.addPathSegment("BodyScale")
                    newBuilder.addPathSegment(fileName)
                    return newBuilder.build().toString()
                }
                // 用户填了子目录但漏写了 /dav 前缀（例如 https://dav.jianguoyun.com/我的坚果云/）
                !segments[0].equals("dav", ignoreCase = true) -> {
                    newBuilder.addPathSegment("dav")
                    segments.forEach { newBuilder.addPathSegment(it) }
                    if (!segments.last().endsWith(".json", ignoreCase = true)) {
                        newBuilder.addPathSegment("scale_backup.json")
                    }
                    return newBuilder.build().toString()
                }
            }
        }

        return if (!trimmed.endsWith(".json", ignoreCase = true)) {
            if (trimmed.endsWith("/")) "${trimmed}scale_backup.json" else "$trimmed/scale_backup.json"
        } else {
            trimmed
        }
    }

    /**
     * 逐级 MKCOL 创建文件所在的父目录。
     * 注意：WebDAV 规范 (RFC 4918) 要求集合目录 URL 以 "/" 结尾，
     * 否则许多服务端（Nginx/Apache/坚果云/Nextcloud）会返回 301 重定向并被 OkHttp 转为 GET，导致创建失败。
     */
    private fun ensureParentDirs(fileUrl: String, credential: String) {
        val parsed = fileUrl.toHttpUrlOrNull() ?: return
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }.dropLast(1)
        val builder = parsed.newBuilder().encodedPath("/")
        for (seg in segments) {
            builder.addPathSegment(seg)
            // 仅跳过坚果云的 /dav 挂载根前缀
            if (parsed.host.equals("dav.jianguoyun.com", ignoreCase = true) &&
                seg.equals("dav", ignoreCase = true) &&
                builder.build().pathSegments.size == 1
            ) {
                continue
            }
            val dirUrlWithSlash = "${builder.build()}/"
            val req = Request.Builder()
                .url(dirUrlWithSlash)
                .header("Authorization", credential)
                .method("MKCOL", null)
                .build()
            try {
                executeWebDav(req).use { resp ->
                    Log.d("WebDavManager", "MKCOL $dirUrlWithSlash -> ${resp.code}")
                }
            } catch (e: Exception) {
                Log.e("WebDavManager", "MKCOL error", e)
            }
        }
    }

    /**
     * 当坚果云等服务端不允许通过 WebDAV 在 /dav/ 下直接新建顶层同步文件夹时，
     * 通过 PROPFIND Depth: 1 自动发现账号下已有的同步文件夹（例如 /dav/我的坚果云/），
     * 并在其下拼接 BodyScale/scale_backup.json。
     */
    private fun resolveFallbackFileUrl(originalFileUrl: String, credential: String): String? {
        val parsed = originalFileUrl.toHttpUrlOrNull() ?: return null
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }
        val baseDirUrl = if (parsed.host.equals("dav.jianguoyun.com", ignoreCase = true) && segments.isNotEmpty() && segments[0].equals("dav", ignoreCase = true)) {
            parsed.newBuilder().encodedPath("/dav/").build().toString()
        } else if (segments.size >= 2) {
            parsed.newBuilder().encodedPath("/${segments.first()}/").build().toString()
        } else {
            parsed.newBuilder().encodedPath("/").build().toString()
        }

        val firstSubFolder = findFirstSubFolder(baseDirUrl, credential) ?: return null
        val subFolderParsed = firstSubFolder.toHttpUrlOrNull() ?: return null
        val targetFileUrl = subFolderParsed.newBuilder()
            .addPathSegment("BodyScale")
            .addPathSegment("scale_backup.json")
            .build()
            .toString()
        AppLogger.i("WebDAV", "已自动定位可用同步目录: $targetFileUrl")
        return targetFileUrl
    }

    /**
     * 通过 PROPFIND (Depth: 1) 查询指定目录下的第一个可用子目录。
     */
    private fun findFirstSubFolder(baseDirUrl: String, credential: String): String? {
        val dirUrl = if (baseDirUrl.endsWith("/")) baseDirUrl else "$baseDirUrl/"
        val parsed = dirUrl.toHttpUrlOrNull() ?: return null
        val basePath = parsed.encodedPath
        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(dirUrl)
            .header("Authorization", credential)
            .header("Depth", "1")
            .method("PROPFIND", propfindBody)
            .build()
        return try {
            executeWebDav(req).use { resp ->
                if (resp.code != 207 && !resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val regex = Regex("""<(?:[a-zA-Z0-9]+:)?href[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?href>""", RegexOption.IGNORE_CASE)
                val hrefs = regex.findAll(xml).map { it.groupValues[1].trim() }.toList()
                val normalizedBase = if (basePath.endsWith("/")) basePath else "$basePath/"
                val subHref = hrefs.firstOrNull { href ->
                    val path = if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
                        href.toHttpUrlOrNull()?.encodedPath ?: href
                    } else {
                        href
                    }
                    val normalizedPath = if (path.endsWith("/")) path else "$path/"
                    normalizedPath != normalizedBase &&
                        normalizedPath.startsWith(normalizedBase) &&
                        !normalizedPath.removeSuffix("/").endsWith(".json", ignoreCase = true)
                } ?: return null

                if (subHref.startsWith("http://", true) || subHref.startsWith("https://", true)) {
                    if (subHref.endsWith("/")) subHref else "$subHref/"
                } else {
                    val pathWithSlash = if (subHref.endsWith("/")) subHref else "$subHref/"
                    parsed.newBuilder().encodedPath(pathWithSlash).build().toString()
                }
            }
        } catch (e: Exception) {
            Log.w("WebDavManager", "PROPFIND $dirUrl failed", e)
            null
        }
    }

    /**
     * 检查目标文件的父目录是否真实存在且可访问（PROPFIND Depth: 0）。
     */
    private fun checkParentDirStatus(fileUrl: String, credential: String): Int {
        val parsed = fileUrl.toHttpUrlOrNull() ?: return -1
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }.dropLast(1)
        val builder = parsed.newBuilder().encodedPath("/")
        segments.forEach { builder.addPathSegment(it) }
        val parentDirUrl = "${builder.build()}/"

        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(parentDirUrl)
            .header("Authorization", credential)
            .header("Depth", "0")
            .method("PROPFIND", propfindBody)
            .build()
        return try {
            executeWebDav(req).use { it.code }
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun testConnection(url: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (url.isBlank()) {
                return@withContext Result.failure(Exception("请填写 WebDAV 服务器地址"))
            }
            var targetUrl = normalizeUrl(url)
            val credential = Credentials.basic(user.trim(), pass)

            // 1. 尝试确保目标父目录存在
            ensureParentDirs(targetUrl, credential)
            var dirCode = checkParentDirStatus(targetUrl, credential)

            if (dirCode == 401 || dirCode == 403 || dirCode == 404 || dirCode == 409) {
                val fallback = resolveFallbackFileUrl(targetUrl, credential)
                if (fallback != null) {
                    targetUrl = fallback
                    ensureParentDirs(targetUrl, credential)
                    dirCode = checkParentDirStatus(targetUrl, credential)
                }
            }

            if (dirCode in 200..299 || dirCode == 207) {
                AppLogger.i("WebDAV", "连接测试成功，目标路径: $targetUrl")
                return@withContext Result.success("连接成功！配置已自动保存，可正常备份")
            }

            // 2. 兼容部分不支持 PROPFIND 的简易 WebDAV 服务端，退回 GET Range 探测
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", credential)
                .header("Range", "bytes=0-0")
                .get()
                .build()

            executeWebDav(request).use { response ->
                when {
                    response.isSuccessful || response.code == 206 -> {
                        Result.success("连接成功！服务器可用")
                    }
                    response.code == 401 || response.code == 403 || dirCode == 401 || dirCode == 403 -> {
                        Result.failure(Exception("认证失败或目录无写入权限，请检查账号、密码及同步目录权限 (HTTP ${response.code})"))
                    }
                    dirCode == 404 || dirCode == 409 || response.code == 404 -> {
                        Result.failure(Exception("目标 WebDAV 路径不存在 (HTTP 404)，请检查服务器地址及目录路径是否正确"))
                    }
                    else -> {
                        Result.failure(Exception("连接失败，HTTP 状态码: ${response.code}"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebDavManager", "Test connection error", e)
            Result.failure(Exception("连接错误: ${e.localizedMessage ?: "未知错误"}"))
        }
    }

    suspend fun backupData(url: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            var targetUrl = normalizeUrl(url)
            val credential = Credentials.basic(user.trim(), pass)

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
                obj.put("ageUsed", m.ageUsed)
                obj.put("heightCmUsed", m.heightCmUsed)
                obj.put("algorithmId", m.algorithmId)
                obj.put("recalcCount", m.recalcCount)
                obj.put("lastRecalcAtEpochMs", m.lastRecalcAtEpochMs)
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

            var code = executeWebDav(request).use { it.code }
            // 404/409/403：父目录不存在或根目录不允许直接写文件，尝试带尾斜杠 MKCOL 创建父目录后重试
            if (code == 404 || code == 409 || code == 403) {
                ensureParentDirs(targetUrl, credential)
                val retry = Request.Builder()
                    .url(targetUrl)
                    .header("Authorization", credential)
                    .put(requestBody)
                    .build()
                code = executeWebDav(retry).use { it.code }
            }
            // 若仍为 404/409/403（如根目录不允许直接写文件），自动发现已有子目录并写入
            if (code == 404 || code == 409 || code == 403) {
                val fallbackUrl = resolveFallbackFileUrl(targetUrl, credential)
                if (fallbackUrl != null && fallbackUrl != targetUrl) {
                    targetUrl = fallbackUrl
                    ensureParentDirs(targetUrl, credential)
                    val fallbackReq = Request.Builder()
                        .url(targetUrl)
                        .header("Authorization", credential)
                        .put(requestBody)
                        .build()
                    code = executeWebDav(fallbackReq).use { it.code }
                }
            }
            if (code in 200..299) {
                AppLogger.i("WebDAV", "备份成功: $targetUrl (HTTP $code)")
                Result.success("备份成功！已备份 ${membersList.size} 位成员及 ${measurementsList.size} 条测量记录")
            } else if (code == 404 || code == 409) {
                AppLogger.e("WebDAV", "备份失败 (HTTP $code): $targetUrl")
                Result.failure(Exception("备份上传失败 (HTTP $code)：目标路径不存在 ($targetUrl)"))
            } else if (code == 401 || code == 403) {
                AppLogger.e("WebDAV", "备份权限错误 (HTTP $code): $targetUrl")
                Result.failure(Exception("备份上传被拒绝 (HTTP $code)：请确认账号密码具有该目录的写入权限"))
            } else {
                AppLogger.e("WebDAV", "备份失败 (HTTP $code): $targetUrl")
                Result.failure(Exception("备份上传失败，HTTP 状态码: $code"))
            }
        } catch (e: Exception) {
            Log.e("WebDavManager", "Backup error", e)
            Result.failure(Exception("备份错误: ${e.localizedMessage ?: "未知错误"}"))
        }
    }

    suspend fun restoreData(url: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = normalizeUrl(url)
            val credential = Credentials.basic(user.trim(), pass)

            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", credential)
                .get()
                .build()

            var response = executeWebDav(request)
            if (response.code == 404) {
                response.close()
                val fallbackUrl = resolveFallbackFileUrl(targetUrl, credential)
                if (fallbackUrl != null && fallbackUrl != targetUrl) {
                    val fallbackReq = Request.Builder()
                        .url(fallbackUrl)
                        .header("Authorization", credential)
                        .get()
                        .build()
                    response = executeWebDav(fallbackReq)
                } else {
                    return@withContext Result.failure(Exception("恢复失败，服务器上尚未找到备份文件 (HTTP 404)"))
                }
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("恢复失败，找不到备份文件或 HTTP 状态码: ${resp.code}"))
                }

                val jsonString = resp.body?.string() ?: return@withContext Result.failure(Exception("恢复失败，服务器返回空数据"))
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
                            memberNameSnapshot = if (obj.isNull("memberNameSnapshot")) null else obj.optString("memberNameSnapshot").takeIf { it.isNotBlank() },
                            ageUsed = obj.optInt("ageUsed", 0),
                            heightCmUsed = obj.optDouble("heightCmUsed", 0.0),
                            algorithmId = obj.optString("algorithmId", ""),
                            recalcCount = obj.optInt("recalcCount", 0),
                            lastRecalcAtEpochMs = obj.optLong("lastRecalcAtEpochMs", 0L)
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
