package com.shize.expensetracker.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.TagEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// 标签的界面。**这一整块是 2026-09-05 新加的。**
//
// 在这之前安卓端的标签只有数据层：同步引擎四种记录都合并、统计页有「按标签排行」、
// 导出 CSV 里也有标签列 —— 但**界面上一个入口都没有**：明细行不显示标签、
// 记一笔时打不了标签、也没法新建/改名/删除。
// 所以从用户角度看就是「安卓上没有标签这个东西」，虽然数据一直在同步。
//
// 分工（刻意跟已有的分类那套对称）：
//   · `TagPickerInline`  —— 只管**选**和**新建**，直接长在「记一笔」表单里
//   · `TagManagerScreen` —— 管**改名 / 删除**，从「更多 → 标签管理」进来，整页
//
// ⚠️ 2026-09-08 之前选择器是一个**底部弹层**（`TagPickerSheet`，已删）。
// 换掉的理由见 `ExpenseFormScreen` 里「标签」那一段的注释 —— 简单说是：
// 分类在表单里直接铺着、标签却藏在弹层后面，同一页上两件同性质的事一个铺开一个藏起来。
//
// ⚠️ 为什么不把改名/删除也塞进选择器（iOS 那边是塞在一起的，靠左滑）：
// 左滑改名/删除在安卓上没有对应物，硬做手感是别人家的；而给每个 chip 加长按菜单会跟
// FilterChip 自己的点击抢事件（chip 内部就有一个 clickable，外层长按拿不到 down 事件）。
// 拆成两个入口更安卓、也更好找 —— 这个 app 里「分类管理」本来就是一个独立页面。

// ---------------------------------------------------------------- 小胶囊

/// 标签小胶囊。对位 iOS `Components.swift` 的 `TagChip`（字号 caption2 / caption，左右 6 / 8、上下 2 / 3）。
@Composable
fun TagChip(name: String, colorIndex: Int, compact: Boolean = true) {
    val c = tagColor(colorIndex)
    Text(
        name,
        style = if (compact) Ios.caption2 else Ios.caption,
        color = c,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(c.copy(alpha = 0.15f))
            .padding(horizontal = if (compact) 6.dp else 8.dp,
                     vertical = if (compact) 2.dp else 3.dp),
    )
}

