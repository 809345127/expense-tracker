package com.shize.expensetracker.data

import android.content.Context
import com.shize.expensetracker.sync.SyncWorker
import com.shize.expensetracker.widget.WidgetRefresh
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.util.UUID

/// 界面唯一的数据入口。四件事只在这里做，别处不许重复：
///
///   ① **私密记录的过滤**（见下面 visible()）
///   ② 任何本地写入都要 `updatedAt = 现在` + `dirty = true`
///   ③ 删除是**置墓碑**，不是删行
///   ④ 写完主动触发一次同步
///
/// ④ 为什么重要：用户手机是 vivo（OriginOS），这类国产 ROM 杀后台很凶，
/// WorkManager 的 15 分钟周期任务**很可能不会按时跑**（要用户手动放行自启动/后台高耗电）。
/// 所以同步不能只靠周期任务：开 app 同步一次、每次写入之后同步一次、再给个手动下拉。
/// 三条路都留着，任何一条通了数据就是新的。
class Repository(
    private val context: Context,
    private val db: AppDatabase,
) {
    private val expenses = db.expenseDao()
    private val tags = db.tagDao()
    private val categories = db.categoryDao()
    private val links = db.linkDao()

    private fun now() = System.currentTimeMillis()

    // ---------------------------------------------------------------- 读

    /// ⚠️⚠️ **私密记录的过滤只在这一个函数里做**（对位 iOS 那边的
    /// `Array<Expense>.visible(unlocked:)`）。列表、合计、统计、小组件、导出，
    /// 全部从这里派生。
    ///
    /// 教训是 iOS 那边总结出来的：**露馅的从来不是被藏起来的内容，是对不上的那个数。**
    /// 只要有任何一个统计口径绕过这个过滤自己去查库，锁定态下它算出来的总额就会跟
    /// 列表对不上，别人一比就知道你藏了东西、还知道藏了多少。
    private fun List<ExpenseEntity>.visible(unlocked: Boolean) =
        if (unlocked) this else filter { !it.isPrivate }

    fun observeMonth(from: Long, to: Long, unlocked: Boolean): Flow<List<ExpenseEntity>> =
        expenses.observeRange(from, to).let { flow ->
            combine(flow, categories.observeAll()) { list, _ -> list.visible(unlocked) }
        }

    /// 全部账目（过完私密门）。导出「全部」那一档用
    fun observeAllExpenses(unlocked: Boolean): Flow<List<ExpenseEntity>> =
        expenses.observeAll().map { it.visible(unlocked) }

    fun observeCategories(): Flow<List<CategoryEntity>> = categories.observeAll()
    fun observeTags(): Flow<List<TagEntity>> = tags.observeActive()

    /// ⚠️ 含已归档的标签。历史账目上挂着的归档标签，统计和导出里要能显示出名字，
    /// 不能因为它归档了就变成一个 id
    fun observeAllTags(): Flow<List<TagEntity>> = tags.observeAll()
    fun observeLinks(): Flow<List<LinkEntity>> = links.observeAll()
    suspend fun tagIdsOf(expenseId: String): List<String> = links.tagIdsOf(expenseId)

    /// 分类管理页要的两份用量。
    /// ⚠️⚠️ 两个口径必须**分开**（见下面 categoryDeletable 的注释）：
    /// `all` 判能不能删（含私密），`visible` 只用来显示。
    fun observeCategoryUsage(unlocked: Boolean): Flow<Pair<Map<String, Int>, Map<String, Int>>> =
        combine(expenses.observeUsageAll(), expenses.observeUsageVisible()) { all, vis ->
            val allMap = all.associate { it.categoryKey to it.n }
            // 解锁态下「看得见的」就是全部 —— 用同一份，免得两个数字在解锁后还不一致
            val visMap = if (unlocked) allMap else vis.associate { it.categoryKey to it.n }
            allMap to visMap
        }

    // ---------------------------------------------------------------- 写

    /// 记一笔。返回新记录的 id。
    ///
    /// ⚠️ 金额参数是 BigDecimal，调用方**必须先把用户输入净化过**——
    /// iOS 那边最严重的历史 bug 就在这：中文输入法把小数点打成全角句号「。」时，
    /// 解析静默截断（`12。75` → `12`）、保存按钮照样能按。安卓这边同一个坑，
    /// 净化和校验在 ui/AmountInput.kt 里，改金额相关代码务必回归这一条。
    suspend fun addExpense(
        amount: BigDecimal,
        categoryKey: String,
        note: String = "",
        date: Long = now(),
        isPrivate: Boolean = false,
        tagIds: List<String> = emptyList(),
    ): String {
        val id = UUID.randomUUID().toString()
        val t = now()
        expenses.upsert(
            ExpenseEntity(
                id = id, amount = amount, categoryKey = categoryKey, note = note,
                date = date, createdAt = t, isPrivate = isPrivate,
                updatedAt = t, deleted = false, dirty = true,
            )
        )
        setTags(id, tagIds)
        syncSoon()
        return id
    }

    suspend fun updateExpense(
        e: ExpenseEntity,
        amount: BigDecimal = e.amount,
        categoryKey: String = e.categoryKey,
        note: String = e.note,
        date: Long = e.date,
        isPrivate: Boolean = e.isPrivate,
    ) {
        expenses.upsert(
            e.copy(
                amount = amount, categoryKey = categoryKey, note = note,
                date = date, isPrivate = isPrivate,
                // ⚠️ createdAt 不动：它记的是「这条记录是什么时候写进库的」，
                // 编辑时改掉的话「当场记的 / 事后补的」这个区分就没了
                updatedAt = now(), dirty = true,
            )
        )
        syncSoon()
    }

    /// 删一笔。⚠️ 置墓碑，不是删行——真删的话另一台设备下次同步会把它送回来。
    suspend fun deleteExpense(e: ExpenseEntity) {
        val t = now()
        expenses.upsert(e.copy(deleted = true, updatedAt = t, dirty = true))
        // 它的标签关联也要一起置墓碑，否则对端会留下指向不存在账目的悬空关联
        for (tagId in links.tagIdsOf(e.id)) {
            links.findRaw(LinkEntity.idOf(e.id, tagId))?.let {
                links.upsert(it.copy(deleted = true, updatedAt = t, dirty = true))
            }
        }
        syncSoon()
    }

    /// 把一笔账的标签**整体设成**给定这批（多的置墓碑、少的建出来）。
    /// ⚠️ 关联是独立记录，所以「取消一个标签」必须留下一条墓碑，不能只是不写它
    suspend fun setTags(expenseId: String, tagIds: List<String>) {
        val t = now()
        val current = links.tagIdsOf(expenseId).toSet()
        val want = tagIds.toSet()

        for (add in want - current) {
            links.upsert(
                LinkEntity(LinkEntity.idOf(expenseId, add), expenseId, add,
                           updatedAt = t, deleted = false, dirty = true)
            )
        }
        for (remove in current - want) {
            links.findRaw(LinkEntity.idOf(expenseId, remove))?.let {
                links.upsert(it.copy(deleted = true, updatedAt = t, dirty = true))
            }
        }
        if (want != current) syncSoon()
    }

    suspend fun addTag(name: String, colorIndex: Int): String {
        val id = UUID.randomUUID().toString()
        val t = now()
        tags.upsert(TagEntity(id, cleanedName(name), colorIndex, sortOrder = 0,
                              createdAt = t, updatedAt = t, dirty = true))
        syncSoon()
        return id
    }

    /// 新建分类。
    /// ⚠️ 代号（id）取「建的这一刻的名字」，撞了就加 `-2` 后缀 —— 跟 iOS 那边**一模一样的算法**，
    /// 这样两台设备各自建同名分类会算出同一个代号、自动并成一条。改这里必须同时改 iOS。
    suspend fun addCategory(name: String, iconName: String, colorIndex: Int): String {
        // ⚠️ 必须走 cleanedName，不能只 trim：iOS 那边算代号用的就是它
        //（它还会把名字中间的连续空白压成一个空格）。两端算法差一点，
        // 同一个名字就会得到两个代号 → 同步之后变成两条一模一样的分类，而且不可逆
        val clean = cleanedName(name)
        val taken = categories.all().map { it.id }.toSet()
        var key = clean
        var n = 2
        while (key in taken) { key = "$clean-$n"; n++ }

        val t = now()
        val maxOrder = categories.all().maxOfOrNull { it.sortOrder } ?: -1
        categories.upsert(
            CategoryEntity(id = key, name = clean, iconName = iconName, colorIndex = colorIndex,
                           sortOrder = maxOrder + 1, isFallback = false,
                           createdAt = t, updatedAt = t, dirty = true)
        )
        syncSoon()
        return key
    }

    /// 改分类。⚠️ **只改显示用的三个字段，绝不动 id（代号）** ——
    /// 历史账目认的是代号，动了它们全部找不到分类
    suspend fun updateCategory(c: CategoryEntity, name: String, iconName: String, colorIndex: Int) {
        categories.upsert(c.copy(name = cleanedName(name), iconName = iconName,
                                 colorIndex = colorIndex, updatedAt = now(), dirty = true))
        syncSoon()
    }

    /// 能不能删这个分类。
    ///
    /// ⚠️⚠️ 两个口径必须分开（iOS 那边踩出来的）：
    ///   **能不能删** → 按**全部**记录判（含私密）
    ///   **提示里显示的笔数** → 按**看得见的**算
    /// 只数看得见的会出事：某个分类底下只有私密记录时，锁定态下数出 0 → 允许删 →
    /// 那几笔私密账当场悬空。所以出现「显示 0 笔却不给删」时，提示语**不带数字**。
    suspend fun categoryDeletable(key: String): Pair<Boolean, Int> {
        val all = expenses.countAllInCategory(key)
        val visible = expenses.countVisibleInCategory(key)
        return (all == 0) to visible
    }

    suspend fun deleteCategory(c: CategoryEntity): Boolean {
        val (ok, _) = categoryDeletable(c.id)
        if (!ok || c.isFallback) return false
        categories.upsert(c.copy(deleted = true, updatedAt = now(), dirty = true))
        syncSoon()
        return true
    }

    /// 拖动排序。整批重编号、整批标 dirty
    suspend fun reorderCategories(ordered: List<CategoryEntity>) {
        val t = now()
        categories.upsert(ordered.mapIndexed { i, c ->
            c.copy(sortOrder = i, updatedAt = t, dirty = true)
        })
        syncSoon()
    }

    /// 写完就催一次同步 + 刷一次桌面小组件。
    ///
    /// ⚠️ 同步不 await —— 记账这个动作不该被网络拖住，没网时 WorkManager 会自己排队等网络回来。
    /// ⚠️ 小组件也要刷：不刷的话桌面上那个数会停在上一次的值，而**它跟 app 里的数对不上
    /// 本身就是个隐私漏洞**（能看出"有几笔没算进去"）。
    private fun syncSoon() {
        SyncWorker.syncNow(context)
        WidgetRefresh.request(context)
    }

    // ---------------------------------------------------------------- 桌面小组件

    /// 桌面小组件要显示的那几个数。
    ///
    /// ⚠️⚠️ **恒定按锁定态算**（`visible(unlocked = false)`）：小组件摆在桌面上，
    /// 比 app 里更暴露；而且只要它跟 app 锁定态的数字对不上，别人一比就知道藏了东西。
    /// 跟 iOS 那边一样，过滤在写入这一侧做，小组件拿到什么显示什么。
    suspend fun widgetSummary(): WidgetSummary {
        val month = java.time.YearMonth.now()
        val zone = java.time.ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val items = expenses.range(from, to).visible(unlocked = false)
        val byKey = categories.all().associateBy { it.id }
        val top = items.groupBy { it.categoryKey }
            .map { (k, v) ->
                WidgetSlice(byKey[k]?.name ?: k, v.fold(BigDecimal.ZERO) { a, e -> a + e.amount })
            }
            .sortedByDescending { it.amount }
            .take(3)

        return WidgetSummary(
            monthLabel = "${month.year}年${month.monthValue}月",
            total = items.fold(BigDecimal.ZERO) { a, e -> a + e.amount },
            count = items.size,
            top = top,
        )
    }
}

data class WidgetSlice(val name: String, val amount: BigDecimal)

data class WidgetSummary(
    val monthLabel: String,
    val total: BigDecimal,
    val count: Int,
    val top: List<WidgetSlice>,
)
