package com.shize.expensetracker.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.shize.expensetracker.data.ExpenseFilter
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
// ✅ 2026-09-05：排行的每一行**可以点了**，点了下钻到明细页并带上筛选条件（跟 iOS 一致）。
// （在这之前安卓没有筛选页，所以这些行故意做成不可点 —— 可点但点了没反应比不可点更糟。）
// ⚠️ 下钻是把条件**换成**「只看这一个」，不是往上叠加：点第二个分类应该是「改看那个」，
// 要两个都要就去筛选面板里选。

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

/// 统计页。**长相照着 iOS 的 `StatsView.swift` 做**（2026-10-13 用户要求全部照 iOS）：
/// 左上角大标题「统计」→ 月份切换 → 有收入时一个「支出 | 收入」分段控件 →
/// 三个白色数字瓦片 → 白卡里的圆环 → 分类排行（一张白卡、细线隔开、右边小箭头）→ 按标签排行。
@Composable
fun StatsScreen(
    onToggleLock: () -> Unit,
    onDrillDown: (ExpenseFilter) -> Unit,
    vm: StatsViewModel = viewModel(),
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val allVisible by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()

    // 「支出 | 收入」切换（对位 iOS StatsContent.Kind）。两种钱不能混在一张圆环里：
    // 工资占 95% 的话，所有支出分类都会被挤成一条细缝。
    // 这个月没有收入就**不显示切换**，而且自动退回「支出」——
    // 不然切到收入后翻到一个没有收入的月份，整页是空的、又没有地方切回来
    var showIncome by rememberSaveable { mutableStateOf(false) }
    val hasIncome = remember(allVisible) { allVisible.any { it.isIncome } }
    val incomeMode = showIncome && hasIncome
    val kindLabel = if (incomeMode) "收入" else "支出"
    val expenses = remember(allVisible, incomeMode) { allVisible.filter { it.isIncome == incomeMode } }

    // ⚠️ 下面一律用 magnitude：收入的 amount 是负数
    val total = remember(expenses) { expenses.map { it.magnitude }.sum() }
    val catStats = remember(expenses, categories, total) { categoryStats(expenses, categories, total) }
    val tagStats = remember(expenses, tags, links, total) { tagStats(expenses, tags, links, total) }
    val dailyAvg = remember(expenses, month, total) { dailyAverage(total, month) }

    val scroll = rememberScrollState()
    val collapsed by remember { derivedStateOf { scroll.value > 60 } }
    val c = Ios.c

    Box(Modifier.fillMaxSize().background(c.groupedBg)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp),
        ) {
            IosLargeTitle("统计")
            Column(
                Modifier.padding(horizontal = Ios.sideInset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ⚠️⚠️ 私密门的入口也挂在这个月份标题上：连点三下（跟明细页那张卡上的是同一个手势）
                MonthSwitcher(month.title(), vm::prevMonth, vm::nextMonth, onToggleLock)

                if (hasIncome) {
                    IosSegmented(listOf("支出", "收入"), if (incomeMode) 1 else 0,
                                 { showIncome = it == 1 }, Modifier.width(220.dp))
                }

                if (expenses.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.PieChart, null, tint = c.secondaryLabel, modifier = Modifier.size(48.dp))
                        Text("这个月还没有记录", style = Ios.title2.copy(fontSize = 20.sp), color = c.label)
                        Text("点右下角 + 记几笔，这里就有图看了", style = Ios.subheadline, color = c.secondaryLabel)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tile("总$kindLabel", formatYuan(total), Modifier.weight(1f))
                        Tile("笔数", "${expenses.size}", Modifier.weight(1f))
                        Tile("日均", formatYuan(dailyAvg), Modifier.weight(1f))
                    }
                    Donut(catStats, total, kindLabel)
                    CategoryRanking(catStats) { onDrillDown(ExpenseFilter.onlyCategory(it)) }
                    if (tagStats.any { !it.untagged }) {
                        TagRanking(tagStats, kindLabel) { id ->
                            // ⚠️「未打标签」那一行点了没意义 —— 筛选条件表达不了「没有标签」
                            // 这个否定条件（`tagIds` 是「命中其中任意一个」）。所以那行不给点，传上来的 id 是 null
                            if (id != null) onDrillDown(ExpenseFilter.onlyTag(id))
                        }
                    }
                }
            }
            // 底部留出那条浮着的玻璃（64dp 高 + 12dp 边距）的位置，最后一张卡不被它压住
            Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp))
        }
        IosLargeTitleTopBar("统计", collapsed)
    }
}

