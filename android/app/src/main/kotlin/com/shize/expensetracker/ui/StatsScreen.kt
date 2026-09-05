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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onToggleLock: () -> Unit,
    onDrillDown: (ExpenseFilter) -> Unit,
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

    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                // ⚠️ 顶栏**没有锁头图标了**（2026-09-05 撤的）。界面上摆一个锁，
                // 等于当着别人的面宣布「这儿藏了东西」。私密门的入口挪到了下面
                // 月份标题上的「连点三下」—— 跟 iOS 同一个位置、同一个手势。
                scrollBehavior = scroll,
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
            MonthSwitcher(month.title(), unlocked, vm::prevMonth, vm::nextMonth, onToggleLock)

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
                CategoryRanking(catStats) { onDrillDown(ExpenseFilter.onlyCategory(it)) }
                if (tagStats.any { !it.untagged }) {
                    TagRanking(tagStats) { id ->
                        // ⚠️「未打标签」那一行点了没意义 —— 筛选条件表达不了「没有标签」
                        // 这个否定条件（`tagIds` 是「命中其中任意一个」）。所以那行不给点，
                        // 传上来的 id 是 null
                        if (id != null) onDrillDown(ExpenseFilter.onlyTag(id))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/// 月份切换器。
///
/// ⚠️⚠️ **私密门的入口就挂在这个标题上：连点三下**（跟明细页那张主卡片上的是同一个手势，
/// 两页都能进/出）。锁着 → 走指纹/密码；已经开着 → 直接锁上。
/// 挑这个位置的理由见 `ui/Interactions.kt`：它平时就是一行普通文字、看起来完全不可点。
@Composable
private fun MonthSwitcher(
    title: String,
    unlocked: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSecretTap: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, "上个月")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .secretTripleTap(onSecretTap)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        // ⚠️ 解锁态要有明显标记 —— 不然自己忘了开着、随手把手机递出去就露了。
        // 锁着的时候这里**什么都没有**，界面上一点痕迹都看不出来。
        if (unlocked) {
            Text(
                "含私密",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, "下个月")
        }
    }
}

@Composable
private fun Tiles(total: BigDecimal, count: Int, dailyAvg: BigDecimal) {
    // ⚠️ 三个瓦片各用一种主题色调（primary / secondary / tertiary 的容器色），
    // 不再是三个一样的灰盒子。这个 app 开着**动态取色**（配色跟着系统壁纸走），
    // 而改版之前整屏只有一种灰、主色只出现在 FAB 上 —— 等于把 Material You 白开了。
    // 这三个色调是系统按壁纸算出来的同一族，所以不会撞色、也不用自己调。
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("总支出", formatYuan(total), cs.primaryContainer, cs.onPrimaryContainer, Modifier.weight(1f))
        Tile("笔数", "$count", cs.secondaryContainer, cs.onSecondaryContainer, Modifier.weight(1f))
        Tile("日均", formatYuan(dailyAvg), cs.tertiaryContainer, cs.onTertiaryContainer, Modifier.weight(1f))
    }
}

@Composable
private fun Tile(
    title: String, value: String,
    container: Color, onContainer: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = onContainer,
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ⚠️ 用 LocalContentColor 带一点透明，别用 onSurfaceVariant ——
            // 那是给「灰底」配的字色，放到有色容器上对比度会崩
            Text(title, style = MaterialTheme.typography.bodySmall,
                 color = LocalContentColor.current.copy(alpha = 0.8f))
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

    // ⚠️ 不套 Card：改版之前它被一个 210dp 高的大灰框包着，那一整块灰是整页最扎眼的地方，
    // 而框本身不传达任何信息（环自己就是一个完整的图形）。去掉之后这一页干净很多。
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(200.dp),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(196.dp)) {
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

@Composable
private fun CategoryRanking(stats: List<CategoryStat>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
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
                shape = groupedShape(i, stats.size),
                // 点一行 → 明细页只看这个分类（2026-09-05 接上的，在这之前不可点）
                onClick = { onPick(s.key) },
            )
        }
    }
}

@Composable
private fun TagRanking(stats: List<TagStat>, onPick: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        Text("按标签", style = MaterialTheme.typography.titleSmall,
             modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
        stats.forEachIndexed { i, s ->
            val color = if (s.untagged) MaterialTheme.colorScheme.outline else tagColor(s.colorIndex)
            RankRow(
                leading = {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                },
                name = s.name, count = s.count, total = s.total,
                share = s.share, color = color, leadingWidth = 10.dp,
                shape = groupedShape(i, stats.size),
                // ⚠️「未打标签」那行不给点：筛选条件表达不了「没有标签」这个否定条件
                onClick = if (s.untagged) null else ({ onPick(s.id) }),
            )
        }
        // ⚠️ 这句话不能省：不写清楚重叠，这几行加起来超过总支出会让人以为算错了
        Text(
            "一笔可以打多个标签，所以上面各行之间会重叠、加起来会超过本月总支出。「未打标签」那行不与其它行重叠。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
        )
    }
}

@Composable
private fun RankRow(
    leading: @Composable () -> Unit,
    name: String, count: Int, total: BigDecimal, share: Float, color: Color,
    shape: androidx.compose.ui.graphics.Shape,
    leadingWidth: androidx.compose.ui.unit.Dp = 36.dp,
    /// null = 这一行不可点（「未打标签」那行）。可点的行有涟漪反馈，一试就知道
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick)
                   else Modifier.fillMaxWidth(),
    ) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
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
