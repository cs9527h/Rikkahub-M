package me.rerere.rikkahub.data.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Copies settings-referenced assets before settings export can suspend or live files can be retired. */
internal object BackupAssetSnapshots {
    // Match the archive's per-entry/total expansion limits, without loading an asset into memory.
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024

    fun capture(
        assets: Map<String, File>,
        filesRoot: File,
        snapshotRoot: File,
        onBlock: () -> Unit = {},
    ): Map<String, File> {
        val liveRoot = filesRoot.canonicalFile
        val privateRoot = snapshotRoot.canonicalFile
        require(privateRoot != liveRoot && !privateRoot.path.startsWith(liveRoot.path + File.separator)) {
            "Backup asset snapshots must be outside managed files"
        }
        check(privateRoot.isDirectory || privateRoot.mkdirs()) { "Cannot create backup asset snapshots" }
        var total = 0L
        return buildMap {
            for ((name, source) in assets) {
                onBlock()
                val expectedSource = resolveInside(liveRoot, name)
                require(source.canonicalFile == expectedSource) { "Backup asset path does not match its entry: $name" }
                val before = attributes(expectedSource)
                require(before.isRegularFile && before.size() in 0..MAX_ENTRY_BYTES) {
                    "Backup asset is missing, changed or too large: $name"
                }
                require(before.size() <= MAX_TOTAL_BYTES - total) { "Backup asset snapshots exceed size limit" }
                val available = privateRoot.usableSpace
                require(available <= 0 || BackupSpace.fits(before.size(), available)) {
                    "Insufficient space for backup asset snapshot: $name"
                }
                val target = resolveInside(privateRoot, name)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                    "Cannot create backup asset snapshot directory"
                }
                check(!target.exists()) { "Duplicate backup asset snapshot: $name" }
                try {
                    // Do not follow a leaf symlink swapped in between validation and opening the source.
                    onBlock()
                    var copied = 0L
                    Files.newByteChannel(expectedSource.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                        Channels.newInputStream(channel).use { input ->
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    onBlock()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    copied += read
                                    require(copied <= before.size()) { "Backup asset changed during snapshot: $name" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    onBlock()
                    val after = attributes(expectedSource)
                    require(
                        expectedSource == resolveInside(liveRoot, name) &&
                            after.isRegularFile && copied == before.size() &&
                            after.size() == before.size() && after.lastModifiedTime() == before.lastModifiedTime() &&
                            after.fileKey() == before.fileKey()
                    ) { "Backup asset changed during snapshot: $name" }
                    total += copied
                    put(name, target)
                } catch (error: Throwable) {
                    target.delete()
                    throw error
                }
            }
        }
    }

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun resolveInside(root: File, relative: String): File {
        require(
            relative.isNotBlank() && !relative.startsWith('/') && '\\' !in relative &&
                relative.split('/').none { it.isEmpty() || it == "." || it == ".." }
        ) { "Invalid backup asset path: $relative" }
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Backup asset is outside its directory" }
        return target
    }
}
