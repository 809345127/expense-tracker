package com.shize.expensetracker.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.comparisonKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 分类管理。对位 iOS 的 CategoryManagerView.swift。
//
// ## 删除规则：只能删没被用过的
//
// 这是刻意选的，不是偷懒。分类不像标签 —— 标签删掉只是解除关联，那笔账还在；
// 分类是**必填**的，删掉一个还有账目在用的分类，那些账就指向一个不存在的分类了
//（界面上变成灰问号，统计里多出一坨认不出的东西）。
//
// 另外两种做法都更糟：
//   · 删的时候把那些账改成「其他」 → 等于替用户改他的账，而且不可撤销
//   · 允许悬空、显示成「已删除」   → 那笔账从此没法被正常统计和筛选
//
// 所以规则是「用过就不给删」，并且**明说还有几笔在用**，而不是把按钮灰掉让人猜。
//
// ## ⚠️⚠️ 计数必须算上私密记录，但**不能把数字露出来**
//
// 「有没有被用过」如果只数看得见的记录会出事：某个分类底下只有私密记录时，
// 锁定态下数出来是 0 笔 → 允许删 → 那几笔私密账当场指向一个不存在的分类。
// 所以**判断用全部记录**。
//
// 但数字不能照实显示 —— 锁定态下写「理发 · 3 笔在用」，等于告诉旁人
// 「这里有 3 笔你看不见的账」，跟「藏记录必须连合计一起藏」那条红线冲突。
// 折中：**显示只用看得见的笔数**（可能是 0），删除与否按全部记录判；
// 出现「显示 0 笔却不给删」时，提示语**不写数字**，只说「还有记录在用」。
//
// ## 删除入口：左滑（2026-10-13 起跟 iOS 一样）
//
// 原来安卓故意不做左滑、删除放在编辑页里 —— 那是照 Material 的习惯。
// 用户要求界面全部照 iOS 之后，改成跟 iOS 一样左滑删除；删不掉时弹「删不掉」说明原因。

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val appState = App.from(app)
    private val repo = appState.repository

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// (全部用量, 看得见的用量)。⚠️ 两个口径分开的理由见文件头
    val usage: StateFlow<Pair<Map<String, Int>, Map<String, Int>>> =
        appState.gate.unlocked
            .flatMapLatest { repo.observeCategoryUsage(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap<String, Int>() to emptyMap())

    fun reorder(ordered: List<CategoryEntity>) = viewModelScope.launch {
        repo.reorderCategories(ordered)
    }

    fun save(editing: CategoryEntity?, name: String, iconName: String, colorIndex: Int, income: Boolean) =
        viewModelScope.launch {
            if (editing == null) repo.addCategory(name, iconName, colorIndex, income)
            else repo.updateCategory(editing, name, iconName, colorIndex)
        }

    fun delete(c: CategoryEntity, done: () -> Unit) = viewModelScope.launch {
        repo.deleteCategory(c)
        done()
    }
}

/// 一行的固定高度（含分隔线）。
/// ⚠️ **拖动排序的下标算法依赖它是固定值**（见 CategorySection）：
/// 行高一样，"拖了多远 = 跨过了几行"就是一道除法。改这个值没问题，改成"每行高度不一样"就得连算法一起改。
private val ROW_HEIGHT = 60.dp

/// 分类管理弹层。**照 iOS `CategoryManagerView` 做**（2026-10-13）：
/// 左上「完成」、右上「+」（点开选新建支出分类还是收入分类）；
/// 支出、收入两组白卡，一行一个分类（图标 + 名字 + 用了几笔 + 小箭头）；
/// **长按一行拖动排序、往左滑删除、点一下进编辑** —— 跟 iOS 的 List 一样。
///
/// （安卓原来是「支出 | 收入」分段切换 + 右边一个拖动把手 + 删除放在编辑页里，
///  那是照 Material 的习惯做的；现在两端统一成 iOS 这一套。）
@Composable
fun CategoryManagerScreen(onBack: () -> Unit, vm: CategoryManagerViewModel = viewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val (usageAll, usageVisible) = vm.usage.collectAsStateWithLifecycle().value

    // null = 不在编辑；Optional(null) = 新建；Optional(c) = 编辑 c
    var editing by remember { mutableStateOf<Optional<CategoryEntity>?>(null) }
    var creatingIncome by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var swiped by remember { mutableStateOf<String?>(null) }
    val c = Ios.c

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            IosSheetHeader(
                "分类",
                leading = { IosPillButton("完成", onBack) },
                trailing = {
                    Box {
                        IosPillButton(icon = Icons.Filled.Add, contentDescription = "新建分类", onClick = { addMenu = true })
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem({ Text("支出分类") }, { addMenu = false; creatingIncome = false; editing = Optional(null) })
                            DropdownMenuItem({ Text("收入分类") }, { addMenu = false; creatingIncome = true; editing = Optional(null) })
                        }
                    }
                },
            )
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                for (income in listOf(false, true)) {
                    // ⚠️ key 跟着组：两组各自一份拖动状态
                    androidx.compose.runtime.key(income) {
                        CategorySection(
                            title = if (income) "收入" else "支出",
                            footer = if (income) "长按拖动可以调整顺序，顺序决定记一笔时九宫格的排列。\n左滑删除；已经有账目在用的分类删不掉，先把那些账改到别的分类。" else null,
                            categories = categories.filter { it.isIncome == income },
                            usageVisible = usageVisible,
                            swiped = swiped,
                            onSwiped = { swiped = it },
                            onClick = { editing = Optional(it) },
                            onDelete = { cat ->
                                swiped = null
                                val reason = whyCannotDelete(cat, usageAll, usageVisible)
                                if (reason != null) blocked = reason else vm.delete(cat) {}
                            },
                            onReorder = vm::reorder,
                        )
                    }
                }
            }
        }

        // 编辑 / 新建是叠在上面的又一层弹层（iOS 也是 sheet 套 sheet）
        IosSheetHost(editing, { editing = null }) { target ->
            CategoryEditorScreen(
                editing = target.value,
                income = target.value?.isIncome ?: creatingIncome,
                allCategories = categories,
                onClose = { editing = null },
                vm = vm,
            )
        }
    }

    blocked?.let { msg ->
        IosAlert("删不掉", msg, listOf(IosAlertButton("知道了", cancel = true) { blocked = null }), { blocked = null })
    }
}

