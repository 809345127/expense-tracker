package com.shize.expensetracker.sync

import com.shize.expensetracker.data.*
import java.math.BigDecimal

/// 同步的全部逻辑都在这里。算法写在 server/README.md 的「客户端同步算法」一节，
/// **iOS 那边实现的是同一套** —— 改这里之前先看那一节，两端要一起改。
///
/// 五步：
///   1. 拉（GET /v1/changes?since=本地记的 lastRev）
///   2. 逐条合并（规则见下面 shouldKeepLocal）
///   3. has_more 就带新游标回到第 1 步
///   4. 推（POST /v1/changes，body 是本地所有 dirty 的记录，**含墓碑**）
///   5. 清 dirty、存新的 lastRev
class SyncEngine(
    private val api: SyncApi,
    private val db: AppDatabase,
    private val settings: Settings,
) {
    data class Report(val pulled: Int, val pushed: Int, val rev: Long)

    suspend fun syncOnce(): Report {
        var pulled = 0
        var since = settings.lastRev()

        // ---- 1~3：拉 + 合并，一页一页拉到没有为止 ----
        var guard = 0
        while (true) {
            val page = api.pull(since)
            merge(page)
            pulled += page.size
            if (!page.hasMore) {
                since = page.rev
                break
            }
            // ⚠️ 游标必须前进，否则就是死循环。服务端保证了这点，但客户端也要自己兜一道：
            // 万一哪天服务端有 bug，这里要退出而不是把手机烧了
            if (page.rev <= since || ++guard > 200) break
            since = page.rev
        }
        settings.setLastRev(since)

        // ---- 4~5：推本地改动 ----
        val outbox = collectDirty()
        var pushed = 0
        if (!outbox.isEmpty) {
            val res = api.push(outbox)
            pushed = res.applied
            clearDirty(outbox)
            settings.setLastRev(maxOf(res.rev, settings.lastRev()))
        }
        return Report(pulled, pushed, settings.lastRev())
    }

    /// 合并规则（**唯一一条**，四种记录共用）：
    ///
    /// - 本地没有这条 → 直接写入
    /// - 本地有、但没有未推送的改动（dirty = false） → 用服务器这份覆盖
    /// - 本地有、且 dirty = true → 只有**本地严格更新**才保留本地，否则用服务器这份覆盖并清掉 dirty
    ///
    /// ⚠️⚠️ 写入拉下来的数据时 **dirty 一定要是 false**。
    /// 顺手置成 true 的话，两台设备会把同一批数据无限互相推送，
    /// 而且表面上一切正常（数据也是对的），只有流量和 rev 在悄悄暴涨。
    private fun shouldKeepLocal(local: Syncable?, remoteUpdatedAt: Long): Boolean =
        local != null && local.dirty && local.updatedAt > remoteUpdatedAt

    private suspend fun merge(page: Payload) {
        // 分类先合并：账目引用的是分类代号，先有分类界面上才不会出现「问号图标」
        for (d in page.categories) {
            if (shouldKeepLocal(db.categoryDao().findRaw(d.id), d.updatedAt)) continue
            db.categoryDao().upsert(
                CategoryEntity(
                    id = d.id, name = d.name, iconName = d.iconName,
                    colorIndex = d.colorIndex, sortOrder = d.sortOrder,
                    isFallback = d.isFallback, createdAt = d.createdAt,
                    updatedAt = d.updatedAt, deleted = d.deleted, dirty = false,
                )
            )
        }
        for (d in page.tags) {
            if (shouldKeepLocal(db.tagDao().findRaw(d.id), d.updatedAt)) continue
            db.tagDao().upsert(
                TagEntity(
                    id = d.id, name = d.name, colorIndex = d.colorIndex,
                    sortOrder = d.sortOrder, isArchived = d.isArchived,
                    createdAt = d.createdAt, updatedAt = d.updatedAt,
                    deleted = d.deleted, dirty = false,
                )
            )
        }
        for (d in page.expenses) {
            if (shouldKeepLocal(db.expenseDao().findRaw(d.id), d.updatedAt)) continue
            db.expenseDao().upsert(
                ExpenseEntity(
                    id = d.id, amount = BigDecimal(d.amount), categoryKey = d.categoryKey,
                    note = d.note, date = d.date, createdAt = d.createdAt,
                    isPrivate = d.isPrivate, updatedAt = d.updatedAt,
                    deleted = d.deleted, dirty = false,
                )
            )
        }
        for (d in page.links) {
            if (shouldKeepLocal(db.linkDao().findRaw(d.id), d.updatedAt)) continue
            db.linkDao().upsert(
                LinkEntity(
                    id = d.id, expenseId = d.expenseId, tagId = d.tagId,
                    updatedAt = d.updatedAt, deleted = d.deleted, dirty = false,
                )
            )
        }
        // ⚠️ 只拿**这一页刚到的标签名**去查重，不是拿本地 dirty 的。
        // 我第一版写成了从 dirty 里找，那是错的：典型场景是 A 建了「出差」推上去、
        // B 也建了「出差」推上去，A 拉到 B 那条之后本地有两个同名标签，
        // 而**两条都不是 dirty**（都已经推过了）→ 从 dirty 里找永远找不到，去重压根不触发。
        mergeDuplicateTags(page.tags.map { it.name })
    }

    /// ⚠️ 一个真实存在的边界：两台设备**各自独立**新建了同名标签（比如都建了「出差」），
    /// 会得到两个不同 UUID 的同名标签。分类不会有这个问题（它的 id 是名字算出来的），
    /// 标签会，因为标签可以改名、只能用 UUID。
    ///
    /// 处理：留 id 字典序小的那个（**两台设备各自算都得到同一个结果**，所以不会打架），
    /// 把另一个的关联指过去、自己置墓碑。
    private suspend fun mergeDuplicateTags(candidateNames: List<String>) {
        if (candidateNames.isEmpty()) return
        val now = System.currentTimeMillis()
        for (name in candidateNames.distinct()) {
            val same = db.tagDao().findByName(name).sortedBy { it.id }
            if (same.size < 2) continue
            val keep = same.first()
            for (dup in same.drop(1)) {
                db.linkDao().repoint(dup.id, keep.id, now)
                db.tagDao().upsert(dup.copy(deleted = true, updatedAt = now, dirty = true))
            }
        }
    }

    private suspend fun collectDirty(): Payload = Payload(
        expenses = db.expenseDao().dirty().map {
            ExpenseDto(
                id = it.id, updatedAt = it.updatedAt, deleted = it.deleted,
                amount = it.amount.toPlainString(), categoryKey = it.categoryKey,
                note = it.note, date = it.date, createdAt = it.createdAt, isPrivate = it.isPrivate,
            )
        },
        tags = db.tagDao().dirty().map {
            TagDto(
                id = it.id, updatedAt = it.updatedAt, deleted = it.deleted, name = it.name,
                colorIndex = it.colorIndex, sortOrder = it.sortOrder,
                isArchived = it.isArchived, createdAt = it.createdAt,
            )
        },
        categories = db.categoryDao().dirty().map {
            CategoryDto(
                id = it.id, updatedAt = it.updatedAt, deleted = it.deleted, name = it.name,
                iconName = it.iconName, colorIndex = it.colorIndex, sortOrder = it.sortOrder,
                isFallback = it.isFallback, createdAt = it.createdAt,
            )
        },
        links = db.linkDao().dirty().map {
            LinkDto(id = it.id, updatedAt = it.updatedAt, deleted = it.deleted,
                    expenseId = it.expenseId, tagId = it.tagId)
        },
    )

    /// ⚠️⚠️ 清 dirty 时必须**连 updatedAt 一起当条件**（DAO 里那几条 UPDATE 就是这么写的）。
    /// 推送在飞的这几百毫秒里，用户完全可能又改了同一条记录；只按 id 清的话，
    /// 那次改动的 dirty 会被误清 → **它永远推不上去了**，而且没有任何迹象。
    private suspend fun clearDirty(sent: Payload) {
        sent.expenses.forEach { db.expenseDao().clearDirty(it.id, it.updatedAt) }
        sent.tags.forEach { db.tagDao().clearDirty(it.id, it.updatedAt) }
        sent.categories.forEach { db.categoryDao().clearDirty(it.id, it.updatedAt) }
        sent.links.forEach { db.linkDao().clearDirty(it.id, it.updatedAt) }
    }
}
