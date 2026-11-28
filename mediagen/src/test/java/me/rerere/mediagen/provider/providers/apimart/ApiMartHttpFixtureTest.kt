package me.rerere.mediagen.provider.providers.apimart

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.MediaGenerationApiException
import me.rerere.mediagen.provider.providers.bodyAsString
import me.rerere.mediagen.provider.providers.fakeClient
import me.rerere.mediagen.provider.providers.jsonResponse
import me.rerere.mediagen.provider.providers.mediaGenerationJson
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/** Documented APIMart wire shapes, replayed through OkHttp without a network or API key. */
class ApiMartHttpFixtureTest {
    private val setting = MediaGenerationProviderSetting.ApiMart(apiKey = "fixture-key")
    private val imageModel = MediaGenerationModel(modelId = "gpt-image-2.6-flare", kind = MediaKind.IMAGE)
    private val videoModel = MediaGenerationModel(modelId = "MiniMax-H3", kind = MediaKind.VIDEO)

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/apimart/$name.json")) { "Missing fixture: $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun imageHttpLifecyclePreservesMetadataAndSignedGifSvgUrls() = runBlocking {
        val requests = mutableListOf<Request>()
        val replies = ArrayDeque(listOf("image-submitted", "image-processing", "image-completed"))
        val manager = MediaGenerationManager(fakeClient(requests) { request ->
            jsonResponse(request, fixture(replies.removeFirst()))
        })

        val tasks = manager.generate(
            setting.copy(baseUrl = "https://api.apimart.ai/v1/"),
            imageModel,
            MediaGenerationRequest(prompt = "cat", resolution = "2K", aspectRatio = "16:9", count = 2),
            interval = 1.milliseconds,
        ).toList()

        assertEquals(
            listOf(MediaGenerationStatus.QUEUED, MediaGenerationStatus.RUNNING, MediaGenerationStatus.SUCCEEDED),
            tasks.map { it.status },
        )
        assertEquals(listOf("POST", "GET", "GET"), requests.map { it.method })
        assertEquals("/v1/images/generations", requests.first().url.encodedPath)
        assertTrue(requests.drop(1).all { it.url.encodedPath == "/v1/tasks/task_image_01" })
        assertTrue(requests.all { it.header("Authorization") == "Bearer fixture-key" })
        assertEquals("application/json; charset=utf-8", requests.first().body!!.contentType().toString())
        val body = mediaGenerationJson.parseToJsonElement(requests.first().bodyAsString()).jsonObject
        assertEquals("2k", body["resolution"]!!.jsonPrimitive.content)
        assertEquals("16:9", body["size"]!!.jsonPrimitive.content)
        assertEquals("2", body["n"]!!.jsonPrimitive.content)
        assertEquals("35", tasks[1].metadata["progress"]!!.jsonPrimitive.content)
        assertEquals(imageModel.modelId, tasks.last().model)
        assertEquals(listOf("image/gif", "image/svg+xml"), tasks.last().outputs.map { it.mimeType })
        assertTrue(tasks.last().outputs.all { it.url!!.endsWith("?signature=fixture") })
        assertEquals("0.15", tasks.last().metadata["cost"]!!.jsonPrimitive.content)
        assertEquals(1763088289L, tasks.last().createdAtEpochSeconds)
        assertEquals(1763088308L, tasks.last().updatedAtEpochSeconds)
        val image = tasks.last().metadata["result"]!!.jsonObject["images"]!!.jsonArray.single().jsonObject
        assertEquals("1763174708", image["expires_at"]!!.jsonPrimitive.content)
        assertTrue(setting.downloadHeaders(tasks.last().outputs.first().url!!).isEmpty())
    }

