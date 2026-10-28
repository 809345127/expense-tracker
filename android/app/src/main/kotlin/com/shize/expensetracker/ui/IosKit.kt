package com.shize.expensetracker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// 安卓端的「iOS 长相」组件。
//
// 2026-10-13 用户：「所有的 UI 都参照着 iOS 版本的改」—— 两台手机都在用，
// 两边长得不一样、操作不一样，换着用很割裂。所以安卓不再走 Material 的长相，
// 一律对着 iOS 版的截图做（iOS 模拟器 + DemoData 的启动参数拍的参考图）。
//
// 这个文件是那套长相的「零件箱」：颜色、分组卡片、分段控件、弹层顶栏按钮、开关……
// 每个页面只拼这些零件，**别在页面里各自手写一套圆角 / 灰度**，不然几页之间又会对不上。
//
// 尺寸、字号、颜色全取 iOS 的系统值（iOS 的 pt ≈ 安卓的 dp / sp），出处写在各自旁边。
// ─────────────────────────────────────────────────────────────────────────────

/// iOS 的系统色（浅色 / 深色两套）。名字跟 UIKit 的同名颜色一一对应，查 iOS 代码时好对照。
@Immutable
data class IosColors(
    /// systemGroupedBackground：整页的浅灰底
    val groupedBg: Color,
    /// secondarySystemGroupedBackground：分组卡片的白底
    val card: Color,
    /// label / secondaryLabel / tertiaryLabel
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    /// separator：卡片里行与行之间那条细线
    val separator: Color,
    /// tertiarySystemFill：分段控件的底槽、胶囊按钮的底
    val fill: Color,
    /// 主题色。用户当初从苹果系统色板里挑的系统蓝 #007AFF（深色 #0A84FF），见项目 memory
    val accent: Color,
    val red: Color,
    val orange: Color,
    /// iOS 开关打开时的绿
    val green: Color,
    /// 收入金额的绿，跟 iOS `Color.income` 同一对色值（见 Money.kt）
    val income: Color,
    val isDark: Boolean,
)

private val LightIos = IosColors(
    groupedBg = Color(0xFFF2F2F7),
    card = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F767680),
    accent = Color(0xFF007AFF),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    green = Color(0xFF34C759),
    income = IncomeGreenLight,
    isDark = false,
)

private val DarkIos = IosColors(
    groupedBg = Color.Black,
    card = Color(0xFF1C1C1E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x3D767680),
    accent = Color(0xFF0A84FF),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    green = Color(0xFF30D158),
    income = IncomeGreenDark,
    isDark = true,
)

val LocalIos = staticCompositionLocalOf { LightIos }

@Composable
fun iosColorsFor(dark: Boolean = isSystemInDarkTheme()): IosColors = if (dark) DarkIos else LightIos

/// 取当前这套 iOS 颜色：`Ios.c.accent`
object Ios {
    val c: IosColors @Composable @ReadOnlyComposable get() = LocalIos.current

    // ── 字号（iOS Dynamic Type 的「默认」那一档）──
    val largeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 41.sp)
    val title2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)

    /// 卡片圆角。iOS 26 的分组列表是 26pt 左右；手机屏比 iPhone Pro Max 窄一圈，取 22 看着更协调
    val cardRadius = 22.dp
    /// 卡片离屏幕左右边的距离（iOS 小屏 16、大屏 20；vivo 是 360dp 宽，用 16）
    val sideInset = 16.dp
    /// 卡片里内容离卡片边的距离
    val rowInset = 16.dp
}

// ─────────────────────────────── 分组卡片 ───────────────────────────────

/// iOS 的「分组列表」一组：上面一行灰色小标题、中间一张白卡片（行与行之间细线隔开）、
/// 下面一行灰色说明。对位 SwiftUI `Section { } header: { } footer: { }`。
///
/// 行用 [IosSectionScope.row] 一行一行加，分隔线由这里统一画 —— 页面里别自己画线。
@Composable
fun IosSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    footer: String? = null,
    content: @Composable IosSectionScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Ios.sideInset)) {
        if (header != null || headerTrailing != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = Ios.rowInset + 4.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(header ?: "", style = Ios.subheadline.copy(fontWeight = FontWeight.Medium),
                     color = Ios.c.secondaryLabel, modifier = Modifier.weight(1f))
                headerTrailing?.invoke()
            }
        }
        IosCard { IosSectionScope.content() }
        if (footer != null) {
            Text(footer, style = Ios.footnote, color = Ios.c.secondaryLabel,
                 modifier = Modifier.padding(start = Ios.rowInset + 4.dp, end = Ios.rowInset, top = 7.dp))
        }
    }
}

