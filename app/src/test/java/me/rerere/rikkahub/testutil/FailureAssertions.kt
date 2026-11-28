package me.rerere.rikkahub.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import java.util.Collections
import java.util.IdentityHashMap

/** Coroutine stacktrace recovery can copy an exception; its original must remain in the cause chain. */
internal fun assertRecoveredFailure(expected: Throwable, actual: Throwable?) {
    assertNotNull(actual)
    actual!!
    assertEquals(expected.javaClass, actual.javaClass)
    assertEquals(expected.message, actual.message)
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cursor: Throwable? = actual
    while (cursor != null && seen.add(cursor)) {
        if (cursor === expected) return
        cursor = cursor.cause
    }
    fail("Original exception identity must be preserved in the recovery cause chain")
}
