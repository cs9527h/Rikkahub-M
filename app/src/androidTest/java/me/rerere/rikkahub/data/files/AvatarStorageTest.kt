package me.rerere.rikkahub.data.files

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.sync.BackupManager
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
import java.util.zip.ZipFile

/** Real file IO, compiled Room queries and DataStore persistence barriers; no mocked file deletion. */
@RunWith(AndroidJUnit4::class)
class AvatarStorageTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var scope: AppScope
    private lateinit var store: SettingsStore
    private lateinit var preferences: GatedPreferences
    private lateinit var storage: AvatarStorage
    private lateinit var filesManager: FilesManager

    @Volatile private var runtime = emptySet<String>()

    @Before fun setUp() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(base.cacheDir.toPath(), "avatar-storage-").toFile()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
        }
        scope = AppScope()
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        preferences = GatedPreferences(PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "fixture.preferences_pb") }
        ))
        store = SettingsStore(preferences, scope)
        withTimeout(10_000) { store.awaitLoaded() }
        filesManager = FilesManager(context, FilesRepository(database.managedFileDao()), scope)
        storage = AvatarStorage(
            context,
            store,
            filesManager,
            database,
            JsonInstant,
            runtimeReferences = { runtime }
        )
    }

    @After fun tearDown() {
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
        database.close()
        directory.deleteRecursively()
    }

    @Test fun migrationCopiesSharedProvenAvatarButKeepsBackgroundAndUnprovenUploads() = runBlocking {
        val source = file("upload/shared.png")
        val orphan = file("upload/unknown.png")
        val assistant =
            Assistant(avatar = Avatar.Image(source.toUri().toString()), background = source.toUri().toString())
        store.update {
            it.copy(
                displaySetting = it.displaySetting.copy(userAvatar = assistant.avatar),
                assistants = listOf(assistant),
            )
        }
        storage.synchronize()
        val current = store.settingsFlow.value
        val image = current.displaySetting.userAvatar as Avatar.Image
        assertEquals(image, current.assistants.single().avatar)
        assertTrue(AvatarReferences.relativePath(image.url)!!.startsWith("avatars/"))
        assertEquals("fixture image", File(java.net.URI(image.url)).readText())
        assertEquals(source.toUri().toString(), current.assistants.single().background)
        assertTrue(source.isFile)
        assertTrue(orphan.isFile)
        assertTrue(preferences.data.first().asMap().values.any { it.toString().contains("avatars/") })
        assertFalse(storage.deleteIfUnused("upload/shared.png"))
    }

    @Test fun roomUnselectedBranchAndRuntimeQueueProtectOriginalUntilBothAreGone() = runBlocking {
        val source = file("upload/queued.png")
        val url = source.toUri().toString()
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = Avatar.Image(url))) }
        database.conversationDao().insert(ConversationEntity("c", "a", "", "[]", 0, 0, "[]", false))
        database.messageNodeDao().insert(MessageNodeEntity(
            "n",
            "c",
            0,
            """[{"parts":[]},{"parts":[{"output":[{"url":"$url"}]}]}]""",
            0
        ))
        runtime = setOf(url)
        storage.synchronize()
        assertTrue(source.exists())
        assertTrue(database.fileReferenceDao().messageReferencesFile("queued.png", "queued.png"))
        database.messageNodeDao().deleteById("n")
        storage.synchronize()
        assertTrue(source.exists())
        runtime = emptySet()
        storage.synchronize()
        assertFalse(source.exists())
    }

    @Test fun emojiThenRemoteReplacementDeletesOnlyUnusedLocalImages() = runBlocking {
        val original = file("avatars/original.png")
        store.update {
            it.copy(
                displaySetting = it.displaySetting.copy(userAvatar = Avatar.Image(original.toUri().toString()))
            )
        }
        storage.synchronize()
        val old = store.settingsFlow.value
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = Avatar.Emoji("🙂"))) }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertFalse(original.exists())
        val remote = Avatar.Image("https://example.invalid/avatar.png")
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = remote)) }
        storage.synchronize()
        assertEquals(remote, store.settingsFlow.value.displaySetting.userAvatar)
        assertTrue(File(context.filesDir, "avatars").listFiles().orEmpty().isEmpty())
    }

    @Test fun neverDeletesWhileNewStateIsOnlyEmittedButPersistenceIsBlocked() = runBlocking {
        val original = file("avatars/durable.png")
        store.update {
            it.copy(
                displaySetting = it.displaySetting.copy(userAvatar = Avatar.Image(original.toUri().toString()))
            )
        }
        storage.synchronize()
        val gate = preferences.blockNextWrite()
        val update =
            launch { store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = Avatar.Dummy)) } }
        withTimeout(10_000) { preferences.entered.await() }
        assertEquals(Avatar.Dummy, store.settingsFlow.value.displaySetting.userAvatar)
        val cleanup = launch { storage.synchronize() }
        kotlinx.coroutines.yield()
        assertTrue(original.exists())
        gate.complete(Unit)
        withTimeout(10_000) {
            update.join()
            cleanup.join()
        }
        assertFalse(original.exists())
        assertFalse(preferences.data.first().asMap().values.any { it.toString().contains("durable.png") })
    }

    @Test fun sharedAssistantImageSurvivesCloneRemovalAndFinalOwnerChangeCleansIt() = runBlocking {
        val image = file("avatars/shared.png")
        val first = Assistant(avatar = Avatar.Image(image.toUri().toString()))
        val second = first.copy(id = kotlin.uuid.Uuid.random())
        store.update { it.copy(assistants = listOf(first, second)) }
        storage.synchronize()
        var old = store.settingsFlow.value
        store.update { it.copy(assistants = listOf(second)) }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertTrue(image.exists())
        old = store.settingsFlow.value
        store.update { it.copy(assistants = listOf(second.copy(avatar = Avatar.Dummy))) }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertFalse(image.exists())
    }

    @Test fun encodedFileNamesAndFavoriteSnapshotsRemainProtected() = runBlocking {
        val image = file("avatars/a b中文#?.png")
        val relative = "avatars/${image.name}"
        val url = image.toURI().toASCIIString()
        database.favoriteDao().upsert(me.rerere.rikkahub.data.db.entity.FavoriteEntity(
            "f",
            "message",
            "fixture",
            "{}",
            """{"url":"$url"}""",
            null,
            0,
            0,
        ))
        assertFalse(storage.deleteIfUnused(relative))
        assertTrue(image.exists())
        database.favoriteDao().deleteById("f")
        runtime = setOf(url)
        assertFalse(storage.deleteIfUnused(relative))
        runtime = emptySet()
        assertTrue(storage.deleteIfUnused(relative))
        assertFalse(image.exists())
    }

    @Test fun foreignFileUriMustNeverClaimOrDeleteAnUnrelatedUploadWithSameName() = runBlocking {
        val unrelated = file("upload/foreign.png")
        val foreign = Avatar.Image("file:///different-app/files/upload/foreign.png")
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = foreign)) }
        storage.synchronize()
        assertEquals(foreign, store.settingsFlow.value.displaySetting.userAvatar)
        val old = store.settingsFlow.value
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = Avatar.Dummy)) }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertFalse(storage.deleteLocalUriIfUnused(foreign.url))
        assertTrue(unrelated.exists())
    }

    @Test fun missingMigrationSourceAndUnknownRuntimeEvidenceRetainOriginalState() = runBlocking {
        val missing = Avatar.Image(File(context.filesDir, "upload/missing.png").toUri().toString())
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = missing)) }
        storage.synchronize()
        assertEquals(missing, store.settingsFlow.value.displaySetting.userAvatar)
        val retained = file("avatars/retained.png")
        val unknown = AvatarStorage(
            context,
            store,
            FilesManager(context, FilesRepository(database.managedFileDao()), scope),
            database,
            JsonInstant
        )
        assertFalse(unknown.deleteIfUnused("avatars/retained.png"))
        assertTrue(retained.exists())
    }

    @Test fun avatarFilesAreIncludedAndRestagedWithSettingsEvenWithoutChatAttachments() = runBlocking {
        val image = file("avatars/backup.png")
        file("avatars/.incomplete.partial")
        store.update {
            it.copy(
                displaySetting = it.displaySetting.copy(userAvatar = Avatar.Image(image.toUri().toString()))
            )
        }
        val backup = BackupManager(context, database, store, JsonInstant)
        val archive = backup.createBackup(includeDatabase = false, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("avatars/backup.png") != null)
            assertEquals(null, zip.getEntry("avatars/.incomplete.partial"))
        }
        backup.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/files/avatars/backup.png")
        assertEquals("fixture image", staged.readText())
        val restored = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
        assertTrue(restored.contains("avatars/backup.png"))
        assertFalse(storage.deleteIfUnused("avatars/../datastore/private"))
    }

    @Test fun supersededPickerResultReleasesOnlyItsOwnPendingFile() = runBlocking {
        val source = file("upload/picker.png")
        val first = storage.save(source.toUri())
        val second = storage.save(source.toUri())
        storage.discardPending(first)
        assertFalse(File(java.net.URI(first.url)).exists())
        assertTrue(File(java.net.URI(second.url)).isFile)
        val old = store.settingsFlow.value
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = second)) }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertTrue(File(java.net.URI(second.url)).isFile)
        assertTrue(source.isFile)
    }

    @Test fun freshServiceReclaimsAbandonedAvatarButNeverGuessesLegacyUploadOwnership() = runBlocking {
        val legacy = file("upload/unknown-before-restart.png")
        val abandoned = storage.save(legacy.toUri())
        val restarted = AvatarStorage(
            context,
            store,
            FilesManager(context, FilesRepository(database.managedFileDao()), scope),
            database,
            JsonInstant,
            runtimeReferences = { runtime }
        )
        restarted.synchronize()
        assertFalse(File(java.net.URI(abandoned.url)).exists())
        assertTrue(legacy.isFile)
    }

    @Test fun settingsReferenceAddedBeforeFinalFenceRetainsFileAndIndex() = runBlocking {
        val original = file("avatars/late-settings.png")
        filesManager.syncFolder(FileFolders.AVATARS)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = storageBeforeFinalCheck {
            entered.complete(Unit)
            release.await()
        }
        var deleted = true
        val cleanup = launch { deleted = gated.deleteIfUnused("avatars/late-settings.png") }
        withTimeout(10_000) { entered.await() }
        store.update {
            it.copy(assistants = listOf(Assistant(background = original.toUri().toString())))
        }
        release.complete(Unit)
        withTimeout(10_000) { cleanup.join() }
        assertFalse(deleted)
        assertTrue(original.isFile)
        assertTrue(filesManager.getByRelativePath("avatars/late-settings.png") != null)
        assertTrue(quarantineFiles().isEmpty())
    }

    @Test fun runtimeReferenceAddedBeforeFinalFenceRetainsFileAndIndex() = runBlocking {
        val original = file("avatars/late-runtime.png")
        filesManager.syncFolder(FileFolders.AVATARS)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = storageBeforeFinalCheck {
            entered.complete(Unit)
            release.await()
        }
        var deleted = true
        val cleanup = launch { deleted = gated.deleteIfUnused("avatars/late-runtime.png") }
        withTimeout(10_000) { entered.await() }
        runtime = setOf(original.toURI().toASCIIString())
        release.complete(Unit)
        withTimeout(10_000) { cleanup.join() }
        assertFalse(deleted)
        assertTrue(original.isFile)
        assertTrue(filesManager.getByRelativePath("avatars/late-runtime.png") != null)
        assertTrue(quarantineFiles().isEmpty())
    }

    @Test fun finalFenceRetainsFileWhenNewReferencePersistenceIsStillSuspended() = runBlocking {
        val original = file("avatars/late-durable.png")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = storageBeforeFinalCheck {
            entered.complete(Unit)
            release.await()
        }
        var deleted = true
        val cleanup = launch { deleted = gated.deleteIfUnused("avatars/late-durable.png") }
        withTimeout(10_000) { entered.await() }
        val persistGate = preferences.blockNextWrite()
        val update = launch {
            store.update { it.copy(assistants = listOf(Assistant(background = original.toUri().toString()))) }
        }
        withTimeout(10_000) { preferences.entered.await() }
        release.complete(Unit)
        kotlinx.coroutines.yield()
        assertTrue(original.isFile)
        assertTrue(quarantineFiles().isEmpty())
        persistGate.complete(Unit)
        withTimeout(10_000) {
            update.join()
            cleanup.join()
        }
        assertFalse(deleted)
        assertTrue(original.isFile)
        assertTrue(preferences.data.first().asMap().values.any { it.toString().contains("late-durable.png") })
    }

    @Test fun realRenameFailureRetainsOriginalAndItsIndex() = runBlocking {
        val original = file("avatars/rename-failure.png")
        filesManager.syncFolder(FileFolders.AVATARS)
        val gated = storageBeforeFinalCheck {
            // Preparation has already validated an empty directory. Replace it with a regular file
            // to make the actual rename syscall fail, rather than substituting a fake delete callback.
            val quarantine = File(context.noBackupFilesDir, AvatarFileQuarantine.DIRECTORY)
            assertTrue(quarantine.delete())
            quarantine.writeText("block target directory")
        }
        assertFalse(gated.deleteIfUnused("avatars/rename-failure.png"))
        assertEquals("fixture image", original.readText())
        assertTrue(filesManager.getByRelativePath("avatars/rename-failure.png") != null)
    }

    @Test fun successfulRetirementReleasesQuarantineAndOnlyItsOwnIndexWithoutFolderRescan() = runBlocking {
        val original = file("avatars/retired.png")
        val stale = file("avatars/stale-index.png")
        filesManager.syncFolder(FileFolders.AVATARS)
        assertTrue(stale.delete())
        val legacy = file("upload/unrelated-legacy.png")
        val guarded = file("avatars/not-indexed.png")
        store.update { it.copy(assistants = listOf(Assistant(background = guarded.toUri().toString()))) }
        storage.synchronize()
        assertFalse(original.exists())
        assertTrue(quarantineFiles().isEmpty())
        assertEquals(null, filesManager.getByRelativePath("avatars/retired.png"))
        // A folder rescan would remove the stale row and/or register the protected unindexed file.
        assertTrue(filesManager.getByRelativePath("avatars/stale-index.png") != null)
        assertEquals(null, filesManager.getByRelativePath("avatars/not-indexed.png"))
        assertEquals(null, filesManager.getByRelativePath("upload/unrelated-legacy.png"))
        assertTrue(legacy.isFile)
        assertTrue(guarded.isFile)
    }

    @Test fun backgroundAndPresetRemovalRetryRetainedCandidateWithoutAvatarSignatureChange() = runBlocking {
        val original = file("upload/background-owner.png")
        store.update {
            it.copy(assistants = listOf(Assistant(
                avatar = Avatar.Image(original.toUri().toString()),
                background = original.toUri().toString(),
                presetMessages = listOf(UIMessage.user(original.toUri().toString())),
            )))
        }
        storage.synchronize()
        assertTrue(original.isFile)
        var old = store.settingsFlow.value
        store.update { it.copy(assistants = it.assistants.map { assistant -> assistant.copy(background = null) }) }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertTrue(original.isFile)
        old = store.settingsFlow.value
        store.update {
            it.copy(assistants = it.assistants.map { assistant -> assistant.copy(presetMessages = emptyList()) })
        }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertFalse(original.exists())
    }

    @Test fun unrelatedSettingsSaveWithoutCandidateOrPendingDoesNotSweep() = runBlocking {
        // synchronize() would discover and retire this file; onSettingsSaved should do no work here.
        val abandoned = file("avatars/not-a-settings-change.png")
        val old = store.settingsFlow.value
        store.update { it.copy(displaySetting = it.displaySetting.copy(userAvatar = Avatar.Emoji("🙂"))) }
        storage.onSettingsSaved(old, store.settingsFlow.value)
        assertTrue(abandoned.isFile)
    }

    @Test fun processRestartSweepsOnlyPrivateQuarantineArtifacts() = runBlocking {
        val original = file("avatars/crash-orphan.png")
        val quarantine = AvatarFileQuarantine(context.filesDir, context.noBackupFilesDir)
        val move = checkNotNull(quarantine.prepare("avatars/crash-orphan.png"))
        assertTrue(move.retire())
        assertFalse(original.exists())
        assertEquals(1, quarantineFiles().size)
        val legacy = file("upload/never-owned.png")
        storage.synchronize()
        assertTrue(quarantineFiles().isEmpty())
        assertTrue(legacy.isFile)
    }

    @Test fun newAndImportedAssistantsPersistCroppedAvatarAfterDelayedCopy() = runBlocking {
        val imported = Assistant(
            name = "Imported role card fixture",
            systemPrompt = "Preserve imported personality",
            avatar = Avatar.Image(file("upload/role-card.png").toUri().toString()),
        )
        val created = Assistant(name = "New assistant fixture", systemPrompt = "Preserve new personality")
        store.update { it.copy(assistants = listOf(imported, created)) }
        storage.synchronize()
        val png = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aXXsAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT,
        )
        for (target in listOf(imported, created)) {
            val cropped = File(directory, "crop-${target.id}.png")
            val entered = CompletableDeferred<Unit>()
            val allowCopy = CompletableDeferred<Unit>()
            val before = store.settingsFlow.value
            val rendered = before.assistants.single { it.id == target.id }
            var saved: Avatar.Image? = null
            val delivery = launch {
                me.rerere.rikkahub.ui.components.ai.deliverCroppedImage(
                    file = cropped,
                    write = { it.writeBytes(png) },
                    consume = { source ->
                        entered.complete(Unit)
                        allowCopy.await()
                        val image = storage.save(source.toUri())
                        store.update { latest ->
                            val current = latest.assistants.single { it.id == target.id }
                            val merged = checkNotNull(
                                me.rerere.rikkahub.ui.pages.assistant.detail.mergeAssistantDetailUpdate(
                                    target.id,
                                    rendered,
                                    rendered.copy(avatar = image),
                                    current,
                                )
                            )
                            latest.copy(assistants = latest.assistants.map {
                                if (it.id == target.id) merged else it
                            })
                        }
                        storage.onSettingsSaved(before, store.settingsFlow.value)
                        saved = image
                    },
                )
            }
            withTimeout(10_000) { entered.await() }
            assertTrue("Crop must survive delayed IO", cropped.isFile)
            allowCopy.complete(Unit)
            withTimeout(10_000) { delivery.join() }
            val current = store.settingsFlow.value.assistants.single { it.id == target.id }
            val image = checkNotNull(saved)
            assertEquals(image, current.avatar)
            assertEquals(target.name, current.name)
            assertEquals(target.systemPrompt, current.systemPrompt)
            assertEquals(
                before.assistants.single { it.id != target.id },
                store.settingsFlow.value.assistants.single { it.id != target.id },
            )
            assertFalse(cropped.exists())
            val owned = File(java.net.URI(image.url))
            org.junit.Assert.assertArrayEquals(png, owned.readBytes())
            storage.synchronize()
            assertTrue(owned.isFile)
            assertTrue(preferences.data.first().asMap().values.any { it.toString().contains(owned.name) })
        }
    }

    private fun storageBeforeFinalCheck(block: suspend () -> Unit) = AvatarStorage(
        context,
        store,
        filesManager,
        database,
        JsonInstant,
        runtimeReferences = { runtime },
        beforeFinalReferenceCheck = block,
    )

    private fun quarantineFiles(): List<File> =
        File(context.noBackupFilesDir, AvatarFileQuarantine.DIRECTORY).listFiles().orEmpty().toList()

    private fun file(relative: String): File = File(context.filesDir, relative).apply {
        parentFile!!.mkdirs()
        writeText("fixture image")
    }

    private class GatedPreferences(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        override val data = delegate.data
        var entered = CompletableDeferred<Unit>()
        private var gate: CompletableDeferred<Unit>? = null
        fun blockNextWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also {
            entered = CompletableDeferred()
            gate = it
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            gate?.let { pending ->
                gate = null
                entered.complete(Unit)
                pending.await()
            }
            return delegate.updateData(transform)
        }
    }
}
