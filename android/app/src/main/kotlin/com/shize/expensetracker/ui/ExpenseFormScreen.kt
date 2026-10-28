package com.shize.expensetracker.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.shize.expensetracker.smart.ParsedDraft
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

    /// 编辑一笔已有的账时，把它现在挂着的标签读出来当初值。
    /// ⚠️ 用 `.first()` 现查一次，不订阅 Flow：这是**命令式**路径（进页面时取一次初值），
    /// 订阅的话每次别处改了关联都会把用户正在编辑的选择冲掉。
    /// （这个项目在导出页踩过反面的坑：`WhileSubscribed` 的 StateFlow 没人 collect 时
    ///  永远停在初始值，导出的 CSV 标签列全是空的、还不报错。）
    fun loadTags(expenseId: String, into: (Set<String>) -> Unit) = viewModelScope.launch {
        into(repo.tagIdsOf(expenseId).toSet())
    }

    /// `magnitude` 是键盘算出来的正数；`income` 决定存成正数还是负数（见 ExpenseEntity.amount）
    fun save(
        editing: ExpenseEntity?, magnitude: java.math.BigDecimal?, income: Boolean, categoryKey: String,
        note: String, isPrivate: Boolean, date: Long, tagIds: Set<String>,
        done: () -> Unit,
    ) = viewModelScope.launch {
        // ⚠️ 算不出来就**不保存**，绝不写 0（把一笔账静默变成 0 元比保存失败恶劣得多）
        val m = magnitude?.takeIf { it.signum() > 0 } ?: return@launch
        // ⚠️ 收入存成负数。界面上从头到尾只有正数，符号只在这一处加
        val amount = if (income) m.negate() else m
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

/// 「记一笔」/ 编辑。**长相照 iOS `ExpenseFormView` 做**（2026-10-13 用户要求全部照 iOS）：
///
///   · 顶栏：左「取消」、中间标题、右「保存」（浮起的白胶囊）；
///   · 第一张卡：「支出 | 收入」分段控件 + 大号金额（点它弹出底部计算器键盘）；
///   · 「分类」组：标题右边一个「管理」，下面五列的分类格子；
///   · 第三张卡：记账时间（日期、时刻两颗灰胶囊）/ 创建时间（只编辑时有）/ 备注 / 标签 / 私密开关；
///   · 编辑时最底下一张卡「删除这笔记录」（红字）。
///
/// 2026-09-05 补过的两样仍在：日期时间可以改（补记账）、标签可以打。
@Composable
fun ExpenseFormScreen(
    editing: ExpenseEntity?,
    onClose: () -> Unit,
    /// 智能记账的核对列表里点开某一笔时用：改完**不写库**，交回给核对列表（见 SmartEntryScreen）
    draft: ParsedDraft? = null,
    onDraftDone: ((ParsedDraft) -> Unit)? = null,
    vm: ExpenseFormViewModel = viewModel(),
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val allTags by viewModel<TagsViewModel>().allTags.collectAsStateWithLifecycle()

    // 金额算式（自定义键盘输进来的，可以带加减乘除）。**只有正数**，正负由 isIncome 决定。
    // 编辑时从金额的绝对值开始（收入存的是负数，界面上不显示负号）
    var expr by remember {
        mutableStateOf(
            editing?.let { AmountExpression.of(it.magnitude) }
                ?: draft?.let { AmountExpression.of(it.amount) } ?: AmountExpression()
        )
    }
    var isIncome by remember { mutableStateOf(editing?.isIncome ?: draft?.isIncome ?: false) }
    // 自定义键盘露没露出来。新建时一进来就露；编辑旧账时先收着，点金额那一行才出来
    var keypadShown by remember { mutableStateOf(editing == null && draft == null) }
    // 备注框拿到焦点（系统键盘升起来）时要把自定义键盘收掉，两个键盘不能叠着
    var noteFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var note by remember { mutableStateOf(editing?.note ?: draft?.note ?: "") }
    var chosenKey by remember { mutableStateOf(editing?.categoryKey ?: draft?.categoryKey ?: "") }
    var showCategories by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }

    // 私密开关的值。null = 用户还没自己拨过。
    // ⚠️ 「新建时默认跟随解锁态」这件事**每次渲染现算**，不在 LaunchedEffect 里事后补。
    // ⚠️ 为什么解锁态下新建要默认打私密：特意解锁进来多半就是为了记这一笔（跟 iOS `isPrivate = gate.isUnlocked` 一致）
    var privateChoice by remember { mutableStateOf(editing?.isPrivate ?: draft?.isPrivate) }
    var date by remember { mutableLongStateOf(editing?.date ?: draft?.date ?: System.currentTimeMillis()) }
    var tagIds by remember { mutableStateOf(draft?.tagIds ?: emptySet()) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    // 编辑已有记录时，把它现在挂着的标签读进来当初值。只读一次
    LaunchedEffect(editing?.id) {
        if (editing != null) vm.loadTags(editing.id) { tagIds = it }
    }

    /// 选中哪个分类 **每次渲染时现算**，不靠"先渲染再在别处补状态"（iOS 那边踩出来的教训）。
    /// 2026-10-01 起还要跟着「支出 / 收入」走：格子只显示当前这一种；切到收入时原来选的
    /// 「餐饮」不在这组里，就落到收入的第一个上，切回支出它又回来（chosenKey 一直记着）
    val shown = categories.filter { it.isIncome == isIncome }
    val effectiveKey = when {
        shown.any { it.id == chosenKey } -> chosenKey
        else -> shown.firstOrNull()?.id ?: ""
    }
    val amount = expr.value
    val canSave = amount != null && effectiveKey.isNotEmpty()
    val isPrivate = privateChoice ?: unlocked
    val selectedTags = allTags.filter { it.id in tagIds }
    val save = {
        if (draft != null && onDraftDone != null) {
            // 草稿：不写库，改好交回核对列表。人看过、改过了，就不再标黄
            if (amount != null && effectiveKey.isNotEmpty()) {
                onDraftDone(draft.copy(
                    amount = amount, isIncome = isIncome, categoryKey = effectiveKey, date = date,
                    note = note.trim(), tagIds = tagIds, isPrivate = isPrivate,
                    uncertain = false, selected = true,
                ))
                onClose()
            }
        } else vm.save(editing, amount, isIncome, effectiveKey, note, isPrivate, date, tagIds, onClose)
    }
    val c = Ios.c

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        IosSheetHeader(
            if (draft != null) "核对这一笔" else if (editing == null) "记一笔" else "编辑",
            leading = { IosPillButton("取消", onClose) },
            trailing = { IosPillButton("保存", { save() }, enabled = canSave, prominent = true) },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ---- 支出 / 收入 + 金额 ----
            // 收入不是靠输负号，是靠这个切换（键盘上的「−」只用来做减法，见 AmountExpression）
            IosCard(Modifier.padding(horizontal = Ios.sideInset)) {
                Column(Modifier.padding(horizontal = Ios.rowInset, vertical = 14.dp)) {
                    IosSegmented(listOf("支出", "收入"), if (isIncome) 1 else 0, { isIncome = it == 1 },
                                 Modifier.fillMaxWidth())
                    // 显示正在输的算式；带运算符时下面多一行「= 结果」。点它 → 收起系统键盘、露出自定义键盘
                    Column(
                        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)
                            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                                focusManager.clearFocus(); keypadShown = true
                            },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("¥", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = c.secondaryLabel)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (expr.isEmpty) "0.00" else expr.text,
                                fontSize = 34.sp, fontWeight = FontWeight.Bold,
                                color = when { expr.isEmpty -> c.tertiaryLabel; isIncome -> c.income; else -> c.label },
                                maxLines = 1,
                            )
                        }
                        if (expr.hasOperator) {
                            Text(
                                amount?.let { "= ${formatYuan(it)}" } ?: "算不出来",
                                style = Ios.subheadline.copy(fontWeight = FontWeight.Medium),
                                color = if (amount == null) c.red else c.secondaryLabel,
                            )
                        }
                    }
                }
            }

            // ---- 分类格子 ----
            // 「管理」放在标题右边：低频操作，不跟格子抢位置；记到一半发现分类不够用时不用退出去重进
            IosSection(
                header = "分类",
                headerTrailing = {
                    Text("管理", style = Ios.caption.copy(fontSize = 13.sp), color = c.accent,
                         modifier = Modifier.clickable { showCategories = true }.padding(4.dp))
                },
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    shown.chunked(5).forEach { rowCats ->
                        Row {
                            rowCats.forEach { cat ->
                                CategoryCell(cat, cat.id == effectiveKey, Modifier.weight(1f)) { chosenKey = cat.id }
                            }
                            repeat(5 - rowCats.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            // ---- 时间 / 备注 / 标签 / 私密 ----
            IosSection(
                // ⚠️ 这台机器没设锁屏密码 / 没录指纹时，私密开关其实是**没有牙的**：没有任何凭据可验，
                // 私密门只能直接放行（见 PrivacyGate.unlock）。既然如此就得当场说清楚，不能让人以为藏住了
                footer = if (unlocked && !hasDeviceLock(LocalContext.current))
                    "这台手机还没设锁屏密码，所以「私密」挡不住人 —— 去系统设置里加一个锁屏密码或指纹，它才有用。" else null,
            ) {
                row(first = true) {
                    Text("记账时间", style = Ios.body, color = c.label, modifier = Modifier.weight(1f))
                    DateCapsule(date.dateText()) { showDate = true }
                    Spacer(Modifier.width(6.dp))
                    DateCapsule(date.timeText().take(5)) { showTime = true }
                }
                if (editing != null) {
                    row(first = false) {
                        Text("创建时间", style = Ios.body, color = c.label, modifier = Modifier.weight(1f))
                        Text(fullStamp(editing.createdAt), style = Ios.body, color = c.secondaryLabel, maxLines = 1)
                    }
                }
                row(first = false) {
                    Text("备注", style = Ios.body, color = c.label)
                    Spacer(Modifier.width(16.dp))
                    IosInlineField(
                        note, { note = it }, "可选",
                        modifier = Modifier.onFocusChanged {
                            noteFocused = it.isFocused
                            // 备注框聚焦 → 收起自定义键盘；之后要改金额，点一下金额那行就回来
                            if (it.isFocused) keypadShown = false
                        },
                    )
                }
                // 标签：点进标签弹层选（跟 iOS 一样）。选了的直接在这一行全部列出来 ——
                // ⚠️ 不限量：这一行是「我给这笔挂了哪些标签」的答案，收成「+N」会被读成「只能挂 N 个」
                row(first = false, onClick = { showTags = true }) {
                    Text("标签", style = Ios.body, color = c.label)
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (selectedTags.isEmpty()) Text("可选", style = Ios.body, color = c.secondaryLabel)
                        else TagChipRow(selectedTags, limit = null, compact = false)
                    }
                    Spacer(Modifier.width(4.dp))
                    IosChevron()
                }
                // ⚠️⚠️ **锁着的时候这一行必须完全不存在，不是禁用、不是灰掉。**
                // 别人拿你手机点一下「记一笔」，只要看见「私密」两个字，「界面上完全无痕」就当场破功了
                // —— 而且比在设置里放个开关暴露得更彻底，因为记一笔是最顺手会被点开的地方。
                if (unlocked) {
                    row(first = false) {
                        Icon(Icons.Filled.Lock, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("私密", style = Ios.body, color = c.label, modifier = Modifier.weight(1f))
                        IosSwitch(isPrivate, { privateChoice = it })
                    }
                }
            }

            if (editing != null) {
                // iOS 是点了直接删、不再确认 —— 这一行本来就在最底下、要特意滑下来点，误碰的机会很小
                IosSection {
                    row(first = true, onClick = { vm.delete(editing, onClose) }) {
                        Text("删除这笔记录", style = Ios.body, color = c.red, textAlign = TextAlign.Center,
                             modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        // 自定义键盘贴在底部。放在列下面而不是叠在内容上：滚到最底下的备注、私密那几行不会被键盘压住
        androidx.compose.animation.AnimatedVisibility(
            visible = keypadShown && !noteFocused,
            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            AmountKeypad(onKey = { expr = expr.press(it) }, canSave = canSave, onSave = { save() })
        }
    }

    // ⚠️ 分类管理、标签选择是**叠在表单上面**的弹层，不是把表单替换掉：
    // 替换掉的话表单会离开 composition，它那些 `remember`（金额、选好的标签、改过的时间）全部失忆。
    IosSheetHost(if (showCategories) Unit else null, { showCategories = false }) {
        CategoryManagerScreen(onBack = { showCategories = false })
    }
    IosSheetHost(if (showTags) Unit else null, { showTags = false }) {
        TagPickerSheet(tagIds, { tagIds = it }, onDone = { showTags = false })
    }
    }

    // 日期：iOS 那种月历；时刻：iOS 那种滚轮（见 IosPickers.kt）。时刻沿用 / 日期沿用，秒数保留
    if (showDate) {
        val t = date.localDateTime()
        IosDatePickerDialog(t.toLocalDate(), { d ->
            date = composeTimestamp(d, t.hour, t.minute, keepSecondsFrom = date)
        }, { showDate = false })
    }
    if (showTime) {
        val t = remember { date.localDateTime() }
        IosTimePickerDialog(t.hour, t.minute, { h, m ->
            date = composeTimestamp(date.localDateTime().toLocalDate(), h, m, keepSecondsFrom = date)
        }, { showTime = false })
    }
}

/// iOS 紧凑日期选择器的样子：一颗灰底胶囊里写着当前值，点开再选
@Composable
private fun DateCapsule(text: String, onClick: () -> Unit) {
    Text(
        text, style = Ios.body, color = Ios.c.label,
        modifier = Modifier.clip(CircleShape).background(Ios.c.fill).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/// 「2026-10-13 12:19:19」—— 创建时间那一行（对位 iOS `fullStampTitle`）
private fun fullStamp(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

/// 分类格子：44×44 的圆角方块（选中涂满分类色、图标变白），下面一行小字名字（对位 iOS `categoryCell`）
@Composable
private fun CategoryCell(cat: CategoryEntity, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = categoryColor(cat.colorIndex)
    Column(
        modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(if (selected) color else color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(categoryIcon(cat.iconName), null, tint = if (selected) Color.White else color, modifier = Modifier.size(22.dp))
        }
        Text(cat.name, style = Ios.caption2, color = if (selected) Ios.c.label else Ios.c.secondaryLabel,
             maxLines = 1, textAlign = TextAlign.Center)
    }
}
