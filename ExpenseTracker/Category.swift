import Foundation
import SwiftData
import SwiftUI

// MARK: - 分类（可自定义，2026-08-24 加）
//
// ## 从「写死 10 个」改成「一张表」时，最关键的一个决定
//
// `Expense` 里存分类的那一列叫 `categoryRaw`，存的是**字符串**（"餐饮"、"购物"…）。
// 这次改动**不动它**，于是历史账目一个字节都不用改 —— 这张 `CategoryDef` 表是纯新增，
// 属于 SwiftData 的自动轻量迁移，跟当初加标签那次同一种（那次已在真机上验过不丢数据）。
//
// 具体做法是把「代号」和「显示名」拆开：
//
// | | 存在哪 | 会不会变 | 干什么用 |
// |---|---|---|---|
// | 代号 `key` | `Expense.categoryRaw` + `CategoryDef.key` | **建好之后永不改** | 把一笔账和一个分类对起来 |
// | 显示名 `name` | 只在 `CategoryDef.name` | 随时可改 | 界面上、导出里显示 |
//
// 预设那 10 个的代号，就直接用它们当初的中文名（"餐饮" 等），所以老账目天然对得上。
// 以后新建的分类，代号取「建的那一刻的名字」，之后改名也不动代号。
//
// **为什么值得这么拆**：不拆的话，改名就得把所有历史记录里的字符串批量改一遍，
// 漏一条那笔账就掉队、统计里凭空多出一个分类。这跟标签表当初的论证是同一条
// （见 `Tag` 的注释：改名只改一处，历史记录跟着变）。

/// 一个分类的定义。
///
/// ⚠️ 所有属性都必须有默认值、关系可空 —— 这是 SwiftData 自动迁移和以后开 iCloud 同步的硬要求，
/// 整个项目统一遵守（同 `Tag` / `Expense`）。
@Model
final class CategoryDef {
    /// 不变的内部代号，等于 `Expense.categoryRaw`。**建好之后任何地方都不许改它**
    var key: String = ""

    /// 显示名，可以改。改了之后所有历史账目跟着变（因为它们记的是 `key`）
    var name: String = ""

    /// SF Symbols 图标名（系统自带的矢量图标库）。清单见 `CategoryIconLibrary`
    var iconName: String = "questionmark.circle.fill"

    /// `CategoryPalette` 的下标。存下标不存色值，换配色只改一处
    var colorIndex: Int = 0

    /// 越小越靠前。表单里的九宫格、筛选面板、统计排行都按它排
    var sortOrder: Int = 0

    /// 兜底分类（「其他」）。**永远不给删**，否则某天真删空了就没有能落脚的分类了
    var isFallback: Bool = false

    // ⚠️ 必须写 Date.now 不能写 .now：@Model 宏要求默认值是完整写法
    var createdAt: Date = Date.now

    init(key: String, name: String, iconName: String,
         colorIndex: Int, sortOrder: Int, isFallback: Bool = false) {
        self.key = key
        self.name = name
        self.iconName = iconName
        self.colorIndex = colorIndex
        self.sortOrder = sortOrder
        self.isFallback = isFallback
        self.createdAt = .now
    }

    var color: Color { CategoryPalette.color(at: colorIndex) }

    // MARK: 名字的清理与查重
    //
    // 照抄 `Tag` 那一套，理由也一样：这个 app 被中文输入法坑过一次（全角句号让 12。75
    // 静默变成 12）。肉眼看不出的空格和全角字符，会让「理发」和「理发 」变成两个分类。

    /// 入库用的显示名：去首尾空白、中间连续空白压成一个空格
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

    var comparisonKey: String { CategoryDef.comparisonKey(name) }
}

// MARK: - 配色

