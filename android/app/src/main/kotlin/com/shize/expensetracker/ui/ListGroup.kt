package com.shize.expensetracker.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/// 「连续项组」的圆角规则 —— Material 3 Expressive 那套列表长相。
///
/// 一组连着的行**共用一个视觉块**：最上面那行的上圆角和最下面那行的下圆角是大的，
/// 中间挨着的边只留一点点圆角。视觉上是一整块，但每一行仍然有自己的按压涟漪和间隙。
///
/// ⚠️ 这是这次改版里最影响观感的一条。改之前每一行都是**一张独立的灰卡片**，
/// 六行就是六块一模一样的灰色 —— 那是 iOS 早期分组表格的排法，在安卓上看着散、
/// 而且卡片色跟背景差得太近，整屏是一片灰。
///
/// 只有一行的时候四个角都是大的（它自己就是完整一块）。
fun groupedShape(index: Int, count: Int): Shape {
    val big = 20.dp
    val small = 6.dp
    val top = if (index == 0) big else small
    val bottom = if (index == count - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/// 组内行与行之间的缝。留 2dp 而不是 0：
/// 有缝才看得出这是「几行」而不是「一整块」，而缝太大就散成独立卡片了。
val GROUP_GAP = 2.dp
