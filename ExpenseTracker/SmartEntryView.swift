import PhotosUI
import SwiftData
import SwiftUI

// MARK: - 智能记账弹层（2026-10-09 加）
//
// 入口：长按右下角「+」→「智能记账」（点一下「+」还是原来的手动记一笔）。
//
// 流程：写一段字 / 拍照 / 选图 / 粘贴图片 → 点「识别」→ 核对列表（逐笔改、勾选）→「保存 N 笔」。
// 识别逻辑和四级降级全在 SmartParse.swift。
//
// ⚠️ 核对列表里的东西只是草稿：点「保存」之前一个字都不进数据库、不会同步。
//    关掉弹层草稿就没了 —— 跟手动记一笔点「取消」是一个意思。

struct SmartEntryView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @Environment(\.categoryCatalog) private var catalog
    @Environment(PrivacyGate.self) private var gate

    private enum Phase { case input, working, review }

    @State private var phase = Phase.input
    @State private var text = ""
    @State private var images: [UIImage] = []
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showingCamera = false
    @State private var showingSettings = false

    @State private var result: SmartResult?
    @State private var drafts: [ParsedDraft] = []
    /// 点了核对列表里的哪一笔（弹出记账表单改它）
    @State private var editingDraft: ParsedDraft?
    /// 第 4 级「什么都没认出来」时的提示，显示在输入页
    @State private var failMessage = ""
    @State private var hasKey = DeepSeekKeyStore.load() != nil
    @FocusState private var textFocused: Bool
    #if DEBUG
    @State private var appliedDebug = false
    #endif

    /// 一次最多几张图。多了上传慢、DeepSeek 也容易超时
    private let maxImages = 5

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .input, .working: inputPage
                case .review: reviewPage
                }
            }
            .navigationTitle("智能记账")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if phase == .review {
                        // 回去改输入，输入原样保留
                        Button("重新输入") { phase = .input }
                    } else {
                        Button("取消") { dismiss() }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("AI 设置")
                }
            }
            .sheet(isPresented: $showingSettings, onDismiss: { hasKey = DeepSeekKeyStore.load() != nil }) {
                SmartSettingsView()
            }
            .sheet(item: $editingDraft) { d in
                ExpenseFormView(draft: d) { edited in
                    if let i = drafts.firstIndex(where: { $0.id == edited.id }) { drafts[i] = edited }
                }
            }
            .fullScreenCover(isPresented: $showingCamera) {
                CameraPicker { img in if images.count < maxImages { images.append(img) } }
                    .ignoresSafeArea()
            }
            .onChange(of: photoItems) { _, items in
                guard !items.isEmpty else { return }
                Task {
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self),
                           let img = UIImage(data: data), images.count < maxImages {
                            images.append(img)
                        }
                    }
                    photoItems = []
                }
            }
            #if DEBUG
            // -smartText "午饭 32 打车 18"：带着这段字打开，省得在模拟器上打中文
            .onAppear {
                guard !appliedDebug else { return }
                appliedDebug = true
                if let t = DevFlags.value("-smartText") { text = t }
                if let p = DevFlags.value("-smartImage"), let img = UIImage(contentsOfFile: p) { images = [img] }
            }
            #endif
        }
    }

    // MARK: 输入页

    private var inputPage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ZStack(alignment: .topLeading) {
                    TextEditor(text: $text)
                        .focused($textFocused)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 140)
                        .accessibilityIdentifier("smartText")
                    if text.isEmpty {
                        Text("写下要记的账，可以一次写好几笔\n比如：午饭 32 打车 18.5 昨天超市 126")
                            .foregroundStyle(.tertiary)
                            .padding(.top, 8).padding(.leading, 5)
                            .allowsHitTesting(false)
                    }
                }
                .padding(10)
                .background(Color(.secondarySystemGroupedBackground),
                            in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                if !images.isEmpty { thumbnails }

                imageButtons

                // 现在会走哪一级，提前说清楚，不让人以为 AI 在工作而其实没有
                Label(hasKey ? "用你的 DeepSeek 识别，不通时自动改用本地规则"
                             : "还没填 DeepSeek key，只能按本地规则拆文字（右上角设置）",
                      systemImage: hasKey ? "sparkles" : "exclamationmark.circle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if !failMessage.isEmpty {
                    Text(failMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) {
            Button(action: recognize) {
                HStack(spacing: 8) {
                    if phase == .working {
                        ProgressView().tint(.white)
                        Text("识别中…")
                    } else {
                        Image(systemName: "sparkles")
                        Text("识别")
                    }
                }
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 14))
            .disabled(phase == .working || (text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && images.isEmpty))
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
        .disabled(phase == .working)
    }

    private var thumbnails: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(images.indices, id: \.self) { i in
                    Image(uiImage: images[i])
                        .resizable()
                        .scaledToFill()
                        .frame(width: 76, height: 76)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(alignment: .topTrailing) {
                            Button {
                                images.remove(at: i)
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, .black.opacity(0.6))
                                    .font(.title3)
                            }
                            .padding(3)
                            .accessibilityLabel("删掉第 \(i + 1) 张图")
                        }
                }
            }
        }
    }

    private var imageButtons: some View {
        HStack(spacing: 10) {
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button { showingCamera = true } label: {
                    Label("拍照", systemImage: "camera").frame(maxWidth: .infinity)
                }
            }
            PhotosPicker(selection: $photoItems, maxSelectionCount: max(1, maxImages - images.count),
                         matching: .images) {
                Label("相册", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity)
            }
            // 只看「剪贴板里有没有图」不会触发系统的「允许粘贴」提示；真去取的时候才会
            if UIPasteboard.general.hasImages {
                Button {
                    if let img = UIPasteboard.general.image, images.count < maxImages { images.append(img) }
                } label: {
                    Label("粘贴图片", systemImage: "doc.on.clipboard").frame(maxWidth: .infinity)
                }
            }
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .disabled(images.count >= maxImages)
    }

    // MARK: 核对页

    private var selectedCount: Int { drafts.filter(\.selected).count }

    private var reviewPage: some View {
        List {
            Section {
                ForEach($drafts) { $d in
                    draftRow($d)
                        // 没把握的标黄：分类是猜的 / 日期没认出来 / 本地规则拆的
                        .listRowBackground(d.uncertain ? Color.yellow.opacity(0.16) : nil)
                }
                .onDelete { drafts.remove(atOffsets: $0) }
            } header: {
                reviewHeader
            } footer: {
                Text("点一行可以改；左滑删掉。标黄的是没把握的，保存前看一眼。")
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button(action: saveAll) {
                Text(selectedCount == 0 ? "没有勾选" : "保存 \(selectedCount) 笔")
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.roundedRectangle(radius: 14))
            .disabled(selectedCount == 0)
            .padding(.horizontal)
            .padding(.bottom, 8)
            .accessibilityIdentifier("smartSave")
        }
    }

    private var reviewHeader: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(result?.level.title ?? "") · 共 \(drafts.count) 笔")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            if let r = result, !r.downgradeReason.isEmpty {
                Text("没用上 AI：\(r.downgradeReason)")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }
        .textCase(nil)
        .padding(.bottom, 4)
    }

    private func draftRow(_ d: Binding<ParsedDraft>) -> some View {
        let v = d.wrappedValue
        let def = catalog.def(forKey: v.categoryKey)
        return HStack(spacing: 12) {
            Button { d.wrappedValue.selected.toggle() } label: {
                Image(systemName: v.selected ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundStyle(v.selected ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(v.selected ? "已勾选" : "未勾选")

            // 行的其余部分点了是改这一笔
            Button { editingDraft = v } label: {
                HStack(spacing: 12) {
                    CategoryIcon(def, size: 36)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(v.note.isEmpty ? catalog.name(forKey: v.categoryKey) : v.note)
                            .lineLimit(1)
                        HStack(spacing: 6) {
                            Text(v.date.shortStamp)
                            if !v.note.isEmpty { Text(catalog.name(forKey: v.categoryKey)) }
                            if v.duplicate {
                                Text("可能已记过").foregroundStyle(.orange)
                            }
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Text(v.isIncome ? v.amount.incomeYuan : v.amount.yuan)
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(v.isIncome ? Color.income : .primary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .opacity(v.selected ? 1 : 0.55)
    }

    // MARK: 动作

    private func recognize() {
        textFocused = false
        failMessage = ""
        phase = .working
        let cats = catalog.all.map { SmartCategory(key: $0.key, name: $0.name) }
        let input = text
        let imgs = images
        Task {
            let r = await SmartRecognizer.run(text: input, images: imgs, categories: cats)
            guard r.level != .nothing else {
                failMessage = "没认出任何一笔"
                    + (r.downgradeReason.isEmpty ? "" : "（\(r.downgradeReason)）")
                    + "。输入还在，改一改再试。"
                phase = .input
                return
            }
            result = r
            drafts = markDuplicates(r.drafts).map {
                var d = $0
                // 跟手动记一笔一致：解锁状态下新建的默认私密
                d.isPrivate = gate.isUnlocked
                return d
            }
            phase = .review
        }
    }

    /// 同一天、同金额、同收支已经有一笔 → 标「可能已记过」并默认不勾。
    /// 典型场景：同一张账单截图导了两次。
    ///
    /// ⚠️ 只跟**当前看得见的**记录比（锁着时不含私密记录）：
    /// 不然锁着的时候也会对一笔私密账提示「可能已记过」，等于说出了那笔私密账的存在
    private func markDuplicates(_ list: [ParsedDraft]) -> [ParsedDraft] {
        let all = ((try? context.fetch(FetchDescriptor<Expense>())) ?? []).visible(unlocked: gate.isUnlocked)
        let cal = Calendar.current
        var seen = Set<String>()
        for e in all { seen.insert("\(cal.startOfDay(for: e.date).timeIntervalSince1970)|\(e.amount)") }
        return list.map { d in
            var d = d
            let signed = d.isIncome ? -d.amount : d.amount
            if seen.contains("\(cal.startOfDay(for: d.date).timeIntervalSince1970)|\(signed)") {
                d.duplicate = true
                d.selected = false
            }
            return d
        }
    }

    private func saveAll() {
        let tags = ((try? context.fetch(FetchDescriptor<Tag>())) ?? []).alive
        for d in drafts where d.selected {
            // ⚠️ 收入存成负数（见 Expense.amount），符号只在这一处加
            let e = Expense(amount: d.isIncome ? -d.amount : d.amount, categoryKey: d.categoryKey,
                            note: d.note, date: d.date, isPrivate: d.isPrivate)
            context.insert(e)
            // 关系必须在 insert 之后再建，见 Expense.tags 的注释
            e.tags = tags.filter { d.tagIDs.contains($0.persistentModelID) }
        }
        try? context.save()
        SyncEngine.shared.syncSoon(context.container)
        dismiss()
    }
}

private extension Date {
    /// 10-09 12:30 —— 核对列表里用。识别出来的账可能跨好几天，所以要带月日
    var shortStamp: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MM-dd HH:mm"
        return f.string(from: self)
    }
}

// MARK: - 拍照

/// 系统相机。SwiftUI 没有现成的相机控件，只能包一层 UIKit 的
struct CameraPicker: UIViewControllerRepresentable {
    let onPick: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let c = UIImagePickerController()
        c.sourceType = .camera
        c.delegate = context.coordinator
        return c
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let img = info[.originalImage] as? UIImage { parent.onPick(img) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

// MARK: - AI 设置（DeepSeek key + 模型名）

struct SmartSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var savedKey = DeepSeekKeyStore.load()
    @State private var newKey = ""
    @State private var textModel = DeepSeekConfig.textModel
    @State private var visionModel = DeepSeekConfig.visionModel
    @State private var testing = false
    @State private var testResult = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let k = savedKey {
                        HStack {
                            Text("已保存")
                            Spacer()
                            // 存进去之后只给看末 4 位，完整的 key 界面上再也读不出来
                            Text(DeepSeekKeyStore.masked(k)).foregroundStyle(.secondary).monospaced()
                        }
                    }
                    // ⚠️ SecureField：这一屏可能在别人眼前打开
                    SecureField(savedKey == nil ? "粘贴 DeepSeek API key" : "换一个新的 key", text: $newKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("保存 key") {
                        let k = newKey.trimmingCharacters(in: .whitespacesAndNewlines)
                        if DeepSeekKeyStore.save(k) { savedKey = k; newKey = ""; testResult = "" }
                        else { testResult = "存进钥匙串失败" }
                    }
                    .disabled(newKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if savedKey != nil {
                        Button("删除 key", role: .destructive) {
                            DeepSeekKeyStore.delete()
                            savedKey = nil
                            testResult = ""
                        }
                    }
                } header: {
                    Text("DeepSeek key")
                } footer: {
                    Text("key 只存在这台手机的钥匙串里，不同步、不备份到别的设备、不会发给记账服务器。"
                         + "建议在 DeepSeek 后台专门给它建一个 key、别充太多钱，万一泄露最多损失余额，后台一键作废。")
                }

                Section {
                    Button {
                        guard let k = savedKey else { return }
                        testing = true
                        Task {
                            do {
                                let models = try await DeepSeekClient.listModels(key: k)
                                testResult = "key 能用。这个 key 能用的模型：" + models.joined(separator: "、")
                            } catch {
                                testResult = "不通：" + ((error as? DeepSeekError)?.message ?? error.localizedDescription)
                            }
                            testing = false
                        }
                    } label: {
                        HStack {
                            Text("测试 key")
                            if testing { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(savedKey == nil || testing)
                } footer: {
                    if !testResult.isEmpty { Text(testResult) }
                }

                Section {
                    LabeledContent("文字模型") {
                        TextField("deepseek-chat", text: $textModel)
                            .multilineTextAlignment(.trailing)
                    }
                    LabeledContent("看图模型") {
                        TextField("deepseek-flash", text: $visionModel)
                            .multilineTextAlignment(.trailing)
                    }
                } header: {
                    Text("模型")
                } footer: {
                    Text("一般不用改。识别报「模型不存在」时，点上面的「测试 key」，从列出来的名字里挑一个填进来。")
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            }
            .navigationTitle("AI 设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            // 下滑关掉也要存，不能只认「完成」
            .onDisappear {
                if !textModel.isEmpty { DeepSeekConfig.textModel = textModel }
                if !visionModel.isEmpty { DeepSeekConfig.visionModel = visionModel }
            }
        }
    }
}
