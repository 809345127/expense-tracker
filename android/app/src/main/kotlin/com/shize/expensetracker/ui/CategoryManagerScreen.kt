package com.shize.expensetracker.ui

import android.app.Application
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
// ## ⚠️ 跟 iOS 故意不一样：删除入口
//
// iOS 是左滑删除。安卓这边**故意不做左滑** —— Material 里没有这个模式，
// 硬做出来手感是别人家的。删除放在「点进去编辑」那一页里 + 确认弹窗，
// 跟这个 app 删一笔账的做法保持一致（见交接文档坑 G10）。

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

    fun save(editing: CategoryEntity?, name: String, iconName: String, colorIndex: Int) =
        viewModelScope.launch {
            if (editing == null) repo.addCategory(name, iconName, colorIndex)
            else repo.updateCategory(editing, name, iconName, colorIndex)
        }

    fun delete(c: CategoryEntity, done: () -> Unit) = viewModelScope.launch {
        repo.deleteCategory(c)
        done()
    }
}

/// 一行的固定高度。
/// ⚠️ **拖动排序的下标算法依赖它是固定值**（见 DraggableCategoryList）：
/// 行高一样，"拖了多远 = 跨过了几行"就是一道除法，不用去问 LazyColumn 每一行多高。
/// 改这个值没问题，改成"每行高度不一样"就得连算法一起改。
private val ROW_HEIGHT = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerScreen(onBack: () -> Unit, vm: CategoryManagerViewModel = viewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val (usageAll, usageVisible) = vm.usage.collectAsStateWithLifecycle().value

    // null = 不在编辑；Some(null) = 新建；Some(c) = 编辑 c
    var editing by remember { mutableStateOf<Optional<CategoryEntity>?>(null) }

    val target = editing
    if (target != null) {
        // ⚠️ 编辑页是"盖在"分类列表上的一层，返回键要退回列表、不是退出 app。
        // 这一层必须自己拦：MainActivity 那个 BackHandler 只知道「现在在分类页」，
        // 不知道分类页里面还嵌了一层
        BackHandler { editing = null }
        CategoryEditorScreen(
            editing = target.value,
            allCategories = categories,
            usageAll = usageAll,
            usageVisible = usageVisible,
            onClose = { editing = null },
            vm = vm,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { editing = Optional(null) }) {
                        Icon(Icons.Filled.Add, "新建分类")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                "长按右边的把手拖动可以调整顺序，顺序决定「记一笔」时格子的排列。\n" +
                        "点进去可以改名字、图标、颜色，也能删 —— 已经有账目在用的分类删不掉，" +
                        "先把那些账改到别的分类。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DraggableCategoryList(
                categories = categories,
                usageVisible = usageVisible,
                onClick = { editing = Optional(it) },
                onReorder = vm::reorder,
            )
        }
    }
}

/// 让 `null` 能表示两种不同意思（不在编辑 / 正在新建）
private data class Optional<T>(val value: T?)

// ---------------------------------------------------------------- 拖动排序

