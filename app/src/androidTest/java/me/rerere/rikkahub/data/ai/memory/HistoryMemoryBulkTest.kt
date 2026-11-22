package me.rerere.rikkahub.data.ai.memory

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * 200 条候选的真实批量路径：保存、幂等、撤销、需要确认的留在待审核。
 * 不调用任何模型，验证的是审核落库与撤销，而不是提取。
 */
@RunWith(AndroidJUnit4::class)
class HistoryMemoryBulkTest {
    @Test
    fun savesTwoHundredCandidatesThenUndoesThemAndKeepsUncertainOnesForReview() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(app.cacheDir.toPath(), "history-bulk-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(folder, "consent").apply { mkdirs() }
            override fun getCacheDir() = folder
        }
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = diskScope) {
            File(folder, "bulk.preferences_pb")
        }, diskScope)
        val db = AppDatabaseFactory.create(context, File(folder, "bulk.db").absolutePath)
        try {
            withTimeout(10_000) { settings.awaitLoaded() }
            val assistant = Assistant()
            settings.update { it.copy(assistants = listOf(assistant)) }
            val providers = ProviderManager(OkHttpClient(), context) { _, _ -> error("bulk test never calls a model") }
            val memory = MemoryRepository(db.memoryDao())
            val history = HistoryMemoryManager(context, db, settings, providers, memory)
            val scope = assistant.id.toString()
            val jobId = "bulk-job"
            db.historyMemoryDao().put(
                HistoryMemoryJob(
                    scope = scope,
                    id = jobId,
                    deviceConsent = "device",
                    modelId = "model",
                    providerFingerprint = "fingerprint",
                    sources = "[]",
                    sourceCount = 0,
                    status = "finished",
                )
            )
            val conversation = "00000000-0000-0000-0000-0000000000aa"
            val message = "00000000-0000-0000-0000-0000000000bb"
            val candidates = (0 until 200).map { index ->
                val fact = "编号 $index 的长期习惯：每周三晚上固定参加第 $index 号兴趣小组的活动"
                HistoryMemoryCandidate(
                    jobId = jobId,
                    key = "bulk-$index",
                    scope = scope,
                    category = "ongoing",
                    subject = "习惯 $index",
                    fact = fact,
                    evidence = fact,
                    conversationId = conversation,
                    messageId = message,
                    sourceTime = "2026-01-01T00:00:00Z",
                )
            }
            db.historyMemoryDao().addCandidates(candidates)

            val result = history.saveSelected(
                scope,
                candidates.map { it.key }.toSet(),
                HistoryMemoryManager.BulkMode.SAVE_SELECTED,
            )
            assertEquals(200, result.saved)
            assertEquals(0, result.failed)
            assertEquals(200, memory.getFiles(scope).size)
            assertEquals(0, history.pendingKeys(scope).size)

            // 再点一次不会写出重复记忆
            val repeated = history.saveSelected(
                scope,
                candidates.map { it.key }.toSet(),
                HistoryMemoryManager.BulkMode.SAVE_SELECTED,
            )
            assertEquals(0, repeated.saved)
            assertEquals(200, memory.getFiles(scope).size)

            val undo = history.undoLastBulk(scope)
            assertEquals(200, undo?.restoredFiles ?: -1)
            assertEquals(0, memory.getFiles(scope).size)
            assertEquals(200, history.pendingKeys(scope).size)

            // 涉及人物关系的候选：按建议整理时不会自动落盘
            val uncertain = HistoryMemoryCandidate(
                jobId = jobId,
                key = "uncertain-people",
                scope = scope,
                category = "people",
                subject = "妹妹",
                fact = "我的妹妹今年在杭州读研究生，专业是建筑设计",
                evidence = "我的妹妹今年在杭州读研究生，专业是建筑设计",
                conversationId = conversation,
                messageId = message,
                sourceTime = "2026-01-01T00:00:00Z",
            )
            db.historyMemoryDao().addCandidates(listOf(uncertain))
            val suggested = history.saveSelected(
                scope,
                setOf(uncertain.key),
                HistoryMemoryManager.BulkMode.APPLY_SUGGESTIONS,
            )
            assertEquals(0, suggested.saved)
            assertEquals(1, suggested.pending)
            assertTrue(uncertain.key in history.pendingKeys(scope))
            assertTrue(memory.getFiles(scope).isEmpty())

            // 误触“全部跳过”后的恢复：200 条全部找回，正文/证据/来源不变
            val skipped = history.skipSelected(scope, candidates.map { it.key }.toSet())
            assertEquals(200, skipped)
            assertEquals(1, history.pendingKeys(scope).size)
            assertEquals(200, history.skippedCount(scope))
            val restored = history.restoreSkipped(scope)
            assertEquals(200, restored)
            assertEquals(201, history.pendingKeys(scope).size)
            assertEquals(0, history.skippedCount(scope))
            val reopened = history.candidates(scope, "pending").first()
            assertEquals(201, reopened.size)
            val restoredFacts = reopened.filterNot { it.key == "uncertain-people" }
            assertEquals(candidates.map { it.fact }.toSet(), restoredFacts.map { it.fact }.toSet())
            assertTrue(restoredFacts.all { it.evidence.isNotBlank() && it.conversationId == conversation })

            // 新任务替换任务行之后，旧候选仍然按记忆库归属可见、可恢复
            val replacement = HistoryMemoryJob(
                scope = scope,
                id = "replacement-job",
                deviceConsent = "device",
                modelId = "model",
                providerFingerprint = "fingerprint",
                sources = "[]",
                sourceCount = 0,
                status = "finished",
            )
            db.historyMemoryDao().put(replacement)
            history.skipSelected(scope, candidates.map { it.key }.toSet())
            assertEquals(200, history.skippedCount(scope))
            assertEquals(200, history.restoreSkipped(scope))
            assertEquals(200, history.pendingKeys(scope).size)
        } finally {
            db.close()
            diskScope.coroutineContext[Job]?.cancelAndJoin()
            folder.deleteRecursively()
        }
    }
}
