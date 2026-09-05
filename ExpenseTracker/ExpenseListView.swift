import SwiftUI
import SwiftData

// MARK: - 明细页（Tab 1）

struct ExpenseListScreen: View {
    @Binding var month: Date
    /// 筛选条件由 RootView 持有（明细页和统计页共用同一份），这里只是读写它
    @Binding var filter: ExpenseFilter
    @State private var showingFilter = false
    @State private var showingExport = false

    #if DEBUG
    @Query private var allTagsRaw: [Tag]
    /// ⚠️ 墓碑过滤统一在这里做（`.alive`），下面所有用到它的地方一行都不用改。
    /// 之所以不在 `@Query` 的 `#Predicate` 里滤：这个项目记着「谓词里的布尔取反编译能过、
    /// 运行时可能抛『不支持的谓词』把界面打崩」，所以一律在内存里滤。
    private var allTags: [Tag] { allTagsRaw.alive }
    @Environment(\.categoryCatalog) private var catalog
    @State private var appliedDebugFilter = false
    #endif

    @State private var showingSync = false
    @State private var syncEngine = SyncEngine.shared

    /// 同步按钮的图标。状态直接画在主界面上，不用进二级页面才知道坏了
    private var syncIcon: String {
        if case .failed = syncEngine.state { return "arrow.trianglehead.2.clockwise.rotate.90.circle" }
        if !SyncConfig.isConfigured { return "icloud.slash" }
        if !SyncConfig.lastError.isEmpty { return "exclamationmark.icloud" }
        return "checkmark.icloud"
    }

    private var filterButton: some View {
        Button {
            showingFilter = true
        } label: {
            // 筛选中时用实心图标：不然切回这一页看到数字变小、
            // 又想不起来是不是还筛着，会以为账丢了
            Image(systemName: filter.isEmpty
                  ? "line.3.horizontal.decrease.circle"
                  : "line.3.horizontal.decrease.circle.fill")
        }
    }

    var body: some View {
        NavigationStack {
            ExpenseList(month: $month, filter: $filter)
                .navigationTitle("记账本")
                .toolbar {
                    // ⚠️ 筛选和分享都放右边是**故意的**：iOS 26 起，同一 placement 里相邻的
                    // 工具栏按钮会自动合成一个玻璃胶囊（Apple 日历右上角那组就是这么来的）。
                    // 把筛选挪回左边，这一组就散成两个孤立圆钮了。要拆开才用 ToolbarSpacer。
                    ToolbarItem(placement: .topBarTrailing) { filterButton }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showingExport = true
                        } label: {
                            Image(systemName: "square.and.arrow.up")
                        }
                    }
                    // 同步：跟筛选、分享合成同一个玻璃胶囊（放同一侧就会自动成组）。
                    // ⚠️ 图标带状态：没配服务器时是空心云、同步失败时是带斜杠的云 ——
                    // 同步这种「在后台默默跑」的东西，出问题必须在主界面上看得见，
                    // 否则两台手机数据不一样、而界面上一切正常
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showingSync = true
                        } label: {
                            Image(systemName: syncIcon)
                        }
                    }
                }
                .sheet(isPresented: $showingExport) {
                    ExportSheet(month: $month)
                }
                .sheet(isPresented: $showingFilter) {
                    FilterSheet(filter: $filter, month: month)
                }
                .sheet(isPresented: $showingSync) {
                    SyncSettingsView()
                }
                .onAppear { applyDebugLaunchOptions() }
        }
    }

    /// 处理开发期启动参数（清单见 DevFlags）。正常使用走不到这里。
    private func applyDebugLaunchOptions() {
        #if DEBUG
        guard !appliedDebugFilter else { return }
        appliedDebugFilter = true

        if let names = DevFlags.value("-filterTags") {
            let wanted = Set(names.split(separator: ",").map { Tag.comparisonKey(String($0)) })
            filter.tagIDs = Set(
                allTags.filter { wanted.contains($0.comparisonKey) }.map(\.persistentModelID)
            )
        }
        if let names = DevFlags.value("-filterCats") {
            // 参数里写的是分类**名字**（好记），要翻成代号
            let wanted = Set(names.split(separator: ",").map(String.init))
            filter.categoryKeys = Set(catalog.all.filter { wanted.contains($0.name) }.map(\.key))
        }
        switch DevFlags.value("-openSheet") {
        // form / tags 由 RootView 的悬浮按钮那套弹层负责，见 DevFlags 注释
        case "filter": showingFilter = true
        case "export": showingExport = true
        default: break
        }
        #endif
    }
}

