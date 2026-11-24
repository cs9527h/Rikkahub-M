package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "S3Sync"

class S3Sync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
) {
    private fun getS3Client(config: S3Config): S3Client {
        // 备份包里有聊天库与（可选的）凭据，endpoint 没写协议时必须补 https，
        // 否则凭据与整包内容会走明文 http（RemoteFileStore 已经这么做，这里保持一致）。
        return S3Client(config.withHttpsByDefault(), httpClient)
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(
        config: S3Config,
        passphrase: CharArray? = null,
        includeCredentials: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, passphrase, includeCredentials)
        try {
            val client = getS3Client(config)
            client.putObject(
                key = "rikkahub_backups/${file.name}",
                file = file,
                contentType = "application/octet-stream"
            ).getOrThrow()
            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            // Failures and cancellation must also remove temporary archives.
            file.delete()
        }
    }

    /** Returns null if this destination already holds the same successful content version. */
    suspend fun backupAutomatically(
        config: S3Config,
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
            val destinationId = AutoBackupPolicy.destinationId(
                "s3",
                config.endpoint,
                config.bucket,
                config.region,
                config.pathStyle.toString(),
                config.accessKeyId,
            )
            if (AutoBackupPolicy.unchanged(lastSuccess, prepared.fingerprint, destinationId)) return@withContext null
            getS3Client(config).putObject(
                key = "rikkahub_backups/${prepared.file.name}",
                file = prepared.file,
                contentType = "application/octet-stream",
            ).getOrThrow()
            prepared.fingerprint
        } finally {
            if (prepared.file.exists() && !prepared.file.delete()) Log.w(TAG, "无法清理自动备份临时文件")
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        val objects = completeBackupListing { token ->
            val page = client.listObjects(
                prefix = "rikkahub_backups/",
                maxKeys = 1000,
                continuationToken = token,
            ).getOrThrow()
            BackupListingPage(page.objects, page.isTruncated, page.nextContinuationToken)
        }

        objects.distinctBy { it.key }
            .filter {
                it.key.startsWith("rikkahub_backups/") &&
                    BackupArchiveFormat.isBackupName(it.key.removePrefix("rikkahub_backups/"))
            }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    /** Deletes only older AUTOMATIC archives of [kind]; manual archives are never selected. */
    suspend fun trimAutomaticBackups(config: S3Config, kind: BackupKind, keep: Int): Int =
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
                runCatching { deleteS3BackupFile(config, item) }
                    .onSuccess { deleted++ }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "trimAutomaticBackups: 无法删除旧版本")
                    }
            }
            deleted
        }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem, passphrase: CharArray? = null) = withContext(
        Dispatchers.IO
    ) {
        val client = getS3Client(config)
        val backupFile = File.createTempFile("restore-", ".zip", context.cacheDir)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
            client.downloadObjectToFile(item.key, backupFile).getOrThrow()

            Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, config, passphrase)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(
        config: S3Config,
        passphrase: CharArray? = null,
        includeCredentials: Boolean = false,
    ): File = backupManager.createBackup(
        includeDatabase = S3Config.BackupItem.DATABASE in config.items,
        includeFiles = S3Config.BackupItem.FILES in config.items,
        passphrase = passphrase,
        includeCredentials = includeCredentials,
    )

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: S3Config,
        passphrase: CharArray?,
    ) = backupManager.stageRestore(
        archive = backupFile,
        includeDatabase = S3Config.BackupItem.DATABASE in config.items,
        includeFiles = S3Config.BackupItem.FILES in config.items,
        passphrase = passphrase,
    )
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
