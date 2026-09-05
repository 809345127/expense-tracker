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
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
import com.shize.expensetracker.hasDeviceLock
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.TagEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class ExpenseFormViewModel(app: Application) : AndroidViewModel(app) {
    private val appState = App.from(app)
    private val repo = appState.repository

    /// ⚠️ 表单要用它决定「私密」那一行**存不存在**（不是禁用、不是灰掉），见下面的注释
    val unlocked: StateFlow<Boolean> = appState.gate.unlocked

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /// ⚠️ 含已归档的：历史账目上挂着的归档标签，编辑时要能显示出名字
    val tags: StateFlow<List<TagEntity>> =
        repo.observeAllTags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /// 编辑一笔已有的账时，把它现在挂着的标签读出来当初值。
    /// ⚠️ 用 `.first()` 现查一次，不订阅 Flow：这是**命令式**路径（进页面时取一次初值），
    /// 订阅的话每次别处改了关联都会把用户正在编辑的选择冲掉。
    /// （这个项目在导出页踩过反面的坑：`WhileSubscribed` 的 StateFlow 没人 collect 时
    ///  永远停在初始值，导出的 CSV 标签列全是空的、还不报错。）
    fun loadTags(expenseId: String, into: (Set<String>) -> Unit) = viewModelScope.launch {
        into(repo.tagIdsOf(expenseId).toSet())
    }

    fun save(
        editing: ExpenseEntity?, amountText: String, categoryKey: String,
        note: String, isPrivate: Boolean, date: Long, tagIds: Set<String>,
        done: () -> Unit,
    ) = viewModelScope.launch {
        // ⚠️ 金额一定要走 parseAmount：解析不出来就**不保存**，绝不写 0
        //（把一笔账静默变成 0 元比保存失败恶劣得多，见 Money.kt 顶部）
        val amount = parseAmount(amountText) ?: return@launch
        if (categoryKey.isEmpty()) return@launch
        if (editing == null) {
            repo.addExpense(amount, categoryKey, note.trim(),
                            date = date, isPrivate = isPrivate, tagIds = tagIds.toList())
        } else {
            repo.updateExpense(editing, amount = amount, categoryKey = categoryKey,
                               note = note.trim(), date = date, isPrivate = isPrivate)
            // ⚠️ 标签是**独立记录**，不跟着账目那条一起走 —— 必须单独调一次。
            // setTags 会把多出来的置墓碑、少的建出来（取消一个标签也得留墓碑，
            // 否则另一台设备察觉不到，因为账目那条记录的字段一个都没变）
            repo.setTags(editing.id, tagIds.toList())
        }
        done()
    }

    fun delete(e: ExpenseEntity, done: () -> Unit) = viewModelScope.launch {
        repo.deleteExpense(e)
        done()
    }
}

