import SwiftUI
import SwiftData
import UIKit

// MARK: - CSV（给 AI 分析用）

enum ExpenseCSV {
    static let header = "记账时间,创建时间,金额,分类,备注,标签"

    /// 一行一笔。时间用 `2026-08-19 12:18:32`，AI 不用猜格式；标签多个用 `|` 隔开
    /// `catalog` 用来把分类代号翻成显示名（分类改过名的话，导出里也要是新名字）。
    /// 传 nil 就原样写代号 —— 代号本身就是分类当初的名字，认得出
    static func make(from expenses: [Expense], catalog: CategoryCatalog? = nil) -> String {
        let rows = expenses.map { e in
            [
                e.date.fullStampTitle,
                e.createdAt.fullStampTitle,
                "\(e.amount)",
                catalog?.name(forKey: e.categoryRaw) ?? e.categoryRaw,
                e.note,
                e.tags.map(\.name).joined(separator: "|"),
            ]
            .map(escaped)
            .joined(separator: ",")
        }
        return ([header] + rows).joined(separator: "\n")
    }

    /// CSV 转义：字段里出现逗号、引号或换行时，整个字段用双引号包起来，里面的引号写两遍。
    ///
    /// ⚠️ 别省这一步。备注是自由文本，逗号非常常见（"打车，报销用"）。不转义的话那一行会被
    /// 多切出一列，**后面每一列全部错位**——金额挪到分类那一列去。最坑的是这种错谁都不会报错，
    /// AI 读进去照样一本正经地分析，只是结论全是错的。
    private static func escaped(_ field: String) -> String {
        let needsQuoting = field.contains { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }
        guard needsQuoting else { return field }
        return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    /// 写成临时文件，返回可以丢进系统分享面板的地址。
    ///
    /// ⚠️ 开头补一个 BOM：不补的话 Excel 会拿系统本地编码去解这份 UTF-8，中文全是乱码。
    /// 给 AI 吃无所谓，但你哪天想用 Excel 打开就有所谓了。多这 3 个字节没有任何副作用。
    static func writeTempFile(_ expenses: [Expense], named name: String) -> URL? {
        var data = Data([0xEF, 0xBB, 0xBF])
        data.append(Data(make(from: expenses).utf8))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name).csv")
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }
}

// MARK: - 长图

/// 专门用来渲染长图的视图。
///
/// ⚠️⚠️ **必须用 VStack，绝对不能用 List / LazyVStack / ScrollView**。
/// `ImageRenderer` 只画渲染那一刻**已经存在**的子视图，而 List 和所有 Lazy 容器都是
/// 「滚到哪才建到哪」——屏幕外的行在那一刻根本不存在，出来的图就只有开头一小截，
/// 而且**不报错**，你不数一遍根本发现不了。
///
/// 这也正是 iOS 上的 app 做不到"真·滚动截屏"的原因：屏幕外的内容不是被裁掉了，是压根没画。
/// 所以只能像这样把内容重新画一遍——但行样式直接复用 `ExpenseRow`，画出来跟界面一模一样。
struct ExportImageView: View {
    let title: String
    let total: Decimal
    let count: Int
    let days: [(date: Date, items: [Expense], total: Decimal)]
    let footer: String

    /// 渲染宽度。用固定值而不是当前屏宽：导出的图跟在哪台设备上导的无关，
    /// 换手机之后导出来的图还是一样宽，存档才对得齐
    static let width: CGFloat = 390

