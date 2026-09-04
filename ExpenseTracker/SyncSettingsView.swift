import SwiftUI
import SwiftData

/// 「同步设置」页：粘服务器地址和 token、测试连接、立即同步、看上次同步的结果。
///
/// ⚠️ 地址和 token **不写进代码**（仓库是公开的，而那台 VPS 上还跑着别的东西），
/// 所以只能在这里粘一次。好处是以后换服务器不用重新装 app。
struct SyncSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var engine = SyncEngine.shared

    @State private var url = SyncConfig.baseURL
    @State private var token = SyncConfig.token
    @State private var testResult = ""
    @State private var testing = false

    /// 库里还有多少条等着推上去 —— 这个数字比任何文案都能说明"同步到底有没有在干活"
    @State private var pendingCount = -1

    var body: some View {
        NavigationStack {
            Form {
                // ⚠️ `Section("标题") { } footer: { }` 这个组合不存在
                // （字符串标题那个构造器不能再挂 footer），要用 header/footer 两个闭包
                Section {
                    TextField("http://地址:端口", text: $url)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    // ⚠️ token 用 SecureField：这一屏可能在别人眼前打开
                    SecureField("token", text: $token)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("服务器")
                } footer: {
                    Text("地址末尾有没有斜杠都行，会自动处理。token 在服务器上的 /opt/expense-sync/env 里。")
                }

                Section {
                    Button {
                        save()
                        Task {
                            testing = true
                            testResult = await engine.testConnection()
                            testing = false
                        }
                    } label: {
                        HStack {
                            Text("测试连接")
                            if testing { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(url.isEmpty || token.isEmpty || testing)

                    Button {
                        save()
                        Task {
                            await engine.syncNow(context.container)
                            refreshPending()
                        }
                    } label: {
                        HStack {
                            Text("立即同步")
                            if engine.state == .running { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(url.isEmpty || token.isEmpty || engine.state == .running)
                } footer: {
                    if !testResult.isEmpty { Text(testResult) }
                }

                Section("状态") {
                    row("这台设备待推送", pendingCount < 0 ? "—" : "\(pendingCount) 条")
                    row("同步游标", "\(SyncConfig.lastRev)")
                    row("上次同步", SyncConfig.lastSyncAt.map { $0.formatted(date: .abbreviated, time: .standard) } ?? "还没同步过")
                    switch engine.state {
                    case .done(let pulled, let pushed):
                        row("这次结果", "拉下来 \(pulled) 条 / 推上去 \(pushed) 条")
                    case .failed(let msg):
                        // ⚠️ 失败必须显示出来。静默失败是最坏的形态：
                        // 两台手机数据不一样，而界面上一切正常
                        Text(msg).font(.footnote).foregroundStyle(.red)
                    default:
                        EmptyView()
                    }
                    if !SyncConfig.lastError.isEmpty, engine.state == .idle {
                        Text("上次的问题：\(SyncConfig.lastError)")
                            .font(.footnote).foregroundStyle(.orange)
                    }
                }

                Section {
                    Button("重新拉一遍全部数据", role: .destructive) {
                        // 把游标归零，下次同步从头拉。数据不会丢：合并是按 id 覆盖的
                        SyncConfig.resetCursor()
                        Task {
                            await engine.syncNow(context.container)
                            refreshPending()
                        }
                    }
                } footer: {
                    Text("排障用。会把游标归零、从服务器重新拉一遍；本地数据不会丢，"
                         + "同名记录按 id 对上之后覆盖。")
                }
            }
            .navigationTitle("同步")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { save(); dismiss() }
                }
            }
            .onAppear(perform: refreshPending)
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).foregroundStyle(.secondary)
        }
    }

    private func save() {
        SyncConfig.baseURL = url
        SyncConfig.token = token
    }

    /// 数一下有多少条等着推。⚠️ 故意用「全部取出来在内存里数」而不是 #Predicate：
    /// 这个项目记着「谓词里的布尔取反运行时可能抛『不支持的谓词』把界面打崩」
    private func refreshPending() {
        let e = (try? context.fetch(FetchDescriptor<Expense>()))?.filter(\.needsPush).count ?? 0
        let t = (try? context.fetch(FetchDescriptor<Tag>()))?.filter(\.needsPush).count ?? 0
        let c = (try? context.fetch(FetchDescriptor<CategoryDef>()))?.filter(\.needsPush).count ?? 0
        let l = (try? context.fetch(FetchDescriptor<TagLink>()))?.filter(\.needsPush).count ?? 0
        pendingCount = e + t + c + l
    }
}