/// 只要白卡片、不要标题时用（比如首页那张月份卡）
@Composable
fun IosCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Ios.cardRadius)).background(Ios.c.card),
        content = content,
    )
}

/// 分组卡片里的行。`first = false` 的行顶上自动画一条细线（缩进到 [separatorIndent]，跟 iOS 一样不顶到左边）。
object IosSectionScope {
    @Composable
    fun row(
        first: Boolean,
        onClick: (() -> Unit)? = null,
        separatorIndent: Dp = Ios.rowInset,
        minHeight: Dp = 50.dp,
        content: @Composable RowScope.() -> Unit,
    ) {
        Column {
            if (!first) IosSeparator(separatorIndent)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .heightIn(min = minHeight)
                    .padding(horizontal = Ios.rowInset, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun IosSeparator(startIndent: Dp = Ios.rowInset) {
    Box(
        Modifier.fillMaxWidth().padding(start = startIndent, end = Ios.rowInset)
            .height(0.5.dp).background(Ios.c.separator)
    )
}

/// 行最右边那个灰色小箭头（「点进去还有一层」）
@Composable
fun IosChevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
         tint = Ios.c.tertiaryLabel, modifier = Modifier.size(22.dp))
}

/// 最常见的一种行：左边标题，右边灰色的值，可选小箭头
@Composable
fun RowScope.IosValueRowContent(title: String, value: String? = null, chevron: Boolean = false,
                                titleColor: Color = Ios.c.label) {
    Text(title, style = Ios.body, color = titleColor, modifier = Modifier.weight(1f))
    if (value != null) Text(value, style = Ios.body, color = Ios.c.secondaryLabel, maxLines = 1)
    if (chevron) { Spacer(Modifier.width(4.dp)); IosChevron() }
}

// ─────────────────────────────── 分段控件 ───────────────────────────────

/// iOS 的分段控件：灰色底槽 + 一颗滑动的白色胶囊。对位 `Picker(...).pickerStyle(.segmented)`。
@Composable
fun IosSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
) {
    val c = Ios.c
    val pos by animateFloatAsState(selected.toFloat(), spring(0.85f, 600f), label = "seg")
    Box(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(c.fill).padding(2.dp)
    ) {
        // 白色胶囊：按「选中第几格」算偏移，滑过去
        Layout(content = {
            Box(
                Modifier
                    .shadow(2.dp, RoundedCornerShape(height / 2), clip = false,
                            ambientColor = Color.Black.copy(0.12f), spotColor = Color.Black.copy(0.12f))
                    .clip(RoundedCornerShape(height / 2))
                    .background(if (c.isDark) Color(0xFF636366) else Color.White)
            )
        }) { measurables, constraints ->
            val w = constraints.maxWidth / options.size
            val p = measurables.first().measure(androidx.compose.ui.unit.Constraints.fixed(w, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { p.place((pos * w).toInt(), 0) }
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Ios.footnote.copy(
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium),
                         color = c.label, maxLines = 1)
                }
            }
        }
    }
}

// ─────────────────────────────── 按钮 ───────────────────────────────

/// 弹层顶栏 / 页面右上角的按钮：浮起来的白色胶囊（iOS 26 的玻璃按钮放在灰底上就是这个样子）。
/// 文字按钮（取消 / 保存 / 完成）和图标按钮都用它。
///
/// `prominent = true` 是蓝字加粗（「保存」「完成」这种确认动作，iOS 的 confirmationAction 就是这么画的）。
@Composable
fun IosPillButton(
    text: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
    prominent: Boolean = false,
    tint: Color = Ios.c.accent,
) {
    val c = Ios.c
    val color = if (enabled) tint else c.tertiaryLabel
    Box(
        modifier
            .height(44.dp)
            .defaultMinSize(minWidth = 44.dp)
            .iosFloating(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (text != null) 16.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription ?: text, tint = color, modifier = Modifier.size(24.dp))
        else Text(text ?: "", style = Ios.body.copy(
            fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal), color = color)
    }
}

