package me.rerere.rikkahub.mate

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal enum class DiagnosticEvent { APP_CRASH, CLOUD_CHAT_FAILED, MANUAL_CHECK }

internal object MateDiagnostics {
    private const val PREFS = "mate_diagnostic_consent"
    const val RESULT = "last_result"
    private const val WORK_TAG = "mate_diagnostic"
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setConsent(context: Context, instance: String, enabled: Boolean) {
        val prefs = preferences(context)
        prefs.edit().putString("allowed_instance", if (enabled) instance else "").apply()
        if (!enabled) WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }

    fun allowed(context: Context, instance: String) =
        instance.isNotBlank() && preferences(context).getString("allowed_instance", "") == instance

    fun enqueue(context: Context, event: DiagnosticEvent) {
        val instance = MateGatewayStore(context).savedInstanceId()
        if (!allowed(context, instance)) return
        val bucket = System.currentTimeMillis() / TimeUnit.HOURS.toMillis(1)
        val request = OneTimeWorkRequestBuilder<MateDiagnosticWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .addTag(WORK_TAG)
            .setInputData(workDataOf("instance" to instance, "event" to event.name, "queued" to System.currentTimeMillis()))
            .build()
        val prefs = preferences(context)
        // A completed WorkManager request can be re-enqueued under the same name.
        // Reserve each event once per instance/hour, including rapid manual taps.
        synchronized(this) {
            val key = "sent:${instance}:${event.name}"
            if (prefs.getLong(key, -1) == bucket) return
            WorkManager.getInstance(context).enqueueUniqueWork("$WORK_TAG:$key:$bucket", ExistingWorkPolicy.KEEP, request)
            prefs.edit().putLong(key, bucket).putString(RESULT, "诊断已排队，等待网络").apply()
        }
    }
}

class MateDiagnosticWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val instance = inputData.getString("instance").orEmpty()
        if (!MateDiagnostics.allowed(context, instance)) return@withContext Result.success()
        val connection = MateGatewayStore(context).load()
        if (connection?.instanceId != instance) return@withContext Result.success()
        val event = DiagnosticEvent.entries.firstOrNull { it.name == inputData.getString("event") }
            ?: return@withContext Result.failure()
        val queued = inputData.getLong("queued", 0L)
        if (System.currentTimeMillis() - queued > TimeUnit.DAYS.toMillis(1)) return@withContext Result.failure()
        val prefs = MateDiagnostics.preferences(context)
        val body = JSONObject().put("app", "Rikkahub-M").put("version", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE.toString()).put("device_class", "Android SDK " + Build.VERSION.SDK_INT)
            .put("logs", JSONArray().put("event android " + event.name))
        try {
            val response = MateChatTransport().post(connection, "/diagnostics", body)
            check(response.optInt("accepted") >= 1)
            prefs.edit().putString(MateDiagnostics.RESULT, "云服务器已接收诊断").apply()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val retry = e is java.io.IOException && runAttemptCount < 2 &&
                listOf("HTTP 400", "HTTP 401", "HTTP 403", "HTTP 404").none { e.message.orEmpty().contains(it) }
            prefs.edit().putString(MateDiagnostics.RESULT, if (retry) "诊断上传暂时失败，稍后重试" else "诊断未送达，请检查授权和服务器").apply()
            if (retry) Result.retry() else Result.failure()
        }
    }
}
