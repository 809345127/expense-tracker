package com.shize.expensetracker.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

// 页面之间的转场。
//
// ⚠️ 改版之前这里**什么都没有** —— `when (screen)` 直接换 composable，是硬切：
// 上一屏消失、下一屏出现，中间一帧过渡都没有。这是「不精致」里最扎眼的一条，
// 因为系统自带的 app 全都有转场，硬切一眼就看出是自己写的。
//
// 用的是 Material 的两种标准转场，按「这两屏是什么关系」选：
//   · **共享 X 轴**（横向推进）：有层级关系 —— 首页 → 分类管理 / 导出 / 记一笔。
//     前进时新的从右边进来，返回时反过来。方向感跟返回手势一致。
//   · **淡入淡出穿透**：平级切换 —— 明细 ↔ 统计（底部两个 tab）。
//     横向推进用在平级上会让人以为「统计在明细右边」，而 tab 是没有先后的。
//
// ⚠️ 全程用**弹簧**而不是「时长 + 缓动」：动画要能被打断。定时动画被打断会跳一下
//（它只知道还剩多少毫秒）；弹簧带着当前速度继续走，一路都是连续的。
// 用户在转场半途按返回，这个差别很明显。

/// 横向推进的位移量：不推满整屏，推三分之一。
/// 推满屏在手机上显得慢且晃；三分之一 + 淡入就够读出「进/退」的方向。
private const val SLIDE_FRACTION = 3

/// 有层级关系的两屏之间。`forward = true` 是往里进，`false` 是往回退。
fun <S> AnimatedContentTransitionScope<S>.pushTransform(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    return (
        slideInHorizontally(animationSpec = spring(0.85f, Spring.StiffnessMediumLow)) {
            dir * it / SLIDE_FRACTION
        } + fadeIn(animationSpec = tween(180))
    ) togetherWith (
        slideOutHorizontally(animationSpec = spring(0.85f, Spring.StiffnessMediumLow)) {
            -dir * it / SLIDE_FRACTION
        } + fadeOut(animationSpec = tween(140))
    )
}

/// 平级切换（底部 tab）。淡入淡出 + 一点点缩放 —— Material 管这个叫 fade through。
/// 缩放幅度故意很小（0.96 → 1）：大了会像弹窗，而 tab 切换应该是「换了内容」不是「开了一层」。
fun <S> AnimatedContentTransitionScope<S>.fadeThroughTransform(): ContentTransform =
    (fadeIn(animationSpec = tween(200, delayMillis = 60)) +
        scaleIn(initialScale = 0.96f, animationSpec = tween(240, delayMillis = 60))) togetherWith
        (fadeOut(animationSpec = tween(120)) +
            scaleOut(targetScale = 0.98f, animationSpec = tween(120)))
