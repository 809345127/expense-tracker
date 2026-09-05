import Foundation
import SwiftData
import SwiftUI

// MARK: - 同步引擎
//
// 算法写在仓库的 `server/README.md`「客户端同步算法」一节，**安卓端实现的是同一套**。
// 改这里之前先读那一节，两端要一起改。
//
// 五步：拉 → 逐条合并 → 有下一页就继续拉 → 推本地改动 → 清 needsPush、记下 rev

// MARK: 配置

/// 服务器地址 / token / 游标。
///
/// ⚠️ **都不写进代码**（这个仓库是公开的，而那台 VPS 上还跑着别的东西）。
/// 用户在「同步设置」页里粘一次，存在 UserDefaults 里 —— 沙盒内，别的 app 读不到。
enum SyncConfig {
    private static let d = UserDefaults.standard

    static var baseURL: String {
        get { d.string(forKey: "sync.url") ?? "" }
        // 末尾统一补一个斜杠：用户粘地址时大概率不带，
        // 不补的话拼出来的路径少一层，症状是「地址看着完全对、请求却 404」
        set { d.set(newValue.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: ["/"]), forKey: "sync.url") }
    }
    static var token: String {
        get { d.string(forKey: "sync.token") ?? "" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "sync.token") }
    }
    /// 见过的最大 rev。下次只拉比它大的
    static var lastRev: Int {
        get { d.integer(forKey: "sync.lastRev") }
        set { d.set(newValue, forKey: "sync.lastRev") }
    }
    static var lastSyncAt: Date? {
        get { d.object(forKey: "sync.lastSyncAt") as? Date }
        set { d.set(newValue, forKey: "sync.lastSyncAt") }
    }
    /// ⚠️ 失败要留痕。静默失败是最坏的形态：两台手机数据不一样，而界面上一切正常
    static var lastError: String {
        get { d.string(forKey: "sync.lastError") ?? "" }
        set { d.set(newValue, forKey: "sync.lastError") }
    }

    static var isConfigured: Bool { !baseURL.isEmpty && !token.isEmpty }

    /// 排障用：重置游标，下次同步从头拉一遍
    static func resetCursor() { lastRev = 0 }

    #if DEBUG
    /// 从启动参数里读同步配置并**存下来**：`-sync.url http://… -sync.token xxx`
    ///
    /// 为什么需要这条路：token 是 64 位随机串，在手机上手输不现实；而开发这台机器
    /// 点不了手机屏幕、也没法替用户粘贴。启动参数是唯一能把配置送上真机的通道。
    ///
    /// ⚠️ 必须**显式存一次**：iOS 会把 `-key value` 形式的启动参数当成 UserDefaults
    /// 最高优先级的一层（NSArgumentDomain）来读，所以不存也"读得到"——
    /// 但那一层**不持久**，下次正常从桌面点开就又是空的了。
    /// 第一次配的时候用它，配完之后就可以在「同步设置」页里改。
    static func adoptLaunchArgsIfAny() {
        let args = ProcessInfo.processInfo.arguments
        func value(_ name: String) -> String? {
            guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
            let v = args[i + 1]
            return v.hasPrefix("-") ? nil : v
        }
        if let u = value("-sync.url"), !u.isEmpty { baseURL = u }
        if let t = value("-sync.token"), !t.isEmpty { token = t }
    }
    #endif
}

// MARK: 网络传输的形状
//
// **必须跟 server/model.go 一字对应**，改任何一边都要同时改另一边。
// ⚠️ amount 是 String 不是 Double：JSON 的 number 在很多语言里就是 double，
//    一过就有误差。这个 app 用 Decimal，安卓用 BigDecimal，中间统一走字符串。

private struct ExpenseDTO: Codable {
    var id: String
    var updated_at: Int64
    var deleted: Bool
    var rev: Int64?
    var amount: String
    var category_key: String
    var note: String
    var date: Int64
    var created_at: Int64
    var is_private: Bool
}

