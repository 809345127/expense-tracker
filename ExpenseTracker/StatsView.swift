import SwiftUI
import SwiftData
import Charts
import UIKit

// MARK: - 统计页（Tab 2）

struct StatsScreen: View {
    @Binding var month: Date
    /// 点了某一行（分类或标签）时回调给父级：父级负责写筛选条件 + 切到明细 tab。
    /// 统计页自己不持有筛选状态 —— 它只是「把条件填好」，避免两个页面各存一份。
    var onDrillDown: (ExpenseFilter) -> Void = { _ in }

    var body: some View {
        NavigationStack {
            StatsContent(month: $month, onDrillDown: onDrillDown)
                .navigationTitle("统计")
        }
    }
}

/// 单个分类的月度汇总。
///
/// ⚠️ 按分类**代号**分组，而不是按分类对象 —— 对象是 SwiftData 的托管实例，
/// 拿它当字典键要担心身份问题；代号是纯字符串，稳。名字和颜色渲染时再去目录里查。
private struct CategoryStat: Identifiable {
    let key: String
    let name: String
    let iconName: String
    let color: Color
    let total: Decimal
    let count: Int
    let share: Double // 占比 0~1
    var id: String { key }
}

/// 单个标签的月度汇总。tag 为 nil 表示「未打标签」那一行
private struct TagStat: Identifiable {
    let tag: Tag?
    let total: Decimal
    let count: Int
    let share: Double // 占当月总支出的比例 0~1
    var id: String { tag.map { "tag:" + $0.name } ?? "__untagged__" }
    var name: String { tag?.name ?? "未打标签" }
    var color: Color { tag?.color ?? .gray }
}

private struct StatsContent: View {
    @Binding var month: Date
    var onDrillDown: (ExpenseFilter) -> Void
    @Environment(PrivacyGate.self) private var gate
    @Environment(\.categoryCatalog) private var catalog
    @Query private var allExpenses: [Expense]

