import SwiftUI
import SwiftData

// MARK: - 分类管理
//
// 入口在「记一笔」表单的「分类」那一组标题右边。
//
// ## 删除规则：只能删没被用过的
//
// 这是刻意选的，不是偷懒。分类不像标签 —— 标签删掉只是解除关联，那笔账还在；
// 分类是**必填**的，删掉一个还有账目在用的分类，那些账就指向一个不存在的分类了
// （界面上会变成灰问号，统计里多出一坨认不出的东西）。
//
// 另外两种做法都更糟：
// - **删的时候把那些账改成「其他」**：等于替用户改他的账，而且不可撤销
// - **允许悬空，显示成「已删除」**：那笔账从此没法被正常统计和筛选
//
// 所以规则是「用过就不给删」，并且在界面上**明说还有几笔在用**，
// 而不是把按钮灰掉让人猜。想删就先把那几笔改到别的分类去。
//
// ## ⚠️ 计数必须算上私密记录，但**不能把数字露出来**
//
// 「有没有被用过」这个判断如果只数看得见的记录，会出事：某个分类底下只有私密记录时，
// 锁定态下数出来是 0 笔 → 允许删 → 那几笔私密账当场指向一个不存在的分类。
// 所以判断用**全部记录**。
//
// 但数字不能照实显示 —— 锁定态下写「理发 · 3 笔在用」，等于告诉旁人
// 「这里有 3 笔你看不见的账」，跟「藏记录必须连合计一起藏」那条红线冲突。
// 折中：**锁定态下只显示看得见的笔数**（可能是 0），而删除与否按全部记录判；
// 出现「显示 0 笔却不给删」这种情况时，提示语不写数字，只说「还有记录在用」。
// 这种情形只会发生在你自己有私密账的分类上，你自己知道是怎么回事。

