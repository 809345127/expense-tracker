package com.shize.expensetracker.sync

import android.content.Context
import androidx.work.*
import com.shize.expensetracker.data.Settings
import java.util.concurrent.TimeUnit

/// 后台同步。用 WorkManager —— 这是安卓做这件事的标准答案：
/// 由系统调度、**没网就等网络回来**、app 进程被杀了任务也不丢、开机后还会接着来。
/// （iOS 那边没有对等的东西，只能在 app 活着的时候同步。）
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 真正的逻辑在 SyncRunner 里（前台轮询和下拉刷新也走它，见那边的注释）。
        // ⚠️ 这里传 recordProblems = true：后台任务失败了必须留痕给界面看，
        // 静默失败是最坏的形态 —— 两台手机数据不一样，而界面上一切正常。
        val report = SyncRunner.run(applicationContext, recordProblems = true)
        return if (report != null) {
            Result.success(workDataOf("pulled" to report.pulled, "pushed" to report.pushed))
        } else {
            // ⚠️ retry 而不是 failure：网络类问题让 WorkManager 按指数退避自己重试，
            // 判成 failure 就再也不试了。
            // ⚠️ 注意 report 为 null 也可能是「压根没配服务器」—— 那种情况重试也无害
            //（下次还是立刻返回 null），比为了区分两者把返回值搞复杂划算
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

        /// 立刻同步一次（记完一笔时调）。
        /// ⚠️ 下拉刷新**不走这里**，它直接 await SyncRunner —— 因为下拉要等出结果好收起转圈，
        /// 而 WorkManager 是"排进队列就返回"，转圈会立刻停掉、看着像没刷
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