/// 分类配色：取 iOS 系统色板，深浅色自动适配。
/// 比标签那份多几个色，因为分类通常比标签多、更容易撞色。
enum CategoryPalette {
    static let colors: [Color] = [
        .orange, .blue, .pink, .brown, .purple,
        .red, .indigo, .mint, .cyan, .gray,
        .green, .teal, .yellow,
    ]

    static func color(at index: Int) -> Color {
        colors[((index % colors.count) + colors.count) % colors.count]
    }

    /// 新建分类时按已有数量轮着给颜色，尽量不撞色
    static func nextIndex(existingCount: Int) -> Int { existingCount % colors.count }
}

// MARK: - 图标清单

/// 新建 / 编辑分类时能挑的图标。
///
/// **为什么是一份精选清单，而不是让人自己输图标名**：SF Symbols 有几千个，
/// 名字得背（"fork.knife"、"tram.fill"）；输错一个字就是个空白方块，而且不报错。
/// 所以给一格一格的图让人点，输入错误这条路直接堵死。
///
/// 加图标只要往这个数组里加一行，不影响任何已有数据。
enum CategoryIconLibrary {
    /// 分成几组，界面上按组显示，找起来快
    static let groups: [(title: String, icons: [String])] = [
        ("吃喝", ["fork.knife", "cup.and.saucer.fill", "wineglass.fill", "birthday.cake.fill",
                 "carrot.fill", "takeoutbag.and.cup.and.straw.fill"]),
        ("出行", ["tram.fill", "car.fill", "airplane", "bicycle", "fuelpump.fill",
                 "map.fill", "suitcase.fill", "ferry.fill"]),
        ("居家", ["house.fill", "bed.double.fill", "lightbulb.fill", "washer.fill",
                 "wrench.and.screwdriver.fill", "leaf.fill"]),
        ("购物", ["bag.fill", "cart.fill", "giftcard.fill", "shippingbox.fill",
                 "tshirt.fill", "shoe.fill"]),
        ("身体", ["cross.case.fill", "pills.fill", "stethoscope", "figure.run",
                 "dumbbell.fill", "scissors", "comb.fill"]),
        ("学习娱乐", ["book.fill", "graduationcap.fill", "gamecontroller.fill", "film.fill",
                   "music.note", "ticket.fill", "camera.fill", "paintbrush.fill"]),
        ("人情往来", ["gift.fill", "heart.fill", "person.2.fill", "hands.clap.fill"]),
        ("钱与其它", ["arrow.triangle.2.circlepath", "creditcard.fill", "banknote.fill",
                   "pawprint.fill", "phone.fill", "wifi", "ellipsis.circle.fill",
                   "questionmark.circle.fill"]),
    ]

    static let all: [String] = groups.flatMap(\.icons)

    static let fallback = "questionmark.circle.fill"
}

// MARK: - 预设

/// 首次启动时种进库里的 10 个分类。
///
/// ⚠️ **这些 `key` 就是历史账目里 `categoryRaw` 存的值，一个字都不能改。**
/// 改了的话，手机上那些账会找不到自己的分类，全掉进「已删除的分类」里。
/// 想改叫法就改 `name`（或者干脆在 app 里改），`key` 留着。
enum CategorySeed {
    static let builtIn: [(key: String, name: String, icon: String, color: Int)] = [
        ("餐饮", "餐饮", "fork.knife", 0),
        ("交通", "交通", "tram.fill", 1),
        ("购物", "购物", "bag.fill", 2),
        ("居住", "居住", "house.fill", 3),
        ("娱乐", "娱乐", "gamecontroller.fill", 4),
        ("医疗", "医疗", "cross.case.fill", 5),
        ("学习", "学习", "book.fill", 6),
        ("人情", "人情", "gift.fill", 7),
        ("订阅", "订阅", "arrow.triangle.2.circlepath", 8),
        ("其他", "其他", "ellipsis.circle.fill", 9),
    ]

    /// 兜底分类的代号。它永远存在、永远不给删
    static let fallbackKey = "其他"

