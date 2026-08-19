import Foundation
import SwiftData
import SwiftUI

// MARK: - 支出记录（@Model 由 SwiftData 负责存取，相当于 ORM 实体，存在手机本地）

@Model
final class Expense {
    var amount: Decimal        // 金额。用 Decimal 避免浮点误差
    var categoryRaw: String    // 分类存字符串，以后加分类不用迁移数据
    var note: String           // 备注，可为空
    var date: Date             // 记账时间：这笔钱花出去的时刻，带时分秒、可手动改。列表按它倒序，并按天分组
    var createdAt: Date        // 创建时间：这条记录写进库的那一刻。自动记、编辑时不改，用来看一笔账是当场记的还是后来补的

    /// 私密记录：锁着的时候，整个 app 当它不存在（列表、合计、笔数、统计页全部排除）。
    ///
    /// ⚠️ **必须有默认值**。这是这个 app 第一次往已有的表里加列，靠的是 SwiftData 的
    /// 自动轻量迁移；没有默认值的新字段迁移不了，老库一打开就崩。
    /// （加标签那次是新增一张表 + 一个默认空数组的关系，也是同一条规矩。）
    var isPrivate: Bool = false

    /// 标签（多选、可选）。多对多，反向关系声明在 `Tag.expenses` 上。
    ///
    /// ⚠️ 不要在 init 里赋值：SwiftData 要求对象先 insert 进 context 再建关系，
    /// 否则关系可能建不上。统一在插入之后 `expense.tags = [...]`。
    var tags: [Tag] = []

    init(amount: Decimal, category: ExpenseCategory, note: String = "",
         date: Date = .now, isPrivate: Bool = false) {
        self.amount = amount
        self.categoryRaw = category.rawValue
        self.note = note
        self.date = date
        self.createdAt = .now
        self.isPrivate = isPrivate
    }

    var category: ExpenseCategory {
        get { ExpenseCategory(rawValue: categoryRaw) ?? .other }
        set { categoryRaw = newValue.rawValue }
    }

    /// 列表行主标题：有备注显示备注，否则显示分类名
    var title: String {
        note.isEmpty ? category.rawValue : note
    }
}

// MARK: - 标签（多选、可选。和「分类」是两个独立维度）
//
// 分类回答「这笔钱花在什么品类上」，单选必填；标签回答分类答不了的问题
// ——「这次出差一共花了多少」「哪些能报销」，多选可选。
//
// 为什么单独一张表，而不是在 Expense 上存 ["出差","可报销"] 这样的字符串数组：
//   ① 改名只改一处，所有历史记录跟着变；存字符串就得遍历所有记录批量改，
//      漏一条就裂成两个标签，统计直接分家
//   ② 有唯一实体，"出差" 和 "出差 " 不会变成两个
//   ③ 配色、排序、停用这些附加信息有地方放
//
// 代价：多对多关系在 SwiftData 的 #Predicate 里不好写查询条件，所以按标签
// 筛选和汇总一律在内存里算（见下面的 matchingAny）。这个 app 一年几千笔，无所谓。
@Model
final class Tag {
    var name: String = ""
    var colorIndex: Int = 0        // TagPalette 的下标。存下标而不是 hex，换配色只改一处
    var sortOrder: Int = 0         // 越小越靠前。留给以后「常用标签置顶」
    var isArchived: Bool = false   // 停用但不删（历史统计还要用它）。界面暂时没有入口
    // ⚠️ 这里必须写 Date.now 不能写 .now：@Model 宏要求默认值是完整写法，
    // 用简写会报 "A default value requires a fully qualified domain named value"
    var createdAt: Date = Date.now

    /// 反向关系。删掉标签时 SwiftData 只解除关联，不会连带删记录
    @Relationship(inverse: \Expense.tags) var expenses: [Expense] = []

    init(name: String, colorIndex: Int = 0, sortOrder: Int = 0) {
        self.name = Tag.cleanedName(name)
        self.colorIndex = colorIndex
        self.sortOrder = sortOrder
        self.createdAt = .now
    }

