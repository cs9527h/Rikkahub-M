package me.rerere.rikkahub.data.files

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AvatarReferencesTest {
    @Test fun classifiesOnlyActualLocalAvatarFieldsBeforeMigration() {
        val shared = "file:///data/user/0/fixture/files/upload/shared.png"
        val assistant = Assistant(avatar = Avatar.Image(shared), background = shared)
        val backgroundOnly = Assistant(background = "file:///data/user/0/fixture/files/upload/background.png")
        val remote = Assistant(avatar = Avatar.Image("https://example.test/files/upload/remote.png"))
        val settings = Settings(
            displaySetting = DisplaySetting(userAvatar = Avatar.Image(shared)),
            assistants = listOf(assistant, backgroundOnly, remote),
        )
        assertEquals(setOf("upload/shared.png"), AvatarReferences.localAvatarPaths(settings))
        assertEquals(
            setOf("user", "assistant:${assistant.id}"),
            AvatarReferences.localAvatarOwners(settings)["upload/shared.png"]
        )
    }

    @Test fun recognizesAvatarsAndEscapedUnicodeNamesWithoutTouchingNetworkUris() {
        val value = File("/old/files/avatars/头像 #1.png").toURI().toASCIIString()
        assertEquals("avatars/头像 #1.png", AvatarReferences.relativePath(value))
        for (invalid in listOf(
            "https://example.test/files/avatars/a.png", "content://images/1",
            "file://host/app/files/avatars/a.png", "file:///app/files/avatars/a.png?x=1",
            "file:///app/files/avatars/a.png#x", "file:///app/files/avatars/%2e%2e/a.png",
            "file:///app/files/avatars/%5ca.png", "file:///app/files/avatars/nested/a.png",
            "file:///app/files/datastore/settings", "file:///etc/passwd",
        )) {
            assertNull(invalid, AvatarReferences.relativePath(invalid))
        }
    }

    @Test fun encodedEmbeddedReferencesAndObjectKeysAreProtected() {
        for (name in listOf("a b.png", "中文头像.png", "hash#question?.png")) {
            val relative = "avatars/$name"
            val uri = File("/data/user/0/x/files/$relative").toURI().toASCIIString()
            for (value in listOf(uri, "keep $uri unchanged", "keep ${uri.replace("/", "%2f")} unchanged")) {
                val text = kotlinx.serialization.json.JsonPrimitive(value)
                assertTrue(value, AvatarReferences.containsReference(text, relative))
                val keyed = kotlinx.serialization.json.JsonObject(
                    mapOf(value to kotlinx.serialization.json.JsonPrimitive(0))
                )
                assertTrue(value, AvatarReferences.containsReference(keyed, relative))
            }
        }
    }

    @Test fun classificationChecksActualAppRootAndAllowsOnlyKnownAndroidRootAlias() {
        val root = File("/data/user/0/current/files")
        val foreign =
            Settings(displaySetting = DisplaySetting(userAvatar = Avatar.Image("file:///other/files/upload/same.png")))
        assertTrue(AvatarReferences.localAvatarPaths(foreign, root).isEmpty())
        val local = foreign.copy(
            displaySetting = foreign.displaySetting.copy(
                userAvatar = Avatar.Image("file:///data/data/current/files/upload/same.png")
            )
        )
        assertEquals(setOf("upload/same.png"), AvatarReferences.localAvatarPaths(local, root))
    }

    @Test fun serializedToolPresetAndBackgroundReferencesAreConservativelyProtected() {
        val serialized = Json.parseToJsonElement("""{
            "background":"file:///data/user/0/x/files/upload/shared.png",
            "presetMessages":[{"parts":[{"output":[{"url":"file:///x/files/avatars/preset.png"}]}]}],
            "prompt":"keep file:///x/files/upload/text.png unchanged"
        }""")
        assertTrue(AvatarReferences.containsReference(serialized, "upload/shared.png"))
        assertTrue(AvatarReferences.containsReference(serialized, "avatars/preset.png"))
        assertTrue(AvatarReferences.containsReference(serialized, "upload/text.png"))
        assertFalse(AvatarReferences.containsReference(serialized, "avatars/other.png"))
    }
}
