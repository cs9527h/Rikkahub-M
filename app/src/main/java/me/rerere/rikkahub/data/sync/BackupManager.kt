package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.model.Avatar
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        passphrase: CharArray? = null,
        includeCredentials: Boolean = false,
        kind: BackupKind = BackupKind.FULL,
    ): File = prepareBackup(includeDatabase, includeFiles, passphrase, includeCredentials, kind).file

    internal suspend fun prepareBackup(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        passphrase: CharArray? = null,
        includeCredentials: Boolean = false,
        kind: BackupKind = BackupKind.FULL,
    ): PreparedBackup = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            require(!includeCredentials || (passphrase != null && passphrase.isNotEmpty())) {
                "包含凭据的备份必须使用非空口令加密"
            }
            require(passphrase == null || passphrase.isNotEmpty()) { "备份口令不能为空" }
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val archive = File.createTempFile("backup_${kind.wireName}_${timestamp}_", ".zip", context.cacheDir)
            val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
            try {
                val settings = settingsStore.awaitLoaded()
                val backupSettings = kind != BackupKind.CHATS
                val backupDatabase = kind == BackupKind.CHATS || (kind == BackupKind.FULL && includeDatabase)
                val coreAssets = if (backupSettings) localCoreAssetFiles(settings) else emptyMap()
                val entries = mutableListOf<BackupManifestEntry>()
                var settingsJsonForFingerprint: String? = null
                ZipOutputStream(FileOutputStream(archive)).use { zip ->
                    if (backupSettings) {
                        zip.putNextEntry(ZipEntry("settings.json"))
                        val settingsJson = portableSettings(json.parseToJsonElement(
                            json.encodeSettings(settings, settingsStore.launchCountFlow.first())
                        ).jsonObject, includeCredentials).toString()
                        val settingsBytes = settingsJson.toByteArray(Charsets.UTF_8)
                        zip.write(settingsBytes)
                        zip.closeEntry()
                        entries += BackupManifest.entry("settings.json", settingsBytes)
                        settingsJsonForFingerprint = settingsJson
                        if (!backupDatabase) {
                            val memories = database.memoryDao().getAllFiles().map { memory ->
                                MemoryBackupRecord(memory.assistantId, memory.path, memory.content, memory.updatedAt)
                            }
                            val memoryBytes = MemoryBackupCodec.encode(memories).toByteArray(Charsets.UTF_8)
                            zip.putNextEntry(ZipEntry(MemoryBackupCodec.FILE_NAME))
                            zip.write(memoryBytes)
                            zip.closeEntry()
                            entries += BackupManifest.entry(MemoryBackupCodec.FILE_NAME, memoryBytes)
                        }
                    }
                    if (backupDatabase) {
                        val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                        DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                        entries += addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                    }
                    if (backupSettings && !includeFiles) {
                        for ((name, file) in coreAssets) {
                            if (name.substringBefore('/') !in CORE_ASSET_FOLDERS) entries += addFile(zip, file, name)
                        }
                    }
                    run {
                        val folders = when {
                            !backupSettings && includeFiles -> listOf(FileFolders.UPLOAD, FileFolders.MEDIA_CREATION)
                            !backupSettings -> emptyList()
                            includeFiles -> ATTACHMENT_FOLDERS
                            else -> CORE_ASSET_FOLDERS
                        }
                        for (folder in folders) {
                            val directory = File(context.filesDir, folder)
                            val files = if (folder in NESTED_ATTACHMENT_FOLDERS) {
                                directory.walkTopDown().asSequence()
                            } else {
                                directory.listFiles().orEmpty().asSequence()
                            }
                            for (file in files.filter { it.isFile }) {
                                currentCoroutineContext().ensureActive()
                                // A media result still being downloaded is incomplete and gets renamed when it finishes.
                                if (folder == FileFolders.MEDIA_CREATION &&
                                    file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                                ) {
                                    continue
                                }
                                val relative = file.relativeTo(directory).invariantSeparatorsPath
                                PendingRestore.resolveInside(directory, relative)
                                entries += addFile(zip, file, "$folder/$relative")
                            }
                        }
                    }
                    zip.putNextEntry(ZipEntry(BackupManifest.FILE_NAME))
                    zip.write(
                        BackupManifest.encode(
                            entries,
                            System.currentTimeMillis(),
                            me.rerere.rikkahub.BuildConfig.VERSION_NAME,
                            kind,
                        ).toByteArray(Charsets.UTF_8)
                    )
                    zip.closeEntry()
                }
                val fingerprint = BackupFingerprint.calculate(kind, entries, settingsJsonForFingerprint)
                if (passphrase == null) return@withLock PreparedBackup(archive, fingerprint, kind)
                val encrypted = File(
                    context.cacheDir,
                    "backup_${kind.wireName}_${timestamp}_${System.nanoTime()}.${BackupCrypto.EXTENSION}",
                )
                BackupCrypto.encrypt(archive, encrypted, passphrase)
                archive.delete()
                return@withLock PreparedBackup(encrypted, fingerprint, kind)
            } catch (e: Throwable) {
                archive.delete()
                throw e
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun stageRestore(
        archive: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        passphrase: CharArray? = null,
    ) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                var decrypted: File? = null
                try {
                    val readable = if (!BackupCrypto.isEncrypted(archive)) {
                        archive
                    } else {
                        requireNotNull(passphrase) { "该备份已加密，需要恢复口令" }
                        File.createTempFile("backup_decrypted_", ".zip", context.cacheDir).also { temp ->
                            decrypted = temp
                            BackupCrypto.decrypt(archive, temp, passphrase)
                        }
                    }
                    val restore = pendingRestore(context)
                    val staging = restore.createStagingDirectory()
                    try {
                        val payload = File(staging, "payload")
                        val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                        val stagedWal = File(stagedDatabase.path + "-wal")
                        val seen = mutableSetOf<String>()
                        var restoredEntries = 0
                        var restoredSettings: Settings? = null
                        var restoredKind = BackupKind.FULL
                        var restoredLaunchCount = 0
                        ZipFile(readable).use { zip ->
                            val coroutineContext = currentCoroutineContext()
                            val verified = BackupManifest.verifyDetailed(zip) { coroutineContext.ensureActive() }
                            restoredKind = verified.kind
                            when (verified.kind) {
                                BackupKind.SETTINGS -> require(
                                    zip.getEntry("settings.json") != null &&
                                        zip.getEntry(MemoryBackupCodec.FILE_NAME) != null &&
                                        zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) == null
                                ) { "设置备份的数据类型不完整或混入聊天数据库" }
                                BackupKind.CHATS -> require(
                                    zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null &&
                                        zip.getEntry("settings.json") == null &&
                                        zip.getEntry(MemoryBackupCodec.FILE_NAME) == null
                                ) { "聊天备份的数据类型不完整或混入设置数据" }
                                BackupKind.FULL -> require(
                                    zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) == null ||
                                        zip.getEntry(MemoryBackupCodec.FILE_NAME) == null
                                ) { "完整备份含有重复的记忆数据源" }
                            }
                            zip.getEntry(MemoryBackupCodec.FILE_NAME)?.let { entry ->
                                require(entry.size in 1..MemoryBackupCodec.MAX_BYTES) { "记忆备份超过安全上限" }
                            }
                            val originalSettings = zip.getEntry("settings.json")?.let { entry ->
                                require(entry.size in 1..MAX_SETTINGS_BYTES) { "设置备份大小无效或超过 64 MiB" }
                                val text = zip.getInputStream(
                                    entry
                                ).bufferedReader(Charsets.UTF_8).use { it.readText() }
                                val migrated = SettingsJsonMigrator.migrate(text)
                                restoredLaunchCount = json.launchCountOf(migrated)
                                json.decodeFromString<Settings>(migrated).also { require(!it.init) }
                            }
                            restoredSettings = originalSettings?.let(::remapManagedAssets)
                            val coreAssetPaths = originalSettings?.let(::coreAssetUris).orEmpty()
                                .mapNotNull { BackupFileUris.relativePath(it, ATTACHMENT_FOLDERS.toSet()) }.toSet()
                            for (entry in zip.entries()) {
                                currentCoroutineContext().ensureActive()
                                if (entry.isDirectory) continue
                                val target = when (entry.name) {
                                    "settings.json" -> File(staging, "settings.json")
                                    MemoryBackupCodec.FILE_NAME -> File(staging, MemoryBackupCodec.FILE_NAME)
                                    DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                    DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                    DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                    else -> if (isAttachment(entry.name) && (
                                            includeFiles || entry.name in coreAssetPaths ||
                                                entry.name.substringBefore('/') in CORE_ASSET_FOLDERS
                                            )) {
                                        PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                    } else {
                                        null
                                    }
                                } ?: continue
                                require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                    "Cannot create backup staging directory"
                                }
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(target).use { output ->
                                        input.copyTo(output)
                                        output.fd.sync()
                                    }
                                }
                                restoredEntries++
                            }
                        }
                        require(restoredEntries > 0) { "No selected data found in the backup" }
                        require(
                            !stagedWal.exists() || stagedDatabase.exists()
                        ) { "Backup WAL has no matching database" }
                        if (stagedDatabase.exists()) {
                            DatabaseBackup.normalize(context, stagedDatabase)
                            // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                            val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                            try {
                                DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                            } finally {
                                room.close()
                            }
                            DatabaseBackup.removeSidecars(stagedDatabase)
                        }

                        restoredSettings?.let { settings ->
                            // Preserve generated IDs and user-authored text; only managed asset references are relocated.
                            PendingRestore.writeDurably(
                                File(staging, "settings.json"),
                                json.encodeSettings(settings, restoredLaunchCount)
                            )
                        }
                        val memories = File(staging, MemoryBackupCodec.FILE_NAME)
                        if (memories.isFile) {
                            require(memories.length() <= MemoryBackupCodec.MAX_BYTES) { "记忆备份超过安全上限" }
                            MemoryBackupCodec.decode(memories.readText())
                        }
                        PendingRestore.writeDurably(
                            File(staging, PartialMemoryRestore.KIND_FILE),
                            restoredKind.wireName
                        )
                        currentCoroutineContext().ensureActive()
                        restore.publish(staging)
                    } finally {
                        staging.deleteRecursively()
                    }
                } finally {
                    decrypted?.delete()
                }
            }
        }

    private fun coreAssetUris(settings: Settings): List<String> = buildList {
        (settings.displaySetting.userAvatar as? Avatar.Image)?.let { add(it.url) }
        for (assistant in settings.assistants) {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url) }
            assistant.background?.let(::add)
        }
    }

    private fun localCoreAssetFiles(settings: Settings): Map<String, File> = buildMap {
        val root = context.filesDir.canonicalFile
        for (value in coreAssetUris(settings)) {
            if (!value.startsWith("file:", ignoreCase = true)) continue
            val relative = requireNotNull(BackupFileUris.relativePath(value, ATTACHMENT_FOLDERS.toSet())) {
                "头像或背景不在托管目录，请重新选择后备份"
            }
            require(isAttachment(relative)) { "头像或背景引用无效" }
            val file = File(java.net.URI(value)).canonicalFile
            require(file.path.startsWith(root.path + File.separator) && file.isFile) {
                "头像或背景文件缺失，请重新选择后备份"
            }
            put(relative, file)
        }
    }

    private fun remapManagedAssets(settings: Settings): Settings {
        fun relocate(value: String) = BackupFileUris.restoreUri(value, context.filesDir, ATTACHMENT_FOLDERS.toSet())
        fun avatar(value: Avatar): Avatar = if (value is Avatar.Image) value.copy(url = relocate(value.url)) else value
        return settings.copy(
            displaySetting = settings.displaySetting.copy(userAvatar = avatar(settings.displaySetting.userAvatar)),
            assistants = settings.assistants.map { assistant ->
                assistant.copy(avatar = avatar(assistant.avatar), background = assistant.background?.let(::relocate))
            },
        )
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private suspend fun addFile(zip: ZipOutputStream, file: File, name: String): BackupManifestEntry {
        zip.putNextEntry(ZipEntry(name))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                zip.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                size += count
            }
        }
        zip.closeEntry()
        return BackupManifestEntry(name, size, BackupManifest.hex(digest.digest()))
    }

    companion object {
        private const val MAX_SETTINGS_BYTES = 64L * 1024 * 1024
        private val CORE_ASSET_FOLDERS = listOf(FileFolders.SKILLS, FileFolders.FONTS)
        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION)

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION)

        // 启动次数不在 Settings 里，备份文件里仍放在 settings.json 的这个字段，和旧备份保持一致
        private const val LAUNCH_COUNT_KEY = "launchCount"

        // Apply only on archive export. Restore staging still accepts older archive formats.
        internal fun portableSettings(settings: JsonObject, includeCredentials: Boolean = false): JsonObject {
            val credentialFields = setOf(
                "apiKey", "accessKeyId", "secretAccessKey", "password", "proxyPassword",
                "privateKey", "passphrase", "credential", "oauth", "customHeaders", "headers",
            )
            fun clean(value: JsonElement): JsonElement = when (value) {
                is JsonArray -> JsonArray(value.map(::clean))
                is JsonObject -> JsonObject(value.filterKeys { it !in credentialFields }.mapValues { (key, child) ->
                    // Tool schemas and user-authored JSON are data, not credential settings.
                    if (key in setOf("inputSchema", "parameters", "customBodies")) child else clean(child)
                })
                else -> value
            }
            val connections = setOf(
                "webDavConfig", "s3Config", "uploadS3Config", "providers", "searchServices",
                "mediaGenerationProviders", "ttsProviders", "asrProviders", "networkSetting", "mcpServers",
            )
            if (includeCredentials) {
                // 仅加密备份携带凭据；明文 ZIP 仍按原规则剥离密钥。
                return JsonObject(settings.mapValues { (key, value) ->
                    if (key == "webServerEnabled") {
                        JsonPrimitive(false)
                    } else if (key == "webServerAccessPassword") {
                        JsonPrimitive("")
                    } else {
                        value
                    }
                })
            }
            return JsonObject(settings.mapValues { (key, value) ->
                when (key) {
                    "webServerEnabled" -> JsonPrimitive(false)
                    "webServerAccessPassword" -> JsonPrimitive("")
                    "assistants" -> JsonArray((value as JsonArray).map { JsonObject(it.jsonObject - "customHeaders") })
                    in connections -> clean(value)
                    else -> value
                }
            })
        }

        private fun Json.encodeSettings(settings: Settings, launchCount: Int): String {
            val fields = encodeToJsonElement(settings).jsonObject + (LAUNCH_COUNT_KEY to JsonPrimitive(launchCount))
            return encodeToString(JsonObject(fields))
        }

        // 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致
        private fun Json.launchCountOf(settingsJson: String): Int =
            parseToJsonElement(settingsJson).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.intOrNull ?: 0

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            try {
                PartialMemoryRestore.prepare(context)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException || error is RestoreFailedException) throw error
                val pending = File(context.noBackupFilesDir, "backup-restore/pending")
                if (!File(pending, "journal.json").exists() && !File(pending, "originals").exists()) {
                    pending.deleteRecursively()
                    throw RestoreFailedException(error)
                }
                throw error
            }
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(
                    context = context,
                    settings = json.decodeFromString<Settings>(settingsJson),
                    launchCount = json.launchCountOf(settingsJson),
                )
            }
        }
    }
}