private struct TagDTO: Codable {
    var id: String
    var updated_at: Int64
    var deleted: Bool
    var rev: Int64?
    var name: String
    var color_index: Int
    var sort_order: Int
    var is_archived: Bool
    var created_at: Int64
}

private struct CategoryDTO: Codable {
    /// ⚠️ 这个 id 就是分类代号 key（不另发 UUID，理由见 CategoryDef 的注释）
    var id: String
    var updated_at: Int64
    var deleted: Bool
    var rev: Int64?
    var name: String
    var icon_name: String
    var color_index: Int
    var sort_order: Int
    var is_fallback: Bool
    var created_at: Int64
}

private struct LinkDTO: Codable {
    /// ⚠️ 这个 id 是 `<账目id>:<标签id>` 拼出来的
    var id: String
    var updated_at: Int64
    var deleted: Bool
    var rev: Int64?
    var expense_id: String
    var tag_id: String
}

private struct Payload: Codable {
    var rev: Int64 = 0
    var has_more: Bool = false
    var expenses: [ExpenseDTO] = []
    var tags: [TagDTO] = []
    var categories: [CategoryDTO] = []
    var links: [LinkDTO] = []

    var count: Int { expenses.count + tags.count + categories.count + links.count }

    // ⚠️⚠️ **Swift 的 Codable 不会用属性默认值去补缺失的 key。**
    // 上面那些 `= []`、`= false` 只在自己 new 一个对象时管用；解析 JSON 时，
    // 只要某个 key 不在，合成的解码器就直接抛错 —— 而服务端对空数组用了 omitempty，
    // 所以「没有任何变更」时的响应就是 `{"rev":0}`，五个 key 全不在。
    //
    // 症状极具欺骗性：请求发出去了、服务端 200、日志也正常，客户端却报
    // 「服务器返回的内容看不懂：数据丢失」，看着像服务端坏了。
    // 所以这里必须自己写解码、用 decodeIfPresent。
    //
    // （安卓那边没这个坑：kotlinx.serialization 缺 key 时会用默认值。
    //   Swift 和 Kotlin 在这一点上行为相反，两端都要各自记住自己那半。）
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        rev = try c.decodeIfPresent(Int64.self, forKey: .rev) ?? 0
        has_more = try c.decodeIfPresent(Bool.self, forKey: .has_more) ?? false
        expenses = try c.decodeIfPresent([ExpenseDTO].self, forKey: .expenses) ?? []
        tags = try c.decodeIfPresent([TagDTO].self, forKey: .tags) ?? []
        categories = try c.decodeIfPresent([CategoryDTO].self, forKey: .categories) ?? []
        links = try c.decodeIfPresent([LinkDTO].self, forKey: .links) ?? []
    }

    init() {}
}

private struct PushResult: Codable {
    var rev: Int64 = 0
    var received: Int = 0
    var applied: Int = 0
    /// ⚠️ 被服务器当成旧数据丢掉的那些。非空几乎只有一个原因：这台设备时钟比另一台慢。
    /// 必须报给用户看 —— 咽掉的话那些改动就是「永久丢失且毫无迹象」
    var stale: [String] = []

    // 同上：缺 key 也要能解析（服务端以后加/删字段不至于让客户端整个失败）
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        rev = try c.decodeIfPresent(Int64.self, forKey: .rev) ?? 0
        received = try c.decodeIfPresent(Int.self, forKey: .received) ?? 0
        applied = try c.decodeIfPresent(Int.self, forKey: .applied) ?? 0
        stale = try c.decodeIfPresent([String].self, forKey: .stale) ?? []
    }
}

// MARK: 引擎

@MainActor
@Observable
final class SyncEngine {
    static let shared = SyncEngine()
    private init() {}

    enum State: Equatable {
        case idle
        case running
        case done(pulled: Int, pushed: Int)
        case failed(String)
    }

    var state: State = .idle
    private var inFlight = false

