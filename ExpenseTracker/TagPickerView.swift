import SwiftUI
import SwiftData

/// 标签多选器。两处复用：记一笔时给记录打标签、明细页按标签筛选。
struct TagPickerView: View {
    @Binding var selection: Set<PersistentIdentifier>
    var title: String = "标签"
    /// 是否允许新建 / 改名 / 删除。筛选场景下关掉，避免筛着筛着把数据改了
    var allowsEditing: Bool = true

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    /// ⚠️ 下面那两处「这个标签用在几笔」必须过它，见 visibleUses 的注释。
    /// sheet 会继承呈现方的 environment（`FilterSheet` 同样这么拿的），所以这里读得到。
    @Environment(PrivacyGate.self) private var gate

    // 故意不在 @Query 里写 #Predicate 过滤 isArchived：
    // SwiftData 对布尔取反这类谓词的支持不稳，编译能过但运行时可能直接抛「不支持的谓词」
    // 把整个弹层打崩。标签总量就几十个，取出来在内存里滤掉更稳，也更快。
    @Query(sort: [SortDescriptor(\Tag.sortOrder), SortDescriptor(\Tag.createdAt)])
    private var allTags: [Tag]

    /// ⚠️ `.alive` 摘掉删除墓碑，整个文件都从这里取标签，所以只用滤这一处
    private var tags: [Tag] { allTags.alive.filter { !$0.isArchived } }

    @State private var search = ""
    @State private var renaming: Tag?
    @State private var renameText = ""
    @State private var showingRename = false
    @State private var pendingDelete: Tag?
    @State private var showingDelete = false
    @State private var alertMessage: String?

    var body: some View {
        NavigationStack {
            List {
                // 搜索框里输入了一个还不存在的名字 → 直接给个新建入口
                if let name = pendingNewName {
                    Section {
                        Button {
                            create(named: name)
                        } label: {
                            Label("新建标签「\(name)」", systemImage: "plus.circle.fill")
                        }
                    }
                }

                if tags.isEmpty {
                    Section {
                        ContentUnavailableView {
                            Label("还没有标签", systemImage: "tag")
                        } description: {
                            Text("在上面搜索框里直接输入名字就能新建，比如「出差」「可报销」「请客」。")
                        }
                    }
                } else {
                    Section {
                        ForEach(visibleTags) { tag in
                            row(for: tag)
                        }
                    } footer: {
                        Text(allowsEditing
                             ? "一笔可以打多个标签。左滑某个标签可以改名或删除。"
                             : "选中多个标签时，同一笔记录只算一次钱。")
                    }
                }
            }
            // 筛选模式下不能新建标签，提示语不要误导
            .searchable(text: $search, prompt: allowsEditing ? "搜索，或输入新标签名" : "搜索标签")
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("清空") { selection.removeAll() }
                        .disabled(selection.isEmpty)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
            .alert("重命名标签", isPresented: $showingRename) {
                TextField("标签名", text: $renameText)
                Button("保存") { commitRename() }
                Button("取消", role: .cancel) { renaming = nil }
            }
            .confirmationDialog(
                "删除标签「\(pendingDelete?.name ?? "")」？",
                isPresented: $showingDelete,
                titleVisibility: .visible
            ) {
                Button("删除", role: .destructive) { confirmDelete() }
                Button("取消", role: .cancel) { pendingDelete = nil }
            } message: {
                // ⚠️ `tag.expenses` 是 SwiftData 的关系、不是 @Query，所以不经过 visible()，
                // 必须自己过 `.alive` —— 不然删掉的账会被数进"这个标签用在几笔上"
                Text("这个标签用在 \(pendingDelete.map(visibleUses(of:)) ?? 0) 笔记录上。删掉后那些记录会失去这个标签，金额和其它内容不受影响。")
            }
            .alert(alertMessage ?? "", isPresented: Binding(
                get: { alertMessage != nil },
                set: { if !$0 { alertMessage = nil } }
            )) {
                Button("好") {}
            }
        }
    }

