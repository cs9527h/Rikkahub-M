package me.rerere.rikkahub.utils

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 纯解析逻辑，和网络、Android 无关，方便 JVM 单测。
 * GitHub REST 的字段名是 snake_case，必须显式映射；Atom 是不吃 API 限流的回退源。
 */
object UpdateParsing {

    private const val REPO = "cs9527h/Rikkahub-M"

    private val atomEntry = Regex("<entry>([\\s\\S]*?)</entry>")
    private val atomTag = Regex("<link[^>]*href=\"[^\"]*/tag/([^\"]+)\"")
    private val atomTitle = Regex("<title[^>]*>([\\s\\S]*?)</title>")
    private val atomContent = Regex("<content[^>]*>([\\s\\S]*?)</content>")
    private val atomUpdated = Regex("<updated>([^<]+)</updated>")

    fun githubReleases(json: Json, body: String): List<ReleaseNote> =
        json.decodeFromString(ListSerializer(GitHubRelease.serializer()), body)
            .filterNot { it.draft }
            .map { release ->
                val version = release.tag.removePrefix("v")
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ReleaseNote(
                    version = version,
                    tag = release.tag.ifEmpty { "v$version" },
                    title = release.name,
                    body = release.body,
                    publishedAt = release.publishedAt,
                    prerelease = release.prerelease || version.contains('-'),
                    apkUrl = apk?.url,
                    apkSize = apk?.size,
                    sha256 = apk?.digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                        ?.substringAfter(':')?.lowercase()?.takeIf { it.length == 64 },
                    pageUrl = release.htmlUrl.ifEmpty { "$REPO_URL/releases/tag/${release.tag}" },
                    source = "github-api",
                )
            }

    fun atomReleases(xml: String): List<ReleaseNote> = atomEntry.findAll(xml).mapNotNull { match ->
        val entry = match.groupValues[1]
        val tag = atomTag.find(entry)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
        val version = tag.removePrefix("v")
        ReleaseNote(
            version = version,
            tag = tag,
            title = atomTitle.find(entry)?.groupValues?.get(1)?.let(::unescapeXml).orEmpty(),
            body = atomContent.find(entry)?.groupValues?.get(1)?.let(::htmlToText).orEmpty(),
            publishedAt = atomUpdated.find(entry)?.groupValues?.get(1).orEmpty(),
            prerelease = version.contains('-'),
            pageUrl = "$REPO_URL/releases/tag/$tag",
            source = "github-atom",
        )
    }.toList()

    /**
     * 备用源 manifest：地址以版本号为准，而不是照抄 manifest 里的 apkUrl。
     * 线上 manifest 曾经把 0.7.0 指向 0.6.1 的 apk，照抄就会下错包。
     */
    fun manifest(json: Json, body: String, mirrorBase: String): List<ReleaseNote> {
        val manifest = json.decodeFromString(MirrorManifest.serializer(), body)
        val base = mirrorBase.trimEnd('/')
        return manifest.releases.map { note ->
            val expected = apkName(note.version)
            val declared = note.directUrl?.takeIf { it.isNotBlank() } ?: note.apkUrl
            val nameMatches = declared?.substringAfterLast('/')?.equals(expected, ignoreCase = true) == true
            // 文件名和版本对不上：宁可自己拼一个正确地址，也不下载错版本
            val rewritten = declared != null && declared.startsWith("http") && !nameMatches
            val resolved = when {
                declared.isNullOrBlank() -> "$base/$expected"
                !declared.startsWith("http") -> base + "/" + declared.trimStart('/')
                rewritten -> "$base/$expected"
                else -> declared
            }
            note.copy(
                source = "mirror",
                apkUrl = resolved,
                // 地址被改写后原来的大小就不可信了，只保留 manifest 明确给出的精确大小
                apkSize = if (rewritten) note.exactSize else (note.exactSize ?: note.apkSize),
            )
        }
    }

    /** 自动升级只认正式版本，预览版本只进更新日志。 */
    fun latestStable(notes: List<ReleaseNote>, currentVersion: String): ReleaseNote? =
        notes.filter { !it.prerelease && it.version.isNotBlank() }
            .maxByOrNull { Version(it.version) }
            ?.takeIf { Version(it.version) > Version(currentVersion) }

    fun apkName(version: String): String = "Rikkahub-M-${version.removePrefix("v")}.apk"

    fun htmlToText(html: String): String {
        // Atom 里的 content 是转义过的 HTML，要先还原再剥标签。
        val text = unescapeXml(html)
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<li>"), "\n- ")
            .replace(Regex("<[^>]+>"), "")
        return text.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private const val REPO_URL = "https://github.com/$REPO"
}
