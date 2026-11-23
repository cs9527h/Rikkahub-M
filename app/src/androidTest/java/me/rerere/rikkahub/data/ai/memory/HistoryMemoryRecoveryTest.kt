package me.rerere.rikkahub.data.ai.memory

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 场景 D：失败之后“继续”，不能重复计费、不能重复出候选、不能覆盖已保存的记忆。
 *
 * 这三条都是用户看得见的行为：候选重复要重审一遍，记忆被覆盖会丢内容，
 * 重复计费则是每次点“继续”都在花钱。测试用假提供商数调用次数，不联网。
 */
@RunWith(AndroidJUnit4::class)
class HistoryMemoryRecoveryTest {
    @Test
    fun retryAfterModelFailureResumesWithoutPayingTwice() = runBlocking {
        withFixture { fixture ->
            val history = fixture.history
            val scope = fixture.scope
            val job = history.persistConfirmedPlan(
                history.prepare(scope, fixture.assistant, history.sources(Long.MIN_VALUE, Long.MAX_VALUE))
            )
            // 第 2 次模型调用（第二个批次）注入一次网络失败
            fixture.model.failOnCall = 2

            assertFalse(history.run(scope, job.id, job.generation))

            // 失败必须能解释：阶段 + 错误码 + 脱敏摘要
            val failed = db(fixture).historyMemoryDao().job(scope)!!
            assertEquals("failed", failed.status)
            assertEquals(MemoryStage.MODEL_CALL.id, failed.failureStage)
            assertEquals(MemoryFailureCode.NETWORK, failed.failureCode)
            assertNotNull(failed.failureDetail)
            assertTrue(failed.failureDetail!!.contains("IOException"))
            assertTrue(failed.failure.orEmpty().contains("从这一批继续"))
            assertEquals(1, failed.batchAttempts)

            val log = db(fixture).historyMemoryDao().taskLog(job.id)!!
            assertEquals("failed", log.status)
            assertEquals(MemoryStage.MODEL_CALL.id, log.errorStage)
            assertEquals(MemoryFailureCode.NETWORK, log.errorCode)
            assertNotNull(log.errorDetail)
            // 诊断里不能出现聊天正文
            assertFalse(log.errorDetail!!.contains(FIXTURE_TEXT))
            assertTrue(MemoryTaskLogSummary.diagnosticText(log).contains("失败阶段: model_call"))

            // 已经完成的批次不能被重放：第一次调用的批次必须只花一次钱
            val firstDigest = fixture.model.calls.keys.first()
            assertEquals(1, fixture.model.calls[firstDigest])

            // 用户点“继续”：模拟 control("running") 的换代，不经过 WorkManager
            resume(fixture)
            assertFalse(history.run(scope, job.id, job.generation + 1))

            val finished = db(fixture).historyMemoryDao().job(scope)!!
            assertEquals("finished", finished.status)
            assertEquals(3, finished.sourceIndex)
            // 已完成批次的调用次数完全不变
            assertEquals(1, fixture.model.calls[firstDigest])
            // 只有失败的那一批被重试一次，总调用次数 = 批次 + 1 次重试
            assertEquals(4, fixture.model.totalCalls)

            // 候选不重复
            val candidates = history.candidates(job.id).first()
            assertEquals(3, candidates.size)
            assertEquals(candidates.size, candidates.map { it.key }.distinct().size)

            // 保存一条记忆，再做一轮新任务：不能重复调用模型，也不能覆盖已经保存的内容
            val saved = candidates.first()
            history.resolve(scope, job.id, saved.key, saved.fact)
            val file = fixture.memory.getFiles(scope).single()
            val before = file.content

            val second = history.persistConfirmedPlan(
                history.prepare(scope, fixture.assistant, history.sources(Long.MIN_VALUE, Long.MAX_VALUE))
            )
            val callsBeforeSecondTask = fixture.model.totalCalls
            assertFalse(history.run(scope, second.id, second.generation))
            assertEquals("finished", db(fixture).historyMemoryDao().job(scope)!!.status)
            // 跨任务批次指纹生效：同样的聊天不会再花一次钱
            assertEquals(callsBeforeSecondTask, fixture.model.totalCalls)
            assertTrue(history.candidates(second.id).first().isEmpty())
            assertEquals(before, fixture.memory.getFiles(scope).single().content)
        }
    }

