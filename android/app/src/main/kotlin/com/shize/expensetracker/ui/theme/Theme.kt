package com.shize.expensetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/// Material 3 主题。
///
/// 用**动态取色**——系统按用户的壁纸生成一整套配色，app 跟着走，
/// 观感自动跟系统统一。这是安卓的原生做法（iOS 没有对等的东西），
/// 比自己钉死一套颜色更像「这个平台上的 app」。
///
/// 不用写版本分支：动态取色要 Android 12+，而 minSdk 已经是 33。
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                 else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
