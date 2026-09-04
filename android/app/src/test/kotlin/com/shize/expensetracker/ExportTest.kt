package com.shize.expensetracker

import com.shize.expensetracker.data.*
import com.shize.expensetracker.ui.ExpenseCsv
import com.shize.expensetracker.ui.LongImage
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/// 导出的单元测试。
///
/// **为什么这两块值得单独测**：它们是这个 app 里「写错了不报错、只是结果悄悄是错的」
/// 的两个地方 ——
///   · CSV 转义漏了 → 那一行被多切一列，**后面每一列全部错位**（金额挪到分类那列去）。
///     谁都不会报错，AI 读进去照样一本正经地分析，只是结论全是错的。
///   · 长图高度算错 → iOS 那边的老 bug：越界时 `ImageRenderer` 返回 nil、不报错、
///     不崩，界面上就是「点了生成长图，什么都没发生」。
///
/// ⚠️ 跟 MoneyTest 一样的规矩：关键用例要配**控制组** —— 不能只断言「转义之后是对的」，
/// 还得断言「不转义确实会错」，否则哪天有人把转义去掉，测试可能照样通过。
class ExportTest {

    private fun expense(
        id: String, amount: String, key: String, note: String = "",
        day: Int = 1, hour: Int = 12, private: Boolean = false,
    ): ExpenseEntity {
        val t = LocalDate.of(2026, 9, day).atTime(hour, 34, 56)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return ExpenseEntity(
            id = id, amount = BigDecimal(amount), categoryKey = key, note = note,
            date = t, createdAt = t, isPrivate = private, updatedAt = t,
        )
    }

    private val cats = listOf(
        CategoryEntity(id = "餐饮", name = "吃饭", iconName = "fork.knife",
                       createdAt = 0, updatedAt = 0),
        CategoryEntity(id = "cat-uuid-like", name = "数码", iconName = "phone.fill",
                       createdAt = 0, updatedAt = 0),
    )

    // ------------------------------------------------------------ CSV

