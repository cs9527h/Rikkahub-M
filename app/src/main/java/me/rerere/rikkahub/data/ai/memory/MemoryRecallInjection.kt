package me.rerere.rikkahub.data.ai.memory

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.buildRecallPrompt
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.memoryId
import kotlin.time.TimeSource

/**
 * 聊天注入只用到记忆库的两件事：按当前问题检索、读固定文件（偏好）。
 *
 * 抽成接口不是为了多一层抽象，而是让「这一轮到底往请求里塞了什么文本」能在纯 JVM 用例里断言，
 * 不用起 Room、不用真模型。
 */
internal interface MemoryRecallStore {
    suspend fun search(scope: String, query: String): RecallResult

    suspend fun file(scope: String, path: String): MemoryFile?
}

/** 一轮聊天最终会注入请求的记忆上下文；[prompt] 就是追加进系统消息的那段文本。 */
internal class RecallInjection(
    /** 这次读的是哪个记忆库：助手独立的，还是 useGlobalMemory 打开的全局库 */
    val scope: String,
    val prompt: String,
    /** 命中的记忆路径，带工具的模型会看到这份清单 */
    val matches: List<String>,
    val excerpts: List<RecallExcerpt>,
    val elapsedMillis: Long,
    val query: String,
    val considered: Int,
    val filtered: Int,
    val broad: Boolean,
    val filterReasons: Map<String, Int>,
)

/**
 * 算出这一轮要注入的记忆上下文。返回 null 表示「什么都不加」。
 *
 * 几条约定都有对应用例盯着：
 * - [Assistant.enableMemory] 为 false 时立刻返回 null，**并且一次都不碰记忆库**：关掉记忆就该不检索、不注入；
 * - 作用域只取 [memoryId]：助手独立库与全局库互不可见，换助手不会串库；
 * - [Assistant.autoConsolidateMemory] 只决定后台要不要自动整理，和这里的召回无关；
 * - 记忆库空着时不给没有工具的模型塞空的「背景记录」；带工具的模型仍要拿到写记忆的规则。
 */
internal suspend fun buildRecallInjection(
    assistant: Assistant,
    query: String,
    hasTools: Boolean,
    store: MemoryRecallStore,
): RecallInjection? {
    if (!assistant.enableMemory) return null
    val scope = assistant.memoryId
    val clock = TimeSource.Monotonic.markNow()
    val recall = store.search(scope, query)
    // 偏好每轮都算相关，放不放得下由下面的字符预算决定
    val preferences = store.file(scope, MemoryFile.PREFERENCES_PATH)?.takeUnless { it.isOutdated }
    val excerpts = recallExcerpts(
        listOfNotNull(preferences) + recall.files.filter { it.path != preferences?.path },
        maxChars = 2 * assistant.memoryRecallTokenBudget.coerceIn(500, 4_000),
    )
    // 没有任何可注入的内容时不要塞一段空的「背景记录」：既费 Token，也让模型以为拿到过资料。
    // 例外是带记忆工具的模型——这段文本里还托着「只在用户明确要求时才写」的规则，空了也不能省。
    if (excerpts.isEmpty() && recall.files.isEmpty() && !hasTools) return null
    return RecallInjection(
        scope = scope,
        prompt = buildRecallPrompt(excerpts, recall.files.map { it.path }, hasTools),
        matches = recall.files.map { it.path },
        excerpts = excerpts,
        elapsedMillis = clock.elapsedNow().inWholeMilliseconds,
        query = recall.query.take(200),
        considered = recall.considered,
        filtered = recall.filtered,
        broad = recall.broad,
        filterReasons = recall.filterReasons,
    )
}

/**
 * 把召回文本接到请求消息上：系统消息在最前时追在它后面，否则新起一条系统消息。
 *
 * [injection] 为 null 时原样返回同一个列表——关掉记忆的会话不该看到任何多出来的东西。
 */
internal fun List<UIMessage>.withRecallInjection(injection: RecallInjection?): List<UIMessage> {
    if (injection == null) return this
    val first = firstOrNull()
    return if (first?.role == MessageRole.SYSTEM) {
        listOf(first.copy(parts = first.parts + UIMessagePart.Text("\n\n" + injection.prompt))) + drop(1)
    } else {
        listOf(UIMessage.system(injection.prompt).copy(isSynthetic = true)) + this
    }
}
