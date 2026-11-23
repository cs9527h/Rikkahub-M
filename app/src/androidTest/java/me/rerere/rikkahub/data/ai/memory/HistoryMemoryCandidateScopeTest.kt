package me.rerere.rikkahub.data.ai.memory

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
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
 * 同一句话在两轮整理任务里各有一条候选时，跳过 / 恢复 / 撤销只能动自己那一行。
 *
 * 候选主键是 (jobId, key)：按「记忆库 + key」批量改状态会把上一轮已经保存的那条一起打回待审核，
 * 于是已保存数掉下去、界面又把它当成没处理过，用户会重复审一遍。这里全程不调用模型。
 */
@RunWith(AndroidJUnit4::class)
class HistoryMemoryCandidateScopeTest {
    @Test
    fun `restoring one job's skipped candidate keeps the other job's saved candidate saved`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(app.cacheDir.toPath(), "history-scope-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(folder, "consent").apply { mkdirs() }
            override fun getCacheDir() = folder
        }
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = diskScope) { File(folder, "scope.preferences_pb") },
            diskScope,
        )
        val db = AppDatabaseFactory.create(context, File(folder, "scope.db").absolutePath)
        try {
            withTimeout(10_000) { settings.awaitLoaded() }
            val assistant = Assistant()
            settings.update { it.copy(assistants = listOf(assistant)) }
            // 任何模型调用都直接失败：这一轮检查不该产生费用
            val providers = ProviderManager(OkHttpClient(), context) { _, _ -> error("scope test never calls a model") }
            val memory = MemoryRepository(db.memoryDao())
            val history = HistoryMemoryManager(context, db, settings, providers, memory)
            val scope = assistant.id.toString()

            val conversation = "00000000-0000-0000-0000-0000000000c1"
            val message = "00000000-0000-0000-0000-0000000000c2"
            val fact = "我每周三晚上固定参加第 7 号兴趣小组的活动"
            val key = factKey(fact)

            putJob(db, scope, "job-first")
            db.historyMemoryDao().addCandidates(listOf(candidate("job-first", key, scope, fact, conversation, message)))
            val saved = history.saveSelected(scope, setOf(key), HistoryMemoryManager.BulkMode.SAVE_SELECTED)
            assertEquals(1, saved.saved)
            assertEquals(1, memory.getFiles(scope).size)

            // 第二轮任务又提取到同一句话：新的一行，主键不同
            putJob(db, scope, "job-second")
            db.historyMemoryDao().addCandidates(listOf(candidate("job-second", key, scope, fact, conversation, message)))
            assertEquals(2, countRows(db, scope, key))

            // 跳过第二轮的那条：不能把第一轮已经保存的那条也变成“已跳过”
            assertEquals(1, history.skipSelected(scope, setOf(key)))
            assertEquals("saved", statusOf(db, "job-first", key))
            assertEquals("discarded", statusOf(db, "job-second", key))
            assertEquals(1, history.skippedCount(scope))

            // 撤销只回退自己那一行
            val undone = history.undoLastBulk(scope)
            assertEquals(1, undone?.pending)
            assertEquals("saved", statusOf(db, "job-first", key))
            assertEquals("pending", statusOf(db, "job-second", key))

            // 误触「全部跳过」后恢复：只恢复真正被跳过的那一条
            history.skipSelected(scope, setOf(key))
            assertEquals(1, history.restoreSkipped(scope))
            assertEquals(0, history.skippedCount(scope))
            assertEquals("saved", statusOf(db, "job-first", key))
            assertEquals("pending", statusOf(db, "job-second", key))

            // 恢复不会重跑整理：任务行、状态、去重指纹都没动，模型一次也没被调用
            assertEquals("job-second", db.historyMemoryDao().job(scope)?.id)
            assertEquals("finished", db.historyMemoryDao().job(scope)?.status)

            // 恢复的候选再保存一次：同一句话不会写成第二条记忆
            val again = history.saveSelected(scope, setOf(key), HistoryMemoryManager.BulkMode.SAVE_SELECTED)
            assertEquals(0, again.saved)
            assertEquals(1, again.duplicate)
            assertEquals(1, memory.getFiles(scope).size)
            assertTrue(memory.getFiles(scope).single().content.contains(fact))
        } finally {
            db.close()
            diskScope.coroutineContext[Job]?.cancelAndJoin()
            folder.deleteRecursively()
        }
        Unit
    }

    private suspend fun putJob(db: AppDatabase, scope: String, id: String) {
        db.historyMemoryDao().put(
            HistoryMemoryJob(
                scope = scope,
                id = id,
                deviceConsent = "device",
                modelId = "model",
                providerFingerprint = "fingerprint",
                sources = "[]",
                sourceCount = 0,
                status = "finished",
            )
        )
    }

    private fun candidate(
        jobId: String,
        key: String,
        scope: String,
        fact: String,
        conversationId: String,
        messageId: String,
    ) = HistoryMemoryCandidate(
        jobId = jobId,
        key = key,
        scope = scope,
        category = "ongoing",
        subject = "兴趣小组",
        fact = fact,
        evidence = fact,
        conversationId = conversationId,
        messageId = messageId,
        sourceTime = "2026-01-01T00:00:00Z",
    )

    private suspend fun statusOf(db: AppDatabase, jobId: String, key: String): String? =
        db.historyMemoryDao().candidatesOf(jobId).first().firstOrNull { it.key == key }?.status

    private suspend fun countRows(db: AppDatabase, scope: String, key: String): Int =
        db.historyMemoryDao().snapshotOfStatus(scope, "pending", 100).count { it.key == key } +
            db.historyMemoryDao().snapshotOfStatus(scope, "saved", 100).count { it.key == key }
}
