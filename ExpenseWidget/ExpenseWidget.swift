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
                if family != .systemSmall { addButton }
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
        // 整块可点。**两个尺寸的目的地故意不一样**，因为平台能力不一样：
        //
        // · 中号：点右上角那颗 + 才记一笔，点其它任何地方 → 进 app 主页面（明细页）。
        //   「记一笔」这个动作只归那颗按钮，别处点不出来。
        // · 小号：整块 → 记一笔。⚠️ 不是没统一，是**统一不了**：Link 在小号上被系统
        //   忽略，小号只认 widgetURL 这一个目的地，摆颗 + 上去也点不动（点它等于点整块）。
        //   所以小号只能二选一，选了保留「一点就记账」这个快捷方式 —— 否则小号就只剩
        //   「打开 app」这一件事，跟直接点桌面图标没区别了。
        .widgetURL(family == .systemSmall ? addURL : homeURL)
    }

    /// 右上角那颗「记一笔」。**做成实心胶囊 + 带字，不是一枚裸图标**，两个原因：
    ///
    /// ① **点它和点它旁边现在是两个不同的去处**（点它记一笔、点别处进 app）。
    ///    裸的 `plus.circle.fill` 连图标带笔画才 20 多 pt，差几个 pt 就会
    ///    「明明点的是 +、却进了主页面」—— 那看起来就是功能坏了，而不是手指偏了。
    /// ② 顺带把这条新规矩说明白：桌面上看一眼就知道「记账要点这颗」，
    ///    不用记「哪里能点、哪里不能点」。
    ///
    /// 尺寸是这么配平的：**视觉高度不许涨、命中区要涨**。中号的高度本来就只剩一两 pt
    /// 余量（下面紧跟着三行分类排行），行高一涨就把内容挤掉；所以垂直方向用
    /// 「透明 padding 撑命中区 + 外层负 padding 把空间还给排版」，
    /// 视觉上一动没动、手指能碰到的范围高了一倍。
    /// ⚠️ `contentShape` 少不了：padding 撑出来的是透明留白，不声明形状的话点在留白上不算命中。
    private var addButton: some View {
        Link(destination: addURL) {
            HStack(spacing: 3) {
                Image(systemName: "plus").font(.caption.weight(.bold))
                Text("记一笔").font(.caption.weight(.semibold))
                    .lineLimit(1).minimumScaleFactor(0.8)   // 系统字号拉到最大时缩一点，别把胶囊撑爆
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Capsule().fill(.tint))
            .padding(.vertical, 7)          // ← 只撑命中区（透明），不是视觉留白
            .contentShape(Rectangle())
        }
        .padding(.vertical, -7)             // ← 把上面撑出来的 7pt 还给排版
    }

    /// ⚠️ scheme 必须和主 app 的 Info.plist 里注册的那个一字不差，
    /// 否则点了只会打开 app 停在明细页 —— 而且不报任何错，很难发现
    private var addURL: URL { URL(string: "expensetracker://add")! }

    /// 点小组件本体 → 只是把 app 打开、停在明细页，不弹「记一笔」。
    /// ⚠️ 这里必须是一个**显式**的 URL、并且在 app 那边真的处理它，不能图省事把
    /// `.widgetURL` 整个去掉：去掉之后点一下只是「把 app 调到前台」，如果上次是
    /// 带着「记一笔」弹层切走的，回来还停在那个弹层上 —— 症状跟没改一模一样。
    private var homeURL: URL { URL(string: "expensetracker://home")! }

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