    /// 本地写完之后催一次同步。**对位安卓那边的 `Repository.syncSoon()`。**
    ///
    /// ⚠️ 不 await —— 记一笔这个动作不该被网络拖住。没网就安静地失败，
    /// 等下次切到前台、或者手动同步再补上（`syncNow` 自带互斥，不会打架）。
    ///
    /// ⚠️⚠️ **每一处「用户改了数据」的写入之后都要调一次。**
    /// iOS 这边没有安卓 `Repository` 那样的单一漏斗 —— 写操作散在各个 View 里，
    /// 所以只能逐处调。**漏一处的症状是「那一类改动要等下次打开 app 才传出去」，
    /// 而且完全不报错**：另一台设备上就是单纯的「少了一笔」。
    /// 现有调用点（改动时一并维护这张表）：
    ///   · `ExpenseFormView`      记一笔 / 编辑保存、删除
    ///   · `ExpenseListView`      左滑删除
    ///   · `CategoryManagerView`  拖动排序、删除、新建/编辑分类
    ///   · `TagPickerView`        新建标签、改名、删除
    ///
    /// ⚠️ **同步引擎自己的写入绝对不要调这个**（`Sync.swift` 里的对账、`merge`、
    /// 以及种默认分类）—— 那会变成自己触发自己。
    func syncSoon(_ container: ModelContainer) {
        guard SyncConfig.isConfigured else { return }
        Task { await syncNow(container) }
    }

    /// 手动/自动都走这一个入口。
    /// ⚠️ 自带互斥：同时来两次（比如刚记完一笔又切回前台）不会打架
    func syncNow(_ container: ModelContainer) async {
        guard SyncConfig.isConfigured, !inFlight else { return }
        inFlight = true
        state = .running
        defer { inFlight = false }

        let context = ModelContext(container)
        do {
            // ⚠️ 先从「关系」对账出关联表，**必须在合并拉下来的数据之前**：
            // 这样本地改动才带着 needsPush 参与后面的冲突判定。顺序反了本地改动会被覆盖
            SyncBackfill.run(context)
            SyncBackfill.reconcileLinksFromRelationships(context)

            // ---- 1~3 拉 + 合并（一页一页拉到没有为止）----
            var pulled = 0
            var since = SyncConfig.lastRev
            var guardCount = 0
            while true {
                let page = try await get(since: since)
                try merge(page, into: context)
                pulled += page.count
                if !page.has_more { since = Int(page.rev); break }
                // ⚠️ 游标必须前进，否则死循环。服务端保证了，客户端也自己兜一道
                if Int(page.rev) <= since || guardCount > 200 { break }
                guardCount += 1
                since = Int(page.rev)
            }
            SyncConfig.lastRev = since
            // 拉完把关联表写回关系（跟上面那次对账是一对，方向相反）
            SyncBackfill.applyLinksToRelationships(context)

            // ---- 4~5 推 + 清 needsPush ----
            let (payload, snapshot) = try collectDirty(context)
            var pushed = 0
            if payload.count > 0 {
                let result = try await post(payload)
                pushed = result.applied
                clearDirty(snapshot, context: context)
                SyncConfig.lastRev = max(SyncConfig.lastRev, Int(result.rev))
                if !result.stale.isEmpty {
                    // 不当成失败（其它记录都推成功了），但要让用户看得见
                    SyncConfig.lastError = "有 \(result.stale.count) 条改动被服务器当成旧数据丢掉了，"
                        + "检查一下两台设备的时间是不是差得比较多"
                    state = .done(pulled: pulled, pushed: pushed)
                    SyncConfig.lastSyncAt = .now
                    return
                }
            }

            try? context.save()
            SyncConfig.lastSyncAt = .now
            SyncConfig.lastError = ""
            state = .done(pulled: pulled, pushed: pushed)
        } catch {
            let msg = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            SyncConfig.lastError = msg
            state = .failed(msg)
        }
    }

    // MARK: 合并

