package com.shize.expensetracker.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.LinkEntity
import com.shize.expensetracker.data.TagEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth

// 统计页。
//
// ⚠️⚠️ **这一页往下的每一个数字都必须从「过完私密门的那份」派生**，不能自己去查库。
// 三个瓦片、圆环、分类排行、按标签排行，漏掉任何一个，那个数就会把私密记录算进去、
// 跟明细页对不上 —— 别人一比就知道你藏了东西、还知道藏了多少。
// 过滤在 Repository.visible() 那一处做，这里只消费它的结果。
//
// 口径逐条对齐 iOS 的 StatsView.swift：
//   · 总支出 / 笔数        = 当月可见记录
//   · 日均                = 当前月按**已过天数**算，历史月按整月天数算
//   · 分类排行            = 按分类代号分组，按金额倒序
//   · 按标签              = 一笔多标签时**每个标签各算一次**，所以各行会重叠、
//                          加起来超过总支出（界面上必须写清楚，否则看着像算错了）
//
// ⚠️ 跟 iOS 故意不一样的一处：iOS 那边点排行的一行会**下钻**到明细页并带上筛选条件，
// 安卓这边还没有「按分类/标签筛选」这个页面，所以这些行**不可点**。
// 等安卓补上筛选页再接 —— 现在做成可点但点了没反应，比不可点更糟。

private data class CategoryStat(
    val key: String, val name: String, val iconName: String, val colorIndex: Int,
    val total: BigDecimal, val count: Int, val share: Float,
)

