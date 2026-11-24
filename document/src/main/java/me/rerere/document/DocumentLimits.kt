package me.rerere.document

import java.io.InputStream

/**
 * 文档解析的硬上限。OOXML 是压缩包，一个几十 KB 的 docx 可以解压出几个 GB 的 XML
 * （压缩炸弹），解析器又要把文本攒成 String，所以必须在读入阶段就截断。
 */
internal object DocumentLimits {
    /** 单个 XML 入口允许解压出的最大字节数。 */
    const val MAX_XML_BYTES: Long = 32L * 1024 * 1024

    /** 解析结果允许的最大字符数，超出即截断并加标记。 */
    const val MAX_OUTPUT_CHARS: Int = 2_000_000
}

/** 读到超过 [limit] 字节时抛错，供 ZipInputStream 这类无法预知大小的入口使用。 */
internal class LimitedInputStream(
    private val delegate: InputStream,
    private val limit: Long,
) : InputStream() {
    private var consumed = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            consumed += 1
            checkLimit()
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) {
            consumed += read
            checkLimit()
        }
        return read
    }

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()

    private fun checkLimit() {
        require(consumed <= limit) { "Document content too large" }
    }
}
