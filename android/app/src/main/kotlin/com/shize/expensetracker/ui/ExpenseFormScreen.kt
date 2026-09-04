package com.shize.expensetracker.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ExpenseFormViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = App.from(app).repository

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(
        editing: ExpenseEntity?, amountText: String, categoryKey: String,
        note: String, isPrivate: Boolean, done: () -> Unit,
    ) = viewModelScope.launch {
        // ⚠️ 金额一定要走 parseAmount：解析不出来就**不保存**，绝不写 0
        //（把一笔账静默变成 0 元比保存失败恶劣得多，见 Money.kt 顶部那段）
        val amount = parseAmount(amountText) ?: return@launch
        if (categoryKey.isEmpty()) return@launch
        if (editing == null) {
            repo.addExpense(amount, categoryKey, note.trim(), isPrivate = isPrivate)
        } else {
            repo.updateExpense(editing, amount = amount, categoryKey = categoryKey,
                               note = note.trim(), isPrivate = isPrivate)
        }
        done()
    }

    fun delete(e: ExpenseEntity, done: () -> Unit) = viewModelScope.launch {
        repo.deleteExpense(e)
        done()
    }
}

/// 「记一笔」/ 编辑。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseFormScreen(
    editing: ExpenseEntity?,
    onClose: () -> Unit,
    vm: ExpenseFormViewModel = viewModel(),
) {
    val categories by vm.categories.collectAsStateWithLifecycle()

    var amountText by remember {
        mutableStateOf(editing?.amount?.toPlainString() ?: "")
    }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var isPrivate by remember { mutableStateOf(editing?.isPrivate ?: false) }
    var chosenKey by remember { mutableStateOf(editing?.categoryKey ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    /// 选中哪个分类 **每次渲染时现算**，不靠"先渲染再在别处补状态"。
    /// ⚠️ 这是 iOS 那边踩出来的教训：分类是库里的数据，界面第一帧还没有，
    /// 在事后回调里补 state 会「时好时坏」（懒加载的行不一定重画）。
    /// 现算就没有先后顺序问题 —— 数据到了那一帧结果就是对的。
    val effectiveKey = when {
        categories.any { it.id == chosenKey } -> chosenKey
        else -> categories.firstOrNull()?.id ?: ""
    }
    val canSave = parseAmount(amountText) != null && effectiveKey.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing == null) "记一笔" else "编辑") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "取消") }
                },
                actions = {
                    if (editing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, "删除",
                                 tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = {
                            vm.save(editing, amountText, effectiveKey, note, isPrivate, onClose)
                        },
                        enabled = canSave,
                    ) { Text("保存") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 金额 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¥", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = amountText,
                    onValueChange = {
                        // ⚠️⚠️ 每次输入都净化。中文输入法的全角句号「。」如果漏过去，
                        // 12。75 会被解析歪 —— iOS 那边就是这么把一笔账静默记成 12 块的。
                        // 详见 Money.kt 顶部
                        amountText = sanitizeAmount(it)
                    },
                    placeholder = { Text("0.00", fontSize = 30.sp) },
                    textStyle = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- 分类九宫格 ----
            Text("分类", style = MaterialTheme.typography.titleSmall)
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(categories, key = { it.id }) { c ->
                    CategoryCell(c, selected = c.id == effectiveKey) { chosenKey = c.id }
                }
            }

            // ---- 备注 ----
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            // ---- 私密 ----
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("私密记录", style = MaterialTheme.typography.bodyLarge)
                    Text("锁着的时候整个 app 当它不存在（列表、合计、统计全排除）",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉这笔？") },
            text = { Text("删掉之后另一台设备下次同步也会跟着删。") },
            confirmButton = {
                TextButton(onClick = { vm.delete(editing, onClose) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CategoryCell(c: CategoryEntity, selected: Boolean, onClick: () -> Unit) {
    val color = categoryColor(c.colorIndex)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) color else color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            categoryIcon(c.iconName), contentDescription = null,
            tint = if (selected) Color.White else color,
            modifier = Modifier.size(20.dp),
        )
        Text(
            c.name, style = MaterialTheme.typography.labelSmall,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}
