#if DEBUG
import Foundation
import SwiftData

/// 「删除墓碑会不会漏进某个界面」的落盘探针。
///
/// 为什么需要它：删除现在是**置墓碑**（不是删行），所以墓碑会一直躺在库里。
/// 任何一个统计口径忘了过滤，症状就是「删掉的账又冒出来」或者「数字对不上」——
/// 而这个 bug **只在删过东西之后才复现**，平时完全看不出来。
/// 这台机器点不了屏幕（没法左滑删除），所以只能用探针：
/// 代码里造几条墓碑，再把每个界面各自算出来的数字、和「应该是多少」一起落盘。
///
/// ⚠️ 每个断言的"应该是多少"都是**独立算的**（用「全部 − 墓碑」这条另一条路），
/// 不是拿被测的那个函数自己算出来再跟自己比 —— 那样的测试恒通过、一点区分力都没有。
///
/// 跑法（见 DevFlags）：
///   -seedDemo -probeTombstone mark      造墓碑再测（实验组）
///   -seedDemo -probeTombstone baseline  同一份数据不造墓碑（对照组）
/// 两次的「应该是多少」不一样，所以对照组能证明这些数字**真的会跟着墓碑变**，
/// 而不是碰巧都对。
enum TombstoneProbe {

    static func run(_ context: ModelContext, unlocked: Bool, catalog: CategoryCatalog) {
        guard let mode = DevFlags.value("-probeTombstone") else { return }
        let mark = (mode == "mark")

        guard var expenses = try? context.fetch(FetchDescriptor<Expense>()),
              let tags = try? context.fetch(FetchDescriptor<Tag>()),
              let cats = try? context.fetch(FetchDescriptor<CategoryDef>()) else { return }
        expenses.sort { $0.date > $1.date }

        var lines: [String] = []
        var pass = 0, fail = 0
        func check(_ what: String, _ got: some Equatable, _ want: some Equatable) {
            let ok = "\(got)" == "\(want)"
            ok ? (pass += 1) : (fail += 1)
            lines.append("  \(ok ? "✓" : "✗") \(what): 算出来 \(got) / 应该 \(want)")
        }

        // ---- 造墓碑：挑 3 笔账（故意含一笔私密的）、1 个标签、1 个没被用过的分类 ----
        var deadExpenses: [Expense] = []
        var deadTagName = "（没造）"
        var deadCatKey = "（没造）"
        if mark {
            // 两笔普通 + 一笔私密。私密那笔很关键：它同时压着「墓碑」和「私密」两道过滤，
            // 两道叠加时最容易出「减重了两次」或者「一次都没减」
            let normals = expenses.filter { !$0.isPrivate }.prefix(2)
            let privates = expenses.filter { $0.isPrivate }.prefix(1)
            deadExpenses = Array(normals) + Array(privates)
            for e in deadExpenses { e.markDeleted() }

            if let t = tags.first(where: { !$0.expenses.isEmpty }) {
                deadTagName = t.name
                for e in t.expenses { e.tags.removeAll { $0.persistentModelID == t.persistentModelID }; e.touch() }
                t.markDeleted()
            }
            // 挑一个一笔都没用过的分类
            let used = Set(expenses.map(\.categoryRaw))
            if let c = cats.first(where: { !used.contains($0.key) && !$0.isFallback }) {
                deadCatKey = c.key
                c.markDeleted()
            }
            try? context.save()
        }

        // 重新取（墓碑已经落盘）
        let all = (try? context.fetch(FetchDescriptor<Expense>())) ?? []
        let allTags = (try? context.fetch(FetchDescriptor<Tag>())) ?? []
        let allCats = (try? context.fetch(FetchDescriptor<CategoryDef>())) ?? []

        // ---- 独立算出「应该是多少」：全部 − 墓碑 ----
        let liveWant = all.filter { !$0.tombstone }
        let visibleWant = liveWant.filter { unlocked || !$0.isPrivate }

        lines.append("模式：\(mode)（\(mark ? "造了墓碑" : "对照组，没造墓碑")）  私密门：\(unlocked ? "已解锁" : "锁着")")
        lines.append("造的墓碑：\(deadExpenses.count) 笔账 / 标签「\(deadTagName)」/ 分类「\(deadCatKey)」")
        lines.append("库里总行数（含墓碑）：账目 \(all.count)、标签 \(allTags.count)、分类 \(allCats.count)")
        lines.append("")

        // ---- 逐个界面口径 ----
        check("明细页笔数（visible）", all.visible(unlocked: unlocked).count, visibleWant.count)
        check("明细页合计（visible）", all.visible(unlocked: unlocked).amountSum, visibleWant.amountSum)
        check("按天分组的总条数", all.visible(unlocked: unlocked).groupedByDay().reduce(0) { $0 + $1.items.count }, visibleWant.count)

        // 统计页跟明细页共用 visible，所以口径必须一致 —— 对不上就是「藏了东西」那类漏点
        check("统计页笔数", all.visible(unlocked: unlocked).count, visibleWant.count)
        check("统计页总额", all.visible(unlocked: unlocked).amountSum, visibleWant.amountSum)

        // 小组件：恒按锁定态算（隐私红线）
        let widgetWant = liveWant.filter { !$0.isPrivate }
        check("小组件笔数（恒锁定态）", all.visible(unlocked: false).count, widgetWant.count)
        check("小组件总额（恒锁定态）", all.visible(unlocked: false).amountSum, widgetWant.amountSum)

        // 导出 CSV：行数 = 表头 1 行 + 记录数
        let csv = ExpenseCSV.make(from: all.visible(unlocked: unlocked), catalog: catalog)
        let csvRows = csv.split(separator: "\n", omittingEmptySubsequences: true).count
        check("导出 CSV 行数", csvRows, visibleWant.count + 1)

        // 标签列表不该出现墓碑标签
        check("标签列表条数", allTags.alive.count, allTags.filter { !$0.tombstone }.count)
        check("墓碑标签不在列表里", allTags.alive.contains { $0.name == deadTagName && mark }, false)

        // 分类：catalog 是全 app 取分类的唯一入口
        check("分类目录条数", CategoryCatalog(allCats).all.count, allCats.filter { !$0.tombstone }.count)
        // ⚠️ 这条只在实验组有意义：对照组压根没造墓碑分类，
        // 拿一个不存在的代号去查当然是 nil —— 那不算「墓碑被挡住了」
        if mark {
            check("墓碑分类查不到", CategoryCatalog(allCats).def(forKey: deadCatKey) == nil, true)
        }

        // 分类用量（删除判定用的那个口径：全部记录、含私密，但不含墓碑）
        let usageWant = Dictionary(grouping: liveWant, by: \.categoryRaw).mapValues(\.count)
        let usageGot = Dictionary(grouping: all.alive, by: \.categoryRaw).mapValues(\.count)
        check("分类用量表", usageGot == usageWant, true)

        // 标签用量走的是关系，不是查询 —— 这一处最容易漏
        if let t = allTags.alive.first {
            check("标签「\(t.name)」用量", t.expenses.alive.count, t.expenses.filter { !$0.tombstone }.count)
        }

        lines.append("")
        lines.append("===== \(pass) 通过 / \(fail) 失败 =====")
        // ⚠️ 带一个每次都不同的运行标记。没有它的话，「探针压根没跑」和
        // 「跑了、结果跟上次一样」在文件内容上一模一样 —— 我就是这么差点把上一轮的
        // 结果当成这一轮的（第二次启动复用了还活着的进程、新参数被丢掉了）
        lines.append("运行标记：\(mode)/\(UUID().uuidString.prefix(8))/\(Date.now.timeIntervalSince1970)")

        if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            try? lines.joined(separator: "\n").write(
                // ⚠️ 文件名带上模式。两个模式写同一个文件的话，「这次压根没跑」和
                // 「跑了、结果跟上次一样」在磁盘上一模一样 —— 而第二次启动复用旧进程、
                // 新参数被丢掉恰恰是这个项目记着的坑。分开文件名之后这两种情况不可能混淆。
                to: dir.appendingPathComponent("tombstone-\(mode).txt"), atomically: true, encoding: .utf8)
        }
    }
}
#endif
