package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 真机反馈的两个故障都在这层：字段映射错、403 后没有回退。这里用合成数据锁住解析行为。 */
class UpdateParsingTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val githubFixture = """
        [
          {
            "tag_name": "v0.4.3",
            "name": "Rikkahub-M 0.4.3",
            "body": "## 修复\n- 403 回退",
            "published_at": "2026-11-21T10:00:00Z",
            "prerelease": false,
            "draft": false,
            "html_url": "https://github.com/cs9527h/Rikkahub-M/releases/tag/v0.4.3",
            "assets": [
              {"name": "Rikkahub-M-0.4.3.apk", "size": 19341771, "digest": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "browser_download_url": "https://github.com/cs9527h/Rikkahub-M/releases/download/v0.4.3/Rikkahub-M-0.4.3.apk"},
              {"name": "SHA256SUMS.txt", "size": 87, "browser_download_url": "https://example.invalid/SHA256SUMS.txt"}
            ]
          },
          {
            "tag_name": "v0.4.4-preview.1",
            "name": "preview",
            "body": "",
            "published_at": "2026-11-22T10:00:00Z",
            "prerelease": true,
            "draft": false,
            "assets": []
          }
        ]
    """.trimIndent()

    private val atomFixture = """
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Release notes from Rikkahub-M</title>
          <entry>
            <updated>2026-11-21T10:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/cs9527h/Rikkahub-M/releases/tag/v0.4.3"/>
            <title>Rikkahub-M 0.4.3</title>
            <content type="html">&lt;p&gt;修复 403 回退&lt;/p&gt;&lt;p&gt;校验签名&lt;/p&gt;</content>
          </entry>
          <entry>
            <updated>2026-11-20T10:00:00Z</updated>
            <link rel="alternate" type="text/html" href="https://github.com/cs9527h/Rikkahub-M/releases/tag/v0.4.1-preview.1"/>
            <title>preview</title>
            <content type="html">&lt;p&gt;预览说明&lt;/p&gt;</content>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun githubSnakeCaseFieldsAreMapped() {
        val notes = UpdateParsing.githubReleases(json, githubFixture)
        assertEquals(2, notes.size)
        val latest = notes.first()
        assertEquals("0.4.3", latest.version)
        assertEquals("v0.4.3", latest.tag)
        assertEquals("2026-11-21T10:00:00Z", latest.publishedAt)
        assertTrue(latest.body.contains("403"))
        assertEquals(19341771L, latest.apkSize)
        assertEquals(
            "https://github.com/cs9527h/Rikkahub-M/releases/download/v0.4.3/Rikkahub-M-0.4.3.apk",
            latest.apkUrl,
        )
        assertTrue(notes[1].prerelease)
    }

    @Test
    fun atomFeedParsesWithoutApiAccess() {
        val notes = UpdateParsing.atomReleases(atomFixture)
        assertEquals(2, notes.size)
        assertEquals("0.4.3", notes[0].version)
        assertTrue(notes[0].body.contains("修复 403 回退"))
        assertTrue(notes[0].body.contains("校验签名"))
        assertTrue(notes[1].prerelease)
        assertEquals("https://github.com/cs9527h/Rikkahub-M/releases/tag/v0.4.3", notes[0].pageUrl)
    }

    @Test
    fun mirrorManifestFillsApkUrlAndSha() {
        val manifest = """
            {"format":1,"updatedAt":"2026-11-21","releases":[
              {"version":"0.4.3","tag":"v0.4.3","body":"说明","sha256":"ABC","apkSize":19341771}
            ]}
        """.trimIndent()
        val notes = UpdateParsing.manifest(json, manifest, "https://8.133.167.195/updates")
        assertEquals(1, notes.size)
        assertEquals("https://8.133.167.195/updates/Rikkahub-M-0.4.3.apk", notes[0].apkUrl)
        assertEquals("mirror", notes[0].source)
        assertEquals("ABC", notes[0].sha256)
    }

    @Test
    fun githubDigestBecomesSha256() {
        val notes = UpdateParsing.githubReleases(json, githubFixture)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", notes.first().sha256)
    }

    @Test
    fun mirrorManifestRewritesMismatchedVersionAndDropsBadSize() {
        // 线上事故复现：0.7.0 条目指向 0.6.1 的安装包，大小也是 0.6.1 的，但哈希是 0.7.0 的
        val manifest = """
            {"format":1,"releases":[{
              "version":"0.7.0","tag":"v0.7.0","body":"说明",
              "apkUrl":"https://8.133.167.195/updates/Rikkahub-M-0.6.1.apk",
              "apkSize":19348074,
              "sha256":"19f6f5a1b1303436661f65ca4c3f8db8545d6c84defb2678a2baa03a8f8da050"
            }]}
        """.trimIndent()
        val note = UpdateParsing.manifest(json, manifest, "https://8.133.167.195/updates").single()
        assertEquals("https://8.133.167.195/updates/Rikkahub-M-0.7.0.apk", note.apkUrl)
        assertNull("地址被改写后原大小不可信", note.apkSize)
        assertEquals("19f6f5a1b1303436661f65ca4c3f8db8545d6c84defb2678a2baa03a8f8da050", note.sha256)
    }

    @Test
    fun mirrorManifestAcceptsAliasUrlAndSize() {
        val manifest = """
            {"format":1,"releases":[{
              "version":"0.7.0",
              "url":"https://8.133.167.195/updates/Rikkahub-M-0.7.0.apk",
              "size":19405390,
              "sha256":"19f6f5a1b1303436661f65ca4c3f8db8545d6c84defb2678a2baa03a8f8da050"
            }]}
        """.trimIndent()
        val note = UpdateParsing.manifest(json, manifest, "https://8.133.167.195/updates").single()
        assertEquals("https://8.133.167.195/updates/Rikkahub-M-0.7.0.apk", note.apkUrl)
        assertEquals(19405390L, note.apkSize)
    }

    @Test
    fun autoUpdatePicksOnlyStableAndNewer() {
        val notes = UpdateParsing.githubReleases(json, githubFixture)
        assertEquals("0.4.3", UpdateParsing.latestStable(notes, "0.4.2")?.version)
        assertNull("已是最高版本时不应提示更新", UpdateParsing.latestStable(notes, "0.4.3"))
        assertNull("只有预览版本时不提示升级", UpdateParsing.latestStable(notes.filter { it.prerelease }, "0.4.0"))
    }

    @Test
    fun bundledNotesAlwaysContainCurrentVersion() {
        val asset = java.io.File("src/main/assets/updates.json")
        assertTrue("内置更新说明缺失：${asset.absolutePath}", asset.exists())
        val notes = UpdateParsing.manifest(json, asset.readText(), "https://example.invalid")
        val current = me.rerere.rikkahub.BuildConfig.VERSION_NAME
        assertTrue("内置说明必须包含当前版本 $current", notes.any { it.version == current })
    }

    @Test
    fun versionCompareIsSemverNotStringCompare() {
        assertTrue(Version("0.4.10") > Version("0.4.9"))
        assertTrue(Version("0.4.3") > Version("0.4.2"))
        assertTrue(Version("0.4.3") > Version("0.4.3-preview.1"))
    }
}