    init(month: Binding<Date>, onDrillDown: @escaping (ExpenseFilter) -> Void) {
        _month = month
        self.onDrillDown = onDrillDown
        let start = month.wrappedValue.startOfMonth
        let end = start.addingMonths(1)
        _allExpenses = Query(filter: #Predicate<Expense> { $0.date >= start && $0.date < end })
    }

    /// ⚠️ 这一页往下的每一个数字都必须走这里，不能直接用 allExpenses。
    /// 三个瓦片、圆环、分类排行、按标签排行，漏掉任何一个，
    /// 那个数就会把私密记录的金额算进去、跟明细页对不上（见 PrivacyGate 的注释）。
    private var expenses: [Expense] { allExpenses.visible(unlocked: gate.isUnlocked) }

    private var total: Decimal { expenses.reduce(.zero) { $0 + $1.amount } }

    private var stats: [CategoryStat] {
        let grouped = Dictionary(grouping: expenses) { $0.categoryRaw }
        let totalD = total.asDouble
        return grouped.map { key, items in
            let sum = items.reduce(Decimal.zero) { $0 + $1.amount }
            let def = catalog.def(forKey: key)
            return CategoryStat(
                key: key,
                name: def?.name ?? key,
                iconName: def?.iconName ?? CategoryIconLibrary.fallback,
                color: def?.color ?? .gray,
                total: sum,
                count: items.count,
                share: totalD > 0 ? sum.asDouble / totalD : 0
            )
        }
        .sorted { $0.total > $1.total }
    }

    /// 按标签汇总。
    ///
    /// ⚠️ 这里跟分类不一样：一笔可以打多个标签，所以同一笔会计入它的每个标签，
    /// 各行之间是重叠的、加起来会超过总支出——这是「每个标签各自花了多少」这个
    /// 问题的正确答案，所以界面上必须写清楚重叠，而不能给一个合计数字。
    /// （明细页「按标签筛选」那边是另一套口径：选中多个标签时同一笔只算一次。）
    private var tagStats: [TagStat] {
        let totalD = total.asDouble
        func share(_ d: Decimal) -> Double { totalD > 0 ? d.asDouble / totalD : 0 }

        var sums: [String: (tag: Tag, total: Decimal, count: Int)] = [:]
        var untaggedTotal = Decimal.zero
        var untaggedCount = 0

        for expense in expenses {
            if expense.tags.isEmpty {
                untaggedTotal += expense.amount
                untaggedCount += 1
                continue
            }
            for tag in expense.tags {
                var row = sums[tag.name] ?? (tag, .zero, 0)
                row.total += expense.amount
                row.count += 1
                sums[tag.name] = row
            }
        }

        var rows = sums.values
            .map { TagStat(tag: $0.tag, total: $0.total, count: $0.count, share: share($0.total)) }
            .sorted { $0.total > $1.total }
        if untaggedCount > 0 {
            rows.append(TagStat(tag: nil, total: untaggedTotal, count: untaggedCount, share: share(untaggedTotal)))
        }
        return rows
    }

    private var hasAnyTag: Bool { tagStats.contains { $0.tag != nil } }

    /// 日均：当前月按已过天数算，历史月按整月天数算
    private var dailyAverage: Decimal {
        let cal = Calendar.current
        let days: Int
        if cal.isDate(month, equalTo: .now, toGranularity: .month) {
            days = cal.component(.day, from: .now)
        } else {
            days = cal.range(of: .day, in: .month, for: month)?.count ?? 30
        }
        return days > 0 ? total / Decimal(days) : total
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                MonthSwitcher(month: $month) {
                    if gate.isUnlocked { gate.lock() } else { Task { await gate.unlock() } }
                }
                    .padding(.top, 4)

                if expenses.isEmpty {
                    ContentUnavailableView(
                        "这个月还没有记录",
                        systemImage: "chart.pie",
                        description: Text("去「明细」页记几笔，这里就有图看了")
                    )
                    .padding(.top, 60)
                } else {
                    tiles
                    donut
                    ranking
                    if hasAnyTag {
                        tagRanking
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background(Color(.systemGroupedBackground))
    }

    // 三个数字瓦片
    private var tiles: some View {
        HStack(spacing: 10) {
            tile("总支出", total.yuan)
            tile("笔数", "\(expenses.count)")
            tile("日均", dailyAverage.yuan)
        }
    }

    private func tile(_ title: String, _ value: String) -> some View {
        VStack(spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // 分类占比圆环，中间叠总额
    private var donut: some View {
        ZStack {
            Chart(stats) { s in
                SectorMark(
                    angle: .value("金额", s.total.asDouble),
                    innerRadius: .ratio(0.62),
                    angularInset: 1.5
                )
                .cornerRadius(4)
                .foregroundStyle(s.color.gradient)
            }
            .chartLegend(.hidden)
            VStack(spacing: 4) {
                Text("总支出")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(total.yuan)
                    .font(.system(.title3, design: .rounded, weight: .bold))
                    .monospacedDigit()
            }
        }
        .frame(height: 230)
        .padding(16)
        .frame(maxWidth: .infinity)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // 按标签排行：色点 + 标签名 + 笔数 + 金额 + 占比条
    private var tagRanking: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("按标签")
                .font(.subheadline.weight(.semibold))
                .padding(.top, 14)
                .padding(.bottom, 2)

            ForEach(tagStats) { s in
                Button {
                    // ⚠️「未打标签」那一行没有 tag 可以筛，它不可点（下面 disabled 兜住）
                    if let tag = s.tag { onDrillDown(.only(tag: tag.persistentModelID)) }
                } label: {
                HStack(spacing: 12) {
                    Circle()
                        .fill(s.color)
                        .frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(s.name)
                                .font(.subheadline)
                            Text("\(s.count) 笔")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                            Spacer()
                            Text(s.total.yuan)
                                .font(.subheadline.weight(.semibold))
                                .monospacedDigit()
                            Image(systemName: "chevron.right")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.tertiary)
                                .opacity(s.tag == nil ? 0 : 1)   // 占位不跳动
                        }
                        HStack(spacing: 8) {
                            ProgressView(value: s.share)
                                .tint(s.color)
                            Text("\(Int((s.share * 100).rounded()))%")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                                .frame(width: 34, alignment: .trailing)
                        }
                    }
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
                }
                .buttonStyle(.plain)          // 同分类排行：不加会被 App 级蓝色染成链接样
                .disabled(s.tag == nil)
                if s.id != tagStats.last?.id {
                    Divider().padding(.leading, 22)
                }
            }

            // 这句话不能省：不写清楚重叠，这几行加起来超过总支出会让人以为算错了
            Text("一笔可以打多个标签，所以上面各行之间会重叠、加起来会超过本月总支出。「未打标签」那行不与其它行重叠。")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .padding(.top, 4)
                .padding(.bottom, 12)
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // 分类排行：图标 + 名称 + 笔数 + 金额 + 占比条。整行可点 → 下钻到明细
    private var ranking: some View {
        VStack(spacing: 0) {
            ForEach(stats) { s in
                Button {
                    onDrillDown(.only(categoryKey: s.key))
                } label: {
                HStack(spacing: 12) {
                    CategoryIcon(iconName: s.iconName, color: s.color, size: 36)
                    VStack(alignment: .leading, spacing: 5) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(s.name)
                                .font(.subheadline)
                            Text("\(s.count) 笔")
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                            Spacer()
                            Text(s.total.yuan)
                                .font(.subheadline.weight(.semibold))
                                .monospacedDigit()
                            Image(systemName: "chevron.right")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.tertiary)
                        }
                        HStack(spacing: 8) {
                            ProgressView(value: s.share)
                                .tint(s.color)
                            Text("\(Int((s.share * 100).rounded()))%")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                                .frame(width: 34, alignment: .trailing)
                        }
                    }
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())   // 空白处也能点，不用非戳在文字上
                }
                // ⚠️ 必须 .plain：App 级 .tint(.blue) 会把整行文字染成蓝色、看着像链接。
                // 判据是「这一行是承载内容的行、还是一个动作按钮」——这里是前者。
                .buttonStyle(.plain)
                if s.id != stats.last?.id {
                    Divider().padding(.leading, 48)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
