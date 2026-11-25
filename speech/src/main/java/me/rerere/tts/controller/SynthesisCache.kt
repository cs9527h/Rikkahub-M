package me.rerere.tts.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import me.rerere.tts.model.TTSResponse
import java.util.UUID

/** Only the current chunk and two look-ahead chunks may own audio in memory. */
internal class SynthesisCache(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 3,
    private val maxResponseBytes: Int = 8 * 1024 * 1024,
) {
    private val entries = mutableMapOf<UUID, Deferred<TTSResponse>>()

    @Synchronized
    fun getOrCreate(id: UUID, synthesize: suspend () -> TTSResponse): Deferred<TTSResponse> {
        entries[id]?.let { return it }
        check(entries.size < maxEntries) { "TTS prefetch cache is full" }
        return scope.async(Dispatchers.IO) {
            synthesize().also {
                check(it.audioData.size <= maxResponseBytes) { "TTS chunk exceeds audio memory budget" }
            }
        }.also { entries[id] = it }
    }

    @Synchronized
    fun remove(id: UUID) {
        entries.remove(id)?.cancel()
    }

    @Synchronized
    fun retain(ids: Set<UUID>) {
        entries.keys.filter { it !in ids }.forEach { remove(it) }
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { it.cancel() }
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
