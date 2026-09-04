import SwiftUI
import SwiftData

/// 筛选面板：**分类和标签在同一个面板里选**。
///
/// 这是刻意的：筛选只有一套（`ExpenseFilter`），分类和标签是它的两个维度。
/// 如果把分类筛选放统计页、标签筛选放这里，用户就得记「哪个维度在哪儿点」，
/// 而且迟早会出现两种筛选态互相打架。统计页点某一行只是**往同一套条件里填值**，
/// 不是第二套机制。
///
/// 口径说明（分类内并集 / 标签内并集 / 两者之间交集）见 `ExpenseFilter` 的注释。
struct FilterSheet: View {
    @Binding var filter: ExpenseFilter
    let month: Date
    @Environment(\.dismiss) private var dismiss
    @Environment(PrivacyGate.self) private var gate
    /// 当月记录。⚠️ 用之前必须先过 `visible(unlocked:)` —— 见下面 counts
    @Query private var monthExpenses: [Expense]
    @Query(sort: [SortDescriptor(\CategoryDef.sortOrder), SortDescriptor(\CategoryDef.createdAt)])
    private var allCategoriesRaw: [CategoryDef]
    /// ⚠️ 墓碑过滤统一在这里做（`.alive`），下面所有用到它的地方一行都不用改。
    /// 之所以不在 `@Query` 的 `#Predicate` 里滤：这个项目记着「谓词里的布尔取反编译能过、
    /// 运行时可能抛『不支持的谓词』把界面打崩」，所以一律在内存里滤。
    private var allCategories: [CategoryDef] { allCategoriesRaw.alive }

    /// 编辑中的副本：点「完成」才写回去。中途反悔直接关掉，外面的列表不会跟着乱跳
    @State private var draft: ExpenseFilter

    init(filter: Binding<ExpenseFilter>, month: Date) {
        _filter = filter
        self.month = month
        _draft = State(initialValue: filter.wrappedValue)
        let start = month.startOfMonth
        let end = start.addingMonths(1)
        _monthExpenses = Query(filter: #Predicate<Expense> { $0.date >= start && $0.date < end })
    }

    /// ⚠️ 私密门必须在这里过一次：不过的话，锁着的时候面板上的笔数会把私密记录算进去，
    /// 跟列表对不上 —— 那个对不上的数就是最容易露馅的地方（见 PrivacyGate 注释）。
    private var counts: FilterCounts {
        FilterCounts(visibleExpenses: monthExpenses.visible(unlocked: gate.isUnlocked))
    }

    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 8)]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                        ForEach(allCategories) { cat in
                            CategoryChip(
                                category: cat,
                                count: counts.category[cat.key] ?? 0,
                                selected: draft.categoryKeys.contains(cat.key)
                            ) {
                                draft.toggle(categoryKey: cat.key)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("分类")
                } footer: {
                    Text("选多个 = 这几类都要看")
                }

                TagFilterSection(selection: $draft.tagIDs, counts: counts.tag)

                if !draft.isEmpty {
                    Section {
                        Button("清除全部条件", role: .destructive) { draft.clear() }
                    } footer: {
                        Text(draft.categoryKeys.isEmpty || draft.tagIDs.isEmpty
                             ? "共 \(matchCount) 笔"
                             : "分类和标签同时选 = 两个都要满足 · 共 \(matchCount) 笔")
                    }
                }
            }
            .navigationTitle("筛选")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { filter = draft; dismiss() }
                }
            }
        }
    }

    /// 当前草稿能筛出多少笔 —— 让人在点「完成」之前就知道结果，不用来回试
    private var matchCount: Int { counts.match(draft) }
}

/// 面板里那些「几笔」的预统计：一次遍历同时算出每个分类和每个标签各有多少笔。
/// 分开各算一遍要扫 N 次，这里扫 1 次。
struct FilterCounts {
    /// 键是分类**代号**（`Expense.categoryRaw`）
    var category: [String: Int] = [:]
    var tag: [PersistentIdentifier: Int] = [:]
    /// 原始记录，用来算「当前这组条件一共命中几笔」
    var expenses: [Expense] = []

