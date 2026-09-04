package com.shize.expensetracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.shize.expensetracker.App
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import androidx.lifecycle.viewModelScope
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseListViewModel(app: Application) : AndroidViewModel(app) {
    private val appState = App.from(app)
    private val repo = appState.repository

    /// ⚠️ 月份和解锁状态都**挂在 App 上、全进程一份**，不是这个 ViewModel 自己的。
    /// 各页各存一份会出现「明细页锁着、统计页开着」（两页总额当场对不上）
    /// 和「明细页在 7 月、统计页还在 8 月」。见 App.kt / PrivacyGate.kt 的注释。
    val month: StateFlow<YearMonth> = appState.month
    val unlocked: StateFlow<Boolean> = appState.gate.unlocked

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 当月看得见的账目。⚠️ 私密过滤在 Repository 那一处做，这里不重复
    val expenses: StateFlow<List<ExpenseEntity>> =
        combine(month, unlocked) { m, u -> m to u }
            .flatMapLatest { (m, u) ->
                val (from, to) = m.rangeMillis()
                repo.observeMonth(from, to, u)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun prevMonth() { appState.month.value = appState.month.value.minusMonths(1) }
    fun nextMonth() { appState.month.value = appState.month.value.plusMonths(1) }

    suspend fun delete(e: ExpenseEntity) = repo.deleteExpense(e)
}
