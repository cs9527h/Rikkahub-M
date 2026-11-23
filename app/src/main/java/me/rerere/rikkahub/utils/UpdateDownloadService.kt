package me.rerere.rikkahub.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 更新包下载的前台服务：只负责保活进程与刷新通知栏进度。
 * 真正的下载在 [UpdateChecker] 的 AppScope 里、Dispatchers.IO 上跑，服务观察同一份状态，
 * 因此界面、通知栏不会出现两套进度，取消也只作用一次。
 *
 * 线程约定：Service 的 onCreate/onStartCommand/collect 全在主线程，这里**只允许**
 * binder 调用（startForeground、NotificationManager、PackageManager）与状态渲染，
 * 不许出现网络、文件读写或大对象哈希；那些一律留在 UpdateChecker 的 IO 段。
 * 这条约定由 UpdateMainThreadSourceGuardTest 检查。
 *
 * 通知权限被拒绝、startForeground 被系统拒绝等失败一律记录并回报界面，
 * 不再用 runCatching 静默吞掉，也不会继续提示「请在状态栏查看进度」。
 */
class UpdateDownloadService : Service(), KoinComponent {

    private val checker: UpdateChecker by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(UpdateDownloadState.Preparing("")),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            foregroundStarted = true
        } catch (e: Exception) {
            // 常见原因：Android 14+ 前台服务类型权限缺失、系统限制后台启动、通知被完全关闭
            Log.w(TAG, "startForeground 失败，改为仅应用内显示进度", e)
            checker.reportNotificationProblem("系统拒绝显示下载通知（${e.javaClass.simpleName}），进度仅在应用内显示")
            stopSelf()
            return
        }
        if (!notificationsEnabled()) {
            checker.reportNotificationProblem("通知权限未开启，下载进度仅在应用内显示")
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
        if (!foregroundStarted) return
        when (state) {
            is UpdateDownloadState.Idle -> finish("更新下载已取消", "需要时可以重新下载，已下载的部分会尽量续传", ongoing = false)
            is UpdateDownloadState.Failed -> finish("更新下载失败", state.message, ongoing = false)
            is UpdateDownloadState.Ready -> finish(
                "更新包已校验完成",
                "打开 Rikkahub-M，在「关于」页点击安装",
                ongoing = false,
            )
            else -> post(buildNotification(state))
        }
    }

    /** 结束后保留一条可点开的通知（持续的进度通知换成结果通知），再停止前台。 */
    private fun finish(title: String, text: String, ongoing: Boolean) {
        post(
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rikkahub)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(launchIntent())
                .build()
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        foregroundStarted = false
        stopSelf()
    }

    @SuppressLint("MissingPermission") // 调用前已检查 areNotificationsEnabled 与 POST_NOTIFICATIONS
    private fun post(notification: android.app.Notification): Boolean = try {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "通知发布被拒绝（缺少 POST_NOTIFICATIONS）", e)
        checker.reportNotificationProblem("通知权限未开启，下载进度仅在应用内显示")
        false
    }

    private fun buildNotification(state: UpdateDownloadState): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(titleFor(state))
            .setContentText(textFor(state))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(launchIntent())

        when (state) {
            is UpdateDownloadState.Running -> {
                val fraction = state.fraction
                if (fraction == null) {
                    builder.setProgress(0, 0, true)
                } else {
                    builder.setProgress(1000, (fraction * 1000).toInt(), false)
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

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    companion object {
        private const val TAG = "UpdateDownloadService"
        private const val CHANNEL_ID = "update_download"
        private const val NOTIFICATION_ID = 0x0A51
        private const val ACTION_CANCEL_REQUEST = 1
        const val ACTION_CANCEL = "me.rerere.rikkahub.action.CANCEL_UPDATE_DOWNLOAD"

        /**
         * 启动前台下载服务。返回 false 表示通知/前台服务不可用（权限被拒、系统限制等），
         * 此时下载仍在应用内继续，界面必须显示进度并如实提示通知不可用。
         */
        fun start(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                // 不再往 SharedPreferences 里写标记：这个值没有读取方，而 SharedPreferences
                // 的加载/写入是主线程磁盘 IO 风险点。调用方已经通过返回值处理「通知不可用」。
                Log.w(TAG, "缺少 POST_NOTIFICATIONS，跳过前台通知服务")
                return false
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.w(TAG, "系统通知被关闭，跳过前台通知服务")
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, UpdateDownloadService::class.java),
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService 失败", e)
                false
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, UpdateDownloadService::class.java)) }
        }
    }
}