    var body: some View {
        VStack(spacing: 0) {
            header
            VStack(spacing: 18) {
                ForEach(days, id: \.date) { day in
                    VStack(spacing: 0) {
                        HStack {
                            Text(day.date.dayTitle)
                            Spacer()
                            Text(day.total.yuan).monospacedDigit()
                        }
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 4)
                        .padding(.bottom, 8)

                        VStack(spacing: 0) {
                            ForEach(Array(day.items.enumerated()), id: \.element.persistentModelID) { i, expense in
                                if i > 0 {
                                    Divider().padding(.leading, 52)
                                }
                                // 跟明细页同一个 ExpenseRow，图和界面不会分叉
                                ExpenseRow(expense: expense)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                            }
                        }
                        .background(Color(.secondarySystemGroupedBackground),
                                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 18)

            Text(footer)
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .padding(.top, 22)
                .padding(.bottom, 18)
        }
        .frame(width: Self.width)
        .background(Color(.systemGroupedBackground))
    }

    private var header: some View {
        VStack(spacing: 8) {
            Text(title)
                .font(.headline)
                .foregroundStyle(.white.opacity(0.92))
            Text(total.yuan)
                .font(.system(size: 36, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white)
            Text("共 \(count) 笔")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.78))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 22)
        .background(Theme.cardGradient)
    }
}

// MARK: - 导出弹层

struct ExportSheet: View {
    @Binding var month: Date
    @Environment(\.dismiss) private var dismiss
    @Environment(PrivacyGate.self) private var gate
    @Environment(\.categoryCatalog) private var catalog

    /// 全部记录。范围是「本月」时在内存里再筛一次——跟明细页一样的做法，
    /// 一年几千笔无所谓，也避开了 #Predicate 的那些坑
    @Query(sort: [SortDescriptor(\Expense.date, order: .reverse),
                  SortDescriptor(\Expense.createdAt, order: .reverse)])
    private var allExpenses: [Expense]

    enum Scope: String, CaseIterable, Identifiable {
        case month = "本月"
        case all = "全部"
        var id: String { rawValue }
    }

    @State private var scope: Scope = .month
    @State private var csvFile: URL?
    @State private var longImage: UIImage?
    @State private var rendering = false
    @State private var copied = false

    /// 要导出的记录。**先过私密门**——锁着的时候导出的内容和你屏幕上看到的完全一致，
    /// 不会出现「界面上藏着、导出来却带出去了」这种后门
    private var records: [Expense] {
        let visible = allExpenses.visible(unlocked: gate.isUnlocked)
        switch scope {
        case .all:
            return visible
        case .month:
            let start = month.startOfMonth
            let end = start.addingMonths(1)
            return visible.filter { $0.date >= start && $0.date < end }
        }
    }

    private var total: Decimal { records.amountSum }

    // MARK: 长图的尺寸保护
    //
    // ⚠️⚠️ **真正卡住这件事的是「一张位图最高 8192 像素」，不是内存。**
    //
    // 2026-08-20 二分实测（99 笔、内容高 7488 点）：
    //   scale 1.09 → 8163 px ✅ 出图
    //   scale 1.095 → 8199 px ❌ ImageRenderer 直接返回 nil
    // 8192 = 2^13，是 CoreGraphics / Metal 单张位图的经典上限。
    //
    // ⚠️ **它失败得完全无声**：`renderer.uiImage` 返回 nil，不抛错、不崩、不打日志，
    // 界面上就是「点了生成长图，什么都没发生」。
    //
    // 🔎 这个 bug 是怎么潜伏下来的（值得记住的教训）：
    // 原来的实现按**笔数**挑清晰度（>250 用 1 倍、>100 用 2 倍、否则 3 倍），
    // 而当初验收时库里只有 **14 笔** —— 3 倍图才 4897 px，远在上限之内，一切正常。
    // 等账目长到 99 笔，同样的 3 倍图变成 22464 px，直接越界、静默失效。
    // **「在默认那一档验过」不等于「验过」**：这里真正的自变量是像素高度，
    // 而笔数只是它的一个间接代理，代理关系随数据量增长就断了。
    //
    // 所以现在改成**按渲染出来的真实高度反推清晰度**，不再猜笔数。

    /// 一张位图最高多少像素（实测值，见上面注释）。留一点余量，不贴着边走
    private static let maxPixelHeight: CGFloat = 8000

