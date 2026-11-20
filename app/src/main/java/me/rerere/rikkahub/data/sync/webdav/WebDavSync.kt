package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.BackupArchiveFormat
import me.rerere.rikkahub.data.datastore.WebDavConfig
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

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem, passphrase: CharArray? = null) = withContext(Dispatchers.IO) {
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

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig, passphrase: CharArray?) = backupManager.stageRestore(
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