/// 让 `null` 能表示两种不同意思（不在编辑 / 正在新建）
private data class Optional<T>(val value: T?)

// ---------------------------------------------------------------- 一组 + 拖动排序

/// 一组分类：长按某一行拖动排序（iOS List 的 onMove 手感），左滑删除，点一下编辑。
///
/// ⚠️ **为什么拖动是自己写的，不是拉一个库**：Compose foundation 到 1.11 都还没有官方的
/// 「可重排列表」，为一个 60 行的交互拖进一个依赖不划算。
///
/// 算法（**成立的前提是每行等高**，见 ROW_HEIGHT）：
///   1. 长按某一行 → 记下它现在是第几个
///   2. 手指移动 → 累计位移 dy；`dy / 行高` 四舍五入就是"跨过了几行"
///   3. 跨过了就在本地列表里把它挪过去，**同时把 dy 减掉实际挪的行数 × 行高**
///      （否则它会一路飞到底 —— 位移没归零，下一帧又判定要再跨一行）
///   4. 松手 → 把整个新顺序交给 Repository 重新编号 + 标 dirty 推出去
@Composable
private fun CategorySection(
    title: String,
    footer: String?,
    categories: List<CategoryEntity>,
    usageVisible: Map<String, Int>,
    swiped: String?,
    onSwiped: (String?) -> Unit,
    onClick: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    onReorder: (List<CategoryEntity>) -> Unit,
) {
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    // 拖动期间用这份本地顺序渲染；没在拖的时候跟着库走
    var order by remember(categories) { mutableStateOf(categories) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val c = Ios.c

    IosSection(header = title, footer = footer) {
        order.forEachIndexed { i, cat ->
            val dragging = cat.id == dragId
            Box(
                Modifier.zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragDy else 0f; if (dragging) { scaleX = 1.02f; scaleY = 1.02f } }
                    .shadow(if (dragging) 8.dp else 0.dp)
                    // ⚠️⚠️ key 只能用 cat.id，**绝不能用 order**：用 order 的话每次换位手势检测器就被销毁重建，
                    // 正在进行的拖动被当场取消（症状是「拖了半天松手一点没变」，而且不报错）
                    .pointerInput(cat.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragId = cat.id; dragDy = 0f; onSwiped(null) },
                            onDragEnd = { dragId = null; dragDy = 0f; onReorder(order) },
                            // 取消：把本地顺序丢回库里那份，别留一个没提交的假顺序
                            onDragCancel = { dragId = null; dragDy = 0f; order = categories },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDy += amount.y
                                val from = order.indexOfFirst { it.id == cat.id }
                                if (from < 0) return@detectDragGesturesAfterLongPress
                                val steps = (dragDy / rowPx).roundToInt()
                                if (steps != 0) {
                                    val to = (from + steps).coerceIn(0, order.lastIndex)
                                    if (to != from) {
                                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                                        // ⚠️ 按**实际挪动的行数**扣，不是按 steps —— 拖到头尾时 to 会被夹住
                                        dragDy -= (to - from) * rowPx
                                    }
                                }
                            },
                        )
                    }
            ) {
                IosSwipeRow(
                    open = swiped == cat.id,
                    onOpenChange = { onSwiped(if (it) cat.id else null) },
                    actions = listOf(IosSwipeAction("删除", c.red) { onDelete(cat) }),
                ) {
                    Column(Modifier.height(ROW_HEIGHT)) {
                        if (i > 0) IosSeparator(Ios.rowInset + 46.dp)
                        Row(
                            Modifier.fillMaxWidth().weight(1f).clickable(enabled = dragId == null) { onClick(cat) }
                                .padding(horizontal = Ios.rowInset),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IosCategoryIcon(categoryIcon(cat.iconName), categoryColor(cat.colorIndex), 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(cat.name, style = Ios.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(usageText(cat, usageVisible[cat.id] ?: 0), style = Ios.caption2, color = c.secondaryLabel)
                            }
                            IosChevron()
                        }
                    }
                }
            }
        }
    }
}

