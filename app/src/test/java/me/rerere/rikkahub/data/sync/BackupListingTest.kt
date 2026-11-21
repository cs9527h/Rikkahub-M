package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackupListingTest {
    @Test fun completeListingReadsPastFirstThousandObjects() = runBlocking {
        val tokens = mutableListOf<String?>()
        val result = completeBackupListing { token ->
            tokens += token
            when (token) {
                null -> BackupListingPage((1..1000).toList(), true, "second")
                "second" -> BackupListingPage(listOf(1001))
                else -> error("Unexpected token")
            }
        }
        assertEquals(listOf(null, "second"), tokens)
        assertEquals((1..1001).toList(), result)
    }

    @Test fun missingOrLoopingContinuationNeverReturnsPartialResults() = runBlocking {
        for (next in listOf(null, "", "loop")) {
            var requests = 0
            val failure = runCatching {
                completeBackupListing {
                    requests++
                    BackupListingPage(listOf("synthetic"), true, next)
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(if (next == "loop") 2 else 1, requests)
        }
    }

    @Test fun laterPageFailureNeverReturnsFirstPageAsComplete() = runBlocking {
        val failure = runCatching {
            completeBackupListing { token ->
                if (token != null) throw IOException("Synthetic unavailable page")
                BackupListingPage(listOf("synthetic"), true, "second")
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        val failure = runCatching {
            completeBackupListing<String> { throw CancellationException("synthetic cancellation") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun pageAndItemCapsFailClosedBeforeRetention() = runBlocking {
        var requests = 0
        val pageFailure = runCatching {
            completeBackupListing(maxPages = 2) {
                requests++
                BackupListingPage(listOf(requests), true, requests.toString())
            }
        }.exceptionOrNull()
        assertTrue(pageFailure is IllegalStateException)
        assertEquals(2, requests)
        val itemFailure = runCatching {
            completeBackupListing(maxItems = 1) { BackupListingPage(listOf(1, 2)) }
        }.exceptionOrNull()
        assertTrue(itemFailure is IllegalArgumentException)
    }

    @Test fun emptyCompletedListingNeedsNoFurtherRequests() = runBlocking {
        var requests = 0
        val result = completeBackupListing<String> {
            requests++
            BackupListingPage(emptyList())
        }
        assertTrue(result.isEmpty())
        assertEquals(1, requests)
    }
}
