package com.shize.expensetracker

import android.app.Application
import com.shize.expensetracker.data.AppDatabase
import com.shize.expensetracker.data.Repository
import com.shize.expensetracker.data.Settings
import com.shize.expensetracker.sync.SyncWorker

/// 整个 app 的入口 + 依赖容器。
///
/// 依赖在这里 lazy 组装好，界面通过 `App.from(context)` 取。
/// 不引 Hilt：这个 app 只有三四个依赖，注解处理器带来的构建复杂度不值得
/// —— 代价是自己写这几行 lazy，收益是构建快、出错时堆栈干净。
class App : Application() {
    val database by lazy { AppDatabase.get(this) }
    val settings by lazy { Settings(this) }
    val repository by lazy { Repository(this, database) }

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
