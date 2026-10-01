import SwiftUI

// MARK: - 记账专用数字键盘（2026-10-01 加）
//
// 用户原话：「输入的时候只能通过我自带的输入法，希望能用你们自定义的输入法。
// 如果能带一点加减乘除功能，会比较好一点。」
//
// 两层拆开写：
//   · `AmountExpression` —— 纯逻辑：按键怎么改算式、算式怎么算出结果。不碰界面，好测。
//   · `AmountKeypad`     —— 只管画键盘、把按键转给上面那层。
//
// ⚠️ 安卓那边（`ui/AmountKeypad.kt`）是同一套规则，改一边要同步改另一边：
//    键位、每段数字的位数上限、先乘除后加减、结果保留两位、≤ 0 不给存。

/// 一个按键。
enum AmountKey: Hashable {
    case digit(Int)      // 0...9
    case dot
    case op(AmountOp)
    case backspace
}

enum AmountOp: Character, CaseIterable {
    case plus = "+", minus = "−", times = "×", divide = "÷"
}

/// 输入中的算式，形如 `50−12.5×2`。
///
/// ⚠️ **里面只有正数**。收入不是靠输一个负号表示的，是靠表单顶上的「支出 / 收入」切换
/// —— 键盘上的「−」只用来做减法。两件事混在一个键上，`−50` 到底是「收入 50」还是
/// 「减 50」就说不清了。
struct AmountExpression: Equatable {
    /// 原样显示的字符串（运算符用 `+ − × ÷` 这四个显示字符）
    private(set) var text: String = ""

    /// 每段数字的上限：最多 9 位整数、2 位小数。跟原来那个文本框的规则一样
    static let maxIntDigits = 9
    static let maxFracDigits = 2

    init(_ text: String = "") { self.text = text }

    /// 从一个已有金额开始（编辑一笔旧账时用）。
    /// 用 `NSDecimalNumber.stringValue`：它给的是 `28.5` 这种不带千分位、不带本地化的纯数字
    init(amount: Decimal) {
        self.text = NSDecimalNumber(decimal: amount).stringValue
    }

    var isEmpty: Bool { text.isEmpty }

    /// 算式里有没有运算符（有的话界面才多显示一行「= 结果」）
    var hasOperator: Bool { text.contains { AmountOp(rawValue: $0) != nil } }

    /// 最后一段正在输的数字（运算符后面那截）
    private var lastNumber: Substring {
        if let i = text.lastIndex(where: { AmountOp(rawValue: $0) != nil }) {
            return text[text.index(after: i)...]
        }
        return text[...]
    }

    mutating func press(_ key: AmountKey) {
        switch key {
        case .digit(let d):
            let n = lastNumber
            if let dot = n.firstIndex(of: ".") {
                // 小数位满了就不收
                guard n[n.index(after: dot)...].count < Self.maxFracDigits else { return }
            } else {
                // 整数部分是单独一个 0 时，再按数字是替换它，免得出现 007
                if n == "0" { text.removeLast(); text.append(String(d)); return }
                guard n.count < Self.maxIntDigits else { return }
            }
            text.append(String(d))
        case .dot:
            let n = lastNumber
            guard !n.contains(".") else { return }
            // 直接从小数点开始按时补个 0，跟原来文本框的行为一样
            text.append(n.isEmpty ? "0." : ".")
        case .op(let op):
            guard !text.isEmpty else { return } // 开头不能是运算符（这里没有负数）
            // 末尾已经是运算符 → 换成新按的这个，而不是叠两个
            if let last = text.last, AmountOp(rawValue: last) != nil { text.removeLast() }
            // 末尾是小数点（`12.`）先把它去掉，免得留下 `12.+`
            if text.last == "." { text.removeLast() }
            text.append(op.rawValue)
        case .backspace:
            guard !text.isEmpty else { return }
            text.removeLast()
        }
    }