    /// 把缺的预设补进库里。
    ///
    /// **按 `key` 判断存不存在，不是按「表空不空」**：这样用户删掉某个没用过的预设之后，
    /// 下次启动不会又给他种回来 —— 那种"删了又自己长出来"最气人。
    ///
    /// 只有**整张表是空的**（第一次装、或者从老版本升上来）才会种全套。
    static func seedIfNeeded(_ context: ModelContext) {
        let existing = (try? context.fetch(FetchDescriptor<CategoryDef>())) ?? []
        guard existing.isEmpty else {
            ensureFallbackExists(context, existing: existing)
            return
        }
        for (i, s) in builtIn.enumerated() {
            context.insert(CategoryDef(key: s.key, name: s.name, iconName: s.icon,
                                       colorIndex: s.color, sortOrder: i,
                                       isFallback: s.key == fallbackKey))
        }
        try? context.save()
    }

    /// 兜底分类必须一直在。正常情况下删不掉它，这里只是防御 —— 万一库被外部改坏了，
    /// 也不至于让「新建记录」没有分类可选。
    private static func ensureFallbackExists(_ context: ModelContext, existing: [CategoryDef]) {
        guard !existing.contains(where: { $0.key == fallbackKey }) else { return }
        let maxOrder = existing.map(\.sortOrder).max() ?? 0
        context.insert(CategoryDef(key: fallbackKey, name: "其他",
                                   iconName: "ellipsis.circle.fill", colorIndex: 9,
                                   sortOrder: maxOrder + 1, isFallback: true))
        try? context.save()
    }
}

// MARK: - 查表

/// 把 `Expense.categoryRaw` 那个字符串翻译成「显示名 + 图标 + 颜色」。
///
/// **为什么要有这个东西**：列表一屏几十行，每行都去分类数组里线性找一遍很浪费；
/// 而且「这个代号在表里找不到」这种情况要有统一的兜底，不能每个调用点各写各的。
///
/// 用法：视图用 `@Query` 拿到全部 `CategoryDef`，建一个 catalog，往下传（走 environment）。
struct CategoryCatalog {
    /// 按 `sortOrder` 排好的全部分类
    let all: [CategoryDef]
    private let byKey: [String: CategoryDef]

    init(_ defs: [CategoryDef]) {
        let sorted = defs.sorted { $0.sortOrder < $1.sortOrder }
        self.all = sorted
        self.byKey = Dictionary(sorted.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
    }

    /// 找得到就返回定义；找不到返回 nil。
    ///
    /// ⚠️ **正常情况下不该找不到** —— 删除分类的前提是"一笔都没用过"，所以不会留下悬空的代号。
    /// 这里仍然按找不到处理，是为了扛住「库被外部工具改过」这类意外，不让整个列表崩掉。
    func def(forKey key: String) -> CategoryDef? { byKey[key] }

    func def(for expense: Expense) -> CategoryDef? { byKey[expense.categoryRaw] }

    /// 显示名。找不到定义时**原样显示那个代号**，而不是显示「未知」——
    /// 代号本身就是当初的分类名，显示出来至少还认得出是什么
    func name(forKey key: String) -> String { byKey[key]?.name ?? key }

    func icon(forKey key: String) -> String { byKey[key]?.iconName ?? CategoryIconLibrary.fallback }

    func color(forKey key: String) -> Color { byKey[key]?.color ?? .gray }

    var fallback: CategoryDef? { byKey[CategorySeed.fallbackKey] ?? all.first }
}

// MARK: environment

private struct CategoryCatalogKey: EnvironmentKey {
    static let defaultValue = CategoryCatalog([])
}

extension EnvironmentValues {
    /// 分类目录。在根视图注入一次，各层视图直接读，不用层层传参
    var categoryCatalog: CategoryCatalog {
        get { self[CategoryCatalogKey.self] }
        set { self[CategoryCatalogKey.self] = newValue }
    }
}
