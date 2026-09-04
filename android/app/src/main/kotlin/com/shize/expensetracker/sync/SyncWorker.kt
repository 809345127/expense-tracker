package com.shize.expensetracker.sync

import android.content.Context
import androidx.work.*
import com.shize.expensetracker.data.AppDatabase
import com.shize.expensetracker.data.Settings
import com.shize.expensetracker.widget.WidgetRefresh
import java.util.concurrent.TimeUnit

/// 后台同步。用 WorkManager —— 这是安卓做这件事的标准答案：
/// 由系统调度、**没网就等网络回来**、app 进程被杀了任务也不丢、开机后还会接着来。
/// （iOS 那边没有对等的东西，只能在 app 活着的时候同步。）
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        val api = Network.api(settings) ?: return Result.success()  // 还没配服务器，不算失败
        return try {
            val report = SyncEngine(api, AppDatabase.get(applicationContext), settings).syncOnce()
            if (report.stale.isEmpty()) settings.recordSuccess()
            // ⚠️ 有 stale 就**不算干净成功**：那几条改动已经永久没了，
            // 必须在同步设置页里留下痕迹，否则两台手机数据不一样而界面上一切正常
            else settings.recordFailure(staleMessage(report.stale.size))
            // ⚠️ 拉到新数据也要刷桌面小组件：另一台手机上记的账，本机界面靠 Room 的 Flow
            // 自动更新，但小组件不在 Compose 里、不会自己动 —— 不刷的话桌面上那个数会
            // 一直是上次的值，而它跟 app 里对不上本身就是个隐私漏洞（能看出差了几笔）
            if (report.pulled > 0) WidgetRefresh.request(applicationContext)
            Result.success(workDataOf("pulled" to report.pulled, "pushed" to report.pushed))
        } catch (e: Exception) {
            // ⚠️ 失败要留痕给界面看。静默失败是最坏的形态：
            // 两台手机数据不一样，而界面上一切正常
            settings.recordFailure(e.message ?: e.javaClass.simpleName)
            // ⚠️ retry 而不是 failure：网络类问题让 WorkManager 按指数退避自己重试，
            // 判成 failure 就再也不试了
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        /// ⚠️ 两个调用点（后台任务 / 设置页里手动点）用**同一句话**，
        /// 免得同一件事在两个地方说法不一样
        fun staleMessage(n: Int) =
            "有 $n 条改动被服务器当成旧数据丢掉了 —— 几乎肯定是这台设备的时间比另一台慢。" +
                    "去两台手机的「设置 → 日期与时间」都打开自动校准，然后重新记一次那几笔。"

        private const val PERIODIC = "sync-periodic"
        private const val ONESHOT = "sync-now"

        /// 周期同步。⚠️ 系统允许的最小周期是 15 分钟，写更小的值会被静默改成 15
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                // KEEP：已经排上了就别重排，否则每次开 app 都把周期重置、永远等不到那 15 分钟
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /// 立刻同步一次（记完一笔、或者用户下拉刷新时调）
        fun syncNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT,
                // APPEND_OR_REPLACE：连着记几笔时排队执行，不要互相取消
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                req,
            )
        }
    }
}
