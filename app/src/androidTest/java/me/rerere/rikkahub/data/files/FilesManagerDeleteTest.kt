package me.rerere.rikkahub.data.files

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FilesManagerDeleteTest {
    @Test fun failedDiskDeletionMustKeepItsManagedRecord() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "managed-delete-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }
        val database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        val scope = AppScope()
        try {
            val directory = File(root, "upload/nonempty").apply { mkdirs() }
            File(directory, "keep.txt").writeText("keep")
            val id = database.managedFileDao().insert(ManagedFileEntity(
                folder = "upload",
                relativePath = "upload/nonempty",
                displayName = "nonempty",
                mimeType = "application/octet-stream",
                sizeBytes = 4,
                createdAt = 0,
                updatedAt = 0,
            ))
            val manager = FilesManager(context, FilesRepository(database.managedFileDao()), scope)
            assertFalse(manager.delete(id))
            assertNotNull(database.managedFileDao().getById(id))
            assertTrue(File(directory, "keep.txt").isFile)
        } finally {
            scope.cancel()
            database.close()
            root.deleteRecursively()
        }
    }
}
