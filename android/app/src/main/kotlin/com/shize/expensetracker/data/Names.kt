package com.shize.expensetracker.data

import java.text.Normalizer
import java.util.Locale

// 分类 / 标签名字的清理与查重。
//
// ⚠️⚠️ **这两个函数的行为必须跟 iOS 那边 `CategoryDef.cleanedName` /
// `CategoryDef.comparisonKey` 一模一样**，因为新分类的**代号（id）就是用
// `cleanedName` 算出来的（见 Repository.addCategory）。两端算得不一样的话，
// 同一个名字在两台设备上会得到两个不同的代号 → 同步之后变成两条一模一样的分类，
// 而这个错误**不可逆**（历史账目已经存了各自那个代号）。
//
// 为什么要清理：这个 app 被中文输入法坑过一次（全角句号让 12。75 静默变成 12）。
// 肉眼看不出的空格和全角字符，会让「理发」和「理发 」变成两个分类。

/// 入库用的显示名：去首尾空白、中间连续空白压成一个空格。
/// 对位 iOS：`raw.components(separatedBy: .whitespacesAndNewlines).filter{!$0.isEmpty}.joined(separator:" ")`
fun cleanedName(raw: String): String =
    raw.split(Regex("[\\s\\u00A0\\u3000]+")).filter { it.isNotEmpty() }.joinToString(" ")

/// 查重用的比较形式：忽略大小写、全角半角、变音符号。
///
/// 对位 iOS 的 `.folding(options: [.caseInsensitive, .widthInsensitive, .diacriticInsensitive])`：
///   - 全角半角 → `Normalizer.Form.NFKC`（把「Ａ」折成「A」、「１」折成「1」）
///   - 大小写   → `lowercase`
///   - 变音符号 → 拆成 NFD 之后把组合记号（Unicode 类别 Mn）去掉，「é」→「e」
fun comparisonKey(raw: String): String {
    val cleaned = cleanedName(raw)
    if (cleaned.isEmpty()) return ""
    val folded = Normalizer.normalize(cleaned, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return Normalizer.normalize(folded, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