/// 一行标签，超出 `limit` 个收成「+N」。
///
/// ⚠️ `limit = null` 表示全显示。**凡是「我给这笔挂了哪些标签」的答案都要用 null**：
/// 收成「+N」会被读成「只能挂 N 个」（iOS 那边用户 2026-08-18 真这么问过）。
/// 只有明细列表行是限量的 —— 那一行要跟金额抢宽度，而且它是概览、不是答案。
/// （安卓表单现在不走这个组件了，改成可点的 `FilterChip`，见 `TagPickerInline`。）
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(tags: List<TagEntity>, limit: Int? = 2, compact: Boolean = true) {
    if (tags.isEmpty()) return
    val shown = if (limit == null) tags else tags.take(limit)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { TagChip(it.name, it.colorIndex, compact) }
        if (tags.size > shown.size) {
            Text(
                "+${tags.size - shown.size}",
                style = Ios.caption2,
                color = Ios.c.secondaryLabel,
                // ⚠️ FlowRow 没有 verticalAlignment 这个参数，垂直对齐要在子项上用
                // FlowRowScope 的 Modifier.align（写成参数编译不过）
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

// ---------------------------------------------------------------- ViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModel(app: Application) : AndroidViewModel(app) {
    private val appState = App.from(app)
    private val repo = appState.repository

    /// 可选的标签（去掉墓碑，也去掉停用的）
    val tags: StateFlow<List<TagEntity>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 含停用的。⚠️ 只给 `TagPickerInline` 用来把「已经挂在这笔账上的停用标签」补回列表里，
    /// 别拿它当可选列表 —— 那样停用就等于没停用
    val allTags: StateFlow<List<TagEntity>> =
        repo.observeAllTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 每个标签用在多少笔账上。
    ///
    /// ⚠️⚠️ **必须过私密门**（`observeAllExpenses(unlocked)` 里已经过了）。
    /// 不过的话，锁着的时候「咖啡 15 笔」会把私密那几笔算进去，而按这个标签筛出来只有 12 笔
    /// —— **那个对不上的数就是最容易露馅的地方**，别人一比就知道藏了东西、还知道藏了几笔。
    /// 这是这个项目的第一条红线（见 PrivacyGate 的注释）。
    ///
    /// 📌 顺带记一条两端差异：**iOS 那边这个数没过私密门**（`TagPickerView` 里是
    /// `tag.expenses.alive.count`，`alive` 只摘墓碑、不管私密）。那是 iOS 侧的一个小漏，
    /// 安卓这边不照抄。
    val usage: StateFlow<Map<String, Int>> =
        appState.gate.unlocked
            .flatMapLatest { unlocked ->
                combine(repo.observeAllExpenses(unlocked), repo.observeLinks()) { expenses, links ->
                    val alive = expenses.map { it.id }.toSet()
                    links.filter { it.expenseId in alive }
                        .groupingBy { it.tagId }.eachCount()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /// 新建。名字空的或者重名会回 null —— 界面上要给一句提示，不能静默吞掉
    fun add(name: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(repo.addTag(name))
    }

    fun rename(tag: TagEntity, name: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(repo.renameTag(tag, name))
    }

    fun delete(tag: TagEntity) = viewModelScope.launch { repo.deleteTag(tag) }
}

// ---------------------------------------------------------------- 选择器（选 + 新建 + 改名 + 删除）

/// 标签选择弹层。**照 iOS `TagPickerView` 做**（2026-10-13）：从「记一笔」的「标签」那一行点进来，
/// 左上「清空」、右上「完成」；中间一张白卡，一行一个标签（色点 + 名字 + 用了几笔 + 勾）；
/// 最底下一个搜索框，**输入一个还没有的名字就多出一行「新建标签『xx』」**；
/// 往左滑一行出「改名」「删除」。
///
/// 安卓原来是表单里直接铺一排 chip、另有一个「标签管理」页；iOS 没有单独的管理页，
/// 改名和删除就在这个选择器里左滑做，现在两边一样了。
///
/// 选中状态是**直接改外面的**（没有「取消」）：跟 iOS 一样，「完成」只是关掉。
@Composable
fun TagPickerSheet(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onDone: () -> Unit,
    vm: TagsViewModel = viewModel(),
) {
    val active by vm.tags.collectAsStateWithLifecycle()
    val all by vm.allTags.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var swiped by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<TagEntity?>(null) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val c = Ios.c

    // ⚠️ 停用（`isArchived`）的标签**平时不列出来，但已经挂在这笔账上的必须列出来**，
    // 否则那个选中状态在界面上根本看不见 —— 用户既不知道这笔挂着它、也没法取消。
    val tags = remember(active, all, selected) {
        val extra = all.filter { it.isArchived && it.id in selected }
        if (extra.isEmpty()) active else active + extra
    }
    val key = search.trim().lowercase()
    val visible = if (key.isEmpty()) tags else tags.filter { it.name.lowercase().contains(key) }
    // 输入的名字还没有 → 顶上多一行「新建」。重名（不分大小写、去首尾空格）不给建
    val newName = search.trim().takeIf { n -> n.isNotEmpty() && tags.none { it.name.trim().lowercase() == n.lowercase() } }

    IosSheetHeader(
        "标签",
        leading = { IosPillButton("清空", { onSelectedChange(emptySet()) }, enabled = selected.isNotEmpty()) },
        trailing = { IosPillButton("完成", onDone, prominent = true) },
    )
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (newName != null) {
                IosSection {
                    row(first = true, onClick = {
                        vm.add(newName) { id ->
                            if (id == null) message = "已经有一个叫「$newName」的标签了。"
                            else { onSelectedChange(selected + id); search = "" }   // 建完顺手选上
                        }
                    }) {
                        Icon(Icons.Filled.AddCircle, null, tint = c.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("新建标签「$newName」", style = Ios.body, color = c.accent)
                    }
                }
            }
            if (tags.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Sell, null, tint = c.secondaryLabel, modifier = Modifier.size(44.dp))
                    Text("还没有标签", style = Ios.title2.copy(fontSize = 20.sp), color = c.label)
                    Text("在下面搜索框里直接输入名字就能新建，比如「出差」「可报销」「请客」。",
                         style = Ios.subheadline, color = c.secondaryLabel, textAlign = TextAlign.Center)
                }
            } else if (visible.isNotEmpty()) {
                IosSection(footer = "一笔可以打多个标签。左滑某个标签可以改名或删除。") {
                    visible.forEachIndexed { i, tag ->
                        IosSwipeRow(
                            open = swiped == tag.id,
                            onOpenChange = { swiped = if (it) tag.id else null },
                            actions = listOf(
                                IosSwipeAction("删除", c.red) { swiped = null; deleting = tag },
                                IosSwipeAction("改名", c.orange) { swiped = null; renaming = tag },
                            ),
                        ) {
                            row(first = i == 0, separatorIndent = Ios.rowInset + 20.dp, onClick = {
                                onSelectedChange(if (tag.id in selected) selected - tag.id else selected + tag.id)
                            }) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(tag.colorIndex)))
                                Spacer(Modifier.width(10.dp))
                                Text(tag.name, style = Ios.body, color = c.label, modifier = Modifier.weight(1f))
                                Text("${usage[tag.id] ?: 0}", style = Ios.caption, color = c.tertiaryLabel)
                                if (tag.id in selected) {
                                    Spacer(Modifier.width(10.dp))
                                    Icon(Icons.Filled.Check, "已选", tint = c.accent, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        // 底部搜索框（iOS 26 的 .searchable 在弹层里就放在最底下）
        Row(
            Modifier.padding(horizontal = Ios.sideInset, vertical = 10.dp).fillMaxWidth().height(48.dp)
                .iosFloating(CircleShape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = c.label, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            IosInlineField(search, { search = it }, "搜索，或输入新标签名", align = TextAlign.Start)
        }
    }

    renaming?.let { tag ->
        var text by remember(tag.id) { mutableStateOf(tag.name) }
        IosAlert(
            "重命名标签", null,
            buttons = listOf(
                IosAlertButton("取消", cancel = true) { renaming = null },
                IosAlertButton("保存", enabled = text.isNotBlank()) {
                    val name = text
                    renaming = null
                    vm.rename(tag, name) { ok -> if (!ok) message = "已经有一个叫「${name.trim()}」的标签了。" }
                },
            ),
            onDismiss = { renaming = null },
            field = { IosAlertField(text, { text = it }, "标签名") },
        )
    }
    deleting?.let { tag ->
        IosAlert(
            "删除标签「${tag.name}」？",
            "这个标签用在 ${usage[tag.id] ?: 0} 笔记录上。删掉后那些记录会失去这个标签，金额和其它内容不受影响。",
            buttons = listOf(
                IosAlertButton("取消", cancel = true) { deleting = null },
                IosAlertButton("删除", destructive = true) {
                    onSelectedChange(selected - tag.id); vm.delete(tag); deleting = null
                },
            ),
            onDismiss = { deleting = null },
        )
    }
    message?.let { Notice(it) { message = null } }
}

// ---------------------------------------------------------------- 共用的小弹框

/// 输名字的弹框（新建和改名共用）。iOS 弹框长相
@Composable
fun TagNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    hint: String? = null,
    onClose: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    IosAlert(
        title, hint,
        buttons = listOf(
            IosAlertButton("取消", cancel = true, onClick = onClose),
            IosAlertButton(confirmLabel, enabled = text.isNotBlank()) { onConfirm(text); onClose() },
        ),
        onDismiss = onClose,
        field = { IosAlertField(text, { text = it }, "标签名") },
    )
}

/// 一句话提示。⚠️ 重名这种情况**必须让用户看见** —— 静默不建的话，
/// 用户会以为按钮坏了，然后再点几次
@Composable
fun Notice(message: String, onClose: () -> Unit) {
    IosAlert(message, null, listOf(IosAlertButton("好", onClick = onClose)), onClose)
}
