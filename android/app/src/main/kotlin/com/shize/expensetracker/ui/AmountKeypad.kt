package com.shize.expensetracker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode

// 记账专用数字键盘（2026-10-01 加）。
//
// 用户原话：「输入的时候只能通过我自带的输入法，希望能用你们自定义的输入法。
// 如果能带一点加减乘除功能，会比较好一点。」
//
// 两层拆开写：
//   · `AmountExpression` —— 纯逻辑：按键怎么改算式、算式怎么算出结果。不碰界面，有单元测试。
//   · `AmountKeypad`     —— 只管画键盘、把按键转给上面那层。
//
// ⚠️ 跟 iOS 那边（`AmountKeypad.swift`）是同一套规则，改一边要同步改另一边：
//    键位、每段数字的位数上限、先乘除后加减、结果保留两位、≤ 0 不给存。
//
// 顺带解决了一个老坑：原来用系统输入法时，中文输入法会把小数点打成全角句号「。」
// （见 Money.kt 顶部那段历史 bug）。自己的键盘上只有半角的「.」，这条路从源头上堵死了。

sealed interface AmountKey {
    data class Digit(val d: Int) : AmountKey
    data object Dot : AmountKey
    data class Op(val op: AmountOp) : AmountKey
    data object Backspace : AmountKey
}

enum class AmountOp(val symbol: Char, val label: String) {
    Plus('+', "加"), Minus('−', "减"), Times('×', "乘"), Divide('÷', "除");

    companion object {
        fun of(c: Char) = entries.firstOrNull { it.symbol == c }
    }
}

/// 输入中的算式，形如 `50−12.5×2`。不可变：每按一下得到一个新的。
///
/// ⚠️ **里面只有正数**。收入不是靠输一个负号表示的，是靠表单顶上的「支出 / 收入」切换
/// —— 键盘上的「−」只用来做减法。
data class AmountExpression(val text: String = "") {

    val isEmpty: Boolean get() = text.isEmpty()

    /// 算式里有没有运算符（有的话界面才多显示一行「= 结果」）
    val hasOperator: Boolean get() = text.any { AmountOp.of(it) != null }

    /// 最后一段正在输的数字（运算符后面那截）
    private val lastNumber: String
        get() {
            val i = text.indexOfLast { AmountOp.of(it) != null }
            return if (i >= 0) text.substring(i + 1) else text
        }

    fun press(key: AmountKey): AmountExpression = when (key) {
        is AmountKey.Digit -> {
            val n = lastNumber
            val dot = n.indexOf('.')
            when {
                // 小数位满了就不收
                dot >= 0 -> if (n.length - dot - 1 < MAX_FRAC) copy(text = text + key.d) else this
                // 整数部分是单独一个 0 时，再按数字是替换它，免得出现 007
                n == "0" -> copy(text = text.dropLast(1) + key.d)
                n.length >= MAX_INT -> this
                else -> copy(text = text + key.d)
            }
        }
        AmountKey.Dot -> {
            val n = lastNumber
            when {
                '.' in n -> this
                // 直接从小数点开始按时补个 0
                n.isEmpty() -> copy(text = "${text}0.")
                else -> copy(text = "$text.")
            }
        }
        is AmountKey.Op -> {
            if (text.isEmpty()) this // 开头不能是运算符（这里没有负数）
            else {
                var t = text
                // 末尾已经是运算符 → 换成新按的这个；末尾是小数点（`12.`）先去掉
                if (AmountOp.of(t.last()) != null) t = t.dropLast(1)
                if (t.endsWith('.')) t = t.dropLast(1)
                copy(text = t + key.op.symbol)
            }
        }
        AmountKey.Backspace -> copy(text = text.dropLast(1))
    }