    @Test
    fun videoHttpLifecycleUsesVideoEndpointAndTaskQuery() = runBlocking {
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) { request ->
            jsonResponse(request, fixture(if (request.method == "POST") "video-submitted" else "video-completed"))
        })
        val raw = buildJsonObject {
            put("url", "https://example.com/reference.png")
            put("role", "reference_image")
        }
        val tasks = manager.generate(
            setting,
            videoModel,
            MediaGenerationRequest(
                prompt = "A character walks",
                inputs = listOf(MediaGenerationInput.Raw(raw)),
                callbackUrl = "https://example.com/hook",
                extraParameters = buildJsonObject { put("model_option", "custom") },
            ),
            interval = 1.milliseconds,
        ).toList()

        assertEquals("/v1/videos/generations", requests.first().url.encodedPath)
        assertEquals("/v1/tasks/task_video_01", requests.last().url.encodedPath)
        val body = mediaGenerationJson.parseToJsonElement(requests.first().bodyAsString()).jsonObject
        assertEquals(raw, body["image_with_roles"]!!.jsonArray.single())
        assertEquals("https://example.com/hook", body["webhook"]!!.jsonPrimitive.content)
        assertEquals("custom", body["model_option"]!!.jsonPrimitive.content)
        // No invented client defaults: APIMart's documented server defaults remain in effect.
        assertFalse("duration" in body)
        assertFalse("resolution" in body)
        assertFalse("aspect_ratio" in body)
        assertEquals(MediaGenerationStatus.SUCCEEDED, tasks.last().status)
        assertEquals(videoModel.modelId, tasks.last().model)
        assertEquals("video/mp4", tasks.last().outputs.single().mimeType)
    }

    @Test
    fun queryHttpTaskFailureKeepsErrorCodeAndMessage() = runBlocking {
        val provider = ApiMartMediaGenerationProvider(fakeClient { request ->
            jsonResponse(request, fixture("task-failed"))
        })
        val task = provider.query(setting, videoModel, "task_video_01").getOrThrow()
        assertEquals(MediaGenerationStatus.FAILED, task.status)
        assertTrue(task.isTerminal)
        val error = checkNotNull(task.error)
        assertEquals("422", error.code)
        assertEquals("Task processing failed", error.message)
    }

    @Test
    fun nonSuccessHttpResponsePreservesProviderStatusAndApiError() = runBlocking {
        val requests = mutableListOf<Request>()
        val provider = ApiMartMediaGenerationProvider(fakeClient(requests) { request ->
            jsonResponse(request, fixture("http-rate-limit"), code = 429)
        })
        val result = provider.create(setting, imageModel, MediaGenerationRequest(prompt = "cat"))
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as MediaGenerationApiException
        assertEquals("apimart", error.provider)
        assertEquals(429, error.statusCode)
        assertEquals("rate_limit_exceeded", error.code)
        assertEquals("Rate limit exceeded", error.message)
        assertEquals(1, requests.size)
    }

    @Test
    fun taskStatusesAndObjectSubmissionMatchTheWireContract() {
        val expected = mapOf(
            "submitted" to MediaGenerationStatus.QUEUED,
            "pending" to MediaGenerationStatus.QUEUED,
            "processing" to MediaGenerationStatus.RUNNING,
            "completed" to MediaGenerationStatus.SUCCEEDED,
            "failed" to MediaGenerationStatus.FAILED,
            "cancelled" to MediaGenerationStatus.CANCELLED,
            "canceled" to MediaGenerationStatus.CANCELLED,
            "future_status" to MediaGenerationStatus.UNKNOWN,
        )
        expected.forEach { (status, mapped) ->
            val json = buildJsonObject {
                put("data", buildJsonObject {
                    put("id", "task")
                    put("status", status)
                })
            }
            assertEquals(status, mapped, ApiMartMediaGenerationProvider.parseTask(json).status)
        }
        val root = mediaGenerationJson.parseToJsonElement(
            """{"data":{"task_id":"object-task","status":"submitted"}}"""
        ).jsonObject
        assertEquals("object-task", ApiMartMediaGenerationProvider.parseSubmission(root, imageModel).id)
    }

    @Test
    fun malformedSuccessfulHttpResponseIsNotAcceptedAsATask(): Unit = runBlocking {
        val provider = ApiMartMediaGenerationProvider(fakeClient { request -> jsonResponse(request, "{}") })
        assertTrue(provider.create(setting, imageModel, MediaGenerationRequest(prompt = "cat")).isFailure)
        assertTrue(provider.query(setting, videoModel, "missing").isFailure)
        assertThrows(IllegalStateException::class.java) {
            ApiMartMediaGenerationProvider.parseSubmission(JsonObject(emptyMap()), imageModel)
        }
    }
}
