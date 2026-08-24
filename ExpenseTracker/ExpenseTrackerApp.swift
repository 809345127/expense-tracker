import SwiftUI
import SwiftData
import WidgetKit

@main
struct ExpenseTrackerApp: App {
    let container: ModelContainer
    /// 私密记录的门。整个 app 共享一个，两个 tab 和表单都从环境里取
    @State private var gate = PrivacyGate()
    #if DEBUG
    @State private var ranKeychainProbe = false
    #endif
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // -seedDemo 只用于开发期截图演示：数据放内存、不落盘，正常启动完全不走这里
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-seedDemo") {
            container = DemoData.makeInMemoryContainer()
            return
        }
        #endif
        do {
            container = try ModelContainer(for: Expense.self, Tag.self, CategoryDef.self)
            // 分类从「写死的枚举」改成「库里的一张表」之后，第一次启动（以及从老版本
            // 升上来的第一次）得把那 10 个预设种进去，否则记一笔时九宫格是空的。
            // ⚠️ 按 key 判断存不存在，删掉的预设不会自己长回来，见 CategorySeed
            CategorySeed.seedIfNeeded(ModelContext(container))
        } catch {
            fatalError("初始化本地数据库失败：\(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()
                WidgetSync()

                // 切走的那一瞬间，iOS 会给 app 拍一张快照放进多任务卡片里。
                // 解锁状态下不盖住的话，那张卡片就把私密记录原样露出去了
                // —— 而多任务卡片恰恰是「随手翻手机」最容易看到的地方。
                // ⚠️ 只在已解锁时盖，否则 Face ID 弹窗期间（也是 inactive）会白闪一下。
                if gate.isUnlocked && scenePhase != .active {
                    Color(.systemBackground).ignoresSafeArea()
                }
            }
            .tint(.blue) // 系统蓝：浅色 #007AFF / 深色 #0A84FF 自动切换
            .environment(\.locale, Locale(identifier: "zh_CN"))
            .environment(gate)
            .modifier(CategoryCatalogProvider())
        }
        .modelContainer(container)
        #if DEBUG
        // -kcProbe：验「app 和小组件能不能靠钥匙串共享数据」这条路通不通。
        // ⚠️ 只有真机结果算数——模拟器对钥匙串权限的检查比真机松，
        //    在模拟器上通过**不能**推论真机也行。
        .onChange(of: scenePhase, initial: true) { _, _ in
            guard DevFlags.value("-kcProbe") != nil, !ranKeychainProbe else { return }
            ranKeychainProbe = true
            // ⚠️ 只读：早先这里会写一条假摘要进去，结果把 WidgetSync 刚算出来的真数据覆盖了，
            // 看到的永远是「探针月 123.45」。写入能力已经单独验过，这里不该再写。
            let readBack = SharedSummaryStore.load()
            // 落盘而不是 print：这台机器捞不到模拟器/真机的 stdout，
            // 落到 Documents 再用 devicectl / simctl 把文件拉出来看，是这个项目验证过的老办法
            let report = """
            共享组：\(SharedSummaryStore.accessGroup ?? "（算不出来 —— 团队前缀探测失败）")
            摘要月份：\(readBack?.monthLabel ?? "nil")
            总额：\(readBack?.total ?? -1)
            笔数：\(readBack?.count ?? -1)
            分类前三：\(readBack?.top.map { "\($0.name) \($0.amount)" }.joined(separator: " / ") ?? "nil")
            更新时间：\(readBack?.updatedAt.description ?? "nil")
            诊断：\(SharedSummaryStore.diagnostics())
            """
            if let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                try? report.write(to: dir.appendingPathComponent("kcprobe.txt"),
                                  atomically: true, encoding: .utf8)
            }
        }
        #endif
        .onChange(of: scenePhase) { _, phase in
            // 切到后台就上锁。把手机递给别人之前基本都会先按一下 home 或者切走，
            // 这一下就把门关上了 —— 比指望自己记得手动退出可靠得多。
            //
            // ⚠️ 判的是 .background 不是 `!= .active`：
            // 拉控制中心、来通知横幅、**以及 Face ID 系统弹窗本身**都会让 app 变成
            // .inactive。按 != .active 上锁的话，Face ID 一弹就把自己刚开的门关上，
            // 永远解不开。
            if phase == .background { gate.lock() }
        }
    }
}

