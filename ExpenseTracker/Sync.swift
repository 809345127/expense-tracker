import Foundation
import SwiftData

// MARK: - 多设备同步（2026-09-04 加）
//
// 协议在仓库的 `server/README.md`，**安卓端实现的是同一套算法**，改这里要同时改那边。
// 这个文件只放「模型 + 老数据补齐」，真正的拉取/合并/推送在 SyncEngine.swift。

/// 「某笔账挂了某个标签」这件事本身，是一条独立的同步记录。
///
/// ⚠️ 为什么不能让标签跟着账目一起传：**「取消一笔账的某个标签」这个动作，
/// 账目那条记录的字段一个都没变**。如果关联只是账目的一个数组，另一台设备
/// 察觉不到这个变化 —— 标签会取消不掉、而且悄无声息。所以关联要有自己的
/// 删除墓碑和 `updatedAt`。
///
/// ⚠️ iOS 这边界面用的仍然是 SwiftData 的多对多关系（`Expense.tags`），
/// **这张表只服务于同步**：推送前从关系里对账生成、拉取后再写回关系。
/// 对账只在 SyncEngine 里做（一进一出各一处），所以**界面代码完全不用知道它的存在**。
@Model
final class TagLink {
    /// ⚠️ id 是拼出来的：`<账目syncID>:<标签syncID>`，确定性。
    /// 两台设备各自给同一笔账打同一个标签，算出来的 id 相同 → 自动并成一条；
    /// 发 UUID 就会变成两条重复的。
    var syncID: String = ""
    var expenseSyncID: String = ""
    var tagSyncID: String = ""
    var updatedAt: Date = Date.now
    var tombstone: Bool = false
    var needsPush: Bool = false

    init(expenseSyncID: String, tagSyncID: String) {
        self.syncID = TagLink.id(expense: expenseSyncID, tag: tagSyncID)
        self.expenseSyncID = expenseSyncID
        self.tagSyncID = tagSyncID
        self.updatedAt = .now
        self.needsPush = true
    }

    /// 两个客户端必须用同一个拼法
    static func id(expense: String, tag: String) -> String { "\(expense):\(tag)" }

    func touch() {
        updatedAt = .now
        needsPush = true
    }
}

// MARK: - 老数据补齐

/// 把「同步之前就存在的记录」补上同步字段。
///
/// 手机上已经有 160 笔账、5 个标签、12 个分类，它们的 `syncID` 是空串
/// （新字段的默认值），必须补上 UUID 才能参与同步 —— 不补的话所有记录的 id 都是
/// 空串，一推上去互相覆盖，只会剩一条。
///
/// ⚠️ 判据是**逐条看 syncID 空不空**，不是「有没有跑过一个开关」。
/// 用开关（存个 flag 表示"迁移过了"）的话，一旦中途失败或者以后又出现空 syncID
/// 的记录，就永远补不上了；逐条判是幂等的，跑多少次都对。
enum SyncBackfill {

    /// 每次启动都调。没有要补的就是几乎零成本的一次遍历。
    @discardableResult
    static func run(_ context: ModelContext) -> Int {
        var fixed = 0
        let now = Date.now

        // 账目
        if let expenses = try? context.fetch(FetchDescriptor<Expense>()) {
            for e in expenses where e.syncID.isEmpty {
                e.syncID = UUID().uuidString
                // ⚠️ 用「现在」而不是 createdAt 当 updatedAt：服务器上还没有这条记录，
                // 取什么值都会被接受；用现在的时间最简单，也不会跟以后安卓那边的改动比出怪结果。
                e.updatedAt = now
                e.needsPush = true    // 第一次同步要把它们全推上去（手机是初始真相）
                fixed += 1
            }
        }

        // 标签
        if let tags = try? context.fetch(FetchDescriptor<Tag>()) {
            for t in tags where t.syncID.isEmpty {
                t.syncID = UUID().uuidString
                t.updatedAt = now
                t.needsPush = true
                fixed += 1
            }
        }

        // 分类**不需要在这里补**：它没有 syncID（同步 id 就是永不改的 key），
        // 而「老数据要推上去」这件事已经由 `CategoryDef.needsPush` 的默认值 true 解决了。
        //
        // ⚠️ 别再试图用「updatedAt == createdAt」之类的条件去判「从没同步过」——
        // 迁移给新字段填的是默认值（updatedAt = 迁移那一刻），跟 createdAt 不相等，
        // 那个条件永远不成立，结果是分类一条都推不上去、而账目和标签看着都正常。

        if fixed > 0 {
            // ⚠️ 显式 save：SwiftData 不保证什么时候落盘，不 save 的话
            // 下次启动又是一批空 syncID（而且看起来像"补齐没生效"）
            try? context.save()
        }
        return fixed
    }

