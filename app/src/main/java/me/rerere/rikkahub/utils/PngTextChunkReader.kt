package me.rerere.rikkahub.utils

import java.io.InputStream

/**
 * 只做一件事的最小 PNG 文本块读取器：从 PNG 里取出 tEXt / iTXt 的文本内容，
 * 用于稀有角色卡（SillyTavern 一类）把卡数据塞在 PNG 文本块里的场景。
 *
 * 之前这里用 `com.drew:metadata-extractor`，但本项目只用它读 PNG 文本块，
 * 为这点功能背一个完整元数据库（dex + 解析器）不划算。
 */
object PngTextChunkReader {
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A
    )

    /** 单块上限：8 MB，避免畸形文件把内存吃满。 */
    private const val MAX_CHUNK_BYTES = 8 * 1024 * 1024

    /** 所有文本块合计上限：一条 8 MB 的 tEXt 不算问题，几百条加起来就能把堆打爆。 */
    private const val MAX_TOTAL_TEXT_CHARS = 4 * 1024 * 1024

    /** 读取所有文本块内容（按文件顺序）。非 PNG 或没有文本块时返回空列表。 */
    fun readTextChunks(input: InputStream): List<String> {
        val header = ByteArray(8)
        if (!readFully(input, header) || !header.contentEquals(PNG_SIGNATURE)) return emptyList()

        val texts = mutableListOf<String>()
        var totalChars = 0
        val lengthBytes = ByteArray(4)
        val typeBytes = ByteArray(4)
        val crcBytes = ByteArray(4)
        while (true) {
            if (!readFully(input, lengthBytes)) break
            val length = readInt(lengthBytes)
            if (length < 0) break
            if (!readFully(input, typeBytes)) break
            val type = String(typeBytes, Charsets.US_ASCII)
            if (length > MAX_CHUNK_BYTES) {
                // 大图的 IDAT 通常有几十 MB，它只是像素数据：跳过去继续找后面的文本块，
                // 不能因为一块超大就结束整个扫描，否则 4K 角色卡的卡数据永远读不到。
                if (type == "IEND") break
                if (!skipFully(input, length.toLong() + 4L)) break
                continue
            }
            val data = ByteArray(length)
            if (!readFully(input, data)) break
            // crc 跳过：这里只读文本，不校验完整性
            if (!readFully(input, crcBytes)) break

            val text = when (type) {
                "tEXt" -> parseTextChunk(data)
                "iTXt" -> parseInternationalTextChunk(data)
                else -> null
            }
            if (text != null) {
                texts.add(text)
                totalChars += text.length
                if (totalChars >= MAX_TOTAL_TEXT_CHARS) break
            }
            if (type == "IEND") break
        }
        return texts
    }

    /** tEXt: keyword\0text */
    private fun parseTextChunk(data: ByteArray): String? {
        val separator = data.indexOf(0)
        if (separator <= 0) return null
        return String(data, separator + 1, data.size - separator - 1, Charsets.ISO_8859_1)
    }

    /** iTXt: keyword\0 compressionFlag(1) compressionMethod(1) languageTag\0 translatedKeyword\0 text */
    private fun parseInternationalTextChunk(data: ByteArray): String? {
        val keywordEnd = data.indexOf(0)
        if (keywordEnd <= 0 || keywordEnd + 3 > data.size) return null
        val compressed = data[keywordEnd + 1].toInt() != 0
        if (compressed) return null // 压缩文本块不支持，交给上层当作没有
        var cursor = keywordEnd + 3
        val languageEnd = data.indexOf(0, cursor)
        if (languageEnd < 0) return null
        cursor = languageEnd + 1
        val translatedEnd = data.indexOf(0, cursor)
        if (translatedEnd < 0) return null
        cursor = translatedEnd + 1
        if (cursor >= data.size) return null
        return String(data, cursor, data.size - cursor, Charsets.UTF_8)
    }

    private fun ByteArray.indexOf(value: Byte, from: Int = 0): Int {
        for (index in from until size) {
            if (this[index] == value) return index
        }
        return -1
    }

    private fun ByteArray.indexOf(value: Int, from: Int = 0) = indexOf(value.toByte(), from)

    private fun readInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    /** 跳过 [count] 字节，用于略过不关心的大块，避免为它们分配内存。 */
    private fun skipFully(input: InputStream, count: Long): Boolean {
        var remaining = count
        val scratch = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = runCatching {
                input.skip(remaining)
            }.getOrDefault(0L)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (read <= 0) return false
            remaining -= read
        }
        return true
    }
}
