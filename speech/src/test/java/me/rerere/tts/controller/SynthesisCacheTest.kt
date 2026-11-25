package me.rerere.tts.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SynthesisCacheTest {
    private fun audio(bytes: Int = 1024) = TTSResponse(ByteArray(bytes), AudioFormat.PCM)

    @Test
    fun longDocumentEvictsConsumedAudioAndRetainsOnlyLookahead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val cache = SynthesisCache(scope, maxEntries = 3, maxResponseBytes = 1024)
            val ids = List(1000) { UUID.randomUUID() }
            for (index in ids.indices) {
                val window = ids.drop(index).take(3)
                cache.retain(window.toSet())
                window.forEach { cache.getOrCreate(it) { audio() } }
                assertEquals(1024, cache.getOrCreate(ids[index]) { error("already prefetched") }.await().audioData.size)
                assertTrue(cache.size() <= 3) // <= 3072 retained audio bytes, even at chunk 1000.
                cache.remove(ids[index])
            }
            assertEquals(0, cache.size())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pausedCurrentChunkRemainsReplayableUntilPlaybackCompletes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val cache = SynthesisCache(scope)
            val id = UUID.randomUUID()
            val response = cache.getOrCreate(id) { audio() }.await()
            // Pause/seek/resume do not release the active chunk.
            assertSame(response, cache.getOrCreate(id) { error("must not resynthesize paused chunk") }.await())
            cache.remove(id)
            assertEquals(0, cache.size())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oversizedChunkAndExtraPrefetchAreRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val cache = SynthesisCache(scope, maxEntries = 1, maxResponseBytes = 1024)
            val result = cache.getOrCreate(UUID.randomUUID()) { audio(1025) }
            assertTrue(runCatching { result.await() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { cache.getOrCreate(UUID.randomUUID()) { audio() } }.isFailure)
            cache.clear()
            assertEquals(0, cache.size())
        } finally {
            scope.cancel()
        }
    }
}
