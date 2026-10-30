package com.example.dianzicheng.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.BodyAlgorithmType

/**
 * 身体成分（BIA）算法选择对话框。
 *
 * 用户可在此自主选择：
 * - 商业对标模式（对齐小米、OKOK等市售体脂秤官方读数）
 * - 日内瓦物理模型（Kyle & Kushner 学术金标准，基于 H²/Z 阻抗指数物理推导）
 * - 经典线性模型（原版简易回归）
 */
@Composable
fun SelectBodyAlgorithmDialog(
    currentAlgorithm: BodyAlgorithmType,
    onAlgorithmSelected: (BodyAlgorithmType) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentAlgorithm) }
    val algorithms = remember { BodyAlgorithmType.entries }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "选择体成分计算算法",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "基于生物电阻抗（BIA）推算体脂、水分、肌肉与骨量",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(algorithms, key = { it.id }) { algorithm ->
                    val isSelected = algorithm == selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = algorithm },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        },
                        border = if (isSelected) {
                            androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary
                            )
                        } else null
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selected = algorithm },
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = algorithm.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val tagText = when (algorithm) {
                                            BodyAlgorithmType.COMMERCIAL -> "推荐"
                                            BodyAlgorithmType.ACADEMIC -> "学术"
                                            BodyAlgorithmType.LEGACY -> "原版"
                                        }
                                        Surface(
                                            color = if (algorithm == BodyAlgorithmType.COMMERCIAL) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = tagText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (algorithm == BodyAlgorithmType.COMMERCIAL) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = algorithm.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = algorithm.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 36.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAlgorithmSelected(selected)
                    onDismiss()
                }
            ) {
                Text("确认切换")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
