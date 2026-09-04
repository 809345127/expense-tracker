package com.shize.expensetracker

import com.shize.expensetracker.ui.parseAmount
import com.shize.expensetracker.ui.sanitizeAmount
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/// 金额输入净化的单元测试。
///
/// **为什么这个函数值得单独写测试**：它是这个 app 唯一一处「写错了会静默改掉用户数据」
/// 的地方。iOS 那边的历史 bug 就在这：中文输入法把小数点打成全角句号「。」时，
/// `Decimal(string: "12。75")` **静默截断成 12、不报错**，保存按钮照样可用
/// —— 一笔 12.75 被记成 12 块，当场完全看不出来。
///
/// ⚠️ 这里不能只测「净化之后是对的」，还必须测「**不净化就是错的**」——
/// 否则万一哪天有人把净化去掉，测试可能照样通过（比如底层库变得能认全角句号了）。
/// 所以下面每个关键用例都配了一条「原始输入确实有问题」的断言当控制组。
class MoneyTest {

    @Test
    fun `全角句号必须被转成小数点`() {
        // 控制组：**不净化**的话，这个串是解析不出正确金额的
        val raw = "12。75"
        assertNull("控制组失效了：原始串居然直接解析成功，那这个测试就没有区分力了",
                   parseAmount(raw))

        // 净化之后才对
        assertEquals("12.75", sanitizeAmount(raw))
        assertEquals(BigDecimal("12.75"), parseAmount(sanitizeAmount(raw)))
    }

    @Test
    fun `全角句点也要认`() {
        assertEquals("8.50", sanitizeAmount("8．50"))
    }

    @Test
    fun `只保留第一个小数点`() {
        assertEquals("1.23", sanitizeAmount("1..2.345"))
        assertEquals("0.12", sanitizeAmount("..12"))
    }

    @Test
    fun `小数最多两位`() {
        assertEquals("9.99", sanitizeAmount("9.999999"))
    }

    @Test
    fun `整数最多九位`() {
        assertEquals("123456789", sanitizeAmount("1234567890123"))
    }

    @Test
    fun `直接从小数点开始输入时补零`() {
        assertEquals("0.5", sanitizeAmount(".5"))
    }

    @Test
    fun `数字以外的字符一律去掉`() {
        assertEquals("123", sanitizeAmount("1a2b3c"))
        assertEquals("50", sanitizeAmount("￥50"))
        assertEquals("", sanitizeAmount("abc"))
    }

    @Test
    fun `解析不出来要返回 null 而不是 0`() {
        // ⚠️ 静默把一笔账变成 0 元比"保存失败"恶劣得多
        assertNull(parseAmount(""))
        assertNull(parseAmount("."))
        assertNull(parseAmount("0"))      // 0 元不是一笔有效支出
        assertNull(parseAmount("0.00"))
    }

    @Test
    fun `金额全程不碰浮点数`() {
        // 0.1 + 0.2 用 double 算出来是 0.30000000000000004
        val a = parseAmount(sanitizeAmount("0.1"))!!
        val b = parseAmount(sanitizeAmount("0.2"))!!
        assertEquals(BigDecimal("0.3"), a + b)
        // 控制组：double 算出来确实是错的 —— 证明上面那条断言不是白测
        assertNotEquals("0.3", (0.1 + 0.2).toString())
    }
}
