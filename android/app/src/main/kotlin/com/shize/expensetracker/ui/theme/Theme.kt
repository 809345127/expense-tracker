package com.shize.expensetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shize.expensetracker.ui.IosColors
import com.shize.expensetracker.ui.LocalIos
import com.shize.expensetracker.ui.iosColorsFor

/// 主题。
///
/// ⚠️ 2026-10-13 起**不再跟壁纸动态取色（Material You）**，改成固定的 iOS 系统色：
/// 用户「所有的 UI 都参照着 iOS 版本的改」—— 两台手机换着用，配色得一样。
/// 页面主要用 `ui/IosKit.kt` 里的零件（它们读 `LocalIos`）；
/// 这里把 Material 的配色也映射到同一套 iOS 色上，剩下还在用 Material 组件的地方
/// （菜单、日期选择器、输入框光标……）颜色也不会跑偏。
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ios = iosColorsFor(isSystemInDarkTheme())
    CompositionLocalProvider(LocalIos provides ios) {
        MaterialTheme(colorScheme = materialFrom(ios), shapes = IosShapes, content = content)
    }
}

private fun materialFrom(c: IosColors) = if (c.isDark) darkColorScheme(
    primary = c.accent, onPrimary = Color.White,
    primaryContainer = c.accent.copy(alpha = 0.25f), onPrimaryContainer = c.label,
    secondaryContainer = c.fill, onSecondaryContainer = c.label,
    background = c.groupedBg, onBackground = c.label,
    surface = c.groupedBg, onSurface = c.label,
    surfaceVariant = c.card, onSurfaceVariant = c.secondaryLabel,
    surfaceContainerLowest = c.card, surfaceContainerLow = c.card, surfaceContainer = c.card,
    surfaceContainerHigh = Color(0xFF2C2C2E), surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = c.separator, outlineVariant = c.separator,
    error = c.red,
) else lightColorScheme(
    primary = c.accent, onPrimary = Color.White,
    primaryContainer = c.accent.copy(alpha = 0.12f), onPrimaryContainer = c.label,
    secondaryContainer = c.fill, onSecondaryContainer = c.label,
    background = c.groupedBg, onBackground = c.label,
    surface = c.groupedBg, onSurface = c.label,
    surfaceVariant = c.card, onSurfaceVariant = c.secondaryLabel,
    surfaceContainerLowest = c.card, surfaceContainerLow = c.card, surfaceContainer = c.card,
    surfaceContainerHigh = c.card, surfaceContainerHighest = c.card,
    outline = c.separator, outlineVariant = c.separator,
    error = c.red,
)

/// 圆角跟 iOS 对齐：弹框 14、菜单 12、卡片 22
val IosShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
