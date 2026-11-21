package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable

@Serializable
data class AutoBackupState(
    val lastCheckedAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val fingerprint: String = "",
    val destinationId: String = "",
)

/** Content checks run at separate cadences; no state key contains a URL or credential. */
internal object AutoBackupPolicy {
    const val SETTINGS_HOURS = 6
    const val CHATS_HOURS = 72
    private const val HOUR_MILLIS = 60L * 60 * 1000

    fun key(transport: String, kind: BackupKind): String {
        require(transport == "webdav" || transport == "s3")
        require(kind == BackupKind.SETTINGS || kind == BackupKind.CHATS)
        return "${transport}_${kind.wireName}"
    }

    fun isDue(state: AutoBackupState?, kind: BackupKind, now: Long, hours: Int): Boolean {
        require(kind != BackupKind.FULL && hours in 1..24 * 30 && now >= 0)
        val checked = state?.lastCheckedAt ?: 0
        return checked <= 0 || now < checked || now - checked >= hours * HOUR_MILLIS
    }

    fun destinationId(transport: String, vararg fields: String): String {
        require(transport == "webdav" || transport == "s3")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (value in listOf(transport) + fields) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return "v1:" + BackupManifest.hex(digest.digest())
    }

    fun unchanged(state: AutoBackupState?, fingerprint: String, destinationId: String): Boolean =
        state?.fingerprint?.isNotBlank() == true && state.fingerprint == fingerprint &&
            state.destinationId.isNotBlank() && state.destinationId == destinationId && state.lastSuccessAt > 0

    fun checked(state: AutoBackupState?, now: Long): AutoBackupState =
        (state ?: AutoBackupState()).copy(lastCheckedAt = now)

    fun uploaded(state: AutoBackupState?, now: Long, fingerprint: String, destinationId: String): AutoBackupState {
        require(fingerprint.matches(Regex("v1:[a-f0-9]{64}")))
        require(destinationId.matches(Regex("v1:[a-f0-9]{64}")))
        return (state ?: AutoBackupState()).copy(
            lastCheckedAt = now,
            lastSuccessAt = now,
            fingerprint = fingerprint,
            destinationId = destinationId,
        )
    }
}