    @Test
    fun poisonBatchIsSkippedAfterBoundedAttemptsAndRecorded() = runBlocking {
        withFixture { fixture ->
            val history = fixture.history
            val scope = fixture.scope
            fixture.model.poisoned = { excerpt -> excerpt.contains("编号 2") }
            val job = history.persistConfirmedPlan(
                history.prepare(scope, fixture.assistant, history.sources(Long.MIN_VALUE, Long.MAX_VALUE))
            )

            // 连续重试：同一批最多花 MAX_BATCH_ATTEMPTS 次钱，之后跳过并继续跑完
            var generation = job.generation
            repeat(HistoryMemoryManager.MAX_BATCH_ATTEMPTS + 1) {
                if (db(fixture).historyMemoryDao().job(scope)?.status != "finished") {
                    assertFalse(history.run(scope, job.id, generation))
                    if (db(fixture).historyMemoryDao().job(scope)?.status != "finished") {
                        generation++
                        resume(fixture, generation)
                    }
                }
            }

            val finished = db(fixture).historyMemoryDao().job(scope)!!
            assertEquals("finished", finished.status)
            assertEquals(3, finished.sourceIndex)

            val log = db(fixture).historyMemoryDao().taskLog(job.id)!!
            assertEquals(1, log.skippedBatches)
            assertTrue(MemoryTaskLogSummary.skipLabel(log)!!.contains("跳过 1 个批次"))

            // 毒批次总共只被调用 MAX_BATCH_ATTEMPTS 次，不是无限次
            assertTrue(fixture.model.attemptsPerPoisonedDigest.isNotEmpty())
            assertEquals(
                HistoryMemoryManager.MAX_BATCH_ATTEMPTS,
                fixture.model.attemptsPerPoisonedDigest.values.sum(),
            )
        }
    }

    @Test
    fun changedSourceIsSkippedInsteadOfFailingTheWholeTask() = runBlocking {
        withFixture { fixture ->
            val history = fixture.history
            val scope = fixture.scope
            val job = history.persistConfirmedPlan(
                history.prepare(scope, fixture.assistant, history.sources(Long.MIN_VALUE, Long.MAX_VALUE))
            )
            // 计划确认之后，第 2 个会话被改动了：只跳过它，不能让整轮任务永远失败
            db(fixture).withTransaction {
                db(fixture).conversationDao().insert(
                    ConversationEntity("fixture-1", scope, "Synthetic 1", "[]", 2000, 4000, "[]", false)
                )
            }
            assertFalse(history.run(scope, job.id, job.generation))

            val finished = db(fixture).historyMemoryDao().job(scope)!!
            assertEquals("finished", finished.status)
            assertEquals(3, finished.sourceIndex)
            val log = db(fixture).historyMemoryDao().taskLog(job.id)!!
            assertEquals(1, log.skippedSources)
            assertNull(log.errorStage)
        }
    }

    // ---- fixture ----