    private func row(for tag: Tag) -> some View {
        let isOn = selection.contains(tag.persistentModelID)
        return Button {
            toggle(tag)
        } label: {
            HStack(spacing: 10) {
                Circle()
                    .fill(tag.color)
                    .frame(width: 10, height: 10)
                Text(tag.name)
                    .foregroundStyle(.primary)
                Spacer()
                // 这个标签一共用在多少笔记录上，顺手给个手感
                Text("\(visibleUses(of: tag))")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .monospacedDigit()
                if isOn {
                    Image(systemName: "checkmark")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
        // 不加 .plain 的话整行（标签名、次数）会被按钮样式染成主题蓝，看着像链接
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            if allowsEditing {
                Button(role: .destructive) {
                    pendingDelete = tag
                    showingDelete = true
                } label: {
                    Label("删除", systemImage: "trash")
                }
                .tint(.red) // App 级 .tint(.blue) 会盖掉 destructive 的红，这里显式覆盖

                Button {
                    renaming = tag
                    renameText = tag.name
                    showingRename = true
                } label: {
                    Label("改名", systemImage: "pencil")
                }
                .tint(.orange)
            }
        }
    }

    // MARK: - 数据

    /// 这个标签用在多少笔**看得见的**记录上。
    ///
    /// ⚠️⚠️ **必须过私密门**（2026-09-05 补的，之前这里写的是 `tag.expenses.alive.count`，
    /// 只摘墓碑、不管私密）。不过的话，锁着的时候这里显示「咖啡 15」，而按这个标签
    /// 筛出来只有 12 笔 —— **那个对不上的数就是最容易露馅的地方**，别人一比就知道
    /// 藏了东西、还知道藏了几笔。这是本项目的第一条红线（见 PrivacyGate.swift 顶部）。
    /// 安卓端同一处（`TagsViewModel.usage`）也是这么算的，两端一致。
    ///
    /// ⚠️ `tag.expenses` 是 SwiftData 的**关系**、不是 `@Query`，所以不经过任何全局过滤，
    /// 墓碑和私密都得自己滤 —— `visible(unlocked:)` 两件事一起做了。
    ///
    /// 📌 删除确认框里也用这个数。锁着时它会少报（某个标签只挂在私密记录上时会显示 0），
    /// 这是**刻意接受的**：删标签只是让那些账目少一个标签，金额和其它字段一个都不动，
    /// 少报不会造成不可挽回的后果。分类那边不一样（分类是必填的，删错会让账目悬空），
    /// 所以那边「能不能删」按全部记录判、只有显示的数才过门。
    private func visibleUses(of tag: Tag) -> Int {
        tag.expenses.visible(unlocked: gate.isUnlocked).count
    }

    private var visibleTags: [Tag] {
        let key = Tag.comparisonKey(search)
        guard !key.isEmpty else { return tags }
        return tags.filter { $0.comparisonKey.contains(key) }
    }

    /// 搜索词是个尚不存在的标签名时返回它，否则 nil（用来决定要不要显示「新建」）
    private var pendingNewName: String? {
        guard allowsEditing else { return nil }
        let cleaned = Tag.cleanedName(search)
        guard !cleaned.isEmpty else { return nil }
        let key = Tag.comparisonKey(cleaned)
        guard !tags.contains(where: { $0.comparisonKey == key }) else { return nil }
        return cleaned
    }

    private func toggle(_ tag: Tag) {
        let id = tag.persistentModelID
        if selection.contains(id) {
            selection.remove(id)
        } else {
            selection.insert(id)
        }
    }

    private func create(named name: String) {
        let tag = Tag(
            name: name,
            colorIndex: TagPalette.nextIndex(existingCount: tags.count),
            sortOrder: (tags.map(\.sortOrder).max() ?? 0) + 1
        )
        context.insert(tag)
        // ⚠️ 必须先 save 再取 persistentModelID：没落盘之前拿到的是临时 ID，
        // 落盘后会被换成永久 ID，早取到的那个就成了对不上的野 ID。
        try? context.save()
        SyncEngine.shared.syncSoon(context.container)
        selection.insert(tag.persistentModelID)
        search = ""
    }

    private func commitRename() {
        guard let tag = renaming else { return }
        defer { renaming = nil }
        let cleaned = Tag.cleanedName(renameText)
        guard !cleaned.isEmpty else { return }
        let key = Tag.comparisonKey(cleaned)
        if tags.contains(where: { $0.comparisonKey == key && $0.persistentModelID != tag.persistentModelID }) {
            alertMessage = "已经有一个叫「\(cleaned)」的标签了。"
            return
        }
        tag.name = cleaned
        tag.touch()   // ⚠️ 漏了这一句，改名就同步不出去
        try? context.save()
        SyncEngine.shared.syncSoon(context.container)
    }

    private func confirmDelete() {
        guard let tag = pendingDelete else { return }
        selection.remove(tag.persistentModelID)
        // ⚠️ 置墓碑，不是删行。
        // 注意：SwiftData 删对象时会自动解除关系，置墓碑不会 —— 所以要自己把关系断开，
        // 否则被删标签仍然挂在那些账目上（界面靠 alive 过滤看不见，但导出和统计会数进去）
        for e in tag.expenses { e.tags.removeAll { $0.persistentModelID == tag.persistentModelID }; e.touch() }
        tag.markDeleted()
        try? context.save()
        SyncEngine.shared.syncSoon(context.container)
        pendingDelete = nil
    }
}
