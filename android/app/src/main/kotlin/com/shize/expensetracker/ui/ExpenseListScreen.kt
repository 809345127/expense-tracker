package com.shize.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.ExpenseFilter
import com.shize.expensetracker.data.TagEntity
import kotlinx.coroutines.launch
import java.math.BigDecimal

/// 明细页。**长相和交互照着 iOS 的 `ExpenseListView.swift` 做**（2026-10-13 用户要求全部照 iOS）。
///
/// 对应关系：
///   · 左上角大标题「记账本」，往上滚走之后顶栏中间淡入小标题；
///   · 右上角一颗白胶囊里三个图标：筛选 / 导出 / 同步（iOS 26 同一侧的工具栏按钮会合成一颗胶囊）。
///     原来安卓的「⋮」菜单（分类管理 / 标签管理）没了 —— iOS 也没有：
///     分类在「记一笔」里分类那组标题右边的「管理」进，标签在记一笔的「标签」那行进；
///   · 月份卡片是素净的白卡（不是彩色），颜色只留给要强调的那一个元素；
///   · 每天一组：组标题左边日期、右边当天合计，下面一张白卡、行与行之间细线；
///   · 行往左滑出红色「删除」（iOS 的 swipeActions）；点一下弹出编辑。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onOpenSync: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenFilter: () -> Unit,
    onToggleLock: () -> Unit,
    onEdit: (ExpenseEntity) -> Unit,
    vm: ExpenseListViewModel = viewModel(),
    syncVm: SyncViewModel = viewModel(),
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val allTags by vm.tags.collectAsStateWithLifecycle()
    val tagsByExpense by vm.tagsByExpense.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val syncUrl by syncVm.url.collectAsStateWithLifecycle()
    val syncError by syncVm.lastError.collectAsStateWithLifecycle()

    val catByKey = remember(categories) { categories.associateBy { it.id } }
    val tagById = remember(allTags) { allTags.associateBy { it.id } }
    val total = remember(expenses) { expenses.expenseSum() }
    val income = remember(expenses) { expenses.incomeSum() }
    val expenseCount = remember(expenses) { expenses.count { !it.isIncome } }
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    // 大标题滚出去了没有 → 顶栏中间要不要显示小标题
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 } }
    // 同一时间只让一行处于「左滑露出删除」的状态（iOS 也是：滑开另一行，前一行自己收回去）
    var swipedId by remember { mutableStateOf<String?>(null) }

    val c = Ios.c
    Box(Modifier.fillMaxSize().background(c.groupedBg)) {
        // 下拉刷新（2026-09-05 加的）：用户的真实痛点是「在另一台记完，想立刻在这台看到」。
        // 加上 app 在前台时每 30 秒的静默轮询（MainActivity.startForegroundPolling），不用去翻设置页
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                    // 别让最后一行被底部那条玻璃（64dp 高 + 12dp 边距）压住
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 96.dp,
                ),
            ) {
                item(key = "title") { IosLargeTitle("记账本") }
                item(key = "hero") {
                    SummaryCard(
                        title = month.title(),
                        total = total,
                        income = income,
                        count = expenses.size,
                        expenseCount = expenseCount,
                        unlocked = unlocked,
                        filter = filter,
                        categories = catByKey,
                        tags = tagById,
                        onPrev = vm::prevMonth,
                        onNext = vm::nextMonth,
                        onSecretTap = onToggleLock,
                        onClearFilter = { vm.setFilter(ExpenseFilter.none) },
                    )
                }

                if (expenses.isEmpty()) {
                    item(key = "empty") { EmptyState(filtering = !filter.isEmpty) }
                }

                // 按天分组、天倒序（跟 iOS 一致）
                val byDay = expenses.groupBy { it.date.toLocalDate() }.toSortedMap(reverseOrder())
                byDay.forEach { (day, items) ->
                    item(key = "day-$day") {
                        IosSection(
                            modifier = Modifier.padding(top = 26.dp),
                            header = day.dayTitle(),
                            headerTrailing = { DayTotals(items) },
                        ) {
                            items.forEachIndexed { i, e ->
                                // ⚠️ 删除是置墓碑不是删行（Repository.deleteExpense 里），另一台设备同步后也会跟着消失
                                IosSwipeRow(
                                    open = swipedId == e.id,
                                    onOpenChange = { swipedId = if (it) e.id else null },
                                    actions = listOf(IosSwipeAction("删除", Ios.c.red) {
                                        swipedId = null; scope.launch { vm.delete(e) }
                                    }),
                                ) {
                                    row(first = i == 0, onClick = { onEdit(e) }, separatorIndent = Ios.rowInset + 52.dp,
                                        minHeight = 64.dp) {
                                        ExpenseRowContent(
                                            e = e,
                                            cat = catByKey[e.categoryKey],
                                            tags = (tagsByExpense[e.id] ?: emptySet()).mapNotNull { tagById[it] },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        IosLargeTitleTopBar("记账本", collapsed) {
            IosToolbarGroup {
                // 筛选中时用实心图标：不然切回这一页看到数字变小、又想不起来是不是还筛着，会以为账丢了
                IosToolbarIcon(
                    if (filter.isEmpty) Icons.Outlined.FilterAlt else Icons.Filled.FilterAlt,
                    if (filter.isEmpty) "筛选" else "筛选（正在生效）", onOpenFilter,
                )
                IosToolbarIcon(Icons.Outlined.IosShare, "导出", onOpenExport)
                // 同步：图标带状态 —— 没配服务器是带斜杠的云、上次失败是带感叹号的。
                // 同步这种在后台默默跑的东西，出问题必须在主界面上看得见（对位 iOS syncIcon）
                IosToolbarIcon(
                    when {
                        syncUrl.isBlank() -> Icons.Outlined.CloudOff
                        syncError.isNotEmpty() -> Icons.Outlined.SyncProblem
                        else -> Icons.Outlined.CloudDone
                    },
                    "同步", onOpenSync,
                )
            }
        }
    }
}

/// 每天那组标题右边的合计。只有支出时只显示 `¥28.50`；当天有收入时前面多一个绿色的 `+¥X`（对位 iOS `DayTotals`）
@Composable
private fun DayTotals(items: List<ExpenseEntity>) {
    val dayIncome = items.incomeSum()
    val dayExpense = items.expenseSum()
    val style = Ios.subheadline.copy(fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dayIncome.signum() > 0) Text(formatIncome(dayIncome), style = style, color = Ios.c.income)
        if (dayExpense.signum() > 0 || dayIncome.signum() == 0) {
            Text(formatYuan(dayExpense), style = style, color = Ios.c.secondaryLabel)
        }
    }
}

/// 月份 + 本月合计的主卡片（对位 iOS `summaryCard`）。
///
/// ⚠️⚠️ **私密记录的入口就在这张卡的月份标题上：连点三下。**
/// 它平时就是一行普通说明文字、看起来完全不可点，别人不会想到去连点它。锁着 → 走指纹/密码；已经开着 → 直接锁上。
///
/// 素净白卡：内容用主文字色、说明用次级色，颜色只留给需要强调的那一个元素（iOS 是对着 Apple 日历比出来的）。
@Composable
private fun SummaryCard(
    title: String,
    total: BigDecimal,
    income: BigDecimal,
    count: Int,
    expenseCount: Int,
    unlocked: Boolean,
    filter: ExpenseFilter,
    categories: Map<String, CategoryEntity>,
    tags: Map<String, TagEntity>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSecretTap: () -> Unit,
    onClearFilter: () -> Unit,
) {
    val c = Ios.c
    IosCard(Modifier.padding(horizontal = Ios.sideInset)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MonthSwitcher(title, onPrev, onNext, onSecretTap)

            // 只有收入、一笔支出都没有时（比如筛了「工资」），大数字改显示收入 —— 不然看到的是一个大大的 ¥0.00
            val incomeCount = count - expenseCount
            val incomeIsHeadline = expenseCount == 0 && incomeCount > 0
            val caption = if (incomeIsHeadline) "本月收入 · 共 $incomeCount 笔" else "本月支出 · 共 $expenseCount 笔"
            Text(
                if (incomeIsHeadline) formatIncome(income) else formatYuan(total),
                style = Ios.largeTitle.copy(fontSize = 36.sp),
                color = if (incomeIsHeadline) c.income else c.label,
            )
            // 支出、收入都有时多一行：收入 +¥X · 结余 ¥Y（结余 = 收入 − 支出，可以是负的）
            if (!incomeIsHeadline && incomeCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val st = Ios.subheadline.copy(fontWeight = FontWeight.Medium)
                    Text("收入 ${formatIncome(income)}", style = st, color = c.income)
                    Text("·", style = st, color = c.secondaryLabel)
                    Text("结余 ${formatSigned(income - total)}", style = st, color = c.secondaryLabel)
                }
            }

            when {
                !filter.isEmpty -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                                          verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已筛选：${filterSummary(filter, categories, tags)} · 共 $count 笔",
                         style = Ios.caption, color = c.secondaryLabel, textAlign = TextAlign.Center)
                    if (filter.tagIds.isNotEmpty()) {
                        Text("一笔被多个标签同时命中只算一次", style = Ios.caption2,
                             color = c.secondaryLabel.copy(alpha = c.secondaryLabel.alpha * 0.8f))
                    }
                    Spacer(Modifier.height(2.dp))
                    IosTintedCapsule("清除筛选", c.accent, onClearFilter)
                }

                // ⚠️⚠️ 解锁态**一定要有明显标记**：不然自己忘了开着、随手把手机递出去就露了。
                // 这是整个私密功能里**唯一一处故意显眼**的 UI（锁着的时候界面上一点痕迹都没有），用橙色
                unlocked -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                                   verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$caption（含私密）", style = Ios.caption, color = c.secondaryLabel)
                    Spacer(Modifier.height(2.dp))
                    IosTintedCapsule("私密模式 · 点此退出", c.orange, onSecretTap, icon = Icons.Filled.LockOpen)
                }

                else -> Text(caption, style = Ios.caption, color = c.secondaryLabel)
            }
        }
    }
}