/// 「浮起来的白色胶囊」这层皮：白底 + 很淡的阴影。顶栏按钮、右上角那组图标都穿这层
fun Modifier.iosFloating(shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    val c = Ios.c
    this
        .shadow(10.dp, shape, clip = false,
                ambientColor = Color.Black.copy(if (c.isDark) 0.4f else 0.10f),
                spotColor = Color.Black.copy(if (c.isDark) 0.4f else 0.10f))
        .clip(shape)
        .background(if (c.isDark) Color(0xFF2C2C2E) else Color.White.copy(alpha = 0.96f))
}

/// 右上角那一组图标按钮合成一颗胶囊（iOS 26 同一侧相邻的工具栏按钮会自动合成一个，见 iOS ExpenseListView）
@Composable
fun IosToolbarGroup(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.height(44.dp).iosFloating(CircleShape).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun IosToolbarIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit,
                   tint: Color = Ios.c.accent) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(24.dp)) }
}

/// 整行宽的大按钮（智能记账底部的「识别」「保存 N 笔」）。对位 `.borderedProminent` + 圆角 14
@Composable
fun IosProminentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                       enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    val c = Ios.c
    Row(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) c.accent else c.fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) Color.White else c.tertiaryLabel) {
            leading?.let { it(); Spacer(Modifier.width(8.dp)) }
            Text(text, style = Ios.headline, color = LocalContentColor.current)
        }
    }
}

// ─────────────────────────────── 开关 ───────────────────────────────

/// iOS 的开关：51×31 的胶囊，开是绿底，白色圆钮左右滑。对位 SwiftUI `Toggle`
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.c
    val track by animateColorAsState(if (checked) c.green else c.fill.copy(alpha = if (c.isDark) 0.32f else 0.16f).compositeOverCard(c), label = "sw")
    val x by animateFloatAsState(if (checked) 1f else 0f, spring(0.8f, 500f), label = "swx")
    Box(
        modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(2.dp),
    ) {
        Box(
            Modifier.graphicsLayer { translationX = x * 20.dp.toPx() }
                .size(27.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White)
        )
    }
}

private fun Color.compositeOverCard(c: IosColors): Color =
    if (c.isDark) Color(0xFF39393D) else Color(0xFFE9E9EA)

// ─────────────────────────────── 弹层 ───────────────────────────────

/// iOS 弹层（sheet）的顶栏：左右各一颗浮起的胶囊按钮，中间是加粗的标题。
/// 对位 iOS 各弹层里 `.navigationTitle` + `ToolbarItem(.cancellationAction / .confirmationAction)`。
@Composable
fun IosSheetHeader(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Ios.sideInset, vertical = 10.dp).heightIn(min = 44.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Text(title, style = Ios.headline, color = Ios.c.label, textAlign = TextAlign.Center,
             modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

/// 弹层本体：灰底、上面两个大圆角、顶上留出一截（露出后面变暗的页面，iOS 的 page sheet 就这个样子）。
/// 内容自己决定滚不滚。由 MainActivity 负责把它从底下推上来、给后面加暗色。
@Composable
fun IosSheetSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .statusBarsPadding()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(Ios.c.groupedBg)
            .navigationBarsPadding()
            .imePadding(),
        content = content,
    )
}

// ─────────────────────────────── 大标题页 ───────────────────────────────

