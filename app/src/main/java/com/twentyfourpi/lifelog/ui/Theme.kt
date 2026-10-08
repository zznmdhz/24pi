package com.twentyfourpi.lifelog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LifeLogLightColors = lightColorScheme(
    primary = Color(0xFF087F75),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F4F0),
    secondary = Color(0xFFA85C08), // v0.15 对比度修复：原 #C66C12 正文仅 3.78:1，现 5.0:1 过 AA
    secondaryContainer = Color(0xFFFFF1E2),
    tertiary = Color(0xFF2568C8),
    tertiaryContainer = Color(0xFFEAF1FD),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F5F2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFCFA),
    surfaceContainer = Color(0xFFF3F7F4),
    surfaceContainerHigh = Color(0xFFEDF3EF),
    surfaceContainerHighest = Color(0xFFE6EEE9),
    onSurface = Color(0xFF182B29),
    onSurfaceVariant = Color(0xFF657571),
    outline = Color(0xFF71847E),
    outlineVariant = Color(0xFFDDE8E1),
    error = Color(0xFFBA1A1A),
)

private val LifeLogDarkColors = darkColorScheme(
    primary = Color(0xFF68D6C5),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF174B43),
    secondary = Color(0xFFE5A447),
    secondaryContainer = Color(0xFF5A3A0B),
    tertiary = Color(0xFFB9A7FF),
    tertiaryContainer = Color(0xFF40346F),
    background = Color(0xFF101917),
    surface = Color(0xFF17211E),
    surfaceVariant = Color(0xFF22302B),
    surfaceContainerLowest = Color(0xFF080D11),
    surfaceContainerLow = Color(0xFF10171D),
    surfaceContainer = Color(0xFF151D24),
    surfaceContainerHigh = Color(0xFF1B252D),
    surfaceContainerHighest = Color(0xFF232E37),
    onSurface = Color(0xFFE4EFEB),
    onSurfaceVariant = Color(0xFFB6C9C1),
    outline = Color(0xFF87939C),
    outlineVariant = Color(0xFF35414A),
)

private val LifeLogTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 37.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

private val LifeLogShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

@Composable fun LifeLogTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) LifeLogDarkColors else LifeLogLightColors,
        typography = LifeLogTypography,
        shapes = LifeLogShapes,
        content = content,
    )
}
