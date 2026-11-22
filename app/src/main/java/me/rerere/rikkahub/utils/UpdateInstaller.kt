package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.content.FileProvider
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 应用内更新下载与校验。
 * 优先级：SHA-256 → 包名 → 版本号（必须更高）→ 与已安装应用相同的签名证书。
 * 校验全部通过后才交给系统安装器，用户仍需在系统弹窗里确认，绝不静默安装。
 */
class InstallPermissionRequiredException(val apk: File) :
    IllegalStateException("系统尚未允许 Rikkahub-M 安装应用")

/** 用户主动取消下载：已下载的部分会被删除，不留半包。 */
class UpdateCancelledException : IllegalStateException("已取消下载")

object UpdateInstaller {

    private const val UPDATE_DIR = "updates"
    private const val UPDATE_FILE = "Rikkahub-M-update.apk"

    suspend fun downloadAndVerify(
        context: Context,
        client: OkHttpClient,
        url: String,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onVerifying: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<File> = runCatching {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        val target = File(dir, UPDATE_FILE)
        if (target.exists()) target.delete()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Rikkahub-M/${BuildConfig.VERSION_NAME}")
            .build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateHttpException(response.code, "")
            val body = response.body
            val total = body.contentLength()
            var read = 0L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw UpdateCancelledException()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
            if (total > 0 && read != total) throw UpdateCheckException("下载不完整（$read/$total 字节）")
        }

        if (isCancelled()) throw UpdateCancelledException()
        onVerifying()

        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = expectedSha256?.lowercase()?.replace(":", "")?.trim()
        if (!expected.isNullOrEmpty() && sha256 != expected) {
            throw UpdateCheckException("SHA-256 不匹配，已丢弃下载文件")
        }

        verifyArchive(context, target)
        target
    }.onFailure {
        File(File(context.cacheDir, UPDATE_DIR), UPDATE_FILE).takeIf { it.exists() }?.delete()
    }

    private fun verifyArchive(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw UpdateCheckException("不是有效的 APK")
        if (archive.packageName != context.packageName) {
            throw UpdateCheckException("包名不符：${archive.packageName}")
        }
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveCode = archive.longVersionCodeCompat()
        val installedCode = installed.longVersionCodeCompat()
        if (archiveCode <= installedCode) {
            throw UpdateCheckException("版本号未递增（$archiveCode ≤ $installedCode）")
        }
        val archiveCert = archive.certSha256()
        val installedCert = installed.certSha256()
        if (archiveCert == null || installedCert == null || archiveCert != installedCert) {
            throw UpdateCheckException("签名证书与已安装版本不一致，拒绝安装")
        }
    }

    /** Android 8 起，覆盖安装必须由用户为本应用单独打开「安装未知应用」。 */
    fun canRequestPackageInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 带包名的授权页；个别 ROM 不认包名参数时退回应用列表页。 */
    fun openInstallPermissionSettings(context: Context) {
        val withPackage = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(withPackage) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun startSystemInstall(context: Context, apk: File) {
        if (!canRequestPackageInstalls(context)) throw InstallPermissionRequiredException(apk)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    private fun android.content.pm.PackageInfo.certSha256(): String? {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") signatures
        } ?: return null
        val first = signers.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
