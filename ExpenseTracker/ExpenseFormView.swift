import SwiftUI
import SwiftData

/// 记一笔 / 编辑 共用的表单弹层。expense 传 nil 表示新增。
struct ExpenseFormView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context

    private let editing: Expense?
    @State private var amountText: String
    @State private var category: ExpenseCategory
    @State private var date: Date
    @State private var note: String
    /// 已选标签只存 ID，真正的 Tag 对象从 allTags 里解析
    /// —— 直接把模型对象放进 @State 容易在弹层来回时踩到 SwiftData 的对象生命周期问题
    @State private var selectedTagIDs: Set<PersistentIdentifier>
    @State private var showingTagPicker = false
    @State private var isPrivate: Bool
    @FocusState private var amountFocused: Bool
    @Environment(PrivacyGate.self) private var gate

    @Query(sort: [SortDescriptor(\Tag.sortOrder), SortDescriptor(\Tag.createdAt)])
    private var allTags: [Tag]

    init(expense: Expense? = nil) {
        editing = expense
        // Decimal 的字符串形式就是 "28.5" 这类纯数字，直接可编辑
        _amountText = State(initialValue: expense.map { "\($0.amount)" } ?? "")
        _category = State(initialValue: expense?.category ?? .food)
        _date = State(initialValue: expense?.date ?? .now)
        _note = State(initialValue: expense?.note ?? "")
        _selectedTagIDs = State(initialValue: Set((expense?.tags ?? []).map(\.persistentModelID)))
        // 编辑已有记录 → 沿用它自己的。新建 → 先按 false，onAppear 里再看门开没开
        // （init 里读不到 @Environment，只能等视图上屏）
        _isPrivate = State(initialValue: expense?.isPrivate ?? false)
    }

    /// 当前选中的标签，按标签自身的排序显示
    private var selectedTags: [Tag] {
        allTags.filter { selectedTagIDs.contains($0.persistentModelID) }
    }

    /// 输入合法时返回金额，否则 nil（保存按钮据此禁用）
    ///
    /// 必须严格校验：Decimal(string:) 会把 "12元"、"12。75" 这类脏输入
    /// 截断成 12 而不报错，那样用户以为记了 12.75、实际记成 12，且毫无提示。
    private var amount: Decimal? {
        let t = amountText.trimmingCharacters(in: .whitespaces)
        guard t.range(of: #"^\d{1,9}(\.\d{1,2})?$"#, options: .regularExpression) != nil,
              let d = Decimal(string: t), d > 0 else { return nil }
        return d
    }

    /// 净化金额输入：中文输入法的全角句号转成小数点，去掉数字以外的字符，
    /// 并限制成「最多 9 位整数 + 最多 2 位小数、只有一个小数点」。
    static func sanitizeAmount(_ raw: String) -> String {
        var t = raw
            .replacingOccurrences(of: "。", with: ".")
            .replacingOccurrences(of: "．", with: ".")
            .filter { $0.isASCII && ($0.isNumber || $0 == ".") }

        // 只保留第一个小数点
        if let dot = t.firstIndex(of: ".") {
            let tail = t[t.index(after: dot)...].filter { $0 != "." }
            t = t[..<dot] + "." + tail
        }

        var intPart = t, decPart: String?
        if let dot = t.firstIndex(of: ".") {
            intPart = String(t[..<dot])
            decPart = String(t[t.index(after: dot)...].prefix(2))
        }
        if intPart.isEmpty && decPart != nil { intPart = "0" } // 直接从小数点开始输入时补 0
        intPart = String(intPart.prefix(9))
        return decPart.map { intPart + "." + $0 } ?? intPart
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 8) {
                        Text("¥")
                            .font(.system(size: 28, weight: .semibold, design: .rounded))
                            .foregroundStyle(.secondary)
                        TextField("0.00", text: $amountText)
                            .keyboardType(.decimalPad)
                            .font(.system(size: 34, weight: .bold, design: .rounded))
                            .focused($amountFocused)
                            .onChange(of: amountText) { _, new in
                                let clean = Self.sanitizeAmount(new)
                                if clean != new { amountText = clean }
                            }
                    }
                    .padding(.vertical, 4)
                    // 整行都能唤起键盘：只有数字那一小块可点的话，点右侧空白会像没反应
                    .contentShape(Rectangle())
                    .onTapGesture { amountFocused = true }
                }

                Section("分类") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 5), spacing: 14) {
                        ForEach(ExpenseCategory.allCases) { c in
                            categoryCell(c)
                        }
                    }
                    .padding(.vertical, 6)
                }

                Section {
                    // 带上时分：这一行选的就是列表里显示的「记账时间」。
                    //
                    // ⚠️ iOS 的时间选择器最细只到分钟，秒选不了。新建一笔时，秒是系统在
                    // 你点开这个表单那一刻自动带上的（date 的初值就是 .now）。
                    // ⚠️ 还没实测：手动拨动时间选择器之后，原来的秒是被保留还是被清成 0。
                    // 这台机器上的 Xcode 27 没有模拟器窗口、点不了屏幕（见 README），
                    // 只能在真机上试。两种结果都不影响正确性，纯粹是显示上的细节。
                    DatePicker("记账时间", selection: $date, displayedComponents: [.date, .hourAndMinute])
                    if let editing {
                        // 只读：创建时间是这条记录写进库的那一刻，不给改。
                        // 摆在记账时间下面，一眼能看出这笔是当场记的还是后来补的
                        HStack {
                            Text("创建时间")
                            Spacer()
                            Text(editing.createdAt.fullStampTitle)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                                // 同列表那行：字号拉大后这一行也装不下，
                                // 缩小总比把「创建时间」四个字折成两截好看
                                .lineLimit(1)
                                .minimumScaleFactor(0.7)
                        }
                    }
                    HStack {
                        Text("备注")
                        TextField("可选", text: $note)
                            .multilineTextAlignment(.trailing)
                    }
                    tagRow

                    // ⚠️ 锁着的时候这一行**必须完全不存在**，不是禁用、不是灰掉。
                    // 别人拿你手机点一下右上角的 +，只要看见「私密」两个字，
                    // 「界面上完全无痕」这件事就当场破功了 —— 而且比在设置里放个开关
                    // 暴露得更彻底，因为记一笔是最顺手会被点开的地方。
                    if gate.isUnlocked {
                        Toggle(isOn: $isPrivate) {
                            Label("私密", systemImage: "lock.fill")
                        }
                    }
                }

                if let editing {
                    Section {
                        Button("删除这笔记录", role: .destructive) {
                            context.delete(editing)
                            try? context.save()
                            dismiss()
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            .navigationTitle(editing == nil ? "记一笔" : "编辑")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") { save() }
                        .fontWeight(.semibold)
                        .disabled(amount == nil)
                }
            }
            .onAppear {
                if editing == nil {
                    amountFocused = true
                    // 解锁状态下新建，默认就打上私密 —— 特意解锁进来多半就是为了记这一笔。
                    // 不想私密的话那一行就在眼前，拨回去即可。
                    isPrivate = gate.isUnlocked
                }
                #if DEBUG
                if DevFlags.value("-openSheet") == "tags" { showingTagPicker = true }
                #endif
            }
        }
    }

    /// 「标签」那一行：点开是多选器，选中的直接显示成小胶囊
    private var tagRow: some View {
        Button {
            showingTagPicker = true
        } label: {
            HStack(spacing: 8) {
                Text("标签")
                    .foregroundStyle(.primary)
                Spacer()
                if selectedTags.isEmpty {
                    Text("可选")
                        .foregroundStyle(.secondary)
                } else {
                    TagChipRow(tags: selectedTags, limit: 2, compact: false)
                }
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        // 同 TagPickerView：不加 .plain，整行会被按钮样式染成主题蓝
        .buttonStyle(.plain)
        .sheet(isPresented: $showingTagPicker) {
            TagPickerView(selection: $selectedTagIDs)
        }
    }

    private func categoryCell(_ c: ExpenseCategory) -> some View {
        let selected = category == c
        return Button {
            category = c
        } label: {
            VStack(spacing: 6) {
                Image(systemName: c.icon)
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(selected ? .white : c.color)
                    .frame(width: 44, height: 44)
                    .background(
                        selected ? c.color : c.color.opacity(0.15),
                        in: RoundedRectangle(cornerRadius: 13, style: .continuous)
                    )
                Text(c.rawValue)
                    .font(.caption2)
                    .foregroundStyle(selected ? .primary : .secondary)
            }
        }
        .buttonStyle(.plain)
    }

    private func save() {
        guard let amount else { return }
        let trimmedNote = note.trimmingCharacters(in: .whitespaces)
        let tags = selectedTags
        if let editing {
            editing.amount = amount
            editing.category = category
            editing.date = date
            editing.note = trimmedNote
            editing.tags = tags
            editing.isPrivate = isPrivate
        } else {
            let expense = Expense(amount: amount, category: category, note: trimmedNote,
                                  date: date, isPrivate: isPrivate)
            context.insert(expense)
            // 关系必须在 insert 之后再建，见 Expense.tags 的注释
            expense.tags = tags
        }
        // SwiftData 的自动保存要等主线程空闲，用户从后台划掉 app 时可能来不及；这里立刻落盘
        try? context.save()
        dismiss()
    }
}
