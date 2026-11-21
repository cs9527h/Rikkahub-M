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
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.BackupArchiveFormat
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.MissingBackupPassphraseException
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

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val deepSeekMigrator: DeepSeekMigrator,
    private val backupPassphraseStore: me.rerere.rikkahub.data.sync.BackupPassphraseStore,
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

    // Deliberately not persisted in Settings, SavedStateHandle, archives or logs.
    val encryptBackups = MutableStateFlow(true)
    val includeBackupCredentials = MutableStateFlow(false)
    val automaticPassphraseConfigured = MutableStateFlow(false)
    private val archiveMutex = Mutex()

    /** 离开页面时清掉敏感开关，不动本机已保存的恢复口令。 */
    fun clearBackupPassphrase() {
        includeBackupCredentials.value = false
    }

    /** 本机保存的恢复口令是唯一日常凭据；界面不再要求每次输入。 */
    private suspend fun <T> withPassphrase(export: Boolean, block: suspend (CharArray?, Boolean) -> T): T =
        archiveMutex.withLock {
            val includeCredentials = export && encryptBackups.value && includeBackupCredentials.value
            require(!includeCredentials || encryptBackups.value) { "包含凭据的备份必须加密" }
            val stored = if (encryptBackups.value) backupPassphraseStore.read() else null
            if (export && encryptBackups.value && stored == null) throw MissingBackupPassphraseException()
            try {
                block(stored, includeCredentials)
            } finally {
                stored?.fill('\u0000')
            }
        }

    override fun onCleared() {
        clearBackupPassphrase()
        super.onCleared()
    }

    init {
        viewModelScope.launch { automaticPassphraseConfigured.value = backupPassphraseStore.isConfigured() }
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    /** 只设置一次恢复口令：之后手动/自动备份都不再弹输入框。 */
    suspend fun configureBackupKey(passphrase: CharArray) {
        require(passphrase.size in 8..1024) { "恢复口令需为 8 至 1024 位" }
        settingsStore.update { current ->
            current.copy(backupReminderConfig = current.backupReminderConfig.copy(autoBackupStates = emptyMap()))
        }
        backupPassphraseStore.save(passphrase)
        automaticPassphraseConfigured.value = true
        encryptBackups.value = true
    }

    /** 关闭加密备份：仅允许手动明文备份，自动备份同时停止。 */
    suspend fun disableBackupEncryption() {
        settingsStore.update { current ->
            current.copy(
                backupReminderConfig = current.backupReminderConfig.copy(
                    autoBackupOnAppLifecycle = false,
                    autoBackupStates = emptyMap(),
                )
            )
        }
        me.rerere.rikkahub.mate.MateAutoBackupWorker.cancel(context)
        backupPassphraseStore.clear()
        automaticPassphraseConfigured.value = false
        encryptBackups.value = false
        includeBackupCredentials.value = false
    }

    suspend fun forgetAutomaticBackupPassphrase() {
        settingsStore.update { current ->
            current.copy(backupReminderConfig = current.backupReminderConfig.copy(autoBackupOnAppLifecycle = false))
        }
        me.rerere.rikkahub.mate.MateAutoBackupWorker.cancel(context)
        backupPassphraseStore.clear()
        automaticPassphraseConfigured.value = false
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() = withPassphrase(export = true) { passphrase, includeCredentials ->
        webDavSync.backup(settings.value.webDavConfig, passphrase, includeCredentials)
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem, oneTimePassphrase: CharArray? = null) =
        if (oneTimePassphrase != null) {
            webDavSync.restore(config = settings.value.webDavConfig, item = item, passphrase = oneTimePassphrase)
        } else withPassphrase(export = false) { passphrase, _ ->
        webDavSync.restore(config = settings.value.webDavConfig, item = item, passphrase = passphrase)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File = withPassphrase(export = true) { passphrase, includeCredentials ->
        webDavSync.prepareBackupFile(
            settings.value.webDavConfig.copy(items = localBackupItems.value),
            passphrase,
            includeCredentials,
        )
    }

    suspend fun recordLocalExportSuccess() = recordBackupTime()

    suspend fun restoreFromLocalFile(file: File, oneTimePassphrase: CharArray? = null) =
        if (oneTimePassphrase != null) {
            webDavSync.restoreFromLocalFile(
                file,
                settings.value.webDavConfig.copy(items = localBackupItems.value),
                oneTimePassphrase,
            )
        } else withPassphrase(export = false) { passphrase, _ ->
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.webDavConfig.copy(items = localBackupItems.value),
            passphrase,
        )
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
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() = withPassphrase(export = true) { passphrase, includeCredentials ->
        s3Sync.backupToS3(settings.value.s3Config, passphrase, includeCredentials)
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem, oneTimePassphrase: CharArray? = null) =
        if (oneTimePassphrase != null) {
            s3Sync.restoreFromS3(config = settings.value.s3Config, item = item, passphrase = oneTimePassphrase)
        } else withPassphrase(export = false) { passphrase, _ ->
            s3Sync.restoreFromS3(config = settings.value.s3Config, item = item, passphrase = passphrase)
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
