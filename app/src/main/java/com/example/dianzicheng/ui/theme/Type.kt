package com.example.dianzicheng.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// In Jetpack Compose, default includeFontPadding = false can cause CJK (Chinese) descenders
// to be clipped at the bottom. Enabling includeFontPadding ensures full glyph bounds are rendered.
@Suppress("DEPRECATION")
private fun TextStyle.withChineseSupport(): TextStyle = copy(
    platformStyle = PlatformTextStyle(
        includeFontPadding = true
    ),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)

private val defaultTypography = Typography()

// Set of Material typography styles configured with CJK font-padding support
// to prevent Chinese character descenders/ascenders from being clipped.
val Typography = Typography(
    displayLarge = defaultTypography.displayLarge.withChineseSupport(),
    displayMedium = defaultTypography.displayMedium.withChineseSupport(),
    displaySmall = defaultTypography.displaySmall.withChineseSupport(),
    headlineLarge = defaultTypography.headlineLarge.withChineseSupport(),
    headlineMedium = defaultTypography.headlineMedium.withChineseSupport(),
    headlineSmall = defaultTypography.headlineSmall.withChineseSupport(),
    titleLarge = defaultTypography.titleLarge.withChineseSupport(),
    titleMedium = defaultTypography.titleMedium.withChineseSupport(),
    titleSmall = defaultTypography.titleSmall.withChineseSupport(),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ).withChineseSupport(),
    bodyMedium = defaultTypography.bodyMedium.withChineseSupport(),
    bodySmall = defaultTypography.bodySmall.withChineseSupport(),
    labelLarge = defaultTypography.labelLarge.copy(
        fontSize = 14.5.sp,
        lineHeight = 22.sp
    ).withChineseSupport(),
    labelMedium = defaultTypography.labelMedium.withChineseSupport(),
    labelSmall = defaultTypography.labelSmall.withChineseSupport()
)
