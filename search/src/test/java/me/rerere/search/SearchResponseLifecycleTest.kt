package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SearchResponseLifecycleTest {
    @Test
    fun `every HTTP provider closes 401 429 and 500 responses for search and scraping`() = runBlocking {
        val original = SearchService.httpClient
        try {
            for (code in listOf(401, 429, 500)) {
                val bodies = Collections.synchronizedList(mutableListOf<TrackingBody>())
                SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                    val body = TrackingBody("error".repeat(10_000))
                    bodies.add(body)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(code).message("HTTP error").body(body).build()
                }.build()
                val options = SearchServiceOptions.FACTORIES.values.map { it() }
                    .filterNot {
                        it is SearchServiceOptions.BingLocalOptions ||
                            it is SearchServiceOptions.CustomJsOptions
                    }
                    .map {
                        when (it) {
                            is SearchServiceOptions.SearXNGOptions -> it.copy(url = "https://search.example.test")
                            is SearchServiceOptions.PerplexityOptions -> it.copy(apiKey = "fixture-key")
                            is SearchServiceOptions.GrokOptions -> it.copy(apiKey = "fixture-key")
                            else -> it
                        }
                    }
                for (option in options) {
                    val service = SearchService.getService(option)
                    val params = buildJsonObject {
                        put("query", "test")
                        put("url", "https://example.test/page")
                    }
                    val beforeSearch = bodies.size
                    val result = service.search(params, SearchCommonOptions(), option)
                    assertTrue("${service.name} HTTP $code must fail", result.isFailure)
                    assertEquals("${service.name} must actually request search", beforeSearch + 1, bodies.size)
                    assertTrue("${service.name} HTTP $code search body leaked", bodies.last().closed)
                    if (service.scrapingParameters(option) != null) {
                        val beforeScrape = bodies.size
                        val scraped = service.scrape(params, SearchCommonOptions(), option)
                        assertTrue("${service.name} HTTP $code scrape must fail", scraped.isFailure)
                        assertEquals("${service.name} must actually request scrape", beforeScrape + 1, bodies.size)
                        assertTrue("${service.name} HTTP $code scrape body leaked", bodies.last().closed)
                    }
                }
            }
        } finally {
            SearchService.httpClient = original
        }
    }

    @Test
    fun `error diagnostics are bounded and owning response still closes`() {
        val body = TrackingBody("x".repeat(100_000))
        val response = Response.Builder().request(okhttp3.Request.Builder().url("https://example.test").build())
            .protocol(Protocol.HTTP_1_1).code(500).message("error").body(body).build()
        response.use { assertEquals(4096, it.readErrorBody().length) }
        assertTrue(body.closed)
        assertTrue("bounded error reader must not consume the entire error body", body.bytesRead < 100_000)
    }

    @Test
    fun `body read failures also close owning provider response`() = runBlocking {
        val original = SearchService.httpClient
        val body = TrackingBody("ignored", failRead = true)
        try {
            SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build()
            }.build()
            val result = FirecrawlSearchService.search(
                buildJsonObject { put("query", "test") },
                SearchCommonOptions(),
                SearchServiceOptions.FirecrawlOptions()
            )
            assertTrue(result.isFailure)
            assertTrue(body.closed)
        } finally {
            SearchService.httpClient = original
        }
    }

    private class TrackingBody(text: String, private val failRead: Boolean = false) : ResponseBody() {
        @Volatile var closed = false
        var bytesRead = 0L
        private val trackedSource = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failRead) throw java.io.IOException("body read failed")
                return super.read(sink, byteCount).also { if (it > 0) bytesRead += it }
            }
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source() = trackedSource
    }
}
