package com.shize.expensetracker.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.smart.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 智能记账页（2026-10-09 加，照 iOS `SmartEntryView` 做，样子按安卓的规矩来）。
//
// 入口：首页右下角「记一笔」上面那颗小按钮（✦）。iOS 那边是长按「+」——
// 那是 iOS 的系统交互（带主动作的菜单），安卓没有这个约定，长按 FAB 没人会想到去按，
// 所以安卓给一颗看得见的小按钮（Material 允许一大一小两颗 FAB 叠放）。
//
// 流程：写一段字 / 拍照 / 选图 / 粘贴图片 →「识别」→ 核对列表（逐笔改、勾选）→「保存 N 笔」。
// ⚠️ 核对列表里的东西只是草稿：点「保存」之前一个字都不进库、不会同步。

class SmartEntryViewModel(app: Application) : AndroidViewModel(app) {
    private val appState = App.from(app)
    private val repo = appState.repository
    private val ctx: Context get() = getApplication()

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val unlocked: StateFlow<Boolean> = appState.gate.unlocked

    enum class Phase { Input, Working, Review }

    // 状态放在 ViewModel 里：转屏、切深色模式时 Activity 会重建，放在 remember 里会全丢
    var phase by mutableStateOf(Phase.Input)
    var text by mutableStateOf("")
    val images = mutableStateListOf<Bitmap>()
    var result by mutableStateOf<SmartResult?>(null)
    val drafts = mutableStateListOf<ParsedDraft>()
    /// 第 4 级「什么都没认出来」时的提示，显示在输入页
    var failMessage by mutableStateOf("")
    var hasKey by mutableStateOf(DeepSeekKeyStore.load(app) != null)

    fun refreshKey() { hasKey = DeepSeekKeyStore.load(ctx) != null }

    fun addImages(uris: List<Uri>) = viewModelScope.launch {
        for (u in uris) {
            if (images.size >= MAX_IMAGES) break
            SmartImages.load(ctx, u)?.let { images += it }
        }
    }

    fun recognize() {
        failMessage = ""
        phase = Phase.Working
        val cats = categories.value.map { SmartCategory(it.id, it.name) }
        viewModelScope.launch {
            val r = SmartRecognizer.run(ctx, text, images.toList(), cats)
            if (r.level == SmartLevel.Nothing) {
                failMessage = "没认出任何一笔" +
                    (if (r.downgradeReason.isEmpty()) "" else "（${r.downgradeReason}）") + "。输入还在，改一改再试。"
                phase = Phase.Input
                return@launch
            }
            val priv = unlocked.value
            result = r
            drafts.clear()
            // 跟手动记一笔一致：解锁状态下新建的默认私密
            drafts += markDuplicates(r.drafts).map { it.copy(isPrivate = priv) }
            phase = Phase.Review
        }
    }

    /// 同一天、同金额、同收支已经有一笔 → 标「可能已记过」并默认不勾。
    ///
    /// ⚠️ 只跟**当前看得见的**记录比（锁着时不含私密记录）：
    /// 不然锁着的时候也会对一笔私密账提示「可能已记过」，等于说出了那笔私密账的存在
    private suspend fun markDuplicates(list: List<ParsedDraft>): List<ParsedDraft> {
        val existing = repo.observeAllExpenses(unlocked.value).first()
        val seen = existing.map { "${it.date.toLocalDate()}|${it.amount.clean().toPlainString()}" }.toSet()
        return list.map { d ->
            val signed = if (d.isIncome) d.amount.negate() else d.amount
            if ("${d.date.toLocalDate()}|${signed.clean().toPlainString()}" in seen)
                d.copy(duplicate = true, selected = false) else d
        }
    }

    fun replace(d: ParsedDraft) {
        val i = drafts.indexOfFirst { it.id == d.id }
        if (i >= 0) drafts[i] = d
    }

    fun saveAll(done: () -> Unit) = viewModelScope.launch {
        for (d in drafts.filter { it.selected }) {
            // ⚠️ 收入存成负数（见 ExpenseEntity.amount），符号只在这一处加
            repo.addExpense(
                amount = if (d.isIncome) d.amount.negate() else d.amount,
                categoryKey = d.categoryKey, note = d.note, date = d.date,
                isPrivate = d.isPrivate, tagIds = d.tagIds.toList(),
            )
        }
        done()
    }

    companion object {
        /// 一次最多几张图。多了上传慢、DeepSeek 也容易超时
        const val MAX_IMAGES = 5
    }
}

