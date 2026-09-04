package com.shize.expensetracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shize.expensetracker.App
import com.shize.expensetracker.data.CategoryEntity
import com.shize.expensetracker.data.ExpenseEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = App.from(app).repository

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    /// 私密门。⚠️ 默认锁着 —— 跟 iOS 一致
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    val categories: StateFlow<List<CategoryEntity>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /// 当月看得见的账目。⚠️ 私密过滤在 Repository 那一处做，这里不重复
    val expenses: StateFlow<List<ExpenseEntity>> =
        combine(_month, _unlocked) { m, u -> m to u }
            .flatMapLatest { (m, u) ->
                val (from, to) = m.rangeMillis()
                repo.observeMonth(from, to, u)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun prevMonth() { _month.value = _month.value.minusMonths(1) }
    fun nextMonth() { _month.value = _month.value.plusMonths(1) }
    fun setUnlocked(v: Boolean) { _unlocked.value = v }

    suspend fun delete(e: ExpenseEntity) = repo.deleteExpense(e)
}
