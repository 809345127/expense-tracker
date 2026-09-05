package com.shize.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.ExpenseFilter
import com.shize.expensetracker.data.TagEntity
import java.math.BigDecimal

/// 明细页。
///
/// 口径照着 iOS 那边来（按月看、按天分组、每天有小计、每行两行制），
/// 但**长相和交互按 Material 来** —— 用 FAB、用底部弹层、用连续项组，
/// 不照搬 iOS 的分组表格。同一个功能在两个平台上长得不一样，是因为两边规范不同，不是不一致。
///
/// 2026-09-05 这一版改了三件事：
///   ① **撤掉了顶栏那颗常驻的锁头按钮**，私密门入口改成「连点三下月份标题」（见下面 HeroCard）；
///   ② 加了筛选（分类 + 标签）和标签显示；
///   ③ 长相整体按 Material 3 重做：连续项组代替「一行一张卡」、顶栏跟着滚动、
///      FAB 滚动时收起、主色用起来（原来整屏只有一种灰）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onOpenSync: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenExport: () -> Unit,
    onToggleLock: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ExpenseEntity) -> Unit,
    bottomBar: @Composable () -> Unit,
    vm: ExpenseListViewModel = viewModel(),
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val monthExpenses by vm.monthExpenses.collectAsStateWithLifecycle()
    val expenses by vm.expenses.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val allTags by vm.tags.collectAsStateWithLifecycle()
    val activeTags by vm.activeTags.collectAsStateWithLifecycle()
    val tagsByExpense by vm.tagsByExpense.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    val catByKey = remember(categories) { categories.associateBy { it.id } }
    val tagById = remember(allTags) { allTags.associateBy { it.id } }
    val total = remember(expenses) { expenses.map { it.amount }.sum() }

    var menuOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // FAB 在最顶上时是展开的「＋ 记一笔」，**一离开顶部就收成小圆钮**。
    //
    // ⚠️ 这不只是好看：展开态的 FAB 会压住最后一行（原来那版就是这样，看着像 bug）。
    // 收起之后占的地方小很多，而这时候用户正在看列表、不是要记账。
    //
    // ⚠️ 判据用的是 `canScrollBackward`（还能不能往回滚 = 是不是已经离开顶部），
    // **不是** `firstVisibleItemIndex == 0`。我第一版写的是后者，实测不收：
    // 第一项是那张很高的月份卡片，滚了整整一屏它仍然「可见」，下标还是 0。
    // 这就是「拿档位当代理」那类错 —— 真正的自变量是滚动位移，不是第几项。
    val fabExpanded by remember { derivedStateOf { !listState.canScrollBackward } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("记账本") },
                actions = {
                    // ⚠️ 顶栏**没有锁头图标了**。界面上放一个锁，等于当着别人的面宣布
                    // 「这儿藏了东西」—— 私密这件事，露馅的从来不是被藏的内容，
                    // 是那些「明明什么都没有、却有个开关」的痕迹。入口挪到了月份标题上（连点三下）。
                    FilterAction(active = !filter.isEmpty) { filterOpen = true }
                    IconButton(onClick = onOpenSync) { Icon(Icons.Filled.Sync, "同步") }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("分类管理") },
                            leadingIcon = { Icon(Icons.Filled.Category, null) },
                            onClick = { menuOpen = false; onOpenCategories() },
                        )
                        DropdownMenuItem(
                            text = { Text("标签管理") },
                            leadingIcon = { Icon(Icons.Filled.Sell, null) },
                            onClick = { menuOpen = false; onOpenTags() },
                        )
                        DropdownMenuItem(
                            text = { Text("导出") },
                            leadingIcon = { Icon(Icons.Filled.IosShare, null) },
                            onClick = { menuOpen = false; onOpenExport() },
                        )
                    }
                },
                scrollBehavior = scroll,
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                expanded = fabExpanded,
                // ⚠️ 图标必须给 contentDescription。实测（uiautomator dump）发现不给的话
                // 这颗按钮在无障碍树里**一个字都没有** —— ExtendedFAB 的 text 参数
                // 不会自动变成无障碍标签，别指望它。
                icon = { Icon(Icons.Filled.Add, contentDescription = "记一笔") },
                text = { Text("记一笔") },
            )
        },
    ) { padding ->
        // 下拉刷新（2026-09-05 加的）。
        //
        // ⚠️ 在这之前，想强制拉一次只能进「同步设置」点按钮 —— 那不是这个手势该待的地方。
        // 而用户的真实痛点是「在另一台记完，想立刻在这台看到」：现在下拉一下即时，
        // 加上 app 在前台时每 30 秒的静默轮询（见 MainActivity.startForegroundPolling），
        // 这个场景就不用去翻设置页了。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
        ) {
            item(key = "hero") {
                HeroCard(
                    title = month.title(),
                    total = total,
                    count = expenses.size,
                    unlocked = unlocked,
                    filter = filter,
                    categories = catByKey,
                    tags = tagById,
                    onPrev = vm::prevMonth,
                    onNext = vm::nextMonth,
                    onSecretTap = onToggleLock,
                    onClearFilter = { vm.setFilter(ExpenseFilter.none) },
                    onOpenFilter = { filterOpen = true },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (expenses.isEmpty()) {
                item(key = "empty") {
                    EmptyState(filtering = !filter.isEmpty, onClearFilter = {
                        vm.setFilter(ExpenseFilter.none)
                    })
                }
            }

            // 按天分组、天倒序（跟 iOS 一致）
            val byDay = expenses.groupBy { it.date.toLocalDate() }.toSortedMap(reverseOrder())
            byDay.forEach { (day, items) ->
                item(key = "day-$day") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(day.dayTitle(), style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatYuan(items.map { it.amount }.sum()),
                             style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(items, key = { _, e -> e.id }) { i, e ->
                    ExpenseRow(
                        e = e,
                        cat = catByKey[e.categoryKey],
                        tags = (tagsByExpense[e.id] ?: emptySet()).mapNotNull { tagById[it] },
                        shape = groupedShape(i, items.size),
                        onClick = { onEdit(e) },
                    )
                }
            }

            // 别让最后一行被 FAB 压住
            item(key = "tail") { Spacer(Modifier.height(88.dp)) }
        }
        }
    }

    if (filterOpen) {
        FilterSheet(
            filter = filter,
            onFilterChange = vm::setFilter,
            categories = categories,
            tags = activeTags,
            // ⚠️ 传的是**过完私密门、还没过筛选**的那份 —— 面板上的「几笔」要按
            // 「这个月一共有什么」来算，不能按「筛完还剩什么」算（那样数字会自己吃掉自己）
            monthExpenses = monthExpenses,
            tagsByExpense = tagsByExpense,
            onDismiss = { filterOpen = false },
        )
    }
}

