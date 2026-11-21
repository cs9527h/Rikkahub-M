package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BackupListingPage<T>(
    val items: List<T>,
    val isTruncated: Boolean = false,
    val nextToken: String? = null,
)

/** Never return a partial listing to a retention decision. Failed or looping pages abort before deletion. */
internal suspend fun <T> completeBackupListing(
    maxPages: Int = 100,
    maxItems: Int = 100_000,
    fetch: suspend (String?) -> BackupListingPage<T>,
): List<T> {
    require(maxPages > 0 && maxItems > 0)
    val result = mutableListOf<T>()
    val seenTokens = mutableSetOf<String>()
    var token: String? = null
    repeat(maxPages) {
        currentCoroutineContext().ensureActive()
        val page = fetch(token)
        require(page.items.size <= maxItems - result.size) { "备份列表超过安全上限，未执行清理" }
        result.addAll(page.items)
        if (!page.isTruncated) return result
        token = page.nextToken
        require(!token.isNullOrBlank() && seenTokens.add(token)) { "备份列表分页无效，未执行清理" }
    }
    error("备份列表分页超过安全上限，未执行清理")
}
