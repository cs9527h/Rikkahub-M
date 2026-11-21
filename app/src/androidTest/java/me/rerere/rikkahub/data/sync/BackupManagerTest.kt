package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var manager: BackupManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var settingsScope: kotlinx.coroutines.CoroutineScope

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "backup-manager-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) {
                File(name)
            } else {
                File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
            }
        }
        liveDatabase = AppDatabaseFactory.create(context)
        liveDatabase.openHelper.writableDatabase.execSQL("CREATE TABLE backup_probe (text TEXT)")
        liveDatabase.openHelper.writableDatabase.execSQL("INSERT INTO backup_probe VALUES ('live')")
        settingsScope =
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val preferences = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = settingsScope,
            produceFile = { File(directory, "fixture.preferences_pb") },
        )
        settingsStore = SettingsStore(preferences, settingsScope)
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { settingsStore.awaitLoaded() } }
        manager = BackupManager(context, liveDatabase, settingsStore, JsonInstant)
    }

    @After fun tearDown() {
        runBlocking { settingsScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
        liveDatabase.close()
        directory.deleteRecursively()
    }

    @Test fun oldArchiveIgnoresShmAndLeavesLiveDatabaseUntouchedUntilStartup() = runBlocking {
        val source = File(directory, "source")
        val archive = File(directory, "legacy.zip")
        withRoom(source) { room ->
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE backup_probe (text TEXT)")
            DatabaseBackup.checkpoint(db)
            db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
            db.execSQL("INSERT INTO backup_probe VALUES ('archived')")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addFile(zip, DatabaseBackup.ARCHIVE_DATABASE, source)
                addFile(zip, DatabaseBackup.WAL, File(source.path + "-wal"))
                zip.putNextEntry(ZipEntry(DatabaseBackup.SHM))
                zip.write("deliberately invalid SHM".toByteArray())
                zip.closeEntry()
            }
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        liveDatabase.close()
        BackupManager.applyPendingRestore(context, JsonInstant)
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("archived", probe(liveDatabase))
        ZipFile(archive).use { assertTrue(it.getEntry(DatabaseBackup.SHM) != null) }
    }

    @Test fun newArchiveContainsStandaloneDatabaseAndNoWalOrShm() = runBlocking {
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("settings.json") != null)
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun launchCountStaysInSettingsJsonThroughBackupAndStaging() = runBlocking {
        val archive = manager.createBackup(includeDatabase = false, includeFiles = false)
        val archived = ZipFile(archive).use { zip ->
            launchCountOf(zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString())
        }
        assertTrue(archived != null)
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
        assertEquals(archived, launchCountOf(staged))
    }

    @Test fun archiveWithoutLaunchCountRestoresItAsZero() = runBlocking {
        val current = manager.createBackup(includeDatabase = false, includeFiles = false)
        val settingsJson = ZipFile(current).use { zip ->
            zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString()
        }
        val archive = File(directory, "no-launch-count.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(
                JsonObject(
                    JsonInstant.parseToJsonElement(settingsJson).jsonObject - "launchCount"
                ).toString().toByteArray()
            )
            zip.closeEntry()
        }
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
        assertEquals(0, launchCountOf(staged))
    }

    private fun launchCountOf(settingsJson: String): Int? =
        JsonInstant.parseToJsonElement(settingsJson).jsonObject["launchCount"]?.jsonPrimitive?.int

    @Test fun mediaCreationFilesKeepTheirDirectoriesAndSkipUnfinishedDownloads() = runBlocking {
        val session = File(context.filesDir, "media_creation/session")
        File(session, "record").mkdirs()
        File(session, "draft").mkdirs()
        File(session, "record/out_0.png").writeText("image")
        File(session, "record/out_1.mp4.part").writeText("half a video")
        File(session, "draft/asset.jpg").writeText("asset")
        val archive = manager.createBackup(includeDatabase = false, includeFiles = true)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("media_creation/session/record/out_0.png") != null)
            assertTrue(zip.getEntry("media_creation/session/draft/asset.jpg") != null)
            assertEquals(null, zip.getEntry("media_creation/session/record/out_1.mp4.part"))
        }
        session.deleteRecursively()
        manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
        assertFalse(session.exists())
        // Applying the staged settings would write to the real settings store of the app under test.
        assertTrue(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").delete())
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertEquals("image", File(session, "record/out_0.png").readText())
        assertEquals("asset", File(session, "draft/asset.jpg").readText())
        assertFalse(File(session, "record/out_1.mp4.part").exists())
    }

    @Test fun unsupportedSchemaIsRejectedBeforePublishing() = runBlocking {
        val future = File(directory, "future")
        withRoom(future) {
            it.openHelper.writableDatabase.version = 9999
        }
        val archive = File(directory, "future.zip")
        ZipOutputStream(archive.outputStream()).use { addFile(it, DatabaseBackup.ARCHIVE_DATABASE, future) }
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun plaintextExportCannotCarryCredentials() = runBlocking {
        val failure = runCatching {
            manager.createBackup(false, false, includeCredentials = true)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun encryptedArchiveRestoresDatabaseOnlyAfterVerifiedStaging() = runBlocking {
        val archive = manager.createBackup(true, false, "synthetic test password".toCharArray(), true)
        assertEquals("rkmbak", archive.extension)
        assertTrue(BackupCrypto.isEncrypted(archive))
        liveDatabase.openHelper.writableDatabase.execSQL("UPDATE backup_probe SET text = 'changed'")
        manager.stageRestore(archive, true, false, "synthetic test password".toCharArray())
        assertEquals("changed", probe(liveDatabase))
        assertTrue(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").delete())
        liveDatabase.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("live", probe(liveDatabase))
        assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("backup_decrypted_") })
    }

    @Test fun encryptedRestoreRejectsMissingOrWrongPasswordWithoutChangingLiveData() = runBlocking {
        val archive = manager.createBackup(true, false, "synthetic password".toCharArray())
        for (passphrase in listOf(null, "incorrect password".toCharArray())) {
            assertTrue(runCatching { manager.stageRestore(archive, true, false, passphrase) }.isFailure)
            assertEquals("live", probe(liveDatabase))
            assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("backup_decrypted_") })
        }
    }

    @Test fun manifestDescribesSettingsAndDatabaseAndRejectsCorruptionBeforeStaging() = runBlocking {
        val archive = manager.createBackup(true, false)
        ZipFile(archive).use { zip ->
            val manifest = requireNotNull(BackupManifest.verify(zip))
            assertEquals(setOf("settings.json", DatabaseBackup.ARCHIVE_DATABASE), manifest.map { it.path }.toSet())
        }
        val damaged = File(directory, "damaged.zip")
        ZipFile(archive).use { source ->
            ZipOutputStream(damaged.outputStream()).use { output ->
                for (entry in source.entries()) {
                    output.putNextEntry(ZipEntry(entry.name))
                    source.getInputStream(entry).use { input -> input.copyTo(output) }
                    // Still valid JSON and ZIP CRC, but no longer the manifest's declared settings bytes.
                    if (entry.name == "settings.json") output.write(' '.code)
                    output.closeEntry()
                }
            }
        }
        assertTrue(runCatching { manager.stageRestore(damaged, true, false) }.isFailure)
        assertEquals("live", probe(liveDatabase))
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
    }

    @Test fun automaticPassphraseVaultSurvivesRecreationWithoutStoringPlaintextOrExportingIt() = runBlocking {
        withPassphraseVault { store, alias ->
            val password = " synthetic recovery 口令 🔐 ".toCharArray()
            store.save(password)
            password.fill('\u0000')
            assertTrue(store.isConfigured())
            val reloaded = requireNotNull(BackupPassphraseStore(context, alias).read())
            assertEquals(" synthetic recovery 口令 🔐 ", String(reloaded))
            reloaded.fill('\u0000')
            val ciphertext = File(context.noBackupFilesDir, "backup-secret/passphrase.bin").readBytes()
            assertFalse(ciphertext.toString(Charsets.UTF_8).contains("synthetic recovery"))
            val backup = manager.createBackup(false, false)
            ZipFile(backup).use { zip -> assertFalse(zip.entries().asSequence().any { "backup-secret" in it.name }) }
            store.clear()
            assertFalse(store.isConfigured())
            assertEquals(null, store.read())
        }
    }

    @Test fun corruptOrLostLocalBackupKeyCannotYieldAPlaintextFallback() = runBlocking {
        withPassphraseVault { store, alias ->
            store.save("synthetic password".toCharArray())
            val vault = File(context.noBackupFilesDir, "backup-secret/passphrase.bin")
            val encrypted = vault.readBytes()
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            vault.writeBytes(encrypted)
            assertTrue(runCatching { store.read() }.isFailure)
            store.save("synthetic replacement password".toCharArray())
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            assertTrue(runCatching { store.read() }.isFailure)
            assertEquals("live", probe(liveDatabase))
        }
    }

    @Test fun excludingChatAttachmentsStillBacksUpAvatarsBackgroundsSkillsAndFonts() = runBlocking {
        val avatar = File(context.filesDir, "upload/synthetic-avatar.png").apply {
            parentFile!!.mkdirs()
            writeText("synthetic avatar pixels")
        }
        val unrelated = File(context.filesDir, "upload/unrelated-chat.bin").apply { writeBytes(ByteArray(1024 * 1024)) }
        val font = File(context.filesDir, "fonts/synthetic.ttf").apply {
            parentFile!!.mkdirs()
            writeText("synthetic font bytes")
        }
        val skill = File(context.filesDir, "skills/demo/SKILL.md").apply {
            parentFile!!.mkdirs()
            writeText("synthetic skill text")
        }
        val assistant = me.rerere.rikkahub.data.model.Assistant(
            name = "Synthetic backup assistant",
            systemPrompt = "Keep this synthetic persona text unchanged.",
            avatar = me.rerere.rikkahub.data.model.Avatar.Image(avatar.toURI().toString()),
            background = avatar.toURI().toString(),
        )
        settingsStore.update { current ->
            current.copy(
                assistants = listOf(assistant),
                assistantId = assistant.id,
                displaySetting = current.displaySetting.copy(userAvatar = assistant.avatar),
            )
        }
        val archive = manager.createBackup(false, false)
        ZipFile(archive).use { zip ->
            for (name in listOf("upload/synthetic-avatar.png", "fonts/synthetic.ttf", "skills/demo/SKILL.md")) {
                assertTrue(zip.getEntry(name) != null)
            }
            assertEquals(null, zip.getEntry("upload/unrelated-chat.bin"))
            assertEquals(null, zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE))
            BackupManifest.verify(zip)
        }
        assertTrue(avatar.delete())
        assertTrue(font.delete())
        assertTrue(skill.delete())
        manager.stageRestore(archive, false, false)
        val stagedSettings = File(context.noBackupFilesDir, "backup-restore/pending/settings.json")
        val restored = JsonInstant.decodeFromString<me.rerere.rikkahub.data.datastore.Settings>(
            stagedSettings.readText()
        )
        assertEquals(assistant.systemPrompt, restored.assistants.single().systemPrompt)
        assertEquals(assistant.id, restored.assistants.single().id)
        // Keep the real app's DataStore untouched; apply only the staged synthetic asset files here.
        assertTrue(stagedSettings.delete())
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertEquals("synthetic avatar pixels", avatar.readText())
        assertEquals("synthetic font bytes", font.readText())
        assertEquals("synthetic skill text", skill.readText())
        assertTrue(unrelated.isFile)
    }

    @Test fun crossDeviceAvatarReferencesAreRelocatedWithoutRewritingPromptTextOrIdentity() = runBlocking {
        val oldUri = "file:///data/user/10/com.synthetic.old/files/upload/avatar.png"
        val prompt = "Preserve this exact synthetic text: $oldUri"
        val assistant = me.rerere.rikkahub.data.model.Assistant(
            name = "Synthetic identity",
            systemPrompt = prompt,
            avatar = me.rerere.rikkahub.data.model.Avatar.Image(oldUri),
            background = oldUri,
        )
        val original = settingsStore.awaitLoaded()
        val settings = original.copy(
            assistants = listOf(assistant),
            assistantId = assistant.id,
            displaySetting = original.displaySetting.copy(userAvatar = assistant.avatar),
        )
        val settingsBytes = JsonInstant.encodeToString(settings).toByteArray()
        val avatarBytes = "synthetic cross-device pixels".toByteArray()
        val payloads = mapOf("settings.json" to settingsBytes, "upload/avatar.png" to avatarBytes)
        val archive = File(directory, "cross-device.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in payloads) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(BackupManifest.FILE_NAME))
            zip.write(
                BackupManifest.encode(
                    payloads.map { BackupManifest.entry(it.key, it.value) },
                    1234,
                    "synthetic"
                ).toByteArray()
            )
            zip.closeEntry()
        }
        manager.stageRestore(archive, false, false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json")
        val restored = JsonInstant.decodeFromString<me.rerere.rikkahub.data.datastore.Settings>(staged.readText())
        assertEquals(prompt, restored.assistants.single().systemPrompt)
        assertEquals(assistant.id, restored.assistants.single().id)
        assertEquals(assistant.name, restored.assistants.single().name)
        val newUri = (restored.assistants.single().avatar as me.rerere.rikkahub.data.model.Avatar.Image).url
        // Android aliases /data/user/0 to /data/data; compare canonical targets, not URI spellings.
        assertEquals(
            File(context.filesDir, "upload/avatar.png").canonicalPath,
            File(java.net.URI(newUri)).canonicalPath,
        )
        assertEquals(newUri, restored.assistants.single().background)
        assertEquals(newUri, (restored.displaySetting.userAvatar as me.rerere.rikkahub.data.model.Avatar.Image).url)
        assertEquals("synthetic cross-device pixels", File(
            context.noBackupFilesDir,
            "backup-restore/pending/payload/files/upload/avatar.png"
        ).readText())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun settingsOnlyArchiveCarriesNativeMemoryWithoutReplacingConversations() = runBlocking {
        val dao = liveDatabase.memoryDao()
        val archived = me.rerere.rikkahub.data.db.entity.MemoryFileEntity(
            "synthetic-assistant",
            "/profile.md",
            "synthetic archived memory",
            1234,
        )
        dao.upsertFile(archived)
        val archive = manager.createBackup(false, false, kind = BackupKind.SETTINGS)
        ZipFile(archive).use { zip ->
            assertEquals(null, zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE))
            assertTrue(zip.getEntry(MemoryBackupCodec.FILE_NAME) != null)
            assertEquals(BackupKind.SETTINGS, BackupManifest.verifyDetailed(zip).kind)
        }
        dao.upsertFile(archived.copy(content = "synthetic newer live memory"))
        manager.stageRestore(archive, false, false)
        PartialMemoryRestore.prepare(context)
        assertEquals("synthetic newer live memory", dao.getFile(archived.assistantId, archived.path)?.content)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        val stagedRoom = me.rerere.rikkahub.data.db.AppDatabaseFactory.create(context, staged.absolutePath)
        try {
            assertEquals(archived, stagedRoom.memoryDao().getFile(archived.assistantId, archived.path))
            assertEquals("live", probe(stagedRoom))
        } finally {
            stagedRoom.close()
        }
        // Preparing again must not replace the already-validated staged state.
        PartialMemoryRestore.prepare(context)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun chatsOnlyArchiveRetainsLatestLiveNativeMemoryAndOmitsSettings() = runBlocking {
        val dao = liveDatabase.memoryDao()
        val before = me.rerere.rikkahub.data.db.entity.MemoryFileEntity(
            "synthetic-assistant",
            "/notes.md",
            "synthetic old memory",
            12,
        )
        dao.upsertFile(before)
        val archive = manager.createBackup(true, false, kind = BackupKind.CHATS)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            assertEquals(null, zip.getEntry("settings.json"))
            assertEquals(null, zip.getEntry(MemoryBackupCodec.FILE_NAME))
            assertEquals(BackupKind.CHATS, BackupManifest.verifyDetailed(zip).kind)
        }
        val newest = before.copy(content = "synthetic latest live memory", updatedAt = 99)
        dao.upsertFile(newest)
        manager.stageRestore(archive, true, false)
        PartialMemoryRestore.prepare(context)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        val stagedRoom = me.rerere.rikkahub.data.db.AppDatabaseFactory.create(context, staged.absolutePath)
        try {
            assertEquals(newest, stagedRoom.memoryDao().getFile(newest.assistantId, newest.path))
            assertEquals("live", probe(stagedRoom))
        } finally {
            stagedRoom.close()
        }
        assertEquals(newest, dao.getFile(newest.assistantId, newest.path))
    }

    @Test fun realMemoryDaoSoftDeleteHidesRowsUntilRestoredOrPurged() = runBlocking {
        val dao = liveDatabase.memoryDao()
        val entity = me.rerere.rikkahub.data.db.entity.MemoryFileEntity(
            "synthetic-assistant",
            "/profile.md",
            "synthetic memory",
            1,
        )
        dao.upsertFile(entity)

        assertEquals(1, dao.markDeleted(entity.assistantId, entity.path, 99))
        assertTrue(dao.getFilesOfAssistant(entity.assistantId).isEmpty())
        assertEquals(null, dao.getFile(entity.assistantId, entity.path))
        assertEquals(99L, dao.getFileIncludingDeleted(entity.assistantId, entity.path)?.deletedAt)

        assertEquals(1, dao.markRestored(entity.assistantId, entity.path))
        assertEquals(entity, dao.getFile(entity.assistantId, entity.path))

        dao.markDeleted(entity.assistantId, entity.path, 100)
        dao.purgeDeletedFilesOfAssistant(entity.assistantId)
        assertEquals(null, dao.getFileIncludingDeleted(entity.assistantId, entity.path))
    }

    @Test fun passphraseVaultRecoversFromAtomicFileBackupAfterInterruptedWrite() = runBlocking {
        withPassphraseVault { store, _ ->
            val secret = "synthetic passphrase 123".toCharArray()
            try {
                store.save(secret)
                val base = File(context.noBackupFilesDir, "backup-secret/passphrase.bin")
                val backup = File(base.parentFile, base.name + ".bak")
                assertTrue(base.renameTo(backup))
                assertFalse(base.exists())

                assertTrue("Backup file must count as configured", store.isConfigured())
                val read = store.read()
                try {
                    org.junit.Assert.assertArrayEquals(secret, read)
                } finally {
                    read?.fill('\u0000')
                }
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    private suspend fun withPassphraseVault(block: suspend (BackupPassphraseStore, String) -> Unit) {
        val alias = "rikkahub-contract-test-${java.util.UUID.randomUUID()}"
        val store = BackupPassphraseStore(context, alias)
        try {
            block(store, alias)
        } finally {
            store.clear()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use {
            check(it.moveToFirst())
            it.getString(0)
        }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun <T> withRoom(file: File, block: (AppDatabase) -> T): T {
        val room = AppDatabaseFactory.create(context, file.path)
        return try {
            block(room)
        } finally {
            room.close()
        }
    }
}
