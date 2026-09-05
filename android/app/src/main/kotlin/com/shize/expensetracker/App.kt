package com.shize.expensetracker

import android.app.Application
import com.shize.expensetracker.data.AppDatabase
import com.shize.expensetracker.data.ExpenseFilter
import com.shize.expensetracker.data.Repository
import com.shize.expensetracker.data.Settings
import com.shize.expensetracker.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.YearMonth

/// 整个 app 的入口 + 依赖容器。
///
/// 依赖在这里 lazy 组装好，界面通过 `App.from(context)` 取。
/// 不引 Hilt：这个 app 只有三四个依赖，注解处理器带来的构建复杂度不值得
/// —— 代价是自己写这几行 lazy，收益是构建快、出错时堆栈干净。
class App : Application() {
    val database by lazy { AppDatabase.get(this) }
    val settings by lazy { Settings(this) }
    val repository by lazy { Repository(this, database) }

    /// ⚠️ 私密门是**全进程唯一一份**。原因见 PrivacyGate 的注释：
    /// 每个页面各存一份的话会出现「明细页锁着、统计页开着」，两页总额当场对不上。
    val gate by lazy { PrivacyGate() }

    /// 当前在看哪个月。**明细页和统计页共用这一份** —— 对位 iOS 那边把 month
    /// 提到 App 级当 `@Binding` 传下去的做法。各存一份的话，在明细页翻到 7 月、
    /// 切到统计页却还是 8 月，两个页面对不上。
    val month = MutableStateFlow(YearMonth.now())

    /// 当前的筛选条件（分类 + 标签）。**全 app 只有这一套**，跟 month 一样提到这里。
    ///
    /// 为什么不放在明细页的 ViewModel 里：统计页点某一行要能**下钻**到明细
    /// —— 那个动作就是「往这一套条件里填值，然后切到明细 tab」。
    /// 条件存在明细页自己身上的话，统计页够不着它，就得再造一套传递机制。
    /// 对位 iOS：那边 `filter` 提在根视图上、用 `@Binding` 传给两页。
    val filter = MutableStateFlow(ExpenseFilter.none)

    override fun onCreate() {
        super.onCreate()
        // 周期同步排上。⚠️ 用户手机是 vivo（OriginOS），这类 ROM 杀后台很凶，
        // 这个 15 分钟的任务很可能不会按时跑 —— 所以它只是兜底，
        // 真正靠得住的是「开 app 同步一次 + 每次写入之后同步一次 + 手动下拉」。
        SyncWorker.schedulePeriodic(this)
        // 开 app 就同步一次
        SyncWorker.syncNow(this)
    }

    companion object {
        fun from(context: android.content.Context) = context.applicationContext as App
    }
}