    private class Fixture(
        val context: Context,
        val db: AppDatabase,
        val history: HistoryMemoryManager,
        val memory: MemoryRepository,
        val model: FakeModel,
        val scope: String,
        val assistant: Assistant,
        val diskScope: CoroutineScope,
        val folder: File,
    )

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(app.cacheDir.toPath(), "history-recovery-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(folder, "consent").apply { mkdirs() }
            override fun getCacheDir() = folder
        }
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = diskScope) {
            File(folder, "history.preferences_pb")
        }, diskScope)
        val database = AppDatabaseFactory.create(context, File(folder, "fixture.db").absolutePath)
        try {
            val model = Model(modelId = "recovery-fixture-model")
            val provider = ProviderSetting.OpenAI(name = "fixture", models = listOf(model))
            val assistant = Assistant()
            withTimeout(10_000) { settings.awaitLoaded() }
            settings.update {
                it.copy(assistants = listOf(assistant), providers = listOf(provider), memoryModelId = model.id)
            }
            val fake = FakeModel()
            val providers = ProviderManager(OkHttpClient(), context) { _, _ -> error("fixture uses no credentials") }
            providers.registerProvider("openai", fake)
            val memory = MemoryRepository(database.memoryDao())
            val scope = assistant.id.toString()
            val history = HistoryMemoryManager(context, database, settings, providers, memory)
            database.withTransaction {
                repeat(3) { conversation ->
                    val conversationId = "fixture-$conversation"
                    database.conversationDao().insert(
                        ConversationEntity(conversationId, scope, "Synthetic $conversation", "[]", 1000, 1000, "[]", false),
                    )
                    val message = UIMessage.user("$FIXTURE_TEXT 编号 ${conversation + 1}")
                        .copy(createdAt = LocalDateTime.parse("2020-01-0${conversation + 1}T00:00"))
                    database.messageNodeDao().insertAll(
                        listOf(
                            MessageNodeEntity(
                                "$conversationId-0",
                                conversationId,
                                0,
                                JsonInstant.encodeToString(listOf(message)),
                                0,
                            )
                        )
                    )
                }
            }
            block(
                Fixture(
                    context = context,
                    db = database,
                    history = history,
                    memory = memory,
                    model = fake,
                    scope = scope,
                    assistant = assistant,
                    diskScope = diskScope,
                    folder = folder,
                )
            )
        } finally {
            diskScope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
            folder.deleteRecursively()
        }
    }

    private fun db(fixture: Fixture) = fixture.db

    /** 模拟 retryTask/control("running") 的换代：只改数据库，不触发 WorkManager。 */
    private suspend fun resume(fixture: Fixture, generation: Long? = null) {
        val dao = fixture.db.historyMemoryDao()
        val current = dao.job(fixture.scope) ?: return
        val next: HistoryMemoryJob = current.copy(
            status = "running",
            failure = null,
            failureStage = null,
            failureCode = null,
            failureDetail = null,
            finishedAt = 0,
            generation = generation ?: (current.generation + 1),
        )
        dao.put(next)
    }

    private class FakeModel : Provider<ProviderSetting.OpenAI> {
        /** payload 摘要 → 这一批被调用了几次 */
        val calls = mutableMapOf<String, Int>()
        /** 同一批的尝试次数上限，用来证明“不会无限扣费” */
        val attemptsPerPoisonedDigest = mutableMapOf<String, Int>()
        var totalCalls = 0
        /** 第几次调用注入一次可重试的失败（null 表示不注入） */
        var failOnCall: Int? = null
        var poisoned: (String) -> Boolean = { false }

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            val payload = messages.last().toText()
            val digest = historyDigest(payload)
            totalCalls++
            calls[digest] = (calls[digest] ?: 0) + 1
            if (failOnCall == totalCalls) throw IOException("Synthetic offline error")
            val excerpts = Json.decodeFromString<List<String>>(payload)
            val text = excerpts.first()
            if (poisoned(text)) {
                attemptsPerPoisonedDigest[digest] = (attemptsPerPoisonedDigest[digest] ?: 0) + 1
                // 第一批毒批次抛网络错误，之后的批次数够了就走“自动跳过”
                if (attemptsPerPoisonedDigest[digest] == 1) throw IOException("Synthetic offline error")
                return TextGenerationResult("fixture", params.model.modelId, UIMessage.assistant("not a json array"))
            }
            val subject = text.substringBefore(" 编号").ifBlank { "记忆事实" }
            val response = """[{"segment":0,"category":"projects","subject":"$subject","fact":"$text","evidence":"$text"}]"""
            return TextGenerationResult("fixture", params.model.modelId, UIMessage.assistant(response))
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ) = emptyFlow<me.rerere.ai.ui.StreamChunk>()

    }

    private companion object {
        const val FIXTURE_TEXT = "记忆整理失败恢复测试事实"
    }
}