/// 智能记账弹层。**照 iOS `SmartEntryView` 做**（2026-10-13）：
/// 左上「取消」（核对时是「重新输入」）、右上齿轮（AI 设置）；
/// 输入页：白底大输入框 → 图片缩略图 → 「拍照 | 相册 | 粘贴」三颗浅蓝胶囊 → 一行说明 → 底部通栏蓝色「识别」；
/// 核对页：一张白卡，一行一笔（左边圆形勾、点行改、左滑删），底部通栏「保存 N 笔」。
@Composable
fun SmartEntryScreen(
    onClose: () -> Unit,
    debugText: String? = null,
    debugImage: String? = null,
    vm: SmartEntryViewModel = viewModel(),
) {
    var showSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ParsedDraft?>(null) }

    // debug 包：用 adb 带进来的预填文字 / 图片（省得在模拟器上打中文、传图）
    LaunchedEffect(Unit) {
        if (debugText != null && vm.text.isEmpty()) vm.text = debugText
        if (debugImage != null && vm.images.isEmpty()) vm.addImages(listOf(Uri.fromFile(File(debugImage))))
    }

    val review = vm.phase == SmartEntryViewModel.Phase.Review
    // 核对页按返回 = 回到输入页（输入原样保留），不是直接关掉
    BackHandler(enabled = review && editing == null && !showSettings) { vm.phase = SmartEntryViewModel.Phase.Input }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            IosSheetHeader(
                "智能记账",
                leading = {
                    if (review) IosPillButton("重新输入", { vm.phase = SmartEntryViewModel.Phase.Input })
                    else IosPillButton("取消", onClose)
                },
                trailing = { IosPillButton(icon = Icons.Outlined.Settings, contentDescription = "AI 设置", onClick = { showSettings = true }) },
            )
            Box(Modifier.weight(1f)) {
                if (review) ReviewList(vm) { editing = it } else InputPage(vm)
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = Ios.sideInset).padding(bottom = 8.dp, top = 6.dp)) {
                if (review) {
                    val n = vm.drafts.count { it.selected }
                    IosProminentButton(if (n == 0) "没有勾选" else "保存 $n 笔", { vm.saveAll(onClose) }, enabled = n > 0)
                } else {
                    val working = vm.phase == SmartEntryViewModel.Phase.Working
                    IosProminentButton(
                        if (working) "识别中…" else "识别", vm::recognize,
                        enabled = !working && (vm.text.isNotBlank() || vm.images.isNotEmpty()),
                        leading = {
                            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(20.dp))
                        },
                    )
                }
            }
        }

        // 改某一笔 / AI 设置：叠在上面一层弹层（替换掉的话核对列表会失忆，理由同 ExpenseFormScreen 里分类管理那段）
        IosSheetHost(editing, { editing = null }) { d ->
            ExpenseFormScreen(editing = null, draft = d, onDraftDone = { vm.replace(it) }, onClose = { editing = null })
        }
        IosSheetHost(if (showSettings) Unit else null, { showSettings = false; vm.refreshKey() }) {
            SmartSettingsScreen(onBack = { showSettings = false; vm.refreshKey() })
        }
    }
}

@Composable
private fun InputPage(vm: SmartEntryViewModel) {
    val ctx = LocalContext.current
    val working = vm.phase == SmartEntryViewModel.Phase.Working
    val room = SmartEntryViewModel.MAX_IMAGES - vm.images.size
    val c = Ios.c

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(SmartEntryViewModel.MAX_IMAGES)
    ) { uris -> vm.addImages(uris) }

    // 拍照：交给系统相机，照片写到我们给的 content:// 地址（见 res/xml/file_paths.xml 的 camera/）。
    // ⚠️ 不申请 CAMERA 权限：用系统相机 app 拍不需要；而一旦在清单里声明了它，反倒必须先申请才能调系统相机
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { vm.addImages(listOf(it)) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Ios.sideInset, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 白底大输入框（iOS 的 TextEditor + 圆角 14 的白底）
        androidx.compose.foundation.text.BasicTextField(
            vm.text, { vm.text = it }, enabled = !working,
            textStyle = Ios.body.copy(color = c.label),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().testTag("smartText"),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(14.dp)).background(c.card).padding(14.dp)) {
                    if (vm.text.isEmpty()) {
                        Text("写下要记的账，可以一次写好几笔\n比如：午饭 32 打车 18.5 昨天超市 126", style = Ios.body, color = c.tertiaryLabel)
                    }
                    inner()
                }
            },
        )

        if (vm.images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(vm.images) { i, bmp ->
                    Box {
                        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                              modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(24.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable(enabled = !working) { vm.images.removeAt(i) }
                                .semantics { contentDescription = "删掉第 ${i + 1} 张图" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }

        // 「拍照 | 相册 | 粘贴」：浅蓝底胶囊、蓝字（iOS `.bordered` + `.large`）
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val enabled = !working && room > 0
            TintedButton(Icons.Outlined.PhotoCamera, "拍照", enabled, Modifier.weight(1f)) {
                val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                cameraUri = uri
                takePhoto.launch(uri)
            }
            TintedButton(Icons.Outlined.PhotoLibrary, "相册", enabled, Modifier.weight(1f)) {
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            // 只看「剪贴板里是不是图」（看描述，不读内容），不会触发系统的「已读取剪贴板」提示
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            if (cm.primaryClipDescription?.hasMimeType("image/*") == true) {
                TintedButton(Icons.Outlined.ContentPaste, "粘贴", enabled, Modifier.weight(1f)) {
                    cm.primaryClip?.getItemAt(0)?.uri?.let { vm.addImages(listOf(it)) }
                }
            }
        }

        // 现在会走哪一级，提前说清楚，不让人以为 AI 在工作而其实没有
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (vm.hasKey) Icons.Filled.AutoAwesome else Icons.Outlined.ErrorOutline, null,
                 Modifier.size(15.dp), tint = c.secondaryLabel)
            Spacer(Modifier.width(8.dp))
            Text(
                if (vm.hasKey) "用你的 DeepSeek 识别，不通时自动改用本地规则"
                else "还没填 DeepSeek key，只能按本地规则拆文字（右上角设置）",
                style = Ios.footnote, color = c.secondaryLabel,
            )
        }
        if (vm.failMessage.isNotEmpty()) Text(vm.failMessage, style = Ios.footnote, color = c.red)
    }
}