    /// 算出来的金额。**算不出来或者 ≤ 0 时返回 nil**（保存按钮据此禁用）。
    ///
    /// 规则：
    ///   · 先乘除、后加减（跟手机自带计算器一样，不是从左往右硬算）
    ///   · 末尾挂着的运算符忽略（`50+` 按 50 算），这样边输边看结果不会闪成「算不出」
    ///   · 结果四舍五入到分；除以 0、结果 ≤ 0、超过 9 位整数都算不合法
    ///   · 全程 Decimal，不碰浮点（理由同 Expense.amount）
    var value: Decimal? {
        var t = text
        while let last = t.last, AmountOp(rawValue: last) != nil || last == "." { t.removeLast() }
        guard !t.isEmpty else { return nil }

        // 拆成「数字, 运算符, 数字, …」
        var numbers: [Decimal] = []
        var ops: [AmountOp] = []
        var current = ""
        for ch in t {
            if let op = AmountOp(rawValue: ch) {
                guard let n = Self.parse(current) else { return nil }
                numbers.append(n); ops.append(op); current = ""
            } else {
                current.append(ch)
            }
        }
        guard let tail = Self.parse(current) else { return nil }
        numbers.append(tail)

        // 第一遍：乘除
        var terms: [Decimal] = [numbers[0]]
        var addOps: [AmountOp] = []
        for (i, op) in ops.enumerated() {
            let rhs = numbers[i + 1]
            switch op {
            case .times:  terms[terms.count - 1] *= rhs
            case .divide:
                guard rhs != 0 else { return nil }
                terms[terms.count - 1] /= rhs
            case .plus, .minus:
                terms.append(rhs); addOps.append(op)
            }
        }
        // 第二遍：加减
        var result = terms[0]
        for (i, op) in addOps.enumerated() {
            result = op == .plus ? result + terms[i + 1] : result - terms[i + 1]
        }

        var rounded = Decimal()
        NSDecimalRound(&rounded, &result, 2, .plain)
        guard rounded > 0, rounded < 1_000_000_000 else { return nil }
        return rounded
    }

    private static func parse(_ s: String) -> Decimal? {
        guard !s.isEmpty else { return nil }
        return Decimal(string: s, locale: Locale(identifier: "en_US_POSIX"))
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
/// 为什么「保存」放在键盘上：表单右上角那颗「保存」大屏单手够不着，
/// 而输完金额的那一刻手指正好就在键盘上（跟「记一笔」挪到右下角是同一个理由）。
struct AmountKeypad: View {
    let onKey: (AmountKey) -> Void
    let canSave: Bool
    let onSave: () -> Void

    private let rows: [[AmountKey]] = [
        [.digit(7), .digit(8), .digit(9), .op(.divide)],
        [.digit(4), .digit(5), .digit(6), .op(.times)],
        [.digit(1), .digit(2), .digit(3), .op(.minus)],
        [.dot, .digit(0), .backspace, .op(.plus)],
    ]

    var body: some View {
        VStack(spacing: 8) {
            ForEach(rows.indices, id: \.self) { r in
                HStack(spacing: 8) {
                    ForEach(rows[r], id: \.self) { key in
                        keyButton(key)
                    }
                }
            }
            Button(action: onSave) {
                Text("保存")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 12))
            .disabled(!canSave)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .background(.bar)
    }

    private func keyButton(_ key: AmountKey) -> some View {
        Button { onKey(key) } label: {
            label(for: key)
                .frame(maxWidth: .infinity, minHeight: 48)
                .background(background(for: key), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(Rectangle())
        }
        // ⚠️ .plain：不加的话 App 级 .tint(.blue) 会把数字全染成蓝色
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityName(key))
    }

    @ViewBuilder private func label(for key: AmountKey) -> some View {
        switch key {
        case .digit(let d):
            Text("\(d)").font(.system(size: 24, weight: .medium, design: .rounded))
        case .dot:
            Text(".").font(.system(size: 26, weight: .bold, design: .rounded))
        case .op(let op):
            Text(String(op.rawValue))
                .font(.system(size: 24, weight: .medium, design: .rounded))
                .foregroundStyle(Color.accentColor)
        case .backspace:
            Image(systemName: "delete.left").font(.system(size: 20, weight: .medium))
        }
    }

    private func background(for key: AmountKey) -> Color {
        switch key {
        case .op, .backspace: Color(.tertiarySystemFill)
        default: Color(.secondarySystemGroupedBackground)
        }
    }

    private func accessibilityName(_ key: AmountKey) -> String {
        switch key {
        case .digit(let d): "\(d)"
        case .dot: "小数点"
        case .op(.plus): "加"
        case .op(.minus): "减"
        case .op(.times): "乘"
        case .op(.divide): "除"
        case .backspace: "删除"
        }
    }
}
