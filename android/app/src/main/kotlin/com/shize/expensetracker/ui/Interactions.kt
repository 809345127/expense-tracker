package com.shize.expensetracker.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/// 连点三下才触发的隐蔽手势。**私密记录的入口就挂在这上面。**
///
/// ⚠️⚠️ 为什么不能用一颗按钮：
/// 界面上放一个锁头图标，等于**当着别人的面宣布「这儿藏了东西」** —— 连藏了多少都能猜到。
/// 私密这件事，露馅的从来不是被藏的内容，是那些「明明什么都没有、却有个开关」的痕迹。
/// 所以入口必须挂在一个**平时就在那儿、看起来完全不可点**的元素上（这里是月份标题）。
/// 对位 iOS `Components.swift` 的 `MonthSwitcher.onSecretTap`，两端选的是同一个位置。
///
/// 为什么是三下而不是两下：一下两下都可能是误碰（翻月份的时候手指落在标题上很常见）。
///
/// ⚠️ `pointerInput` 的 key 必须是常量（这里 `Unit`）。
/// 这个项目在拖动排序上踩过：key 写成「手势自己会改的那个状态」时，每次状态一变
/// Compose 就把手势检测器整个销毁重建，**正在进行的手势被当场取消、还不报错**。
/// 计数和时间戳走 `remember` 的可变状态、不进 key，回调走 `rememberUpdatedState`
/// —— 这样外面换了 lambda 也不会重建检测器。
@Composable
fun Modifier.secretTripleTap(onTriggered: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onTriggered)
    val count = remember { mutableLongStateOf(0L) }
    val lastAt = remember { mutableLongStateOf(0L) }
    return this.pointerInput(Unit) {
        detectTapGestures {
            val now = System.currentTimeMillis()
            count.longValue = if (now - lastAt.longValue <= TRIPLE_TAP_WINDOW_MS) {
                count.longValue + 1
            } else {
                1
            }
            lastAt.longValue = now
            if (count.longValue >= 3) {
                count.longValue = 0
                latest()
            }
        }
    }
}

/// 两下之间最多隔多久还算「连点」。
///
/// 800ms 是刻意放宽的（系统双击判定通常是 300ms 左右）：
///   · 这个手势**没有竞争对手** —— 月份标题上没有别的点击行为，判长一点不会误伤谁；
///   · 判太短的话，手指慢一点就进不去，而这是个「进不去也没有任何提示」的入口
///     （故意不给提示：一给提示就等于承认这儿有东西），用户只会以为坏了。
private const val TRIPLE_TAP_WINDOW_MS = 800L
