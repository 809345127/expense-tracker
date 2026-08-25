#if DEBUG
import Foundation
import SwiftData

/// 开发期启动参数。正常使用（从桌面点开）不会带这些参数，所以走不到。
///
/// 为什么要靠启动参数把界面摆到想看的状态：这台机器上的 Xcode 27 已经不带 Simulator.app，
/// 模拟器只能无头跑、没有窗口可以发点击事件，`simctl` 也没有输入注入能力。
///
/// | 参数 | 作用 |
/// |---|---|
/// | `-seedDemo` | 用一批演示数据起内存数据库（不落盘） |
/// | `-openTab stats` | 启动就停在统计页 |
/// | `-openSheet form` | 弹出「记一笔」 |
/// | `-openSheet tags` | 弹出「记一笔」并直接打开标签选择器 |
/// | `-openSheet categories` | 弹出「记一笔」并直接打开分类管理（新建/改名/换图标/拖动排序/删除） |
/// | `-dumpDelete` | 配合上一条：把每个分类的「显示几笔 / 实际几笔 / 能不能删」落盘到 Documents/delete.txt。
///   左滑删除这台机器点不了，而这条逻辑压着隐私红线（只被私密记录用着的分类必须删不掉、且不能说出笔数），只能这样验 |
/// | `-openSheet edit` | 弹出**编辑**（最上面那一笔）—— 只有编辑态才有「创建时间」那一行 |
/// | `-unlockPrivate` | 直接以「私密模式已解锁」启动 —— 连点三下那个手势这台机器做不了 |
/// | `-openSheet export` | 弹出「导出」（CSV / 长图） |
/// | `-openSheet filter` | 弹出「按标签筛选」 |
/// | `-filterTags 出差,可报销` | 带着标签筛选启动，用来核对「同一笔只算一次」 |
/// | `-filterCats 餐饮,交通` | 带着**分类**筛选启动 |
/// | `-scrollBottom 1` | 明细页启动后自动滚到最后一行——查底部布局有没有被下面那条「记一笔」压住 |
/// | `-deepLink expensetracker://add` | 走跟「点中号小组件右上角那颗 `+`」完全同一段处理逻辑 → 弹「记一笔」。⚠️ 别用 `simctl openurl` 测：那算「从别的 app 打开」，iOS 会先弹确认框，URL 根本到不了 app |
/// | `-deepLink expensetracker://home` | 走跟「点中号小组件除了 `+` 以外任何地方」同一段逻辑 → 明细页、不弹任何东西。跟上一条**成对**验：只验 add 的话，「点本体不该弹」这半边等于没验 |
/// | `-imgScale 1.5` | 强行指定导出长图的清晰度。用来复现 / 二分「一张位图最高 8192 像素」这个坑，见 Export.swift 的注释 |
enum DevFlags {
    static func has(_ name: String) -> Bool {
        ProcessInfo.processInfo.arguments.contains(name)
    }

    /// 取 `-name value` 形式的值
    static func value(_ name: String) -> String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
        return args[i + 1]
    }
}

