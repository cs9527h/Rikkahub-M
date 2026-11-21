package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Runs only at startup, before Room/Settings consumers are initialized. Settings restores
 * merge archived memories into a snapshot of the LATEST live database, not the database
 * as it existed when the user first selected restore. Chat restores preserve live memories.
 * Only staged copies are edited; PendingRestore remains responsible for atomic installation.
 */
internal object PartialMemoryRestore {
    const val KIND_FILE = "restore-kind"
    private const val READY_FILE = "memory-prepared"

    suspend fun prepare(context: Context) {
        val root = File(context.noBackupFilesDir, "backup-restore")
        val pending = File(root, "pending")
        if (!pending.isDirectory || File(pending, "journal.json").exists()) return
        val memoryFile = File(pending, MemoryBackupCodec.FILE_NAME)
        val kind = File(pending, KIND_FILE).takeIf { it.isFile }?.readText()?.let { BackupKind.fromWireName(it) }
            ?: BackupKind.FULL
        val stagedDatabase = File(pending, "payload/database/${SQLiteConfiguration.DATABASE_NAME}")
        val preserveCurrentMemory = kind == BackupKind.CHATS && stagedDatabase.isFile
        if (!memoryFile.isFile && !preserveCurrentMemory) return
        if (File(pending, READY_FILE).isFile) {
            check(stagedDatabase.isFile && stagedDatabase.length() > 0) { "已准备的记忆数据库缺失" }
            return
        }
        // An absent journal must mean no live file was moved. Never delete an unexpected original.
        check(!File(pending, "originals").exists()) { "恢复状态异常，需要先核查原始文件" }
        // A previous process may have died while staging; those directories never held live data.
        pending.listFiles()?.filter { it.isDirectory && it.name.startsWith("memory-prepare-") }
            ?.forEach { it.deleteRecursively() }
        val working = Files.createTempDirectory(pending.toPath(), "memory-prepare-").toFile()
        try {
            val live = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
            val latest = File(working, "latest.db")
            if (live.isFile) DatabaseBackup.createSnapshot(context, live, latest)
            val sourceMemories = if (memoryFile.isFile) {
                require(memoryFile.length() <= MemoryBackupCodec.MAX_BYTES) { "记忆备份超过安全上限" }
                MemoryBackupCodec.decode(memoryFile.readText()).map {
                    MemoryFileEntity(it.assistantId, it.path, it.content, it.updatedAt, it.deletedAt)
                }
            } else if (latest.isFile) {
                val room = AppDatabaseFactory.create(context, latest.absolutePath)
                try {
                    room.memoryDao().getAllFiles()
                } finally {
                    room.close()
                }
            } else {
                emptyList()
            }
            val merged = File(working, "merged.db")
            when {
                preserveCurrentMemory -> Files.copy(stagedDatabase.toPath(), merged.toPath())
                latest.isFile -> Files.copy(latest.toPath(), merged.toPath())
            }
            val room = AppDatabaseFactory.create(context, merged.absolutePath)
            try {
                room.withTransaction {
                    room.memoryDao().deleteAllFiles()
                    room.memoryDao().upsertFiles(sourceMemories)
                }
                DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
            } finally {
                room.close()
            }
            DatabaseBackup.normalize(context, merged)
            require(stagedDatabase.parentFile!!.isDirectory || stagedDatabase.parentFile!!.mkdirs())
            Files.move(merged.toPath(), stagedDatabase.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            PendingRestore.writeDurably(File(pending, READY_FILE), "1")
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // No live data was changed. Remove only this unpublished staged import, never originals.
            if (!File(pending, "journal.json").exists() && !File(pending, "originals").exists()) {
                pending.deleteRecursively()
                throw RestoreFailedException(error)
            }
            throw error
        } finally {
            working.deleteRecursively()
        }
    }
}