/// 数字瓦片：白底圆角 14，上面灰色小字、下面加粗数字（对位 iOS `tile`）
@Composable
private fun Tile(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Ios.c.card).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = Ios.caption, color = Ios.c.secondaryLabel)
        Text(value, style = Ios.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Ios.c.label,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/// 分类占比圆环，中间叠总额，外面一张白卡（对位 iOS `donut`：内径 62%、每段之间 1.5° 的缝、每段一点渐变）。
///
/// ⚠️ 自己用 Canvas 画，**不引图表库**：只要一个环，为它拖进来一整个依赖不划算。
@Composable
private fun Donut(stats: List<CategoryStat>, total: BigDecimal, kindLabel: String) {
    val colors = stats.map { categoryColor(it.colorIndex) }
    val sweeps = stats.map { it.share * 360f }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ios.c.card).padding(16.dp).height(230.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(230.dp)) {
            // 环宽 = 半径的 38%（对位 iOS 的 innerRadius .ratio(0.62)）
            val d = minOf(size.width, size.height)
            val stroke = d * 0.19f
            val inset = stroke / 2f
            val arcSize = Size(d - stroke, d - stroke)
            val topLeft = Offset((size.width - d) / 2f + inset, (size.height - d) / 2f + inset)
            // 从 12 点方向开始顺时针。⚠️ 每段之间留 1.5° 的缝，相邻两块颜色接近时不留缝会糊成一片
            var start = -90f
            for (i in stats.indices) {
                val sweep = sweeps[i]
                if (sweep <= 0f) continue
                // 缝不能比这一块本身还宽，否则细分类会被画成反向的一段
                val gap = minOf(1.5f, sweep / 3f)
                // iOS 用的是 `color.gradient`：上面亮一点、下面是原色
                val brush = Brush.verticalGradient(listOf(lerp(colors[i], Color.White, 0.18f), colors[i]))
                drawArc(brush, start + gap / 2f, sweep - gap, false, topLeft, arcSize, style = Stroke(width = stroke))
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("总$kindLabel", style = Ios.caption, color = Ios.c.secondaryLabel)
            Text(formatYuan(total), style = Ios.title2.copy(fontSize = 20.sp), color = Ios.c.label)
        }
    }
}

/// 分类排行：图标 + 名称 + 笔数 + 金额 + 小箭头 + 占比条。整行可点 → 下钻到明细（对位 iOS `ranking`）
@Composable
private fun CategoryRanking(stats: List<CategoryStat>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ios.c.card).padding(vertical = 4.dp)) {
        stats.forEachIndexed { i, s ->
            if (i > 0) IosSeparator(14.dp + 48.dp)
            RankRow(
                leading = { IosCategoryIcon(categoryIcon(s.iconName), categoryColor(s.colorIndex), 36.dp) },
                name = s.name, count = s.count, total = s.total, share = s.share,
                color = categoryColor(s.colorIndex), onClick = { onPick(s.key) },
            )
        }
    }
}

/// 按标签排行：色点 + 标签名 + 笔数 + 金额 + 占比条，末尾一句重叠说明（对位 iOS `tagRanking`）
@Composable
private fun TagRanking(stats: List<TagStat>, kindLabel: String, onPick: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ios.c.card)) {
        Text("按标签", style = Ios.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Ios.c.label,
             modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 2.dp))
        stats.forEachIndexed { i, s ->
            if (i > 0) IosSeparator(14.dp + 22.dp)
            val color = if (s.untagged) Ios.c.tertiaryLabel else tagColor(s.colorIndex)
            RankRow(
                leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
                leadingWidth = 10.dp,
                name = s.name, count = s.count, total = s.total, share = s.share, color = color,
                // ⚠️「未打标签」那行不给点：筛选条件表达不了「没有标签」这个否定条件
                onClick = if (s.untagged) null else ({ onPick(s.id) }),
            )
        }
        // ⚠️ 这句话不能省：不写清楚重叠，这几行加起来超过总支出会让人以为算错了
        Text(
            "一笔可以打多个标签，所以上面各行之间会重叠、加起来会超过本月总$kindLabel。「未打标签」那行不与其它行重叠。",
            style = Ios.caption2, color = Ios.c.tertiaryLabel,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun RankRow(
    leading: @Composable () -> Unit,
    name: String, count: Int, total: BigDecimal, share: Float, color: Color,
    leadingWidth: androidx.compose.ui.unit.Dp = 36.dp,
    /// null = 这一行不可点（「未打标签」那行）
    onClick: (() -> Unit)? = null,
) {
    val c = Ios.c
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(leadingWidth), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(name, style = Ios.subheadline, color = c.label,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                Spacer(Modifier.width(6.dp))
                Text("$count 笔", style = Ios.caption2, color = c.tertiaryLabel)
                Spacer(Modifier.weight(1f))
                Text(formatYuan(total), style = Ios.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.label)
                // 「未打标签」那行没箭头，但占着位置 —— 不然这一行的金额会比别的行往右错一截
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                     tint = if (onClick != null) c.tertiaryLabel else Color.Transparent, modifier = Modifier.size(16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // iOS 的 ProgressView：4dp 高的灰色细槽 + 分类色
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(c.fill)) {
                    Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
                }
                Text("${Math.round(share * 100)}%", style = Ios.caption2, color = c.secondaryLabel,
                     textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
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
        val sum = items.map { it.magnitude }.sum()
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
            untaggedTotal += e.magnitude
            untaggedCount++
            continue
        }
        for (t in ts) {
            val row = sums[t.id] ?: Triple(t, BigDecimal.ZERO, 0)
            sums[t.id] = Triple(t, row.second + e.magnitude, row.third + 1)
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
