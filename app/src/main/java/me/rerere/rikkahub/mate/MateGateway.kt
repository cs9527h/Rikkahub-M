package me.rerere.rikkahub.mate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phase-one integration with the existing Mate protocol-v3 gateway.
 * This is NOT an OpenAI-compatible provider and must not be offered as one.
 * Secrets must never be embedded in the APK, logs, QR images or repository.
 */
internal data class MateConnection(
    val baseUrl: String,
    val token: String,
    val instanceId: String
)

internal object MateGatewayAddress {
    fun normalize(value: String): String {
        val candidate = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请输入完整的 HTTPS 网关地址")
        require(candidate.scheme == "https" && candidate.host.isNotBlank() &&
            candidate.username.isEmpty() && candidate.password.isEmpty() &&
            candidate.encodedPath == "/" && candidate.query == null && candidate.fragment == null) {
            "只接受不含账号、路径和查询参数的 HTTPS 网关基础地址"
        }
        return candidate.toString().trimEnd('/')
    }
}

internal class MateGatewayClient {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun pair(address: String, code: String, label: String): MateConnection =
        withContext(Dispatchers.IO) {
            val base = MateGatewayAddress.normalize(address)
            val pairingCode = code.trim()
            val deviceLabel = label.trim()
            require(pairingCode.length in 24..80) { "配对码格式不正确" }
            require(deviceLabel.length in 1..40 && deviceLabel.none { it.code < 32 }) {
                "设备名称需要 1 至 40 个字符"
            }
            val result = request(base, "/pair", null,
                JSONObject().put("code", pairingCode).put("label", deviceLabel))
            val token = result.optString("token")
            val instance = result.optString("instance_id")
            require(token.length >= 32 && instance.matches(Regex("[0-9a-f]{32}"))) {
                "配对响应无效；未保存任何凭据"
            }
            MateConnection(base, token, instance).also { verifyBlocking(it) }
        }

    suspend fun verify(connection: MateConnection): Unit = withContext(Dispatchers.IO) {
        verifyBlocking(connection)
    }

    private fun verifyBlocking(connection: MateConnection) {
        val result = request(connection.baseUrl, "/capabilities", connection.token, null)
        require(result.optInt("protocol") == 3 &&
            result.optString("instance_id") == connection.instanceId) {
            "网关协议或实例标识不匹配，已拒绝连接"
        }
    }

    private fun request(base: String, path: String, token: String?, data: JSONObject?): JSONObject {
        val builder = Request.Builder().url(base + path)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer " + token)
        if (data != null) builder.post(
            data.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        )
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("网关返回 HTTP " + response.code + "，请检查配对码或服务端状态")
            }
            return readBoundedJson(response)
        }
    }

    private fun readBoundedJson(response: Response): JSONObject {
        val input = response.body?.byteStream() ?: throw IOException("网关未返回数据")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (output.size() + size > 65_536) throw IOException("网关响应超过安全上限")
            output.write(buffer, 0, size)
        }
        return try {
            JSONObject(output.toString("UTF-8"))
        } catch (_: Exception) {
            throw IOException("网关返回的不是有效 JSON")
        }
    }
}

/** AES-GCM secret storage. Only the non-secret server address is plaintext. */
internal class MateGatewayStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("mate_gateway_connection", Context.MODE_PRIVATE)

    fun savedInstanceId(): String = preferences.getString("instance_id", "").orEmpty()

    fun savedAddress(): String = preferences.getString("base_url", "").orEmpty()

    fun load(): MateConnection? {
        val address = savedAddress()
        val instance = preferences.getString("instance_id", "").orEmpty()
        val encrypted = preferences.getString("device_token", "").orEmpty()
        if (address.isBlank() || instance.isBlank() || encrypted.isBlank()) return null
        val token = try { decrypt(encrypted) } catch (_: Exception) { return null }
        return MateConnection(address, token, instance)
    }

    fun save(connection: MateConnection) {
        val ciphertext = encrypt(connection.token)
        check(preferences.edit()
            .putString("base_url", connection.baseUrl)
            .putString("instance_id", connection.instanceId)
            .putString("device_token", ciphertext)
            .commit()) { "本机授权凭据保存失败" }
    }

    /** Only forgets local access. The server device remains authorized until revoked there. */
    fun forgetLocal() {
        check(preferences.edit().clear().commit()) { "本机授权凭据移除失败" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val previous = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (previous != null) return previous
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
    }

    private fun decrypt(encoded: String): String {
        val value = Base64.decode(encoded, Base64.NO_WRAP)
        require(value.size > 12 + 16) { "无效的加密凭据" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        return String(cipher.doFinal(value.copyOfRange(12, value.size)), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "mate.gateway.device.aes.v1"
    }
}
