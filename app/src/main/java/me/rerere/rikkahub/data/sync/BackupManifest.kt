package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal data class BackupManifestEntry(val path: String, val size: Long, val sha256: String)
internal data class VerifiedBackupManifest(val entries: List<BackupManifestEntry>?, val kind: BackupKind)

/**
 * Versioned, streaming archive integrity checks. SHA-256 detects corruption; it is not
 * authentication for a plaintext ZIP. Only the outer AES-GCM envelope authenticates it.
 * Legacy ZIPs remain readable, with path/duplicate/size/CRC checks applied before staging.
 */
internal object BackupManifest {
    const val FILE_NAME = "manifest.json"
    const val FORMAT_VERSION = 1
    private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024
    private val hashPattern = Regex("[a-f0-9]{64}")

    fun entry(path: String, bytes: ByteArray) = BackupManifestEntry(path, bytes.size.toLong(), sha256(bytes))

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun encode(
        entries: List<BackupManifestEntry>,
        createdAt: Long,
        appVersion: String,
        kind: BackupKind = BackupKind.FULL,
    ): String {
        validateEntries(entries)
        require(createdAt >= 0 && appVersion.isNotBlank()) { "备份版本信息无效" }
        return buildJsonObject {
            put("formatVersion", FORMAT_VERSION)
            put("createdAt", createdAt)
            put("appVersion", appVersion)
            put("kind", kind.wireName)
            put("entries", buildJsonArray {
                for (entry in entries) {
                    add(buildJsonObject {
                        put("path", entry.path)
                        put("size", entry.size)
                        put("sha256", entry.sha256)
                    })
                }
            })
        }.toString()
    }

    /** Returns null for an old ZIP without a manifest, never an unchecked success. */
    fun verify(zip: ZipFile, onBlock: () -> Unit = {}): List<BackupManifestEntry>? =
        verifyDetailed(zip, onBlock).entries

    fun verifyDetailed(zip: ZipFile, onBlock: () -> Unit = {}): VerifiedBackupManifest {
        var kind = BackupKind.FULL
        val files = zip.entries().asSequence().toList()
        require(files.size <= MAX_ENTRIES + 1) { "备份文件数量超过安全上限" }
        val names = mutableSetOf<String>()
        for (file in files) {
            validatePath(file.name.removeSuffix("/"))
            require(names.add(file.name)) { "备份包含重复文件" }
        }
        val manifestFile = files.singleOrNull { it.name == FILE_NAME && !it.isDirectory }
        val expected = manifestFile?.let { file ->
            require(file.size in 1..MAX_MANIFEST_BYTES.toLong()) { "备份清单大小无效" }
            val text = zip.getInputStream(file).use { readManifest(it, file.size, file.crc, onBlock) }
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["formatVersion"]?.jsonPrimitive?.int == FORMAT_VERSION) { "不支持的备份版本" }
            require(root["createdAt"]?.jsonPrimitive?.long?.let { it >= 0 } == true) { "备份时间信息无效" }
            require(root["appVersion"]?.jsonPrimitive?.content?.isNotBlank() == true) { "备份应用版本缺失" }
            kind = BackupKind.fromWireName(root["kind"]?.jsonPrimitive?.content ?: "full")
            val entries = (root["entries"] as? JsonArray ?: error("备份清单缺少文件列表")).map { element ->
                val item = element.jsonObject
                BackupManifestEntry(
                    item.getValue("path").jsonPrimitive.content,
                    item.getValue("size").jsonPrimitive.long,
                    item.getValue("sha256").jsonPrimitive.content,
                )
            }
            validateEntries(entries)
            entries.associateBy { it.path }
        }
        val payloads = files.filter { !it.isDirectory && it.name != FILE_NAME }
        if (expected != null) {
            require(payloads.map { it.name }.toSet() == expected.keys) { "备份文件与清单不一致" }
        }
        var totalBytes = 0L
        for (file in payloads) {
            require(file.size in 0..MAX_ENTRY_BYTES) { "备份文件大小超过安全上限" }
            val expectedEntry = expected?.getValue(file.name)
            if (expectedEntry != null) require(file.size == expectedEntry.size) { "备份文件长度不匹配" }
            val digest = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            var size = 0L
            zip.getInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    onBlock()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    totalBytes += count
                    require(size <= file.size && totalBytes <= MAX_TOTAL_BYTES) { "备份展开大小超过安全上限" }
                    digest.update(buffer, 0, count)
                    crc.update(buffer, 0, count)
                }
            }
            require(size == file.size && crc.value == file.crc) { "备份文件完整性校验失败" }
            if (expectedEntry != null) {
                require(hex(digest.digest()) == expectedEntry.sha256) { "备份文件 SHA-256 校验失败" }
            }
        }
        return VerifiedBackupManifest(expected?.values?.toList(), kind)
    }

    private fun readManifest(input: InputStream, expectedSize: Long, expectedCrc: Long, onBlock: () -> Unit): String {
        val output = ByteArrayOutputStream()
        val crc = CRC32()
        val buffer = ByteArray(8192)
        while (true) {
            onBlock()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_MANIFEST_BYTES) { "备份清单过大" }
            crc.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        require(output.size().toLong() == expectedSize && crc.value == expectedCrc) { "备份清单完整性校验失败" }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun validateEntries(entries: List<BackupManifestEntry>) {
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) { "备份清单文件数量无效" }
        val names = mutableSetOf<String>()
        var total = 0L
        for (entry in entries) {
            validatePath(entry.path)
            require(entry.path != FILE_NAME && names.add(entry.path)) { "备份清单包含重复或保留路径" }
            require(entry.size in 0..MAX_ENTRY_BYTES && hashPattern.matches(entry.sha256)) { "备份清单校验信息无效" }
            total = Math.addExact(total, entry.size)
            require(total <= MAX_TOTAL_BYTES) { "备份总大小超过安全上限" }
        }
    }

    private fun validatePath(path: String) {
        require(
            path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                path.none { it.isISOControl() }
        ) { "备份包含不安全的文件路径" }
    }
}
