package me.rerere.rikkahub.ui.components.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CroppedImageDeliveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `source stays alive until delayed avatar copy finishes`() = runBlocking {
        val source = File(temp.root, "crop.jpg")
        val avatar = File(temp.root, "avatar.jpg")
        val bytes = byteArrayOf(1, 7, 9, 3)
        val started = CompletableDeferred<Unit>()
        val copyAllowed = CompletableDeferred<Unit>()
        val delivery = async {
            deliverCroppedImage(source, write = { it.writeBytes(bytes) }, consume = { file ->
                started.complete(Unit)
                copyAllowed.await()
                withContext(Dispatchers.IO) { file.copyTo(avatar) }
            })
        }
        started.await()
        assertTrue(source.isFile)
        assertFalse(avatar.exists())
        copyAllowed.complete(Unit)
        delivery.await()
        assertArrayEquals(bytes, avatar.readBytes())
        assertFalse(source.exists())
    }

    @Test
    fun `consumer failure preserves cause and cleans temporary crop`() = runBlocking {
        val source = File(temp.root, "crop.jpg")
        val failure = IOException("synthetic save failure")
        try {
            deliverCroppedImage(source, write = { it.writeText("crop") }, consume = {
                assertTrue(it.isFile)
                throw failure
            })
            error("Expected consumer failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        assertFalse(source.exists())
    }

    @Test
    fun `failed crop write never calls avatar consumer and removes partial source`() = runBlocking {
        val source = File(temp.root, "crop.jpg")
        var consumed = false
        val failure = IOException("synthetic crop failure")
        try {
            deliverCroppedImage(source, write = {
                it.writeText("partial")
                throw failure
            }, consume = { consumed = true })
            error("Expected writer failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        assertFalse(consumed)
        assertFalse(source.exists())
    }

    @Test
    fun `cancellation during copy cleans source without committing avatar`() = runBlocking {
        val source = File(temp.root, "crop.jpg")
        val started = CompletableDeferred<Unit>()
        var committed = false
        val delivery = launch {
            deliverCroppedImage(source, write = { it.writeText("crop") }, consume = {
                started.complete(Unit)
                awaitCancellation()
            })
            committed = true
        }
        started.await()
        assertTrue(source.exists())
        delivery.cancelAndJoin()
        assertFalse(committed)
        assertFalse(source.exists())
    }

    @Test
    fun `fatal save failure propagates and still cleans source`() = runBlocking {
        val source = File(temp.root, "crop.jpg")
        val failure = OutOfMemoryError("synthetic")
        try {
            deliverCroppedImage(source, write = { it.writeText("crop") }, consume = { throw failure })
            error("Expected fatal failure")
        } catch (actual: OutOfMemoryError) {
            assertSame(failure, actual)
        }
        assertFalse(source.exists())
    }
}
