package com.shize.expensetracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import com.shize.expensetracker.data.ExpenseFilter
import com.shize.expensetracker.data.TagEntity
import com.shize.expensetracker.data.matching
import com.shize.expensetracker.data.tagIndex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import androidx.lifecycle.viewModelScope
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseListViewModel(app: Application) : AndroidViewModel(app) {
    private val appState = App.from(app)
    private val repo = appState.repository

    /// ⚠️ 月份、解锁状态、筛选条件都**挂在 App 上、全进程一份**，不是这个 ViewModel 自己的。
    /// 各页各存一份会出现「明细页锁着、统计页开着」（两页总额当场对不上）、
    /// 「明细页在 7 月、统计页还在 8 月」，以及统计页点一行下钻不过来。
    /// 见 App.kt / PrivacyGate.kt 的注释。
    val month: StateFlow<YearMonth> = appState.month
    val unlocked: StateFlow<Boolean> = appState.gate.unlocked
    val filter: StateFlow<ExpenseFilter> = appState.filter

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// ⚠️ 用 `observeAllTags()`（含已归档），不是 `observeTags()`：
    /// 历史账目上挂着的归档标签，行里要能显示出名字，不能因为归档了就变成一个 id
    val tags: StateFlow<List<TagEntity>> =
        repo.observeAllTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 只在筛选面板里列的标签（不含归档的）——归档的意思就是「以后不再用它打新标签」
    val activeTags: StateFlow<List<TagEntity>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 「哪笔账挂了哪些标签」的索引。压成 Map 之后每笔账查标签是 O(1)
    val tagsByExpense: StateFlow<Map<String, Set<String>>> =
        repo.observeLinks()
            .map { tagIndex(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /// 当月**过完私密门**的账目（还没过筛选）。
    ///
    /// ⚠️⚠️ 筛选面板上的「几笔」和总额都要从这一份算，不是从筛完的那份 ——
    /// 也不能自己去查库。私密过滤在 Repository 那一处做，这里不重复。
    val monthExpenses: StateFlow<List<ExpenseEntity>> =
        combine(month, unlocked) { m, u -> m to u }
            .flatMapLatest { (m, u) ->
                val (from, to) = m.rangeMillis()
                repo.observeMonth(from, to, u)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 列表真正显示的那一批：私密门 → 筛选，**顺序不能反**。
    /// 私密门是最外层，任何筛选都不能把被藏起来的记录放出来。
    val expenses: StateFlow<List<ExpenseEntity>> =
        combine(monthExpenses, filter, tagsByExpense) { list, f, idx -> list.matching(f, idx) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun prevMonth() { appState.month.value = appState.month.value.minusMonths(1) }
    fun nextMonth() { appState.month.value = appState.month.value.plusMonths(1) }
    fun setFilter(f: ExpenseFilter) { appState.filter.value = f }

    suspend fun delete(e: ExpenseEntity) = repo.deleteExpense(e)
}
