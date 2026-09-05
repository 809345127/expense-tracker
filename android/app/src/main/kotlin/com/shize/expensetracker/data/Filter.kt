package com.shize.expensetracker.data

// 筛选条件（分类 + 标签，全 app 只有这一套）。
//
// **对位 iOS 的 `ExpenseFilter`（Models.swift），三条口径一字不差** —— 改这里必须同时改那边，
// 不然同一批数据在两台手机上筛出来的结果会不一样，而这种不一致没有任何报错。
//
// 口径（这三条是刻意选的，改之前先想清楚）：
// 1. **分类之间是「或」**：选了餐饮 + 交通，两类都算。跟标签一致。
// 2. **分类和标签之间是「且」**：既要是餐饮、又要带「咖啡」标签。
//    因为这两个维度回答的是不同问题（钱花在什么事上 / 属于哪次活动），叠加才有意义。
// 3. **一笔记录只算一次钱**：多个标签同时命中同一笔，也只计入一次。
//    实现上是「按记录过滤」而不是「按维度遍历累加」，去重是免费的。
//
// ⚠️ 不管是在明细页的筛选面板里选的，还是在统计页点某一行进来的，落到的都是这**一个**
// ExpenseFilter —— 所以不会出现「两种筛选态」互相打架。
data class ExpenseFilter(
    /// 存的是分类**代号**（`CategoryEntity.id` / `ExpenseEntity.categoryKey`），不是显示名。
    /// 代号是纯字符串：改名不影响它，分类被删之后残留在条件里也只是筛不出东西，不会崩。
    val categoryKeys: Set<String> = emptySet(),
    /// 标签 id（UUID）。同理，标签改名不影响，被删了最多筛不出东西
    val tagIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = categoryKeys.isEmpty() && tagIds.isEmpty()

    fun toggleCategory(key: String) = copy(
        categoryKeys = if (key in categoryKeys) categoryKeys - key else categoryKeys + key
    )

    fun toggleTag(id: String) = copy(
        tagIds = if (id in tagIds) tagIds - id else tagIds + id
    )

    companion object {
        val none = ExpenseFilter()

        /// 统计页点某一行时用：把条件**换成**「只看这一个」，而不是往上叠加。
        /// 点第二个分类应该是「改看那个」，不是「两个都要」—— 要两个都要就去筛选面板里选。
        /// （跟 iOS 的 `ExpenseFilter.only(categoryKey:)` 同一个决定）
        fun onlyCategory(key: String) = ExpenseFilter(categoryKeys = setOf(key))
        fun onlyTag(id: String) = ExpenseFilter(tagIds = setOf(id))
    }
}

/// 「哪笔账挂了哪些标签」的现成索引。
///
/// ⚠️ iOS 那边 `Expense.tags` 是 SwiftData 的关系、随手就能取；安卓这边关联是**独立的一张表**
/// （`link`），所以要先把它压成 `账目id -> 标签id 集合` 再用。压这一次是 O(关联数)，
/// 之后每笔账查标签都是 O(1) —— 别在过滤的循环里对 links 做 filter，那是 O(N×M)。
///
/// ⚠️ 传进来的 links **必须已经滤掉墓碑**（DAO 的 observeAll 已经带了 `deleted = 0`）。
fun tagIndex(links: List<LinkEntity>): Map<String, Set<String>> =
    links.groupBy { it.expenseId }.mapValues { (_, v) -> v.map { it.tagId }.toSet() }

/// 按筛选条件过滤。分类内并集、标签内并集、两者之间交集（口径见文件头）。
///
/// ⚠️⚠️ **调用方必须先过私密门（Repository.visible）再调这个，顺序不能反。**
/// 私密门是最外层，任何筛选都不能把被藏起来的记录放出来。
/// ⚠️ 参数故意不叫 `filter`：那会盖掉集合自己的 `filter {}`。
fun List<ExpenseEntity>.matching(
    f: ExpenseFilter,
    tagsByExpense: Map<String, Set<String>>,
): List<ExpenseEntity> {
    if (f.isEmpty) return this
    return this.filter { e ->
        // 空集合 = 这个维度不筛（不是「一条都不要」）。这是 iOS 那边 matchingAny 的同一条约定
        val catOk = f.categoryKeys.isEmpty() || e.categoryKey in f.categoryKeys
        val tagOk = f.tagIds.isEmpty() ||
                (tagsByExpense[e.id] ?: emptySet()).any { it in f.tagIds }
        catOk && tagOk
    }
}
