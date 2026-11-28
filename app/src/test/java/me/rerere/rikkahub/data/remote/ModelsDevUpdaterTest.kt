package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.registry.ModelsDevCatalog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelsDevUpdaterTest {
    @get:Rule val folder = TemporaryFolder()
    private val installed = mutableListOf<ModelsDevCatalog>()
    private val failures = mutableListOf<String>()
    private fun updater(factory: Call.Factory) = ModelsDevUpdater(
        filesDir = folder.root,
        client = factory,
        loadBundled = { decode("{}") },
        installCatalog = { installed.add(it) },
        reportFailure = { operation, _ -> failures.add(operation) },
    )

    @Test fun `cancelling pending HTTP cancels actual call without publishing refreshed cache`() = runBlocking {
        val requested = CompletableDeferred<ScriptedCall>()
        val updater =
            updater(factory { request -> ScriptedCall(request, enqueue = { call, _ -> requested.complete(call) }) })
        val job = launch { updater.start() }
        val call = withTimeout(5_000) { requested.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(call.cancelled)
        assertEquals(1, installed.size) // bundled fallback only
        assertTrue(failures.isEmpty())
        assertFalse(folder.root.resolve("models_dev/api.json").exists())
    }

    @Test fun `cancelling body decoding still cancels call and closes response`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val body = TrackingBody("{}", beforeRead = {
            reading.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
            throw IOException("body cancelled")
        })
        lateinit var call: ScriptedCall
        val updater = updater(factory { request ->
            ScriptedCall(
                request,
                enqueue = { current, callback -> callback.onResponse(current, response(request, body)) },
                onCancel = { release.countDown() }
            ).also { call = it }
        })
        val job = launch { updater.start() }
        try {
            withTimeout(5_000) { reading.await() }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(call.cancelled)
            assertTrue(body.closed)
            assertEquals(1, installed.size)
            assertTrue(failures.isEmpty())
            assertFalse(folder.root.resolve("models_dev/api.json").exists())
        } finally {
            release.countDown()
            job.cancel()
        }
    }

    @Test fun `undersized refresh keeps previous cache and installed catalog and closes response`() = runBlocking {
        val original = catalogJson(1)
        val cache = folder.root.resolve("models_dev/api.json").apply {
            parentFile!!.mkdirs()
            writeText(original)
        }
        val body = TrackingBody("{}")
        updater(factory { request ->
            ScriptedCall(request, enqueue = { call, callback ->
                callback.onResponse(call, response(request, body))
            })
        }).start()
        assertEquals(original, cache.readText())
        assertEquals(1, installed.size)
        assertTrue(installed.single().size > 0)
        assertEquals(listOf("models-dev.refresh"), failures)
        assertTrue(body.closed)
    }

    @Test fun `valid refresh preserves atomic cache etag and catalog publication behavior`() = runBlocking {
        val body = TrackingBody(catalogJson(110))
        updater(factory { request ->
            ScriptedCall(request, enqueue = { call, callback ->
                callback.onResponse(call, response(request, body).newBuilder().header("ETag", "fresh-etag").build())
            })
        }).start()
        assertTrue(body.closed)
        assertEquals(2, installed.size)
        assertTrue(installed.last().size >= 100)
        val cache = folder.root.resolve("models_dev/api.json")
        assertTrue(cache.inputStream().use { ModelsDevCatalog.decode(it) }.size >= 100)
        assertEquals("fresh-etag", folder.root.resolve("models_dev/etag").readText())
        assertFalse(folder.root.resolve("models_dev/api.json.tmp").exists())
        assertTrue(failures.isEmpty())
    }

    private fun decode(text: String) = text.byteInputStream().use { ModelsDevCatalog.decode(it) }
    private fun catalogJson(count: Int) = "{\"provider\":{\"models\":{" +
        (0 until count).joinToString(",") { "\"model-$it\":{}" } + "}}}"
    private fun response(request: Request, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
    private fun factory(create: (Request) -> Call) = object : Call.Factory {
        override fun newCall(request: Request): Call = create(request)
    }

    private class ScriptedCall(
        private val originalRequest: Request,
        private val enqueue: (ScriptedCall, Callback) -> Unit,
        private val onCancel: () -> Unit = {},
    ) : Call by OkHttpClient().newCall(originalRequest) {
        // Delegate metadata-only API additions; execute/enqueue remain scripted and never reach the network.
        @Volatile var cancelled = false
        private var executed = false
        override fun request() = originalRequest
        override fun enqueue(responseCallback: Callback) {
            executed = true
            enqueue(this, responseCallback)
        }
        override fun execute(): Response = error("Updater must use asynchronous Call.await, not execute")
        override fun cancel() {
            cancelled = true
            onCancel()
        }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = error("No retry clone expected")
    }

    private class TrackingBody(text: String, beforeRead: () -> Unit = {}) : ResponseBody() {
        @Volatile var closed = false
        private val tracked = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                beforeRead()
                return super.read(sink, byteCount)
            }
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source() = tracked
    }
}