/// 只在开发期用：`-seedDemo` 启动参数会用一批演示数据起一个内存数据库（不落盘），
/// 方便截图和调 UI。正常启动完全不走这里。
enum DemoData {
    static func makeInMemoryContainer() -> ModelContainer {
        let config = ModelConfiguration(isStoredInMemoryOnly: true)
        let container = try! ModelContainer(for: Expense.self, Tag.self, CategoryDef.self, configurations: config)
        let context = ModelContext(container)

        // 分类现在是库里的数据，演示库也得先种上，否则九宫格是空的
        CategorySeed.seedIfNeeded(context)

        // 先建标签，后面按名字挂到记录上
        var tags: [String: Tag] = [:]
        for (i, name) in ["出差", "可报销", "请客", "大件"].enumerated() {
            let tag = Tag(name: name, colorIndex: TagPalette.nextIndex(existingCount: i), sortOrder: i)
            context.insert(tag)
            tags[name] = tag
        }

        let cal = Calendar.current
        // 每笔都给一个像样的记账时刻（时分秒），不要全都取「现在」——
        // 否则列表里每行的记账时间一模一样，既看不出排序也看不出这个功能。
        // createdAfter = 创建时间比记账时间晚多少秒：
        //   几十秒 ≈ 当场掏手机记的；上万秒 ≈ 事后补记的（两个时间会落在不同天，正好演示为什么创建时间要带月日）
        // 最后一列是「私不私密」。造两笔出来，才看得出锁上/解锁两个状态下
        // 顶部合计和笔数是不是真的跟着变（这正是这个功能最容易做漏的地方）
        let samples: [(daysAgo: Int, time: String, amount: Double,
                       category: String, note: String,
                       tags: [String], createdAfter: TimeInterval, isPrivate: Bool)] = [
            (0,  "12:18:32", 24.5, "餐饮",          "午饭·公司楼下面馆", [],                 47, false),
            (0,  "09:02:15", 4,    "交通",     "地铁",             ["出差", "可报销"],  92, false),
            (1,  "21:58:44", 15,   "交通",     "打车",             ["出差", "可报销"],  55, false),
            (1,  "19:35:20", 128,  "娱乐", "电影两张票",        ["请客"],       58_020, true),   // 第二天早上才补记
            (1,  "12:41:07", 32.8, "餐饮",          "午饭",             [],                 63, false),
            (2,  "20:07:53", 349,  "购物",      "键帽，\"矮轴\" 一套", ["大件"],           120, false),
            (2,  "15:22:09", 25,   "餐饮",          "咖啡",             [],                 71, false),
            (3,  "18:44:31", 68,   "餐饮",          "周末火锅 AA",       ["请客"],      180_960, false),   // 隔了两天才想起来补
            (3,  "08:15:06", 10,   "交通",     "共享单车月卡",      [],                 40, false),
            (5,  "19:12:38", 52.5, "餐饮",          "晚饭",             [],                 66, false),
            (5,  "10:00:00", 99,   "订阅",  "iCloud + 视频会员",  ["可报销"],         300, false),
            (6,  "11:30:00", 200,  "人情",        "同事结婚随礼",      ["请客"],         3_600, true),
            (8,  "13:27:41", 45,   "学习",     "技术书",           ["可报销"],          95, false),
            (8,  "09:45:12", 1860, "居住",       "房租水电分摊",      [],                 180, false),
            (10, "16:50:23", 30,   "医疗",       "感冒药",           [],                  58, false),
            (11, "12:33:17", 88,   "餐饮",          "日料午市",          ["请客"],            74, false),
            (34, "12:20:44", 21.5, "餐饮",          "午饭",             [],                  61, false),
            (35, "10:05:00", 3200, "居住",       "房租",             [],                 240, false),
            (36, "21:14:29", 599,  "购物",      "降噪耳机",          ["大件"],           150, false),
            (40, "08:33:52", 12,   "交通",     "地铁",             ["出差"],             45, false),
        ]
        for s in samples {
            let day = cal.date(byAdding: .day, value: -s.daysAgo, to: .now)!
            let hms = s.time.split(separator: ":").compactMap { Int($0) }
            let date = cal.date(bySettingHour: hms[0], minute: hms[1], second: hms[2], of: day)!
            let expense = Expense(amount: Decimal(s.amount), categoryKey: s.category, note: s.note,
                                  date: date, isPrivate: s.isPrivate)
            context.insert(expense)
            // 关系要在 insert 之后再建，见 Expense.tags 的注释
            expense.tags = s.tags.compactMap { tags[$0] }
            // init 里 createdAt 取的是「现在」，演示数据要自己覆盖掉
            expense.createdAt = date.addingTimeInterval(s.createdAfter)
        }
        try? context.save()
        return container
    }
}
#endif
