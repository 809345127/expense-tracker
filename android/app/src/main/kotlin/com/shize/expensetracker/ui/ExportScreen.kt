package com.shize.expensetracker.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.time.YearMonth

// 导出。两条路：
//   · CSV  —— 给 AI 分析用（也能用 Excel 打开）。可以直接复制成文本，也能导成文件分享出去。
//   · 长图 —— 存档/发人看。
//
// ⚠️⚠️ **导出的内容先过私密门**：锁着的时候导出的东西和屏幕上看到的完全一致，
// 不会出现「界面上藏着、导出来却带出去了」这种后门。解锁态导出时文件名里带个「含私密」
// 标记 —— 免得你解锁时导了一份、回头忘了，直接发给别人。

enum class ExportScope(val label: String) { MONTH("本月"), ALL("全部") }

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModel(app: Application) : AndroidViewModel(app) {
    private val appState = App.from(app)
    private val repo = appState.repository

    val month: StateFlow<YearMonth> = appState.month
    val unlocked: StateFlow<Boolean> = appState.gate.unlocked

    private val _scope = MutableStateFlow(ExportScope.MONTH)
    val scope: StateFlow<ExportScope> = _scope
    fun setScope(s: ExportScope) { _scope.value = s }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    /// 导出要用到的分类 / 标签 / 关联，**现查一次**，不走 StateFlow。
    ///
    /// ⚠️⚠️ 这里踩过一个不报错的坑：原来这三样是 `stateIn(..., WhileSubscribed(5000), emptyList())`，
    /// 而导出这一页**压根没有 collect 它们**（界面上只显示笔数和金额）。
    /// `WhileSubscribed` 是「有人订阅才开始查」，所以它们三个永远停在初始值**空列表** ——
    /// 导出来的 CSV 里标签那一列全是空的。
    ///
    /// 而且这个错**差点被我自己的测试放过去**：分类那一列看起来是对的，
    /// 因为按协议「分类的 id 就是它的代号，而代号取自建它时的名字」，
    /// 测试数据里 id 和 name 恰好一模一样 —— 走了 `?: e.categoryKey` 那条兜底路径，
    /// 输出跟正确结果长得完全一样。**这就是「对照组和实验组撞在一起」的活例子。**
    ///
    /// 现在改成在协程里 `.first()` 现查：不依赖「界面有没有恰好订阅过」这个时序，
    /// 想错也错不了。
    private suspend fun refs(): Triple<List<CategoryEntity>, List<TagEntity>, List<LinkEntity>> =
        Triple(repo.observeCategories().first(),
               repo.observeAllTags().first(),
               repo.observeLinks().first())

    /// 要导出的那批记录。⚠️ 私密过滤走 Repository 那一处，这里不重复
    val records: StateFlow<List<ExpenseEntity>> =
        combine(_scope, month, unlocked) { s, m, u -> Triple(s, m, u) }
            .flatMapLatest { (s, m, u) ->
                if (s == ExportScope.ALL) repo.observeAllExpenses(u)
                else m.rangeMillis().let { (from, to) -> repo.observeMonth(from, to, u) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun csvText(): String {
        val (cats, tags, links) = refs()
        return ExpenseCsv.make(records.value, cats, tags, links)
    }

    private fun scopeTitle() =
        if (_scope.value == ExportScope.ALL) "全部记录" else month.value.title()

    /// 文件名。⚠️ 解锁态导出的内容含私密记录，名字里带个标记
    private fun baseName() =
        "记账本-${scopeTitle()}" + if (unlocked.value) "-含私密" else ""

    fun shareCsv(context: Context) = viewModelScope.launch {
        _busy.value = true
        _message.value = try {
            val bytes = ExpenseCsv.BOM + csvText().toByteArray(Charsets.UTF_8)
            val file = writeExportFile(context, "${baseName()}.csv", bytes)
            share(context, file, "text/csv", "导出 CSV")
            ""
        } catch (e: Exception) {
            "导出失败：${e.message ?: e.javaClass.simpleName}"
        }
        _busy.value = false
    }

    fun copyCsv(context: Context) = viewModelScope.launch {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("记账明细", csvText()))
        _message.value = "已复制 ${records.value.size} 笔到剪贴板"
    }

    /// 生成长图 → 存进相册 + 拉起分享。
    /// ⚠️ 渲染放到 Dispatchers.Default：一张 1170×8000 的位图在主线程上画会明显卡一下
    fun makeLongImage(context: Context, alsoSave: Boolean) = viewModelScope.launch {
        _busy.value = true
        _message.value = try {
            val days = LongImage.group(records.value)
            val heightPt = LongImage.measureHeight(days)
            if (LongImage.tooBig(heightPt)) {
                // ⚠️ 明确说出来。iOS 那个老 bug 就是在这儿悄悄失败的
                //（ImageRenderer 返回 nil，界面上「点了什么都没发生」）
                "${records.value.size} 笔画出来有 ${heightPt.toInt()} 点高，超过一张图的最大高度" +
                        "（大约 ${LongImage.approxRowCapacity()} 笔）。改选「本月」，或者用上面的 CSV。"
            } else {
                // 分类现查一次再进渲染 —— 渲染那段是纯计算，不该在里面掺库查询
                val cats = refs().first
                val bmp = withContext(Dispatchers.Default) {
                    LongImage.render(
                        title = scopeTitle(),
                        total = records.value.expenseSum(),
                        income = records.value.incomeSum(),
                        count = records.value.size,
                        days = days,
                        categories = cats,
                        footer = "记账本 · 导出于 " + java.time.LocalDate.now(),
                    )
                }
                val name = "${baseName()}.png"
                val file = withContext(Dispatchers.Default) {
                    writeExportFile(context, name, bmp.toPngBytes())
                }
                var note = ""
                if (alsoSave) {
                    note = if (saveToGallery(context, name, bmp)) "已存进相册。" else "存相册失败。"
                }
                // ⚠️ 尺寸要在 recycle **之前**读出来 —— 回收过的位图上再取宽高是未定义行为
                val dims = "${bmp.width}×${bmp.height}"
                bmp.recycle()
                share(context, file, "image/png", "分享长图")
                note + "长图 $dims 像素已生成。"
            }
        } catch (e: Exception) {
            "生成失败：${e.message ?: e.javaClass.simpleName}"
        }
        _busy.value = false
    }
}

private fun Bitmap.toPngBytes(): ByteArray =
    java.io.ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

/// 写到 cache/export/ 下 —— **只有这个目录在 FileProvider 的白名单里**（见 res/xml/file_paths.xml）
private fun writeExportFile(context: Context, name: String, bytes: ByteArray): File {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    // ⚠️ 文件名里带用户的月份标题（「2026年8月」），本身没有路径分隔符；
    // 但仍然过一道，免得以后有人把备注之类塞进文件名
    val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    return File(dir, safe).apply { writeBytes(bytes) }
}

/// ⚠️ 安卓 7.0 起禁止直接把 file:// 递给别的 app（会抛 FileUriExposedException），
/// 必须走 FileProvider 换成临时授权的 content:// 地址 + FLAG_GRANT_READ_URI_PERMISSION
private fun share(context: Context, file: File, mime: String, title: String) {
    val uri: Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/// 存进系统相册。minSdk 33，所以走 MediaStore、**不需要任何存储权限**
private fun saveToGallery(context: Context, name: String, bmp: Bitmap): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/记账本")
    }
    val uri = context.contentResolver
        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return context.contentResolver.openOutputStream(uri)?.use {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
    } ?: false
}