/// 浅色主题色底 + 主题色字的大按钮（iOS `.buttonStyle(.bordered)` 的样子）
@Composable
private fun TintedButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean,
                         modifier: Modifier, onClick: () -> Unit) {
    val c = Ios.c
    val color = if (enabled) c.accent else c.tertiaryLabel
    Row(
        modifier.height(50.dp).clip(CircleShape).background(c.accent.copy(alpha = if (enabled) 0.15f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Ios.body, color = color)
    }
}

private val stampFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/// 核对页：一张白卡，组标题是「用了哪一级 · 共 N 笔」，组说明是操作提示（对位 iOS `reviewPage`）
@Composable
private fun ReviewList(vm: SmartEntryViewModel, onEdit: (ParsedDraft) -> Unit) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val byKey = remember(cats) { cats.associateBy { it.id } }
    val r = vm.result
    var swiped by remember { mutableStateOf<String?>(null) }
    val c = Ios.c

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 16.dp)) {
        Column(Modifier.padding(start = Ios.sideInset + Ios.rowInset + 4.dp, end = Ios.sideInset, bottom = 8.dp)) {
            Text("${r?.level?.title ?: ""} · 共 ${vm.drafts.size} 笔", style = Ios.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.label)
            if (r != null && r.downgradeReason.isNotEmpty()) {
                Text("没用上 AI：${r.downgradeReason}", style = Ios.footnote, color = c.orange)
            }
        }
        IosSection(footer = "点一行可以改；左滑删掉。标黄的是没把握的，保存前看一眼。") {
            vm.drafts.forEachIndexed { i, d ->
                IosSwipeRow(
                    open = swiped == d.id,
                    onOpenChange = { swiped = if (it) d.id else null },
                    actions = listOf(IosSwipeAction("删除", c.red) { swiped = null; vm.drafts.removeAll { it.id == d.id } }),
                ) {
                    DraftRow(i == 0, d, byKey[d.categoryKey], vm, onEdit)
                }
            }
        }
    }
}

