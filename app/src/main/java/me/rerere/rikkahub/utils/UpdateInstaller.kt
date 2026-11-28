package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.common.util.runCatchingCancellable
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/**
 * 应用内更新下载与校验。
 *
 * 下载委托给 [UpdateDownloadEngine]：多来源、退避重试、Range 断点续传、大小与 SHA-256 校验；
 * 这里只负责 Android 侧的安装校验（包名、versionCode、签名证书）与系统安装器入口。
 * 全部校验通过后交给系统安装器，用户仍需在系统弹窗确认，绝不静默安装。
 */
class InstallPermissionRequiredException(val apk: File) :
    IllegalStateException("系统尚未允许 Rikkahub-M 安装应用")

/** 用户主动取消下载：保留已下载部分，下次可续传。 */
class UpdateCancelledException : IllegalStateException("已取消下载")

/** 一次下载来源（GitHub 正式附件 / 已验证的备用源）。 */
data class UpdateSource(val label: String, val url: String)

object UpdateInstaller {

    private const val UPDATE_DIR = "updates"
    private const val UPDATE_FILE = "Rikkahub-M-update.apk"

    /**
     * 下载 + 安装前校验。整段（下载、哈希、PackageManager 解析 APK、签名比对）都在
     * Dispatchers.IO 上执行；调用方即使从主线程发起，也不会把阻塞调用留在 Main。
     * 返回 Result 而不是抛异常：调用方只关心「成功拿到可安装的 APK」或「失败原因」。
     */
    suspend fun downloadAndVerify(
        context: Context,
        client: OkHttpClient,
        sources: List<UpdateSource>,
        expectedSha256: String?,
        expectedSize: Long? = null,
        version: String? = null,
        onProgress: (read: Long, total: Long, source: String) -> Unit = { _, _, _ -> },
        onVerifying: (source: String) -> Unit = {},
        onStage: (UpdateStage) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            val apk = UpdateDownloadEngine.download(
                client = client,
                sources = sources,
                target = File(dir, UPDATE_FILE),
                expectedSha256 = expectedSha256,
                expectedSize = expectedSize,
                version = version,
                onProgress = onProgress,
                onVerifying = onVerifying,
                onStage = onStage,
                isCancelled = isCancelled,
            )
            onStage(UpdateStage.VERIFY_ARCHIVE)
            try {
                verifyArchive(context, apk)
            } catch (e: UpdateCheckException) {
                // 包名/版本/签名不对的包不可复用，删除后让用户重试或换浏览器下载
                apk.delete()
                throw e
            }
            apk
        }
    }

    /**
     * 解析 APK 的 manifest 与签名：PackageManager 会真的去读 zip，属于阻塞 IO。
     * 只能在 [downloadAndVerify] 的 IO 段里调用，不要挪到别的线程上下文。
     */
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
        val withPackage = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatchingCancellable { context.startActivity(withPackage) }.onFailure {
            runCatchingCancellable {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * 交给系统安装器。**故意保持同步、由主线程调用**：它只做 FileProvider 路径解析与
     * startActivity，没有任何网络或大文件读写；新增任何阻塞逻辑都必须先切到 IO，
     * 否则会重新踩到 StrictMode 的主线程策略。
     */
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
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun android.content.pm.PackageInfo.certSha256(): String? {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            signatures
        } ?: return null
        val first = signers.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