/// 大标题页（明细、统计）：左上角 34sp 的粗体大标题跟着内容一起滚走；
/// 滚过去之后顶栏中间淡入一个小标题 —— iOS `navigationTitle` 大标题模式的行为。
/// 右上角的按钮组（[actions]）一直浮在顶栏上。
///
/// 用法：把 [IosLargeTitle] 放进列表的第一项，并把列表的「滚过大标题了没有」传进 [collapsed]。
@Composable
fun IosLargeTitleTopBar(title: String, collapsed: Boolean, actions: (@Composable RowScope.() -> Unit)? = null) {
    val c = Ios.c
    val a by animateFloatAsState(if (collapsed) 1f else 0f, label = "title")
    Box(
        Modifier.fillMaxWidth()
            .background(c.groupedBg.copy(alpha = a * 0.92f))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = Ios.sideInset),
    ) {
        Text(title, style = Ios.headline, color = c.label,
             modifier = Modifier.align(Alignment.Center).alpha(a))
        if (actions != null) {
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
fun IosLargeTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title, style = Ios.largeTitle, color = Ios.c.label,
        modifier = modifier.padding(start = Ios.sideInset + 4.dp, top = 4.dp, bottom = 8.dp),
    )
}

// ─────────────────────────────── 杂项 ───────────────────────────────

/// 分类图标：淡色底的圆角小方块 + 分类色图标。对位 iOS `CategoryIcon`（圆角 = 边长 × 0.3）
@Composable
fun IosCategoryIcon(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f)) }
}

/// 淡色底小胶囊按钮（「清除筛选」「私密模式 · 点此退出」）
@Composable
fun IosTintedCapsule(text: String, color: Color, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.13f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = Ios.caption.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/// 弹层的「宿主」：负责后面那层变暗、弹层从底下推上来 / 退下去、系统返回键关弹层、
/// 以及**按住弹层往下拖就关掉**（iOS 弹层的标准手势）。
///
/// `item` 非空 = 弹出来，内容按 `item` 画；变回 null = 收起。
/// ⚠️ 收起的动画要画「刚才那一个」，所以这里自己留着上一个非空的 item —— 不然一置 null，
/// 退场动画里画的就是空白。
///
/// 放在**整屏**的那一层（MainActivity 的最外层 Box、或者弹层自己里面再叠一层），
/// 不能放进被底部玻璃条盖住的页面里，不然弹层会被那条压在下面。
@Composable
fun <T : Any> IosSheetHost(item: T?, onDismiss: () -> Unit, content: @Composable (T) -> Unit) {
    var last by remember { mutableStateOf(item) }
    if (item != null) last = item
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    state.targetState = item != null
    val dismiss by rememberUpdatedState(onDismiss)

    androidx.activity.compose.BackHandler(enabled = item != null) { dismiss() }

    androidx.compose.animation.AnimatedVisibility(
        state,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(remember { MutableInteractionSource() }, null) { dismiss() }
        )
    }
    androidx.compose.animation.AnimatedVisibility(
        state,
        enter = androidx.compose.animation.slideInVertically(spring(1f, 380f)) { it },
        exit = androidx.compose.animation.slideOutVertically(spring(1f, 500f)) { it },
    ) {
        // 往下拖关弹层：只认从顶栏那一截开始的拖（不然跟弹层里面的列表滚动抢手势）
        var drag by remember { mutableFloatStateOf(0f) }
        val animDrag by animateFloatAsState(drag, spring(1f, 800f), label = "drag")
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = animDrag }) {
            IosSheetSurface { last?.let { content(it) } }
            Box(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp).height(64.dp)
                    .padding(horizontal = 76.dp) // 让开左右两颗按钮
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag > with(density) { 120.dp.toPx() }) dismiss()
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        ) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) }
                    }
            )
        }
    }
}

// ─────────────────────────────── 弹框 ───────────────────────────────