/// 列表本体。单独拆一层是因为 @Query 的过滤条件依赖所选月份，
/// 需要在 init 里按月份重建查询（SwiftUI 动态查询的标准写法）。
private struct ExpenseList: View {
    @Binding var month: Date
    @Binding var filter: ExpenseFilter
    @Environment(\.modelContext) private var context
    @Environment(PrivacyGate.self) private var gate
    @Query private var expenses: [Expense]
    @State private var editing: Expense?
    #if DEBUG
    /// -openSheet edit 的一次性门闩。⚠️ 不能拿 `editing == nil` 当条件：
    /// 关掉弹层就会把它置回 nil，切一次 tab 回来 onAppear 又满足条件、弹层自己弹回来，
    /// 结果是开着这个参数就没法看列表本身。父视图的 appliedDebugFilter 就是这个写法。
    @State private var appliedDebugEdit = false
    #endif

    init(month: Binding<Date>, filter: Binding<ExpenseFilter>) {
        _month = month
        _filter = filter
        let start = month.wrappedValue.startOfMonth
        let end = start.addingMonths(1)
        _expenses = Query(
            filter: #Predicate<Expense> { $0.date >= start && $0.date < end },
            sort: [SortDescriptor(\Expense.date, order: .reverse),
                   SortDescriptor(\Expense.createdAt, order: .reverse)]
        )
    }

    /// 当月真正显示出来的记录：先过私密门，再按标签筛选。
    ///
    /// 顶部的本月合计、笔数、每天的小计全都从这里派生，所以**私密门只要在这一处过一次**，
    /// 所有数字就自动跟着一致 —— 不会出现「行藏起来了但合计还算着」那种露馅。
    ///
    /// 一笔记录在这里最多出现一次，所以「多个标签命中同一笔只算一次钱」也天然成立。
    private var visible: [Expense] {
        expenses.visible(unlocked: gate.isUnlocked).matching(filter)
    }

    private var isFiltering: Bool { !filter.isEmpty }

    /// 顶部横幅要显示标签名，而名字只能从当前这批记录的关系里拿到
    private var filterForDisplay: ExpenseFilter {
        var f = filter
        var names: [String] = []
        var seen = Set<PersistentIdentifier>()
        for e in expenses {
            for t in e.tags where filter.tagIDs.contains(t.persistentModelID) {
                if seen.insert(t.persistentModelID).inserted { names.append(t.name) }
            }
        }
        f.tagNames = names.sorted()
        return f
    }

    private var monthTotal: Decimal { visible.amountSum }

    /// 按天分组。实现在 Models.swift 的 groupedByDay()，导出长图用的是同一份
    private var days: [(date: Date, items: [Expense], total: Decimal)] {
        visible.groupedByDay()
    }