private fun usageText(c: CategoryEntity, visibleCount: Int): String = when {
    c.isFallback -> "兜底分类，不能删"
    visibleCount == 0 -> "还没用过"
    else -> "$visibleCount 笔在用"
}

// ---------------------------------------------------------------- 新建 / 编辑

/// 新建 / 编辑分类。照 iOS `CategoryEditorView`：
/// 第一张卡是实时预览的图标 + 名字输入框；然后「颜色」七列色点；然后按组列出的图标（六列）。
@Composable
private fun CategoryEditorScreen(
    editing: CategoryEntity?,
    /// 是不是收入分类。编辑时跟着它自己走；新建时是「+」菜单里选的那一种
    income: Boolean,
    allCategories: List<CategoryEntity>,
    onClose: () -> Unit,
    vm: CategoryManagerViewModel,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var iconName by remember { mutableStateOf(editing?.iconName ?: FALLBACK_ICON) }
    var colorIndex by remember {
        mutableIntStateOf(editing?.colorIndex ?: (allCategories.size % categoryColorCount))
    }
    val c = Ios.c

    val cleaned = com.shize.expensetracker.data.cleanedName(name)
    /// 重名判断：忽略大小写、全角半角、变音符号（同 iOS）。改自己的名字时把自己排除掉
    val duplicate = remember(cleaned, allCategories, editing) {
        val key = comparisonKey(cleaned)
        // 只跟同一种比：支出有「其他」，收入也可以有一个叫「其他」的，代号不会撞（见 CategoryKind）
        key.isNotEmpty() && allCategories.any {
            it.isIncome == income && it.id != editing?.id && comparisonKey(it.name) == key
        }
    }
    val canSave = cleaned.isNotEmpty() && !duplicate
    val tint = categoryColor(colorIndex)

    IosSheetHeader(
        if (editing == null) (if (income) "新建收入分类" else "新建支出分类") else "编辑分类",
        leading = { IosPillButton("取消", onClose) },
        trailing = {
            IosPillButton("保存", { vm.save(editing, name, iconName, colorIndex, income); onClose() },
                          enabled = canSave, prominent = true)
        },
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        IosSection(
            footer = when {
                duplicate -> "已经有一个叫「$cleaned」的分类了"
                editing != null && cleaned.isNotEmpty() && editing.name != cleaned ->
                    "改名之后，这个分类下已有的账目会跟着显示新名字 —— 历史数据不用动。"
                else -> null
            },
        ) {
            row(first = true, minHeight = 72.dp) {
                // 实时预览：改名字、换图标、换颜色，这里立刻跟着变
                IosCategoryIcon(categoryIcon(iconName), tint, 52.dp)
                Spacer(Modifier.width(14.dp))
                IosInlineField(name, { name = it }, "分类名字", align = TextAlign.Start)
            }
        }

        IosSection(header = "颜色") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (0 until categoryColorCount).chunked(7).forEach { rowIdx ->
                    Row {
                        rowIdx.forEach { i ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(categoryColor(i)).clickable { colorIndex = i },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (colorIndex == i) Icon(Icons.Filled.Check, "已选中", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        repeat(7 - rowIdx.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        for ((title, icons) in categoryIconGroups) {
            IosSection(header = title) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    icons.chunked(6).forEach { rowIcons ->
                        Row {
                            rowIcons.forEach { icon ->
                                val selected = iconName == icon
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                                            .background(if (selected) tint else tint.copy(alpha = 0.14f))
                                            .clickable { iconName = icon },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(categoryIcon(icon), icon, tint = if (selected) Color.White else tint,
                                             modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                            repeat(6 - rowIcons.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/// 能删就返回 null，不能删返回给用户看的原因。
///
/// ⚠️⚠️ 判定用 `usageAll`（含私密），提示里的数字用 `usageVisible`（看得见的）。
/// 出现「显示 0 笔却不给删」时**提示语不带数字** —— 否则锁定态下就等于告诉旁人
/// 「这个分类底下有你看不见的账」。理由见文件头。
internal fun whyCannotDelete(
    c: CategoryEntity, usageAll: Map<String, Int>, usageVisible: Map<String, Int>,
): String? {
    if (c.isFallback) {
        return "「${c.name}」是兜底分类，任何时候都得留一个能落脚的分类，所以它删不掉。"
    }
    val used = usageAll[c.id] ?: 0
    if (used == 0) return null
    val shown = usageVisible[c.id] ?: 0
    if (shown == 0) return "「${c.name}」还有记录在用，删不掉。"
    return "「${c.name}」下面有 $shown 笔账，删不掉。先把这些账改到别的分类，再回来删。"
}
