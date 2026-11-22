package me.rerere.rikkahub.utils

/** Content-Range: bytes 200000-19337613/19337614 */
data class ContentRange(val start: Long, val end: Long?, val total: Long?)

/**
 * 下载重试与断点续传的纯策略逻辑，方便单元测试；
 * 实际读写文件、发请求由 [UpdateInstaller] 完成。
 */
object UpdateTransferPolicy {

    private val pattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

    fun parseContentRange(value: String?): ContentRange? {
        val match = pattern.find(value?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        return ContentRange(start, end, total)
    }

    /** 4xx 里只有超时/限流值得同一地址重试，其余直接换来源。 */
    fun isRetryable(code: Int): Boolean = when {
        code in 200..299 -> false
        code == 408 || code == 429 -> true
        code in 400..499 -> false
        else -> true
    }

    /** 1s、2s、4s 的退避，最多三次。 */
    fun retryDelayMillis(attempt: Int): Long = 1000L * (1 shl attempt.coerceIn(0, 2))

    /** 续传必须从本地已有长度开始，且总量不能小于该长度。 */
    fun resumeStart(range: ContentRange?, localSize: Long): Long? {
        val r = range ?: return null
        if (r.start != localSize) return null
        if (r.total != null && r.total < localSize) return null
        return r.start
    }
}
