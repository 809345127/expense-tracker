package com.shize.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.ExpenseFilter
import com.shize.expensetracker.data.TagEntity
import com.shize.expensetracker.data.matching

/// 筛选面板：**分类和标签在同一个面板里选。**
///
/// 这是刻意的（跟 iOS `FilterSheet.swift` 同一个决定）：筛选只有一套 `ExpenseFilter`，
/// 分类和标签是它的两个维度。分开放两个地方的话，用户得记「哪个维度在哪儿点」，
/// 而且迟早出现两种筛选态互相打架。统计页点某一行只是**往同一套条件里填值**，不是第二套机制。
///
/// ⚠️ 跟 iOS 那版有一处**故意不同**：iOS 是「改草稿 + 点完成才生效」，这里是**改一下立刻生效**。
/// 原因是载体不同 —— 安卓这边是底部弹层，后面的列表看得见，改一下就能看到结果；
/// 而底部弹层上放「取消 / 完成」两颗按钮不是安卓的习惯（弹层本来就是下滑即关）。
/// 代价是没有「反悔」，所以给了一颗很显眼的「清除全部条件」。
///
/// 口径（分类内并集 / 标签内并集 / 两者之间交集）在 `data/Filter.kt` 里写着。
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filter: ExpenseFilter,
    onFilterChange: (ExpenseFilter) -> Unit,
    categories: List<CategoryEntity>,
    tags: List<TagEntity>,
    /// 当月**已经过完私密门**的账目。⚠️ 见下面 counts 的注释
    monthExpenses: List<ExpenseEntity>,
    tagsByExpense: Map<String, Set<String>>,
    onDismiss: () -> Unit,
) {
    // ⚠️⚠️ 这些「几笔」必须从**已经过完私密门**的那份账目算。
    // 不过的话，锁着的时候面板上的笔数会把私密记录算进去、跟列表对不上 ——
    // 那个对不上的数就是最容易露馅的地方（见 PrivacyGate 的注释）。
    // 调用方（明细页）传进来的就是过完的那份，这里不重复过、也不去查库。
    //
    // 一次遍历同时算出「每个分类几笔」和「每个标签几笔」，别分开各扫一遍。
    val counts = remember(monthExpenses, tagsByExpense) {
        val byCat = HashMap<String, Int>()
        val byTag = HashMap<String, Int>()
        for (e in monthExpenses) {
            byCat[e.categoryKey] = (byCat[e.categoryKey] ?: 0) + 1
            for (t in tagsByExpense[e.id] ?: emptySet()) byTag[t] = (byTag[t] ?: 0) + 1
        }
        byCat to byTag
    }
    val (catCount, tagCount) = counts

    // 当前条件能筛出几笔。⚠️ 用的是跟列表页**完全同一个** matching()，
    // 所以面板上预告的数字跟关掉弹层看到的一定一致 —— 不会预告 5 笔、进去只有 3 笔
    val matched = remember(monthExpenses, filter, tagsByExpense) {
        monthExpenses.matching(filter, tagsByExpense).size
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("筛选", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "共 $matched 笔",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("分类", "选多个 = 这几类都要看")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                categories.forEach { c ->
                    val n = catCount[c.id] ?: 0
                    val on = c.id in filter.categoryKeys
                    FilterChip(
                        selected = on,
                        onClick = { onFilterChange(filter.toggleCategory(c.id)) },
                        label = { ChipLabel(c.name, n) },
                        leadingIcon = {
                            if (on) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                            else Icon(categoryIcon(c.iconName), null, Modifier.size(18.dp),
                                      tint = categoryColor(c.colorIndex))
                        },
                        // 这个月一笔都没有的分类照样列出来（位置稳定、好找），但淡一点
                        // 表示「点了也是空的」。iOS 那边同一个做法
                        modifier = Modifier.alpha(if (n == 0 && !on) 0.45f else 1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(
                "标签",
                if (tags.isEmpty()) "还没有标签。记一笔的时候可以现建。"
                else "选多个 = 命中其中任意一个就算；一笔被多个标签同时命中只算一次钱",
            )
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { t ->
                        val n = tagCount[t.id] ?: 0
                        val on = t.id in filter.tagIds
                        FilterChip(
                            selected = on,
                            onClick = { onFilterChange(filter.toggleTag(t.id)) },
                            label = { ChipLabel(t.name, n) },
                            leadingIcon = {
                                if (on) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                                else Box(
                                    Modifier.size(10.dp).clip(CircleShape)
                                        .background(tagColor(t.colorIndex))
                                )
                            },
                            modifier = Modifier.alpha(if (n == 0 && !on) 0.45f else 1f),
                        )
                    }
                }
            }

            if (!filter.isEmpty) {
                Spacer(Modifier.height(12.dp))
                // 分类和标签同时选上时，这句必须有 —— 不写的话「餐饮 + 咖啡」
                // 很容易被读成「餐饮的 或者 带咖啡的」
                if (filter.categoryKeys.isNotEmpty() && filter.tagIds.isNotEmpty()) {
                    Text(
                        "分类和标签同时选 = 两个都要满足。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = { onFilterChange(ExpenseFilter.none) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清除全部条件") }
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, hint: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ChipLabel(name: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name)
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            // ⚠️ 别用更淡的：这个项目量过 —— 三级灰对白底只有 1.84:1，小字下限 4.5:1。
            // 层级靠字号拉开，不靠涂淡
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