// MARK: - 界面上几个定过稿的决定（2026-08-20，都是拿真机 / iOS 27 模拟器实测选出来的）
//
// 这三件事各自比过几个方案，结论写在这里，免得以后有人「顺手改回去」：
//
// ① **「记一笔」放 tab 栏正上方的横条**（`tabViewBottomAccessory`）。
//    最早放导航栏右上角 —— 16 Pro Max 单手够不着；
//    改成底部正中悬浮圆钮 —— 实测跟 tab 栏**垂直重叠 61pt**，而且这个 app 只有两个 tab、
//    水平正中恰好是两个按钮的接缝，结果两个 tab 都点不动；
//    改用 `ToolbarItem(placement: .bottomBar)` —— 在 TabView 里会被塞到 tab 栏后面，
//    按钮**压根不显示**。
//    根因是前两种的坐标都由我们自己算、系统不知道它在哪。`tabViewBottomAccessory`
//    是 iOS 26 起专门为这个场景做的 API，**位置由系统排版**，结构上压不到 tab 栏。
//    （Apple HIG 里没有「悬浮按钮」这个模式，那是 Material Design 的东西。）
//
// ② **顶部工具栏按钮成组、月度卡片用素净样式**。
//    对着 iOS 27 上 Apple 日历的真实截图比出来的：Apple 把相关动作收进**一个玻璃胶囊**
//    （日历右上角一个胶囊塞了三个图标），而且整屏灰白、颜色只用来标重点。
//    所以筛选和分享都放 `.topBarTrailing` —— **分组是靠「放在同一侧」实现的**，
//    iOS 26 起同一 placement 里相邻的按钮会自动合成一个胶囊，要拆开才用 ToolbarSpacer。
//
// ③ **列表每一行是两行制**：第一行「这是什么」（分类/备注 + 标签色块），
//    第二行「什么时候」。原来一行堆四层，其中时间那行大多数时候是纯噪音
//    —— 当场记的账两个时间只差几秒。现在创建时间**只在真的是补记时才出现**
//    （见 ExpenseRow.isBackfilled），信息没丢、噪音去掉了。


struct RootView: View {
    enum Tab: String { case list, stats }

    @State private var tab: Tab
    @State private var month: Date // 明细/统计两页共享的「当前查看月份」

    /// 「记一笔」弹层。跟着悬浮按钮一起放在两个 tab 的共同父级
    @State private var showingAdd = false
    #if DEBUG
    @State private var appliedDebugSheet = false
    @State private var appliedDebugLink = false
    #endif

    /// 筛选条件。**全 app 只有这一份**，所以放在两个 tab 的共同父级。
    /// 统计页点某一行 = 往这里写值 + 切到明细 tab；明细页的筛选面板 = 直接改这里。
    /// 两条路径落到同一个状态，不会出现「统计页筛了一套、明细页又筛了一套」。
    @State private var filter = ExpenseFilter()

    init() {
        var initialTab = Tab.list
        #if DEBUG
        // -openTab stats：截图时直接打开统计页
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-openTab"), i + 1 < args.count,
           let t = Tab(rawValue: args[i + 1]) {
            initialTab = t
        }
        #endif
        _tab = State(initialValue: initialTab)
        _month = State(initialValue: Date.now.startOfMonth)
    }

    /// 桌面小组件点一下 → `expensetracker://add` → 直接弹「记一笔」。
    /// ⚠️ scheme 注册在 ExpenseTracker/Info.plist 里，删了这里就永远收不到回调。
    /// ⚠️ 判 host 不判整串：URL 以后可能带 query（比如「记某个分类」），
    ///    用 == 全等匹配会在那天悄悄失效。
    private func handleDeepLink(_ url: URL) {
        guard url.scheme == "expensetracker" else { return }
        if url.host() == "add" { showingAdd = true }
    }

    var body: some View {
        TabView(selection: $tab) {
            ExpenseListScreen(month: $month, filter: $filter)
                .tabItem { Label("明细", systemImage: "list.bullet.rectangle.fill") }
                .tag(Tab.list)
            StatsScreen(month: $month) { newFilter in
                // 统计页点了某一行：换成只看它，然后切过去看明细
                filter = newFilter
                tab = .list
            }
            .tabItem { Label("统计", systemImage: "chart.pie.fill") }
            .tag(Tab.stats)
        }
        // 「记一笔」：tab 栏正上方一条横条，位置由系统排、压不到 tab 栏（理由见文件顶部 ①）。
        // ⚠️ 挂在 TabView 上而不是某一页里：两个 tab 都能记一笔，不用先切回明细页
        .modifier(AddEntryAccessory { showingAdd = true })
        .sheet(isPresented: $showingAdd) { ExpenseFormView() }
        // 桌面小组件点一下 → expensetracker://add → 直接弹「记一笔」。
        // ⚠️ scheme 注册在 ExpenseTracker/Info.plist 里，删了这里就永远收不到回调。
        // ⚠️ 判 host 不判整串：URL 可能带 query（以后加「记某个分类」时会用到），
        //    用 == 全等匹配会在那天悄悄失效。
        .onOpenURL { handleDeepLink($0) }
        #if DEBUG
        // -deepLink expensetracker://add：走跟真实点击**完全同一段**处理逻辑。
        // ⚠️ 为什么不用 `simctl openurl` 测：那是「从别的 app 打开」，iOS 会先弹一个
        //「在"记账本"中打开？」的确认框，把 URL 拦在外面，测不到后面的事。
        //    从小组件点是 app 打开自己的 URL，不弹这个框 —— 所以那条路只能在真机上收尾验。
        .onAppear {
            guard !appliedDebugLink, let raw = DevFlags.value("-deepLink"),
                  let url = URL(string: raw) else { return }
            appliedDebugLink = true
            handleDeepLink(url)
        }
        #endif
        #if DEBUG
        // -openSheet form / tags：截图时直接把「记一笔」摆出来。
        // ⚠️ 用一次性门闩，不能拿 showingAdd == false 当条件
        //（关掉弹层它就又成立了，切个 tab 回来会自己弹回来）
        .onAppear {
            guard !appliedDebugSheet else { return }
            appliedDebugSheet = true
            // categories 也要先把表单摆出来 —— 分类管理的入口就挂在表单的「分类」那一组标题上
            if ["form", "tags", "categories"].contains(DevFlags.value("-openSheet") ?? "") { showingAdd = true }
        }
        #endif
    }

}

