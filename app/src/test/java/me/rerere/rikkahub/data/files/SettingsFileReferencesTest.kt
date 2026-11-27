package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsFileReferencesTest {
    private val relative = "avatars/头像 a#1.png"
    private val uri = File("/data/user/0/fixture/files/$relative").toURI().toASCIIString()

    @Test fun productionSettingsSerializersCoverAvatarBackgroundProviderPresetAndJsonKeys() {
        val nested = JsonObject(mapOf("nested" to JsonObject(mapOf("keep $uri unchanged" to JsonPrimitive(0)))))
        val preset = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "read",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = uri)),
            metadata = nested,
        )))
        // Decode the real sealed provider serializer, including its production defaults.
        val provider = JsonInstant.decodeFromJsonElement(ProviderSetting.serializer(), JsonObject(mapOf(
            "type" to JsonPrimitive("openai"),
            "customHeaders" to JsonInstant.encodeToJsonElement(listOf(CustomHeader("asset", uri))),
        )))
        val cases = listOf(
            Settings(displaySetting = DisplaySetting(userAvatar = Avatar.Image(uri))),
            Settings(assistants = listOf(Assistant(avatar = Avatar.Image(uri)))),
            Settings(assistants = listOf(Assistant(background = uri))),
            Settings(assistants = listOf(Assistant(systemPrompt = "keep $uri unchanged"))),
            Settings(assistants = listOf(Assistant(presetMessages = listOf(preset)))),
            Settings(assistants = listOf(Assistant(customBodies = listOf(CustomBody("custom", nested))))),
            Settings(providers = listOf(provider)),
        )
        cases.forEach { settings ->
            assertTrue(AvatarReferences.settingsContainReference(settings, JsonInstant, relative))
            assertEquals(
                AvatarReferences.containsReference(JsonInstant.encodeToJsonElement(settings), relative),
                AvatarReferences.settingsContainReference(settings, JsonInstant, relative)
            )
            assertFalse(
                AvatarReferences.settingsContainReference(settings, JsonInstant, "avatars/absent-unique-image.png")
            )
        }
    }

    @Test fun ordinaryAndDefaultSettingsDoNotDegenerateIntoUnknownEvidence() {
        for (format in listOf(Json, JsonInstant)) {
            assertFalse(AvatarReferences.settingsContainReference(Settings(), format, relative))
            assertFalse(AvatarReferences.settingsContainReference(Settings(
                assistants = listOf(Assistant(systemPrompt = "ordinary text")),
                providers = listOf(format.decodeFromString(ProviderSetting.serializer(), """{"type":"openai"}""")),
            ), format, relative))
        }
    }

    @Test fun largeRealPromptIsScannedWithoutEncodingAWholeSettingsDocument() {
        val prefix = "large ordinary prompt ".repeat(200_000)
        val settings = Settings(assistants = listOf(Assistant(systemPrompt = "$prefix keep $uri unchanged")))
        assertTrue(AvatarReferences.settingsContainReference(settings, JsonInstant, relative))
        assertFalse(AvatarReferences.settingsContainReference(settings, JsonInstant, "avatars/not-present.png"))
        assertEquals(prefix.length + " keep $uri unchanged".length, settings.assistants.single().systemPrompt.length)
    }

    @Test fun unknownSerializerRetainsFilesAndCancellationStillEscapes() {
        val unsupported = object : SerializationStrategy<String> {
            override val descriptor = PrimitiveSerialDescriptor("UnsupportedFileReference", PrimitiveKind.STRING)
            override fun serialize(encoder: Encoder, value: String) {
                error("concrete JSON encoder required")
            }
        }
        assertTrue(serializedValueContainsFileReference(unsupported, "unreadable", JsonInstant, relative))
        val cancelled = object : SerializationStrategy<String> {
            override val descriptor = PrimitiveSerialDescriptor("CancelledFileReference", PrimitiveKind.STRING)
            override fun serialize(encoder: Encoder, value: String) {
                throw CancellationException("cancel")
            }
        }
        assertThrows(CancellationException::class.java) {
            serializedValueContainsFileReference(cancelled, "unreadable", JsonInstant, relative)
        }
    }
}
