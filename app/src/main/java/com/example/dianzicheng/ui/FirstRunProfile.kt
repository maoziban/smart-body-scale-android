package com.example.dianzicheng.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.Sex
import java.util.Calendar

/**
 * 首次启动基础信息引导。
 *
 * 使用方式：在「无论配对状态如何都会被组合」的位置调用一行即可，例如 `MainScreen` 开头：
 * ```
 * FirstRunProfileOnboarding(
 *     onSave = { name, sex, heightCm, birthDateEpochMs ->
 *         profileViewModel.upsertPrimaryMember(name, sex, heightCm, birthDateEpochMs)
 *     }
 * )
 * ```
 * 注意不要放在 `MainScreen` 的空状态 / 配对页 early return 之后，否则会被跳过。
 *
 * 也不建议放在 `PairingScreen` 内：该页面仅在「尚未完成配对」时才渲染，
 * 早已配对完成的设备永远不会经过它，弹窗也就永远不会出现。
 *
 * 写库必须通过 ViewModel → Repository → **与 App 其余部分同一个 Room 实例**，
 * 原因见文件末尾的说明。
 */

// ═══════════════════════════════════════════════════════════════════════════════
// 内置变量：是否还需要在启动时提醒用户完善基础信息
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 「是否还需要提醒」这个变量。
 *
 * - 首次安装为 **true**（需要提醒）
 * - 启动弹窗**展示过一次后立即置为 false**，此后每次启动都不再提醒
 *
 * 注意：这里没有用一个普通的顶层 `var`，因为它只活在进程内存里，杀进程重启就会
 * 变回初始值，达不到「以后不再提醒」的效果。所以底层持久化在 SharedPreferences，
 * 对外仍然是同一个布尔变量的读写语义。
 */
object FirstRunProfileState {

    private const val PREFS_NAME = "first_run_profile"
    private const val KEY_SHOULD_REMIND = "should_remind_basic_info"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** true = 启动时仍需弹窗；false = 已提醒过，不再提醒。默认 true。 */
    fun shouldRemind(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOULD_REMIND, true)

    /** 弹窗已展示 → 记为 false（持久化），以后不再提醒。 */
    fun markShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_SHOULD_REMIND, false).apply()
        AppLogger.i("FirstRunProfile", "引导已展示，开关置为 false，以后不再提醒")
    }

    /** 仅用于调试/重新体验引导：把开关恢复为 true。 */
    fun reset(context: Context) {
        prefs(context).edit().putBoolean(KEY_SHOULD_REMIND, true).apply()
        AppLogger.i("FirstRunProfile", "引导开关已重置为 true")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 对外入口（一行调用）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 首次启动引导入口。放在 App 启动链路上任意「必定被组合」的位置调用一次即可。
 *
 * 逻辑：读一次开关 → 需要提醒就弹窗并**立刻把开关置为 false** → 无论用户是填写
 * 保存还是跳过/误触关闭，本次之后都不再提醒。
 *
 * @param onSave 用户点击「保存并开始使用」且校验通过时回调。
 *   **务必接到 `ProfileViewModel.upsertPrimaryMember(...)`**，不要在本文件里自行
 *   创建 Room 实例写库，否则首页成员信息不会刷新（详见文件末尾说明）。
 */
@Composable
fun FirstRunProfileOnboarding(
    onSave: (name: String, sex: Sex, heightCm: Double, birthDateEpochMs: Long) -> Unit
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (FirstRunProfileState.shouldRemind(context)) {
            visible = true
            // 弹过即置 false：满足「启动弹窗后变为 false，以后不再提醒」
            FirstRunProfileState.markShown(context)
        }
    }

    if (visible) {
        FirstRunProfileDialog(
            onSave = onSave,
            onDismiss = { visible = false }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 弹窗本体
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 基础信息填写弹窗。
 *
 * 内容：姓名 / 性别 / 身高(cm) / 年龄(岁)，底部固定提供「跳过」。
 * 校验通过后回调 [onSave]，由调用方交给 ViewModel 落库。
 *
 * @param onSave    校验通过后回调（name, sex, heightCm, birthDateEpochMs）
 * @param onDismiss 弹窗关闭（保存、点「跳过」、点击外部或返回键均会回调）
 */
@Composable
private fun FirstRunProfileDialog(
    onSave: (String, Sex, Double, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.MALE) }
    var heightText by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "完善你的基础信息",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "用于计算 BMI、体脂率、肌肉量等指标，数据仅保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                Text(
                    text = "性别",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = sex == Sex.MALE,
                        onClick = { sex = Sex.MALE },
                        label = { Text("男生") },
                        leadingIcon = if (sex == Sex.MALE) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                    FilterChip(
                        selected = sex == Sex.FEMALE,
                        onClick = { sex = Sex.FEMALE },
                        label = { Text("女生") },
                        leadingIcon = if (sex == Sex.FEMALE) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }

                OutlinedTextField(
                    value = heightText,
                    onValueChange = { input ->
                        heightText = input.filter { it.isDigit() || it == '.' }
                        error = null
                    },
                    label = { Text("身高 (cm)") },
                    placeholder = { Text("例如 170") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                OutlinedTextField(
                    value = ageText,
                    onValueChange = { input ->
                        ageText = input.filter { it.isDigit() }.take(3)
                        error = null
                    },
                    label = { Text("年龄 (岁)") },
                    placeholder = { Text("例如 30") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val height = heightText.trim().toDoubleOrNull()
                    val age = ageText.trim().toIntOrNull()
                    when {
                        name.isBlank() ->
                            error = "请填写姓名，或点击「跳过」稍后再填"

                        height == null || height < 50.0 || height > 250.0 ->
                            error = "身高请填写 50 ~ 250 之间的数值"

                        age == null || age < 1 || age > 120 ->
                            error = "年龄请填写 1 ~ 120 之间的整数"

                        else -> {
                            // 交给调用方（ViewModel）落库，确保与 App 使用同一个 Room 实例
                            onSave(name.trim(), sex, height, birthDateFromAge(age))
                            onDismiss()
                        }
                    }
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("保存并开始使用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("跳过")
            }
        }
    )
}

/**
 * 由「年龄」推算出生日期时间戳。
 *
 * 使用 [Calendar.add] 按年回退，可正确处理闰年（避免 365 天近似带来的偏差）。
 * 结果必然小于当前时间，符合 `BodyAlgorithm.calculateAge` 对入参的要求。
 */
private fun birthDateFromAge(age: Int): Long =
    Calendar.getInstance().apply { add(Calendar.YEAR, -age) }.timeInMillis

