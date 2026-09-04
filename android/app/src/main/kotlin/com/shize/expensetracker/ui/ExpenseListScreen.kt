package com.shize.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import java.math.BigDecimal

/// 明细页。布局口径照着 iOS 那边来（按月看、按天分组、每天有小计、每行两行制），
/// 但**交互按安卓的习惯**：不用 iOS 那种左滑，用长按弹菜单 —— Material 里没有
/// "左滑删除"这个模式，硬做出来手感是别人家的。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onOpenSync: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ExpenseEntity) -> Unit,
    vm: ExpenseListViewModel = viewModel(),
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val catByKey = remember(categories) { categories.associateBy { it.id } }
    val total = remember(expenses) { expenses.map { it.amount }.sum() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记账本") },
                actions = {
                    IconButton(onClick = onOpenSync) { Icon(Icons.Filled.CloudSync, "同步") }
                },
            )
        },
        floatingActionButton = {
            // 安卓这边用 FAB 是**对的**（Material 自家的模式）；
            // iOS 那边刻意没用（Apple HIG 里没有悬浮按钮，而且实测会压住 tab 栏）。
            // 同一个功能在两个平台上长得不一样，是因为两边的规范不同，不是不一致。
            ExtendedFloatingActionButton(
                onClick = onAdd,
                // ⚠️ 图标要给 contentDescription。实测（uiautomator dump）发现
                // 不给的话这颗按钮在无障碍树里**一个字都没有** —— 读屏软件念不出它是什么。
                // ExtendedFAB 的 text 参数不会自动变成无障碍标签，别指望它。
                icon = { Icon(Icons.Filled.Add, contentDescription = "记一笔") },
                text = { Text("记一笔") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                MonthCard(
                    title = month.title(),
                    total = total,
                    count = expenses.size,
                    onPrev = vm::prevMonth,
                    onNext = vm::nextMonth,
                )
            }

            if (expenses.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), Alignment.Center) {
                        Text("这个月还没有记账", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 按天分组、天倒序（跟 iOS 一致）
            val byDay = expenses.groupBy { it.date.toLocalDate() }.toSortedMap(reverseOrder())
            byDay.forEach { (day, items) ->
                item(key = "day-$day") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(day.dayTitle(), style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatYuan(items.map { it.amount }.sum()),
                             style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(items, key = { it.id }) { e ->
                    ExpenseRow(e, catByKey[e.categoryKey], onClick = { onEdit(e) })
                }
            }

            item { Spacer(Modifier.height(80.dp)) }   // 别让最后一行被 FAB 压住
        }
    }
}

@Composable
private fun MonthCard(
    title: String, total: BigDecimal, count: Int,
    onPrev: () -> Unit, onNext: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(Icons.Filled.ChevronLeft, "上个月") }
                Text(title, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onNext) { Icon(Icons.Filled.ChevronRight, "下个月") }
            }
            Text(
                formatYuan(total),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            )
            Text("本月支出 · 共 $count 笔",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/// 一行两行制：第一行「这是什么」，第二行「什么时候」。
/// ⚠️ 创建时间**只在真是补记时才显示**（跟 iOS 一致）——当场记的账两个时间只差几十秒，
/// 每行都印出来纯粹是噪音。
@Composable
private fun ExpenseRow(e: ExpenseEntity, cat: CategoryEntity?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = categoryColor(cat?.colorIndex ?: 9)
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    categoryIcon(cat?.iconName ?: "questionmark.circle.fill"),
                    contentDescription = null, tint = color, modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 有备注显示备注，否则显示分类名（跟 iOS 的 title(categoryName:) 一致）
                        e.note.ifEmpty { cat?.name ?: e.categoryKey },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (e.isPrivate) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Lock, "私密", Modifier.size(13.dp),
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    if (e.note.isNotEmpty()) {
                        Text("${cat?.name ?: e.categoryKey} · ",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(e.date.timeText(),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isBackfilled(e.date, e.createdAt)) {
                        Text(" · 补记", style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(formatYuan(e.amount), style = MaterialTheme.typography.titleMedium)
        }
    }
}
