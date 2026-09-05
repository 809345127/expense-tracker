package com.shize.expensetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/// Material 3 主题。
///
/// 两件事让这个 app 看起来「是安卓上的 app」，而不是把 iOS 排版套了个安卓控件：
///
/// ① **动态取色（Material You）**：系统按用户壁纸生成一整套配色，app 跟着走。
///    这是安卓独有的，iOS 没有对等的东西。不用写版本分支 —— 它要 Android 12+，minSdk 是 33。
///
/// ② **圆角按 Expressive 那一档放大**（见下面 `ExpressiveShapes`）。
///
/// ⚠️⚠️ **这里没有用 `MaterialExpressiveTheme`，是查过之后放弃的，别再试**：
/// material3 **1.4.0（当前最新稳定版）里 `MaterialExpressiveTheme` 和
/// `ExperimentalMaterial3ExpressiveApi` 都是 `internal`** —— 类确实在 jar 里
/// （用 `unzip -l` 能看到），但**库外面调不到**，编译报
/// 「Cannot access ...: it is internal in file」。
/// 光看「类在不在」会得出相反的结论，可见性要靠编译器才问得出来。
///
/// 想用官方那套只有两条路，两条都不走：
///   · 升到 `1.5.0-alpha*` —— 这个项目只用稳定版（1.4.0 之后再没有稳定版，全是 alpha）；
///   · 升 Compose 1.12 / BOM 2026.08 —— 那要 `compileSdk 37`，而 android-37 只在 canary 频道，
///     给日常在用的手机装预览 SDK 编出来的包不合适（这条早就否过一次了）。
///
/// 所以 Expressive 的**观感**在这里是用公开 API 自己搭的：形状放大在这个文件，
/// 弹簧动效在 `ui/Motion.kt`。缺的只是官方那套组件（ButtonGroup / FloatingToolbar 之类），
/// 这个 app 用不上。
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                 else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, shapes = ExpressiveShapes, content = content)
}

/// 圆角比 Material 的默认档大一圈 —— 这是 Expressive 那一版最直观的改动。
///
/// 好处是**改一处、全 app 生效**：按钮、卡片、chip、底部弹层、弹框、输入框
/// 都从 `MaterialTheme.shapes` 取形状，不用逐个组件去设。
///
/// 默认档 → 这里：extraSmall 4→6 / small 8→10 / medium 12→16 / large 16→24 / extraLarge 28→32
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
