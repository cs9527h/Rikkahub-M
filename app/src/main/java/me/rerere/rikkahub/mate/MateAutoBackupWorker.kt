package me.rerere.rikkahub.mate

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Opt-in lifecycle backup.
 * Reuses the app's existing archive format (settings, conversation DB, files) and
 * persistently schedules work instead of relying on an onStop network coroutine.
 */
class MateAutoBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val webDavSync: WebDavSync by inject()
    private val s3Sync: S3Sync by inject()

    override suspend fun doWork(): Result {
        val settings = settingsStore.awaitLoaded()
        if (!settings.backupReminderConfig.autoBackupOnAppLifecycle) return Result.success()

        // Only send archives (which contain settings and chat history) over HTTPS.
        val dav = settings.webDavConfig
        val s3 = settings.s3Config
        val davReady = dav.url.startsWith("https://") &&
            dav.username.isNotBlank() && dav.password.isNotBlank()
        val s3Ready = s3.endpoint.startsWith("https://") &&
            s3.bucket.isNotBlank() && s3.accessKeyId.isNotBlank() &&
            s3.secretAccessKey.isNotBlank()
        if (!davReady && !s3Ready) return Result.failure()

        try {
            if (davReady) {
                webDavSync.backup(
                    dav.copy(items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES))
                )
            }
            if (s3Ready) {
                s3Sync.backupToS3(
                    s3.copy(items = listOf(S3Config.BackupItem.DATABASE, S3Config.BackupItem.FILES))
                )
            }
            settingsStore.update { current ->
                current.copy(
                    backupReminderConfig = current.backupReminderConfig.copy(
                        lastBackupTime = System.currentTimeMillis()
                    )
                )
            }
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "rikkahub_mate_full_auto_backup"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<MateAutoBackupWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            // Foreground/background transitions during a large upload share its running snapshot.
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.KEEP, work
            )
        }
    }
}
