package me.rerere.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.modules.SerializersModule
import me.rerere.rikkahub.data.datastore.Settings

/** Visits production serializers without materializing a second Settings JSON tree or string. */
internal fun settingsContainFileReference(settings: Settings, json: Json, relativePath: String): Boolean =
    serializedValueContainsFileReference(Settings.serializer(), settings, json, relativePath)

@OptIn(ExperimentalSerializationApi::class, SealedSerializationApi::class)
internal fun <T> serializedValueContainsFileReference(
    serializer: SerializationStrategy<T>,
    value: T,
    json: Json,
    relativePath: String,
): Boolean {
    val matcher = LocalFileReferenceMatcher(relativePath)
    val format = json
    val encoder = object : AbstractEncoder(), JsonEncoder {
        override val serializersModule: SerializersModule = format.serializersModule
        override val json: Json = format
        var found = false
        private var depth = 0

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            check(++depth <= 128) { "Settings reference nesting limit" }
            return this
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            depth--
        }

        override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
            if (!found && matcher.matches(descriptor.getElementName(index))) found = true
            return !found
        }

        // Scanning default fields too is conservative and avoids depending on omission policy.
        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = true

        override fun encodeString(value: String) {
            if (!found) found = matcher.matches(value)
        }

        override fun encodeJsonElement(element: JsonElement) {
            if (!found) found = matcher.contains(element)
        }

        // Numbers/booleans/chars/enums do not carry file URLs. String/map-key serializers still
        // call encodeString; unsupported serializers throw and the caller retains the file.
        override fun encodeValue(value: Any) = Unit
        override fun encodeNull() = Unit
    }
    return try {
        serializer.serialize(encoder, value)
        encoder.found
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A serializer that requires a concrete JSON implementation is unknown evidence, not absence.
        true
    }
}
