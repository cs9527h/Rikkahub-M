package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.URI

class BackupFileUrisTest {
    private val folders = setOf("upload", "skills", "fonts", "media_creation")

    @Test fun managedFileCanBeMappedAcrossPackagesAndAndroidUsers() {
        val old = "file:///data/user/10/com.example.old/files/upload/avatar.png"
        val target = File("/data/user/0/com.rikkahub.mate/files")
        assertEquals("upload/avatar.png", BackupFileUris.relativePath(old, folders))
        assertEquals(
            "/data/user/0/com.rikkahub.mate/files/upload/avatar.png",
            URI(BackupFileUris.restoreUri(old, target, folders)).path
        )
    }

    @Test fun compatExportRelocatesAssetsToUpstreamPackageWithoutTouchingOtherProjects() {
        // 「兼容原版」导出：把我们的头像路径改写成原版包名的 files 目录，原版恢复时才找得到
        val ours = "file:///data/user/0/com.rikkahub.mate/files/upload/avatar.png"
        assertEquals(
            "file:///data/user/0/me.rerere.rikkahub/files/upload/avatar.png",
            BackupFileUris.relocateTo(ours, "/data/user/0/me.rerere.rikkahub/files", folders),
        )
        // 非托管引用（网络图、content URI）保持原样，避免把用户的数据改坏
        assertEquals(
            "https://example.com/avatar.png",
            BackupFileUris.relocateTo("https://example.com/avatar.png", "/data/user/0/x/files", folders),
        )
    }

    @Test fun unicodeSpacesAndUrlEscapingRoundTripWithoutChangingTheFileName() {
        val original = File("/synthetic/old/files/upload/头像 #1.png").toURI().toASCIIString()
        val restored = BackupFileUris.restoreUri(original, File("/synthetic/new/files"), folders)
        assertEquals("/synthetic/new/files/upload/头像 #1.png", URI(restored).path)
    }

    @Test fun remoteImagesAndContentUrisAreNeverRewritten() {
        for (value in listOf("https://example.com/avatar.png", "content://images/123", "data:image/png;base64,AAAA")) {
            assertEquals(value, BackupFileUris.restoreUri(value, File("/new/files"), folders))
        }
    }

    @Test fun traversalBackslashesAndUnmanagedFoldersAreNotAccepted() {
        for (value in listOf(
            "file:///old/files/upload/../secrets",
            "file:///old/files/upload/%2e%2e/secrets",
            "file:///old/files/upload/%5cescape",
            "file:///old/files/no_backup/passphrase.bin",
            "file:///etc/passwd",
        )) {
            assertNull(BackupFileUris.relativePath(value, folders))
        }
    }

    @Test fun fileUriAuthoritiesQueriesAndFragmentsAreNotAccepted() {
        for (value in listOf(
            "file://remote/old/files/upload/a",
            "file:///old/files/upload/a?x=1",
            "file:///old/files/upload/a#fragment"
        )) {
            assertNull(BackupFileUris.relativePath(value, folders))
        }
    }

    @Test fun nestedSkillAndMediaPathsArePreserved() {
        assertEquals(
            "skills/demo/SKILL.md",
            BackupFileUris.relativePath("file:///old/files/skills/demo/SKILL.md", folders)
        )
        assertEquals(
            "media_creation/session/output.png",
            BackupFileUris.relativePath("file:///old/files/media_creation/session/output.png", folders)
        )
    }

    @Test fun plainTextAndPromptsAreNotTreatedAsFileReferences() {
        val prompt = "Keep this exact synthetic text: file:///old/files/upload/avatar.png"
        assertEquals(prompt, BackupFileUris.restoreUri(prompt, File("/new/files"), folders))
    }
}
