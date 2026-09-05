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
//   · `TagPickerSheet`   —— 只管**选**和**新建**，从「记一笔」进来，底部弹层
//   · `TagManagerScreen` —— 管**改名 / 删除**，从「更多 → 标签管理」进来，整页
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
/// ⚠️ `limit = null` 表示全显示。**表单里必须用 null**：那一行是「我给这笔挂了哪些标签」
/// 的答案，收成「+N」会被读成「只能挂 N 个」（iOS 那边用户 2026-08-18 真这么问过）。
/// 列表行仍然限量 —— 那里要跟金额、备注抢宽度。
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

    val tags: StateFlow<List<TagEntity>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

/// 给一笔账挑标签。底部弹层 —— 安卓做「临时选一下就回去」的标准答案：拇指够得着、下滑就关。
///
/// ⚠️ 这里**不做**改名和删除（见文件头）。要改要删走「更多 → 标签管理」。
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagPickerSheet(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    vm: TagsViewModel = viewModel(),
) {
    val tags by vm.tags.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("标签", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (tags.isEmpty())
                    "标签是横着切的另一刀：分类回答「钱花在什么事上」，标签回答「属于哪一档」"
                        + "—— 比如早饭 / 午饭 / 晚饭、出差、可报销。一笔可以打多个。"
                else "一笔可以打多个标签。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tags.forEach { tag ->
                    val isOn = tag.id in selected
                    FilterChip(
                        selected = isOn,
                        onClick = {
                            onSelectedChange(if (isOn) selected - tag.id else selected + tag.id)
                        },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tag.name)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${usage[tag.id] ?: 0}",
                                    style = MaterialTheme.typography.labelSmall,
                                    // ⚠️ 用 onSurfaceVariant，别更淡：这个项目量过对比度 ——
                                    // 三级灰对白底只有 1.84:1，小字可读下限 4.5:1。
                                    // 层级靠字号拉开，不靠涂淡。
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
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

            if (selected.isNotEmpty()) {
                TextButton(onClick = { onSelectedChange(emptySet()) }) { Text("全部取消") }
            }
        }
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