/// 月份切换器：‹ 2026年10月 ›（明细、统计两页共用，对位 iOS `MonthSwitcher`）
@Composable
fun MonthSwitcher(title: String, onPrev: () -> Unit, onNext: () -> Unit, onSecretTap: () -> Unit) {
    val c = Ios.c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clickable(onClick = onPrev), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ChevronLeft, "上个月", tint = c.label, modifier = Modifier.size(24.dp))
        }
        Text(
            title,
            style = Ios.headline,
            color = c.label,
            // 连点三下进/出私密模式。⚠️ 手势本身没有任何视觉提示 —— 一给提示就等于承认这儿有东西
            modifier = Modifier.secretTripleTap(onSecretTap).padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Box(Modifier.size(36.dp).clickable(onClick = onNext), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ChevronRight, "下个月", tint = c.label, modifier = Modifier.size(24.dp))
        }
    }
}

/// 「已筛选：餐饮、交通 + 咖啡」这一行的文案。
/// ⚠️ 解析不出名字的 id 直接跳过（标签刚被另一台设备删掉这种正常中间态），不显示空白也不崩。
private fun filterSummary(
    f: ExpenseFilter,
    categories: Map<String, CategoryEntity>,
    tags: Map<String, TagEntity>,
): String {
    val parts = mutableListOf<String>()
    if (f.categoryKeys.isNotEmpty()) {
        // 分类名解析不到时退回显示代号 —— 代号就是这个分类当初的名字，看得懂
        parts += f.categoryKeys.map { categories[it]?.name ?: it }.sorted().joinToString("、")
    }
    if (f.tagIds.isNotEmpty()) {
        val names = f.tagIds.mapNotNull { tags[it]?.name }
        // 标签名比「N 个标签」有用得多；多到列不下才退回计数
        parts += if (names.size == f.tagIds.size && names.size <= 3) names.sorted().joinToString("、")
                 else "${f.tagIds.size} 个标签"
    }
    return parts.joinToString(" + ")
}