/// iOS 弹框里的一个按钮。`cancel` 加粗（iOS 的取消键是粗体）、`destructive` 红字
data class IosAlertButton(
    val label: String,
    val destructive: Boolean = false,
    val cancel: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/// iOS 的弹框（alert）：270 宽、圆角 14，标题居中加粗、说明小字，按钮在底下横排（三个以上竖排），细线隔开。
/// 对位 SwiftUI `.alert` / `.confirmationDialog`。`field` 非空时中间多一个输入框（改名、新建用）。
@Composable
fun IosAlert(
    title: String?,
    message: String? = null,
    buttons: List<IosAlertButton>,
    onDismiss: () -> Unit,
    field: (@Composable () -> Unit)? = null,
) {
    val c = Ios.c
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(270.dp).clip(RoundedCornerShape(14.dp))
                .background(if (c.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 19.dp, bottom = 17.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (title != null) Text(title, style = Ios.headline, color = c.label, textAlign = TextAlign.Center)
                if (message != null) Text(message, style = Ios.footnote, color = c.label, textAlign = TextAlign.Center)
                if (field != null) { Spacer(Modifier.height(10.dp)); field() }
            }
            val horizontal = buttons.size <= 2
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            if (horizontal) {
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    buttons.forEachIndexed { i, b ->
                        if (i > 0) Box(Modifier.fillMaxHeight().width(0.5.dp).background(c.separator))
                        AlertButton(b, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            } else {
                buttons.forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                    AlertButton(b, Modifier.fillMaxWidth().height(44.dp))
                }
            }
        }
    }
}

@Composable
private fun AlertButton(b: IosAlertButton, modifier: Modifier) {
    val c = Ios.c
    Box(modifier.clickable(enabled = b.enabled, onClick = b.onClick), contentAlignment = Alignment.Center) {
        Text(
            b.label,
            style = Ios.body.copy(fontWeight = if (b.cancel) FontWeight.SemiBold else FontWeight.Normal),
            color = when { !b.enabled -> c.tertiaryLabel; b.destructive -> c.red; else -> c.accent },
        )
    }
}

/// 弹框里的输入框：白底小圆角、细边（iOS alert 里的 TextField 就是这样）
@Composable
fun IosAlertField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val c = Ios.c
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.compose.foundation.text.BasicTextField(
        value, onValueChange, singleLine = true,
        textStyle = Ios.footnote.copy(fontSize = 14.sp, color = c.label),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(c.card)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                if (value.isEmpty()) Text(placeholder, style = Ios.footnote.copy(fontSize = 14.sp), color = c.tertiaryLabel)
                inner()
            }
        },
    )
}

/// 行内的输入框（表单里「备注」那种：左边标题、右边右对齐的输入，没有边框）
@Composable
fun RowScope.IosInlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.End,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val c = Ios.c
    androidx.compose.foundation.text.BasicTextField(
        value, onValueChange, singleLine = true,
        textStyle = Ios.body.copy(color = c.label, textAlign = align),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        modifier = modifier.weight(1f),
        decorationBox = { inner ->
            Box(contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = Ios.body, color = c.tertiaryLabel)
                inner()
            }
        },
    )
}

// ─────────────────────────────── 左滑按钮 ───────────────────────────────

data class IosSwipeAction(val label: String, val color: Color, val onClick: () -> Unit)

/// 行往左滑露出一排按钮（iOS `swipeActions(edge: .trailing)`）。最右边那颗放 actions 的第一个（iOS 的顺序）。
/// 滑过一半松手就停在打开状态，再点按钮才执行 —— 不会一滑就删掉。
/// 只能放在 [IosSection] 里（行的白底要跟卡片一致）。`open` 由外面管：同一时间只开一行。
@Composable
fun IosSectionScope.IosSwipeRow(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actions: List<IosSwipeAction>,
    content: @Composable IosSectionScope.() -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val each = with(density) { 78.dp.toPx() }
    val full = each * actions.size
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (!dragging) drag = if (open) -full else 0f }
    val x by animateFloatAsState(drag, if (dragging) spring(1f, 10_000f) else spring(0.9f, 500f), label = "swipe")
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Row(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(with(density) { (-x).coerceAtLeast(0f).toDp() })) {
            actions.reversed().forEach { a ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(a.color).clickable(onClick = a.onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    if (-x > full * 0.5f) Text(a.label, style = Ios.body, color = Color.White, maxLines = 1)
                }
            }
        }
        Box(
            Modifier.graphicsLayer { translationX = x }.background(Ios.c.card)
                .draggable(
                    androidx.compose.foundation.gestures.rememberDraggableState { d ->
                        drag = (drag + d).coerceIn(-full * 1.3f, 0f)
                    },
                    androidx.compose.foundation.gestures.Orientation.Horizontal,
                    onDragStarted = { dragging = true },
                    onDragStopped = {
                        dragging = false
                        val o = drag < -full / 2
                        drag = if (o) -full else 0f
                        onOpenChange(o)
                    },
                )
        ) { content() }
    }
}