/// 导出弹层。**照 iOS `ExportSheet` 做**（2026-10-13）：右上「完成」；
/// 「范围」一张卡（本月 | 全部 + 包含几笔多少钱）、「给 AI 分析」一张卡（蓝色图标 + 蓝字的动作行）、「长图」一张卡。
@Composable
fun ExportScreen(onBack: () -> Unit, vm: ExportViewModel = viewModel()) {
    val context = LocalContext.current
    val scope by vm.scope.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val total = remember(records) { records.expenseSum() }
    val heightPt = remember(records) { LongImage.measureHeight(LongImage.group(records)) }
    val tooBig = LongImage.tooBig(heightPt)
    val c = Ios.c

    IosSheetHeader("导出", trailing = { IosPillButton("完成", onBack, prominent = true) })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        IosSection(header = "范围") {
            Box(Modifier.padding(horizontal = Ios.rowInset, vertical = 12.dp)) {
                IosSegmented(ExportScope.entries.map { it.label }, ExportScope.entries.indexOf(scope),
                             { vm.setScope(ExportScope.entries[it]) }, Modifier.fillMaxWidth())
            }
            row(first = false) { IosValueRowContent("包含", "${records.size} 笔 · ${formatYuan(total)}") }
            if (unlocked) {
                // ⚠️ 这条提示必须有：解锁着导出会把私密记录一起带出去，而这一步是**不可撤销**的（文件发出去就收不回来了）
                row(first = false) {
                    Icon(Icons.Filled.LockOpen, null, Modifier.size(14.dp), tint = c.orange)
                    Spacer(Modifier.width(6.dp))
                    Text("私密模式开着，导出的内容包含私密记录，文件名里会带「含私密」", style = Ios.caption, color = c.orange)
                }
            }
        }

        IosSection(
            header = "给 AI 分析",
            footer = "一行一笔，含记账时间、创建时间、收支、金额、分类、备注、标签。直接「复制成文本」粘给 AI 最快；要存档或者用 Excel 打开就导文件。",
        ) {
            ActionRow(true, Icons.Outlined.IosShare, "导出 CSV 文件", !busy && records.isNotEmpty()) { vm.shareCsv(context) }
            ActionRow(false, Icons.Outlined.ContentCopy, "复制成文本", records.isNotEmpty()) { vm.copyCsv(context) }
        }

        IosSection(
            header = "长图",
            footer = "由 app 把整页重新画一张完整长图，样式跟明细页一致。",
        ) {
            ActionRow(true, Icons.Outlined.Image, "生成并存相册", !busy && records.isNotEmpty() && !tooBig) {
                vm.makeLongImage(context, alsoSave = true)
            }
            ActionRow(false, Icons.Outlined.IosShare, "只分享长图", !busy && records.isNotEmpty() && !tooBig) {
                vm.makeLongImage(context, alsoSave = false)
            }
            if (tooBig) {
                row(first = false) {
                    Text("${records.size} 笔画出来有 ${heightPt.toInt()} 点高，超过了一张图能有的最大高度" +
                         "（大约 ${LongImage.approxRowCapacity()} 笔）。改选「本月」，或者用上面的 CSV。",
                         style = Ios.caption, color = c.orange)
                }
            }
            if (busy || message.isNotEmpty()) {
                row(first = false) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.secondaryLabel)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (busy) "正在处理…" else message, style = Ios.footnote, color = c.secondaryLabel)
                }
            }
        }
    }
}

/// 动作行：蓝色图标 + 蓝字（iOS Form 里的 Button / ShareLink 就是这个样子）
@Composable
private fun IosSectionScope.ActionRow(first: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector,
                                      label: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) Ios.c.accent else Ios.c.tertiaryLabel
    row(first = first, onClick = if (enabled) onClick else null, separatorIndent = Ios.rowInset + 40.dp) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = Ios.body, color = color)
    }
}