    /// 算出来的金额。**算不出来或者 ≤ 0 时返回 null**（保存按钮据此禁用）。
    ///
    /// 规则（跟 iOS `AmountExpression.value` 一样）：
    ///   · 先乘除、后加减
    ///   · 末尾挂着的运算符 / 小数点忽略（`50+` 按 50 算），边输边看结果不会闪成「算不出」
    ///   · 结果四舍五入到分；除以 0、结果 ≤ 0、超过 9 位整数都算不合法
    ///   · 全程 BigDecimal，不碰浮点
    val value: BigDecimal?
        get() {
            val t = text.trimEnd { AmountOp.of(it) != null || it == '.' }
            if (t.isEmpty()) return null

            val numbers = mutableListOf<BigDecimal>()
            val ops = mutableListOf<AmountOp>()
            val current = StringBuilder()
            for (ch in t) {
                val op = AmountOp.of(ch)
                if (op != null) {
                    numbers += parse(current.toString()) ?: return null
                    ops += op
                    current.clear()
                } else current.append(ch)
            }
            numbers += parse(current.toString()) ?: return null

            // 第一遍：乘除
            val terms = mutableListOf(numbers[0])
            val addOps = mutableListOf<AmountOp>()
            for ((i, op) in ops.withIndex()) {
                val rhs = numbers[i + 1]
                when (op) {
                    AmountOp.Times -> terms[terms.lastIndex] = terms.last() * rhs
                    AmountOp.Divide -> {
                        if (rhs.signum() == 0) return null
                        // 中间结果多留几位，最后再统一四舍五入到分 —— 不然 10÷3×3 会算成 9.99
                        terms[terms.lastIndex] = terms.last().divide(rhs, 12, RoundingMode.HALF_UP)
                    }
                    AmountOp.Plus, AmountOp.Minus -> { terms += rhs; addOps += op }
                }
            }
            // 第二遍：加减
            var result = terms[0]
            for ((i, op) in addOps.withIndex()) {
                result = if (op == AmountOp.Plus) result + terms[i + 1] else result - terms[i + 1]
            }

            val rounded = result.setScale(2, RoundingMode.HALF_UP)
            if (rounded.signum() <= 0 || rounded >= BigDecimal(1_000_000_000)) return null
            // 去掉多余的尾零（25.00 → 25），跟 iOS Decimal 存进去的样子一致
            return rounded.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        }

    private fun parse(s: String): BigDecimal? =
        if (s.isEmpty()) null else s.toBigDecimalOrNull()

    companion object {
        /// 每段数字的上限：最多 9 位整数、2 位小数（跟原来那个文本框的规则一样）
        const val MAX_INT = 9
        const val MAX_FRAC = 2

        /// 从一个已有金额开始（编辑一笔旧账时用）。toPlainString：不要科学计数法
        fun of(amount: BigDecimal) = AmountExpression(amount.stripTrailingZeros().toPlainString())
    }
}

/// 键盘本体。四行四列，最后一行是一颗通栏的「保存」。
///
/// ```
///  7  8  9  ÷
///  4  5  6  ×
///  1  2  3  −
///  .  0  ⌫  +
///  [   保存   ]
/// ```
///
/// 为什么「保存」放在键盘上：顶栏那颗大屏单手够不着，而输完金额的那一刻手指正好就在键盘上。
@Composable
fun AmountKeypad(onKey: (AmountKey) -> Unit, canSave: Boolean, onSave: () -> Unit) {
    val rows = listOf(
        listOf(AmountKey.Digit(7), AmountKey.Digit(8), AmountKey.Digit(9), AmountKey.Op(AmountOp.Divide)),
        listOf(AmountKey.Digit(4), AmountKey.Digit(5), AmountKey.Digit(6), AmountKey.Op(AmountOp.Times)),
        listOf(AmountKey.Digit(1), AmountKey.Digit(2), AmountKey.Digit(3), AmountKey.Op(AmountOp.Minus)),
        listOf(AmountKey.Dot, AmountKey.Digit(0), AmountKey.Backspace, AmountKey.Op(AmountOp.Plus)),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (key in row) KeyButton(key, Modifier.weight(1f)) { onKey(key) }
                }
            }
            Button(
                onClick = onSave, enabled = canSave,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("保存", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun KeyButton(key: AmountKey, modifier: Modifier, onClick: () -> Unit) {
    val isAction = key is AmountKey.Op || key is AmountKey.Backspace
    val cs = MaterialTheme.colorScheme
    val name = when (key) {
        is AmountKey.Digit -> "${key.d}"
        AmountKey.Dot -> "小数点"
        is AmountKey.Op -> key.op.label
        AmountKey.Backspace -> "删除"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isAction) cs.secondaryContainer else cs.surfaceContainerLowest,
        contentColor = if (isAction) cs.onSecondaryContainer else cs.onSurface,
        modifier = modifier.height(52.dp).semantics { contentDescription = name },
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (key) {
                is AmountKey.Digit -> Text("${key.d}", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                AmountKey.Dot -> Text(".", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                is AmountKey.Op -> Text(key.op.symbol.toString(), fontSize = 24.sp, fontWeight = FontWeight.Medium)
                AmountKey.Backspace -> Icon(Icons.AutoMirrored.Filled.Backspace, null)
            }
        }
    }
}
