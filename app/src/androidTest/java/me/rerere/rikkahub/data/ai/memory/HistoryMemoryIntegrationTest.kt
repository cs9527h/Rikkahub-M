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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDateTime

@RunWith(AndroidJUnit4::class)
class HistoryMemoryIntegrationTest {
    @Test fun scansThousandImportedThreadsResumesFailedBatchAndSavesOnce() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(app.cacheDir.toPath(), "history-fixture-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(folder, "consent").apply { mkdirs() }
            override fun getCacheDir() = folder
        }
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = diskScope) {
            File(folder, "history.preferences_pb")
        }, diskScope)
        val db = AppDatabaseFactory.create(context, File(folder, "fixture.db").absolutePath)
        try {
            val model = Model(modelId = "synthetic-test-model")
            val provider = ProviderSetting.OpenAI(name = "fixture", models = listOf(model))
            val assistant = Assistant()
            withTimeout(10_000) { settings.awaitLoaded() }
            settings.update { it.copy(assistants = listOf(assistant), providers = listOf(provider), memoryModelId = model.id) }
            val fixture = SyntheticModel()
            val providers = ProviderManager(OkHttpClient(), context) { _, _ -> error("fixture uses no credentials") }
            providers.registerProvider("openai", fixture)
            val memory = MemoryRepository(db.memoryDao())
            val scope = assistant.id.toString()
            val history = HistoryMemoryManager(context, db, settings, providers, memory)
            val message = UIMessage.user("我的测试项目用 Kotlin").copy(createdAt = LocalDateTime.parse("2018-01-01T00:00"))
            val messageJson = JsonInstant.encodeToString(listOf(message))
            db.withTransaction {
                repeat(1000) { conversation ->
                    val conversationId = "fixture-$conversation"
                    db.conversationDao().insert(
                        ConversationEntity(conversationId, scope, "Synthetic $conversation", "[]", 1000, 1000, "[]", false),
                    )
                    val size = if (conversation == 0) 5000 else 1
                    db.messageNodeDao().insertAll(List(size) { index ->
                        MessageNodeEntity("$conversationId-$index", conversationId, index, messageJson, 0)
                    })
                }
            }

            assertEquals(1000, history.sources(0, 1001).size)
            val hundred = history.sources(0, 1001).take(100)
            val firstPlan = history.prepare(scope, assistant, hundred)
            assertEquals(100, firstPlan.sources.size)
            val job = history.persistConfirmedPlan(history.prepare(scope, assistant, history.sources(0, 1001)))
            assertFalse(history.run(scope, job.id, job.generation))
            assertEquals("failed", db.historyMemoryDao().job(scope)?.status)
            assertEquals(0, db.historyMemoryDao().job(scope)?.textOffset)
            assertEquals(0, history.count(job.id, "saved").first())

            val resumed = db.historyMemoryDao().job(scope)!!.copy(status = "running", generation = 1)
            db.historyMemoryDao().put(resumed)
            val restarted = HistoryMemoryManager(context, db, settings, providers, memory)
            assertFalse(restarted.run(scope, job.id, 0))
            assertFalse(restarted.run(scope, job.id, 1))
            val complete = db.historyMemoryDao().job(scope)!!
            assertEquals("finished", complete.status)
            assertEquals(1000, complete.sourceIndex)
            assertTrue("Repeated content should only require a few distinct model calls", fixture.attempts in 2..5)

            val candidate = restarted.candidates(job.id).first().single()
            assertEquals(message.id.toString(), candidate.messageId)
            assertTrue(memory.getFiles(scope).isEmpty())
            restarted.resolve(scope, job.id, candidate.key, candidate.fact)
            val saved = memory.searchForRecall(scope, "测试项目").single()
            assertTrue(saved.content.contains(candidate.fact))
            assertTrue(saved.content.contains(candidate.conversationId))
            assertEquals(1, restarted.count(job.id, "saved").first())
            assertTrue(memory.searchForRecall(Uuid.random().toString(), "测试项目").isEmpty())
            memory.deleteFile(scope, saved.path)
            assertTrue(memory.searchForRecall(scope, "测试项目").isEmpty())
            memory.restoreFile(scope, saved.path)
            assertEquals(listOf(saved.path), memory.searchForRecall(scope, "测试项目").map { it.path })

            val repeated = restarted.persistConfirmedPlan(restarted.prepare(scope, assistant, hundred))
            assertFalse(restarted.run(scope, repeated.id, repeated.generation))
            val duplicate = restarted.candidates(repeated.id).first().single()
            restarted.resolve(scope, repeated.id, duplicate.key, duplicate.fact)
            assertEquals(1, restarted.count(repeated.id, "duplicate").first())
            assertEquals(1, memory.getFiles(scope).size)

            val forMerge = duplicate.copy(
                key = "synthetic-merge-key",
                fact = "测试项目也用 Compose",
                evidence = "测试项目也用 Compose",
                status = "pending",
            )
            db.historyMemoryDao().addCandidates(listOf(forMerge))
            val stale = checkNotNull(memory.getFile(scope, saved.path))
            val update = stale.content.replace("Kotlin", "Kotlin 2")
            memory.writeFile(scope, saved.path, update, stale.version)
            val conflict = runCatching { restarted.resolve(scope, repeated.id, forMerge.key, forMerge.fact, stale) }
            assertTrue(conflict.isFailure)
            assertEquals(1, restarted.count(repeated.id, "pending").first())
            val fresh = checkNotNull(memory.getFile(scope, saved.path))
            restarted.resolve(scope, repeated.id, forMerge.key, forMerge.fact, fresh)
            val merged = checkNotNull(memory.getFile(scope, saved.path))
            assertNotEquals(fresh.version, merged.version)
            assertTrue(merged.content.contains("Kotlin 2") && merged.content.contains("Compose"))
            assertEquals(1, memory.getFiles(scope).size)

            val snapshot = File(folder, "snapshot.db")
            DatabaseBackup.createSnapshot(db.openHelper.writableDatabase, snapshot)
            DatabaseBackup.stripLocalMemoryWork(context, snapshot)
            val staged = AppDatabaseFactory.create(context, snapshot.absolutePath)
            try {
                checkNotNull(staged.memoryDao().getFile(scope, saved.path))
                assertEquals(1000, staged.conversationDao().countAll())
                // 备份必须保住任务状态和未保存候选：换机后还能继续审核，也不会重复付费
                assertEquals(repeated.id, staged.historyMemoryDao().job(scope)?.id)
                assertEquals(1, staged.historyMemoryDao().pending(repeated.id).first().size)
            } finally {
                staged.close()
            }
            assertEquals(repeated.id, db.historyMemoryDao().job(scope)?.id)
        } finally {
            diskScope.coroutineContext[Job]?.cancelAndJoin()
            db.close()
            folder.deleteRecursively()
        }
        Unit
    }

    private class SyntheticModel : Provider<ProviderSetting.OpenAI> {
        var attempts = 0

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            attempts++
            if (attempts == 1) throw IOException("Synthetic offline error")
            assertTrue(params.tools.isEmpty())
            assertTrue(params.privateRequest)
            val excerpts = Json.decodeFromString<List<String>>(messages.last().toText())
            assertTrue(excerpts.isNotEmpty())
            assertTrue(excerpts.all { it == "我的测试项目用 Kotlin" })
            val response = """[{"segment":0,"category":"projects","subject":"测试项目","fact":"测试项目用 Kotlin","evidence":"测试项目用 Kotlin"}]"""
            return TextGenerationResult("fixture", params.model.modelId, UIMessage.assistant(response))
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ) = emptyFlow<me.rerere.ai.ui.StreamChunk>()
    }
}
