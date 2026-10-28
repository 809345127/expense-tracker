package com.shize.expensetracker.ui.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import androidx.compose.material3.Text

/// 首页底部那一条：左边「明细 | 统计」玻璃切换，右边「+」玻璃按钮。
///
/// ## 为什么长这样（2026-10-13）
///
/// 用户「两边都用原生 UI，交互比较割裂」。iOS 那边底部是
/// 「左下角图标分段控件 + 右下角『+』」两颗浮着的液态玻璃（见 iOS `RootBottomToolbar`），
/// 安卓原来是 Material 的整条导航栏 + 右下角悬浮按钮叠在它上面 —— 位置、数量、手势全不一样。
/// 而 vivo 自己的系统（OriginOS 7）也换成了玻璃风格，安卓这边照着做在 vivo 上一样像「系统自带」。
/// 所以两端的**排法和手势对齐**：
///   · 左下角两格切换（只放图标，跟 iOS 2026-10-09 用户挑的那版一致）；
///   · 右下角「+」：点一下 = 记一笔，**长按 = 弹出「智能记账」**（跟 iOS 同一个手势）。
///
/// ## 玻璃效果从哪来
///
/// 开源库 `io.github.kyant0:backdrop`。原理：底下的页面先被「录」进一个图层
/// （MainActivity 里 `layerBackdrop(backdrop)` 那一行），玻璃控件画的时候
/// 把那个图层里自己身后那一块取出来，做模糊 + 边缘折射（`lens`）再画上去。
/// ⚠️ 所以这一条**必须浮在页面上面、页面要延伸到它身后**：页面要是在它上面就截止了
/// （像原来 Scaffold 的 bottomBar 那样把内容顶上去），玻璃背后什么都没有，看着就是一块灰板。
///
/// ⚠️ 版本锁 1.0.6，不是最新的 2.0.x：2.0 起要求 compileSdk 37 + Kotlin 2.4，
/// 这个项目还在 36 / 2.3（见 libs.versions.toml 顶上的说明）。1.0.6 的效果和 API 跟 2.0 一样。
@Composable
fun HomeBottomBar(
    backdrop: Backdrop,
    statsSelected: Boolean,
    onSelectTab: (stats: Boolean) -> Unit,
    onAdd: () -> Unit,
    onSmart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    // ⚠️ 给 LiquidBottomTabs 的必须是**同一个** lambda 对象：它内部拿这个 lambda 当 remember 的 key，
    // 每次重组传一个新的进去，它就当成「换了一个数据源」把内部状态重置一遍
    val stats by rememberUpdatedState(statsSelected)
    val selectedIndex = remember { { if (stats) 1 else 0 } }
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidBottomTabs(
            selectedTabIndex = selectedIndex,
            onTabSelected = { onSelectTab(it == 1) },
            backdrop = backdrop,
            tabsCount = 2,
            // 宽度跟 iOS 那颗分段控件差不多（那边 frame 150pt）
            modifier = Modifier.width(160.dp),
        ) {
            LiquidBottomTab({ onSelectTab(false) }) {
                Icon(Icons.AutoMirrored.Filled.List, "明细", tint = contentColor)
            }
            LiquidBottomTab({ onSelectTab(true) }) {
                Icon(Icons.Filled.PieChart, "统计", tint = contentColor)
            }
        }
        Box(Modifier.weight(1f))
        AddButton(backdrop, onAdd, onSmart)
    }
}

/// 右下角「+」：白色玻璃圆钮 + 蓝色加号（iOS 那颗 `.glassProminent` 在浅色模式下就是这个样子）。
/// 点一下记一笔，长按弹菜单选「智能记账」。
///
/// 玻璃部分照着示例工程的 `LiquidButton`（带 tint 的那种）写，
/// 区别只是把 `clickable` 换成了 `combinedClickable` 来接长按。
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddButton(backdrop: Backdrop, onAdd: () -> Unit, onSmart: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = if (com.shize.expensetracker.ui.LocalIos.current.isDark) Color(0xFF2C2C2E).copy(alpha = 0.6f)
                  else Color.White.copy(alpha = 0.55f)
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(animationScope = scope) }
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Box(
            Modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(2f.dp.toPx())
                        lens(12f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        // 按下去微微放大（跟示例一致，少了跟手拖动那段 —— 圆钮太小，拖起来像在晃）
                        val s = lerp(1f, 1f + 4f.dp.toPx() / size.height, highlight.pressProgress)
                        scaleX = s
                        scaleY = s
                    },
                    onDrawSurface = { drawRect(surface) },
                )
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onClick = onAdd,
                )
                .then(highlight.modifier)
                .then(highlight.gestureModifier)
                // ⚠️ 无障碍：读屏要念出「记一笔」，并告诉人长按还有东西
                .semantics {
                    contentDescription = "记一笔"
                    onLongClick("智能记账") { menuOpen = true; true }
                }
                .size(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, null, tint = accent, modifier = Modifier.size(30.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("智能记账") },
                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                onClick = { menuOpen = false; onSmart() },
            )
        }
    }
}