    /// 用的是和列表页完全同一个 `matching(_:)`，所以面板上预告的笔数
    /// 跟点完成之后真正看到的笔数一定一致 —— 不会出现预告 5 笔、进去只有 3 笔
    func match(_ f: ExpenseFilter) -> Int { expenses.matching(f).count }

    /// ⚠️ 传进来的 expenses 必须**已经过了私密门**，这里不再过一次。
    /// 过两次不会错，但少过一次就会把私密记录的笔数漏出来。
    init(visibleExpenses: [Expense] = []) {
        expenses = visibleExpenses
        for e in visibleExpenses {
            category[e.categoryRaw, default: 0] += 1
            for t in e.tags { tag[t.persistentModelID, default: 0] += 1 }
        }
    }
}

// MARK: - 分类胶囊

private struct CategoryChip: View {
    let category: CategoryDef
    let count: Int
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: category.iconName)
                    .font(.caption)
                    .foregroundStyle(selected ? .white : category.color)
                Text(category.name)
                    .font(.subheadline)
                    .foregroundStyle(selected ? .white : .primary)
                Text("\(count)")
                    .font(.caption2)
                    .monospacedDigit()
                    // 用 .secondary 不用 .tertiary：三级灰对白底只有 1.84:1，
                    // 小字可读下限是 4.5:1（这个项目量过，见 README）。层级靠字号拉开就够了
                    .foregroundStyle(selected ? AnyShapeStyle(.white.opacity(0.8))
                                              : AnyShapeStyle(.secondary))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                selected ? AnyShapeStyle(category.color) : AnyShapeStyle(Color(.tertiarySystemFill)),
                in: Capsule()
            )
            .contentShape(Capsule())
        }
        // ⚠️ .plain：不加的话 App 级 .tint(.blue) 会把没选中的胶囊文字全染蓝，
        // 看起来像十个链接。这个坑在这个项目里已经踩过三次。
        .buttonStyle(.plain)
        // 一笔都没有的分类照样列出来（位置稳定、好找），但淡一点表示点了是空的
        .opacity(count == 0 && !selected ? 0.45 : 1)
    }
}

// MARK: - 标签那一段

private struct TagFilterSection: View {
    @Binding var selection: Set<PersistentIdentifier>
    let counts: [PersistentIdentifier: Int]
    @Query(sort: [SortDescriptor(\Tag.sortOrder), SortDescriptor(\Tag.name)])
    private var tagsRaw: [Tag]
    /// ⚠️ 墓碑过滤统一在这里做（`.alive`），下面所有用到它的地方一行都不用改。
    /// 之所以不在 `@Query` 的 `#Predicate` 里滤：这个项目记着「谓词里的布尔取反编译能过、
    /// 运行时可能抛『不支持的谓词』把界面打崩」，所以一律在内存里滤。
    private var tags: [Tag] { tagsRaw.alive }

    var body: some View {
        Section {
            if tags.isEmpty {
                Text("还没有标签。记一笔的时候可以现建。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(tags) { tag in
                Button {
                    let id = tag.persistentModelID
                    if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
                } label: {
                    HStack(spacing: 10) {
                        Circle().fill(tag.color).frame(width: 10, height: 10)
                        Text(tag.name)
                        Text("\(counts[tag.persistentModelID] ?? 0)")
                            .font(.caption2)
                            .monospacedDigit()
                            .foregroundStyle(.tertiary)
                        Spacer()
                        if selection.contains(tag.persistentModelID) {
                            Image(systemName: "checkmark")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.blue)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)   // 同上：这是「承载内容的行」，不是动作按钮
            }
        } header: {
            Text("标签")
        } footer: {
            Text("选多个 = 命中其中任意一个就算；一笔被多个标签同时命中只算一次钱")
        }
    }
}