/// tab 栏上方那条「记一笔」。
///
/// ⚠️ `tabViewBottomAccessory` 要 iOS 26+，而这个工程的最低版本是 iOS 17
/// （README 写明的），所以必须带版本判断。
/// 低版本的降级实现用 `safeAreaInset` —— 它是**真的把安全区撑开**，
/// 所以那条按钮同样压不到 tab 栏；不能改成 `.overlay`，那个会压上去（实测 61pt）。
private struct AddEntryAccessory: ViewModifier {
    let action: () -> Void

    private var label: some View {
        Label("记一笔", systemImage: "plus")
            .font(.body.weight(.medium))
            .frame(maxWidth: .infinity)
    }

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.tabViewBottomAccessory { Button(action: action) { label } }
        } else {
            content.safeAreaInset(edge: .bottom) {
                Button(action: action) { label.padding(.vertical, 12) }
                    .background(.regularMaterial)
            }
        }
    }
}

// MARK: - 桌面小组件的数据同步

/// 一个不占位置的隐形视图：当月账目一变，就把摘要写进钥匙串共享组、并让小组件刷新。
///
/// 为什么单独做一层而不是挂在明细页上：明细页显示的是**你正在翻的那个月**，
/// 而小组件永远显示**当前月**。挂在明细页上的话，你往回翻一个月，小组件就跟着变成上个月了。
///
/// ⚠️⚠️ **这里恒用 `visible(unlocked: false)`，也就是永远按锁定态算。**
/// 小组件摆在桌面上，谁拿起手机都看得见，比 app 里更暴露；而且只要它的数字跟 app
/// 锁定态对不上，别人一比就知道你藏了东西、还知道藏了多少。
/// 这不是开关，是写死的 —— 改这行之前请先读 PrivacyGate.swift 顶部那段。
private struct WidgetSync: View {
    @Query private var monthExpenses: [Expense]
    @Environment(\.categoryCatalog) private var catalog

    init() {
        let start = Date.now.startOfMonth
        let end = start.addingMonths(1)
        _monthExpenses = Query(filter: #Predicate<Expense> { $0.date >= start && $0.date < end })
    }

    /// 变化检测用的指纹。⚠️ 不能只看笔数：改金额时笔数不变，光看笔数会漏掉更新
    private var digest: String {
        let visible = monthExpenses.visible(unlocked: false)
        return "\(visible.count)|\(visible.amountSum)"
    }

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
            .onChange(of: digest, initial: true) { _, _ in push() }
    }

    private func push() {
        let visible = monthExpenses.visible(unlocked: false)   // ← 隐私红线，别改
        // 摘要里存的是**显示名**（小组件那边没有库、翻译不了代号）。
        // 所以分类改名之后，小组件上的名字要等这里下一次推送才会跟着变 —— 而任何一次
        // 记账 / 改分类都会触发推送，所以最多差一次操作，不会长期不一致
        let byCategory = Dictionary(grouping: visible, by: \.categoryRaw)
            .map { ExpenseSummary.Slice(name: catalog.name(forKey: $0.key), amount: $0.value.amountSum) }
            .sorted { $0.amount > $1.amount }

        // 用 app 自己那个 monthTitle，跟明细页顶部卡片显示的是同一个字符串
        // —— 各写各的格式化器迟早分叉（一个「2026年8月」一个「2026年08月」）
        let summary = ExpenseSummary(
            monthLabel: Date.now.startOfMonth.monthTitle,
            total: visible.amountSum,
            count: visible.count,
            top: Array(byCategory.prefix(3)),
            updatedAt: .now)

        // 写失败是静默的，所以这里要看返回值；失败时不去 reload，
        // 免得小组件白刷一次、还以为数据更新了
        if SharedSummaryStore.save(summary) {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}

// MARK: - 分类目录注入
//
// 分类的名字/图标/颜色现在住在库里，而列表一屏几十行、统计页每一行都要用它。
// 每处各自 @Query 一遍既啰嗦又容易漏，所以在根部查一次、放进 environment 往下传。
//
// ⚠️ 为什么要单独包一层 modifier：`@Query` 只能写在 `View` 里，
// 而上面那段是 `App.body`（Scene），在那儿写不了。
private struct CategoryCatalogProvider: ViewModifier {
    @Query(sort: [SortDescriptor(\CategoryDef.sortOrder), SortDescriptor(\CategoryDef.createdAt)])
    private var categories: [CategoryDef]

    func body(content: Content) -> some View {
        content.environment(\.categoryCatalog, CategoryCatalog(categories))
    }
}
