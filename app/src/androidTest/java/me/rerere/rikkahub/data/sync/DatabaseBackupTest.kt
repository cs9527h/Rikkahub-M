package me.rerere.rikkahub.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.migrations.Migration_34_35
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class DatabaseBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "database-backup-test-").toFile()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.CREATE_IF_NECESSARY)
        ),
        null,
        null,
    )

    private fun createWalDatabase(file: File): SQLiteDatabase = open(file).apply {
        enableWriteAheadLogging()
        query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
        execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, text TEXT)")
        query("PRAGMA wal_checkpoint(TRUNCATE)").use { assertTrue(it.moveToFirst()) }
        execSQL("INSERT INTO messages(text) VALUES ('committed only in WAL')")
        assertTrue(File(file.path + "-wal").length() > 0)
    }

    @Test fun legacyWalIsMergedWithoutShmAndWithoutChangingOriginalArchiveFiles() {
        val source = File(directory, "source")
        val staged = File(directory, "rikka_hub")
        createWalDatabase(source).use {
            source.copyTo(staged)
            File(source.path + "-wal").copyTo(File(staged.path + "-wal"))
        }
        DatabaseBackup.normalize(context, staged)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        open(staged).use {
            assertEquals("committed only in WAL", it.stringForQuery("SELECT text FROM messages", null))
        }
    }

    @Test fun snapshotContainsWalCommitsAndFtsTablesAndNeedsNoSidecars() {
        val source = File(directory, "source")
        val snapshot = File(directory, "snapshot")
        createWalDatabase(source).use {
            it.execSQL("CREATE VIRTUAL TABLE search USING fts5(text, tokenize='simple')")
            it.execSQL("INSERT INTO search(text) VALUES ('hello backup')")
            DatabaseBackup.createSnapshot(it, snapshot)
            // Subsequent writes must not appear in the already-created snapshot.
            it.execSQL("INSERT INTO messages(text) VALUES ('after snapshot')")
        }
        assertFalse(File(snapshot.path + "-wal").exists())
        assertFalse(File(snapshot.path + "-shm").exists())
        DatabaseBackup.normalize(context, snapshot)
        open(snapshot).use {
            assertEquals(1L, it.longForQuery("SELECT count(*) FROM messages", null))
            assertEquals(1L, it.longForQuery("SELECT count(*) FROM search WHERE search MATCH 'hello'", null))
        }
    }

    @Test fun readOnlySnapshotIncludesLatestWalWithoutChangingSourceBytes() {
        val source = File(directory, "source")
        val snapshot = File(directory, "readonly-snapshot")
        createWalDatabase(source).use { live ->
            val before = source.readBytes()
            val beforeWal = File(source.path + "-wal").readBytes()
            DatabaseBackup.createSnapshot(context, source, snapshot)
            org.junit.Assert.assertArrayEquals(before, source.readBytes())
            org.junit.Assert.assertArrayEquals(beforeWal, File(source.path + "-wal").readBytes())
            assertEquals(1L, live.longForQuery("SELECT count(*) FROM messages", null))
        }
        open(snapshot).use {
            assertEquals("committed only in WAL", it.stringForQuery("SELECT text FROM messages", null))
        }
    }

    @Test fun unchangedDatabaseProducesStableSnapshotBytesForDirtyDetection() {
        val source = File(directory, "source")
        val first = File(directory, "first")
        val second = File(directory, "second")
        createWalDatabase(source).use {
            DatabaseBackup.createSnapshot(context, source, first)
            DatabaseBackup.createSnapshot(context, source, second)
        }
        org.junit.Assert.assertArrayEquals(first.readBytes(), second.readBytes())
    }

    /**
     * 34→35 的 SQL 行为（本类由 CI 的 BackupManagerTest/DatabaseBackupTest 过滤器执行）：
     * 旧行保留内容并获得 deleted_at = 0，软删除可回滚。不做 Room 完整 schema 校验。
     */
    @Test fun memoryTrashMigrationKeepsOldRowsActiveAndRestorable() {
        val file = File(directory, "v34-memory")
        val v34 = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY)
        v34.execSQL(
            "CREATE TABLE memory_file (" +
                "assistant_id TEXT NOT NULL, path TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', " +
                "updated_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(assistant_id, path))"
        )
        v34.execSQL(
            "INSERT INTO memory_file(assistant_id, path, content, updated_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("assistant", "/profile.md", "synthetic memory", 1234),
        )
        Migration_34_35.migrate(v34)
        v34.query(
            "SELECT content, updated_at, deleted_at FROM memory_file WHERE assistant_id = 'assistant'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("synthetic memory", cursor.getString(0))
            assertEquals(1234L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
        }
        v34.execSQL("UPDATE memory_file SET deleted_at = 999 WHERE path = '/profile.md'")
        v34.query("SELECT count(*) FROM memory_file WHERE deleted_at != 0").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        v34.execSQL("UPDATE memory_file SET deleted_at = 0 WHERE path = '/profile.md'")
        v34.close()
        open(file).use { migrated ->
            assertEquals(1L, migrated.longForQuery("SELECT count(*) FROM memory_file WHERE deleted_at = 0", null))
        }
    }

    @Test fun corruptDatabaseIsRejected() {
        val file = File(directory, "invalid")
        file.writeText("not a SQLite database")
        var failed = false
        try {
            DatabaseBackup.normalize(context, file)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Corrupt backup must not be published", failed)
    }
}
