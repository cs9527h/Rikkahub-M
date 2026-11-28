package me.rerere.common.util

import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Bounded, payload-free diagnostics for logs. Callers must pass a constant operation identifier.
 * Exception messages/toString are deliberately excluded: providers often embed HTTP bodies,
 * prompts, credentials, query strings or personal paths in them. Types, causes and code locations
 * retain diagnostic value without trying to blacklist every possible secret format.
 */
fun formatExceptionForLog(operation: String, error: Throwable): String {
    val safeOperation = operation.takeIf { it.length in 1..96 && OPERATION.matches(it) } ?: "operation"
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val output = StringBuilder(safeOperation).append(" failed")
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSES) {
        if (!seen.add(current)) {
            output.append("\n[cause cycle]")
            break
        }
        output.append(if (depth == 0) ": " else "\nCaused by: ")
            .append(codeIdentifier(current.javaClass.name))
        val stack = current.stackTrace
        stack.take(MAX_FRAMES).forEach { frame ->
            // No file name: custom exceptions may put full device/user paths in StackTraceElement.
            output.append("\n  at ").append(codeIdentifier(frame.className)).append('.')
                .append(codeIdentifier(frame.methodName)).append(':').append(frame.lineNumber)
        }
        if (stack.size > MAX_FRAMES) output.append("\n  [frames truncated]")
        current = current.cause
        depth++
    }
    if (current != null && depth == MAX_CAUSES) output.append("\n[causes truncated]")
    return output.toString().take(MAX_OUTPUT)
}

/** Captures recoverable Exceptions; cancellation and fatal JVM Errors always escape. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

private fun codeIdentifier(value: String): String =
    value.takeIf { it.length in 1..180 && CODE_IDENTIFIER.matches(it) } ?: "[redacted]"

private const val MAX_CAUSES = 8
private const val MAX_FRAMES = 12
private const val MAX_OUTPUT = 8192
private val OPERATION = Regex("[A-Za-z][A-Za-z0-9_.-]*")
private val CODE_IDENTIFIER = Regex("[A-Za-z_$<>][A-Za-z0-9_.$<>]*")
