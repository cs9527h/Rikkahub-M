package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.MemoryPaths

/** Portable representation of the existing memory_file table, not a second memory store. */
internal data class MemoryBackupRecord(
    val assistantId: String,
    val path: String,
    val content: String,
    val updatedAt: Long,
)

internal object MemoryBackupCodec {
    const val FILE_NAME = "memory.json"
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_RECORDS = 100_000

    fun encode(records: List<MemoryBackupRecord>): String {
        validate(records)
        val encoded = buildJsonObject {
            put("formatVersion", 1)
            put("memories", buildJsonArray {
                for (record in records.sortedWith(compareBy({ it.assistantId }, { it.path }))) {
                    add(buildJsonObject {
                        put("assistantId", record.assistantId)
                        put("path", record.path)
                        put("content", record.content)
                        put("updatedAt", record.updatedAt)
                    })
                }
            })
        }.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "本地记忆过大，请使用完整数据库备份" }
        return encoded
    }

    fun decode(text: String): List<MemoryBackupRecord> {
        require(text.length <= MAX_BYTES) { "记忆备份超过安全上限" }
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["formatVersion"]?.jsonPrimitive?.int == 1) { "不支持的记忆备份版本" }
        val entries = root["memories"] as? JsonArray ?: error("记忆备份缺少数据列表")
        require(entries.size <= MAX_RECORDS) { "记忆条目数量超过安全上限" }
        val records = entries.map { value ->
            val record = value.jsonObject
            fun string(name: String): String {
                val field = record[name] as? JsonPrimitive
                require(field?.isString == true) { "记忆备份字段类型无效" }
                return field.content
            }
            MemoryBackupRecord(
                assistantId = string("assistantId"),
                path = requireNotNull(MemoryPaths.normalize(string("path"))) { "记忆文件路径无效" },
                content = string("content"),
                updatedAt = record.getValue("updatedAt").jsonPrimitive.long,
            )
        }
        validate(records)
        return records
    }

    private fun validate(records: List<MemoryBackupRecord>) {
        require(records.size <= MAX_RECORDS) { "记忆条目数量超过安全上限" }
        val keys = mutableSetOf<Pair<String, String>>()
        for (record in records) {
            require(
                record.assistantId.isNotBlank() && record.assistantId.length <= 128 &&
                    record.assistantId.none { it.isISOControl() }
            ) { "记忆助手标识无效" }
            require(MemoryPaths.normalize(record.path) == record.path) { "记忆文件路径无效" }
            require(record.updatedAt >= 0) { "记忆时间无效" }
            require(keys.add(record.assistantId to record.path)) { "记忆备份包含重复条目" }
        }
    }
}