/// 顶栏那颗筛选按钮。有条件在生效时换成实心的（Material 的 `FilledIconButton`），
/// 一眼就能看出「现在看到的不是全部」—— 这比在别处写一行小字有用得多。
@Composable
private fun FilterAction(active: Boolean, onClick: () -> Unit) {
    if (active) {
        FilledIconButton(onClick = onClick) {
            Icon(Icons.Filled.FilterAlt, "筛选（正在生效）")
        }
    } else {
        IconButton(onClick = onClick) {
            // ⚠️ 用 FilterList（朴素的三条横线）而不是 FilterAltOff（带斜杠的漏斗）——
            // 后者读起来像「筛选功能被禁用了」，而这里的意思只是「现在没有条件在生效」
            Icon(Icons.Filled.FilterList, "筛选")
        }
    }
}

/// 月份 + 本月合计的主卡片。
///
/// ⚠️⚠️ **私密记录的入口就在这张卡的月份标题上：连点三下。**
/// 挑这个位置的理由跟 iOS 一样 —— 它平时就是一行普通说明文字、看起来完全不可点，
/// 别人不会想到去连点它。锁着 → 走指纹/密码；已经开着 → 直接锁上。
///
/// 用 `primaryContainer` 而不是原来那个灰：这个 app 开着动态取色（跟系统壁纸走），
/// 而原来整屏只有一种灰、主色只出现在 FAB 上 —— 等于把 Material You 白开了。
@Composable
private fun HeroCard(
    title: String,
    total: BigDecimal,
    count: Int,
    unlocked: Boolean,
    filter: ExpenseFilter,
    categories: Map<String, CategoryEntity>,
    tags: Map<String, TagEntity>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSecretTap: () -> Unit,
    onClearFilter: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(Icons.Filled.ChevronLeft, "上个月") }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    // 连点三下进/出私密模式。⚠️ 手势本身没有任何视觉提示 ——
                    // 一给提示就等于承认这儿有东西
                    modifier = Modifier
                        .secretTripleTap(onSecretTap)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                IconButton(onClick = onNext) { Icon(Icons.Filled.ChevronRight, "下个月") }
            }

            Text(
                formatYuan(total),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            )

            when {
                !filter.isEmpty -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已筛选：${filterSummary(filter, categories, tags)} · 共 $count 笔",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (filter.tagIds.isNotEmpty()) {
                        Text(
                            "一笔被多个标签同时命中只算一次",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.75f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onClearFilter) { Text("清除筛选") }
                        TextButton(onClick = onOpenFilter) { Text("改条件") }
                    }
                }

                unlocked -> {
                    // ⚠️⚠️ 解锁态**一定要有明显标记**：不然自己忘了开着、随手把手机递出去就露了。
                    // 这是整个私密功能里**唯一一处故意显眼**的 UI（锁着的时候界面上一点痕迹都没有）。
                    Text("本月支出 · 共 $count 笔（含私密）",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(onClick = onSecretTap) {
                        Icon(Icons.Filled.LockOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("锁上")
                    }
                }

                else -> Text("本月支出 · 共 $count 笔",
                             style = MaterialTheme.typography.bodySmall)
            }
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

@Composable
private fun EmptyState(filtering: Boolean, onClearFilter: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (filtering) Icons.Filled.FilterAltOff else Icons.Filled.ReceiptLong,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp),
        )
        Text(
            if (filtering) "没有符合条件的记录" else "这个月还没有记账",
            style = MaterialTheme.typography.titleMedium,
        )
        if (filtering) {
            Text("换个条件，或者清掉筛选看全部。",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onClearFilter) { Text("清除筛选") }
        }
    }
}

