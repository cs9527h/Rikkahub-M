package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 原版 RikkaHub 备份布局的导入兼容性（不是高版本数据库向上游回退的保证）。
 *
 * 原版（rikkahub/rikkahub，DB schema 34）的备份就是一个普通 zip：
 * `settings.json` + `rikka_hub.db` + `upload/…` 等附件目录，**没有 manifest.json、也没有加密**。
 * 这里用同样的布局造包，验证我们的导入路径（`BackupManifest.verifyDetailed`）能接受它。
 */
class UpstreamBackupCompatTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("upstream_compat_", ".zip").also { it.deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `upstream full backup without manifest is accepted as legacy full`() {
        val archive = zipOf(
            "settings.json" to """{"assistants":[],"providers":[]}""".toByteArray(),
            DatabaseBackup.ARCHIVE_DATABASE to ByteArray(1024) { 1 },
            "upload/0001.png" to ByteArray(2048) { 2 },
            "media_creation/session/0001.png" to ByteArray(512) { 3 },
        )
        java.util.zip.ZipFile(archive).use { zip ->
            val verified = BackupManifest.verifyDetailed(zip)
            // 没有清单 = 旧格式：不按清单逐条比对，但文件仍然全量校验大小与 CRC
            assertNull("旧格式不应有清单条目", verified.entries)
            assertEquals(BackupKind.FULL, verified.kind)
        }
    }

    @Test
    fun `upstream settings-only backup is accepted`() {
        val archive = zipOf("settings.json" to """{"assistants":[{"id":"x"}],"providers":[]}""".toByteArray())
        java.util.zip.ZipFile(archive).use { zip ->
            val verified = BackupManifest.verifyDetailed(zip)
            assertNull(verified.entries)
            assertEquals(BackupKind.FULL, verified.kind)
        }
    }

    @Test
    fun `legacy archive folders stay unchanged and local avatars have a dedicated folder`() {
        // Existing import names stay intact; avatars is an intentional app layout extension.
        // AvatarStorageTest covers actual archive inclusion and restore staging of the new folder.
        val expected = setOf("upload", "skills", "fonts", "media_creation", "avatars")
        assertEquals(expected, BackupManager.ATTACHMENT_FOLDERS.toSet())
        assertEquals("rikka_hub.db", DatabaseBackup.ARCHIVE_DATABASE)
    }
}
