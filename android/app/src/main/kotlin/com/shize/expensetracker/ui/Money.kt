package com.shize.expensetracker.ui

import java.math.BigDecimal
import java.text.DecimalFormat

// 金额的输入净化和显示格式化。
//
// ⚠️⚠️ **这个 app 最严重的历史 bug 就在金额输入上**（iOS 那边踩的）：
// 中文输入法会把小数点打成**全角句号**「。」，而 `Decimal(string: "12。75")`
// **静默截断成 12、不报错**，保存按钮照样可用 —— 于是一笔 12.75 被记成 12 块，
// 而且当场完全看不出来。安卓这边同一个坑（`BigDecimal("12。75")` 会抛异常，
// 抛异常反而比静默截断好，但如果哪天用 `toDoubleOrNull()` 之类就又变成静默的了）。
//
// 所以规矩是：**输入框里就把它净化掉**，永远不给下游一个可能解析歪的字符串。
// 改任何金额相关代码都要回归这一条。

/// 净化金额输入。跟 iOS 那份 `ExpenseFormView.sanitizeAmount` **必须行为一致**：
/// 全角句号转成小数点、去掉数字以外的字符、最多一个小数点、最多 9 位整数 + 2 位小数。
fun sanitizeAmount(raw: String): String {
    var t = raw
        .replace('。', '.')   // 中文输入法的句号
        .replace('．', '.')   // 全角句点
        .filter { it.code < 128 && (it.isDigit() || it == '.') }

    // 只保留第一个小数点
    val dot = t.indexOf('.')
    if (dot >= 0) {
        t = t.substring(0, dot) + "." + t.substring(dot + 1).filter { it != '.' }
    }

    var intPart = t
    var decPart: String? = null
    val d = t.indexOf('.')
    if (d >= 0) {
        intPart = t.substring(0, d)
        decPart = t.substring(d + 1).take(2)
    }
    // 直接从小数点开始输入时补个 0（".5" → "0.5"）
    if (intPart.isEmpty() && decPart != null) intPart = "0"
    intPart = intPart.take(9)
    return if (decPart != null) "$intPart.$decPart" else intPart
}

/// 净化后的字符串 → BigDecimal。解析不出来返回 null，**绝不返回 0**
/// —— 静默把一笔账变成 0 元比"保存失败"恶劣得多。
fun parseAmount(sanitized: String): BigDecimal? {
    if (sanitized.isEmpty() || sanitized == "." ) return null
    return try {
        val v = BigDecimal(sanitized)
        if (v.signum() <= 0) null else v      // 0 元和负数都不是有效的一笔支出
    } catch (_: NumberFormatException) {
        null
    }
}

private val yuanFull = DecimalFormat("¥#,##0.00")
private val yuanRound = DecimalFormat("¥#,##0")

/// 显示用。⚠️ 用 BigDecimal 的重载，不要先 toDouble —— 那一步就把精度丢了
fun formatYuan(v: BigDecimal, roundWhenLarge: Boolean = false): String =
    if (roundWhenLarge && v >= BigDecimal(1000)) yuanRound.format(v) else yuanFull.format(v)

fun List<BigDecimal>.sum(): BigDecimal = fold(BigDecimal.ZERO) { a, b -> a + b }
