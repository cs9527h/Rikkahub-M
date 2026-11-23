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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.MemoryConsolidator
import me.rerere.rikkahub.data.ai.MemoryDreamKeepAlive
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** 合成事实：不来自任何真实用户对话，也不带任何真实用户信息 */
private const val FACT = "我每周三晚上固定参加第 7 号兴趣小组的活动"
private const val RECALL_QUERY = "我周三晚上要做什么"
private val MEMORY_CONTENT = """
    ---
    name: e2e
    title: 兴趣小组
    description: 每周三的兴趣小组
    ---

    - [stated] $FACT
""".trimIndent()

/**
 * 端到端：真实会话 → 真实 [MemoryConsolidator]（假 provider 只会发起一次 memory_write 工具调用）
 * → 真实记忆文件 → 紧接着用 [buildRecallInjection] 验证这句记忆真的会被注入上下文。
 *
 * ⚠️ 只能 CI 验证：本机是 arm64 proot，AGP 的 aapt2 只有 x86_64 预编译版，instrumented 测试跑不起来。
 * 用例全部在仪器的临时目录里建库，不联网、不读用户数据，也不打印聊天正文。
 */
@RunWith(AndroidJUnit4::class)
class MemoryConsolidationEndToEndTest {
    @Test
    fun `consolidation writes a memory file that the next recall injects`() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(app.cacheDir.toPath(), "memory-e2e-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(folder, "consent").apply { mkdirs() }
            override fun getCacheDir() = folder
        }
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = diskScope) { File(folder, "e2e.preferences_pb") },
            diskScope,
        )
        val db = AppDatabaseFactory.create(context, File(folder, "e2e.db").absolutePath)
        try {
            withTimeout(10_000) { settings.awaitLoaded() }
            val model = Model(modelId = "fixture-memory", abilities = listOf(ModelAbility.TOOL))
            val provider = ProviderSetting.OpenAI(name = "fixture", models = listOf(model))
            val fixture = MemoryWritingProvider()
            // 任何真实凭据都拿不到：这个 provider 永远不会走网络
            val providers = ProviderManager(OkHttpClient(), context) { _, _ -> error("fixture provider has no credentials") }
            providers.registerProvider("openai", fixture)

            val memory = MemoryRepository(db.memoryDao())
            val conversations = conversationRepository(context, db)
            val consolidator = MemoryConsolidator(
                providerManager = providers,
                usageRecorder = UsageRecorder(db.usageRecordDao(), diskScope),
                memoryRepository = memory,
                conversationRepo = conversations,
                consolidationDao = db.memoryConsolidationDao(),
                settingsStore = settings,
            )
            // 一轮就够触发自动整理，不必等两分钟空闲
            val scheduler = MemoryConsolidationScheduler(
                scope = diskScope,
                worker = consolidator,
                keepAlive = MemoryDreamKeepAlive { AutoCloseable {} },
                isAppVisible = { true },
                maxPendingTurns = 1,
            )
            val store = recallStore(memory)

            // ---- 1) 自动整理开着：整理 → 真文件 → 下一轮注入 ----
            val auto = Assistant(name = "auto", enableMemory = true, autoConsolidateMemory = true)
            settings.update {
                it.copy(assistants = listOf(auto), providers = listOf(provider), memoryModelId = model.id)
            }
            val autoConversation = Uuid.random()
            insertConversation(db, autoConversation, auto.id)
            val autoScope = auto.id.toString()
            scheduler.onTurnFinished(checkNotNull(conversations.getConversationById(autoConversation)))

            val file = awaitFile(memory, autoScope)
            // 断言文件内容本身，而不是“库里有行”
            assertEquals("/topics/e2e.md", file.path)
            assertTrue("整理后文件里要有那句话：${file.content}", file.content.contains(FACT))
            assertEquals(1, memory.getFiles(autoScope).size)

            // 整理器自己也认为这个文件是这次改动
            val run = withTimeout(30_000) {
                scheduler.statusOf(autoScope).first { !it.running && it.lastRun != null }.lastRun
            }
            assertEquals(listOf("/topics/e2e.md"), run?.changedPaths)

            // 紧接着一轮召回：这句记忆必须出现在要发给模型的文本里
            val injection = checkNotNull(
                buildRecallInjection(auto, RECALL_QUERY, hasTools = false, store = store),
            )
            assertEquals(autoScope, injection.scope)
            assertTrue("注入文本里要有这句话：${injection.prompt}", injection.prompt.contains(FACT))
            assertEquals(listOf("/topics/e2e.md"), injection.matches)
            // 同时确认它确实进了请求消息（系统消息里）
            val messages = listOf(UIMessage.system("人设"), UIMessage.user(RECALL_QUERY))
            val request = messages.withRecallInjection(injection)
            assertTrue(request.first().toText().contains(FACT))

            // ---- 2) 关了自动整理：不自动写，手动整理仍能写 ----
            val manualOnly = Assistant(name = "manual", enableMemory = true, autoConsolidateMemory = false)
            settings.update { it.copy(assistants = listOf(auto, manualOnly)) }
            val manualConversation = Uuid.random()
            insertConversation(db, manualConversation, manualOnly.id)
            val manualScope = manualOnly.id.toString()
            val attemptsBefore = fixture.attempts

            scheduler.onTurnFinished(checkNotNull(conversations.getConversationById(manualConversation)))
            // 待整理的轮次被记下来了，但没有自动跑
            withTimeout(10_000) { scheduler.statusOf(manualScope).first { it.pendingTurns > 0 } }
            delay(1_000)
            assertTrue("关掉自动整理后不该自己写记忆", memory.getFiles(manualScope).isEmpty())
            assertEquals(attemptsBefore, fixture.attempts)
            assertFalse(scheduler.statusOf(manualScope).first().running)

            scheduler.organize(manualScope)
            val manualFile = awaitFile(memory, manualScope)
            assertTrue(manualFile.content.contains(FACT))
            val manualInjection = checkNotNull(
                buildRecallInjection(manualOnly, RECALL_QUERY, hasTools = false, store = store),
            )
            assertTrue(manualInjection.prompt.contains(FACT))
            assertTrue("手动整理后应该多调用过模型：${fixture.attempts}", fixture.attempts > attemptsBefore)

            // ---- 3) 关掉记忆：既不自动整理，手动整理也不写；文件系统不被动过 ----
            val off = Assistant(name = "off", enableMemory = false)
            settings.update { it.copy(assistants = listOf(auto, manualOnly, off)) }
            val offConversation = Uuid.random()
            insertConversation(db, offConversation, off.id)
            val offScope = off.id.toString()
            val attemptsOff = fixture.attempts

            scheduler.onTurnFinished(checkNotNull(conversations.getConversationById(offConversation)))
            // 关掉记忆时整理器拿不到任何助手：手动触发也不写。这是 0.8.6 的设计（关掉记忆=完全不整理），
            // 断言的就是“什么都没发生”，而不是“手动总能写”。
            scheduler.organize(offScope)
            withTimeout(30_000) { scheduler.statusOf(offScope).first { !it.running } }
            delay(500)
            assertTrue("关掉记忆后整理不该写任何东西", memory.getFiles(offScope).isEmpty())
            assertEquals(attemptsOff, fixture.attempts)

            // 前两个记忆库里的文件不受影响
            assertEquals(1, memory.getFiles(autoScope).size)
            assertEquals(1, memory.getFiles(manualScope).size)
        } finally {
            db.close()
            diskScope.coroutineContext[Job]?.cancelAndJoin()
            folder.deleteRecursively()
        }
        Unit
    }

    // ---- 夹具 ----

    /** 第一次请求返回一次 memory_write 工具调用；工具执行完后返回普通文本，让 agent 循环收尾 */
    private class MemoryWritingProvider : Provider<ProviderSetting.OpenAI> {
        var attempts = 0

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ) = emptyFlow<StreamChunk>()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            attempts++
            val alreadyWritten = messages.flatMap { it.getTools() }
                .any { it.toolName == MemoryToolNames.WRITE && it.isExecuted }
            val message = if (alreadyWritten) {
                UIMessage.assistant("记忆已更新")
            } else {
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "fixture-write-${attempts}",
                            toolName = MemoryToolNames.WRITE,
                            input = buildJsonObject {
                                put("path", "/topics/e2e.md")
                                put("content", MEMORY_CONTENT)
                                put("if_version", "new")
                            }.toString(),
                        )
                    ),
                )
            }
            return TextGenerationResult(id = "fixture", model = params.model.modelId, message = message)
        }
    }

    private fun recallStore(memory: MemoryRepository) = object : MemoryRecallStore {
        override suspend fun search(scope: String, query: String) = memory.searchRecallWithDiagnostics(scope, query)

        override suspend fun file(scope: String, path: String) = memory.getFile(scope, path)
    }

    /** 真实 ConversationRepository：整理器要从库里把会话和消息读回来 */
    private fun conversationRepository(context: Context, db: AppDatabase) = ConversationRepository(
        conversationDAO = db.conversationDao(),
        messageNodeDAO = db.messageNodeDao(),
        favoriteDAO = db.favoriteDao(),
        database = db,
        filesManager = FilesManager(context, FilesRepository(db.managedFileDao()), AppScope()),
        messageFtsManager = MessageFtsManager(db),
    )

    private suspend fun insertConversation(db: AppDatabase, conversationId: Uuid, assistantId: Uuid) {
        val now = System.currentTimeMillis()
        db.conversationDao().insert(
            ConversationEntity(
                id = conversationId.toString(),
                assistantId = assistantId.toString(),
                title = "fixture",
                nodes = "[]",
                createAt = now,
                updateAt = now,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        // 一个节点只放一条消息：currentMessages 是「每个节点取 selectIndex 那一条」，
        // 把一问一答塞进同一个节点会被当成只有提问、没有回复，那一轮就永远算没结束。
        // 节点 id 必须是合法 UUID，ConversationRepository 读回来时会 Uuid.parse。
        db.messageNodeDao().insertAll(
            listOf(
                MessageNodeEntity(
                    id = Uuid.random().toString(),
                    conversationId = conversationId.toString(),
                    nodeIndex = 0,
                    messages = JsonInstant.encodeToString(listOf(UIMessage.user(FACT))),
                    selectIndex = 0,
                ),
                MessageNodeEntity(
                    id = Uuid.random().toString(),
                    conversationId = conversationId.toString(),
                    nodeIndex = 1,
                    messages = JsonInstant.encodeToString(listOf(UIMessage.assistant("好，我记住了"))),
                    selectIndex = 0,
                ),
            )
        )
    }

    private suspend fun awaitFile(memory: MemoryRepository, scope: String): MemoryFile = withTimeout(90_000) {
        var file = memory.getFiles(scope).firstOrNull()
        while (file == null) {
            delay(50)
            file = memory.getFiles(scope).firstOrNull()
        }
        checkNotNull(file)
    }

}
