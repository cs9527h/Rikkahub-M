package me.rerere.mediagen.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.providers.apimart.ApiMartMediaGenerationProvider
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MediaCancellationContractTest {
    @Test
    fun `cancelled APIMart create cancels actual request without returning failure Result`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val active = AtomicReference<Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            active.set(chain.call())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(
                    200
                ).message("OK").body("""{"data":[{"task_id":"test","status":"submitted"}]}""".toResponseBody()).build()
        }.eventListener(object : okhttp3.EventListener() {
            override fun callEnd(call: Call) {
                ended.countDown()
            }
            override fun callFailed(call: Call, ioe: java.io.IOException) {
                ended.countDown()
            }
        }).build()
        var returnedResult = false
        val job = launch(Dispatchers.Default) {
            ApiMartMediaGenerationProvider(client).create(
                MediaGenerationProviderSetting.ApiMart(),
                MediaGenerationModel("gpt-image-1", MediaKind.IMAGE),
                MediaGenerationRequest(prompt = "fixture"),
            )
            returnedResult = true
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue(active.get().isCanceled())
            assertFalse(returnedResult)
        } finally {
            release.countDown()
            job.cancel()
            job.join()
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
