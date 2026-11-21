package me.rerere.rikkahub.data.sync

import java.security.SecureRandom
import java.util.Base64

/**
 * 新备份的恢复密钥：32 字节 CSPRNG 随机值，不以用户口令形式出现。
 * 本机由 Android Keystore 包裹保存；跨设备恢复时把它导出成独立文件，再在新机导入。
 * 文件属于敏感材料，不能和云备份放在同一个目录。
 */
object BackupRecoveryKey {
    const val HEADER = "RIKKAHUB-M RECOVERY KEY 1"
    private const val KEY_BYTES = 32

    fun generate(): CharArray {
        val bytes = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    fun encode(secret: CharArray): String = buildString {
        append(HEADER).append('\n')
        append(secret)
        append('\n')
    }

    /** 只接受本应用的密钥文件；格式不对直接失败，不做任何猜测。 */
    fun decode(text: String): CharArray {
        val lines = text.replace("\r\n", "\n").trim().lines()
        require(lines.firstOrNull() == HEADER) { "不是本应用的恢复密钥文件" }
        val value = lines.getOrNull(1)?.trim().orEmpty()
        val bytes = runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("恢复密钥内容损坏") }
        require(bytes.size == KEY_BYTES) { "恢复密钥长度不正确" }
        bytes.fill(0)
        require(value.length in 8..1024) { "恢复密钥长度不正确" }
        return value.toCharArray()
    }
}
