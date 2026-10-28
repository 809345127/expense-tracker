package com.shize.expensetracker.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith

// 页面之间的转场。
//
// ⚠️ 改版之前这里**什么都没有** —— `when (screen)` 直接换 composable，是硬切：
// 上一屏消失、下一屏出现，中间一帧过渡都没有。这是「不精致」里最扎眼的一条，
// 因为系统自带的 app 全都有转场，硬切一眼就看出是自己写的。
//
// 2026-10-13 起首页之外的页面全部改成 iOS 那种「从底下推上来的弹层」（见 IosKit.kt 的 IosSheetHost），
// 原来的「横向推进」转场随之删掉了。这里只剩一种：
//   · **淡入淡出穿透**：平级切换 —— 明细 ↔ 统计（底部两个 tab）。
//     横向推进用在平级上会让人以为「统计在明细右边」，而 tab 是没有先后的。
//
// ⚠️ 全程用**弹簧**而不是「时长 + 缓动」：动画要能被打断。定时动画被打断会跳一下
//（它只知道还剩多少毫秒）；弹簧带着当前速度继续走，一路都是连续的。
// 用户在转场半途按返回，这个差别很明显。

/// 平级切换（底部 tab）。淡入淡出 + 一点点缩放 —— Material 管这个叫 fade through。
/// 缩放幅度故意很小（0.96 → 1）：大了会像弹窗，而 tab 切换应该是「换了内容」不是「开了一层」。
fun <S> AnimatedContentTransitionScope<S>.fadeThroughTransform(): ContentTransform =
    (fadeIn(animationSpec = tween(200, delayMillis = 60)) +
        scaleIn(initialScale = 0.96f, animationSpec = tween(240, delayMillis = 60))) togetherWith
        (fadeOut(animationSpec = tween(120)) +
            scaleOut(targetScale = 0.98f, animationSpec = tween(120)))
