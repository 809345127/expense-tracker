package com.shize.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.data.TagEntity

/// 标签管理。**跟「分类管理」对称的一页**（都从「更多」菜单进）。
///
/// 跟分类那页刻意不同的两处，都是因为两种东西的性质不一样：
///
/// ① **标签可以随便删，分类不能。** 分类是必填的，删掉一个还有账目在用的分类，
///    那些账就指向一个不存在的分类了；标签是可选的，删掉只是那些账少一个标签，
///    金额和别的字段一个都不动。所以这里没有「用过就不给删」那条限制。
/// ② **标签可以随便改名，分类的代号不能改。** 标签的 id 是 UUID、跟名字无关；
///    分类的 id 就是名字算出来的代号，历史账目认的是它。这就是当初两种东西
///    一个用 UUID、一个用代号的原因（见 server/README.md 的 id 取法那张表）。
///
/// 没做拖动排序：标签的顺序基本是 iOS 那边定的，而这一页在安卓上主要是用来收拾
/// 「改个名 / 删掉一个不用了的」。真要排序再加，别为了对称而对称。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagerScreen(onBack: () -> Unit, vm: TagsViewModel = viewModel()) {
    val tags by vm.tags.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TagEntity?>(null) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("标签") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, "新建标签")
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        // ⚠️ 长相刻意跟「分类管理」**一模一样**（无底色 + 分隔线），不用明细页那种连续项组色块。
        // 两条理由：
        //   ① 这两页是姊妹关系（都从「更多」进、都是「收拾一批东西」），一个 app 里
        //      同一类页面出现两套列表样式就是不精致；
        //   ② Material 里这两种都对，但分工不同 —— 内容列表（明细、统计排行）用成块的卡片，
        //      设置型的可编辑列表用分隔线。管理页属于后者。
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                Text(
                    "标签是横着切的另一刀：分类回答「钱花在什么事上」，标签回答「属于哪一档」。"
                        + "一笔账可以打多个标签。\n"
                        + "删标签是安全的 —— 那些账目只是少一个标签，金额和其它内容不动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                )
            }

            if (tags.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), Alignment.Center) {
                        Text("还没有标签。点右上角的 ＋ 建一个。",
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            itemsIndexed(tags, key = { _, t -> t.id }) { _, tag ->
                TagRow(
                    tag = tag,
                    count = usage[tag.id] ?: 0,
                    onRename = { renaming = tag },
                    onDelete = { deleting = tag },
                )
                // 分隔线的左边距对齐正文（跳过前面那个色点），跟分类管理页同一个做法
                HorizontalDivider(Modifier.padding(start = 42.dp))
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (creating) {
        TagNameDialog(
            title = "新建标签", initial = "", confirmLabel = "建",
            onClose = { creating = false },
            onConfirm = { name ->
                vm.add(name) { id ->
                    if (id == null) message = "已经有一个叫「${name.trim()}」的标签了。"
                }
            },
        )
    }

    renaming?.let { tag ->
        TagNameDialog(
            title = "给标签改个名", initial = tag.name, confirmLabel = "保存",
            hint = "改名不影响已经打了这个标签的记录。",
            onClose = { renaming = null },
            onConfirm = { name ->
                vm.rename(tag, name) { ok ->
                    if (!ok) message = "已经有一个叫「${name.trim()}」的标签了。"
                }
            },
        )
    }

    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删掉标签「${tag.name}」？") },
            text = {
                Text(
                    "这个标签用在 ${usage[tag.id] ?: 0} 笔记录上。删掉之后那些记录会失去这个标签，"
                        + "金额和其它内容不受影响。另一台设备下次同步也会跟着删。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(tag); deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    message?.let { Notice(it) { message = null } }
}

@Composable
private fun TagRow(
    tag: TagEntity,
    count: Int,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp)
            .heightIn(min = 64.dp),   // 跟分类管理页的 ROW_HEIGHT 一样，两页并排看行高一致
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(tagColor(tag.colorIndex)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tag.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (count == 0) "还没用过" else "用在 $count 笔",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 两颗明确的按钮，不用长按 —— 长按藏起来的操作在这种「一年来一次」的页面上没人找得到。
        // ⚠️ 删除图标用**中性色**，不用红色：一列五个鲜红垃圾桶把整页的视觉重心全吸过去了，
        // 而且看着像在催人去删。红色留给确认框里那颗「删除」按钮 —— 那里才是真的要警示。
        IconButton(onClick = onRename) {
            Icon(Icons.Filled.Edit, "改名", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