/// 一行两行制：第一行「这是什么」，第二行「什么时候」，标签跟在后面。
///
/// ⚠️ 创建时间**只在真是补记时才显示**（跟 iOS 一致）—— 当场记的账两个时间只差几十秒，
/// 每行都印出来纯粹是噪音。
///
/// ⚠️ 背景用 `surfaceContainer` + `groupedShape`，**不再是一行一张 Card**：
/// 原来六行就是六块一模一样的独立灰卡片，看着散、而且卡片色跟背景差得太近。
@Composable
private fun ExpenseRow(
    e: ExpenseEntity,
    cat: CategoryEntity?,
    tags: List<TagEntity>,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = categoryColor(cat?.colorIndex ?: 9)
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    categoryIcon(cat?.iconName ?: "questionmark.circle.fill"),
                    contentDescription = null, tint = color, modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 有备注显示备注，否则显示分类名（跟 iOS 的 title(categoryName:) 一致）
                        e.note.ifEmpty { cat?.name ?: e.categoryKey },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
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
                        // ⚠️ 把**创建时间**也带出来（跟 iOS 一致）。只写「补记」两个字的话，
                        // 看得出「是后补的」却看不出「什么时候补的」—— 而对账时想知道的
                        // 恰恰是后者（这笔到底拖了多久才记）
                        Text(" · 补记于 ${createdStampText(e.date, e.createdAt)}",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // 标签。⚠️ 列表行里限量显示（多的收成「+N」）—— 这一行要跟金额、备注抢宽度
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    TagChipRow(tags, limit = 3)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(formatYuan(e.amount), style = MaterialTheme.typography.titleMedium)
        }
    }
}