    var body: some View {
        ScrollViewReader { proxy in
        List {
            Section {
                summaryCard
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
            if visible.isEmpty {
                Section {
                    ContentUnavailableView(
                        isFiltering ? "没有符合筛选条件的记录" : "这个月还没有记录",
                        systemImage: isFiltering ? "line.3.horizontal.decrease.circle" : "tray",
                        description: Text(isFiltering
                                          ? "换个条件，或者点上面的「清除筛选」"
                                          : "点右上角 + 记下第一笔")
                    )
                    .listRowBackground(Color.clear)
                }
            }
            ForEach(days, id: \.date) { day in
                Section {
                    ForEach(day.items) { expense in
                        ExpenseRow(expense: expense)
                            .id(expense.persistentModelID)
                            .contentShape(Rectangle())
                            .onTapGesture { editing = expense }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    // ⚠️ 置墓碑，不是删行。真删的话另一台设备下次同步会把它送回来
                                    expense.markDeleted()
                                    try? context.save()
                                    SyncEngine.shared.syncSoon(context.container)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                                .tint(.red) // App 级 .tint(.blue) 会盖掉 destructive 的红，这里显式覆盖
                            }
                    }
                } header: {
                    HStack {
                        Text(day.date.dayTitle)
                        Spacer()
                        Text(day.total.yuan).monospacedDigit()
                    }
                }
            }
        }
        // 底部给悬浮的「记一笔」按钮让出位置：不留的话滚到最后一行会一直压在按钮下面
        #if DEBUG
        // -scrollBottom：启动后自动滚到最后一行。
        // 这台机器点不了屏幕，「滚到底会不会被悬浮按钮压住」只能靠它截图核。
        // ⚠️ 它是**故意**用程序化滚动的：程序化滚动会绕过 contentMargins，
        //    所以这条路径同时也在盯着「别把 safeAreaInset 改回 contentMargins」。
        .onAppear {
            guard DevFlags.value("-scrollBottom") != nil, let last = visible.last else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                withAnimation { proxy.scrollTo(last.persistentModelID, anchor: .bottom) }
            }
        }
        #endif
        .sheet(item: $editing) { expense in
            ExpenseFormView(expense: expense)
        }
        }   // ScrollViewReader
        #if DEBUG
        // -openSheet edit：直接把最上面那一笔摆进编辑弹层。
        // 这台机器上没有模拟器窗口可以发点击，编辑态（以及它独有的「创建时间」那一行）
        // 只能靠启动参数摆出来看，见 DevFlags。只弹一次，见 appliedDebugEdit 的注释。
        .onAppear {
            guard DevFlags.value("-openSheet") == "edit", !appliedDebugEdit else { return }
            appliedDebugEdit = true
            editing = visible.first
        }
        #endif
    }

    /// 素净卡片的前景色：内容用主文字色、说明用次级色，颜色只留给需要强调的那一个元素。
    /// 这是对着 iOS 27 上 Apple 日历比出来的做法（理由见 ExpenseTrackerApp.swift 顶部 ②）
    private var cardFG: Color { .primary }
    private var cardFGSecondary: Color { .secondary }

    private var summaryCard: some View {
        VStack(spacing: 10) {
            MonthSwitcher(month: $month, foreground: cardFG.opacity(0.92)) {
                // 锁着 → 走 Face ID；已经开着 → 直接关上（省得去找退出按钮）
                if gate.isUnlocked { gate.lock() } else { Task { await gate.unlock() } }
            }
            Text(monthTotal.yuan)
                .font(.system(size: 36, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(cardFG)
            if isFiltering {
                VStack(spacing: 4) {
                    Text("已筛选：\(filterForDisplay.summary) · 共 \(visible.count) 笔")
                        .font(.caption)
                        .foregroundStyle(cardFGSecondary)
                        .multilineTextAlignment(.center)
                    if !filter.tagIDs.isEmpty {
                        Text("一笔被多个标签同时命中只算一次")
                            .font(.caption2)
                            .foregroundStyle(cardFGSecondary.opacity(0.8))
                    }
                    Button {
                        filter.clear()
                    } label: {
                        Text("清除筛选")
                            .font(.caption.weight(.semibold))
                            // ⚠️ 素净版上不能还用「白字 + 白色半透明底」——那是为蓝底设计的，
                            // 放到灰白背景上等于隐形。这里换成主题色文字 + 淡色底。
                            .foregroundStyle(Color.accentColor)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 5)
                            .background(Color.accentColor.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 2)
                }
            } else if gate.isUnlocked {
                // 解锁态一定要有明显标记：不然你自己忘了开着、随手把手机递出去就露了。
                // 这是整个功能里唯一一处「故意显眼」的 UI。
                VStack(spacing: 4) {
                    Text("本月支出 · 共 \(visible.count) 笔（含私密）")
                        .font(.caption)
                        .foregroundStyle(cardFGSecondary)
                    Button {
                        gate.lock()
                    } label: {
                        Label("私密模式 · 点此退出", systemImage: "lock.open.fill")
                            .font(.caption.weight(.semibold))
                            // 解锁态这颗是整个功能里唯一「故意显眼」的 UI，
                            // 素净版上更要用醒目色，不能跟着变淡
                            // 解锁态这颗是整个功能里唯一「故意显眼」的 UI，用橙色不跟着变淡
                            .foregroundStyle(Color.orange)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 5)
                            .background(Color.orange.opacity(0.15), in: Capsule())
                    }
                    .buttonStyle(.plain) // 不加会被 App 级 .tint(.blue) 染色，这坑已经踩过三次
                    .padding(.top, 2)
                }
            } else {
                Text("本月支出 · 共 \(visible.count) 笔")
                    .font(.caption)
                    .foregroundStyle(cardFGSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        // 跟系统分组列表同一层背景色，卡片「退到后面去」，让金额本身成为视觉重点
        // —— Apple 那种「内容优先」的做法
        .background(Color(.secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// 明细页的一行。**导出长图复用的就是它** —— 所以不能是 private，
/// 也别为了长图另写一套：分成两套，图和界面迟早对不上
struct ExpenseRow: View {
    @Environment(\.categoryCatalog) private var catalog
    let expense: Expense

    /// 这笔是不是「后来补记的」：记账时间和创建时间不在同一天。
    ///
    /// ⚠️ 判据用「同不同天」而不是「差几分钟」：当场记的账两个时间只差几秒，
    /// 把两个都显示出来纯属噪音（原来每行都写「记账 13:59:38 · 创建 08-20 13:59:45」）；
    /// 而补记的账（昨晚看的电影今天上午才想起来记）差的是天 —— 这时「后来补的」
    /// 这个信息才有价值，也正是 createdAt 存在的理由（见 README「两个时间」一节）。
    private var isBackfilled: Bool {
        !Calendar.current.isDate(expense.createdAt, inSameDayAs: expense.date)
    }

    /// 创建时间的形式。同年只写月日（`08-19 09:40:12`），**跨年才补上年份**。
    ///
    /// 为什么要分情况：列表上唯一的年份锚点是顶部那张月份卡片。12 月 31 日花的钱
    /// 元旦凌晨才补记时，只写 `01-01` 会被读成同一年的 1 月 1 日 —— 看着像
    /// 「钱还没花就先建了记录」，把 createdAt 的意义整个读反。
    private var createdText: String {
        let cal = Calendar.current
        let sameYear = cal.component(.year, from: expense.createdAt)
            == cal.component(.year, from: expense.date)
        return sameYear ? expense.createdAt.stampTitle : expense.createdAt.fullStampTitle
    }

    /// 次行的时间文案：当场记的只显示时刻，补记的才带出创建时间。
    /// 比原来两个时间戳更直白 —— 原来得自己对比两串数字才看出是不是补记的
    private var timeText: String {
        isBackfilled ? "\(expense.date.timeTitle) · 补记于 \(createdText)"
                     : expense.date.timeTitle
    }

    /// 有备注时主标题显示的是备注，分类名就得在次行补出来；
    /// 没备注时主标题本身就是分类名，再补一遍是重复
    private var categoryHint: String? {
        expense.note.isEmpty ? nil : catalog.name(forKey: expense.categoryRaw)
    }

    /// 次要信息统一排版：小一号字、次级色、等宽数字、绝不折行。
    ///
    /// ⚠️ 用 .secondary 不用 .tertiary：实测 .tertiary 对白底只有 1.84:1，
    /// 比分类名（4.00:1）淡一倍还多，屏幕调暗就读不出秒数了。
    /// 层级靠字号（caption2 比 caption 小）拉开，不靠把它涂淡。
    ///
    /// ⚠️ lineLimit(1) + minimumScaleFactor 不能省：2026-08-19 实测，系统「文字大小」
    /// 拉到最大档（xxxLarge，普通设置、不是辅助功能档）时会折行。
    /// 时间是要被读的信息，宁可缩小也不要断成两截。
    private func secondary(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .foregroundStyle(.secondary)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.75)
    }

    /// 两行制：第一行「这是什么」（分类/备注 + 标签色块），第二行「什么时候」。
    /// 定稿理由见 ExpenseTrackerApp.swift 顶部 ③
    var body: some View {
        HStack(spacing: 12) {
            CategoryIcon(catalog.def(for: expense))
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    if expense.isPrivate {
                        Image(systemName: "lock.fill")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Text(expense.title(categoryName: catalog.name(forKey: expense.categoryRaw)))
                        .lineLimit(1)
                    if !expense.tags.isEmpty { TagChipRow(tags: expense.tags, limit: 2) }
                }
                HStack(spacing: 6) {
                    if let c = categoryHint {
                        secondary(c)
                        secondary("·")
                    }
                    secondary(timeText)
                }
            }
            Spacer()
            Text(expense.amount.yuan)
                .font(.body.weight(.semibold))
                .monospacedDigit()
        }
        .padding(.vertical, 4)
    }
}

