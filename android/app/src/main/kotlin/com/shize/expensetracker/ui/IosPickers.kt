package com.shize.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

// iOS 的日期 / 时刻选择器（2026-10-13，「所有 UI 都照 iOS」的一部分）。
//
// iOS 表单里「记账时间」那两颗灰胶囊，点日期弹出一张**月历**（graphical 样式），
// 点时刻弹出**滚轮**（wheel 样式）。安卓原来用的是 Material 的 DatePickerDialog / TimePicker
// （那个表盘），长得完全不一样，这里自己画了一份。
//
// 交互跟 iOS 一致：**改了就立刻生效**，没有「确定 / 取消」—— 点弹框外面就收起。

/// 月历弹框。点某一天 = 选中并收起。
@Composable
fun IosDatePickerDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val c = Ios.c
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    val today = remember { LocalDate.now() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(340.dp).clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            // 顶上：「2026年10月」+ 右边一对 ‹ › 翻月
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${month.year}年${month.monthValue}月", style = Ios.headline, color = c.label, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronLeft, "上个月", tint = c.accent,
                     modifier = Modifier.size(36.dp).clip(CircleShape).clickable { month = month.minusMonths(1) }.padding(6.dp))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.ChevronRight, "下个月", tint = c.accent,
                     modifier = Modifier.size(36.dp).clip(CircleShape).clickable { month = month.plusMonths(1) }.padding(6.dp))
            }
            // 星期行。iOS 中文区域从周日开始
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                    Text(it, style = Ios.caption.copy(fontWeight = FontWeight.SemiBold), color = c.tertiaryLabel,
                         modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            Spacer(Modifier.height(6.dp))
            // 日期格子：本月 1 号前面空出它是星期几（周日 = 0）
            val lead = month.atDay(1).dayOfWeek.value % 7
            val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { d ->
                        Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                            if (d != null) {
                                val selected = d == initial
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape)
                                        .background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable { onPick(d); onDismiss() },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${d.dayOfMonth}",
                                        fontSize = 20.sp,
                                        fontWeight = if (selected || d == today) FontWeight.SemiBold else FontWeight.Normal,
                                        // 选中：蓝底白字；今天（没选中时）：蓝字 —— iOS 月历的两种强调
                                        color = when { selected -> androidx.compose.ui.graphics.Color.White; d == today -> c.accent; else -> c.label },
                                    )
                                }
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/// 时刻滚轮弹框：「时」「分」两个滚轮，中间一条浅灰的选中带。滚到哪就是哪（实时生效）。
@Composable
fun IosTimePickerDialog(hour: Int, minute: Int, onChange: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val c = Ios.c
    var h by remember { mutableIntStateOf(hour) }
    var m by remember { mutableIntStateOf(minute) }
    Dialog(onDismissRequest = { onChange(h, m); onDismiss() }) {
        Box(
            Modifier.width(260.dp).clip(RoundedCornerShape(14.dp)).background(c.card).padding(vertical = 12.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 选中带：横贯两个滚轮的浅灰圆角条（iOS 滚轮中间那条）
            Box(Modifier.fillMaxWidth().height(WHEEL_ITEM).clip(RoundedCornerShape(8.dp)).background(c.fill))
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel(24, h, { h = it; onChange(h, m) }, Modifier.width(80.dp))
                Text(":", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                Wheel(60, m, { m = it; onChange(h, m) }, Modifier.width(80.dp))
            }
        }
    }
}

private val WHEEL_ITEM = 36.dp
private const val WHEEL_VISIBLE = 5

/// 一个滚轮：0..count-1 两位数，松手吸附到最近的一格；离中间越远越淡。
@Composable
private fun Wheel(count: Int, value: Int, onValue: (Int) -> Unit, modifier: Modifier) {
    val c = Ios.c
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value)
    val pad = WHEEL_ITEM * (WHEEL_VISIBLE / 2)
    // 中间那一格 = 第一个可见项 + 滚过半格就进位
    val center by remember {
        derivedStateOf {
            val first = state.firstVisibleItemIndex
            val off = state.firstVisibleItemScrollOffset
            val itemPx = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1
            (if (off > itemPx / 2) first + 1 else first).coerceIn(0, count - 1)
        }
    }
    LaunchedEffect(center) { if (center != value) onValue(center) }
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = pad),
        modifier = modifier.height(WHEEL_ITEM * WHEEL_VISIBLE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(count) { i ->
            val dist = abs(i - center)
            Box(Modifier.height(WHEEL_ITEM).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "%02d".format(i),
                    fontSize = 22.sp,
                    fontWeight = if (dist == 0) FontWeight.Medium else FontWeight.Normal,
                    color = c.label,
                    modifier = Modifier.alpha(when (dist) { 0 -> 1f; 1 -> 0.45f; else -> 0.2f }),
                )
            }
        }
    }
}
