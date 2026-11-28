package me.rerere.rikkahub.ui.pages.backup

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.common.util.formatExceptionForLog
import me.rerere.common.util.runCatchingCancellable
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.importer.DeepSeekImportResult
import me.rerere.rikkahub.data.sync.importer.DeepSeekMigrator
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

internal suspend fun awaitLocalBackupItems(settingsStore: SettingsStore): List<WebDavConfig.BackupItem> =
    settingsStore.awaitLoaded().backupReminderConfig.localBackupItems

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val deepSeekMigrator: DeepSeekMigrator,
    private val backupManager: me.rerere.rikkahub.data.sync.BackupManager,
    private val context: android.content.Context,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems = MutableStateFlow(WebDavConfig.BackupItem.entries.toList())

    val lastRestoreMessage = MutableStateFlow<String?>(null)
    private val archiveMutex = Mutex()

    /** 离开页面只清提示，不动本机已保存的恢复密钥。 */
    override fun onCleared() {
        super.onCleared()
    }

    init {
        viewModelScope.launch {
            localBackupItems.value = awaitLocalBackupItems(settingsStore)
        }
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    /** 打开自动备份：本机密钥缺失时静默生成，不需要用户输入口令。 */
    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        settingsStore.update { current ->
            current.copy(
                backupReminderConfig = current.backupReminderConfig.copy(
                    autoBackupOnAppLifecycle = enabled,
                    // 同一个开关接管首页的「该备份了」提醒，不再单独给提醒一个入口。
                    enabled = enabled,
                    autoBackupStates = emptyMap(),
                )
            )
        }
        if (enabled) {
            me.rerere.rikkahub.mate.MateAutoBackupWorker.schedule(context)
        } else {
            me.rerere.rikkahub.mate.MateAutoBackupWorker.cancel(context)
        }
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        if (items.isEmpty()) return
        localBackupItems.value = items
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(backupReminderConfig = current.backupReminderConfig.copy(localBackupItems = items))
            }
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatchingCancellable {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                Log.e(TAG, formatExceptionForLog("Backup.listWebDav", it))
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    // 与上游一致：备份不加密、不需要口令或密钥
    suspend fun backup() {
        // 与「本机导出」一致：备份带上 API Key / OAuth，换机后不用重配
        webDavSync.backup(settings.value.webDavConfig, includeCredentials = true)
        recordBackupTime()
    }

    suspend fun backupNow(target: me.rerere.rikkahub.data.datastore.BackupTarget) {
        when (target) {
            me.rerere.rikkahub.data.datastore.BackupTarget.WEBDAV -> backup()
            me.rerere.rikkahub.data.datastore.BackupTarget.S3 -> backupToS3()
            me.rerere.rikkahub.data.datastore.BackupTarget.LOCAL -> Unit
        }
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.webDavConfig, item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    /** 导出本机备份：明文 zip（设置 + 聊天记录 + 附件 + 凭据），不加密、不需要口令。 */
    suspend fun exportToFile(): File = withContext(Dispatchers.IO) {
        webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value),
            includeCredentials = true,
        )
    }

    suspend fun recordLocalExportSuccess() = recordBackupTime()

    suspend fun restoreFromLocalFile(
        file: File,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
            onProgress,
        )
    }

    /** 从 SAF/网络流直接恢复：边读边落盘，省掉整包复制到 cache 的一遍读写。 */
    suspend fun restoreFromStream(
        openStream: () -> java.io.InputStream?,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val items = localBackupItems.value
        val stream = requireNotNull(openStream()) { "无法读取备份文件" }
        try {
            backupManager.stageRestoreFromStream(
                input = stream,
                includeDatabase = me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem.DATABASE in items,
                includeFiles = me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem.FILES in items,
                onProgress = onProgress,
            )
        } finally {
            runCatchingCancellable { stream.close() }
        }
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    suspend fun restoreFromDeepSeek(uri: Uri): DeepSeekImportResult = deepSeekMigrator.import(uri)

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers")

        updateSettings {
            it.copy(
                providers = importProviders + it.providers,
            )
        }
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatchingCancellable {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                Log.e(TAG, formatExceptionForLog("Backup.listS3", it))
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.s3Config, includeCredentials = true)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.s3Config, item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)