    /// 内容在 1 倍下有多高（点）。由 `measureContentHeight()` 在渲染前量出来，
    /// 量不到时为 0 —— 那种情况按最保守的 1 倍处理
    @State private var contentHeight: CGFloat = 0

    /// 按真实高度反推能用的最大清晰度，上限 3 倍（再高肉眼也看不出差别，只是更占内存）
    private var imageScale: CGFloat {
        #if DEBUG
        if let v = DevFlags.value("-imgScale"), let n = Double(v) { return CGFloat(n) }
        #endif
        guard contentHeight > 0 else { return 1 }
        return max(1, min(3, (Self.maxPixelHeight / contentHeight * 100).rounded(.down) / 100))
    }

    /// 连 1 倍都超高，就是真的画不成一张图了 —— 这时要**明确告诉用户**，
    /// 不能像以前那样悄悄失败
    private var longImageTooBig: Bool {
        contentHeight > Self.maxPixelHeight
    }

    /// 大约还能装多少笔（给提示文案用）。一笔约 70 点
    private var approxRowCapacity: Int { Int(Self.maxPixelHeight / 70) }
    private var scopeTitle: String { scope == .month ? month.monthTitle : "全部记录" }

    /// 文件名。解锁状态下导出的内容含私密记录，名字里带个标记
    /// —— 免得你解锁时导了一份、回头忘了，直接发给别人
    private var baseName: String {
        "记账本-\(scopeTitle)" + (gate.isUnlocked ? "-含私密" : "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("范围") {
                    Picker("范围", selection: $scope) {
                        ForEach(Scope.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)

                    LabeledContent("包含") {
                        Text("\(records.count) 笔 · \(total.yuan)")
                            .monospacedDigit()
                    }

                    if gate.isUnlocked {
                        Label("私密模式开着，导出的内容包含私密记录", systemImage: "lock.open.fill")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }

                Section {
                    if let csvFile {
                        ShareLink(item: csvFile) {
                            Label("导出 CSV 文件", systemImage: "square.and.arrow.up")
                        }
                    }
                    Button {
                        UIPasteboard.general.string = ExpenseCSV.make(from: records, catalog: catalog)
                        copied = true
                    } label: {
                        Label(copied ? "已复制到剪贴板" : "复制成文本",
                              systemImage: copied ? "checkmark" : "doc.on.doc")
                    }
                    // 这里**故意不加** .buttonStyle(.plain)：旁边的 ShareLink 就是蓝色可点的样子，
                    // 加了 plain 会变成黑字、跟它并排看着像不能点的说明文字。
                    // 项目里那条「Form 里的 Button 要加 .plain」针对的是"当普通行用"的场景
                    // （比如标签那一行），这里本来就是个动作按钮，蓝色才对
                } header: {
                    Text("给 AI 分析")
                } footer: {
                    Text("一行一笔，含记账时间、创建时间、金额、分类、备注、标签。手机上直接「复制成文本」粘给 AI 最快，要存档或者用 Excel 打开就导文件。")
                }

                Section {
                    Button {
                        Task { await renderLongImage() }
                    } label: {
                        HStack {
                            Label(longImage == nil ? "生成长图" : "重新生成", systemImage: "photo")
                            if rendering {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(rendering || records.isEmpty || longImageTooBig)

                    if longImageTooBig {
                        Label("\(records.count) 笔画出来有 \(Int(contentHeight)) 点高，超过了一张图能有的最大高度（约 \(approxRowCapacity) 笔）。改选「本月」，或者用上面的 CSV",
                              systemImage: "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }

                    if let longImage {
                        ShareLink(
                            item: Image(uiImage: longImage),
                            preview: SharePreview(baseName, image: Image(uiImage: longImage))
                        ) {
                            Label("保存 / 分享长图", systemImage: "square.and.arrow.up")
                        }
                        Text("\(Int(longImage.size.width)) × \(Int(longImage.size.height)) 点，约 \(String(format: "%.1f", longImage.size.height / 844)) 屏高")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("长图")
                } footer: {
                    Text("iOS 没有系统级的滚动截屏，所以由 app 把整页重新画一张完整长图，样式跟明细页一致。分享面板里选「存储图像」就存进相册。")
                }
            }
            .navigationTitle("导出")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
            // 范围一变，之前生成的产物就作废了，必须清掉：
            // 留着的话「本月」切到「全部」后，分享出去的还是上一份，而且完全看不出来
            .onChange(of: scope) { _, _ in
                longImage = nil
                copied = false
                refreshCSV()
            }
            .onAppear { refreshCSV() }
            #if DEBUG
            .task {
                if DevFlags.has("-dumpExport") { await dumpForVerification() }
            }
            #endif
        }
    }

    #if DEBUG
    /// -dumpExport：启动就把 CSV 和长图渲出来落到 Documents，供命令行核对。
    /// 这台机器上的模拟器没有窗口、点不了「生成长图」那个按钮（见 README），
    /// 而「长图有没有把所有行都画进去」恰恰是这功能唯一会静默出错的地方
    /// ——用错容器只会画出开头一小截，不报错、不崩，不数一遍根本看不出来。
    private func dumpForVerification() async {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? Data(ExpenseCSV.make(from: records, catalog: catalog).utf8)
            .write(to: docs.appendingPathComponent("dump.csv"))
        await renderLongImage()
        if let png = longImage?.pngData() {
            try? png.write(to: docs.appendingPathComponent("dump.png"))
        }
        try? "\(records.count)".write(to: docs.appendingPathComponent("dump-count.txt"),
                                      atomically: true, encoding: .utf8)
    }
    #endif

    private func refreshCSV() {
        csvFile = records.isEmpty ? nil : ExpenseCSV.writeTempFile(records, named: baseName)
    }

    /// 渲染之前先量一次内容有多高。
    /// `ImageRenderer.render` 的回调会把内容尺寸交给你，而且**不会真的生成位图**
    /// —— 所以量高度这一步不受 8192 上限影响，可以放心先量再决定清晰度。
    @MainActor
    private func measureContentHeight() {
        let renderer = ImageRenderer(content: longImageContent(stamp: ""))
        renderer.scale = 1
        renderer.render { size, _ in contentHeight = size.height }
    }

    private func longImageContent(stamp: String) -> some View {
        ExportImageView(
            title: scopeTitle,
            total: total,
            count: records.count,
            days: records.groupedByDay(),
            footer: "记账本 · 导出于 \(stamp)"
        )
        // ⚠️ **必须显式把分类目录塞进去**。`ImageRenderer` 渲的视图是脱离视图树的，
        // 拿不到我们在根部注入的 environment —— 不补这一句，图里每行的分类图标会变成
        // 灰问号、分类名退回成内部代号。跟下面固定浅色是同一个道理（同一个坑的两个面）。
        .environment(\.categoryCatalog, catalog)
    }

    @MainActor
    private func renderLongImage() async {
        measureContentHeight()
        guard !longImageTooBig else { return }
        rendering = true
        defer { rendering = false }

        let stamp = Date.now.fullStampTitle
        let view = longImageContent(stamp: stamp)

        // 固定浅色：导出的图要发给别人、要存档，不该跟着你当时是不是深色模式变。
        // ImageRenderer 渲的视图是脱离环境的，不显式指定的话取默认值——这里写死才有保证
        let renderer = ImageRenderer(content: view.environment(\.colorScheme, .light))
        // 图的像素高度 = 内容高度 × scale。笔数一多，3 倍图能到几百 MB，渲染当场把内存打爆。
        // 所以按笔数降档：日常几十笔走 3 倍最清楚，量大了牺牲清晰度换能出图
        renderer.scale = imageScale
        longImage = renderer.uiImage
    }
}
