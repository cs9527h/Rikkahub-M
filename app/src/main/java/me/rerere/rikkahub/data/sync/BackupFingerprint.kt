package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Content identity deliberately excludes ZIP timestamps, salts and operational backup counters. */
internal object BackupFingerprint {
    private val volatileStatus = setOf(
        "lastBackupTime",
        "lastAutoBackupTime",
        "lastAutoBackupError",
        "autoBackupStates",
    )

    fun calculate(kind: BackupKind, entries: List<BackupManifestEntry>, settingsJson: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            // Explicit length delimiters prevent ambiguous concatenation.
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        append("rikkahub-backup-content-v1")
        append(kind.wireName)
        for (entry in entries.sortedBy { it.path }) {
            append(entry.path)
            if (entry.path == "settings.json" && settingsJson != null) {
                val root = Json.parseToJsonElement(settingsJson) as? JsonObject
                    ?: error("设置备份不是 JSON 对象")
                val cleaned = (root - "launchCount").toMutableMap()
                (root["backupReminderConfig"] as? JsonObject)?.let { status ->
                    val meaningful = JsonObject(status.filterKeys { it !in volatileStatus })
                    if (meaningful.isEmpty()) {
                        cleaned.remove("backupReminderConfig")
                    } else {
                        cleaned["backupReminderConfig"] = meaningful
                    }
                }
                append(canonical(JsonObject(cleaned)))
            } else {
                append(entry.size.toString())
                append(entry.sha256)
            }
        }
        return "v1:" + BackupManifest.hex(digest.digest())
    }

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { (key, child) ->
            kotlinx.serialization.json.JsonPrimitive(key).toString() + ":" + canonical(child)
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}
