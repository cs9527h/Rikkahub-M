package me.rerere.rikkahub.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import me.rerere.common.http.await
import me.rerere.common.util.formatExceptionForLog
import me.rerere.common.util.runCatchingCancellable
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.time.Duration.Companion.hours

private const val TAG = "ModelsDevUpdater"
private const val API_URL = "https://models.dev/api.json"
private const val ASSET_NAME = "models_dev.json"
private val REFRESH_INTERVAL = 4.hours
private const val MIN_CATALOG_SIZE = 100

/** Local cache/assets remain usable if an optional refresh fails; cancellation is not a refresh failure. */
class ModelsDevUpdater internal constructor(
    filesDir: File,
    private val client: Call.Factory,
    private val loadBundled: () -> ModelsDevCatalog,
    private val installCatalog: (ModelsDevCatalog) -> Unit = ModelRegistry::installCatalog,
    private val reportFailure: (String, Throwable) -> Unit = { operation, error ->
        Log.w(TAG, formatExceptionForLog(operation, error))
    },
) {
    constructor(context: Context, client: OkHttpClient) : this(
        filesDir = context.filesDir,
        client = client,
        loadBundled = { context.assets.open(ASSET_NAME).use { ModelsDevCatalog.decode(it) } },
    )

    private val dir = File(filesDir, "models_dev")
    private val dataFile = File(dir, "api.json")
    private val etagFile = File(dir, "etag")

    suspend fun start() = withContext(Dispatchers.IO) {
        val hasCache = loadLocal()
        if (System.currentTimeMillis() - etagFile.lastModified() >= REFRESH_INTERVAL.inWholeMilliseconds) {
            runCatchingCancellable { refresh(useEtag = hasCache) }.onFailure {
                currentCoroutineContext().ensureActive()
                reportFailure("models-dev.refresh", it)
            }
        }
    }

    private fun loadLocal(): Boolean {
        if (dataFile.exists()) {
            val cached = runCatchingCancellable { dataFile.inputStream().use { ModelsDevCatalog.decode(it) } }
                .onFailure { reportFailure("models-dev.read-cache", it) }.getOrNull()
            if (cached != null) {
                installCatalog(cached)
                return true
            }
            dataFile.delete()
        }
        runCatchingCancellable { loadBundled() }
            .onSuccess(installCatalog)
            .onFailure { reportFailure("models-dev.read-bundled", it) }
        return false
    }

    private suspend fun refresh(useEtag: Boolean) = coroutineScope {
        val etag = if (useEtag && etagFile.exists()) etagFile.readText().ifBlank { null } else null
        val request = Request.Builder().url(API_URL)
            .apply { if (etag != null) header("If-None-Match", etag) }.build()
        val call = client.newCall(request)
        // Call.await handles the response handoff. Keep cancellation linked while synchronous
        // JSON decoding reads the response body too; cancelling only the initial await is insufficient.
        val canceller = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use { response ->
                currentCoroutineContext().ensureActive()
                if (response.code == 304) {
                    markChecked(etag)
                    return@coroutineScope
                }
                check(response.isSuccessful) { "models.dev responded ${response.code}" }
                val catalog = response.body.byteStream().use { ModelsDevCatalog.decode(it) }
                check(catalog.size >= MIN_CATALOG_SIZE) { "catalog too small: ${catalog.size}" }
                currentCoroutineContext().ensureActive()
                dir.mkdirs()
                val tmp = File(dir, "api.json.tmp")
                try {
                    tmp.outputStream().use { catalog.encodeTo(it) }
                    currentCoroutineContext().ensureActive()
                    check(tmp.renameTo(dataFile)) { "Failed to replace model catalog cache" }
                } finally {
                    tmp.delete()
                }
                installCatalog(catalog)
                markChecked(response.header("ETag"))
            }
        } finally {
            canceller.cancel()
        }
    }

    private fun markChecked(etag: String?) {
        dir.mkdirs()
        etagFile.writeText(etag.orEmpty())
    }
}
