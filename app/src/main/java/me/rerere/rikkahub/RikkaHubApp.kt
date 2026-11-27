package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.AvatarStorage
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.remote.ModelsDevUpdater
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.RestoreFailedException
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.mate.MateAutoBackupWorker
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.ui.hooks.warmUpRikahubPreferences
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.deleteTreeNoFollow
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import java.io.File

private const val TAG = "RikkaHubApp"

/** 首帧之后再开始清理类维护，避免冷启动时与首屏抢 IO。 */
private const val STARTUP_MAINTENANCE_DELAY_MS = 1_200L

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val MEDIA_CREATION_NOTIFICATION_CHANNEL_ID = "media_creation"
const val MEMORY_DREAM_NOTIFICATION_CHANNEL_ID = "memory_dream"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore files and settings before eager Koin singletons or workers can access them.
        // 没有 backup-restore 目录时（绝大多数启动）直接跳过：这里在主线程上 runBlocking，
        // 哪怕只是两次 stat，也会在冷启动阶段和白屏抢时间片。
        val restoreRoot = File(noBackupFilesDir, "backup-restore")
        if (restoreRoot.isDirectory) {
            try {
                val restored = runBlocking(Dispatchers.IO) {
                    BackupManager.applyPendingRestore(this@RikkaHubApp, JsonInstant)
                }
                if (restored) {
                    Toast.makeText(this, R.string.backup_page_restore_success, Toast.LENGTH_LONG).show()
                }
            } catch (e: RestoreFailedException) {
                Log.e(TAG, "Backup restore rolled back", e)
                Toast.makeText(this, "备份恢复失败，已保留原数据。请重新导入备份。", Toast.LENGTH_LONG).show()
            }
        }
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // Retired Mate databases and prior local backups are user data, not dead code.
        // Do not open them for chat, but never delete them implicitly during an upgrade.

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // 冷启动阶段只做必须抢在首帧之前的事；清理类任务统一推迟到首帧之后串行执行，
        // 否则十几个协程同时读写磁盘/数据库，会把白屏和首屏列表都拖慢。
        get<AvatarStorage>().start(get<AppScope>())
        startDeferredMaintenance()

        // 预热 SharedPreferences，避免首屏读偏好时在主线程做第一次磁盘读
        get<AppScope>().launch(Dispatchers.IO) { warmUpRikahubPreferences() }

        // Load models.dev data for model capability matching, then refresh it in background
        updateModelsDev()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Resume media generations interrupted by the last process death
        resumeMediaCreations()
        resumeMemoryConsolidation()
        get<AppScope>().launch(Dispatchers.IO) {
            try {
                get<HistoryMemoryManager>().resumePending()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(TAG, "Could not schedule pending history extraction")
            }
        }
        observeMateAutoBackup()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun resumeMediaCreations() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MediaCreationService>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMediaCreations failed", it)
            }
        }
    }

    private fun resumeMemoryConsolidation() {
        // 应用被切走后进程随时可能被冻结，等不到空闲计时结束，这时直接开始整理
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) get<MemoryConsolidationScheduler>().onAppHidden()
            }
        )
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MemoryConsolidationScheduler>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMemoryConsolidation failed", it)
            }
        }
    }

    /**
     * ON_STOP means the app goes to background (not necessarily a guaranteed process exit).
     * WorkManager persists pending work even if Android stops the app shortly afterward.
     */
    private fun observeMateAutoBackup() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) {
                    val settings = get<SettingsStore>().settingsFlow.value
                    if (!settings.init && settings.backupReminderConfig.autoBackupOnAppLifecycle) {
                        MateAutoBackupWorker.schedule(this@RikkaHubApp)
                    }
                }
            }
        )
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val count = get<SettingsStore>().incrementLaunchCount()
                Log.i(TAG, "incrementLaunchCount: $count")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    /** 首帧之后串行执行的启动维护：清理临时文件、校准文件表、解压内置技能。 */
    private fun startDeferredMaintenance() {
        get<AppScope>().launch(Dispatchers.IO) {
            delay(STARTUP_MAINTENANCE_DELAY_MS)
            runMaintenanceStep("incrementLaunchCount") { get<SettingsStore>().incrementLaunchCount() }
            runMaintenanceStep("deleteTempFiles") { deleteTempFilesNow() }
            runMaintenanceStep("cleanupToolOutputs") { cleanupToolOutputsNow() }
            runMaintenanceStep("cleanupWorkspaceTempDirs") { get<WorkspaceManager>().cleanupAllTempDirs() }
            runMaintenanceStep("checkWorkspaceIntegrity") { get<WorkspaceRepository>().checkIntegrity() }
            runMaintenanceStep("syncManagedFiles") { get<FilesManager>().syncFolder() }
            runMaintenanceStep("extractBuiltinSkills") { get<SkillManager>().ensureBuiltinSkillsExtracted() }
        }
    }

    /** 单个维护步骤失败不影响后续步骤，也不应该把异常抛到 AppScope。 */
    private suspend fun runMaintenanceStep(name: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "startup maintenance step $name failed", it) }
    }

    private fun deleteTempFilesNow() {
        val dir = appTempFolder
        if (dir.exists()) {
            deleteTreeNoFollow(dir)
        }
    }

    private fun cleanupToolOutputsNow() {
        val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
        if (dir.exists()) {
            // 工具输出目录里可能有指向私有数据的软链，递归删除不能跟随。
            deleteTreeNoFollow(dir)
        }
    }

    private fun updateModelsDev() {
        get<AppScope>().launch {
            get<ModelsDevUpdater>().start()
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().awaitLoaded()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val mediaCreationChannel = NotificationChannelCompat
            .Builder(MEDIA_CREATION_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(getString(R.string.media_creation_title))
            .build()
        notificationManager.createNotificationChannel(mediaCreationChannel)

        val memoryDreamChannel = NotificationChannelCompat
            .Builder(MEMORY_DREAM_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.memory_dream_title))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(memoryDreamChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob() +
        Dispatchers.Main +
        CoroutineName("AppScope") +
        CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "AppScope exception", e)
        }
)