/// 「记一笔」/ 编辑。
///
/// 2026-09-05 补了两样**原来没有**的东西：
///   ① **日期和时间可以改了** —— 在这之前安卓只能记「此刻」，等于**补记不了账**
///      （而列表里那个「· 补记」标记因此永远不可能出现在安卓记的账上）。iOS 一直有。
///   ② **标签可以打了** —— 在这之前安卓端标签只有数据层，界面上一个入口都没有。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseFormScreen(
    editing: ExpenseEntity?,
    onClose: () -> Unit,
    vm: ExpenseFormViewModel = viewModel(),
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val allTags by vm.tags.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()

    var amountText by remember {
        mutableStateOf(editing?.amount?.toPlainString() ?: "")
    }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var chosenKey by remember { mutableStateOf(editing?.categoryKey ?: "") }
    var showCategories by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }

    // 私密开关的值。null = 用户还没自己拨过。
    //
    // ⚠️ 「新建时默认跟随解锁态」这件事**每次渲染现算**，不在 LaunchedEffect 里事后补 ——
    // 这个项目记着「别指望先渲染、再在 onAppear 里补状态」那条教训。现算就没有先后顺序问题。
    // ⚠️ 为什么解锁态下新建要默认打私密：特意解锁进来多半就是为了记这一笔。
    // 不想私密的话那一行就在眼前，拨回去即可。（跟 iOS `isPrivate = gate.isUnlocked` 一致。）
    var privateChoice by remember { mutableStateOf(editing?.isPrivate) }
    var date by remember { mutableLongStateOf(editing?.date ?: System.currentTimeMillis()) }
    var tagIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    // 编辑已有记录时，把它现在挂着的标签读进来当初值。只读一次
    LaunchedEffect(editing?.id) {
        if (editing != null) vm.loadTags(editing.id) { tagIds = it }
    }

    /// 选中哪个分类 **每次渲染时现算**，不靠"先渲染再在别处补状态"。
    /// ⚠️ 这是 iOS 那边踩出来的教训：分类是库里的数据，界面第一帧还没有，
    /// 在事后回调里补 state 会「时好时坏」（懒加载的行不一定重画）。
    /// 现算就没有先后顺序问题 —— 数据到了那一帧结果就是对的。
    val effectiveKey = when {
        categories.any { it.id == chosenKey } -> chosenKey
        else -> categories.firstOrNull()?.id ?: ""
    }
    val canSave = parseAmount(amountText) != null && effectiveKey.isNotEmpty()
    val isPrivate = privateChoice ?: unlocked
    val selectedTags = remember(allTags, tagIds) { allTags.filter { it.id in tagIds } }

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
                    // ⚠️ 用实心 Button 而不是原来那颗灰扑扑的 TextButton：
                    // 这是这一页**唯一的主动作**，Material 的规矩是主动作要用填充按钮。
                    // 原来那个禁用态几乎看不见，用户不知道为什么按不了
                    Button(
                        onClick = {
                            vm.save(editing, amountText, effectiveKey, note, isPrivate,
                                    date, tagIds, onClose)
                        },
                        enabled = canSave,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("保存") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
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
                    modifier = Modifier.weight(1f).focusRequester(amountFocus),
                )
            }

            // 新建时光标直接落在金额上、键盘自动弹出来 —— 记一笔的第一件事就是输金额，
            // 少一次点击。⚠️ 编辑已有记录时**不要**抢焦点：那时用户多半是来改分类或备注的，
            // 键盘弹出来反而挡住下半屏。（对位 iOS 的 `amountFocused = true`，它也只在新建时设。）
            LaunchedEffect(Unit) { if (editing == null) amountFocus.requestFocus() }

            // ---- 分类九宫格 ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("分类")
                Spacer(Modifier.weight(1f))
                // 管理入口放在标题右边：它是低频操作，不该跟十几个高频的分类格子抢位置。
                // 有了它，记账记到一半发现分类不够用时不用退出去重进（对位 iOS 那颗「管理」）
                TextButton(onClick = { showCategories = true }) { Text("管理") }
            }
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

            // ---- 时间 ----
            SectionTitle("时间")
            Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                PickerRow(
                    icon = Icons.Filled.Event, label = "日期", value = date.dateText(),
                    shape = groupedShape(0, 2), onClick = { showDate = true },
                )
                PickerRow(
                    icon = Icons.Filled.Schedule, label = "时刻", value = date.timeText(),
                    shape = groupedShape(1, 2), onClick = { showTime = true },
                )
            }

            // ---- 标签 ----
            SectionTitle("标签")
            Surface(
                onClick = { showTags = true },
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Sell, null, Modifier.size(20.dp),
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    if (selectedTags.isEmpty()) {
                        Text("点这里给它打标签（可选）",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // ⚠️ 这里**必须全显示**（limit = null）：这一行是「我给这笔挂了哪些标签」
                        // 的答案，收成「+N」会被读成「只能挂 N 个」（iOS 那边用户真这么问过）
                        TagChipRow(selectedTags, limit = null, compact = false)
                    }
                }
            }

            // ---- 备注 ----
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            // ---- 私密 ----
            //
            // ⚠️⚠️ **锁着的时候这一段必须完全不存在，不是禁用、不是灰掉。**
            // 别人拿你手机点一下「记一笔」，只要看见「私密记录」四个字，
            // 「界面上完全无痕」这件事就当场破功了 —— 而且比在设置里放个开关暴露得更彻底，
            // 因为记一笔是最顺手会被点开的地方。
            //
            // 这跟顶栏那颗锁头图标是同一条红线（2026-09-05 撤掉的那个）。
            // 当时只撤了顶栏、漏了这里，是用户自己发现的：
            // 「无论是不是在私密模式下，记一笔的最下面也有『私密记录』的选项」。
            // iOS 那边一直是对的（`if gate.isUnlocked { Toggle }`），安卓漏抄了。
            if (unlocked) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("私密记录", style = MaterialTheme.typography.bodyLarge)
                    // ⚠️ 这台机器没设锁屏密码 / 没录指纹时，这个开关其实是**没有牙的**：
                    // 没有任何凭据可验，私密门只能直接放行（见 PrivacyGate.unlock ——
                    // 那样做比"永久锁死、自己也拿不回来"诚实）。
                    // 既然如此就得**当场说清楚**，不能让人以为记了私密就藏住了。
                    if (hasDeviceLock(LocalContext.current)) {
                        Text("锁着的时候整个 app 当它不存在（列表、合计、统计、小组件、导出全排除）",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("这台手机还没设锁屏密码，所以这个开关挡不住人 —— 去系统设置里加一个锁屏密码或指纹，它才有用。",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = isPrivate, onCheckedChange = { privateChoice = it })
            }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ⚠️ 分类管理是**盖在表单上面**的一层，不是把表单替换掉。
    // 替换掉的话表单会离开 composition，它那些 `remember`（金额、选好的标签、改过的时间）
    // 全部失忆 —— 用户从分类管理退回来会发现自己白填了。
    // 盖一层则表单一直在树里，状态原样保留。
    if (showCategories) {
        BackHandler { showCategories = false }
        Surface(Modifier.fillMaxSize()) {
            CategoryManagerScreen(onBack = { showCategories = false })
        }
    }

    if (showTags) {
        TagPickerSheet(
            selected = tagIds,
            onSelectedChange = { tagIds = it },
            onDismiss = { showTags = false },
        )
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        // ⚠️ `selectedDateMillis` 是**UTC 当天零点**（Material 的约定），
                        // 直接当本地时间戳用会在东八区差 8 小时、日期可能整个错一天。
                        // 正确做法：只取它的「年月日」，时刻沿用用户原来那个。
                        val d = Instant.ofEpochMilli(picked).atZone(ZoneId.of("UTC")).toLocalDate()
                        val t = date.localDateTime()
                        date = composeTimestamp(d, t.hour, t.minute, keepSecondsFrom = date)
                    }
                    showDate = false
                }) { Text("好") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val t = remember { date.localDateTime() }
        val state = rememberTimePickerState(initialHour = t.hour, initialMinute = t.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("时刻") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    date = composeTimestamp(
                        date.localDateTime().toLocalDate(),
                        state.hour, state.minute, keepSecondsFrom = date,
                    )
                    showTime = false
                }) { Text("好") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } },
        )
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall,
         modifier = Modifier.padding(start = 4.dp))
}

/// 「日期 / 时刻」那两行。左边图标 + 名字，右边当前值，整行可点。
@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
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
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(20.dp),
                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CategoryCell(c: CategoryEntity, selected: Boolean, onClick: () -> Unit) {
    val color = categoryColor(c.colorIndex)
    Column(
        Modifier.clip(RoundedCornerShape(14.dp))
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
