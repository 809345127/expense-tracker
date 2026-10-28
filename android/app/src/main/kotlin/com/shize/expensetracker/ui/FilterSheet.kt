package com.shize.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseFilter
import com.shize.expensetracker.data.matching

/// 筛选弹层：**分类和标签在同一个面板里选**（跟 iOS `FilterSheet.swift` 同一个决定）。
///
/// 2026-10-13 起照 iOS 改成「改草稿 + 点完成才生效」：左上「取消」右上「完成」，
/// 分类是一格一格的胶囊（三列）、标签是一行一个带勾的列表。
/// 原来安卓是「改一下立刻生效」—— 那是因为当时载体是底部半屏弹层、后面的列表看得见；
/// 现在跟 iOS 一样是整页弹层，看不见后面的列表，所以要一个「完成」，底下也一直写着「共 N 笔」。
///
/// 口径（分类内并集 / 标签内并集 / 两者之间交集）在 `data/Filter.kt` 里写着。
@Composable
fun FilterSheet(onDismiss: () -> Unit, vm: ExpenseListViewModel = viewModel()) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val tags by vm.activeTags.collectAsStateWithLifecycle()
    // ⚠️ 用的是**过完私密门、还没过筛选**的那份 —— 面板上的「几笔」要按
    // 「这个月一共有什么」来算，不能按「筛完还剩什么」算（那样数字会自己吃掉自己）
    val monthExpenses by vm.monthExpenses.collectAsStateWithLifecycle()
    val tagsByExpense by vm.tagsByExpense.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf(filter) }

    // ⚠️⚠️ 这些「几笔」必须从**已经过完私密门**的那份账目算。不过的话，锁着的时候面板上的笔数
    // 会把私密记录算进去、跟列表对不上 —— 那个对不上的数就是最容易露馅的地方（见 PrivacyGate 的注释）。
    // 一次遍历同时算出「每个分类几笔」和「每个标签几笔」，别分开各扫一遍。
    val (catCount, tagCount) = remember(monthExpenses, tagsByExpense) {
        val byCat = HashMap<String, Int>()
        val byTag = HashMap<String, Int>()
        for (e in monthExpenses) {
            byCat[e.categoryKey] = (byCat[e.categoryKey] ?: 0) + 1
            for (t in tagsByExpense[e.id] ?: emptySet()) byTag[t] = (byTag[t] ?: 0) + 1
        }
        byCat to byTag
    }
    // 当前草稿能筛出几笔。⚠️ 用的是跟列表页**完全同一个** matching()，
    // 所以这里预告的数字跟点完成之后看到的一定一致
    val matched = remember(monthExpenses, draft, tagsByExpense) { monthExpenses.matching(draft, tagsByExpense).size }

    val c = Ios.c
    IosSheetHeader(
        "筛选",
        leading = { IosPillButton("取消", onDismiss) },
        trailing = { IosPillButton("完成", { vm.setFilter(draft); onDismiss() }, prominent = true) },
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CategoryGrid("分类", "选多个 = 这几类都要看", categories.filter { !it.isIncome }, catCount, draft) {
            draft = draft.toggleCategory(it)
        }
        // 收入分类单独一组；一个都没有就不出现
        val incomeCats = categories.filter { it.isIncome }
        if (incomeCats.isNotEmpty()) {
            CategoryGrid("收入分类", null, incomeCats, catCount, draft) { draft = draft.toggleCategory(it) }
        }

        IosSection(header = "标签", footer = "选多个 = 命中其中任意一个就算；一笔被多个标签同时命中只算一次钱") {
            if (tags.isEmpty()) {
                row(first = true) {
                    Text("还没有标签。记一笔的时候可以现建。", style = Ios.footnote, color = c.secondaryLabel)
                }
            }
            tags.forEachIndexed { i, t ->
                row(first = i == 0, onClick = { draft = draft.toggleTag(t.id) }, separatorIndent = Ios.rowInset + 20.dp) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(tagColor(t.colorIndex)))
                    Spacer(Modifier.width(10.dp))
                    Text(t.name, style = Ios.body, color = c.label)
                    Spacer(Modifier.width(6.dp))
                    Text("${tagCount[t.id] ?: 0}", style = Ios.caption2, color = c.tertiaryLabel)
                    Spacer(Modifier.weight(1f))
                    if (t.id in draft.tagIds) {
                        Icon(Icons.Filled.Check, "已选", tint = c.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        if (!draft.isEmpty) {
            // 分类和标签同时选上时这句必须有 —— 不写的话「餐饮 + 咖啡」很容易被读成「餐饮的 或者 带咖啡的」
            IosSection(footer = if (draft.categoryKeys.isEmpty() || draft.tagIds.isEmpty()) "共 $matched 笔"
                                else "分类和标签同时选 = 两个都要满足 · 共 $matched 笔") {
                row(first = true, onClick = { draft = ExpenseFilter.none }) {
                    Text("清除全部条件", style = Ios.body, color = c.red)
                }
            }
        }
    }
}

/// 一组分类胶囊（三列网格）。对位 iOS `categorySection` + `CategoryChip`：
/// 没选中是浅灰底 + 分类色图标，选中是整颗涂成分类色、字变白。
@Composable
private fun CategoryGrid(
    title: String,
    footer: String?,
    cats: List<CategoryEntity>,
    catCount: Map<String, Int>,
    draft: ExpenseFilter,
    onToggle: (String) -> Unit,
) {
    IosSection(header = title, footer = footer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cats.chunked(3).forEach { rowCats ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowCats.forEach { cat ->
                        val n = catCount[cat.id] ?: 0
                        CategoryChip(cat, n, cat.id in draft.categoryKeys, Modifier.weight(1f)) { onToggle(cat.id) }
                    }
                    repeat(3 - rowCats.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(cat: CategoryEntity, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Ios.c
    val color = categoryColor(cat.colorIndex)
    Row(
        modifier
            // 一笔都没有的分类照样列出来（位置稳定、好找），但淡一点表示点了是空的
            .alpha(if (count == 0 && !selected) 0.45f else 1f)
            .clip(CircleShape)
            .background(if (selected) color else c.fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(categoryIcon(cat.iconName), null, tint = if (selected) Color.White else color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(cat.name, style = Ios.subheadline, color = if (selected) Color.White else c.label,
             maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
        Spacer(Modifier.width(6.dp))
        // ⚠️ 用 secondary 不用 tertiary：三级灰对白底只有 1.84:1，小字可读下限是 4.5:1
        Text("$count", style = Ios.caption2, color = if (selected) Color.White.copy(0.8f) else c.secondaryLabel)
    }
}
