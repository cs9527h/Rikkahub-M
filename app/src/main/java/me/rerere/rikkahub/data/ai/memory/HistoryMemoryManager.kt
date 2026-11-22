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
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.IOException
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
    fun candidates(id: String) = dao.pending(id)
    fun count(id: String, status: String) = dao.count(id, status)
    fun recalls(scope: String) = dao.recalls(scope)
    suspend fun clearRecalls(scope: String) = dao.clearRecalls(scope)
    suspend fun sources(from: Long, until: Long) = conversations.historySources(from, until)

    data class Plan(
        val scope: String,
        val sources: List<HistorySource>,
        val modelId: String,
        val fingerprint: String,
        val destination: String,
        val nodes: Long,
        val serializedChars: Long,
    )

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
                HistoryMemoryWorker.enqueue(context, job.scope, job.id, job.generation, ExistingWorkPolicy.KEEP).result.get()
            }
        }
    }

    suspend fun start(plan: Plan) = withContext(Dispatchers.IO) {
        controls.withLock {
            val job = persistConfirmedPlan(plan)
            HistoryMemoryWorker.enqueue(context, job.scope, job.id, job.generation, ExistingWorkPolicy.REPLACE).result.get()
            Unit
        }
    }

    internal suspend fun persistConfirmedPlan(plan: Plan): HistoryMemoryJob = database.withTransaction {
        val previous = dao.job(plan.scope)
        check(previous == null || previous.status in setOf("finished", "cancelled")) { "当前任务仍在运行或等待处理" }
        previous?.let {
            dao.clearCandidates(it.id)
            dao.clearBatches(it.id)
        }
        HistoryMemoryJob(
            plan.scope, Uuid.random().toString(), consentId, plan.modelId, plan.fingerprint,
            Json.encodeToString(plan.sources), plan.sources.size,
        ).also { dao.put(it) }
    }

    suspend fun control(scope: String, status: String) = withContext(Dispatchers.IO) {
        controls.withLock {
            val job = database.withTransaction {
                val current = dao.job(scope) ?: return@withTransaction null
                val allowed = when (status) {
                    "running" -> current.status in setOf("paused", "failed", "running")
                    "paused" -> current.status == "running"
                    "cancelled" -> current.status != "cancelled"
                    else -> false
                }
                if (!allowed) return@withTransaction null
                if (status == "cancelled") {
                    dao.clearCandidates(current.id)
                    dao.clearBatches(current.id)
                }
                current.copy(status = status, failure = null, generation = current.generation + 1).also { dao.put(it) }
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
                    checkpoint(job, job.copy(status = "finished"))
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
                val payload = Json.encodeToString(excerpts.map { it.text })
                val digest = historyDigest(payload)
                if (dao.processed(id, digest)) {
                    checkpoint(job, cursor)
                    continue
                }
                val result = withTimeout(2.minutes) {
                    providers.getProviderByType(provider).generateText(
                        providerSetting = provider,
                        messages = listOf(UIMessage.system(historyExtractionPrompt), UIMessage.user(payload)),
                        params = TextGenerationParams(model = model.copy(tools = emptySet(), customBodies = emptyList()), maxTokens = HISTORY_OUTPUT_TOKENS, privateRequest = true),
                    )
                }
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
                dao.put(current.copy(status = "failed", failure = reason))
            }
        }
        return false
    }

    private suspend fun checkpoint(
        previous: HistoryMemoryJob,
        next: HistoryMemoryJob,
        digest: String? = null,
        facts: List<HistoryMemoryCandidate> = emptyList(),
    ) = database.withTransaction {
        if (dao.job(previous.scope) != previous) return@withTransaction
        if (digest != null) dao.markProcessed(HistoryMemoryBatch(previous.id, digest))
        if (facts.isNotEmpty()) dao.addCandidates(facts)
        dao.put(next)
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
            dao.resolve(id, key, "discarded")
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
                dao.resolve(id, key, if (source in current.body) "saved" else "duplicate")
                return@withLock
            }
            check(current?.version == mergeInto.version) { "旧记忆已变更，请重新打开候选检查" }
            when (val result = memories.writeFile(scope, mergeInto.path, mergeInto.appendedHistoryFact(fact, source), mergeInto.version)) {
                is MemoryWriteResult.Success -> dao.resolve(id, key, "saved")
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
            dao.resolve(id, key, "duplicate")
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
            is MemoryWriteResult.Success -> dao.resolve(id, key, "saved")
            is MemoryWriteResult.Conflict -> {
                check(result.current?.containsFact(normalizedKey) == true) { "已有记忆内容不同，请在记忆管理中检查" }
                val recoveredWrite = result.current?.body?.lineSequence()?.any {
                    it.startsWith("<!-- history-source ") && it.endsWith(" ${candidate.jobId} -->")
                } == true
                dao.resolve(id, key, if (recoveredWrite) "saved" else "duplicate")
            }
            is MemoryWriteResult.Rejected -> error(result.reason)
        }
    }

    suspend fun related(scope: String, candidate: HistoryMemoryCandidate): List<MemoryFile> =
        memories.searchForRecall(scope, "${candidate.subject} ${candidate.fact}").take(4)

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