private data class TagStat(
    val id: String, val name: String, val colorIndex: Int,
    val total: BigDecimal, val count: Int, val share: Float,
    /// 「未打标签」那一行。它跟其它行不重叠，界面上要单独说明
    val untagged: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = App.from(app).repository
    private val appState = App.from(app)

    val month: StateFlow<YearMonth> = appState.month
    val unlocked: StateFlow<Boolean> = appState.gate.unlocked

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 当月可见账目。⚠️ 私密过滤在 Repository 那一处做，这里不重复
    val expenses: StateFlow<List<ExpenseEntity>> =
        combine(month, unlocked) { m, u -> m to u }
            .flatMapLatest { (m, u) ->
                val (from, to) = m.rangeMillis()
                repo.observeMonth(from, to, u)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tags: StateFlow<List<TagEntity>> =
        repo.observeAllTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val links: StateFlow<List<LinkEntity>> =
        repo.observeLinks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun prevMonth() { appState.month.value = appState.month.value.minusMonths(1) }
    fun nextMonth() { appState.month.value = appState.month.value.plusMonths(1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onToggleLock: () -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: StatsViewModel = viewModel(),
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()

    val total = remember(expenses) { expenses.map { it.amount }.sum() }
    val catStats = remember(expenses, categories, total) { categoryStats(expenses, categories, total) }
    val tagStats = remember(expenses, tags, links, total) { tagStats(expenses, tags, links, total) }
    val dailyAvg = remember(expenses, month, total) { dailyAverage(total, month) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                actions = {
                    IconButton(onClick = onToggleLock) {
                        Icon(
                            if (unlocked) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = if (unlocked) "锁上私密记录" else "解锁私密记录",
                        )
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            MonthSwitcher(month.title(), vm::prevMonth, vm::nextMonth)

            if (expenses.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 64.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                           verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("这个月还没有记录",
                             style = MaterialTheme.typography.titleMedium)
                        Text("去「明细」页记几笔，这里就有图看了",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Tiles(total = total, count = expenses.size, dailyAvg = dailyAvg)
                Donut(catStats, total)
                CategoryRanking(catStats)
                if (tagStats.any { !it.untagged }) TagRanking(tagStats)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MonthSwitcher(title: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, "上个月")
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, "下个月")
        }
    }
}

@Composable
private fun Tiles(total: BigDecimal, count: Int, dailyAvg: BigDecimal) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("总支出", formatYuan(total), Modifier.weight(1f))
        Tile("笔数", "$count", Modifier.weight(1f))
        Tile("日均", formatYuan(dailyAvg), Modifier.weight(1f))
    }
}

@Composable
private fun Tile(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/// 分类占比圆环，中间叠总额。
///
/// ⚠️ 自己用 Canvas 画，**不引图表库**：只要一个环，为它拖进来一整个依赖不划算
/// （而且这个项目的依赖版本要一个个去查 maven-metadata 核对，成本是实打实的）。
/// iOS 那边用的是系统自带的 Swift Charts，那边不用额外依赖。
@Composable
private fun Donut(stats: List<CategoryStat>, total: BigDecimal) {
    val colors = stats.map { categoryColor(it.colorIndex) }
    val sweeps = stats.map { it.share * 360f }

    Card(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().padding(16.dp).height(210.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(200.dp)) {
                // 环宽 = 半径的 38%（对位 iOS 的 innerRadius .ratio(0.62)）
                val d = minOf(size.width, size.height)
                val stroke = d * 0.19f
                val inset = stroke / 2f
                val arcSize = Size(d - stroke, d - stroke)
                val topLeft = Offset((size.width - d) / 2f + inset, (size.height - d) / 2f + inset)

                // 从 12 点方向开始顺时针。⚠️ 每段之间留 1.5° 的缝（对位 iOS 的 angularInset），
                // 相邻两块颜色接近时不留缝会糊成一片
                var start = -90f
                for (i in stats.indices) {
                    val sweep = sweeps[i]
                    if (sweep <= 0f) continue
                    // 缝不能比这一块本身还宽，否则细分类会被画成反向的一段
                    val gap = minOf(1.5f, sweep / 3f)
                    drawArc(
                        color = colors[i],
                        startAngle = start + gap / 2f,
                        sweepAngle = sweep - gap,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                   verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("总支出", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatYuan(total),
                     style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun CategoryRanking(stats: List<CategoryStat>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            stats.forEachIndexed { i, s ->
                RankRow(
                    leading = {
                        val color = categoryColor(s.colorIndex)
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(color.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(categoryIcon(s.iconName), contentDescription = null,
                                 tint = color, modifier = Modifier.size(19.dp))
                        }
                    },
                    name = s.name, count = s.count, total = s.total,
                    share = s.share, color = categoryColor(s.colorIndex),
                )
                if (i != stats.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp))
            }
        }
    }
}

@Composable
private fun TagRanking(stats: List<TagStat>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text("按标签", style = MaterialTheme.typography.titleSmall,
                 modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            stats.forEachIndexed { i, s ->
                val color = if (s.untagged) Color(0xFF8E8E93) else tagColor(s.colorIndex)
                RankRow(
                    leading = {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    },
                    name = s.name, count = s.count, total = s.total,
                    share = s.share, color = color, leadingWidth = 10.dp,
                )
                if (i != stats.lastIndex) HorizontalDivider(Modifier.padding(start = 22.dp))
            }
            // ⚠️ 这句话不能省：不写清楚重叠，这几行加起来超过总支出会让人以为算错了
            Text(
                "一笔可以打多个标签，所以上面各行之间会重叠、加起来会超过本月总支出。「未打标签」那行不与其它行重叠。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun RankRow(
    leading: @Composable () -> Unit,
    name: String, count: Int, total: BigDecimal, share: Float, color: Color,
    leadingWidth: androidx.compose.ui.unit.Dp = 36.dp,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(leadingWidth), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(6.dp))
                Text("$count 笔", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatYuan(total),
                     style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { share },
                    color = color,
                    modifier = Modifier.weight(1f),
                )
                Text("${Math.round(share * 100)}%",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     modifier = Modifier.width(34.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- 口径

/// 分类汇总。⚠️ 按**分类代号**分组，不是按分类对象 —— 代号是稳定的纯字符串，
/// 名字和颜色渲染时再去目录里查（分类改过名，历史统计里也跟着显示新名字）
private fun categoryStats(
    expenses: List<ExpenseEntity>, categories: List<CategoryEntity>, total: BigDecimal,
): List<CategoryStat> {
    val byKey = categories.associateBy { it.id }
    val totalD = total.toDouble()
    return expenses.groupBy { it.categoryKey }.map { (key, items) ->
        val sum = items.map { it.amount }.sum()
        val def = byKey[key]
        CategoryStat(
            key = key,
            // 分类被删掉（墓碑）或者还没同步过来时，退回显示代号本身 ——
            // 代号就是这个分类当初的名字，认得出，比一个 UUID 强
            name = def?.name ?: key,
            iconName = def?.iconName ?: "questionmark.circle.fill",
            colorIndex = def?.colorIndex ?: 9,
            total = sum, count = items.size,
            share = if (totalD > 0) (sum.toDouble() / totalD).toFloat() else 0f,
        )
    }.sortedByDescending { it.total }
}

/// 标签汇总。
///
/// ⚠️ 跟分类不一样：一笔可以打多个标签，所以**同一笔会计入它的每个标签**，
/// 各行之间是重叠的、加起来会超过总支出 —— 这是「每个标签各自花了多少」这个问题的
/// 正确答案，所以界面上必须写清楚重叠，而不能给一个合计数字。
private fun tagStats(
    expenses: List<ExpenseEntity>, tags: List<TagEntity>, links: List<LinkEntity>,
    total: BigDecimal,
): List<TagStat> {
    if (expenses.isEmpty()) return emptyList()
    val totalD = total.toDouble()
    fun share(v: BigDecimal) = if (totalD > 0) (v.toDouble() / totalD).toFloat() else 0f

    val tagById = tags.associateBy { it.id }
    // ⚠️ links 里可能有指向别的月份账目的关联，按当月这批账目的 id 过一遍
    val ids = expenses.map { it.id }.toSet()
    val tagIdsByExpense = links.filter { it.expenseId in ids }
        .groupBy({ it.expenseId }, { it.tagId })

    val sums = LinkedHashMap<String, Triple<TagEntity, BigDecimal, Int>>()
    var untaggedTotal = BigDecimal.ZERO
    var untaggedCount = 0

    for (e in expenses) {
        // ⚠️ 只认还活着的标签：指向已删标签的关联算「未打标签」，
        // 否则排行里会冒出一行没有名字的空标签
        val ts = (tagIdsByExpense[e.id] ?: emptyList()).mapNotNull { tagById[it] }.filter { !it.deleted }
        if (ts.isEmpty()) {
            untaggedTotal += e.amount
            untaggedCount++
            continue
        }
        for (t in ts) {
            val row = sums[t.id] ?: Triple(t, BigDecimal.ZERO, 0)
            sums[t.id] = Triple(t, row.second + e.amount, row.third + 1)
        }
    }

    val rows = sums.values
        .map { (t, sum, n) -> TagStat(t.id, t.name, t.colorIndex, sum, n, share(sum)) }
        .sortedByDescending { it.total }
        .toMutableList()
    if (untaggedCount > 0) {
        rows.add(TagStat("__untagged__", "未打标签", 0, untaggedTotal, untaggedCount,
                         share(untaggedTotal), untagged = true))
    }
    return rows
}

/// 日均：当前月按**已过天数**算，历史月按整月天数算。
/// ⚠️ 口径跟 iOS 一致 —— 8 月 3 号看到的「日均」应该是「这三天平均每天花多少」，
/// 拿 31 天去除会显得每天只花了十分之一
private fun dailyAverage(total: BigDecimal, month: YearMonth): BigDecimal {
    val today = java.time.LocalDate.now()
    val days = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
    return if (days > 0) total.divide(BigDecimal(days), 2, RoundingMode.HALF_UP) else total
}
