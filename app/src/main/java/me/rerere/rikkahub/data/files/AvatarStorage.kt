package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Avatar
import java.io.File
import java.net.URI
import java.util.UUID

/** Owns only proven avatars. Never infers old upload ownership from filenames or image dimensions. */
class AvatarStorage(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
    private val database: AppDatabase,
    private val json: Json,
    // null means runtime reference evidence is unavailable: keep files, rather than guess.
    private val runtimeReferences: () -> Set<String>? = { null },
) {
    private val mutex = Mutex()
    private var watcher: Job? = null
    private var previousPaths = emptySet<String>()
    private val cleanupCandidates = linkedSetOf<String>()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun start(scope: CoroutineScope): Job = watcher?.takeIf { it.isActive } ?: scope.launch(Dispatchers.IO) {
        settingsStore.settingsFlow.collect { settings ->
            if (!settings.init) attempt { synchronize() }
        }
    }.also { watcher = it }

    /** Copies a picker result on IO. It is protected as pending until settings actually persist it. */
    suspend fun save(uri: Uri): Avatar.Image {
        var created: Avatar.Image? = null
        try {
            return withContext(Dispatchers.IO) {
                require(uri.scheme == "content" || uri.scheme == "file") { "Expected a local avatar" }
                val image = mutex.withLock {
                    val target = copyToAvatar(uri)
                    val result = Avatar.Image(target.toUri().toString()).also { created = it }
                    pending.add("${FileFolders.AVATARS}/${target.name}")
                    result
                }
                filesManager.syncFolder(FileFolders.AVATARS)
                image
            }
        } catch (error: Throwable) {
            created?.let { image ->
                withContext(NonCancellable) { attempt { discardPending(image) } }
            }
            throw error
        }
    }

    /** A superseded/cancelled picker result was never committed; release only its own pending file. */
    suspend fun discardPending(image: Avatar.Image): Unit = withContext(Dispatchers.IO) {
        val path = AvatarReferences.relativePath(image.url) ?: return@withContext
        if (ownedLocalFile(image.url) == null) return@withContext
        mutex.withLock {
            pending.remove(path)
            cleanupCandidates.add(path)
            if (deleteIfUnusedLocked(path)) cleanupCandidates.remove(path)
        }
    }

    suspend fun awaitIdle(): Unit = synchronize()

    /** Call only after SettingsStore.update returned successfully (never from a flow collector alone). */
    suspend fun onSettingsSaved(old: Settings, saved: Settings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val savedPaths = ownedAvatarPaths(saved)
            pending.removeAll(savedPaths)
            cleanupCandidates.addAll(ownedAvatarPaths(old) - savedPaths)
        }
        attempt { synchronize() }
    }

    suspend fun synchronize(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val initial = settingsStore.awaitLoaded()
            val initialPaths = ownedAvatarPaths(initial)
            cleanupCandidates.addAll(previousPaths - initialPaths)
            // Copy first; only CAS the still-current, proven avatar references afterwards.
            for (path in initialPaths.filter { it.startsWith("${FileFolders.UPLOAD}/") }) {
                currentCoroutineContext().ensureActive()
                attempt {
                    val sources = avatarUrls(initial).filter {
                        AvatarReferences.relativePath(it) == path && ownedLocalFile(it) != null
                    }
                    val source = sources.firstOrNull { ownedLocalFile(it)?.isFile == true } ?: return@attempt
                    val copied = copyToAvatar(source.toUri())
                    val replacement = Avatar.Image(copied.toUri().toString())
                    var used = false
                    settingsStore.update { latest ->
                        fun replace(avatar: Avatar): Avatar {
                            if (avatar is Avatar.Image && avatar.url in sources) {
                                used = true
                                return replacement
                            }
                            return avatar
                        }
                        latest.copy(
                            displaySetting = latest.displaySetting.copy(
                                userAvatar = replace(latest.displaySetting.userAvatar)
                            ),
                            assistants = latest.assistants.map { it.copy(avatar = replace(it.avatar)) },
                        )
                    }
                    // The update has returned from its NonCancellable persist; only now consider old data.
                    cleanupCandidates.add(if (used) path else "${FileFolders.AVATARS}/${copied.name}")
                }
            }
            // A flow emission precedes persistence. An identity update is a real disk barrier, not a delay.
            var durable: Settings? = null
            settingsStore.update { it.also { value -> durable = value } }
            val paths = ownedAvatarPaths(checkNotNull(durable))
            pending.removeAll(paths)
            cleanupCandidates.addAll(initialPaths - paths)
            previousPaths = paths
            // The reserved directory is proven avatar provenance. Recover abandoned results after
            // process death, but never guess which unrelated legacy uploads once were avatars.
            val directory = File(context.filesDir.canonicalFile, FileFolders.AVATARS)
            if (directory.canonicalFile == directory.absoluteFile) {
                directory.listFiles()?.filter { it.isFile && !it.name.startsWith('.') }?.forEach { file ->
                    val path = "${FileFolders.AVATARS}/${file.name}"
                    if (path !in paths && path !in pending) cleanupCandidates.add(path)
                }
            }
            for (path in cleanupCandidates.toList()) {
                currentCoroutineContext().ensureActive()
                if (deleteIfUnusedLocked(path)) cleanupCandidates.remove(path)
            }
            filesManager.syncFolder(FileFolders.AVATARS)
        }
    }

    /** URI callers must prove the source actually belongs to this app, not just share a basename. */
    suspend fun deleteLocalUriIfUnused(value: String): Boolean = withContext(Dispatchers.IO) {
        if (ownedLocalFile(value) == null) return@withContext false
        deleteIfUnused(AvatarReferences.relativePath(value) ?: return@withContext false)
    }

    /** For file management as well as replacement/deletion; false always means retain the file. */
    suspend fun deleteIfUnused(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { deleteIfUnusedLocked(relativePath) }
    }

    private suspend fun deleteIfUnusedLocked(relativePath: String): Boolean = try {
        database.withTransaction { deleteWithReferenceFence(relativePath) }.also { deleted ->
            if (deleted) filesManager.syncFolder(relativePath.substringBefore('/'))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("AvatarStorage", "Retaining file after failed reference check", e)
        false
    }

    private suspend fun deleteWithReferenceFence(relativePath: String): Boolean {
        val parts = relativePath.split('/')
        if (parts.size != 2 || parts[0] !in setOf(FileFolders.UPLOAD, FileFolders.AVATARS) ||
            parts[1].isBlank() || parts[1] == "." || parts[1] == ".." ||
            relativePath.any { it == '\\' || it.isISOControl() } || relativePath in pending
        ) {
            return false
        }
        val root = context.filesDir.canonicalFile
        val file = File(root, relativePath)
        // Never traverse a symlink, including a symlinked avatars/upload directory.
        if (file.canonicalFile != file.absoluteFile || !file.path.startsWith(root.path + File.separator)) return false
        var durable: Settings? = null
        settingsStore.update { it.also { value -> durable = value } }
        val snapshot = checkNotNull(durable)
        if (AvatarReferences.settingsContainReference(snapshot, json, relativePath)) return false
        val encodedName = URI(null, null, "/${parts[1]}", null).toASCIIString().removePrefix("/")
        if (database.fileReferenceDao().messageReferencesFile(parts[1], encodedName)) return false
        val runtime = runtimeReferences() ?: return false
        fun runtimeUsesFile(values: Set<String>): Boolean = values.any {
            AvatarReferences.relativePath(it) == relativePath || it.contains(relativePath) ||
                it.contains(parts[1]) || it.contains(encodedName, ignoreCase = true)
        }
        if (runtimeUsesFile(runtime)) return false
        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        var deleted = false
        // Atomic with respect to *all* SettingsStore writers. New state after the disk barrier => retain.
        settingsStore.update { latest ->
            if (latest === snapshot && relativePath !in pending &&
                runtimeReferences()?.let { !runtimeUsesFile(it) } == true
            ) {
                coroutineContext.ensureActive()
                deleted = !file.exists() || (file.isFile && file.delete())
            }
            latest
        }
        return deleted
    }

    private fun avatarUrls(settings: Settings): List<String> = buildList {
        (settings.displaySetting.userAvatar as? Avatar.Image)?.let { add(it.url) }
        settings.assistants.forEach { (it.avatar as? Avatar.Image)?.let { avatar -> add(avatar.url) } }
    }

    private fun ownedAvatarPaths(settings: Settings): Set<String> = avatarUrls(settings).mapNotNull { value ->
        if (ownedLocalFile(value) != null) AvatarReferences.relativePath(value) else null
    }.toSet()

    private fun ownedLocalFile(value: String): File? = runCatching {
        val relative = AvatarReferences.relativePath(value) ?: return null
        val root = context.filesDir.canonicalFile
        val actual = File(URI(value))
        val expected = File(root, relative)
        expected.takeIf { actual.canonicalFile == expected && expected.canonicalFile == expected }
    }.getOrNull()

    private suspend fun copyToAvatar(uri: Uri): File {
        currentCoroutineContext().ensureActive()
        val root = context.filesDir.canonicalFile
        val directory = File(root, FileFolders.AVATARS)
        check(directory.canonicalFile == directory) { "Avatar directory is not local" }
        check(directory.isDirectory || directory.mkdirs()) { "Failed to create avatar directory" }
        val name = FileUtils.buildUuidFileName(
            filesManager.getFileNameFromUri(uri) ?: "avatar.png",
            filesManager.getFileMimeType(uri),
        )
        val target = File(directory, name)
        val temporary = File(directory, ".${UUID.randomUUID()}.partial")
        try {
            val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "Avatar source missing" }
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    currentCoroutineContext().ensureActive()
                    output.fd.sync()
                }
            }
            check(temporary.length() > 0) { "Avatar source empty" }
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(target)) { "Failed to publish avatar" }
            return target
        } finally {
            temporary.delete()
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("AvatarStorage", "Avatar operation retained original files", e)
        }
    }
}
