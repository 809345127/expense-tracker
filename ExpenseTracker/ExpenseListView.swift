import SwiftUI
import SwiftData

// MARK: - 明细页（Tab 1）

struct ExpenseListScreen: View {
    @Binding var month: Date
    @State private var showingAdd = false
    @State private var showingFilter = false
    @State private var showingExport = false
    /// 按标签筛选。空集合 = 不筛选
    @State private var filterTagIDs: Set<PersistentIdentifier> = []

    #if DEBUG
    @Query private var allTags: [Tag]
    @State private var appliedDebugFilter = false
    #endif

    var body: some View {
        NavigationStack {
            ExpenseList(month: $month, filterTagIDs: $filterTagIDs)
                .navigationTitle("记账本")
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            showingFilter = true
                        } label: {
                            Image(systemName: filterTagIDs.isEmpty
                                  ? "line.3.horizontal.decrease.circle"
                                  : "line.3.horizontal.decrease.circle.fill")
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showingExport = true
                        } label: {
                            Image(systemName: "square.and.arrow.up")
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showingAdd = true
                        } label: {
                            Image(systemName: "plus")
                        }
                    }
                }
                .sheet(isPresented: $showingAdd) {
                    ExpenseFormView()
                }
                .sheet(isPresented: $showingExport) {
                    ExportSheet(month: $month)
                }
                .sheet(isPresented: $showingFilter) {
                    TagPickerView(selection: $filterTagIDs, title: "按标签筛选", allowsEditing: false)
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
            filterTagIDs = Set(
                allTags.filter { wanted.contains($0.comparisonKey) }.map(\.persistentModelID)
            )
        }
        switch DevFlags.value("-openSheet") {
        case "form", "tags": showingAdd = true
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
    @Binding var filterTagIDs: Set<PersistentIdentifier>
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

    init(month: Binding<Date>, filterTagIDs: Binding<Set<PersistentIdentifier>>) {
        _month = month
        _filterTagIDs = filterTagIDs
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
        expenses.visible(unlocked: gate.isUnlocked).matchingAny(of: filterTagIDs)
    }

    private var isFiltering: Bool { !filterTagIDs.isEmpty }

    private var monthTotal: Decimal { visible.amountSum }

    /// 按天分组。实现在 Models.swift 的 groupedByDay()，导出长图用的是同一份
    private var days: [(date: Date, items: [Expense], total: Decimal)] {
        visible.groupedByDay()
    }

    var body: some View {
        List {
            Section {
                summaryCard
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
            if visible.isEmpty {
                Section {
                    ContentUnavailableView(
                        isFiltering ? "没有符合这些标签的记录" : "这个月还没有记录",
                        systemImage: isFiltering ? "tag.slash" : "tray",
                        description: Text(isFiltering
                                          ? "换几个标签，或者点左上角清除筛选"
                                          : "点右上角 + 记下第一笔")
                    )
                    .listRowBackground(Color.clear)
                }
            }
            ForEach(days, id: \.date) { day in
                Section {
                    ForEach(day.items) { expense in
                        ExpenseRow(expense: expense)
                            .contentShape(Rectangle())
                            .onTapGesture { editing = expense }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    context.delete(expense)
                                    try? context.save()
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
        .sheet(item: $editing) { expense in
            ExpenseFormView(expense: expense)
        }
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

    private var summaryCard: some View {
        VStack(spacing: 10) {
            MonthSwitcher(month: $month, foreground: .white.opacity(0.92)) {
                // 锁着 → 走 Face ID；已经开着 → 直接关上（省得去找退出按钮）
                if gate.isUnlocked { gate.lock() } else { Task { await gate.unlock() } }
            }
            Text(monthTotal.yuan)
                .font(.system(size: 36, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white)
            if isFiltering {
                VStack(spacing: 4) {
                    Text("已按 \(filterTagIDs.count) 个标签筛选 · 共 \(visible.count) 笔")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.78))
                    Text("一笔被多个标签同时命中只算一次")
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.6))
                    Button {
                        filterTagIDs.removeAll()
                    } label: {
                        Text("清除筛选")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 5)
                            .background(.white.opacity(0.22), in: Capsule())
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
                        .foregroundStyle(.white.opacity(0.78))
                    Button {
                        gate.lock()
                    } label: {
                        Label("私密模式 · 点此退出", systemImage: "lock.open.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 5)
                            .background(.white.opacity(0.22), in: Capsule())
                    }
                    .buttonStyle(.plain) // 不加会被 App 级 .tint(.blue) 染色，这坑已经踩过三次
                    .padding(.top, 2)
                }
            } else {
                Text("本月支出 · 共 \(visible.count) 笔")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.78))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .background(Theme.cardGradient, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// 明细页的一行。**导出长图复用的就是它** —— 所以不能是 private，
/// 也别为了长图另写一套：分成两套，图和界面迟早对不上
struct ExpenseRow: View {
    let expense: Expense

    /// 列表行里创建时间的形式。同年只写月日（`08-19 09:40:12`），
    /// **跨年才补上年份**（`2026-01-01 00:14:03`）。
    ///
    /// 为什么要分情况：列表上唯一的年份锚点是顶部那张月份卡片。12 月 31 日花的钱
    /// 元旦凌晨才补记时，只写 `创建 01-01` 会被读成同一年的 1 月 1 日
    /// —— 看着像「钱还没花就先建了记录」，而 createdAt 存在的意义恰恰是让人
    /// 一眼分清当场记的还是后来补的。同年的记录仍旧省掉年份，免得每行都变长。
    private var createdText: String {
        let cal = Calendar.current
        let sameYear = cal.component(.year, from: expense.createdAt)
            == cal.component(.year, from: expense.date)
        return sameYear ? expense.createdAt.stampTitle : expense.createdAt.fullStampTitle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) {
                CategoryIcon(category: expense.category)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 5) {
                        if expense.isPrivate {
                            Image(systemName: "lock.fill")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Text(expense.title)
                            .lineLimit(1)
                    }
                    // 两个都空时整行不渲染，免得白白多出 3pt 的空隙
                    if !expense.note.isEmpty || !expense.tags.isEmpty {
                        HStack(spacing: 6) {
                            if !expense.note.isEmpty {
                                // 主标题已经是备注，副标题补充分类名
                                Text(expense.category.rawValue)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            if !expense.tags.isEmpty {
                                TagChipRow(tags: expense.tags, limit: 2)
                            }
                        }
                    }
                }
                Spacer()
                Text(expense.amount.yuan)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
            }

            // 时间必须单独占一整行。⚠️ 别塞回上面那个 VStack 里跟标题放一起：
            // 那样它会和标题、金额抢宽度，备注长一点的行就被挤到折行
            // （实测过，"创建 08-19" 后面的 "12:19:19" 会掉到第二行）。
            //
            // ⚠️ 单独占一行只解决了默认字号。2026-08-19 实测：系统「文字大小」滑块
            // 拉到最大档（xxxLarge，普通设置、不是辅助功能档）时照样会折行，断点
            // 一模一样。所以还要 lineLimit(1) + minimumScaleFactor 把它定死成一行
            // —— 时间是要被读的信息，宁可缩小也不要断成两截。
            //
            // 记账时间只到时分秒 —— 是哪天，所在分组的标题已经写了。
            // 缩进 52 = 图标 40 + 间距 12，跟上面的标题左对齐；
            // monospacedDigit 让每行的数字上下成列，扫一眼就能比先后。
            //
            // 颜色用 .secondary 不用 .tertiary：实测 .tertiary 对白底只有 1.84:1，
            // 比同行的分类名（4.00:1）淡一倍还多，屏幕调暗就读不出秒数了。
            // 层级靠字号（caption2 比分类名的 caption 小）拉开，不靠把它涂淡。
            Text("记账 \(expense.date.timeTitle) · 创建 \(createdText)")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .padding(.leading, 52)
        }
        .padding(.vertical, 2)
    }
}
