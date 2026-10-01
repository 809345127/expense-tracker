package com.shize.expensetracker

import com.shize.expensetracker.data.CategoryKind
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.ui.AmountExpression
import com.shize.expensetracker.ui.AmountKey
import com.shize.expensetracker.ui.AmountOp
import com.shize.expensetracker.ui.expenseSum
import com.shize.expensetracker.ui.incomeSum
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/// 自定义键盘的算式规则。⚠️ 跟 iOS `AmountExpression` 是同一套，这里的每条期望值两端都得成立
class AmountExpressionTest {

    /// 把「50-12.5」这种串按成一串按键（- * / 换成键盘上的运算符）
    private fun type(keys: String): AmountExpression = keys.fold(AmountExpression()) { e, c ->
        e.press(when (c) {
            in '0'..'9' -> AmountKey.Digit(c - '0')
            '.' -> AmountKey.Dot
            '+' -> AmountKey.Op(AmountOp.Plus)
            '-' -> AmountKey.Op(AmountOp.Minus)
            '*' -> AmountKey.Op(AmountOp.Times)
            '/' -> AmountKey.Op(AmountOp.Divide)
            '<' -> AmountKey.Backspace
            else -> error("没有这个键：$c")
        })
    }

    @Test fun `先乘除后加减`() {
        assertEquals(BigDecimal("25"), type("50-12.5*2").value)
        // 控制组：从左往右硬算会得到 75 —— 证明上面那个 25 不是碰巧
        assertNotEquals(BigDecimal("75"), type("50-12.5*2").value)
    }

    @Test fun `结果四舍五入到分`() {
        assertEquals(BigDecimal("3.33"), type("10/3").value)
        assertEquals(BigDecimal("10"), type("10/3*3").value) // 中间多留几位，不会算成 9.99
    }

    @Test fun `除以零、结果不是正数，都算不出`() {
        assertNull(type("5/0").value)
        assertNull(type("5-5").value)
        assertNull(type("5-8").value)   // 负数也不行：收入靠切换，不靠算出负数
        assertNull(type("").value)
    }

    @Test fun `末尾挂着的运算符和小数点忽略`() {
        assertEquals(BigDecimal("50"), type("50+").value)
        assertEquals(BigDecimal("12"), type("12.").value)
    }

    @Test fun `运算符不能开头、连按换成后一个`() {
        assertEquals("", type("+").text)
        assertEquals("5×", type("5+*").text)
        assertEquals("12+", type("12.+").text)  // 先去掉悬空的小数点
    }

    @Test fun `每段最多 9 位整数 2 位小数，前导 0 被替换`() {
        assertEquals("123456789", type("1234567890").text)
        assertEquals("1.23", type("1.234").text)
        assertEquals("7", type("07").text)
        assertEquals("0.5", type(".5").text)
        assertEquals("1.5+0.25", type("1.5+.25").text)
    }

    @Test fun `编辑旧账从绝对值开始，不带科学计数法`() {
        assertEquals("100", AmountExpression.of(BigDecimal("1E+2")).text)
        assertEquals("28.5", AmountExpression.of(BigDecimal("28.50")).text)
    }

    // ------------------------------------------------------------ 收入

    private fun e(amount: String, key: String = "餐饮") = ExpenseEntity(
        id = amount, amount = BigDecimal(amount), categoryKey = key, date = 0, createdAt = 0, updatedAt = 0)

    @Test fun `支出合计不被收入抵掉`() {
        val list = listOf(e("24.50"), e("4"), e("-8500", "收入:工资"))
        assertEquals(BigDecimal("28.50"), list.expenseSum())
        assertEquals(BigDecimal("8500"), list.incomeSum())
        // 控制组：直接把 amount 加起来就是错的那个数
        assertEquals(BigDecimal("-8471.50"), list.fold(BigDecimal.ZERO) { a, x -> a + x.amount })
    }

    @Test fun `收入分类靠代号前缀认`() {
        assertTrue(CategoryKind.isIncome("收入:工资"))
        assertFalse(CategoryKind.isIncome("餐饮"))
        // 全角冒号不算（支出分类起名「收入：xx」时代号里换成的就是全角）
        assertFalse(CategoryKind.isIncome("收入：奖金"))
    }
}
