import SwiftUI
import SwiftData

@main
struct ExpenseTrackerApp: App {
    let container: ModelContainer
    /// 私密记录的门。整个 app 共享一个，两个 tab 和表单都从环境里取
    @State private var gate = PrivacyGate()
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
            container = try ModelContainer(for: Expense.self, Tag.self)
        } catch {
            fatalError("初始化本地数据库失败：\(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                RootView()

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
        }
        .modelContainer(container)
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

struct RootView: View {
    enum Tab: String { case list, stats }

    @State private var tab: Tab
    @State private var month: Date // 明细/统计两页共享的「当前查看月份」

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

    var body: some View {
        TabView(selection: $tab) {
            ExpenseListScreen(month: $month)
                .tabItem { Label("明细", systemImage: "list.bullet.rectangle.fill") }
                .tag(Tab.list)
            StatsScreen(month: $month)
                .tabItem { Label("统计", systemImage: "chart.pie.fill") }
                .tag(Tab.stats)
        }
    }
}
