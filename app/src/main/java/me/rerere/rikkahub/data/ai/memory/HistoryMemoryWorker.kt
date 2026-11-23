package me.rerere.rikkahub.data.ai.memory

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class HistoryMemoryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params), KoinComponent {
    private val history: HistoryMemoryManager by inject()

    override suspend fun doWork(): Result {
        val scope = inputData.getString("scope") ?: return Result.failure()
        val id = inputData.getString("id") ?: return Result.failure()
        return if (history.run(scope, id, inputData.getLong("generation", 0))) Result.retry() else Result.success()
    }

    companion object {
        private fun name(scope: String) = "history-memory-$scope"

        fun enqueue(context: Context, scope: String, id: String, generation: Long, policy: ExistingWorkPolicy) =
            WorkManager.getInstance(context).enqueueUniqueWork(
                name(scope), policy,
                OneTimeWorkRequestBuilder<HistoryMemoryWorker>()
                    .setInputData(workDataOf("scope" to scope, "id" to id, "generation" to generation))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                    .build(),
            )

        fun cancel(context: Context, scope: String) =
            WorkManager.getInstance(context).cancelUniqueWork(name(scope))

        /**
         * 这个记忆库是否真的还有排队的整理任务。
         * 数据库里残留的 “running” 不等于任务还活着：进程被杀、系统清理 WorkManager 之后
         * 状态会永远卡在运行中，导致用户再点整理只看到“已有任务正在运行”。
         */
        fun isActive(context: Context, scope: String): Boolean = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(name(scope)).get()
                .any {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
        }.getOrDefault(false)
    }
}
