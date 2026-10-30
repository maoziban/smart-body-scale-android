package com.example.dianzicheng.domain

/**
 * 身体成分（BIA 生物电阻抗）计算算法模型。
 *
 * 用户可在"我的 - 应用设置 - 体成分计算算法"中自由切换：
 * - [COMMERCIAL]：商业对标模式（默认推荐），对标小米 / OKOK / 芯海官方 App，经足底两电极/四电极硬件实测调校
 * - [ACADEMIC]：日内瓦物理模型（学术金标准），基于 Kyle & Kushner 阻抗指数 (H²/Z) 物理导体圆柱模型
 * - [LEGACY]：经典线性加权模型（原版），保留早期版本的多项式线性累加公式
 */
enum class BodyAlgorithmType(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val description: String
) {
    COMMERCIAL(
        id = "COMMERCIAL",
        displayName = "商业对标模式（推荐）",
        subtitle = "对标小米 / OKOK / 芯海官方 App",
        description = "基于开源 openScale 与消费级足底 BIA 硬件标定，针对亚洲人体质调校，读数与市售品牌官方 App 读数一致性最高。"
    ),
    ACADEMIC(
        id = "ACADEMIC",
        displayName = "日内瓦物理模型（学术）",
        subtitle = "Kyle & Kushner 阻抗指数 (H²/Z) 物理模型",
        description = "源自欧洲医学期刊日内瓦经典 BIA 公式，依据人体导体几何体积与电阻物理学严谨推导，身材偏瘦/偏胖更不易失真。"
    ),
    LEGACY(
        id = "LEGACY",
        displayName = "经典线性模型（原版）",
        subtitle = "旧版简易线性加权回归公式",
        description = "保留早期版本的简易线性加权回归模型，供习惯旧版数据的用户选用。"
    );

    companion object {
        fun fromId(id: String?): BodyAlgorithmType {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: COMMERCIAL
        }
    }
}