struct CategoryManagerView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @Environment(PrivacyGate.self) private var gate

    @Query(sort: [SortDescriptor(\CategoryDef.sortOrder), SortDescriptor(\CategoryDef.createdAt)])
    private var categoriesRaw: [CategoryDef]
    /// ⚠️ 墓碑过滤统一在这里做（`.alive`），下面所有用到它的地方一行都不用改。
    /// 之所以不在 `@Query` 的 `#Predicate` 里滤：这个项目记着「谓词里的布尔取反编译能过、
    /// 运行时可能抛『不支持的谓词』把界面打崩」，所以一律在内存里滤。
    private var categories: [CategoryDef] { categoriesRaw.alive }

    /// ⚠️ 故意查**全部**记录，不过私密门 —— 只用来数「这个分类有没有被用过」，
    /// 数字本身不直接显示（见文件头的说明）
    @Query private var allExpenses: [Expense]

    @State private var editingTarget: CategoryDef?
    @State private var creating = false
    @State private var blockedMessage: String?

    /// 每个分类被多少笔账用着（含私密）。
    /// ⚠️ 这里**故意不走** `visible(unlocked:)`（删除判定必须按全部记录算，见下面 canDelete），
    /// 所以要自己带上 `.alive` —— 否则被删掉的账（墓碑）会被算成"还在用这个分类"，
    /// 于是一个明明空了的分类永远删不掉，而且提示语说不出是为什么。
    private var totalUsage: [String: Int] {
        allExpenses.alive.reduce(into: [:]) { $0[$1.categoryRaw, default: 0] += 1 }
    }

    /// 看得见的那部分笔数（锁定态下不含私密）。只用来显示
    private var visibleUsage: [String: Int] {
        allExpenses.visible(unlocked: gate.isUnlocked)
            .reduce(into: [:]) { $0[$1.categoryRaw, default: 0] += 1 }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(categories) { c in
                        row(c)
                    }
                    .onMove(perform: move)
                    .onDelete(perform: deleteAt)
                } footer: {
                    Text("长按拖动可以调整顺序，顺序决定记一笔时九宫格的排列。\n左滑删除；已经有账目在用的分类删不掉，先把那些账改到别的分类。")
                }
            }
            .navigationTitle("分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        creating = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            #if DEBUG
            // -dumpDelete：把每个分类的「能不能删 / 显示几笔 / 实际几笔」落盘。
            // 这台机器点不了屏幕，左滑删除没法手动试，而这条逻辑同时压着隐私红线
            // （只被私密记录用着的分类，锁定态下显示 0 笔，但必须删不掉），只能这样验
            .onAppear {
                guard DevFlags.has("-dumpDelete") else { return }
                let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let lines = categories.map { c -> String in
                    let verdict = whyCannotDelete(c).map { "拦住：" + $0 } ?? "可以删"
                    return "\(c.key)\t显示\(visibleUsage[c.key] ?? 0)笔\t实际\(totalUsage[c.key] ?? 0)笔\t\(verdict)"
                }
                let header = "解锁态=\(gate.isUnlocked)\n分类\t显示\t实际\t判定\n"
                try? (header + lines.joined(separator: "\n"))
                    .write(to: docs.appendingPathComponent("delete.txt"), atomically: true, encoding: .utf8)
            }
            #endif
            .sheet(item: $editingTarget) { CategoryEditorView(editing: $0) }
            .sheet(isPresented: $creating) { CategoryEditorView(editing: nil) }
            .alert("删不掉", isPresented: Binding(
                get: { blockedMessage != nil },
                set: { if !$0 { blockedMessage = nil } }
            )) {
                Button("知道了", role: .cancel) {}
            } message: {
                Text(blockedMessage ?? "")
            }
        }
    }

    private func row(_ c: CategoryDef) -> some View {
        // ⚠️ 在 List 里拿 Button 当「承载内容的行」用时必须 .buttonStyle(.plain)，
        // 否则 app 级的 .tint(.blue) 会把整行文字染蓝、看着像链接（这个坑本项目踩过三次）
        Button {
            editingTarget = c
        } label: {
            HStack(spacing: 12) {
                CategoryIcon(c, size: 34)
                VStack(alignment: .leading, spacing: 2) {
                    Text(c.name)
                    Text(usageText(c))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
    }

    private func usageText(_ c: CategoryDef) -> String {
        if c.isFallback { return "兜底分类，不能删" }
        let n = visibleUsage[c.key] ?? 0
        return n == 0 ? "还没用过" : "\(n) 笔在用"
    }

    // MARK: 排序

    private func move(from source: IndexSet, to destination: Int) {
        var reordered = categories
        reordered.move(fromOffsets: source, toOffset: destination)
        // 重排之后整体重新编号。不做「只改动过的那几个」那种小聪明 ——
        // 十来条数据，全量重写最不容易出错
        // ⚠️ 每条都要 touch：排序也是要同步出去的改动（安卓那边的顺序要跟着变）
        for (i, c) in reordered.enumerated() { c.sortOrder = i; c.touch() }
        try? context.save()
        SyncEngine.shared.syncSoon(context.container)
    }

    // MARK: 删除

    private func deleteAt(_ offsets: IndexSet) {
        for i in offsets {
            let c = categories[i]
            if let reason = whyCannotDelete(c) {
                blockedMessage = reason
                continue
            }
            // ⚠️ 置墓碑，不是删行
            c.markDeleted()
        }
        try? context.save()
        SyncEngine.shared.syncSoon(context.container)
    }

    /// 能删就返回 nil，不能删返回给用户看的原因
    private func whyCannotDelete(_ c: CategoryDef) -> String? {
        if c.isFallback {
            return "「\(c.name)」是兜底分类，任何时候都得留一个能落脚的分类，所以它删不掉。"
        }
        let used = totalUsage[c.key] ?? 0
        guard used > 0 else { return nil }
        // 锁定态下如果这个分类只被私密记录用着，不能把笔数说出去（见文件头）
        let shown = visibleUsage[c.key] ?? 0
        if shown == 0 {
            return "「\(c.name)」还有记录在用，删不掉。"
        }
        return "「\(c.name)」下面有 \(shown) 笔账，删不掉。先把这些账改到别的分类，再回来删。"
    }
}

// MARK: - 新建 / 编辑一个分类

struct CategoryEditorView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context

    @Query(sort: \CategoryDef.sortOrder) private var categoriesRaw: [CategoryDef]
    /// ⚠️ 墓碑过滤统一在这里做（`.alive`），下面所有用到它的地方一行都不用改。
    /// 之所以不在 `@Query` 的 `#Predicate` 里滤：这个项目记着「谓词里的布尔取反编译能过、
    /// 运行时可能抛『不支持的谓词』把界面打崩」，所以一律在内存里滤。
    private var categories: [CategoryDef] { categoriesRaw.alive }

    private let editing: CategoryDef?
    @State private var name: String
    @State private var iconName: String
    @State private var colorIndex: Int

    init(editing: CategoryDef?) {
        self.editing = editing
        _name = State(initialValue: editing?.name ?? "")
        _iconName = State(initialValue: editing?.iconName ?? "questionmark.circle.fill")
        _colorIndex = State(initialValue: editing?.colorIndex ?? 0)
    }

    private var cleanedName: String { CategoryDef.cleanedName(name) }

    /// 重名判断：忽略大小写、全角半角（同标签那套）。改自己的名字时要把自己排除掉
    private var duplicate: Bool {
        let key = CategoryDef.comparisonKey(cleanedName)
        guard !key.isEmpty else { return false }
        return categories.contains {
            $0.persistentModelID != editing?.persistentModelID && $0.comparisonKey == key
        }
    }

    private var canSave: Bool { !cleanedName.isEmpty && !duplicate }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 14) {
                        // 实时预览：改名字、换图标、换颜色，这里立刻跟着变
                        CategoryIcon(iconName: iconName,
                                     color: CategoryPalette.color(at: colorIndex),
                                     size: 52)
                        TextField("分类名字", text: $name)
                            .font(.title3)
                    }
                    .padding(.vertical, 4)
                } footer: {
                    if duplicate {
                        Text("已经有一个叫「\(cleanedName)」的分类了")
                            .foregroundStyle(.red)
                    } else if let editing, editing.name != cleanedName, !cleanedName.isEmpty {
                        Text("改名之后，这个分类下已有的 账目会跟着显示新名字 —— 历史数据不用动。")
                    }
                }

                Section("颜色") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 7),
                              spacing: 10) {
                        ForEach(CategoryPalette.colors.indices, id: \.self) { i in
                            colorDot(i)
                        }
                    }
                    .padding(.vertical, 4)
                }

                ForEach(CategoryIconLibrary.groups, id: \.title) { group in
                    Section(group.title) {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6),
                                  spacing: 10) {
                            ForEach(group.icons, id: \.self) { icon in
                                iconCell(icon)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle(editing == nil ? "新建分类" : "编辑分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") { save() }
                        .fontWeight(.semibold)
                        .disabled(!canSave)
                }
            }
        }
    }

    private func colorDot(_ i: Int) -> some View {
        let selected = colorIndex == i
        return Button {
            colorIndex = i
        } label: {
            Circle()
                .fill(CategoryPalette.color(at: i))
                .frame(width: 30, height: 30)
                .overlay {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.white)
                    }
                }
        }
        .buttonStyle(.plain)
    }

    private func iconCell(_ icon: String) -> some View {
        let selected = iconName == icon
        let tint = CategoryPalette.color(at: colorIndex)
        return Button {
            iconName = icon
        } label: {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(selected ? .white : tint)
                .frame(width: 40, height: 40)
                .background(selected ? tint : tint.opacity(0.14),
                            in: RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    private func save() {
        guard canSave else { return }
        if let editing {
            // ⚠️ 只改显示用的三个字段，**绝不动 key** —— 历史账目认的是 key
            editing.name = cleanedName
            editing.iconName = iconName
            editing.colorIndex = colorIndex
            editing.touch()   // ⚠️ 漏了这一句，这次改名就同步不出去
        } else {
            context.insert(CategoryDef(
                key: newKey(),
                name: cleanedName,
                iconName: iconName,
                colorIndex: colorIndex,
                sortOrder: (categories.map(\.sortOrder).max() ?? -1) + 1
            ))
        }
        try? context.save()
        SyncEngine.shared.syncSoon(context.container)
        dismiss()
    }

    /// 新分类的代号取「建的这一刻的名字」。
    ///
    /// ⚠️ 代号必须在全表唯一，而名字查重是**忽略大小写和全角半角**的 ——
    /// 也就是说「Coffee」和「coffee」不能同时存在，但「coffee」和一个已被删掉的
    /// 老「coffee」的代号仍可能撞上（老账目还在用那个代号）。所以撞了就加后缀。
    private func newKey() -> String {
        let taken = Set(categories.map(\.key))
        var candidate = cleanedName
        var n = 2
        while taken.contains(candidate) {
            candidate = "\(cleanedName)-\(n)"
            n += 1
        }
        return candidate
    }
}
