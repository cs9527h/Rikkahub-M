package me.rerere.rikkahub.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import me.rerere.rikkahub.R

/**
 * 更新包下载的前台服务：只负责保活进程与刷新通知栏进度。
 * 真正的下载在 [UpdateChecker] 的 AppScope 里跑，服务观察同一份状态，
 * 因此界面、通知栏不会出现两套进度，取消也只作用一次。
 */
class UpdateDownloadService : Service(), KoinComponent {

    private val checker: UpdateChecker by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(UpdateDownloadState.Preparing("")),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
        observer = scope.launch {
            checker.download.collect { state -> render(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            checker.cancelDownload()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun render(state: UpdateDownloadState) {
        when (state) {
            is UpdateDownloadState.Idle -> stopWithMessage("已取消更新下载")
            is UpdateDownloadState.Failed -> stopWithMessage("下载失败：" + state.message)
            is UpdateDownloadState.Ready -> {
                stopWithMessage("更新包已校验完成，点击打开 Rikkahub-M 安装")
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.let { manager ->
                        manager.notify(
                            NOTIFICATION_ID,
                            buildFinishedNotification(),
                        )
                    }
            }
            else -> {
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(state),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                }
            }
        }
    }

    private fun stopWithMessage(@Suppress("UNUSED_PARAMETER") message: String) {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: UpdateDownloadState): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(titleFor(state))
            .setContentText(textFor(state))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(launchIntent())

        when (state) {
            is UpdateDownloadState.Running -> {
                val fraction = state.fraction
                if (fraction == null) {
                    builder.setProgress(0, 0, true)
                } else {
                    builder.setProgress(100, (fraction * 100).toInt(), false)
                    builder.setSubText("${(fraction * 100).toInt()}%")
                }
            }
            else -> builder.setProgress(0, 0, true)
        }

        builder.addAction(
            0,
            "取消下载",
            PendingIntent.getService(
                this,
                ACTION_CANCEL_REQUEST,
                Intent(this, UpdateDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    private fun buildFinishedNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("更新包已就绪")
            .setContentText("点击打开 Rikkahub-M，在「关于」页继续安装")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(launchIntent())
            .build()

    private fun titleFor(state: UpdateDownloadState): String = when (state) {
        is UpdateDownloadState.Preparing -> "正在准备更新包"
        is UpdateDownloadState.Running -> "正在下载 Rikkahub-M ${state.version}"
        is UpdateDownloadState.Verifying -> "正在校验更新包"
        is UpdateDownloadState.Ready -> "更新包已就绪"
        is UpdateDownloadState.Failed -> "更新下载失败"
        UpdateDownloadState.Idle -> "更新下载"
    }

    private fun textFor(state: UpdateDownloadState): String = when (state) {
        is UpdateDownloadState.Preparing -> "正在连接下载源"
        is UpdateDownloadState.Running -> {
            if (state.fraction == null) {
                "已下载 ${formatByteSize(state.read)}"
            } else {
                "已下载 ${formatByteSize(state.read)} / ${formatByteSize(state.total)}"
            }
        }
        is UpdateDownloadState.Verifying -> "正在校验 SHA-256、包名、版本号与签名"
        is UpdateDownloadState.Ready -> "校验完成"
        is UpdateDownloadState.Failed -> state.message
        UpdateDownloadState.Idle -> ""
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val manager = NotificationManagerCompat.from(this)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "应用更新下载",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示更新包下载进度，可在此取消下载"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "update_download"
        private const val NOTIFICATION_ID = 0x0A51
        private const val ACTION_CANCEL_REQUEST = 1
        const val ACTION_CANCEL = "me.rerere.rikkahub.action.CANCEL_UPDATE_DOWNLOAD"

        fun start(context: Context) {
            val intent = Intent(context, UpdateDownloadService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