/// 空状态（对位 iOS `ContentUnavailableView`）
@Composable
private fun EmptyState(filtering: Boolean) {
    val c = Ios.c
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (filtering) Icons.Outlined.FilterAlt else Icons.AutoMirrored.Outlined.ReceiptLong,
            contentDescription = null, tint = c.secondaryLabel, modifier = Modifier.size(48.dp),
        )
        Text(if (filtering) "没有符合筛选条件的记录" else "这个月还没有记录",
             style = Ios.title2.copy(fontSize = 20.sp), color = c.label)
        Text(if (filtering) "换个条件，或者点上面的「清除筛选」" else "点右下角 + 记下第一笔",
             style = Ios.subheadline, color = c.secondaryLabel, textAlign = TextAlign.Center)
    }
}

/// 一行的内容。**严格两行制**：第一行「这是什么」（私密锁 + 标题 + 标签），第二行「什么时候」。
/// 对位 iOS `ExpenseRow`（锁头在标题前、标签最多 2 个、补记的才带出创建时间）。
///
/// ⚠️⚠️ **两行这件事是硬约束，别再往下加第三行**：有标签的行比没标签的行高一截的话，
/// 同一组里行高忽高忽低，左边那颗分类图标也跟着上下浮动。
@Composable
private fun RowScope.ExpenseRowContent(e: ExpenseEntity, cat: CategoryEntity?, tags: List<TagEntity>) {
    val c = Ios.c
    IosCategoryIcon(categoryIcon(cat?.iconName ?: "questionmark.circle.fill"), categoryColor(cat?.colorIndex ?: 9))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (e.isPrivate) {
                Icon(Icons.Filled.Lock, "私密", Modifier.size(12.dp), tint = c.secondaryLabel)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                // 有备注显示备注，否则显示分类名（跟 iOS 的 title(categoryName:) 一致）
                e.note.ifEmpty { cat?.name ?: e.categoryKey },
                style = Ios.body, color = c.label,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tags.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                TagChipRow(tags, limit = 2)
            }
        }
        // 次行：有备注时先补分类名（主标题已经是备注了），再是时间；补记的带出创建时间
        val parts = buildList {
            if (e.note.isNotEmpty()) add(cat?.name ?: e.categoryKey)
            add(if (isBackfilled(e.date, e.createdAt))
                    "${e.date.timeText()} · 补记于 ${createdStampText(e.date, e.createdAt)}"
                else e.date.timeText())
        }
        Text(parts.joinToString(" · "), style = Ios.caption2.copy(fontSize = 12.sp), color = c.secondaryLabel,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.width(8.dp))
    // 收入带「+」、用绿色；支出不带符号 —— 绝大多数行是支出，给它们都挂个「−」是噪音
    Text(
        if (e.isIncome) formatIncome(e.magnitude) else formatYuan(e.amount),
        style = Ios.body.copy(fontWeight = FontWeight.SemiBold),
        color = if (e.isIncome) c.income else c.label,
    )
}
