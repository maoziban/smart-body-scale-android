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

    private val defaultUserAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36"

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
            // 模式 1：如果用户直接填写了 app_token 和 user_id，直接验证 token 有效性
            if (directToken.isNotBlank() && directUserId.isNotBlank()) {
                val valid = verifyToken(directUserId, directToken)
                return@withContext if (valid) {
                    Result.success(Pair(directUserId, directToken))
                } else {
                    Result.failure(Exception("AppToken 校验未通过或已过期，请重新获取"))
                }
            }

            // 模式 2：使用账号密码登录换取 Token
            if (account.isBlank() || password.isBlank()) {
                return@withContext Result.failure(Exception("请填写 Zepp Life 账号和密码，或直接填写 AppToken"))
            }

            val encodedAccount = URLEncoder.encode(account, "UTF-8")
            val loginUrl = "https://api-user.huami.com/registrations/$encodedAccount/tokens"

            val formBody = FormBody.Builder()
                .add("state", "REDIRECTION")
                .add("client_id", "HuaMi")
                .add("password", password)
                .add("redirect_uri", "https://s3-us-west-2.amazonaws.com/hm-registration/success.html")
                .add("token", "access")
                .build()

            val request = Request.Builder()
                .url(loginUrl)
                .header("User-Agent", defaultUserAgent)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("登录失败(HTTP ${response.code}): $respBody"))
            }

            val json = JSONObject(respBody)
            val tokenInfo = json.optJSONObject("token_info")
                ?: return@withContext Result.failure(Exception("登录未返回有效 token_info: $respBody"))

            val loginToken = tokenInfo.optString("login_token")
            val userId = tokenInfo.optString("user_id")

            if (loginToken.isBlank() || userId.isBlank()) {
                return@withContext Result.failure(Exception("登录令牌解析失败: $respBody"))
            }

            // 用 login_token 换取 app_token
            val appTokenUrl = "https://account.huami.com/v2/client/login"
            val appTokenBody = FormBody.Builder()
                .add("app_name", "com.xiaomi.hm.health")
                .add("code", loginToken)
                .add("grant_type", "access_token")
                .add("country_code", "CN")
                .build()

            val appTokenRequest = Request.Builder()
                .url(appTokenUrl)
                .header("User-Agent", defaultUserAgent)
                .post(appTokenBody)
                .build()

            val appTokenResp = client.newCall(appTokenRequest).execute()
            val appTokenRespStr = appTokenResp.body?.string() ?: ""

            val appTokenJson = JSONObject(appTokenRespStr)
            val finalTokenInfo = appTokenJson.optJSONObject("token_info")
            val appToken = finalTokenInfo?.optString("app_token") ?: loginToken

            Result.success(Pair(userId, appToken))
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