    /// 唯一一条合并规则（四种记录共用）：
    ///
    /// - 本地没有 → 直接写入
    /// - 本地有、没有未推送的改动 → 用服务器这份覆盖
    /// - 本地有、且有未推送的改动 → 只有**本地严格更新**才留本地，否则服务器覆盖并清掉标记
    ///
    /// ⚠️⚠️ 写入拉下来的数据时 `needsPush` **一定要是 false**。
    /// 顺手置成 true 的话，两台设备会把同一批数据无限互相推送 ——
    /// 而且表面上一切正常（数据也是对的），只有流量和 rev 在悄悄暴涨。
    private func keepLocal(localUpdatedAt: Date, localDirty: Bool, remoteMillis: Int64) -> Bool {
        localDirty && ms(localUpdatedAt) > remoteMillis
    }

    private func merge(_ page: Payload, into context: ModelContext) throws {
        // 分类先合并：账目引用的是分类代号，先有分类，界面上才不会出现灰问号
        let cats = try context.fetch(FetchDescriptor<CategoryDef>())
        var catByKey = Dictionary(cats.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        for d in page.categories {
            if let c = catByKey[d.id] {
                if keepLocal(localUpdatedAt: c.updatedAt, localDirty: c.needsPush, remoteMillis: d.updated_at) { continue }
                apply(d, to: c)
            } else {
                let c = CategoryDef(key: d.id, name: d.name, iconName: d.icon_name,
                                    colorIndex: d.color_index, sortOrder: d.sort_order,
                                    isFallback: d.is_fallback)
                context.insert(c)
                apply(d, to: c)
                catByKey[d.id] = c
            }
        }

        let tags = try context.fetch(FetchDescriptor<Tag>())
        var tagBySyncID = Dictionary(tags.compactMap { $0.syncID.isEmpty ? nil : ($0.syncID, $0) },
                                     uniquingKeysWith: { a, _ in a })
        for d in page.tags {
            if let t = tagBySyncID[d.id] {
                if keepLocal(localUpdatedAt: t.updatedAt, localDirty: t.needsPush, remoteMillis: d.updated_at) { continue }
                apply(d, to: t)
            } else {
                let t = Tag(name: d.name, colorIndex: d.color_index, sortOrder: d.sort_order)
                context.insert(t)
                t.syncID = d.id
                apply(d, to: t)
                tagBySyncID[d.id] = t
            }
        }

        let expenses = try context.fetch(FetchDescriptor<Expense>())
        var expBySyncID = Dictionary(expenses.compactMap { $0.syncID.isEmpty ? nil : ($0.syncID, $0) },
                                     uniquingKeysWith: { a, _ in a })
        for d in page.expenses {
            // ⚠️ 金额解析失败就跳过这条，绝不写 0 —— 静默把一笔账变成 0 元比同步失败恶劣得多
            guard let amount = Decimal(string: d.amount, locale: Locale(identifier: "en_US_POSIX")) else { continue }
            if let e = expBySyncID[d.id] {
                if keepLocal(localUpdatedAt: e.updatedAt, localDirty: e.needsPush, remoteMillis: d.updated_at) { continue }
                apply(d, amount: amount, to: e)
            } else {
                let e = Expense(amount: amount, categoryKey: d.category_key, note: d.note,
                                date: date(d.date), isPrivate: d.is_private)
                context.insert(e)
                e.syncID = d.id
                apply(d, amount: amount, to: e)
                expBySyncID[d.id] = e
            }
        }

        let links = try context.fetch(FetchDescriptor<TagLink>())
        var linkBySyncID = Dictionary(links.map { ($0.syncID, $0) }, uniquingKeysWith: { a, _ in a })
        for d in page.links {
            if let l = linkBySyncID[d.id] {
                if keepLocal(localUpdatedAt: l.updatedAt, localDirty: l.needsPush, remoteMillis: d.updated_at) { continue }
                l.tombstone = d.deleted
                l.updatedAt = date(d.updated_at)
                l.needsPush = false
            } else {
                let l = TagLink(expenseSyncID: d.expense_id, tagSyncID: d.tag_id)
                context.insert(l)
                l.tombstone = d.deleted
                l.updatedAt = date(d.updated_at)
                l.needsPush = false
                linkBySyncID[d.id] = l
            }
        }
        try context.save()
    }

    private func apply(_ d: CategoryDTO, to c: CategoryDef) {
        // ⚠️ 不动 key：它是身份本身
        c.name = d.name; c.iconName = d.icon_name
        c.colorIndex = d.color_index; c.sortOrder = d.sort_order; c.isFallback = d.is_fallback
        c.createdAt = date(d.created_at)
        c.updatedAt = date(d.updated_at); c.tombstone = d.deleted; c.needsPush = false
    }

    private func apply(_ d: TagDTO, to t: Tag) {
        t.name = d.name; t.colorIndex = d.color_index
        t.sortOrder = d.sort_order; t.isArchived = d.is_archived
        t.createdAt = date(d.created_at)
        t.updatedAt = date(d.updated_at); t.tombstone = d.deleted; t.needsPush = false
    }

    private func apply(_ d: ExpenseDTO, amount: Decimal, to e: Expense) {
        e.amount = amount; e.categoryRaw = d.category_key; e.note = d.note
        e.date = date(d.date); e.createdAt = date(d.created_at); e.isPrivate = d.is_private
        e.updatedAt = date(d.updated_at); e.tombstone = d.deleted; e.needsPush = false
    }

    // MARK: 推送

    /// 收集本地待推送的记录。
    /// 同时返回一份快照（id + 当时的 updatedAt），清标记时要用 —— 见 clearDirty
    private func collectDirty(_ context: ModelContext) throws -> (Payload, [(String, Date, Kind)]) {
        var p = Payload()
        var snap: [(String, Date, Kind)] = []

        for e in try context.fetch(FetchDescriptor<Expense>()) where e.needsPush && !e.syncID.isEmpty {
            p.expenses.append(ExpenseDTO(
                id: e.syncID, updated_at: ms(e.updatedAt), deleted: e.tombstone, rev: nil,
                // ⚠️ 用 NSDecimalNumber 的 stringValue：它给的是不带科学计数、不丢精度的十进制串
                amount: NSDecimalNumber(decimal: e.amount).stringValue,
                category_key: e.categoryRaw, note: e.note,
                date: ms(e.date), created_at: ms(e.createdAt), is_private: e.isPrivate))
            snap.append((e.syncID, e.updatedAt, .expense))
        }
        for t in try context.fetch(FetchDescriptor<Tag>()) where t.needsPush && !t.syncID.isEmpty {
            p.tags.append(TagDTO(
                id: t.syncID, updated_at: ms(t.updatedAt), deleted: t.tombstone, rev: nil,
                name: t.name, color_index: t.colorIndex, sort_order: t.sortOrder,
                is_archived: t.isArchived, created_at: ms(t.createdAt)))
            snap.append((t.syncID, t.updatedAt, .tag))
        }
        for c in try context.fetch(FetchDescriptor<CategoryDef>()) where c.needsPush && !c.key.isEmpty {
            p.categories.append(CategoryDTO(
                id: c.key, updated_at: ms(c.updatedAt), deleted: c.tombstone, rev: nil,
                name: c.name, icon_name: c.iconName, color_index: c.colorIndex,
                sort_order: c.sortOrder, is_fallback: c.isFallback, created_at: ms(c.createdAt)))
            snap.append((c.key, c.updatedAt, .category))
        }
        for l in try context.fetch(FetchDescriptor<TagLink>()) where l.needsPush && !l.syncID.isEmpty {
            p.links.append(LinkDTO(
                id: l.syncID, updated_at: ms(l.updatedAt), deleted: l.tombstone, rev: nil,
                expense_id: l.expenseSyncID, tag_id: l.tagSyncID))
            snap.append((l.syncID, l.updatedAt, .link))
        }
        return (p, snap)
    }

    private enum Kind { case expense, tag, category, link }

    /// 清掉待推送标记。
    ///
    /// ⚠️⚠️ **必须比对 `updatedAt` 有没有变**，不能只按 id 清。
    /// 推送在飞的那几百毫秒里，用户完全可能又改了同一条记录；只按 id 清的话，
    /// 那次改动的标记会被误清 → **它永远推不上去了**，而且没有任何迹象。
    /// （服务端那份收敛测试里第 6/7 组专门验这条，第 7 组就是故意用错误实现的对照。）
    private func clearDirty(_ snapshot: [(String, Date, Kind)], context: ModelContext) {
        let expenses = (try? context.fetch(FetchDescriptor<Expense>())) ?? []
        let tags = (try? context.fetch(FetchDescriptor<Tag>())) ?? []
        let cats = (try? context.fetch(FetchDescriptor<CategoryDef>())) ?? []
        let links = (try? context.fetch(FetchDescriptor<TagLink>())) ?? []

        for (id, sentAt, kind) in snapshot {
            switch kind {
            case .expense:
                if let e = expenses.first(where: { $0.syncID == id }), e.updatedAt == sentAt { e.needsPush = false }
            case .tag:
                if let t = tags.first(where: { $0.syncID == id }), t.updatedAt == sentAt { t.needsPush = false }
            case .category:
                if let c = cats.first(where: { $0.key == id }), c.updatedAt == sentAt { c.needsPush = false }
            case .link:
                if let l = links.first(where: { $0.syncID == id }), l.updatedAt == sentAt { l.needsPush = false }
            }
        }
        try? context.save()
    }

    // MARK: HTTP

    struct SyncError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private func request(_ path: String, method: String, body: Data? = nil) throws -> URLRequest {
        guard let url = URL(string: SyncConfig.baseURL + path) else {
            throw SyncError(message: "服务器地址填得不对：\(SyncConfig.baseURL)")
        }
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.setValue("Bearer \(SyncConfig.token)", forHTTPHeaderField: "Authorization")
        r.timeoutInterval = 20
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return r
    }

    private func send<T: Decodable>(_ r: URLRequest, as: T.Type) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: r)
        guard let http = response as? HTTPURLResponse else { throw SyncError(message: "没有拿到 HTTP 响应") }
        switch http.statusCode {
        case 200..<300:
            do { return try JSONDecoder().decode(T.self, from: data) }
            catch { throw SyncError(message: "服务器返回的内容看不懂：\(error.localizedDescription)") }
        case 401:
            throw SyncError(message: "token 不对（401）。去「同步设置」核一下")
        default:
            let text = String(data: data, encoding: .utf8) ?? ""
            throw SyncError(message: "服务器报错 \(http.statusCode)：\(text.prefix(120))")
        }
    }

    private func get(since: Int) async throws -> Payload {
        try await send(try request("/v1/changes?since=\(since)&limit=500", method: "GET"), as: Payload.self)
    }

    private func post(_ p: Payload) async throws -> PushResult {
        let body = try JSONEncoder().encode(p)
        return try await send(try request("/v1/changes", method: "POST", body: body), as: PushResult.self)
    }

    /// 只测通不通（设置页那个「测试连接」按钮）
    func testConnection() async -> String {
        guard SyncConfig.isConfigured else { return "地址或 token 还没填" }
        do {
            let r = try request("/v1/health", method: "GET")
            let (data, response) = try await URLSession.shared.data(for: r)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let text = String(data: data, encoding: .utf8) ?? ""
            return code == 200 && text.contains("ok") ? "连上了 ✓" : "连上了但返回不对（\(code)）：\(text.prefix(60))"
        } catch {
            return "连不上：\(error.localizedDescription)"
        }
    }

    // MARK: 时间换算（协议用毫秒时间戳）

    private func ms(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
    private func date(_ m: Int64) -> Date { Date(timeIntervalSince1970: Double(m) / 1000) }
}
