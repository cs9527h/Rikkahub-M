package me.rerere.rikkahub.data.ai.memory

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.buildRecallPrompt
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.FakeMemoryDAO
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「聊天时到底往请求里塞了什么记忆」的用例。
 *
 * 断言的是注入到消息里的**文本**，不是数据库里有没有行：记忆功能的成败只看模型最后有没有看到那段话。
 * 记忆库用真实的 MemoryRepository + 内存版 MemoryDAO，多阶段召回、分词、分页和 frontmatter 解析都是真的。
 */
class MemoryRecallInjectionTest {
    private val assistantA = Assistant(name = "A", enableMemory = true)
    private val assistantB = Assistant(name = "B", enableMemory = true)
    private val scopeA = assistantA.id.toString()
    private val scopeB = assistantB.id.toString()

    private val foodMemory = """
        ---
        name: food
        description: 花生过敏与饮食禁忌
        aliases: [过敏]
        ---

        - [stated] 我对花生严重过敏，点菜要先问清楚
    """.trimIndent()

    private val spiderMemory = """
        ---
        name: spiders
        description: 对蜘蛛的恐惧
        ---

        - [stated] 我小时候被蜘蛛吓到过，看到蜘蛛就发怵
    """.trimIndent()

    @Test
    fun `turning memory off adds nothing to the request and never touches the store`() = runTest {
        val repository = memoryRepository()
        repository.writeFile(scopeA, "/topics/food.md", foodMemory, MemoryRepository.NEW_FILE_VERSION)
        val store = RecordingStore(repository)
        val off = assistantA.copy(enableMemory = false)

        val injection = buildRecallInjection(off, "花生过敏还能吃什么", hasTools = false, store = store)
        assertNull("关掉记忆就不该有注入", injection)
        assertTrue("关掉记忆连检索都不该做", store.searched.isEmpty())
        assertTrue("关掉记忆不该去读偏好文件", store.read.isEmpty())

        // 请求消息原样发出去：连系统消息都不多一条
        val messages = listOf(UIMessage.system("人设"), UIMessage.user("花生过敏还能吃什么"))
        assertSame(messages, messages.withRecallInjection(injection))
        // 关掉记忆只影响注入与后台整理，记忆文件一条都不能少
        assertEquals(1, repository.getFiles(scopeA).size)
    }

    @Test
    fun `only the assistant's own memory reaches the request`() = runTest {
        val repository = memoryRepository()
        repository.writeFile(scopeA, "/topics/food.md", foodMemory, MemoryRepository.NEW_FILE_VERSION)
        repository.writeFile(scopeB, "/topics/spiders.md", spiderMemory, MemoryRepository.NEW_FILE_VERSION)
        val store = RecordingStore(repository)

        val a = checkNotNull(buildRecallInjection(assistantA, "花生过敏还能吃什么", false, store))
        assertEquals(scopeA, a.scope)
        assertTrue(a.prompt.contains("/topics/food.md"))
        assertTrue(a.prompt.contains("我对花生严重过敏"))
        assertFalse("助手 B 的记忆不能出现在 A 的上下文里", a.prompt.contains("蜘蛛"))

        val b = checkNotNull(buildRecallInjection(assistantB, "我记得小时候被蜘蛛吓到过", false, store))
        assertEquals(scopeB, b.scope)
        assertTrue(b.prompt.contains("小时候被蜘蛛吓到过"))
        assertFalse("助手 A 的记忆不能出现在 B 的上下文里", b.prompt.contains("花生"))
        assertTrue(store.searched.containsAll(listOf(scopeA, scopeB)))
    }

    @Test
    fun `global memory replaces the isolated store instead of merging into it`() = runTest {
        val repository = memoryRepository()
        repository.writeFile(scopeA, "/topics/food.md", foodMemory, MemoryRepository.NEW_FILE_VERSION)
        repository.writeFile(
            MemoryRepository.GLOBAL_MEMORY_ID,
            "/profile.md",
            FileContent.profile,
            MemoryRepository.NEW_FILE_VERSION,
        )
        val store = RecordingStore(repository)
        val global = assistantA.copy(useGlobalMemory = true)

        val injection = checkNotNull(buildRecallInjection(global, "我在杭州做什么工作", false, store))
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, injection.scope)
        assertTrue(injection.prompt.contains("我在杭州做后端开发"))
        assertFalse("打开全局记忆后不再读助手的私有库", store.searched.contains(scopeA))