@Composable
private fun IosSectionScope.DraftRow(first: Boolean, d: ParsedDraft, cat: CategoryEntity?, vm: SmartEntryViewModel, onEdit: (ParsedDraft) -> Unit) {
    val c = Ios.c
    // 没把握的标黄：分类是猜的 / 日期没认出来 / 本地规则拆的。
    // ⚠️ 底色必须是**不透明**的：这一行下面垫着左滑删除的红底，半透明的话会透出来
    val bg = if (d.uncertain) Color(0xFFFFCC00).copy(alpha = 0.16f).compositeOver(c.card) else c.card
    val catName = cat?.name ?: d.categoryKey
    Box(Modifier.background(bg)) {
        row(first = first, onClick = { onEdit(d) }, separatorIndent = Ios.rowInset + 44.dp) {
            // 没勾的那行整体调淡 —— 调的是内容不是底色（理由同上）
            Row(Modifier.alpha(if (d.selected) 1f else 0.55f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (d.selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    if (d.selected) "已勾选" else "未勾选",
                    tint = if (d.selected) c.accent else c.secondaryLabel,
                    modifier = Modifier.size(26.dp).clickable { vm.replace(d.copy(selected = !d.selected)) },
                )
                Spacer(Modifier.width(12.dp))
                IosCategoryIcon(categoryIcon(cat?.iconName ?: ""), categoryColor(cat?.colorIndex ?: 9), 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(d.note.ifEmpty { catName }, style = Ios.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 一行写完：可能已记过 · 日期 · 分类。窄屏上放不下就截断，不能折成竖排把右边的金额挤坏
                    val stamp = Instant.ofEpochMilli(d.date).atZone(ZoneId.systemDefault()).format(stampFmt)
                    Text(
                        buildAnnotatedString {
                            // 「可能已记过」排最前：放不下要截断时，截掉的是分类名，不是这句提醒
                            if (d.duplicate) withStyle(SpanStyle(color = c.orange)) { append("可能已记过  ") }
                            append(stamp)
                            if (d.note.isNotEmpty()) append("  $catName")
                        },
                        style = Ios.caption, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (d.isIncome) formatIncome(d.amount) else formatYuan(d.amount),
                    style = Ios.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (d.isIncome) c.income else c.label, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- AI 设置

/// AI 设置（照 iOS `SmartSettingsView`）：右上「完成」；「DeepSeek key」一张卡、「测试 key」一张卡、「模型」一张卡
@Composable
fun SmartSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(DeepSeekKeyStore.load(ctx)) }
    var newKey by remember { mutableStateOf("") }
    var textModel by remember { mutableStateOf(DeepSeekConfig.textModel(ctx)) }
    var visionModel by remember { mutableStateOf(DeepSeekConfig.visionModel(ctx)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    val c = Ios.c

    // 离开这一页就把模型名存下（返回键、「完成」都走这里）
    DisposableEffect(Unit) { onDispose { DeepSeekConfig.setModels(ctx, textModel, visionModel) } }

    IosSheetHeader("AI 设置", trailing = { IosPillButton("完成", onBack, prominent = true) })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        IosSection(
            header = "DeepSeek key",
            footer = "key 加密后只存在这台手机上（解密的钥匙在系统密钥库里、导不出来），不同步、不进备份、不会发给记账服务器。" +
                "建议在 DeepSeek 后台专门给它建一个 key、别充太多钱，万一泄露最多损失余额，后台一键作废。",
        ) {
            var first = true
            saved?.let { k ->
                // 存进去之后只给看末 4 位，完整的 key 界面上再也读不出来
                row(first = true) { IosValueRowContent("已保存", DeepSeekKeyStore.masked(k)) }
                first = false
            }
            row(first = first) {
                // ⚠️ 打码输入：这一屏可能在别人眼前打开
                IosInlineField(newKey, { newKey = it }, if (saved == null) "粘贴 DeepSeek API key" else "换一个新的 key",
                    align = TextAlign.Start, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
            }
            row(first = false, onClick = if (newKey.isNotBlank()) ({
                val k = newKey.trim()
                if (DeepSeekKeyStore.save(ctx, k)) { saved = k; newKey = ""; testResult = "" }
                else testResult = "存进系统密钥库失败"
            }) else null) {
                Text("保存 key", style = Ios.body, color = if (newKey.isNotBlank()) c.accent else c.tertiaryLabel)
            }
            if (saved != null) {
                row(first = false, onClick = { DeepSeekKeyStore.delete(ctx); saved = null; testResult = "" }) {
                    Text("删除 key", style = Ios.body, color = c.red)
                }
            }
        }

        IosSection(footer = testResult.ifEmpty { null }) {
            row(first = true, onClick = if (saved != null && !testing) ({
                val k = saved!!
                testing = true
                scope.launch {
                    testResult = try {
                        "key 能用。这个 key 能用的模型：" + DeepSeekClient.listModels(ctx, k).joinToString("、")
                    } catch (e: SmartError) { "不通：${e.message}" }
                    testing = false
                }
            }) else null) {
                Text("测试 key", style = Ios.body, color = if (saved != null) c.accent else c.tertiaryLabel, modifier = Modifier.weight(1f))
                if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.secondaryLabel)
            }
        }

        IosSection(header = "模型", footer = "一般不用改。识别报「模型不存在」时，点上面的「测试 key」，从列出来的名字里挑一个填进来。") {
            row(first = true) {
                Text("文字模型", style = Ios.body, color = c.label)
                Spacer(Modifier.width(16.dp))
                IosInlineField(textModel, { textModel = it }, "deepseek-chat")
            }
            row(first = false) {
                Text("看图模型", style = Ios.body, color = c.label)
                Spacer(Modifier.width(16.dp))
                IosInlineField(visionModel, { visionModel = it }, "deepseek-flash")
            }
        }
    }
}