/// 长按拖动排序。
///
/// ⚠️ **为什么是自己写的，不是拉一个库**：Compose foundation 到 1.11 都还没有官方的
/// 「可重排 LazyColumn」。第三方库有（sh.calvin.reorderable 之类），但这个项目的规矩是
/// 依赖版本要一条条去 maven-metadata 核对（凭记忆写版本号栽过一次），
/// 为一个 60 行的交互拖进一个依赖不划算。
///
/// 算法（**成立的前提是每行等高**，见 ROW_HEIGHT）：
///   1. 长按某一行 → 记下它现在是第几个（dragIndex）
///   2. 手指移动 → 累计位移 dy；`dy / 行高` 四舍五入就是"跨过了几行"
///   3. 跨过了就在本地列表里把它挪过去，**同时把 dy 减掉一行的高度**
///      （否则它会一路飞到底 —— 位移没归零，下一帧又判定要再跨一行）
///   4. 松手 → 把整个新顺序交给 Repository 重新编号 + 标 dirty 推出去
///
/// ⚠️ **没做「拖到屏幕边缘自动滚动」**：现在 12 个分类刚好在一屏内，用不上。
/// 分类多到超过一屏时，拖到边上会停住 —— 分两次拖即可，不是坏了。
/// 真要补，得处理"列表滚了之后手指位置和行位置的相对关系变了"，不是加一行 scrollBy 能了事的。
@Composable
private fun DraggableCategoryList(
    categories: List<CategoryEntity>,
    usageVisible: Map<String, Int>,
    onClick: (CategoryEntity) -> Unit,
    onReorder: (List<CategoryEntity>) -> Unit,
) {
    val listState = rememberLazyListState()
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    // 拖动期间用这份本地顺序渲染；没在拖的时候跟着库走
    var order by remember(categories) { mutableStateOf(categories) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(order, key = { it.id }) { c ->
            val dragging = c.id == dragId
            // ⚠️ 分隔线画在这个 Column **里面**，让「一行占的高度」正好是 ROW_HEIGHT。
            // 之前分隔线是列表里另一个元素，行距变成 65px 而算法按 64px 算 ——
            // 每跨一行差 1px，行数一多就会少跨/多跨一行
            Column(Modifier.height(ROW_HEIGHT)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 被拖的那一行浮起来：跟着手指走 + 抬到最上层 + 加个投影
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragDy else 0f }
                    .shadow(if (dragging) 6.dp else 0.dp)
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable(enabled = dragId == null) { onClick(c) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = categoryColor(c.colorIndex)
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(categoryIcon(c.iconName), contentDescription = null,
                         tint = color, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        usageText(c, usageVisible[c.id] ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 拖动把手。⚠️ 手势挂在把手上而不是整行：整行既要能点开编辑、
                // 又要能长按拖动的话，两个手势会互相抢（长按之后点击还会不会触发要看时序），
                // 给一个专门的抓取点最不容易误触
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "拖动排序：${c.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(40.dp)
                        .padding(8.dp)
                        // ⚠️⚠️ key 只能用 c.id，**绝不能用 order**。
                        // 用 order 的话每次换位 order 就变了 → Compose 把这个手势检测器
                        // 整个销毁重建 → **正在进行的拖动被当场取消**（还会走 onDragCancel
                        // 把顺序回滚）。症状是「拖了半天松手一点没变」，而且不报错。
                        // 这里压根不需要把 order 当 key：下面是用 indexOfFirst 现查下标的。
                        .pointerInput(c.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragId = c.id; dragDy = 0f },
                                onDragEnd = {
                                    dragId = null; dragDy = 0f
                                    onReorder(order)
                                },
                                onDragCancel = {
                                    // 取消：把本地顺序丢回库里那份，别留一个没提交的假顺序
                                    dragId = null; dragDy = 0f; order = categories
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDy += amount.y
                                    val from = order.indexOfFirst { it.id == c.id }
                                    if (from < 0) return@detectDragGesturesAfterLongPress
                                    val steps = (dragDy / rowPx).roundToInt()
                                    if (steps != 0) {
                                        val to = (from + steps).coerceIn(0, order.lastIndex)
                                        if (to != from) {
                                            order = order.toMutableList().apply {
                                                add(to, removeAt(from))
                                            }
                                            // ⚠️ 位移要按**实际挪动的行数**扣掉，不是按 steps ——
                                            // 拖到头顶/末尾时 coerceIn 会把 to 夹住，
                                            // 按 steps 扣的话位移会凭空少一截、行会往回跳
                                            dragDy -= (to - from) * rowPx
                                        }
                                    }
                                },
                            )
                        },
                )
            }
            HorizontalDivider(Modifier.padding(start = 62.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun usageText(c: CategoryEntity, visibleCount: Int): String = when {
    c.isFallback -> "兜底分类，不能删"
    visibleCount == 0 -> "还没用过"
    else -> "$visibleCount 笔在用"
}

// ---------------------------------------------------------------- 新建 / 编辑

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryEditorScreen(
    editing: CategoryEntity?,
    allCategories: List<CategoryEntity>,
    usageAll: Map<String, Int>,
    usageVisible: Map<String, Int>,
    onClose: () -> Unit,
    vm: CategoryManagerViewModel,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var iconName by remember { mutableStateOf(editing?.iconName ?: FALLBACK_ICON) }
    var colorIndex by remember {
        mutableIntStateOf(editing?.colorIndex ?: (allCategories.size % categoryColorCount))
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf<String?>(null) }

    val cleaned = com.shize.expensetracker.data.cleanedName(name)
    /// 重名判断：忽略大小写、全角半角、变音符号（同 iOS）。改自己的名字时把自己排除掉
    val duplicate = remember(cleaned, allCategories, editing) {
        val key = comparisonKey(cleaned)
        key.isNotEmpty() && allCategories.any {
            it.id != editing?.id && comparisonKey(it.name) == key
        }
    }
    val canSave = cleaned.isNotEmpty() && !duplicate

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "新建分类" else "编辑分类") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "取消") }
                },
                actions = {
                    if (editing != null) {
                        IconButton(onClick = {
                            val reason = whyCannotDelete(editing, usageAll, usageVisible)
                            if (reason != null) blocked = reason else confirmDelete = true
                        }) {
                            Icon(Icons.Filled.Delete, "删除",
                                 tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { vm.save(editing, name, iconName, colorIndex); onClose() },
                        enabled = canSave,
                    ) { Text("保存") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 实时预览：改名字、换图标、换颜色，这里立刻跟着变
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = categoryColor(colorIndex)
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(15.dp))
                        .background(color.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(categoryIcon(iconName), contentDescription = null,
                         tint = color, modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("分类名字") }, singleLine = true,
                    isError = duplicate,
                    modifier = Modifier.weight(1f),
                )
            }

            if (duplicate) {
                Text("已经有一个叫「$cleaned」的分类了",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            } else if (editing != null && cleaned.isNotEmpty() && editing.name != cleaned) {
                Text("改名之后，这个分类下已有的账目会跟着显示新名字 —— 历史数据不用动。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("颜色", style = MaterialTheme.typography.titleSmall)
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items((0 until categoryColorCount).toList()) { i ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .background(categoryColor(i))
                            .clickable { colorIndex = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (colorIndex == i) {
                            Icon(Icons.Filled.Check, "已选中", tint = Color.White,
                                 modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            for ((title, icons) in categoryIconGroups) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                val tint = categoryColor(colorIndex)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = ((icons.size + 5) / 6 * 50).dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(icons) { icon ->
                        val selected = iconName == icon
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (selected) tint else tint.copy(alpha = 0.14f))
                                .clickable { iconName = icon },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(categoryIcon(icon), contentDescription = icon,
                                 tint = if (selected) Color.White else tint,
                                 modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉「${editing.name}」？") },
            text = { Text("删掉之后另一台设备下次同步也会跟着删。这个分类还没有账目在用，所以不会影响任何记录。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(editing) { onClose() } }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    blocked?.let { msg ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text("删不掉") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text("知道了") } },
        )
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