        // 只存在于私有库的内容不会因为开着共享记忆就顺带被注入：不做隐式合并
        assertNull(buildRecallInjection(global, "花生过敏怎么办", false, store))
        assertTrue(store.searched.contains(scopeA).not())
    }

    @Test
    fun `auto consolidation off still recalls`() = runTest {
        val repository = memoryRepository()
        repository.writeFile(scopeA, "/topics/food.md", foodMemory, MemoryRepository.NEW_FILE_VERSION)
        val store = RecordingStore(repository)
        val manualOnly = assistantA.copy(autoConsolidateMemory = false)
        val automatic = assistantA.copy(autoConsolidateMemory = true)

        val off = checkNotNull(buildRecallInjection(manualOnly, "花生过敏还能吃什么", false, store))
        val on = checkNotNull(buildRecallInjection(automatic, "花生过敏还能吃什么", false, store))
        assertEquals(on.prompt, off.prompt)
        assertTrue(off.prompt.contains("我对花生严重过敏"))
    }

    @Test
    fun `an empty store adds nothing instead of an empty record block`() = runTest {
        val store = RecordingStore(memoryRepository())
        // 旧实现无论有没有命中都会拼出一段「空背景记录」，所以没有工具时必须在注入前拦掉
        assertTrue(buildRecallPrompt(emptyList(), emptyList(), false).isNotBlank())
        assertNull(buildRecallInjection(assistantA, "花生过敏还能吃什么", false, store))

        // 带记忆工具的模型例外：这段文本还托着「只在用户明确要求时才写」的规则，空了也要留着
        val withTools = checkNotNull(buildRecallInjection(assistantA, "花生过敏还能吃什么", true, store))
        assertTrue(withTools.prompt.contains("memory_read"))
        assertTrue(withTools.prompt.contains("explicit request"))
        assertTrue(withTools.excerpts.isEmpty())
    }

    @Test
    fun `a memory saved for review is recalled on the next turn`() = runTest {
        val repository = memoryRepository()
        val store = RecordingStore(repository)
        // 与 HistoryMemoryManager 保存候选时同样的写法：标题是主题，正文是那句话，来源注释不参与召回
        val file = MemoryFile("/topics/history-reading.md", "", 0)
        val content = file.contentWith(
            title = "阅读习惯",
            description = "阅读习惯",
            aliases = emptyList(),
            body = "- [stated] 我每天晚上十点读一小时的纸质书\n\n<!-- history-source c m 2026-01-01T00:00:00Z job -->",
        )
        val written = repository.writeFile(scopeA, file.path, content, MemoryRepository.NEW_FILE_VERSION)
        assertTrue("保存路径本身要成功：$written", written is MemoryWriteResult.Success)

        // 关键词一路：问题里的词直接对上
        val keyword = checkNotNull(buildRecallInjection(assistantA, "我晚上读书的习惯你记得吗", false, store))
        assertTrue(keyword.prompt.contains("每天晚上十点读一小时的纸质书"))
        assertFalse("来源注释不该被注入", keyword.prompt.contains("history-source"))

        // 换一种说法（语义近似）仍然能召回
        val rephrased = checkNotNull(buildRecallInjection(assistantA, "我平时读纸质书的习惯", false, store))
        assertTrue(rephrased.prompt.contains("每天晚上十点"))
    }

    @Test
    fun `preferences are injected every turn but an outdated file is not`() = runTest {
        val repository = memoryRepository()
        repository.writeFile(
            scopeA,
            MemoryFile.PREFERENCES_PATH,
            "---\nname: preferences\ndescription: 沟通偏好\n---\n\n- [stated] 回答时先给结论，再给理由\n",
            MemoryRepository.NEW_FILE_VERSION,
        )
        val store = RecordingStore(repository)

        val injection = checkNotNull(buildRecallInjection(assistantA, "帮我看看这段代码", false, store))
        assertTrue(injection.prompt.contains("回答时先给结论"))

        val current = checkNotNull(repository.getFile(scopeA, MemoryFile.PREFERENCES_PATH))
        repository.writeFile(
            scopeA,
            MemoryFile.PREFERENCES_PATH,
            current.contentWithOutdated(true),
            current.version,
        )
        assertNull("过时的偏好不该再注入", buildRecallInjection(assistantA, "帮我看看这段代码", false, store))
    }

    @Test
    fun `a match on the second page of the store is still injected`() = runTest {
        val repository = memoryRepository()
        // 20 条记忆：检索按 path 每页 16 条分页，命中的那条排在第二页
        repeat(19) { index ->
            repository.writeFile(
                scopeA,
                "/topics/filler-%02d.md".format(index),
                "- [stated] 第 $index 条无关记录：我收藏了几本书\n",
                MemoryRepository.NEW_FILE_VERSION,
            )
        }
        repository.writeFile(scopeA, "/topics/zz91.md", foodMemory, MemoryRepository.NEW_FILE_VERSION)
        val store = RecordingStore(repository)

        val injection = checkNotNull(buildRecallInjection(assistantA, "花生过敏还能吃什么", false, store))
        assertTrue(injection.prompt.contains("/topics/zz91.md"))
        assertTrue("分页要扫完全库：${injection.considered}", injection.considered >= 20)
    }

    @Test
    fun `the injected text is appended to the existing system message`() = runTest {
        val repository = memoryRepository()
        repository.writeFile(scopeA, "/topics/food.md", foodMemory, MemoryRepository.NEW_FILE_VERSION)
        val store = RecordingStore(repository)
        val injection = checkNotNull(buildRecallInjection(assistantA, "花生过敏还能吃什么", false, store))

        val messages = listOf(UIMessage.system("人设"), UIMessage.user("花生过敏还能吃什么"))
        val request = messages.withRecallInjection(injection)
        assertEquals(2, request.size)
        val system = request.first()
        assertEquals(MessageRole.SYSTEM, system.role)
        assertTrue(system.toText().startsWith("人设"))
        assertTrue(system.toText().contains("我对花生严重过敏"))

        // 没有系统消息时新起一条合成系统消息，而不是丢掉召回内容
        val noSystem = listOf(UIMessage.user("花生过敏还能吃什么")).withRecallInjection(injection)
        assertEquals(2, noSystem.size)
        assertEquals(MessageRole.SYSTEM, noSystem.first().role)
        assertTrue(noSystem.first().isSynthetic)
        assertTrue(noSystem.first().toText().contains("我对花生严重过敏"))
    }

    @Test
    fun `the semantic stage scores a rephrased question on its own`() {
        // 召回第二路（二元组语义近似）单独验一次：没有整句命中、也没有别名时，靠这一路给出命中原因
        val file = MemoryFile(
            path = "/topics/history-reading.md",
            content = MemoryFile("/topics/history-reading.md", "", 0).contentWith(
                title = "阅读习惯",
                description = "阅读习惯",
                aliases = emptyList(),
                body = "- [stated] 我每天晚上十点读一小时的纸质书",
            ),
            updatedAt = System.currentTimeMillis(),
        )
        val candidate = checkNotNull(
            MemoryRecallEngine.score(file, MemoryRecallEngine.analyze("我每天晚上读一小时纸质书")),
        )
        assertTrue(
            "语义近似这一路要真的参与打分：${candidate.reasons}",
            candidate.reasons.any { it.startsWith("语义近似") },
        )
    }

    // ---- 夹具 ----

    // 与 MemoryRecallTest 共用同一份内存版 DAO：过滤、分页、软删除的语义只有一处
    private fun memoryRepository() = MemoryRepository(FakeMemoryDAO())

    private object FileContent {
        const val profile = "- [stated] 我在杭州做后端开发，主要用 Kotlin\n"
    }

    /** 记录注入逻辑实际读了哪个记忆库：关掉记忆、全局记忆的用例靠它证明「没去读」 */
    private class RecordingStore(private val repository: MemoryRepository) : MemoryRecallStore {
        val searched = mutableListOf<String>()
        val read = mutableListOf<String>()

        override suspend fun search(scope: String, query: String): RecallResult {
            searched += scope
            return repository.searchRecallWithDiagnostics(scope, query)
        }

        override suspend fun file(scope: String, path: String): MemoryFile? {
            read += scope
            return repository.getFile(scope, path)
        }
    }

}