    var color: Color { TagPalette.color(at: colorIndex) }

    // MARK: 名字的清理与查重
    //
    // 这个 app 在金额上被中文输入法坑过一次（全角句号让 12。75 静默变成 12）。
    // 标签同理：肉眼看不出的空格、全角字符会让同一个标签裂成两个。所以入库前统一清理，
    // 查重时再忽略大小写和全角半角差异。
    // 注意：故意不用 SwiftData 的 @Attribute(.unique)
    //   —— 它冲突时的行为是静默覆盖而不是报错，而且以后要开 iCloud 同步的话它根本不支持。

    /// 入库用的显示名：去掉首尾空白、中间连续空白压成一个空格。不动大小写、不动全角字符
    static func cleanedName(_ raw: String) -> String {
        raw.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    /// 查重用的比较形式：忽略大小写、全角半角、变音符号
    static func comparisonKey(_ raw: String) -> String {
        cleanedName(raw).folding(
            options: [.caseInsensitive, .widthInsensitive, .diacriticInsensitive],
            locale: nil
        )
    }

    var comparisonKey: String { Tag.comparisonKey(name) }
}

/// 标签配色：取 iOS 系统色板，深浅色自动适配
enum TagPalette {
    static let colors: [Color] = [.blue, .green, .orange, .pink, .purple, .teal, .indigo, .brown]

    static func color(at index: Int) -> Color {
        colors[((index % colors.count) + colors.count) % colors.count]
    }

    /// 新建标签时按已有数量轮着给颜色，尽量不撞色
    static func nextIndex(existingCount: Int) -> Int { existingCount % colors.count }
}

// MARK: - 按标签筛选与汇总
//
// 核心规则：**一笔记录只算一次钱**。
// 选中多个标签时，同一笔被其中好几个标签同时命中，也只计入一次
// —— 所以这里是「按记录过滤」，而不是「按标签遍历累加」，去重是免费的。
extension Array where Element == Expense {
    /// 命中任意一个给定标签的记录。传空集合表示不筛选，原样返回
    func matchingAny(of tagIDs: Set<PersistentIdentifier>) -> [Expense] {
        guard !tagIDs.isEmpty else { return self }
        return filter { expense in
            expense.tags.contains { tagIDs.contains($0.persistentModelID) }
        }
    }

    var amountSum: Decimal { reduce(.zero) { $0 + $1.amount } }

    /// 私密门锁着时把私密记录整个摘掉。**所有**用到记录的地方都要先过这一层
    /// ——列表、本月合计、笔数、统计页的每个数字，一个都不能漏。
    /// 漏掉任何一个，那个数就和看得见的行对不上，等于直接告诉别人这里藏了东西。
    ///
    /// ⚠️ 故意在内存里滤，不写进 SwiftData 的 `#Predicate`：
    /// 这个项目已经记着「`#Predicate` 少用布尔取反（`!$0.isArchived` 这类），
    /// 编译能过但运行时可能抛『不支持的谓词』、把整个界面打崩」。
    /// 按标签筛选也是同样的理由在内存里做的。一年几千笔，成本可以忽略。
    func visible(unlocked: Bool) -> [Expense] {
        unlocked ? self : filter { $0.isPrivate == false }
    }

    /// 按天分组，天倒序；组内保持原顺序（也就是查询给的记账时间倒序）。
    /// 明细页和导出长图共用这一份 —— 分开写的话迟早分叉，图和界面对不上。
    func groupedByDay() -> [(date: Date, items: [Expense], total: Decimal)] {
        // Calendar.current 每次访问都会新建一个 Calendar 值，不是缓存的单例。
        // 提到闭包外面，几百笔就少几百次构造
        let cal = Calendar.current
        let grouped = Dictionary(grouping: self) { cal.startOfDay(for: $0.date) }
        return grouped.keys.sorted(by: >).map { day in
            let items = grouped[day]!
            return (day, items, items.amountSum)
        }
    }
}

// MARK: - 分类（颜色取 iOS 系统色板，和原型一致）

enum ExpenseCategory: String, CaseIterable, Identifiable {
    case food = "餐饮"
    case transport = "交通"
    case shopping = "购物"
    case housing = "居住"
    case entertainment = "娱乐"
    case medical = "医疗"
    case education = "学习"
    case social = "人情"
    case subscription = "订阅"
    case other = "其他"

