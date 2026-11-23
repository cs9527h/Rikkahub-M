package me.rerere.rikkahub.data.ai.memory

import android.content.Context
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.findForbiddenMemoryContent
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.HistoryConversationSource
import me.rerere.rikkahub.data.db.entity.HistoryMemoryBatch
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import me.rerere.rikkahub.data.db.entity.MemoryBulkUndo
import me.rerere.rikkahub.data.db.entity.HistoryMemorySeen
import me.rerere.rikkahub.data.db.entity.HistoryMemoryTaskLog
import me.rerere.rikkahub.data.db.entity.MemoryScopeState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal class HistoryMemoryManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val providers: ProviderManager,
    private val memories: MemoryRepository,
) {
    private val dao = database.historyMemoryDao()
    private val conversations = database.conversationDao()
    private val nodes = database.messageNodeDao()
    private val controls = Mutex()
    // A restored database must never authorize a new device to transmit old conversations.
    private val consentId: String by lazy {
        val file = File(context.noBackupFilesDir, "history-memory-device")
        if (file.exists()) file.readText() else Uuid.random().toString().also(file::writeText)
    }

    fun observe(scope: String) = dao.observe(scope)
    fun candidates(id: String) = dao.pendingAll(id)
    fun count(id: String, status: String) = dao.count(id, status)

    /** 审核页按记忆库取候选：任务被替换后，上一轮没审完的候选仍然可见可恢复。 */
    fun candidates(scope: String, status: String) = dao.candidatesOfStatus(scope, status, MAX_BULK)
    fun countOf(scope: String, status: String) = dao.countOfStatus(scope, status)
    fun state(scope: String) = dao.observeState(scope)

    /** 有可撤销记录时界面必须一直显示撤销入口，而不是只在“有新增”时才出现。 */
    suspend fun undoAvailable(scope: String): Boolean = withContext(Dispatchers.IO) { dao.lastUndo(scope) != null }
    fun recalls(scope: String) = dao.recalls(scope)
    suspend fun clearRecalls(scope: String) = dao.clearRecalls(scope)
    suspend fun sources(from: Long, until: Long) = conversations.historySources(from, until)

    /** 每一轮整理的历史；任务行被新任务替换后，这些记录仍然按 scope 留下来。 */
    fun observeTaskLogs(scope: String, limit: Int = TASK_LOG_KEEP) = dao.taskLogs(scope, limit)

    suspend fun taskLog(id: String): HistoryMemoryTaskLog? = withContext(Dispatchers.IO) { dao.taskLog(id) }

    /**
     * 打开历史页时把候选数字对齐一次：用户事后审核候选会改变 saved/skipped，
     * 历史里存的必须和候选表现在的事实一致，而不是停在任务结束那一刻。
     */
    suspend fun refreshTaskLogs(scope: String) = withContext(Dispatchers.IO) {
        dao.taskLogIds(scope, TASK_LOG_KEEP).forEach { dao.refreshTaskCounters(it) }
    }

    /**
     * 重试某一轮任务。只有“当前任务仍然是这一轮”才能接着跑：
     * 已被新任务替换的历史轮次只能回看，不能假装点一下就能续上。
     */
    suspend fun retryTask(scope: String, id: String): Boolean = withContext(Dispatchers.IO) {
        val current = dao.job(scope) ?: return@withContext false
        if (current.id != id || current.status !in setOf("paused", "failed", "interrupted")) {
            return@withContext false
        }
        control(scope, "running")
        dao.job(scope)?.status == "running"
    }

    data class Plan(
        val scope: String,
        val sources: List<HistorySource>,
        val modelId: String,
        val fingerprint: String,
        val destination: String,
        val nodes: Long,
        val serializedChars: Long,
        /** 任务历史里展示的来源范围；只存文字，回看时不重新查聊天。 */
        val rangeLabel: String = "",
    )

    /**
     * 日常“整理我的记忆”：只看上次整理之后有改动的聊天，没有就不必再花钱。
     * 返回 null 表示没有任何新内容。
     */
    suspend fun prepareIncremental(scope: String, assistant: Assistant): Plan? = withContext(Dispatchers.IO) {
        val since = dao.state(scope)?.lastOrganizedAt ?: 0L
        val sources = if (since <= 0L) {
            conversations.historySources(Long.MIN_VALUE, Long.MAX_VALUE)
        } else {
            conversations.historySourcesUpdatedSince(since)
        }
        if (sources.isEmpty()) return@withContext null
        prepare(scope, assistant, sources)
    }

    suspend fun lastOrganizedAt(scope: String): Long = withContext(Dispatchers.IO) {
        dao.state(scope)?.lastOrganizedAt ?: 0L
    }

    suspend fun prepare(scope: String, assistant: Assistant, sources: List<HistoryConversationSource>): Plan {
        require(sources.isNotEmpty())
        val settings = settingsStore.awaitLoaded()
        val model = sequenceOf(settings.memoryModelId, settings.fastModelId, assistant.chatModelId ?: settings.chatModelId)
            .mapNotNull { settings.findModelById(it) }
            .firstOrNull { it.type == ModelType.CHAT && it.findProvider(settings.providers)?.enabled == true }
            ?: error("请先配置可用的记忆整理模型")
        val provider = model.findProvider(settings.providers) ?: error("请先配置提供商")
        val transport = provider.historyDestination()
        val sizes = sources.map { it.id }.chunked(300).map { nodes.estimateHistory(it) }
        return Plan(
            scope, sources.map { HistorySource(it.id, it.updatedAt) }, model.id.toString(),
            fingerprint(settings, provider, model),
            listOf(provider.name, model.modelId, transport).joinToString(" · "),
            sizes.sumOf { it.nodes }, sizes.sumOf { it.characters },
            rangeLabel = sourceRangeLabel(sources),
        )
    }

    private fun ProviderSetting.historyDestination(): String {
        val endpoint = when (this) {
            is ProviderSetting.OpenAI -> baseUrl + if (useResponseApi) responsesPath else chatCompletionsPath
            is ProviderSetting.Claude -> baseUrl
            is ProviderSetting.ChatGPT -> "https://chatgpt.com"
            is ProviderSetting.Grok -> "https://api.x.ai"
            is ProviderSetting.Google -> when {
                !vertexAI -> baseUrl
                useInteractionsApi && useServiceAccount && location in setOf("us", "eu") ->
                    "https://aiplatform.$location.rep.googleapis.com"
                else -> "https://aiplatform.googleapis.com"
            }
        }
        val url = endpoint.toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
            "历史提取需要使用 HTTPS 模型服务"
        }
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
    }

    suspend fun resumePending() = withContext(Dispatchers.IO) {
        controls.withLock {
            dao.running().forEach { job ->
                if (!HistoryMemoryWorker.isActive(context, job.scope)) {
                    saveJob(
                        job.copy(
                            status = "paused",
                            failure = "上次整理被系统中断，已保存进度；可以继续，也可以重新开始整理。",
                            generation = job.generation + 1,
                        )
                    )
                    return@forEach
                }
                if (job.deviceConsent != consentId) {
                    // 从备份恢复的任务不属于这台设备：停下并说明，而不是拿旧授权继续发聊天记录
                    saveJob(
                        job.copy(
                            status = "paused",
                            failure = "这份整理任务来自其他设备的备份，需要重新确认整理范围后继续。",
                            generation = job.generation + 1,
                        )
                    )
                } else {
                    HistoryMemoryWorker.enqueue(context, job.scope, job.id, job.generation, ExistingWorkPolicy.KEEP).result.get()
                }
            }
        }
    }

    suspend fun start(plan: Plan): String = withContext(Dispatchers.IO) {
        controls.withLock {
            val job = persistConfirmedPlan(plan)
            HistoryMemoryWorker.enqueue(context, job.scope, job.id, job.generation, ExistingWorkPolicy.REPLACE).result.get()
            job.id
        }
    }

    internal suspend fun persistConfirmedPlan(plan: Plan): HistoryMemoryJob {
        // 先把“数据库说 running、实际早就没有 worker”的僵尸任务释放掉，
        // 否则用户会一直被“已有任务正在运行或等待处理”挡住，永远无法重新整理。
        releaseStaleLock(plan.scope)
        return database.withTransaction {
        val previous = dao.job(plan.scope)
        check(
            previous == null ||
                previous.status in setOf("finished", "cancelled", "failed", "paused", "interrupted"),
        ) {
            "当前任务仍在运行或等待处理，请先在记忆页结束或暂停该任务"
        }
        // 不删除上一任务的候选与批次：候选属于用户的记忆数据，任务行会被替换但候选靠 scope 保留。
        // 旧实现这里 clearCandidates，等于用户误操作或取消后数据直接消失。
        val job = HistoryMemoryJob(
            plan.scope, Uuid.random().toString(), consentId, plan.modelId, plan.fingerprint,
            Json.encodeToString(plan.sources), plan.sources.size,
            createdAt = System.currentTimeMillis(),
        ).also { dao.put(it) }
        // 建任务的同时落一条历史：这一轮从开始就有记录，结束/失败只更新同一行。
        dao.putTaskLog(
            HistoryMemoryTaskLog(
                id = job.id,
                scope = job.scope,
                jobGeneration = job.generation,
                createdAt = job.createdAt,
                status = job.status,
                sourceCount = plan.sources.size,
                rangeLabel = plan.rangeLabel,
            )
        )
        dao.trimTaskLogs(plan.scope, TASK_LOG_KEEP)
        job
        }
    }

    /** 运行中但实际没有 WorkManager 任务：标记为暂停，让用户能继续或重新开始。 */
    private suspend fun releaseStaleLock(scope: String) = withContext(Dispatchers.IO) {
        val current = dao.job(scope) ?: return@withContext
        if (current.status != "running") return@withContext
        if (HistoryMemoryWorker.isActive(context, scope)) return@withContext
        saveJob(
            current.copy(
                status = "paused",
                failure = "上次整理被系统中断，已保存进度；可以继续，也可以重新开始整理。",
                generation = current.generation + 1,
            )
        )
    }

    suspend fun control(scope: String, status: String) = withContext(Dispatchers.IO) {
        controls.withLock {
            val job = database.withTransaction {
                val current = dao.job(scope) ?: return@withTransaction null
                val allowed = when (status) {
                    "running" -> current.status in setOf("paused", "failed", "running", "interrupted")
                    "paused" -> current.status == "running"
                    "cancelled" -> current.status != "cancelled"
                    else -> false
                }
                if (!allowed) return@withTransaction null
                // 取消只停止任务，不删除已经提取出来的候选：它们还能审核、保存或恢复。
                current.copy(
                    status = status,
                    failure = null,
                    generation = current.generation + 1,
                    // 取消后 finishedAt 由 saveJob 补上；继续跑的时候清回 0
                    finishedAt = if (status == "cancelled") System.currentTimeMillis() else 0,
                    // 用户明确继续时重新计算单次预算，避免一点“继续”就又被预算拦住
                    budgetAckTokens = if (status == "running" && current.status == "paused") {
                        current.estimatedTokens
                    } else {
                        current.budgetAckTokens
                    },
                ).also { saveJob(it) }
            } ?: return@withContext
            if (status == "running") HistoryMemoryWorker.enqueue(context, scope, job.id, job.generation, ExistingWorkPolicy.REPLACE).result.get()
            else HistoryMemoryWorker.cancel(context, scope).result.get()
            Unit
        }
    }

    private fun fingerprint(settings: Settings, provider: ProviderSetting, model: Model): String =
        historyDigest(JsonInstant.encodeToString(provider.consentConfiguration()) +
            JsonInstant.encodeToString(model.copy(providerOverwrite = null)) +
            (provider as? ProviderSetting.Account)?.credential?.accountId.orEmpty() +
            JsonInstant.encodeToString(settings.networkSetting))

    private fun ProviderSetting.consentConfiguration(): ProviderSetting {
        val config = if (this is ProviderSetting.Account) withCredential(null) else this
        return config.copyProvider(models = emptyList())
    }

    private class ExtractionFailure(val reason: String) : IllegalStateException()

    /** Return true when this work unit used its time slice and should resume at its durable cursor. */
    suspend fun run(scope: String, id: String, generation: Long): Boolean = withContext(Dispatchers.IO) {
        val start = TimeSource.Monotonic.markNow()
        val sources = dao.job(scope)?.takeIf { it.id == id && it.generation == generation && it.status == "running" }
            ?.let { Json.decodeFromString<List<HistorySource>>(it.sources) } ?: return@withContext false
        try {
            while (start.elapsedNow() < 6.minutes) {
                val job = dao.job(scope)?.takeIf { it.id == id && it.generation == generation && it.status == "running" }
                    ?: return@withContext false
                val source = sources.getOrNull(job.sourceIndex)
                if (source == null) {
                    checkpoint(job, job.copy(status = "finished", finishedAt = System.currentTimeMillis()))
                    return@withContext false
                }
                if (job.deviceConsent != consentId) throw ExtractionFailure("本机授权已失效，请重新确认提取范围")
                val settings = settingsStore.awaitLoaded()
                if (scope != MemoryRepository.GLOBAL_MEMORY_ID && settings.assistants.none { it.id.toString() == scope }) {
                    throw ExtractionFailure("目标助手已不存在")
                }
                val model = settings.findModelById(Uuid.parse(job.modelId))
                    ?: throw ExtractionFailure("已确认的模型已移除，请重新创建任务")
                val provider = model.findProvider(settings.providers)
                    ?: throw ExtractionFailure("已确认的提供商已移除，请重新创建任务")
                if (!provider.enabled || fingerprint(settings, provider, model) != job.providerFingerprint) {
                    throw ExtractionFailure("模型或网络配置已变更，请重新确认提取范围")
                }
                val revision = conversations.historyRevision(source.id)
                if (revision == null || revision != source.revision) {
                    throw ExtractionFailure("来源会话已修改或删除，请重新选择提取范围")
                }
                val (cursor, excerpts) = readBatch(job, source.id)
                if (excerpts.isEmpty()) {
                    checkpoint(job, cursor)
                    continue
                }
                if (conversations.historyRevision(source.id) != source.revision) {
                    throw ExtractionFailure("来源会话已修改，请重新选择提取范围")
                }
                if (dao.job(scope) != job) return@withContext false
                // 预算在真正花钱之前检查：超了就暂停，等用户下次明确继续。
                budgetStop(scope, job, settings)?.let { reason ->
                    saveJob(job.copy(status = "paused", failure = reason))
                    return@withContext false
                }
                val payload = Json.encodeToString(excerpts.map { it.text })
                val digest = historyDigest(payload)
                // 同一个任务内按批次去重；跨任务再按全局指纹去重，同一段聊天永远不会第二次计费。
                if (dao.processed(id, digest) || dao.seen(digest)) {
                    checkpoint(job, cursor, digest = digest)
                    continue
                }
                val result = withTimeout(2.minutes) {
                    providers.getProviderByType(provider).generateText(
                        providerSetting = provider,
                        messages = listOf(UIMessage.system(historyExtractionPrompt), UIMessage.user(payload)),
                        params = TextGenerationParams(model = model.copy(tools = emptySet(), customBodies = emptyList()), maxTokens = HISTORY_OUTPUT_TOKENS, privateRequest = true),
                    )
                }
                // Token 拆成输入/输出分别记账：历史里要能解释“这轮为什么花了这么多”。
                // 提供商没给 usage 时按字符数估算，和 estimatedTokens 的兜底口径一致。
                val inputTokens = result.usage?.promptTokens?.toLong()?.takeIf { it > 0 }
                    ?: ((payload.length + historyExtractionPrompt.length + 1L) / 2L)
                val outputTokens = result.usage?.completionTokens?.toLong()?.takeIf { it > 0 }
                    ?: ((result.message.toText().length + 1L) / 2L)
                val facts = try {
                    parseHistoryCandidates(result.message.toText(), excerpts, id, source.id)
                } catch (_: IllegalArgumentException) {
                    throw ExtractionFailure("模型返回的候选格式无效，可重试当前批次")
                }
                checkpoint(
                    job,
                    cursor.copy(
                        batches = job.batches + 1,
                        estimatedTokens = job.estimatedTokens + (result.usage?.totalTokens?.toLong()?.takeIf { it > 0 }
                            ?: (payload.length + historyExtractionPrompt.length + result.message.toText().length + 1L) / 2L),
                    ),
                    digest,
                    facts,
                    rememberDigest = true,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                )
            }
            true
        } catch (_: TimeoutCancellationException) {
            fail(scope, id, generation, "模型请求超时，可重试当前批次")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractionFailure) {
            fail(scope, id, generation, e.reason)
        } catch (_: IOException) {
            fail(scope, id, generation, "网络或模型请求失败，可重试当前批次")
        } catch (_: Exception) {
            fail(scope, id, generation, "读取历史或调用模型失败，可重试当前批次")
        }
    }

    private suspend fun fail(scope: String, id: String, generation: Long, reason: String): Boolean {
        database.withTransaction {
            val current = dao.job(scope)
            if (current?.id == id && current.generation == generation && current.status == "running") {
                saveJob(current.copy(status = "failed", failure = reason, finishedAt = System.currentTimeMillis()))
            }
        }
        return false
    }

    private suspend fun checkpoint(
        previous: HistoryMemoryJob,
        next: HistoryMemoryJob,
        digest: String? = null,
        facts: List<HistoryMemoryCandidate> = emptyList(),
        rememberDigest: Boolean = false,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
    ) = database.withTransaction {
        if (dao.job(previous.scope) != previous) return@withTransaction
        if (digest != null) dao.markProcessed(HistoryMemoryBatch(previous.id, digest))
        if (rememberDigest && digest != null) {
            dao.markSeen(HistoryMemorySeen(digest, System.currentTimeMillis()))
        }
        if (facts.isNotEmpty()) dao.addCandidates(facts.map { it.copy(scope = previous.scope) })
        dao.put(next)
        // 历史行跟着游标走：处理进度来自 sourceIndex，候选数字现数库，Token 按批次累加。
        recordTaskStatus(next)
        dao.syncTaskCounters(next.id, next.sourceIndex)
        if (inputTokens > 0 || outputTokens > 0) dao.addTaskTokens(next.id, inputTokens, outputTokens)
        val existing = dao.state(next.scope) ?: MemoryScopeState(next.scope)
        val today = java.time.LocalDate.now().toString()
        val usedToday = (if (existing.dailyDay == today) existing.dailyTokens else 0L) +
            (next.estimatedTokens - previous.estimatedTokens).coerceAtLeast(0)
        var state = existing.copy(dailyDay = today, dailyTokens = usedToday)
        // 任务完成时记下水位，下一轮只处理这之后有改动的聊天；首页也用它显示“最近整理”。
        if (next.status == "finished" && previous.status != "finished") {
            val saved = dao.countNow(next.id, "saved")
            val pending = dao.countNow(next.id, "pending")
            val ignored = dao.countNow(next.id, "discarded") + dao.countNow(next.id, "duplicate")
            state = state.copy(
                lastOrganizedAt = next.finishedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                lastResult = "新增 $saved 条 · 需要确认 $pending 条 · 忽略 $ignored 条",
            )
        }
        dao.putState(state)
    }

    /** 达到单次/每日预算时返回给用户看的原因，否则 null。 */
    private suspend fun budgetStop(
        scope: String,
        job: HistoryMemoryJob,
        settings: Settings,
    ): String? {
        val runBudget = settings.memoryRunTokenBudget
        if (runBudget > 0 && job.estimatedTokens - job.budgetAckTokens >= runBudget) {
            return "已达到单次整理上限（约 $runBudget Token），已暂停。确认后可以继续。"
        }
        val dailyBudget = settings.memoryDailyTokenBudget
        if (dailyBudget > 0) {
            val state = dao.state(scope)
            val today = java.time.LocalDate.now().toString()
            val used = if (state?.dailyDay == today) state.dailyTokens else 0L
            if (used >= dailyBudget) {
                return "今天的整理预算（约 $dailyBudget Token）已经用完，明天会自动恢复。"
            }
        }
        return null
    }

    private suspend fun readBatch(job: HistoryMemoryJob, conversationId: String): Pair<HistoryMemoryJob, List<HistoryExcerpt>> {
        var cursor = job
        val excerpts = mutableListOf<HistoryExcerpt>()
        var chars = 0
        val continuation = if (job.textOffset == 0) emptyList() else listOf(
            nodes.historyNode(conversationId, job.nodeId) ?: throw ExtractionFailure("来源消息已被删除")
        )
        val page = continuation + nodes.nextHistoryNodes(conversationId, job.nodeIndex, job.nodeId, 12)
        if (page.isEmpty()) {
            return job.copy(sourceIndex = job.sourceIndex + 1, nodeIndex = -1, nodeId = "", textOffset = 0) to excerpts
        }
        for (node in page) {
            val messages = JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
            val segments = messages.asSequence()
                .filter { it.role == MessageRole.USER && !it.isNoticeOnly && !it.isContextCheckpoint }
                .flatMap { msg -> splitHistoryText(msg.toText()).filter(String::isNotBlank).map {
                    HistoryExcerpt(msg.id.toString(), msg.createdAt.toString(), it)
                } }
            val consumed = if (node.id == job.nodeId) job.textOffset else 0
            for ((index, excerpt) in segments.drop(consumed).withIndex()) {
                if (chars + excerpt.text.length > HISTORY_INPUT_CHARS || excerpts.size == 24) {
                    return cursor to excerpts
                }
                excerpts += excerpt
                chars += excerpt.text.length
                cursor = cursor.copy(nodeIndex = node.nodeIndex, nodeId = node.id, textOffset = consumed + index + 1)
            }
            cursor = cursor.copy(nodeIndex = node.nodeIndex, nodeId = node.id, textOffset = 0)
        }
        return cursor to excerpts
    }

    suspend fun resolve(
        scope: String, id: String, key: String, corrected: String?, mergeInto: MemoryFile? = null,
    ) = controls.withLock {
        val candidate = dao.candidate(id, key) ?: return@withLock
        check(dao.job(scope)?.id == id)
        if (corrected == null) {
            dao.setStatus(id, key, "discarded")
            return@withLock
        }
        val fact = corrected.cleanFact()
        require(fact.length in 4..600 && fact.any { it.isLetterOrDigit() }) { "请输入要保存的事实" }
        require(findForbiddenMemoryContent(fact) == null) { "记忆正文包含证件号或卡号" }
        val normalizedKey = factKey(fact)
        val source = "<!-- history-source ${candidate.conversationId} ${candidate.messageId} ${candidate.sourceTime} ${candidate.jobId} -->"
        if (mergeInto != null) {
            check(!mergeInto.isOutdated) { "请先在记忆管理中恢复该记忆的召回" }
            val current = memories.getFile(scope, mergeInto.path)
            if (current?.containsFact(normalizedKey) == true) {
                dao.setStatus(id, key, if (source in current.body) "saved" else "duplicate")
                return@withLock
            }
            check(current?.version == mergeInto.version) { "旧记忆已变更，请重新打开候选检查" }
            when (val result = memories.writeFile(scope, mergeInto.path, mergeInto.appendedHistoryFact(fact, source), mergeInto.version)) {
                is MemoryWriteResult.Success -> dao.setStatus(id, key, "saved")
                is MemoryWriteResult.Conflict -> error("旧记忆已变更，请重新打开候选检查")
                is MemoryWriteResult.Rejected -> error(result.reason)
            }
            return@withLock
        }
        val path = historyPath(candidate.copy(fact = fact))
        val existing = memories.findImportedFact(scope, normalizedKey.take(32))
            ?: memories.exactTextCandidates(scope, fact.trimEnd { it in "。.!！?？" })
                .firstOrNull { it.containsFact(normalizedKey) }
        if (existing != null && existing.path != path) {
            check(existing.containsFact(normalizedKey)) {
                "已有记忆内容不同，请在记忆管理中检查"
            }
            dao.setStatus(id, key, "duplicate")
            return@withLock
        }
        val result = if (existing?.isDeleted == false) MemoryWriteResult.Conflict(existing) else {
            val file = MemoryFile(path, "", 0)
            val content = file.contentWith(
                title = candidate.subject,
                description = candidate.subject,
                aliases = emptyList(),
                body = "- [stated] $fact\n\n$source",
            )
            memories.writeFile(scope, path, content, MemoryRepository.NEW_FILE_VERSION)
        }
        when (result) {
            is MemoryWriteResult.Success -> dao.setStatus(id, key, "saved")
            is MemoryWriteResult.Conflict -> {
                check(result.current?.containsFact(normalizedKey) == true) { "已有记忆内容不同，请在记忆管理中检查" }
                val recoveredWrite = result.current?.body?.lineSequence()?.any {
                    it.startsWith("<!-- history-source ") && it.endsWith(" ${candidate.jobId} -->")
                } == true
                dao.setStatus(id, key, if (recoveredWrite) "saved" else "duplicate")
            }
            is MemoryWriteResult.Rejected -> error(result.reason)
        }
    }

    /**
     * 关联旧记忆：只推荐和候选在字面上明显同主题的记忆。
     * 旧实现按关键词检索，一个共同词就会推荐一堆无关记忆，用户在审核时根本判断不了。
     */
    suspend fun related(scope: String, candidate: HistoryMemoryCandidate): List<MemoryFile> =
        withContext(Dispatchers.Default) {
            val grams = MemoryTriageEngine.gramsOf(candidate.subject + " " + candidate.fact)
            if (grams.isEmpty()) return@withContext emptyList()
            memories.getFiles(scope)
                .asSequence()
                .filter { !it.isDeleted && !it.isOutdated }
                .map { file ->
                    val target = MemoryTriageEngine.gramsOf(
                        file.title + " " + file.description + " " + file.recallBody
                    )
                    file to MemoryTriageEngine.containment(grams, target)
                }
                .filter { it.second >= 0.3 }
                .sortedByDescending { it.second }
                .take(3)
                .map { it.first }
                .toList()
        }


    // ---- 批量审核：221 条候选不该逐条点 ----

    /** 批量保存的行为，界面上只有“更保守”和“按建议”两种可选。 */
    enum class BulkMode {
        /** 只保存明确的新事实，合并/更新/确认一律留给用户 */
        SAVE_NEW_ONLY,

        /** 按本地建议执行：新事实直接存，重复的合并或更新，需要确认的不动 */
        APPLY_SUGGESTIONS,

        /** 用户明确勾选的条目直接保存 */
        SAVE_SELECTED,
    }

    data class BulkResult(
        val saved: Int = 0,
        val merged: Int = 0,
        val updated: Int = 0,
        val duplicate: Int = 0,
        val ignored: Int = 0,
        val pending: Int = 0,
        val failed: Int = 0,
        val failureReasons: List<String> = emptyList(),
        /** 撤销时恢复了几条记忆文件 */
        val restoredFiles: Int = 0,
        /** 撤销时因为内容已被改动而跳过的文件数 */
        val skippedFiles: Int = 0,
    ) {
        val changed: Int get() = saved + merged + updated
    }

    @kotlinx.serialization.Serializable
    private data class UndoFile(
        val path: String,
        /** null 表示这条记忆是本次新建的，撤销时删除 */
        val before: String?,
        val afterHash: String,
        val updatedAt: Long,
    )

    @kotlinx.serialization.Serializable
    private data class UndoPayload(val files: List<UndoFile>, val keys: List<String>)

    /** 审核页需要一次拿到全部建议；不调用模型，纯本地计算。 */
    suspend fun suggestions(scope: String): Map<String, MemoryTriage> = withContext(Dispatchers.Default) {
        val pending = dao.snapshotOfStatus(scope, "pending", MAX_BULK)
        if (pending.isEmpty()) return@withContext emptyMap()
        val files = memories.getFiles(scope)
        pending.associate { it.key to MemoryTriageEngine.evaluate(it, files) }
    }

    /**
     * 批量保存选中项。
     *
     * [applySuggestions] 为 true 时遵循本地建议：需要确认的继续留在待审核（不落盘），
     * 建议忽略的标记为忽略；为 false 时按用户选择全部保存，只有完全重复的会被跳过。
     */
    suspend fun saveSelected(
        scope: String,
        keys: Set<String>,
        mode: BulkMode,
    ): BulkResult = controls.withLock {
        val pending = dao.snapshotOfStatus(scope, "pending", MAX_BULK).filter { it.key in keys }
        if (pending.isEmpty()) return@withLock BulkResult()
        val files = memories.getFiles(scope)
        var saved = 0
        var merged = 0
        var updated = 0
        var duplicate = 0
        var ignored = 0
        var pendingCount = 0
        var failed = 0
        val reasons = mutableListOf<String>()
        val undoFiles = mutableListOf<UndoFile>()
        val undoKeys = mutableListOf<String>()
        var undoChars = 0L
        for (candidate in pending) {
            val triage = MemoryTriageEngine.evaluate(candidate, files)
            val automatic = mode != BulkMode.SAVE_SELECTED
            if (automatic && triage.suggestion == MemorySuggestion.CONFIRM) {
                pendingCount++
                continue
            }
            if (automatic && triage.suggestion == MemorySuggestion.IGNORE) {
                val alreadyKnown = triage.targetPath != null
                dao.setStatus(candidate.jobId, candidate.key, if (alreadyKnown) "duplicate" else "discarded")
                if (alreadyKnown) duplicate++ else ignored++
                undoKeys += candidate.key
                continue
            }
            if (mode == BulkMode.SAVE_NEW_ONLY &&
                (triage.suggestion == MemorySuggestion.MERGE || triage.suggestion == MemorySuggestion.UPDATE)
            ) {
                pendingCount++
                continue
            }
            val mergeTarget = if (mode == BulkMode.APPLY_SUGGESTIONS) triage.targetPath else null
            when (val outcome = writeCandidate(scope, candidate.jobId, candidate, mergeTarget, triage.suggestion)) {
                is WriteOutcome.Saved -> {
                    when (outcome.kind) {
                        WriteKind.CREATED -> saved++
                        WriteKind.MERGED -> merged++
                        WriteKind.UPDATED -> updated++
                    }
                    dao.setStatus(candidate.jobId, candidate.key, "saved")
                    undoKeys += candidate.key
                    if (undoChars < MAX_UNDO_CHARS) {
                        undoFiles += outcome.undo
                        undoChars += (outcome.undo.before?.length ?: 0) + outcome.undo.afterHash.length + 96L
                    }
                }
                WriteOutcome.Duplicate -> {
                    dao.setStatus(candidate.jobId, candidate.key, "duplicate")
                    duplicate++
                    undoKeys += candidate.key
                }
                is WriteOutcome.Failed -> {
                    failed++
                    if (reasons.size < 5) reasons += "${candidate.subject}：${outcome.reason}"
                }
            }
        }
        if (undoKeys.isNotEmpty()) {
            dao.addUndo(
                MemoryBulkUndo(
                    id = Uuid.random().toString(),
                    scope = scope,
                    jobId = "",
                    createdAt = System.currentTimeMillis(),
                    summary = "新增 $saved 条 · 合并 $merged 条 · 更新 $updated 条 · 忽略 $ignored 条 · 需确认 $pendingCount 条",
                    payload = Json.encodeToString(UndoPayload(undoFiles, undoKeys)),
                )
            )
            dao.trimUndo(scope)
            dao.putState(
                (dao.state(scope) ?: MemoryScopeState(scope)).copy(
                    lastResult = "新增 $saved 条 · 合并 $merged 条 · 更新 $updated 条 · 忽略 $ignored 条 · 需确认 $pendingCount 条",
                )
            )
        }
        BulkResult(saved, merged, updated, duplicate, ignored, pendingCount, failed, reasons)
    }

    suspend fun pendingKeys(scope: String): List<String> = withContext(Dispatchers.IO) {
        dao.snapshotOfStatus(scope, "pending", MAX_BULK).map { it.key }
    }

    /** 某一轮任务自己产生的待审核候选，用于“整理完成后自动保存”，不碰其他轮次。 */
    suspend fun pendingKeysOfJob(jobId: String): List<String> = withContext(Dispatchers.IO) {
        dao.pendingSnapshot(jobId, MAX_BULK).map { it.key }
    }

    /** 已跳过的候选数量与内容，供“恢复误跳过”入口使用。 */
    suspend fun skippedCount(scope: String): Int = withContext(Dispatchers.IO) {
        dao.snapshotOfStatus(scope, "discarded", MAX_BULK).size
    }

    /**
     * 把误跳过的候选恢复成待审核。只改状态，正文、证据、来源原样保留，不调用模型、不花钱。
     * [keys] 为空时恢复该记忆库全部已跳过候选。
     */
    suspend fun restoreSkipped(scope: String, keys: Set<String> = emptySet()): Int = controls.withLock {
        val skipped = dao.snapshotOfStatus(scope, "discarded", MAX_BULK)
            .filter { keys.isEmpty() || it.key in keys }
        if (skipped.isEmpty()) return@withLock 0
        dao.setStatusesOfScope(scope, skipped.map { it.key }, "pending")
        val state = dao.state(scope) ?: MemoryScopeState(scope)
        dao.putState(state.copy(lastResult = "已恢复 ${skipped.size} 条被跳过的候选"))
        skipped.size
    }

    /** 批量跳过：候选保留在库里，随时可以再看。 */
    suspend fun skipSelected(scope: String, keys: Set<String>): Int = controls.withLock {
        val pending = dao.snapshotOfStatus(scope, "pending", MAX_BULK).filter { it.key in keys }
        if (pending.isEmpty()) return@withLock 0
        dao.setStatusesOfScope(scope, pending.map { it.key }, "discarded")
        dao.addUndo(
            MemoryBulkUndo(
                id = Uuid.random().toString(),
                scope = scope,
                jobId = "",
                createdAt = System.currentTimeMillis(),
                summary = "跳过 ${pending.size} 条",
                payload = Json.encodeToString(UndoPayload(emptyList(), pending.map { it.key })),
            )
        )
        dao.trimUndo(scope)
        pending.size
    }

    private sealed interface WriteOutcome {
        data class Saved(val kind: WriteKind, val undo: UndoFile) : WriteOutcome
        data object Duplicate : WriteOutcome
        data class Failed(val reason: String) : WriteOutcome
    }

    private enum class WriteKind { CREATED, MERGED, UPDATED }

    private suspend fun writeCandidate(
        scope: String,
        jobId: String,
        candidate: HistoryMemoryCandidate,
        targetPath: String?,
        suggestion: MemorySuggestion,
    ): WriteOutcome {
        val fact = candidate.fact.cleanFact()
        if (fact.length !in 4..600 || fact.count { it.isLetterOrDigit() } < 4) {
            return WriteOutcome.Failed("内容为空或包含非法字符")
        }
        val key = factKey(fact)
        val source = "<!-- history-source ${candidate.conversationId} ${candidate.messageId} ${candidate.sourceTime} $jobId -->"
        val target = targetPath?.let { memories.getFileIncludingDeleted(scope, it) }
        if (target != null && target.containsFact(key)) return WriteOutcome.Duplicate
        return if (target != null && !target.isDeleted) {
            val merged = target.appendedHistoryFact(fact, source)
            when (val result = memories.writeFile(scope, target.path, merged, target.version)) {
                is MemoryWriteResult.Success -> WriteOutcome.Saved(
                    if (suggestion == MemorySuggestion.UPDATE) WriteKind.UPDATED else WriteKind.MERGED,
                    UndoFile(target.path, target.content, historyDigest(merged), target.updatedAt),
                )
                is MemoryWriteResult.Conflict -> WriteOutcome.Failed("记忆已变更，请重试")
                is MemoryWriteResult.Rejected -> WriteOutcome.Failed(result.reason)
            }
        } else {
            val path = historyPath(candidate)
            val existing = memories.getFile(scope, path)
            if (existing != null) {
                if (existing.containsFact(key)) return WriteOutcome.Duplicate
                val merged = existing.appendedHistoryFact(fact, source)
                when (val result = memories.writeFile(scope, path, merged, existing.version)) {
                    is MemoryWriteResult.Success -> WriteOutcome.Saved(
                        WriteKind.MERGED,
                        UndoFile(path, existing.content, historyDigest(merged), existing.updatedAt),
                    )
                    is MemoryWriteResult.Conflict -> WriteOutcome.Failed("记忆已变更，请重试")
                    is MemoryWriteResult.Rejected -> WriteOutcome.Failed(result.reason)
                }
            } else {
                val file = MemoryFile(path, "", 0)
                val content = file.contentWith(
                    title = candidate.subject,
                    description = candidate.subject,
                    aliases = emptyList(),
                    body = "- [stated] $fact\n\n$source",
                )
                when (val result = memories.writeFile(scope, path, content, MemoryRepository.NEW_FILE_VERSION)) {
                    is MemoryWriteResult.Success -> WriteOutcome.Saved(
                        WriteKind.CREATED,
                        UndoFile(path, null, historyDigest(content), 0),
                    )
                    is MemoryWriteResult.Conflict -> WriteOutcome.Failed("路径已被占用，请重试")
                    is MemoryWriteResult.Rejected -> WriteOutcome.Failed(result.reason)
                }
            }
        }
    }

    /** 撤销上一次批量操作：内容没被改过的文件原样恢复，候选回到待审核。 */
    suspend fun undoLastBulk(scope: String): BulkResult? = controls.withLock {
        val record = dao.lastUndo(scope) ?: return@withLock null
        val payload = runCatching { Json.decodeFromString<UndoPayload>(record.payload) }.getOrNull()
            ?: return@withLock null
        var restored = 0
        var skipped = 0
        for (file in payload.files) {
            val current = memories.getFileIncludingDeleted(scope, file.path)
            if (current == null || historyDigest(current.content) != file.afterHash) {
                skipped++
                continue
            }
            val ok = if (file.before == null) {
                memories.purgeIfContentMatches(scope, file.path, current.content)
            } else {
                memories.restoreSnapshot(scope, file.path, file.before, file.updatedAt)
            }
            if (ok) restored++ else skipped++
        }
        if (payload.keys.isNotEmpty()) {
            dao.setStatusesOfScope(scope, payload.keys, "pending")
        }
        dao.clearUndo(record.id)
        BulkResult(restoredFiles = restored, skippedFiles = skipped, pending = payload.keys.size)
    }

    /**
     * 任务行和历史行必须一起写：否则会出现“历史里还写着正在整理、实际任务早就停了”，
     * 用户按历史判断该不该重试就会被误导。
     */
    private suspend fun saveJob(job: HistoryMemoryJob) {
        dao.put(job)
        recordTaskStatus(job)
    }

    private suspend fun recordTaskStatus(job: HistoryMemoryJob) {
        dao.setTaskStatus(
            id = job.id,
            status = job.status,
            finishedAt = when {
                job.finishedAt > 0 -> job.finishedAt
                job.status in FINAL_STATUSES -> System.currentTimeMillis()
                else -> 0
            },
            error = job.failure,
            generation = job.generation,
        )
    }

    /** 来源范围说明：会话数 + 最早/最晚时间，回看时不用再去查聊天。 */
    private fun sourceRangeLabel(sources: List<HistoryConversationSource>): String {
        val from = sources.minOf { it.createdAt }.takeIf { it > 0 }
        val until = sources.maxOf { it.updatedAt }.takeIf { it > 0 }
        if (from == null || until == null) return "${sources.size} 个会话"
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        fun day(millis: Long) =
            format.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        return "${sources.size} 个会话 · ${day(from)} ~ ${day(until)}"
    }

    private companion object {
        const val MAX_BULK = 2_000
        /** 历史只留最近 100 轮：够回看，也不会把库撑大。 */
        const val TASK_LOG_KEEP = 100
        val FINAL_STATUSES = setOf("finished", "cancelled", "failed", "interrupted")
        const val MAX_UNDO_CHARS = 3_000_000L
    }

    private fun MemoryFile.containsFact(key: String): Boolean = body.lineSequence().any {
        it.startsWith("- [stated] ") && factKey(it.removePrefix("- [stated] ")) == key
    }

    private fun historyPath(candidate: HistoryMemoryCandidate): String {
        val folder = when (candidate.category) {
            "people" -> "people"
            "study", "projects", "ongoing", "decisions" -> "areas"
            else -> "topics"
        }
        return "/$folder/history-${factKey(candidate.fact).take(32)}.md"
    }
}
