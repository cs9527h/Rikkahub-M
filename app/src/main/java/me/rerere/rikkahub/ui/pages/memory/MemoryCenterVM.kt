package me.rerere.rikkahub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkahub.data.ai.memory.HistoryMemoryManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.dao.HistoryConversationSource
import me.rerere.rikkahub.data.db.entity.HistoryMemoryCandidate
import me.rerere.rikkahub.data.db.entity.HistoryMemoryJob
import me.rerere.rikkahub.data.db.entity.MemoryScopeState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId

/**
 * 记忆中心的唯一数据源。
 *
 * 这里刻意只保留一个 [scope]：记忆库选择器、统计、列表、搜索、整理任务观测、候选计数、
 * 使用记录和任务历史全都由它 flatMapLatest 出来。之前首页与二级页各自持有自己的作用域，
 * 切换记忆库时出现过“列表换了、候选数字没换”的两套状态，这里从结构上避免。
 *
 * @param assistantId 从助手详情进来时是那个助手；从设置进来时为 null，跟随当前选中的助手。
 */
internal class MemoryCenterVM(
    private val assistantId: String?,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val consolidationScheduler: MemoryConsolidationScheduler,
    private val history: HistoryMemoryManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow

    /** 每个助手使用独立导航页；选择设置对象不会改动聊天当前选中的助手。 */
    val assistant: StateFlow<Assistant> = settings.memorySettingsAssistantFlow(assistantId)
        // If an explicit target is removed, retain its identity until the UI leaves this page.
        // It must never turn into another assistant's editable settings or memory scope.
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settings.value.memorySettingsAssistant(assistantId)
                ?: Assistant(id = kotlin.uuid.Uuid.parseOrNull(assistantId.orEmpty()) ?: kotlin.uuid.Uuid.NIL),
        )

    /**
     * 用户手动切换记忆库的结果：null 表示跟随助手的 useGlobalMemory。
     *
     * 存的是“共享/独立”这个选择而不是具体 id：助手配置在别处被改（比如在设置页打开共享），
     * 页面上的选择也要跟着变，不能停在旧的 id 上。
     */
    private val scopePreference = MutableStateFlow<Boolean?>(null)

    /** 当前读写的记忆库，页面上一切数据都以它为准。 */
    val scope: StateFlow<String> = combine(assistant, scopePreference) { current, useGlobal ->
        when (useGlobal) {
            true -> MemoryRepository.GLOBAL_MEMORY_ID
            false -> current.id.toString()
            // 跟随助手：useGlobalMemory 打开时这里就是全局记忆库，和聊天里用的是同一个
            null -> current.memoryId
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, assistant.value.memoryId)

    val useGlobalMemory: StateFlow<Boolean> = scope
        .map { it == MemoryRepository.GLOBAL_MEMORY_ID }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val memories: StateFlow<List<MemoryFile>> = scope
        .flatMapLatest { memoryRepository.getFilesFlow(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val deletedMemories: StateFlow<List<MemoryFile>> = scope
        .flatMapLatest { memoryRepository.getDeletedFilesFlow(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val consolidation: StateFlow<MemoryConsolidationStatus> = scope
        .flatMapLatest { consolidationScheduler.statusOf(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MemoryConsolidationStatus())

    val job: StateFlow<HistoryMemoryJob?> = scope
        .flatMapLatest { history.observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 候选按记忆库统计：任务被替换、取消后，旧候选仍然算在这里，避免“数据看起来消失了”。
    val pendingCount: StateFlow<Int> = scope
        .flatMapLatest { history.countOf(it, "pending") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val skippedCount: StateFlow<Int> = scope
        .flatMapLatest { history.countOf(it, "discarded") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val savedCount: StateFlow<Int> = scope
        .flatMapLatest { history.countOf(it, "saved") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val lastState: StateFlow<MemoryScopeState?> = scope
        .flatMapLatest { history.state(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _plan = MutableStateFlow<HistoryMemoryManager.Plan?>(null)
    val plan: StateFlow<HistoryMemoryManager.Plan?> = _plan.asStateFlow()

    private val _result = MutableStateFlow<HistoryMemoryManager.BulkResult?>(null)
    val result: StateFlow<HistoryMemoryManager.BulkResult?> = _result.asStateFlow()

    private val _autoSave = MutableStateFlow(true)
    val autoSave: StateFlow<Boolean> = _autoSave.asStateFlow()

    /** 刚启动、还没结束的整理任务：(它的记忆库, 任务 id)。自动保存只认这一个任务。 */
    private val autoJob = MutableStateFlow<Pair<String, String>?>(null)

    init {
        viewModelScope.launch {
            // 认准启动时的记忆库去等这个任务结束：用户中途切走记忆库，结果也要写回任务自己的库
            autoJob.filterNotNull().collectLatest { (jobScope, jobId) ->
                val settled = history.observe(jobScope).first { current ->
                    current == null || current.id != jobId || current.status != "running"
                }
                autoJob.value = null
                if (settled == null || settled.id != jobId || settled.status != "finished") return@collectLatest
                // 自动保存只处理本轮任务新提取的候选，不碰上一轮还没审完的内容
                val keys = history.pendingKeysOfJob(jobId)
                if (keys.isEmpty()) return@collectLatest
                _busy.value = true
                try {
                    _result.value = if (_autoSave.value) {
                        history.saveSelected(jobScope, keys.toSet(), HistoryMemoryManager.BulkMode.SAVE_NEW_ONLY)
                    } else {
                        // 用户关掉了自动保存：一条都不写，全部留在待确认
                        HistoryMemoryManager.BulkResult(pending = keys.size)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _error.value = "整理完成，但自动保存没有成功；候选都还在，可以点“确认记忆”。"
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /**
     * 切换浏览的记忆库；[global] 为 true 时切到所有助手共享的那一份。
     *
     * 这里只改页面状态，绝不写 assistant.useGlobalMemory：浏览别人/共享的记忆不该顺手把
     * 聊天配置改掉。用户选回“就是助手实际在用的那一个”时清掉偏好，重新跟随助手，
     * 之后助手设置被改动，页面也跟着变。
     */
    fun selectGlobal(global: Boolean) {
        scopePreference.value = if (global == assistant.value.useGlobalMemory) null else global
    }

    /** 从“浏览别处”回到聊天实际使用的记忆库：清掉浏览偏好，重新跟随助手的 useGlobalMemory。 */
    fun followAssistantScope() {
        scopePreference.value = null
    }

    /** 当前记忆库对应的助手快照：整理与使用记录看的是这个库，不是另一个库的配置。 */
    private fun scopedAssistant(): Assistant =
        assistant.value.copy(useGlobalMemory = scope.value == MemoryRepository.GLOBAL_MEMORY_ID)

    fun startOrganize() = launchAction {
        _plan.value = null
        val proposed = history.prepareIncremental(scope.value, scopedAssistant())
        if (proposed == null) {
            _notice.value = "最近没有新的聊天需要整理，你之前整理的记忆都还在。"
        } else {
            _plan.value = proposed
        }
    }

    fun startReextract(sources: List<HistoryConversationSource>) = launchAction {
        _plan.value = history.prepare(scope.value, scopedAssistant(), sources)
    }

    fun dismissPlan() {
        _plan.value = null
    }

    fun setAutoSave(enabled: Boolean) {
        _autoSave.value = enabled
    }

    fun confirmPlan() = launchAction {
        val proposed = _plan.value ?: return@launchAction
        val started = history.start(proposed)
        autoJob.value = proposed.scope to started
        _plan.value = null
    }

    fun dismissResult() {
        _result.value = null
    }

    /** 二级页（确认记忆）自己算出的批量结果也走同一个结果弹窗，撤销入口只有一处。 */
    fun showResult(value: HistoryMemoryManager.BulkResult?) {
        _result.value = value
    }

    fun showError(message: String) {
        _error.value = message
    }

    fun dismissError() {
        _error.value = null
    }

    fun noticeShown() {
        _notice.value = null
    }

    fun control(status: String) = launchAction { history.control(scope.value, status) }

    private fun actionScope(): String? =
        scope.value.takeIf { settings.value.memorySettingsAssistant(assistantId) != null }

    fun deleteMemory(memory: MemoryFile) {
        val target = actionScope() ?: return
        viewModelScope.launch { memoryRepository.deleteFile(target, memory.path) }
    }

    fun restoreMemory(memory: MemoryFile) {
        val target = actionScope() ?: return
        viewModelScope.launch { memoryRepository.restoreFile(target, memory.path) }
    }

    fun purgeMemory(memory: MemoryFile) {
        val target = actionScope() ?: return
        viewModelScope.launch { memoryRepository.purgeFile(target, memory.path) }
    }

    /** 检索预览：让用户看到“问这句话时到底会带哪几条记忆”，不真的发请求。 */
    suspend fun previewRecall(query: String): List<MemoryFile> =
        runCatching { memoryRepository.searchForRecall(scope.value, query) }.getOrDefault(emptyList())

    fun resolveCandidate(candidate: HistoryMemoryCandidate, corrected: String?, mergeInto: MemoryFile?) = launchAction {
        history.resolve(scope.value, candidate.jobId, candidate.key, corrected, mergeInto)
    }

    fun undoLastBulk(previous: HistoryMemoryManager.BulkResult) = launchAction {
        _result.value = history.undoLastBulk(scope.value) ?: previous
        _notice.value = "已撤销上一次批量操作。"
    }

    fun restoreSkipped() = launchAction {
        val restored = history.restoreSkipped(scope.value)
        _result.value = HistoryMemoryManager.BulkResult(pending = restored)
        _notice.value = "已恢复 $restored 条被跳过的候选。"
    }

    /** 记忆中心只管记忆相关的开关，其余字段沿用当前值，避免把别处的修改覆盖掉。 */
    fun updateAssistant(before: Assistant, updated: Assistant) {
        val target = settings.value.memorySettingsAssistant(assistantId) ?: return
        if (before.id != updated.id || before.id != target.id) return
        if (updated.useGlobalMemory != before.useGlobalMemory) scopePreference.value = null
        viewModelScope.launch {
            // Capture the rendered assistant, then patch only changed memory fields onto the latest
            // stored value. Rapid toggles and edits elsewhere must not overwrite each other.
            settingsStore.update { settings -> settings.updateMemoryAssistant(before, updated) }
        }
    }

    /** 统一的忙碌与报错处理：任何一次点击只允许一个在飞，失败原因留在页面上。 */
    private fun launchAction(action: suspend () -> Unit) {
        if (_busy.value || settings.value.memorySettingsAssistant(assistantId) == null) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { e is IllegalStateException || e is IllegalArgumentException }
                    ?: "操作失败，请重试"
            } finally {
                _busy.value = false
            }
        }
    }
}
