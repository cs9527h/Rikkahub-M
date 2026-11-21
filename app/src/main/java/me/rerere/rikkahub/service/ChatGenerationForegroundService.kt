package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.NotificationAvatar
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

private const val TAG = "ChatGenerationFgs"

/**
 * Keeps the app process in the foreground while one or more chat generations are active.
 *
 * Generation itself remains owned by [ChatService]. This service only provides the Android
 * foreground-service lifetime required for streaming to continue after the activity is hidden.
 */
class ChatGenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.CHAT_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.CHAT_GENERATION_RELEASE"
        private const val EXTRA_GENERATION_ID = "generation_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_ACTIVITY = "activity"
        private const val EXTRA_ASSISTANT_ID = "assistant_id"

        const val NOTIFICATION_ID = 2002

        fun acquire(
            context: Context,
            generationId: Uuid,
            conversationId: Uuid,
            assistantId: Uuid? = null,
            activity: SessionActivity = SessionActivity.Generating,
        ): Boolean {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
                assistantId?.let { putExtra(EXTRA_ASSISTANT_ID, it.toString()) }
                putExtra(EXTRA_ACTIVITY, activity.name)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start chat generation foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, generationId: Uuid) {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_GENERATION_ID, generationId.toString())
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release chat generation foreground service", it)
            }
        }
    }

    private data class ActiveGeneration(
        val conversationId: String,
        val activity: SessionActivity,
        val assistantId: String?,
    )

    private val activeGenerations = linkedMapOf<String, ActiveGeneration>()
    private var isForeground = false
    private val appScope: AppScope by inject()
    private val chatService: ChatService by inject()
    private val settingsStore: SettingsStore by inject()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent, startId)
            ACTION_RELEASE -> release(intent, startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeGenerations.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timed out (type=$fgsType)")
        activeGenerations.values
            .mapNotNull { runCatching { Uuid.parse(it.conversationId) }.getOrNull() }
            .distinct()
            .forEach { conversationId ->
                appScope.launch {
                    chatService.stopGeneration(conversationId)
                }
            }
        // Android only allows a few seconds after onTimeout before raising RemoteServiceException.
        stopService()
    }

    private fun acquire(intent: Intent, startId: Int) {
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID) ?: return stopSelf(startId)
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return stopSelf(startId)
        val activity = SessionActivity.entries.find { it.name == intent.getStringExtra(EXTRA_ACTIVITY) }
            ?: SessionActivity.Generating
        val generation = ActiveGeneration(conversationId, activity, intent.getStringExtra(EXTRA_ASSISTANT_ID))
        activeGenerations[generationId] = generation
        updateForegroundNotification(generation)
    }

    private fun release(intent: Intent, startId: Int) {
        intent.getStringExtra(EXTRA_GENERATION_ID)?.let(activeGenerations::remove)
        if (activeGenerations.isEmpty()) {
            // Only stop if no newer start request is queued. A pending ACQUIRE came from
            // startForegroundService(), and stopping before it calls startForeground() crashes
            // with ForegroundServiceDidNotStartInTimeException. onDestroy() removes the notification.
            stopSelf(startId)
        } else {
            updateForegroundNotification(activeGenerations.values.last())
        }
    }

    private fun updateForegroundNotification(generation: ActiveGeneration) {
        try {
            val notification = buildNotification(generation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeGenerations.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(generation: ActiveGeneration): android.app.Notification {
        // Never attribute a notification to another assistant if the selected id is missing.
        val assistant = resolveForegroundAssistant(
            generation.assistantId,
            settingsStore.settingsFlow.value.assistants,
        )
        val senderName = assistant?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        return NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(senderName)
            .also { builder -> NotificationAvatar.render(this, assistant?.avatar)?.let(builder::setLargeIcon) }
            .setContentText(
                getString(
                    when (generation.activity) {
                        SessionActivity.Generating -> R.string.notification_live_update_title
                        SessionActivity.Compressing -> R.string.chat_page_compressing
                    }
                )
            )
            .setContentIntent(getConversationPendingIntent(generation.conversationId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun getConversationPendingIntent(conversationId: String): PendingIntent {
        return PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            conversationIntent(this, conversationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Never attribute a notification to another assistant if the selected ID is missing. */
internal fun resolveForegroundAssistant(assistantId: String?, assistants: List<Assistant>): Assistant? =
    assistants.firstOrNull { it.id.toString() == assistantId }
