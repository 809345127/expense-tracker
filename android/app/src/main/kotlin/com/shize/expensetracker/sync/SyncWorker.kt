package com.shize.expensetracker.sync

import android.content.Context
import androidx.work.*
import com.shize.expensetracker.data.AppDatabase
import com.shize.expensetracker.data.Settings
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
            settings.recordSuccess()
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
