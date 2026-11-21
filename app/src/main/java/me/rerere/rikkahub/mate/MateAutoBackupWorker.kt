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
import me.rerere.rikkahub.data.sync.BackupPassphraseStore
import me.rerere.rikkahub.data.sync.EncryptedBackupSession
import me.rerere.rikkahub.data.sync.MissingBackupPassphraseException
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Opt-in, uniquely scheduled encrypted backup. Never falls back to a plaintext upload. */
class MateAutoBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val webDavSync: WebDavSync by inject()
    private val s3Sync: S3Sync by inject()
    private val passphraseStore: BackupPassphraseStore by inject()

    override suspend fun doWork(): Result {
        val settings = settingsStore.awaitLoaded()
        if (!settings.backupReminderConfig.autoBackupOnAppLifecycle) return Result.success()
        val dav = settings.webDavConfig
        val s3 = settings.s3Config
        val davReady = dav.url.startsWith("https://") &&
            dav.username.isNotBlank() && dav.password.isNotBlank()
        val s3Ready = s3.endpoint.startsWith("https://") &&
            s3.bucket.isNotBlank() && s3.accessKeyId.isNotBlank() && s3.secretAccessKey.isNotBlank()
        if (!davReady && !s3Ready) {
            recordFailure("未配置可用的 HTTPS 备份存储")
            return Result.failure()
        }

        var keyAvailable = false
        try {
            EncryptedBackupSession.run(passphraseStore::read) { passphrase ->
                keyAvailable = true
                val includeCredentials = settings.backupReminderConfig.autoBackupIncludeCredentials
                if (davReady) {
                    webDavSync.backup(
                        // Large attachments are explicitly selected only for manual backups.
                        dav.copy(items = listOf(WebDavConfig.BackupItem.DATABASE)),
                        passphrase = passphrase,
                        includeCredentials = includeCredentials,
                    )
                }
                if (s3Ready) {
                    s3Sync.backupToS3(
                        s3.copy(items = listOf(S3Config.BackupItem.DATABASE)),
                        passphrase = passphrase,
                        includeCredentials = includeCredentials,
                    )
                }
            }
            settingsStore.update { current ->
                val now = System.currentTimeMillis()
                current.copy(backupReminderConfig = current.backupReminderConfig.copy(
                    lastBackupTime = now,
                    lastAutoBackupTime = now,
                    lastAutoBackupError = "",
                ))
            }
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: MissingBackupPassphraseException) {
            recordFailure("请先在备份页设置并安全保存自动备份口令")
            return Result.failure()
        } catch (_: Exception) {
            // Never persist provider exceptions, URLs or authentication material in settings/logs.
            recordFailure(if (keyAvailable) "网络或备份存储暂不可用" else "本机备份口令不可用，请重新设置")
            return if (keyAvailable && runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private suspend fun recordFailure(message: String) {
        settingsStore.update { current ->
            current.copy(backupReminderConfig = current.backupReminderConfig.copy(lastAutoBackupError = message))
        }
    }

    companion object {
        private const val UNIQUE_WORK = "rikkahub_mate_full_auto_backup"

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val work = OneTimeWorkRequestBuilder<MateAutoBackupWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                work
            )
        }
    }
}
