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
import me.rerere.rikkahub.data.sync.AutoBackupPolicy
import me.rerere.rikkahub.data.sync.BackupKind
import me.rerere.rikkahub.data.sync.BackupPassphraseStore
import me.rerere.rikkahub.data.sync.EncryptedBackupSession
import me.rerere.rikkahub.data.sync.MissingBackupPassphraseException
import me.rerere.rikkahub.data.sync.S3Sync
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
        var hadFailure = false
        try {
            EncryptedBackupSession.run(passphraseStore::read) { passphrase ->
                keyAvailable = true
                val includeCredentials = settings.backupReminderConfig.autoBackupIncludeCredentials
                for (kind in listOf(BackupKind.SETTINGS, BackupKind.CHATS)) {
                    val hours = when (kind) {
                        BackupKind.SETTINGS -> settings.backupReminderConfig.autoSettingsIntervalHours
                        BackupKind.CHATS -> settings.backupReminderConfig.autoChatsIntervalHours
                        BackupKind.FULL -> error("自动备份不使用整包策略")
                    }
                    for (transport in listOf("webdav", "s3")) {
                        val ready = if (transport == "webdav") davReady else s3Ready
                        if (!ready) continue
                        try {
                            val stateKey = AutoBackupPolicy.key(transport, kind)
                            val currentState =
                                settingsStore.awaitLoaded().backupReminderConfig.autoBackupStates[stateKey]
                            val destinationId = if (transport == "webdav") {
                                AutoBackupPolicy.destinationId("webdav", dav.url, dav.path, dav.username)
                            } else {
                                AutoBackupPolicy.destinationId(
                                    "s3",
                                    s3.endpoint,
                                    s3.bucket,
                                    s3.region,
                                    s3.pathStyle.toString(),
                                    s3.accessKeyId,
                                )
                            }
                            val checkedAt = System.currentTimeMillis()
                            if (currentState?.destinationId == destinationId &&
                                !AutoBackupPolicy.isDue(currentState, kind, checkedAt, hours)
                            ) {
                                continue
                            }
                            val fingerprint = if (transport == "webdav") {
                                webDavSync.backupAutomatically(dav, passphrase, includeCredentials, kind, currentState)
                            } else {
                                s3Sync.backupAutomatically(s3, passphrase, includeCredentials, kind, currentState)
                            }
                            settingsStore.update { current ->
                                val config = current.backupReminderConfig
                                val latest = config.autoBackupStates[stateKey]
                                val updated = if (fingerprint == null) {
                                    AutoBackupPolicy.checked(latest, checkedAt)
                                } else {
                                    AutoBackupPolicy.uploaded(latest, checkedAt, fingerprint, destinationId)
                                }
                                current.copy(backupReminderConfig = config.copy(
                                    autoBackupStates = config.autoBackupStates + (stateKey to updated),
                                    lastBackupTime = if (fingerprint == null) config.lastBackupTime else checkedAt,
                                    lastAutoBackupTime =
                                    if (fingerprint == null) config.lastAutoBackupTime else checkedAt,
                                    lastAutoBackupError = if (fingerprint == null) config.lastAutoBackupError else "",
                                ))
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // One unreachable destination or type must not cancel the others.
                            hadFailure = true
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: MissingBackupPassphraseException) {
            recordFailure("请先在备份页设置并安全保存自动备份口令")
            return Result.failure()
        } catch (_: Exception) {
            // Never persist provider exceptions, URLs or authentication material in settings/logs.
            recordFailure(
                if (keyAvailable) "网络或备份存储暂不可用" else "本机备份口令不可用，请重新设置",
            )
            return if (keyAvailable && runAttemptCount < 2) Result.retry() else Result.failure()
        }
        if (hadFailure) {
            recordFailure("部分备份目标暂不可用，稍后重试")
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
        return Result.success()
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
