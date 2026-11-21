package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.AutoBackupPolicy
import me.rerere.rikkahub.data.sync.AutoBackupState
import me.rerere.rikkahub.data.sync.BackupArchiveFormat
import me.rerere.rikkahub.data.sync.BackupKind
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.BackupRetention
import me.rerere.rikkahub.data.sync.BackupVersion
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "WebDavSync"

class WebDavSync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(
        config: WebDavConfig,
        passphrase: CharArray? = null,
        includeCredentials: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, passphrase, includeCredentials)
        try {
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(path = file.name, file = file, contentType = "application/octet-stream").getOrThrow()
            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            // Upload failures and cancellation must not leave plaintext chat archives in cache.
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "backup: Failed to remove temporary archive")
            }
        }
    }

    /** Returns null when the verified content equals the last successfully uploaded version. */
    suspend fun backupAutomatically(
        config: WebDavConfig,
        passphrase: CharArray,
        includeCredentials: Boolean,
        kind: BackupKind,
        lastSuccess: AutoBackupState?,
    ): String? = withContext(Dispatchers.IO) {
        require(kind != BackupKind.FULL && passphrase.isNotEmpty())
        val prepared = backupManager.prepareBackup(
            includeDatabase = kind == BackupKind.CHATS,
            includeFiles = false,
            passphrase = passphrase,
            includeCredentials = includeCredentials,
            kind = kind,
        )
        try {
            val destinationId = AutoBackupPolicy.destinationId("webdav", config.url, config.path, config.username)
            if (AutoBackupPolicy.unchanged(lastSuccess, prepared.fingerprint, destinationId)) return@withContext null
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(prepared.file.name, prepared.file, "application/octet-stream").getOrThrow()
            prepared.fingerprint
        } finally {
            if (prepared.file.exists() && !prepared.file.delete()) Log.w(TAG, "无法清理自动备份临时文件")
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && BackupArchiveFormat.isBackupName(it.displayName) }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    /** Deletes only older AUTOMATIC archives of [kind]; manual archives are never selected. */
    suspend fun trimAutomaticBackups(config: WebDavConfig, kind: BackupKind, keep: Int): Int =
        withContext(Dispatchers.IO) {
            if (keep <= 0) return@withContext 0
            val items = listBackupFiles(config)
            val expired = BackupRetention.selectExpired(
                items.map { BackupVersion(it.displayName, it.lastModified.toEpochMilli()) },
                kind,
                keep,
            )
            var deleted = 0
            for (version in expired) {
                currentCoroutineContext().ensureActive()
                val item = items.firstOrNull { it.displayName == version.name } ?: continue
                runCatching { deleteBackupFile(config, item) }
                    .onSuccess { deleted++ }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "trimAutomaticBackups: 无法删除旧版本")
                    }
            }
            deleted
        }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem, passphrase: CharArray? = null) = withContext(
        Dispatchers.IO
    ) {
        val client = getClient(config)
        val backupFile = File.createTempFile("restore-", ".zip", context.cacheDir)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restore: Downloading ${item.displayName}")
            client.downloadToFile(item.displayName, backupFile).getOrThrow()

            Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config, passphrase)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig, passphrase: CharArray? = null) {
        restoreFromBackupFile(file, config, passphrase)
    }

    suspend fun prepareBackupFile(
        config: WebDavConfig,
        passphrase: CharArray? = null,
        includeCredentials: Boolean = false,
    ): File = backupManager.createBackup(
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        passphrase = passphrase,
        includeCredentials = includeCredentials,
    )

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: WebDavConfig,
        passphrase: CharArray?,
    ) = backupManager.stageRestore(
        archive = backupFile,
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        passphrase = passphrase,
    )
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