    /// 从「关系」对账出关联表（推送前调）。
    ///
    /// 界面改标签时动的是 `Expense.tags` 这个关系，不会去动 `TagLink`。
    /// 所以推送之前在这里对一次账：关系里有、表里没有的补一条；表里有、关系里已经没有的置墓碑。
    ///
    /// ⚠️ 顺序很重要：**必须在「合并拉下来的数据」之前调**，
    /// 这样本地这些改动才带着 `needsPush` 参与后面的冲突判定；反了的话本地改动会被覆盖掉。
    @discardableResult
    static func reconcileLinksFromRelationships(_ context: ModelContext) -> Int {
        guard let expenses = try? context.fetch(FetchDescriptor<Expense>()),
              let links = try? context.fetch(FetchDescriptor<TagLink>()) else { return 0 }

        var byID: [String: TagLink] = [:]
        for l in links { byID[l.syncID] = l }
        var changed = 0

        for e in expenses where !e.syncID.isEmpty {
            // 墓碑账目的关联一律置墓碑（账目都没了，关联不该留着）
            let wantedTagIDs: Set<String> = e.tombstone
                ? []
                : Set(e.tags.compactMap { $0.tombstone ? nil : $0.syncID }.filter { !$0.isEmpty })

            // 关系里有、表里没有（或者表里是墓碑）→ 补上/复活
            for tagID in wantedTagIDs {
                let id = TagLink.id(expense: e.syncID, tag: tagID)
                if let existing = byID[id] {
                    if existing.tombstone {
                        existing.tombstone = false
                        existing.touch()
                        changed += 1
                    }
                } else {
                    let link = TagLink(expenseSyncID: e.syncID, tagSyncID: tagID)
                    context.insert(link)
                    byID[id] = link
                    changed += 1
                }
            }

            // 表里有、关系里已经没有 → 置墓碑（这就是「取消一个标签」怎么同步出去的）
            for l in links where l.expenseSyncID == e.syncID && !l.tombstone {
                if !wantedTagIDs.contains(l.tagSyncID) {
                    l.tombstone = true
                    l.touch()
                    changed += 1
                }
            }
        }

        if changed > 0 { try? context.save() }
        return changed
    }

    /// 把关联表写回「关系」（拉取合并之后调）。
    /// ⚠️ 跟上面那个是一对，方向相反。只在 SyncEngine 里调，界面不碰。
    @discardableResult
    static func applyLinksToRelationships(_ context: ModelContext) -> Int {
        guard let expenses = try? context.fetch(FetchDescriptor<Expense>()),
              let tags = try? context.fetch(FetchDescriptor<Tag>()),
              let links = try? context.fetch(FetchDescriptor<TagLink>()) else { return 0 }

        let tagBySyncID = Dictionary(tags.compactMap { t in t.syncID.isEmpty ? nil : (t.syncID, t) },
                                     uniquingKeysWith: { a, _ in a })
        var wanted: [String: Set<String>] = [:]   // 账目 syncID → 该挂的标签 syncID
        for l in links where !l.tombstone {
            wanted[l.expenseSyncID, default: []].insert(l.tagSyncID)
        }

        var changed = 0
        for e in expenses where !e.syncID.isEmpty {
            let want = wanted[e.syncID] ?? []
            let have = Set(e.tags.compactMap { $0.syncID.isEmpty ? nil : $0.syncID })
            guard want != have else { continue }
            // ⚠️ 只认得出来的标签。拉取分页时标签可能还没到，那就这一轮先不挂，
            // 下一轮同步（标签到了之后）再对上 —— 不会永久丢，因为关联行一直在表里
            e.tags = want.compactMap { tagBySyncID[$0] }
            changed += 1
        }
        if changed > 0 { try? context.save() }
        return changed
    }
}
