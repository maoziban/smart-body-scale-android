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
//对外接口
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

// 弹窗本体
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

private fun birthDateFromAge(age: Int): Long =
    Calendar.getInstance().apply { add(Calendar.YEAR, -age) }.timeInMillis

