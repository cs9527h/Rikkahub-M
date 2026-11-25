package me.rerere.rikkahub.mate

import android.content.Context
import android.util.Log
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

        var keyAvailable = true
        var hadFailure = false
        try {
            // 与上游一致：备份不再加密，不生成也不读取任何口令/密钥。
            // 数据安全交给传输层（WebDAV/S3 都要求 https）与设备本身。
            run {
                // 与手动备份一致：凭据进备份，恢复后不用重配；Web 服务口令仍会被清空
                val includeCredentials = true
                for (kind in listOf(BackupKind.SETTINGS, BackupKind.CHATS)) {
                    val hours = when (kind) {
                        BackupKind.SETTINGS -> settings.backupReminderConfig.autoSettingsIntervalHours
                        BackupKind.CHATS -> settings.backupReminderConfig.autoChatsIntervalHours
                        BackupKind.FULL -> error("自动备份不使用整包策略")
                    }
                    for (transport in listOf("webdav", "s3")) {
                        val ready = if (transport == "webdav") davReady else s3Ready
                        if (!ready) continue
                        val stateKey = AutoBackupPolicy.key(transport, kind)
                        val checkedAt = System.currentTimeMillis()
                        try {
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
                            if (currentState?.destinationId == destinationId &&
                                !AutoBackupPolicy.isDue(currentState, kind, checkedAt, hours)
                            ) {
                                continue
                            }
                            val fingerprint = if (transport == "webdav") {
                                webDavSync.backupAutomatically(dav, includeCredentials, kind, currentState)
                            } else {
                                s3Sync.backupAutomatically(s3, includeCredentials, kind, currentState)
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
                                    // 走到这里说明这次检查成功了：旧的错误提示必须清掉，
                                    // 哪怕内容和上次一致（fingerprint == null）也不能留着。
                                    lastAutoBackupError = "",
                                ))
                            }
                            if (fingerprint != null) {
                                val keep = settings.backupReminderConfig.autoRetainVersions
                                if (keep > 0) {
                                    // Retention failures must not turn a successful backup into a retry.
                                    runCatching {
                                        if (transport == "webdav") {
                                            webDavSync.trimAutomaticBackups(dav, kind, keep)
                                        } else {
                                            s3Sync.trimAutomaticBackups(s3, kind, keep)
                                        }
                                    }.onFailure {
                                        if (it is CancellationException) throw it
                                        // Transport exceptions may contain signed URLs or credentials.
                                        Log.w("MateAutoBackup", "旧版本清理失败")
                                    }
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // One unreachable destination or type must not cancel the others.
                            hadFailure = true
                            // 失败也要推进「检查时间」：isDue 只看 lastCheckedAt，不写就等于永远到期，
                            // 每次切前后台都会重新打包整份备份并上传（网络不通时就是持续的 IO 风暴）。
                            runCatching {
                                settingsStore.update { current ->
                                    val config = current.backupReminderConfig
                                    current.copy(
                                        backupReminderConfig = config.copy(
                                            autoBackupStates = config.autoBackupStates +
                                                (stateKey to AutoBackupPolicy.checked(
                                                    config.autoBackupStates[stateKey],
                                                    checkedAt,
                                                )),
                                            lastAutoBackupError = "网络或备份存储暂不可用",
                                        )
                                    )
                                }
                            }.onFailure { Log.w("MateAutoBackup", "记录备份检查时间失败", it) }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Never persist provider exceptions, URLs or authentication material in settings/logs.
            recordFailure(
                if (keyAvailable) "网络或备份存储暂不可用" else "本机备份密钥不可用",
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