    var id: String { rawValue }

    /// SF Symbols 图标名（系统自带矢量图标库）
    var icon: String {
        switch self {
        case .food: "fork.knife"
        case .transport: "tram.fill"
        case .shopping: "bag.fill"
        case .housing: "house.fill"
        case .entertainment: "gamecontroller.fill"
        case .medical: "cross.case.fill"
        case .education: "book.fill"
        case .social: "gift.fill"
        case .subscription: "arrow.triangle.2.circlepath"
        case .other: "ellipsis.circle.fill"
        }
    }

    var color: Color {
        switch self {
        case .food: .orange
        case .transport: .blue
        case .shopping: .pink
        case .housing: .brown
        case .entertainment: .purple
        case .medical: .red
        case .education: .indigo
        case .social: .mint
        case .subscription: .cyan
        case .other: .gray
        }
    }
}

// MARK: - 主题

enum Theme {
    /// 明细页顶部大卡片的渐变（#2E8BFF -> #084A94），与 App 图标同源
    static let gradTop = Color(red: 0.18, green: 0.545, blue: 1.0)
    static let gradBottom = Color(red: 0.031, green: 0.29, blue: 0.58)
    static var cardGradient: LinearGradient {
        LinearGradient(colors: [gradTop, gradBottom], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

// MARK: - 格式化与日期工具

extension Decimal {
    /// 统一的人民币展示，如 ¥1,234.50（锁定 zh_CN，不随设备语言变）
    var yuan: String {
        formatted(.currency(code: "CNY").locale(Locale(identifier: "zh_CN")))
    }

    var asDouble: Double {
        NSDecimalNumber(decimal: self).doubleValue
    }
}

extension Date {
    var startOfMonth: Date {
        Calendar.current.dateInterval(of: .month, for: self)!.start
    }

    func addingMonths(_ n: Int) -> Date {
        Calendar.current.date(byAdding: .month, value: n, to: self)!.startOfMonth
    }

    /// 2026年8月
    var monthTitle: String { Self.monthFormatter.string(from: self) }
    /// 8月12日 周三
    var dayTitle: String { Self.dayFormatter.string(from: self) }

    private static let monthFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy年M月"
        return f
    }()

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "M月d日 EEE"
        return f
    }()

    // MARK: 时间戳（记账时间 / 创建时间，都精确到秒）
    //
    // ⚠️ 下面三个用的是 en_US_POSIX 而不是 zh_CN：格式串是写死的纯数字，
    // 而 DateFormatter 在普通 locale 下会被系统「12/24 小时制」那个开关影响，
    // 用户把手机设成 12 小时制时可能吐出 "上午9:38"。en_US_POSIX 是 Apple 明确
    // 推荐的「固定格式」locale，输出永远和格式串一字不差。
    // 上面 monthTitle / dayTitle 用 zh_CN 是因为它们要的就是中文月日和「周三」。

    /// 09:38:24 — 列表行里的记账时间。不带月日，因为它所在的分组标题已经写了是哪天
    var timeTitle: String { Self.timeFormatter.string(from: self) }

    /// 08-19 09:40:12 — 列表行里的创建时间。必须带月日：
    /// 补记的账（比如今天补昨天的），记账时间和创建时间根本不在同一天
    var stampTitle: String { Self.stampFormatter.string(from: self) }

    /// 2026-08-19 09:38:24 — 表单里那种带年份的完整形式
    var fullStampTitle: String { Self.fullStampFormatter.string(from: self) }

    private static let timeFormatter = fixedFormatter("HH:mm:ss")
    private static let stampFormatter = fixedFormatter("MM-dd HH:mm:ss")
    private static let fullStampFormatter = fixedFormatter("yyyy-MM-dd HH:mm:ss")

    private static func fixedFormatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = format
        return f
    }
}