    @Test
    fun `备注里的逗号和引号必须转义`() {
        // 这条备注同时带逗号和引号 —— iOS 那边的演示数据里也故意留着同一条，
        // 就是为了让这条路径每次都被跑到
        val note = """键帽，"矮轴" 一套"""
        val field = ExpenseCsv.escape(note)

        assertEquals(""""键帽，""矮轴"" 一套"""", field)

        // 控制组：原始串**没有**被引号包起来的话，逗号会把这一列切开。
        // 这条断言证明上面那条不是白测 —— 输入确实是需要转义的
        assertTrue("控制组失效了：这条备注居然不含需要转义的字符，那这个测试没有区分力",
                   note.contains('"'))
        assertNotEquals("转义前后一模一样，说明 escape 压根没干活", note, field)
    }

    @Test
    fun `半角逗号会切列，所以必须包引号；全角逗号不算`() {
        // 真正会把 CSV 切开一列的是**半角**逗号
        assertEquals(""""打车,报销用"""", ExpenseCsv.escape("打车,报销用"))
        // ⚠️ 全角逗号（，）在 CSV 里就是个普通字符，**不许**给它加引号 ——
        // 中文备注里全角逗号极其常见，无脑加引号会让几乎每一行都被包起来
        assertEquals("打车，报销用", ExpenseCsv.escape("打车，报销用"))
        // 控制组：没有特殊字符时也不许画蛇添足（加了引号等于备注里真多了两个引号）
        assertEquals("打车报销用", ExpenseCsv.escape("打车报销用"))
    }

    @Test
    fun `换行也要转义`() {
        assertEquals("\"上\n下\"", ExpenseCsv.escape("上\n下"))
    }

    @Test
    fun `分类那一列写的是显示名，不是代号`() {
        // ⚠️ 这条曾经**假通过**过：按协议「分类的 id 就是代号，代号取自建它时的名字」，
        // 测试数据里 id 和 name 一样时，就算代码走了 `?: categoryKey` 那条兜底路径，
        // 输出也跟正确结果长得一模一样 —— 对照组和实验组撞在一起了。
        // 所以这里**故意用一个 id 和 name 不一样的分类**（代号 cat-uuid-like / 显示名「数码」），
        // 只有真的去查了目录才可能输出「数码」。
        val csv = ExpenseCsv.make(
            listOf(expense("e1", "12.00", "cat-uuid-like")),
            cats, emptyList(), emptyList(),
        )
        val line = csv.lines()[1]
        assertTrue("分类列没有翻成显示名，写的还是代号：$line", line.contains("数码"))
        assertFalse("分类列漏出了代号：$line", line.contains("cat-uuid-like"))
    }

    @Test
    fun `分类改过名的话导出里要是新名字`() {
        // 库里代号还是「餐饮」，但显示名已经被改成「吃饭」
        val csv = ExpenseCsv.make(
            listOf(expense("e1", "12.00", "餐饮")), cats, emptyList(), emptyList())
        assertTrue(csv.lines()[1].contains("吃饭"))
    }

    @Test
    fun `查不到分类时原样写代号，不能写空`() {
        val csv = ExpenseCsv.make(
            listOf(expense("e1", "12.00", "早就删了的分类")), cats, emptyList(), emptyList())
        // 代号本身就是这个分类当初的名字，认得出，比留空强
        assertTrue(csv.lines()[1].contains("早就删了的分类"))
    }

    @Test
    fun `一笔多个标签用竖线隔开，已删的标签不出现`() {
        val tags = listOf(
            TagEntity(id = "t1", name = "报销", createdAt = 0, updatedAt = 0),
            TagEntity(id = "t2", name = "出差", createdAt = 0, updatedAt = 0),
        )
        val links = listOf(
            LinkEntity("e1:t1", "e1", "t1", updatedAt = 0),
            LinkEntity("e1:t2", "e1", "t2", updatedAt = 0),
            // 指向一个不在 tags 里的标签（已删）—— 不能让它变成一个空字段跑进去
            LinkEntity("e1:gone", "e1", "gone", updatedAt = 0),
        )
        val csv = ExpenseCsv.make(listOf(expense("e1", "12.00", "餐饮")), cats, tags, links)
        val cols = csv.lines()[1].split(",")
        assertEquals("报销|出差", cols.last())
    }

    @Test
    fun `金额写成十进制字符串，不能出现科学计数法`() {
        val e = expense("e1", "1E+3", "餐饮")
        val csv = ExpenseCsv.make(listOf(e), cats, emptyList(), emptyList())
        assertTrue("金额列出现了科学计数法：${csv.lines()[1]}", csv.lines()[1].contains("1000"))
        // 控制组：BigDecimal 的 toString 确实会给出科学计数法 —— 证明 toPlainString 不是白加的
        assertEquals("1E+3", BigDecimal("1E+3").toString())
    }

    @Test
    fun `表头和列数对得上`() {
        val csv = ExpenseCsv.make(
            listOf(expense("e1", "12.00", "餐饮", note = "无特殊字符")),
            cats, emptyList(), emptyList())
        assertEquals(ExpenseCsv.HEADER, csv.lines()[0])
        assertEquals(6, csv.lines()[0].split(",").size)
        assertEquals(6, csv.lines()[1].split(",").size)
    }

    // ------------------------------------------------------------ 长图

    @Test
    fun `长图高度是按内容算出来的，不是按笔数猜的`() {
        // iOS 那个 bug 的根子：原实现按**笔数**挑清晰度，而验收时库里只有 14 笔，
        // 3 倍图才 4897px、一切正常；等账目长到 99 笔，同样的 3 倍图变成 22464px，
        // 直接越过位图上限、静默失效。
        // 所以这里测的是「高度随内容线性长」这个性质本身。
        fun heightOf(n: Int): Float {
            val items = (1..n).map { expense("e$it", "1.00", "餐饮", day = 1) }
            return LongImage.measureHeight(LongImage.group(items))
        }
        val h1 = heightOf(1)
        val h2 = heightOf(2)
        val h10 = heightOf(10)
        // 每多一笔就固定多一行的高度
        assertEquals(h2 - h1, (h10 - h1) / 9f, 0.01f)
        assertTrue(h10 > h1)
    }

    @Test
    fun `分了几天就多算几个天标题和天间距`() {
        val sameDay = listOf(expense("a", "1.00", "餐饮", day = 1),
                             expense("b", "1.00", "餐饮", day = 1))
        val twoDays = listOf(expense("a", "1.00", "餐饮", day = 1),
                             expense("b", "1.00", "餐饮", day = 2))
        assertTrue("跨两天应该比同一天高（多一个天标题 + 一个天间距）",
                   LongImage.measureHeight(LongImage.group(twoDays)) >
                           LongImage.measureHeight(LongImage.group(sameDay)))
    }

    @Test
    fun `清晰度按真实高度反推，且永不越过位图上限`() {
        // 内容很短 → 给到上限 3 倍
        assertEquals(3f, LongImage.scaleFor(100f), 0.001f)
        // 内容长到 4000 点 → 只能 2 倍（4000 × 2 = 8000，正好贴着）
        assertEquals(2f, LongImage.scaleFor(4000f), 0.001f)
        // 无论多长，算出来的像素高度都不许超过上限 —— 这是这个函数存在的全部理由
        for (pt in listOf(1f, 500f, 2667f, 4000f, 7999f, 8000f)) {
            val px = pt * LongImage.scaleFor(pt)
            assertTrue("内容 $pt 点算出 $px 像素，越过了 ${LongImage.MAX_PIXEL_HEIGHT}",
                       px <= LongImage.MAX_PIXEL_HEIGHT)
        }
        // 但也不许低于 1 倍（再长也得给张图，实在装不下由 tooBig 明确报出来）
        assertEquals(1f, LongImage.scaleFor(999999f), 0.001f)
    }

    @Test
    fun `连一倍都装不下时要能被识别出来，而不是悄悄失败`() {
        assertFalse(LongImage.tooBig(LongImage.MAX_PIXEL_HEIGHT - 1f))
        assertTrue(LongImage.tooBig(LongImage.MAX_PIXEL_HEIGHT + 1f))
        // 提示文案里那个「大约多少笔」要是个说得出口的数
        assertTrue(LongImage.approxRowCapacity() > 100)
    }

    @Test
    fun `一天里的账按时间倒序，天也倒序`() {
        val items = listOf(
            expense("早", "1.00", "餐饮", day = 2, hour = 8),
            expense("晚", "1.00", "餐饮", day = 2, hour = 20),
            expense("昨天", "1.00", "餐饮", day = 1, hour = 12),
        )
        val days = LongImage.group(items)
        assertEquals(listOf(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 1)),
                     days.map { it.date })
        assertEquals(listOf("晚", "早"), days[0].items.map { it.id })
    }

    @Test
    fun `每天的小计是那天所有账的和`() {
        val days = LongImage.group(listOf(
            expense("a", "128.00", "餐饮", day = 4),
            expense("b", "42.50", "餐饮", day = 4),
            expense("c", "3.00", "交通", day = 4),
        ))
        assertEquals(BigDecimal("173.50"), days[0].total)
    }
}
