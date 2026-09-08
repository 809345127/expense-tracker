package com.shize.expensetracker.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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

/// 标签小胶囊。对位 iOS `Components.swift` 的 `TagChip`。
@Composable
fun TagChip(name: String, colorIndex: Int, compact: Boolean = true) {
    val c = tagColor(colorIndex)
    Text(
        name,
        style = if (compact) MaterialTheme.typography.labelSmall
                else MaterialTheme.typography.labelMedium,
        color = c,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(c.copy(alpha = 0.15f))
            .padding(horizontal = if (compact) 7.dp else 9.dp,
                     vertical = if (compact) 2.dp else 4.dp),
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

// ---------------------------------------------------------------- 选择器（选 + 新建）

/// 记一笔页面里**直接平铺**的标签选择器。一次点击就选上／取消，没有弹层、没有确认按钮。
///
/// 跟 2026-09-08 之前那个底部弹层版比，少了两样东西，都是因为它长在表单里、不是临时浮层：
///   ① **那句「一笔可以打多个标签」的说明**——chip 能多选这件事点一下就知道了，
///      而表单里每一行说明都在跟真正要填的东西抢位置；
///   ② **「用在 N 笔」那个数字**——记账当下不关心这个标签历史上用过几次，
///      那个数只在「标签管理」那种盘点场景有用；挤在名字旁边还容易被读成金额。
///      （盘点场景仍然有：`TagManagerScreen` 用的就是 `vm.usage`。）
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPickerInline(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    vm: TagsViewModel = viewModel(),
) {
    val active by vm.tags.collectAsStateWithLifecycle()
    val all by vm.allTags.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // ⚠️ 停用（`isArchived`）的标签**平时不列出来，但已经挂在这笔账上的必须列出来**，
    // 否则那个选中状态在界面上根本看不见 —— 用户既不知道这笔挂着它、也没法取消。
    // （旧的弹层版是把已选标签在表单里另外只读显示一遍，才没暴露这个问题。）
    // 目前两端都还没有「停用标签」的入口，所以这一段现在跑不到；留着是为了以后加入口时不用回来补。
    val tags = remember(active, all, selected) {
        val extra = all.filter { it.isArchived && it.id in selected }
        if (extra.isEmpty()) active else active + extra
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            val isOn = tag.id in selected
            FilterChip(
                selected = isOn,
                onClick = { onSelectedChange(if (isOn) selected - tag.id else selected + tag.id) },
                label = { Text(tag.name) },
                leadingIcon = {
                    if (isOn) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                    else Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(tagColor(tag.colorIndex))
                    )
                },
            )
        }
        AssistChip(
            onClick = { creating = true },
            label = { Text("新建") },
            leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
        )
    }

    if (creating) {
        TagNameDialog(
            title = "新建标签",
            initial = "",
            confirmLabel = "建",
            onClose = { creating = false },
            onConfirm = { name ->
                vm.add(name) { id ->
                    if (id == null) message = "已经有一个叫「${name.trim()}」的标签了。"
                    else onSelectedChange(selected + id)   // 建完顺手选上，省一次点击
                }
            },
        )
    }
    message?.let { Notice(it) { message = null } }
}

// ---------------------------------------------------------------- 共用的小弹框

/// 输名字的弹框（新建和改名共用）。
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
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("标签名") }, singleLine = true,
                )
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text); onClose() },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

/// 一句话提示。⚠️ 重名这种情况**必须让用户看见** —— 静默不建的话，
/// 用户会以为按钮坏了，然后再点几次
@Composable
fun Notice(message: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onClose) { Text("好") } },
    )
}
