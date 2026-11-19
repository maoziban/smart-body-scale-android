package com.example.dianzicheng.data.sync

import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Zepp Life (原小米运动 / 华米云) 云端数据同步管理器。
 *
 * 核心原理：
 * Zepp Life 作为小米运动早期的生态中枢，支持通过华米账号体系（Huami SSO）直接向云端同步体重与阻抗数据。
 *
 * 本管理器支持两种鉴权模式：
 * 1. 账号密码自动登录：通过 api-user.huami.com 换取 app_token 和 user_id
 * 2. 免密 Token 直连：直接填入已提取的 app_token 与 user_id（适合应对图形验证码）
 */
class ZeppLifeSyncManager(
    private val preferenceManager: PreferenceManager
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 专用于第一步获取 Access Code 的客户端：必须禁用重定向！
     * 因为华米认证接口通过 HTTP 303 重定向响应头的 Location 参数返回 access 授权码，
     * 若跟随重定向跳转至 AWS S3 静态页面会导致授权码丢失且无法解析。
     */
    private val noRedirectClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val defaultUserAgent = "MiFit/6.3.0 (Linux; Android 14; Mobile)"

    /** 记录最近一次密码错误的账号与密码摘要，防止相同错误密码重复请求触发华米风控 */
    @Volatile
    private var lastFailedCredentialKey: String? = null

    /**
     * 解析 URL 或查询字符串中的 Query 参数
     */
    internal fun parseQueryParams(urlOrQuery: String): Map<String, String> {
        val query = urlOrQuery.substringAfter("?", "")
            .substringBefore("#")
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else {
                val k = java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    /**
     * 测试连接并获取/刷新 Token
     *
     * @return 成功返回 Pair(userId, appToken)，失败抛出异常
     */
    suspend fun loginOrVerify(
        account: String,
        password: String,
        directToken: String,
        directUserId: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            // 模式 1：如果已存在 app_token 和 user_id，优先直接验证 token 有效性，避免频繁触发密码登录风控
            if (directToken.isNotBlank() && directUserId.isNotBlank()) {
                val valid = verifyToken(directUserId.trim(), directToken.trim())
                if (valid) {
                    return@withContext Result.success(Pair(directUserId.trim(), directToken.trim()))
                } else if (account.isBlank() || password.isBlank()) {
                    return@withContext Result.failure(Exception("AppToken 校验未通过或已过期，请重新获取"))
                }
                AppLogger.w("ZeppSync", "已保存的 AppToken 已过期，尝试使用账号密码重新刷新 Token...")
            }

            // 模式 2：使用账号密码登录换取 Token
            val cleanAccount = account.trim()
            if (cleanAccount.isBlank() || password.isBlank()) {
                return@withContext Result.failure(Exception("请填写 Zepp Life 账号和密码，或直接填写 AppToken"))
            }

            val credKey = "$cleanAccount:${password.hashCode()}"
            if (credKey == lastFailedCredentialKey) {
                return@withContext Result.failure(
                    Exception("该账号与密码组合刚刚验证失败，请先修改正确的密码后再试（避免连续重试触发华米 TooManyRequests 限制）")
                )
            }

            val isEmail = cleanAccount.contains("@")
            val formattedAccount = if (isEmail) {
                URLEncoder.encode(cleanAccount, "UTF-8")
            } else {
                val digits = cleanAccount.replace(" ", "").replace("-", "")
                when {
                    digits.startsWith("+") -> digits
                    digits.startsWith("86") && digits.length > 11 -> "+$digits"
                    else -> "+86$digits"
                }
            }

            // 第一步：请求 tokens 接口并在 303 重定向 Location 头中提取 access 授权码
            val loginUrl = "https://api-user.huami.com/registrations/$formattedAccount/tokens"
            val formBody = FormBody.Builder()
                .add("client_id", "HuaMi")
                .add("password", password)
                .add("redirect_uri", "https://s3-us-west-2.amazonaws.com/hm-registration/successsignin.html")
                .add("state", "REDIRECTION")
                .add("token", "access")
                .build()

            val request = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", defaultUserAgent)
                .post(formBody)
                .build()

            val response = noRedirectClient.newCall(request).execute()
            val location = response.header("Location") ?: ""
            val respBody = response.body?.string() ?: ""

            AppLogger.i("ZeppSync", "第一步鉴权响应: HTTP ${response.code}, Location=$location")

            val locationParams = parseQueryParams(location)
            val errorParam = locationParams["error"]

            if (response.code == 429 || errorParam == "TooManyRequests" || respBody.contains("TooManyRequests", ignoreCase = true)) {
                return@withContext Result.failure(
                    Exception(
                        "登录请求过于频繁 (TooManyRequests)：因多次尝试登录，华米服务器已临时限制当前网络 IP 或账号。" +
                        "请切换手机移动数据/Wi-Fi（更换 IP）或等待 15~30 分钟后再试，也可直接填写 AppToken 免密连接。"
                    )
                )
            }

            if (!errorParam.isNullOrBlank()) {
                if (errorParam == "-1002" || errorParam.contains("password", ignoreCase = true)) {
                    lastFailedCredentialKey = credKey
                    return@withContext Result.failure(Exception("账号或密码错误 (代码: $errorParam)，请核对密码后重试"))
                }
                return@withContext Result.failure(Exception("华米认证返回错误: $errorParam"))
            }

            val accessCode = locationParams["access"]
            val countryCode = locationParams["country_code"] ?: "CN"

            if (accessCode.isNullOrBlank()) {
                return@withContext Result.failure(
                    Exception("未获取到登录授权码 (HTTP ${response.code})，请确认账号格式（手机号或邮箱）与密码是否正确")
                )
            }

            // 登录成功获取授权码，清空错误密码拦截记录
            lastFailedCredentialKey = null

            // 第二步：使用 access 授权码向 account.huami.com/v2/client/login 换取 app_token 与 user_id
            val thirdNamesToTry = if (isEmail) listOf("email", "huami") else listOf("huami_phone", "huami")
            var lastLoginRespStr = ""

            for (thirdName in thirdNamesToTry) {
                val appTokenUrl = "https://account.huami.com/v2/client/login"
                val appTokenBody = FormBody.Builder()
                    .add("app_name", "com.xiaomi.hm.health")
                    .add("app_version", "6.3.0")
                    .add("code", accessCode)
                    .add("country_code", countryCode)
                    .add("device_id", "02:00:00:00:00:00")
                    .add("device_model", "android_phone")
                    .add("grant_type", "access_token")
                    .add("third_name", thirdName)
                    .add("allow_regist", "false")
                    .add("dn", "account.huami.com,api-user.huami.com,api-watch.huami.com,api-analytics.huami.com,app-analytics.huami.com,api-mifit.huami.com")
                    .build()

                val appTokenRequest = Request.Builder()
                    .url(appTokenUrl)
                    .header("User-Agent", defaultUserAgent)
                    .post(appTokenBody)
                    .build()

                val appTokenResp = client.newCall(appTokenRequest).execute()
                lastLoginRespStr = appTokenResp.body?.string() ?: ""

                if (lastLoginRespStr.startsWith("{")) {
                    val appTokenJson = JSONObject(lastLoginRespStr)
                    val tokenInfo = appTokenJson.optJSONObject("token_info")
                    if (tokenInfo != null) {
                        val appToken = tokenInfo.optString("app_token").ifBlank { tokenInfo.optString("login_token") }
                        val userId = tokenInfo.optString("user_id")
                        if (appToken.isNotBlank() && userId.isNotBlank()) {
                            AppLogger.i("ZeppSync", "Zepp Life 登录成功: userId=$userId, thirdName=$thirdName")
                            return@withContext Result.success(Pair(userId, appToken))
                        }
                    }
                }
            }

            Result.failure(Exception("换取 AppToken 失败: $lastLoginRespStr"))
        } catch (e: Exception) {
            AppLogger.e("ZeppSync", "Zepp Life 登录鉴权异常: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 校验 app_token 是否有效
     */
    private fun verifyToken(userId: String, appToken: String): Boolean {
        return try {
            val checkUrl = "https://api-mifit-cn.huami.com/users/$userId/bodyComposition"
            val request = Request.Builder()
                .url(checkUrl)
                .header("apptoken", appToken)
                .header("User-Agent", defaultUserAgent)
                .get()
                .build()

            val resp = client.newCall(request).execute()
            // 200 或 404 (无历史) 代表 token 鉴权通过；401/403 代表鉴权失败
            resp.code in 200..299 || resp.code == 404
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 上传单次测量记录到 Zepp Life 云端
     *
     * @param weightKg 体重（公斤）
     * @param impedance 电阻抗值（欧姆）
     * @param timestampMillis 测量时间戳（毫秒）
     */
    suspend fun uploadMeasurement(
        weightKg: Double,
        impedance: Int,
        timestampMillis: Long = System.currentTimeMillis()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val enabled = preferenceManager.zeppLifeSyncEnabled.first()
            if (!enabled) {
                return@withContext Result.failure(Exception("Zepp Life 云同步未开启"))
            }

            var appToken = preferenceManager.zeppLifeAppToken.first()
            var userId = preferenceManager.zeppLifeUserId.first()

            // 如果没有现成 token，尝试用账号密码登录获取
            if (appToken.isBlank() || userId.isBlank()) {
                val account = preferenceManager.zeppLifeAccount.first()
                val password = preferenceManager.zeppLifePassword.first()
                if (account.isBlank() || password.isBlank()) {
                    return@withContext Result.failure(Exception("未配置 Zepp Life 账号凭据"))
                }

                val loginResult = loginOrVerify(account, password, "", "")
                if (loginResult.isFailure) {
                    return@withContext Result.failure(loginResult.exceptionOrNull() ?: Exception("登录凭证获取失败"))
                }

                val pair = loginResult.getOrThrow()
                userId = pair.first
                appToken = pair.second
                // 保存更新
                preferenceManager.saveZeppLifeConfig(account, password, appToken, userId)
            }

            // 构造上传数据包
            val timestampSeconds = timestampMillis / 1000
            val uploadUrl = "https://api-mifit-cn.huami.com/huami.health.banddataservice.weight.upload"

            val itemJson = JSONObject().apply {
                put("timestamp", timestampSeconds)
                put("weight", weightKg)
                if (impedance > 0) {
                    put("impedance", impedance)
                }
            }

            val dataArray = JSONArray().apply {
                put(itemJson)
            }

            val formBody = FormBody.Builder()
                .add("userid", userId)
                .add("data", dataArray.toString())
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .header("apptoken", appToken)
                .header("User-Agent", defaultUserAgent)
                .post(formBody)
                .build()

            val resp = client.newCall(request).execute()
            val respStr = resp.body?.string() ?: ""

            if (resp.isSuccessful) {
                AppLogger.i("ZeppSync", "Zepp Life 体重同步成功: 体重=${weightKg}kg, 响应=$respStr")
                Result.success("同步成功")
            } else {
                AppLogger.w("ZeppSync", "Zepp Life 体重同步返回错误(HTTP ${resp.code}): $respStr")
                Result.failure(Exception("HTTP ${resp.code}: $respStr"))
            }
        } catch (e: Exception) {
            AppLogger.e("ZeppSync", "Zepp Life 同步异常: ${e.message}")
            Result.failure(e)
        }
    }
}
