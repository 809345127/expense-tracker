package com.shize.expensetracker.ui

import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.LinkEntity
import com.shize.expensetracker.data.TagEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 导出成 CSV（主要用途是丢给 AI 分析）。
// **列和格式跟 iOS 那份 `ExpenseCSV` 一字对齐** —— 两台设备导出来的表能直接拼在一起。

object ExpenseCsv {
    const val HEADER = "记账时间,创建时间,金额,分类,备注,标签"

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /// 一行一笔。时间用 `2026-08-19 12:18:32`（AI 不用猜格式）；标签多个用 `|` 隔开。
    ///
    /// `categories` 用来把分类**代号**翻成显示名（分类改过名的话，导出里也要是新名字）。
    /// 查不到就原样写代号 —— 代号本身就是这个分类当初的名字，认得出。
    fun make(
        expenses: List<ExpenseEntity>,
        categories: List<CategoryEntity>,
        tags: List<TagEntity>,
        links: List<LinkEntity>,
    ): String {
        val zone = ZoneId.systemDefault()
        val catName = categories.associate { it.id to it.name }
        val tagName = tags.associate { it.id to it.name }
        val tagsOf = links.groupBy({ it.expenseId }, { it.tagId })

        fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(stamp)

        val rows = expenses.map { e ->
            listOf(
                time(e.date),
                time(e.createdAt),
                // ⚠️ toPlainString 而不是 toString：BigDecimal 在某些标度下 toString
                // 会给出科学计数法（1E+2），那样导出来的表里金额是一串鬼画符
                e.amount.toPlainString(),
                catName[e.categoryKey] ?: e.categoryKey,
                e.note,
                (tagsOf[e.id] ?: emptyList()).mapNotNull { tagName[it] }.joinToString("|"),
            ).joinToString(",") { escape(it) }
        }
        return (listOf(HEADER) + rows).joinToString("\n")
    }

    /// CSV 转义：字段里出现逗号、引号或换行时，整个字段用双引号包起来，里面的引号写两遍。
    ///
    /// ⚠️⚠️ **别省这一步，而且错了不报错。** 备注是自由文本，逗号非常常见
    /// （「打车，报销用」）。不转义的话那一行会被多切出一列，**后面每一列全部错位**
    /// —— 金额挪到分类那一列去。最坑的是这种错谁都不会报错，AI 读进去照样
    /// 一本正经地分析，只是结论全是错的。
    /// （单元测试里专门有一条备注同时带逗号和引号，别把它改成普通文本。）
    fun escape(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /// UTF-8 BOM。
    /// ⚠️ 不补的话 Excel 会拿系统本地编码去解这份 UTF-8，中文全是乱码。
    /// 给 AI 吃无所谓，但哪天想用 Excel 打开就有所谓了 —— 多这 3 个字节没有任何副作用。
    val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
