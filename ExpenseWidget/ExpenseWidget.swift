import WidgetKit
import SwiftUI

// MARK: - 桌面小组件
//
// ⚠️ 小组件**读不到 app 的数据库**。免费 Apple 账号用不了 App Group（实测：苹果给的
// 描述文件里那个键在、值是空数组），所以共享容器和 UserDefaults(suiteName:) 都用不了。
// 数据是 app 写进钥匙串共享组、这里读出来的，机制见 SharedSummary.swift 顶部注释。
//
// ⚠️ 这里显示的数字**恒不含私密记录**。小组件摆在桌面上，比 app 里更暴露；
// 而且只要它跟 app 锁定态的数字对不上，别人一比就知道藏了东西。
// 过滤是在 app 写入那一侧做的，这边拿到什么显示什么。

struct SummaryEntry: TimelineEntry {
    let date: Date
    let summary: ExpenseSummary?
}

struct SummaryProvider: TimelineProvider {
    /// 桌面上拖动添加时的占位图。用假数据，不读钥匙串（那时可能还没写过）
    func placeholder(in context: Context) -> SummaryEntry {
        SummaryEntry(date: .now, summary: ExpenseSummary(
            monthLabel: "2026年8月", total: Decimal(string: "1234.56")!, count: 42,
            top: [.init(name: "餐饮", amount: 800), .init(name: "购物", amount: 300)],
            updatedAt: .now))
    }

    func getSnapshot(in context: Context, completion: @escaping (SummaryEntry) -> Void) {
        completion(SummaryEntry(date: .now, summary: SharedSummaryStore.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SummaryEntry>) -> Void) {
        let entry = SummaryEntry(date: .now, summary: SharedSummaryStore.load())
        // app 每次改动数据都会主动 reload，这里的定时刷新只是兜底
        //（万一某次 reload 没送到，最多一小时也会自己回来读一次）
        let next = Calendar.current.date(byAdding: .hour, value: 1, to: .now) ?? .now.addingTimeInterval(3600)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }
}

struct ExpenseWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: SummaryEntry

    var body: some View {
        if let s = entry.summary, s.count > 0 {
            content(s)
        } else {
            // 空态要说清是「还没数据」而不是「这个月没花钱」——后者会让人以为统计坏了
            VStack(spacing: 4) {
                Text("记账本").font(.caption).foregroundStyle(.secondary)
                Text("打开一次 app").font(.footnote.weight(.medium))
                Text("小组件就会有数据").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private func content(_ s: ExpenseSummary) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(s.monthLabel)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                Text("\(s.count) 笔")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                // 中号才放独立按钮：⚠️ 小号尺寸上系统只认 .widgetURL，
                // Link 在那儿是被忽略的 —— 所以小号靠「整块可点」，见下面的 widgetURL。
                if family != .systemSmall {
                    Link(destination: addURL) {
                        Image(systemName: "plus.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.tint)
                    }
                }
            }
            Text(yuan(s.total))
                .font(.system(size: family == .systemSmall ? 24 : 30,
                              weight: .bold, design: .rounded))
                .monospacedDigit()
                .minimumScaleFactor(0.6)   // 金额大起来别折行，缩小就好
                .lineLimit(1)

            if family != .systemSmall {
                // 中号才放得下分类排行
                ForEach(s.top.prefix(3), id: \.name) { slice in
                    HStack(spacing: 6) {
                        Text(slice.name).font(.caption2)
                        Spacer()
                        Text(yuan(slice.amount))
                            .font(.caption2.weight(.medium))
                            .monospacedDigit()
                    }
                    .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        // 整块可点 → 也直接进「记一笔」。
        // 小号尺寸只有这一条路（Link 在小号上无效），中号则是「点空白处也进记一笔、
        // 点右上角那颗 + 同样进记一笔」——两条路目的地一致，不会让人点错。
        .widgetURL(addURL)
    }

    /// ⚠️ scheme 必须和主 app 的 Info.plist 里注册的那个一字不差，
    /// 否则点了只会打开 app 停在明细页 —— 而且不报任何错，很难发现
    private var addURL: URL { URL(string: "expensetracker://add")! }

    private func yuan(_ v: Decimal) -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencySymbol = "¥"
        f.maximumFractionDigits = v >= 1000 ? 0 : 2   // 四位数以上省掉小数，不然小号放不下
        f.locale = Locale(identifier: "zh_CN")
        return f.string(from: NSDecimalNumber(decimal: v)) ?? "¥\(v)"
    }
}

struct ExpenseWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ExpenseSummaryWidget", provider: SummaryProvider()) { entry in
            ExpenseWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("本月支出")
        .description("显示这个月花了多少、共几笔。不含私密记录。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct ExpenseWidgetBundle: WidgetBundle {
    var body: some Widget {
        ExpenseWidget()
    }
}
