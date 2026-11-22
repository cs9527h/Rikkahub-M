package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

internal object DatabaseBackup {
    const val ARCHIVE_DATABASE = "rikka_hub.db"
    const val WAL = "rikka_hub-wal"
    const val SHM = "rikka_hub-shm"

    /** VACUUM INTO includes committed WAL contents in a consistent, standalone snapshot. */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!destination.exists()) { "Backup snapshot already exists" }
        database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
    }

    /**
     * Snapshot the latest committed data before Room opens the live database. Read-only is the
     * normal path. A crash can leave a WAL that a read-only connection cannot recover; falling
     * back to a read-write handle may checkpoint it, which never changes logical user data and
     * avoids dead-ending the restore. No schema migration happens here.
     */
    fun createSnapshot(context: Context, source: File, destination: File) {
        require(source.isFile) { "原数据库不存在" }
        check(!destination.exists()) { "快照目标已存在" }
        fun open(flags: Int): SQLiteDatabase {
            val configuration = SQLiteConfiguration.configure(
                context,
                SQLiteDatabaseConfiguration(source.absolutePath, flags),
            )
            return SQLiteDatabase.openDatabase(configuration, null) { error("无法读取原数据库快照") }
        }
        val database = try {
            open(SQLiteDatabase.OPEN_READONLY)
        } catch (_: Exception) {
            open(SQLiteDatabase.OPEN_READWRITE)
        }
        database.use {
            it.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
        }
    }

    /** Only opens the staged copy. The caller must place its matching WAL beside it first. */
    fun normalize(context: Context, databaseFile: File) {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Backup database is missing or empty" }
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE)
        )
        SQLiteDatabase.openDatabase(configuration, null) {
            error("Backup database is corrupt")
        }.use { database ->
            checkpoint(database)
            database.query("PRAGMA integrity_check").use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                    "Backup database failed its integrity check"
                }
            }
        }
        removeSidecars(databaseFile)
    }

    /**
     * 备份只清理“记忆使用记录”：它带召回片段、只对本机有意义。
     * 待审核候选、整理任务与批次指纹必须保留，否则换机/重装后用户会丢掉还没保存的记忆，
     * 而且已经付费提取过的内容会再花一次钱。恢复后设备授权指纹不匹配，不会被重新上传。
     */
    fun stripLocalMemoryWork(context: Context, snapshot: File) {
        val config = SQLiteConfiguration.configure(
            context, SQLiteDatabaseConfiguration(snapshot.absolutePath, SQLiteDatabase.OPEN_READWRITE),
        )
        SQLiteDatabase.openDatabase(config, null) { error("Could not open staged memory backup") }.use { db ->
            stripLocalMemoryWork(db)
            checkpoint(db)
        }
        removeSidecars(snapshot)
    }

    fun stripLocalMemoryWork(db: SupportSQLiteDatabase) {
        val localTables = listOf("memory_recall")
        val existing = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use {
            buildSet { while (it.moveToNext()) add(it.getString(0)) }
        }
        if (localTables.none { it in existing }) return
        // requery 驱动只允许通过 query/rawQuery 执行 PRAGMA；execSQL 会抛 "Queries can be performed ..." 
        db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        db.beginTransaction()
        try {
            localTables.filter { it in existing }.forEach { db.execSQL("DELETE FROM `$it`") }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun checkpoint(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the backup database"
            }
        }
    }

    fun removeSidecars(databaseFile: File) {
        // All connections must be closed and committed WAL data checkpointed before removal.
        check(File(databaseFile.path + "-wal").length() == 0L) { "Backup WAL was not fully checkpointed" }
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(databaseFile.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove staged database sidecar" }
        }
    }
}
