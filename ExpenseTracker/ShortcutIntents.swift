import AppIntents
import SwiftData
import SwiftUI
import UIKit

// 这个文件放「快捷指令」能调的动作。两个都是为「轻点背面」做的
// （iOS 不让 app 自己接管轻点背面，那个开关只能绑到快捷指令上）：
//   · 本月收支：不打开 app，弹卡片看本月支出 / 收入 / 结余
//   · 截图记账：快捷指令先截屏，把图交给这里 → 打开「智能记账」并自动开始识别

// MARK: - 「本月收支」快捷指令动作（2026-10-28 加的）
//
// 用途：**敲三下手机背面，不打开 app，直接弹一张卡片看本月支出 / 收入 / 结余。**
//
// ⚠️ iOS 不让 app 自己接管「轻点背面」，那个开关只能绑到**快捷指令**上。所以这里做的是一个
// 快捷指令动作，再由用户在 设置 → 辅助功能 → 触控 → 轻点背面 → 轻点三下 里选它。
// 下面的 `AppShortcutsProvider` 让它装完就自动出现在快捷指令里，不用用户自己拼步骤。
//
// ⚠️⚠️ 私密记录**恒不算**，跟桌面小组件同一条红线（见 SharedSummary.swift 顶部）：
// 卡片在锁屏 / 别人手里都能弹出来，比 app 里更暴露；数字要跟 app 锁定态对得上。
// 所以这里写死 `visible(unlocked: false)`，不看 app 当前有没有解锁。

struct MonthBalanceIntent: AppIntent {
    static var title: LocalizedStringResource = "本月收支"
    static var description = IntentDescription("不打开 app，弹一张卡片看本月支出、收入和结余")
    /// 不打开 app：在后台算完、直接弹卡片
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ShowsSnippetView {
        let b = try MonthBalance.compute()
        return .result(dialog: IntentDialog(stringLiteral: "本月结余 \(b.balance.yuan)"),
                       view: MonthBalanceSnippet(b: b))
    }
}

/// 让动作装完就出现在「快捷指令」app 里（轻点背面的列表从那里取）。
/// ⚠️「截图记账」不放进来：它要一张图当输入，单独跑没有意义，
/// 必须由用户在快捷指令里接在「截屏」后面（见 ScreenshotEntryIntent）
struct ExpenseAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: MonthBalanceIntent(),
                    phrases: ["\(.applicationName)本月收支", "\(.applicationName)结余"],
                    shortTitle: "本月收支",
                    systemImageName: "yensign.circle")
    }
}

// MARK: - 「截图记账」（2026-10-28 加的）
//
// 用法：快捷指令里两步「截屏 → 截图记账」，再把这个快捷指令绑到轻点背面。
// 付完款停在支付成功页，敲背面 → 截下当前屏 → 打开 app 的智能记账、图已放好、自动开始识别，
// 认完停在核对列表，**仍然要你点「保存」才入账** —— 跟手动选图那条路一模一样，不跳过核对。
//
// ⚠️ 截图本身由系统的「截屏」动作完成，app 拿不到截屏权限；这里只负责接图。

struct ScreenshotEntryIntent: AppIntent {
    static var title: LocalizedStringResource = "截图记账"
    static var description = IntentDescription("把一张图（通常接在「截屏」后面）交给智能记账识别")
    /// 要打开 app：识别完得让你核对再保存
    static var openAppWhenRun = true

    @Parameter(title: "图片", supportedTypeIdentifiers: ["public.image"])
    var image: IntentFile

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let img = UIImage(data: image.data) else {
            throw $image.needsValueError("没收到图片。前一步要是「截屏」")
        }
        PendingScreenshot.shared.image = img
        return .result()
    }
}

/// 快捷指令 → 界面的交接处。intent 往这里放一张图，RootView 看到就打开智能记账，
/// SmartEntryView 取走（取走即清空，避免下次打开又被识别一遍）
@MainActor @Observable
final class PendingScreenshot {
    static let shared = PendingScreenshot()
    var image: UIImage?
}

/// 卡片上要显示的数
struct MonthBalance {
    var monthLabel: String
    var expense: Decimal
    var income: Decimal
    var count: Int
    var top: [(name: String, amount: Decimal)]   // 支出最多的几个分类
    var balance: Decimal { income - expense }

    /// ⚠️ 这里自己开一个 ModelContainer，不借 app 的：快捷指令可能在 app 没运行时被叫起来，
    /// 那时 app 那个容器根本不存在。同一个库开两个容器只读是安全的。
    /// 表的清单必须跟 ExpenseTrackerApp.init 里那行一致，少登记一张运行时会崩。
    @MainActor
    static func compute() throws -> MonthBalance {
        let container = try ModelContainer(for: Expense.self, Tag.self, CategoryDef.self, TagLink.self)
        let ctx = container.mainContext
        let start = Date.now.startOfMonth
        let end = start.addingMonths(1)
        let month = try ctx.fetch(FetchDescriptor<Expense>(
            predicate: #Predicate { $0.date >= start && $0.date < end }))
        let visible = month.visible(unlocked: false)   // ← 隐私红线 + 摘墓碑，别改
        let catalog = CategoryCatalog(try ctx.fetch(FetchDescriptor<CategoryDef>()))

        let top = Dictionary(grouping: visible.expensesOnly, by: \.categoryRaw)
            .map { (name: catalog.name(forKey: $0.key), amount: $0.value.expenseSum) }
            .sorted { $0.amount > $1.amount }
            .prefix(3)

        return MonthBalance(monthLabel: start.monthTitle,
                            expense: visible.expenseSum,
                            income: visible.incomeSum,
                            count: visible.count,
                            top: Array(top))
    }
}

/// 弹出来的那张卡片
struct MonthBalanceSnippet: View {
    let b: MonthBalance

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(b.monthLabel) · 共 \(b.count) 笔")
                .font(.footnote).foregroundStyle(.secondary)
            row("支出", b.expense.yuan, .primary)
            row("收入", b.income.incomeYuan, .green)
            Divider()
            // 结余为负时标红：这个月花的比挣的多
            row("结余", b.balance.yuan, b.balance < 0 ? .red : .primary, bold: true)
            if !b.top.isEmpty {
                Divider()
                ForEach(Array(b.top.enumerated()), id: \.offset) { _, s in
                    HStack {
                        Text(s.name).font(.subheadline).foregroundStyle(.secondary)
                        Spacer()
                        Text(s.amount.yuan).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding()
    }

    private func row(_ title: String, _ value: String, _ color: Color, bold: Bool = false) -> some View {
        HStack {
            Text(title).font(.body)
            Spacer()
            Text(value).font(bold ? .title3.weight(.semibold) : .body).foregroundStyle(color)
                .monospacedDigit()
        }
    }
}
