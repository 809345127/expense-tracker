package com.shize.expensetracker.sync

import android.content.Context
import com.shize.expensetracker.data.AppDatabase
import com.shize.expensetracker.data.Settings
import com.shize.expensetracker.widget.WidgetRefresh
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/// 真正跑一次同步的**唯一**地方。四个入口共用：
///
///   1. `SyncWorker` 的 15 分钟周期任务（后台兜底）
///   2. `SyncWorker` 的一次性任务（记完一笔立刻推）
///   3. **前台轮询**（`MainActivity` 里那个循环，2026-09-05 加的）
///   4. **下拉刷新**（明细页，2026-09-05 加的）
///
/// 之前这段逻辑长在 `SyncWorker.doWork` 里，只有 1、2 两个入口。加轮询和下拉之后
/// 必须提出来，否则同一段「跑一次 + 记结果 + 刷小组件」会被抄成三份。
object SyncRunner {

    /// ⚠️ 全进程一把锁。
    ///
    /// 按协议设计，两次同步同时跑其实**不会出错**（重推同一批是空操作、
    /// 游标竞态最多导致下次多拉一遍）—— 这是那套 id + updated_at + 墓碑的好处。
    /// 但没必要，而且日志会很乱、`lastError` 会被互相覆盖。所以还是串起来。
    ///
    /// ⚠️ WorkManager 的 Worker 默认跑在 **app 主进程**里，所以这把对象级的锁
    /// 确实能同时管住 Worker 和前台轮询。要是哪天把 Worker 配成独立进程，这条就不成立了。
    private val lock = Mutex()

    /// 跑一次。返回 null 表示「压根没跑」（没配服务器）或者失败。
    ///
    /// @param recordProblems 失败要不要写进 `lastError`（同步设置页会显示它）。
    ///   ⚠️ **前台轮询必须传 false。** 轮询 30 秒一次，没网的时候会一直失败 ——
    ///   写进去的话「上次的问题」会被刷成噪音，而那一行是留给
    ///   「我主动点了同步，结果出了什么事」的。用户主动触发的（下拉刷新、
    ///   设置页那两个按钮）才传 true。
    suspend fun run(context: Context, recordProblems: Boolean): SyncEngine.Report? = lock.withLock {
        val app = context.applicationContext
        val settings = Settings(app)
        val api = Network.api(settings) ?: return@withLock null   // 还没配服务器，不算失败
        try {
            val report = SyncEngine(api, AppDatabase.get(app), settings).syncOnce()
            if (report.stale.isEmpty()) {
                settings.recordSuccess()
            } else {
                // ⚠️ 有 stale 就**不算干净成功**：那几条改动已经永久没了，
                // 必须留下痕迹，否则两台手机数据不一样而界面上一切正常。
                // ⚠️ 这一条**连轮询也要记**（无视 recordProblems）—— 它不是「网络不好」，
                // 是真的丢了数据，比噪音重要得多。
                settings.recordFailure(SyncWorker.staleMessage(report.stale.size))
            }
            // ⚠️ 拉到新数据要刷桌面小组件：另一台手机上记的账，本机界面靠 Room 的 Flow
            // 自动更新，但小组件不在 Compose 里、不会自己动 —— 不刷的话桌面上那个数会
            // 一直是上次的值，而它跟 app 里对不上本身就是个隐私漏洞（能看出差了几笔）
            if (report.pulled > 0) WidgetRefresh.request(app)
            report
        } catch (e: Exception) {
            if (recordProblems) settings.recordFailure(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /// 前台轮询的间隔。
    ///
    /// 30 秒是这么定的：
    /// - **代价可忽略**：增量拉取在「没有新东西」时服务端只回一个 `{"rev":N}`，
    ///   几十字节。前台本来就亮着屏，这点流量和唤醒不值一提。
    /// - **再短没意义**：真正的痛点是「在另一台手机上记完，想立刻在这台看到」，
    ///   而人从记完到切过来看，本身就要几秒到几十秒。
    /// - **再长就够呛人了**：一分钟以上会让人怀疑「是不是没同步」而去手动点。
    ///
    /// ⚠️ 想要「秒级」得改成长轮询（客户端带 `wait=30`、服务端 hold 住），
    /// 那要动服务端。现在这一档是零服务端改动的方案。
    const val FOREGROUND_POLL_MS = 30_000L
}
