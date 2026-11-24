package me.rerere.rikkahub.ui.pages.backup

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
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

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val deepSeekMigrator: DeepSeekMigrator,
    private val backupPassphraseStore: me.rerere.rikkahub.data.sync.BackupPassphraseStore,
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

    val automaticPassphraseConfigured = MutableStateFlow(false)
    val lastRestoreMessage = MutableStateFlow<String?>(null)
    private val archiveMutex = Mutex()

    /** 离开页面只清提示，不动本机已保存的恢复密钥。 */
    fun clearBackupPassphrase() {
        lastRestoreMessage.value = null
    }

    /**
     * 日常零口令：本机没有密钥时静默生成一把，之后手动/自动备份与同机恢复都用它。
     * 备份内容一律加密，API Key / OAuth 等凭据一律排除。
     */
    private suspend fun <T> withPassphrase(export: Boolean, block: suspend (CharArray?, Boolean) -> T): T =
        archiveMutex.withLock {
            val stored = if (export) backupPassphraseStore.readOrCreate() else backupPassphraseStore.read()
            if (export) automaticPassphraseConfigured.value = true
            try {
                block(stored, false)
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
        viewModelScope.launch {
            localBackupItems.value = settingsStore.settingsFlow.first().backupReminderConfig.localBackupItems
        }
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    /** 导出恢复密钥文件：换机或重装后用它恢复，不依赖本机 Keystore。 */
    suspend fun exportRecoveryKey(uri: android.net.Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val secret = backupPassphraseStore.readOrCreate()
            try {
                val text = me.rerere.rikkahub.data.sync.BackupRecoveryKey.encode(secret)
                requireNotNull(context.contentResolver.openOutputStream(uri)) { "无法写入所选位置" }
                    .use { it.write(text.toByteArray(Charsets.UTF_8)) }
                automaticPassphraseConfigured.value = true
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    /** 导入恢复密钥文件：只接受本应用导出的格式，覆盖本机密钥前先清掉自动备份状态。 */
    suspend fun importRecoveryKey(uri: android.net.Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选文件" }
                .use { it.reader(Charsets.UTF_8).readText() }
            val secret = me.rerere.rikkahub.data.sync.BackupRecoveryKey.decode(text)
            try {
                settingsStore.update { current ->
                    current.copy(
                        backupReminderConfig = current.backupReminderConfig.copy(autoBackupStates = emptyMap())
                    )
                }
                backupPassphraseStore.save(secret)
                automaticPassphraseConfigured.value = true
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    /** 一键备份：本机位置由 UI 走 SAF 导出，远端走各自的同步实现，都自动确保已加密。 */
    suspend fun backupNow(target: me.rerere.rikkahub.data.datastore.BackupTarget) {
        when (target) {
            me.rerere.rikkahub.data.datastore.BackupTarget.WEBDAV -> backup()
            me.rerere.rikkahub.data.datastore.BackupTarget.S3 -> backupToS3()
            me.rerere.rikkahub.data.datastore.BackupTarget.LOCAL -> Unit
        }
    }

    /** 打开自动备份：本机密钥缺失时静默生成，不需要用户输入口令。 */
    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        if (enabled) backupPassphraseStore.readOrCreate().also { it.fill('\u0000') }
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
        automaticPassphraseConfigured.value = backupPassphraseStore.isConfigured()
        if (enabled) {
            me.rerere.rikkahub.mate.MateAutoBackupWorker.schedule(context)
        } else {
            me.rerere.rikkahub.mate.MateAutoBackupWorker.cancel(context)
        }
    }

    /**
     * 设置自动备份/本机导出使用的口令（至少 6 位）。会替换本机随机密钥；
     * 已经存在的旧备份仍然用它们各自的口令或旧密钥恢复，不会失效。
     */
    suspend fun setBackupPassphrase(passphrase: CharArray) = withContext(Dispatchers.IO) {
        require(passphrase.size >= 6) { "口令至少 6 位" }
        try {
            backupPassphraseStore.save(passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
        automaticPassphraseConfigured.value = backupPassphraseStore.isConfigured()
    }

    /** 丢弃自定义口令，重新生成一把只存在于本机 Keystore 的随机密钥。 */
    suspend fun resetBackupPassphraseToGeneratedKey() = withContext(Dispatchers.IO) {
        backupPassphraseStore.clear()
        backupPassphraseStore.readOrCreate().also { it.fill('\u0000') }
        automaticPassphraseConfigured.value = backupPassphraseStore.isConfigured()
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
        } else {
            withPassphrase(export = false) { passphrase, _ ->
                webDavSync.restore(config = settings.value.webDavConfig, item = item, passphrase = passphrase)
            }
        }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    /**
     * 导出本机备份。
     *
     * [customPassphrase] 为空时用本机密钥（换机恢复需要导入恢复密钥文件）；
     * 传入自定义口令时用这个口令加密，恢复方只要知道口令即可，不再依赖密钥。
     */
    suspend fun exportToFile(customPassphrase: CharArray? = null): File =
        if (customPassphrase != null) {
            require(customPassphrase.size >= 6) { "口令至少 6 位" }
            webDavSync.prepareBackupFile(
                settings.value.webDavConfig.copy(items = localBackupItems.value),
                customPassphrase,
                includeCredentials = false,
            )
        } else {
            withPassphrase(export = true) { passphrase, includeCredentials ->
                webDavSync.prepareBackupFile(
                    settings.value.webDavConfig.copy(items = localBackupItems.value),
                    passphrase,
                    includeCredentials,
                )
            }
        }

    suspend fun recordLocalExportSuccess() = recordBackupTime()

    suspend fun restoreFromLocalFile(
        file: File,
        oneTimePassphrase: CharArray? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) =
        if (oneTimePassphrase != null) {
            webDavSync.restoreFromLocalFile(
                file,
                settings.value.webDavConfig.copy(items = localBackupItems.value),
                oneTimePassphrase,
                onProgress,
            )
        } else {
            withPassphrase(export = false) { passphrase, _ ->
                webDavSync.restoreFromLocalFile(
                    file,
                    settings.value.webDavConfig.copy(items = localBackupItems.value),
                    passphrase,
                    onProgress,
                )
            }
        }

    /** 从 SAF/网络流直接恢复：加密包边读边解密，省掉一次整包复制。 */
    suspend fun restoreFromStream(
        openStream: () -> java.io.InputStream?,
        oneTimePassphrase: CharArray? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val items = localBackupItems.value
        val stream = requireNotNull(openStream()) { "无法读取备份文件" }
        val key = oneTimePassphrase ?: backupPassphraseStore.read()
        try {
            backupManager.stageRestoreFromStream(
                input = stream,
                includeDatabase = me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem.DATABASE in items,
                includeFiles = me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem.FILES in items,
                passphrase = key,
                onProgress = onProgress,
            )
        } finally {
            key?.fill('\u0000')
            runCatching { stream.close() }
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
        } else {
            withPassphrase(export = false) { passphrase, _ ->
                s3Sync.restoreFromS3(config = settings.value.s3Config, item = item, passphrase = passphrase)
            }
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
